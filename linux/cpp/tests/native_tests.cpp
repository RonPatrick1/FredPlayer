#include "fredplayer/cache.hpp"
#include "fredplayer/clock.hpp"
#include "fredplayer/dsp.hpp"
#include "fredplayer/server_client.hpp"
#include "fredplayer/state.hpp"
#include "fredplayer/visualization.hpp"

#include <zlib.h>
#include <gst/gst.h>

#include <chrono>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <stdexcept>
#include <thread>

namespace {
int failures = 0;

void check(bool condition, const char* message) {
  if (!condition) { std::cerr << "FAIL: " << message << '\n'; ++failures; }
}

void be16(std::vector<std::uint8_t>& out, std::size_t at, std::uint16_t value) {
  out[at] = value >> 8; out[at + 1] = value;
}
void be32(std::vector<std::uint8_t>& out, std::size_t at, std::uint32_t value) {
  out[at] = value >> 24; out[at + 1] = value >> 16; out[at + 2] = value >> 8; out[at + 3] = value;
}
void be64(std::vector<std::uint8_t>& out, std::size_t at, std::uint64_t value) {
  be32(out, at, value >> 32); be32(out, at + 4, value);
}
void beFloat(std::vector<std::uint8_t>& out, std::size_t at, float value) {
  std::uint32_t bits; std::memcpy(&bits, &value, 4); be32(out, at, bits);
}

void testStateCompatibility() {
  const auto root = std::filesystem::temp_directory_path() / "fredplayer-native-state-test";
  std::filesystem::remove_all(root); std::filesystem::create_directories(root);
  fredplayer::StateStore store(root / "state.json");
  fredplayer::AppState state;
  state.activePlaylist = "Night";
  state.playlist = {{"https://server/stream/A/B.flac", "A", true, "Song", "Artist", "Album"}};
  state.namedPlaylists = {{"Night", state.playlist}, {"Empty", {}}};
  state.visualization = {60, 412, "log", 256, 8192, 0};
  state.speakerLatencies["bluez"] = {"bluez", "Bose", 187};
  store.save(state);
  const auto loaded = store.load();
  check(loaded.activePlaylist == "Night", "active named playlist round-trips");
  check(loaded.playlist.size() == 1 && loaded.playlist[0].title == "Song", "remote metadata round-trips");
  check(loaded.visualization.fftSize == 8192 && loaded.visualization.fftColumns == 256,
        "visual settings round-trip");
  check(loaded.speakerLatencies.at("bluez").delayMs == 187, "speaker calibration round-trips");
  std::filesystem::remove_all(root);
}

void testLegacyCacheKeys() {
  fredplayer::VisualizationSettings settings;
  const std::string path = "https://example/stream/a.flac";
  check(fredplayer::spectrumCacheKey(path, settings).value_or("") ==
        "891fd1b377ee49827570657101920774c6a00879b118157d3220c07d76fe5527",
        "spectrum key is byte-compatible with Python");
  check(fredplayer::waveformCacheKey(path, settings).value_or("") ==
        "40a4ced473f9920bbc0e06211956242d663001c5ab8ceb28c886520f3914e229",
        "waveform key is byte-compatible with Python");
}

void testDsp() {
  fredplayer::DspProcessor dsp;
  fredplayer::LevelingSettings settings;
  dsp.configure(.55, .9, settings, fredplayer::TrackProfile{.05, .1});
  std::vector<float> audio(4800 * 2);
  for (std::size_t i = 0; i < audio.size(); ++i) audio[i] = std::sin(i * .03F) * .8F;
  dsp.process(audio.data(), audio.size() / 2);
  for (float value : audio) check(std::isfinite(value) && std::abs(value) <= .96001F,
                                  "DSP output is finite and ceiling-limited");
}

void testClockOutage() {
  using namespace std::chrono;
  fredplayer::PresentationClock clock;
  const auto start = fredplayer::PresentationClock::SteadyClock::now();
  clock.reset(1'000'000'000);
  std::int64_t previous = 0;
  for (int frame = 0; frame < 180; ++frame) {
    const auto now = start + milliseconds(frame * 1000 / 60);
    const auto value = clock.update(std::nullopt, 10'000'000'000LL, now);
    check(value >= previous, "clock remains monotonic during a three-second query outage");
    if (frame) check(value - previous <= 20'000'000, "clock outage has no visualization gap over 20ms");
    previous = value;
  }
  const auto recovered = clock.update(4'000'000'000LL, 10'000'000'000LL, start + seconds(3));
  check(std::abs(recovered - previous) <= 20'000'000, "clock recovery slews instead of jumping");
  check(clock.queryFailureCount() == 180, "clock records query failures");
}

void testClockWithQuantizedBackendPosition() {
  using namespace std::chrono;
  fredplayer::PresentationClock clock;
  const auto start = fredplayer::PresentationClock::SteadyClock::now();
  clock.reset(0);
  clock.update(0, 10'000'000'000LL, start);
  std::int64_t previous = 0;
  for (int sample = 1; sample <= 120; ++sample) {
    const auto elapsedMs = sample * 50;
    const auto quantizedMs = elapsedMs / 100 * 100;
    const auto value = clock.update(quantizedMs * 1'000'000LL,
        10'000'000'000LL, start + milliseconds(elapsedMs));
    const auto step = value - previous;
    check(step >= 49'000'000 && step <= 51'000'000,
          "quantized GStreamer positions do not make the visual clock stutter");
    previous = value;
  }
}

void testVisualizationQueue() {
  fredplayer::VisualizationEngine engine;
  fredplayer::VisualizationSettings settings{60, 80, "log", 64, 2048, 0};
  engine.setSettings(settings);
  std::vector<float> block(480 * 2);
  for (int part = 0; part < 40; ++part) {
    for (int i = 0; i < 480; ++i) {
      const auto value = std::sin(2 * 3.141592653589793 * 440 * (part * 480 + i) / 48000);
      block[i * 2] = block[i * 2 + 1] = static_cast<float>(value * .2);
    }
    engine.submit(block.data(), 480, part * 10'000'000LL);
  }
  std::this_thread::sleep_for(std::chrono::milliseconds(150));
  const auto frame = engine.frameAt(350'000'000, 0);
  check(frame && frame->waveform.size() == 512, "native analyzer emits 512-point waveform");
  check(frame && frame->spectrum.size() == 64, "native analyzer emits requested FFT bands");
  check(engine.producedFrameCount() > 0, "native analyzer counts completed frames");
  check(engine.averageAnalysisMs() > 0 && engine.estimatedAnalysisCapacityFps() > 0,
        "native analyzer reports measured processing capacity");

  fredplayer::VisualizationFrame cached;
  cached.ptsNs = 0;
  cached.waveform.assign(512, .1F);
  cached.spectrum.assign(64, .2F);
  engine.setCachedFrames({cached});
  const auto queuedBeforeSubmit = engine.queuedBlockCount();
  engine.submit(block.data(), 480, 500'000'000LL);
  check(engine.cachedFramesActive(), "valid cached frames disable duplicate live analysis");
  check(engine.queuedBlockCount() == queuedBeforeSubmit,
        "cached playback does not copy audio into the live FFT queue");
  check(engine.latestAudioPtsNs() == 510'000'000LL,
        "cached playback still advances the audio presentation clock");
  engine.clearCachedFrames();
  check(!engine.cachedFramesActive(), "live analysis resumes when cached frames are cleared");
}

void reportVisualizationCapacityWhenRequested() {
  if (!std::getenv("FREDPLAYER_BENCHMARK_VISUAL")) return;
  auto settings = fredplayer::StateStore().load().visualization;
  settings.normalize();
  fredplayer::VisualizationEngine engine;
  engine.setSettings(settings);
  constexpr int framesPerBlock = 4800;
  std::vector<float> block(framesPerBlock * 2);
  for (int i = 0; i < framesPerBlock; ++i) {
    const auto sample = static_cast<float>(.2 * std::sin(
        2 * 3.141592653589793 * 440 * i / 48000));
    block[i * 2] = block[i * 2 + 1] = sample;
  }
  for (int part = 0; part < 20; ++part)
    engine.submit(block.data(), framesPerBlock, part * 100'000'000LL);
  auto previous = std::uint64_t{0};
  int stableChecks = 0;
  for (int checkNumber = 0; checkNumber < 100 && stableChecks < 4; ++checkNumber) {
    std::this_thread::sleep_for(std::chrono::milliseconds(25));
    const auto current = engine.producedFrameCount();
    stableChecks = current > 0 && current == previous ? stableChecks + 1 : 0;
    previous = current;
  }
  std::cout << "Visualization benchmark (" << settings.fftSize << " FFT, "
            << settings.fftColumns << " bars): " << engine.averageAnalysisMs()
            << " ms/frame, about " << engine.estimatedAnalysisCapacityFps()
            << " analyzed frames/second on this CPU\n";
}

void testFlv1() {
  constexpr std::uint32_t frames = 2, points = 512, bars = 24;
  std::vector<std::uint8_t> raw(frames * (points + bars));
  for (std::size_t i = 0; i < raw.size(); ++i) raw[i] = static_cast<std::uint8_t>(i);
  uLongf compressedSize = compressBound(raw.size());
  std::vector<std::uint8_t> compressed(compressedSize);
  compress2(compressed.data(), &compressedSize, raw.data(), raw.size(), 6); compressed.resize(compressedSize);
  std::vector<std::uint8_t> file(72 + compressed.size());
  std::memcpy(file.data(), "FLV1", 4); be16(file,4,1); be16(file,6,1); be32(file,8,72);
  be32(file,12,48000); beFloat(file,16,30); beFloat(file,20,80); be32(file,24,4096);
  be32(file,28,bars); be32(file,32,points); be32(file,36,frames); be64(file,40,33'333'333);
  be64(file,48,123); be64(file,56,456); be32(file,64,raw.size()); be32(file,68,compressed.size());
  std::copy(compressed.begin(), compressed.end(), file.begin()+72);
  const auto decoded=fredplayer::decodeFlv1(file);
  check(decoded && decoded->frames.size()==2,"FLV1 compressed frames decode");
  check(decoded && decoded->settings.fftColumns==24&&decoded->waveformPoints==512,"FLV1 header settings decode");
  file[0]='X'; check(!fredplayer::decodeFlv1(file),"invalid FLV1 magic is rejected");
}

void testHeadlessGstreamer() {
  gst_init(nullptr,nullptr);
  GError* error=nullptr;
  GstElement* pipeline=gst_parse_launch("audiotestsrc num-buffers=12 wave=sine ! audioconvert ! audioresample ! audio/x-raw,format=F32LE,channels=2,rate=48000 ! fakesink sync=false",&error);
  check(pipeline!=nullptr&&error==nullptr,"headless native GStreamer pipeline builds");
  if(error)g_clear_error(&error);
  if(!pipeline)return;
  gst_element_set_state(pipeline,GST_STATE_PLAYING);
  GstBus* bus=gst_element_get_bus(pipeline);
  GstMessage* message=gst_bus_timed_pop_filtered(bus,5*GST_SECOND,
      static_cast<GstMessageType>(GST_MESSAGE_EOS|GST_MESSAGE_ERROR));
  check(message&&GST_MESSAGE_TYPE(message)==GST_MESSAGE_EOS,"headless GStreamer decode reaches EOS");
  if(message)gst_message_unref(message);gst_object_unref(bus);
  gst_element_set_state(pipeline,GST_STATE_NULL);gst_object_unref(pipeline);
}

void testRealCacheWhenRequested() {
  if (!std::getenv("FREDPLAYER_VERIFY_REAL_CACHE")) return;
  const auto state = fredplayer::StateStore().load();
  fredplayer::LegacyVisualizationCache cache;
  bool found = false;
  for (const auto& track : state.playlist) {
    const auto frames = cache.loadFrames(track.path, state.visualization);
    if (!frames.empty()) {
      check(frames.front().waveform.size() == 512, "real Python waveform cache is readable");
      check(frames.front().spectrum.size() == static_cast<std::size_t>(state.visualization.fftColumns),
            "real Python spectrum cache is readable");
      found = true;
      break;
    }
  }
  check(found, "at least one active-playlist Python cache matches current settings");
}

void testServerTicketWhenRequested() {
  if (!std::getenv("FREDPLAYER_VERIFY_SERVER_STREAM")) return;
  const auto state = fredplayer::StateStore().load();
  std::optional<fredplayer::TrackEntry> remote;
  for (const auto& [name, tracks] : state.namedPlaylists) {
    (void)name;
    for (const auto& track : tracks) {
      if (track.remote) {
        remote = track;
        if (track.title == "Tomorrow We’ll See" ||
            track.title == "Tomorrow We'll See")
          break;
      }
    }
    if (remote && (remote->title == "Tomorrow We’ll See" ||
                   remote->title == "Tomorrow We'll See"))
      break;
  }
  check(remote.has_value(), "saved state contains a remote track for stream verification");
  if (!remote) return;
  fredplayer::ServerClient server(state.serverBaseUrl, state.serverToken);
  const auto ticket = server.ticketedStreamUrl(*remote);
  check(ticket.has_value(), "server issues an authenticated native stream ticket");
  check(ticket && ticket->find("?expires=") != std::string::npos &&
            ticket->find("&signature=") != std::string::npos,
        "native stream ticket contains expiry and signature");
  if (!ticket) return;

  auto* player = gst_element_factory_make("playbin", "signed-stream-test");
  auto* audioSink = gst_element_factory_make("fakesink", "signed-audio-sink");
  auto* videoSink = gst_element_factory_make("fakesink", "signed-video-sink");
  check(player && audioSink && videoSink,
        "signed-stream GStreamer test elements are available");
  if (!player || !audioSink || !videoSink) {
    if (player) gst_object_unref(player);
    if (audioSink) gst_object_unref(audioSink);
    if (videoSink) gst_object_unref(videoSink);
    return;
  }
  g_object_set(player, "uri", ticket->c_str(), "audio-sink", audioSink,
               "video-sink", videoSink, nullptr);
  const auto stateChange = gst_element_set_state(player, GST_STATE_PAUSED);
  check(stateChange != GST_STATE_CHANGE_FAILURE,
        "GStreamer accepts the signed remote track");
  GstBus* bus = gst_element_get_bus(player);
  GstMessage* message = gst_bus_timed_pop_filtered(bus, 10 * GST_SECOND,
      static_cast<GstMessageType>(GST_MESSAGE_ASYNC_DONE | GST_MESSAGE_ERROR));
  check(message && GST_MESSAGE_TYPE(message) == GST_MESSAGE_ASYNC_DONE,
        "signed remote FLAC reaches decoded preroll without an Authorization header");
  if (message) gst_message_unref(message);
  gst_object_unref(bus);
  gst_element_set_state(player, GST_STATE_NULL);
  gst_object_unref(player);
}
}  // namespace

int main() {
  testStateCompatibility(); testLegacyCacheKeys(); testDsp(); testClockOutage();
  testClockWithQuantizedBackendPosition();
  testVisualizationQueue(); testFlv1();
  testHeadlessGstreamer();
  testRealCacheWhenRequested();
  testServerTicketWhenRequested();
  reportVisualizationCapacityWhenRequested();
  if (failures) std::cerr << failures << " native test assertion(s) failed\n";
  else std::cout << "All FredPlayer native headless tests passed\n";
  return failures ? 1 : 0;
}
