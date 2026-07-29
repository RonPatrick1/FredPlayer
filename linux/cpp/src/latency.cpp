#include "fredplayer/latency.hpp"

#include <gst/app/gstappsink.h>
#include <gst/app/gstappsrc.h>
#include <gst/audio/gstaudiobasesink.h>
#include <gst/gst.h>
#include <fftw3.h>
#include <nlohmann/json.hpp>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <memory>
#include <stdexcept>
#include <thread>

namespace fredplayer {
namespace {
using json = nlohmann::json;
constexpr int kRate = 48000;
constexpr double kPi = 3.14159265358979323846;

json commandJson(const char* command) {
  FILE* pipe = popen(command, "r");
  if (!pipe) return {};
  std::string output; std::array<char, 4096> buffer{};
  while (fgets(buffer.data(), buffer.size(), pipe)) output += buffer.data();
  pclose(pipe);
  try { return json::parse(output); } catch (...) { return {}; }
}

GstElement* make(const char* factory, const char* name) {
  auto* element = gst_element_factory_make(factory, name);
  if (!element) throw std::runtime_error(std::string("Missing GStreamer element: ") + factory);
  return element;
}

GstElement* outputSink(const AudioOutput& output, const char* name) {
  auto* sink = gst_element_factory_make("pulsesink", name);
  if (sink && !output.key.empty()) g_object_set(sink, "device", output.key.c_str(), nullptr);
  if (!sink) sink = make("autoaudiosink", name);
  return sink;
}

std::vector<float> chirp() {
  const int count = static_cast<int>(.30 * kRate);
  std::vector<float> result(count);
  double phase = 0;
  for (int i = 0; i < count; ++i) {
    const auto t = static_cast<double>(i) / count;
    const auto frequency = 700.0 * std::pow(5600.0 / 700.0, t);
    phase += 2.0 * kPi * frequency / kRate;
    const auto envelope = .5 - .5 * std::cos(2.0 * kPi * i / (count - 1));
    result[i] = static_cast<float>(.32 * envelope * std::sin(phase));
  }
  return result;
}

std::pair<int, double> correlate(const std::vector<float>& capture,
                                 const std::vector<float>& pattern) {
  if (capture.size() < pattern.size()) return {-1, 0};
  std::size_t fftSize = 1;
  while (fftSize < capture.size() + pattern.size()) fftSize <<= 1;
  std::vector<float> left(fftSize), right(fftSize), inverse(fftSize);
  std::copy(capture.begin(), capture.end(), left.begin());
  std::reverse_copy(pattern.begin(), pattern.end(), right.begin());
  std::vector<fftwf_complex> lf(fftSize / 2 + 1), rf(fftSize / 2 + 1), product(fftSize / 2 + 1);
  auto lp = fftwf_plan_dft_r2c_1d(fftSize, left.data(), lf.data(), FFTW_ESTIMATE);
  auto rp = fftwf_plan_dft_r2c_1d(fftSize, right.data(), rf.data(), FFTW_ESTIMATE);
  fftwf_execute(lp); fftwf_execute(rp);
  for (std::size_t i = 0; i < product.size(); ++i) {
    product[i][0] = lf[i][0] * rf[i][0] - lf[i][1] * rf[i][1];
    product[i][1] = lf[i][0] * rf[i][1] + lf[i][1] * rf[i][0];
  }
  auto ip = fftwf_plan_dft_c2r_1d(fftSize, product.data(), inverse.data(), FFTW_ESTIMATE);
  fftwf_execute(ip); fftwf_destroy_plan(lp); fftwf_destroy_plan(rp); fftwf_destroy_plan(ip);
  const auto first = pattern.size() - 1;
  const auto last = first + capture.size() - pattern.size();
  auto found = std::max_element(inverse.begin() + first, inverse.begin() + last + 1,
                                [](float a, float b) { return std::abs(a) < std::abs(b); });
  const auto match = static_cast<int>(std::distance(inverse.begin(), found) - first);
  double patternPower = 0, capturePower = 0;
  for (std::size_t i = 0; i < pattern.size(); ++i) {
    patternPower += pattern[i] * pattern[i];
    capturePower += capture[match + i] * capture[match + i];
  }
  const auto raw = std::abs(*found) / fftSize;
  const auto confidence = raw / std::sqrt(std::max(1e-12, patternPower * capturePower));
  return {match, confidence};
}

}  // namespace

AudioOutput currentOutput() {
  const auto info = commandJson("pactl -f json info 2>/dev/null");
  const auto key = info.value("default_sink_name", "");
  const auto sinks = commandJson("pactl -f json list sinks 2>/dev/null");
  if (sinks.is_array()) for (const auto& sink : sinks) {
    if (sink.value("name", "") != key) continue;
    const auto properties = sink.value("properties", json::object());
    const auto bluetooth = key.rfind("bluez_output.", 0) == 0 || properties.value("device.bus", "") == "bluetooth";
    return {key, sink.value("description", key), bluetooth};
  }
  return {key, key.empty() ? "Default audio output" : key, key.rfind("bluez_output.", 0) == 0};
}

std::vector<Microphone> microphones() {
  const auto info = commandJson("pactl -f json info 2>/dev/null");
  const auto defaultKey = info.value("default_source_name", "");
  std::vector<Microphone> result;
  const auto sources = commandJson("pactl -f json list sources 2>/dev/null");
  if (sources.is_array()) for (const auto& source : sources) {
    const auto key = source.value("name", "");
    if (key.empty() || (key.size() >= 8 && key.compare(key.size() - 8, 8, ".monitor") == 0)) continue;
    result.push_back({key, source.value("description", key), key == defaultKey});
  }
  std::sort(result.begin(), result.end(), [](const auto& a, const auto& b) {
    return a.isDefault != b.isDefault ? a.isDefault : a.label < b.label;
  });
  return result;
}

int probeSystemLatency(const AudioOutput& output) {
  GstElement* pipeline = gst_pipeline_new("fred-native-latency-probe");
  auto* source = make("audiotestsrc", "latency-silence");
  auto* convert = make("audioconvert", "latency-convert");
  auto* sink = outputSink(output, "latency-output");
  g_object_set(source, "is-live", TRUE, "wave", 4, nullptr);
  gst_bin_add_many(GST_BIN(pipeline), source, convert, sink, nullptr);
  if (!gst_element_link_many(source, convert, sink, nullptr)) throw std::runtime_error("Could not build latency probe");
  gst_element_set_state(pipeline, GST_STATE_PLAYING);
  gst_element_get_state(pipeline, nullptr, nullptr, 3 * GST_SECOND);
  GstQuery* query = gst_query_new_latency();
  GstClockTime minimum = 0, maximum = 0; gboolean live = false;
  if (!gst_element_query(pipeline, query)) minimum = 0;
  else gst_query_parse_latency(query, &live, &minimum, &maximum);
  gst_query_unref(query);
  if (GST_IS_BASE_SINK(sink))
    minimum = std::max(minimum, gst_base_sink_get_latency(GST_BASE_SINK(sink)));
  gst_element_set_state(pipeline, GST_STATE_NULL); gst_object_unref(pipeline);
  if (!minimum) throw std::runtime_error("The audio system did not report output latency");
  return std::clamp(static_cast<int>(std::llround(minimum / 1'000'000.0)), 0, 1500);
}

CalibrationResult calibrateWithMicrophone(const std::string& microphoneKey) {
  if (microphoneKey.empty()) throw std::runtime_error("Choose a microphone first");
  const auto activeOutput = currentOutput();
  GstElement* pipeline = gst_pipeline_new("fred-native-speaker-calibration");
  auto* source = make("pulsesrc", "calibration-microphone");
  g_object_set(source, "device", microphoneKey.c_str(), nullptr);
  auto* inConvert = make("audioconvert", "calibration-in-convert");
  auto* inResample = make("audioresample", "calibration-in-resample");
  auto* inCaps = make("capsfilter", "calibration-in-caps");
  auto* sink = make("appsink", "calibration-capture");
  auto* appSource = make("appsrc", "calibration-sound");
  auto* outConvert = make("audioconvert", "calibration-out-convert");
  auto* outResample = make("audioresample", "calibration-out-resample");
  auto* audioSink = outputSink(activeOutput, "calibration-output");
  auto* caps = gst_caps_from_string("audio/x-raw,format=F32LE,channels=1,rate=48000,layout=interleaved");
  g_object_set(inCaps, "caps", caps, nullptr);
  g_object_set(appSource, "caps", caps, "format", GST_FORMAT_TIME, "is-live", TRUE, "block", TRUE, nullptr);
  gst_caps_unref(caps);
  g_object_set(sink, "sync", FALSE, "max-buffers", 256, "drop", FALSE, nullptr);
  gst_bin_add_many(GST_BIN(pipeline), source, inConvert, inResample, inCaps, sink,
                   appSource, outConvert, outResample, audioSink, nullptr);
  if (!gst_element_link_many(source, inConvert, inResample, inCaps, sink, nullptr) ||
      !gst_element_link_many(appSource, outConvert, outResample, audioSink, nullptr))
    throw std::runtime_error("Could not build speaker calibration pipeline");

  auto pattern = chirp();
  const int preRoll = static_cast<int>(.4 * kRate);
  std::vector<float> outputSamples(preRoll + pattern.size() + static_cast<int>(.35 * kRate));
  std::copy(pattern.begin(), pattern.end(), outputSamples.begin() + preRoll);
  std::vector<float> captured;
  gst_element_set_state(pipeline, GST_STATE_PLAYING);
  gst_element_get_state(pipeline, nullptr, nullptr, 3 * GST_SECOND);
  GstBuffer* buffer = gst_buffer_new_allocate(nullptr, outputSamples.size() * sizeof(float), nullptr);
  gst_buffer_fill(buffer, 0, outputSamples.data(), outputSamples.size() * sizeof(float));
  GST_BUFFER_DURATION(buffer) = outputSamples.size() * GST_SECOND / kRate;
  if (gst_app_src_push_buffer(GST_APP_SRC(appSource), buffer) != GST_FLOW_OK)
    throw std::runtime_error("Could not play calibration sound");
  const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
  while (std::chrono::steady_clock::now() < deadline) {
    GstSample* sample = gst_app_sink_try_pull_sample(GST_APP_SINK(sink), 100 * GST_MSECOND);
    if (!sample) continue;
    GstBuffer* received = gst_sample_get_buffer(sample); GstMapInfo map{};
    if (gst_buffer_map(received, &map, GST_MAP_READ)) {
      const auto* values = reinterpret_cast<const float*>(map.data);
      captured.insert(captured.end(), values, values + map.size / sizeof(float));
      gst_buffer_unmap(received, &map);
    }
    gst_sample_unref(sample);
  }
  gst_app_src_end_of_stream(GST_APP_SRC(appSource));
  gst_element_set_state(pipeline, GST_STATE_NULL); gst_object_unref(pipeline);
  if (currentOutput().key != activeOutput.key) throw std::runtime_error("The audio output changed during calibration");
  const auto [match, confidence] = correlate(captured, pattern);
  if (match < 0 || confidence < .10) throw std::runtime_error("The microphone could not hear the calibration sound clearly");
  const auto delay = std::clamp(static_cast<int>(std::llround((match - preRoll) * 1000.0 / kRate)), 0, 1500);
  return {activeOutput, delay, confidence};
}

}  // namespace fredplayer
