#include "fredplayer/cache.hpp"

#include <glib.h>
#include <nlohmann/json.hpp>
#include <sys/stat.h>
#include <zlib.h>

#include <cstring>
#include <cmath>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <tuple>

namespace fredplayer {
namespace {
using json = nlohmann::json;

std::string sha256(const std::string& value) {
  gchar* digest = g_compute_checksum_for_string(G_CHECKSUM_SHA256, value.c_str(), value.size());
  std::string result = digest ? digest : "";
  g_free(digest);
  return result;
}

std::string fixed3(double value) {
  std::ostringstream out; out << std::fixed << std::setprecision(3) << value; return out.str();
}

std::vector<std::uint8_t> readFile(const std::filesystem::path& path) {
  std::ifstream input(path, std::ios::binary);
  if (!input) return {};
  return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

std::uint32_t be32(const std::uint8_t* p) {
  return (std::uint32_t(p[0]) << 24) | (std::uint32_t(p[1]) << 16) |
         (std::uint32_t(p[2]) << 8) | p[3];
}
std::uint16_t be16(const std::uint8_t* p) { return std::uint16_t((p[0] << 8) | p[1]); }
std::uint64_t be64(const std::uint8_t* p) { return (std::uint64_t(be32(p)) << 32) | be32(p + 4); }
float beFloat(const std::uint8_t* p) { const auto bits = be32(p); float result; std::memcpy(&result, &bits, 4); return result; }

std::optional<std::pair<json, std::vector<std::uint8_t>>> legacyFile(const std::filesystem::path& path) {
  const auto raw = readFile(path);
  if (raw.size() < 4) return std::nullopt;
  const auto headerBytes = be32(raw.data());
  if (headerBytes > raw.size() - 4) return std::nullopt;
  json header;
  try { header = json::parse(raw.begin() + 4, raw.begin() + 4 + headerBytes); }
  catch (...) { return std::nullopt; }
  uLongf expected = 0;
  const auto& profile = header.value("profile", json::object());
  if (path.extension() == ".fsp")
    expected = profile.value("frame_count", 0U) * profile.value("bands", 0U);
  else
    expected = profile.value("frame_count", 0U) * profile.value("waveform_points", 0U);
  if (!expected || expected > 2ULL * 1024 * 1024 * 1024) return std::nullopt;
  std::vector<std::uint8_t> payload(expected);
  if (uncompress(payload.data(), &expected, raw.data() + 4 + headerBytes,
                 raw.size() - 4 - headerBytes) != Z_OK) return std::nullopt;
  payload.resize(expected);
  return std::make_pair(std::move(header), std::move(payload));
}
}  // namespace

std::optional<std::string> fileCacheIdentity(const std::string& path) {
  if (path.rfind("http://", 0) == 0 || path.rfind("https://", 0) == 0) return path;
  struct stat status{};
  if (::stat(path.c_str(), &status) != 0) return std::nullopt;
  const auto ns = static_cast<std::int64_t>(status.st_mtim.tv_sec) * 1'000'000'000LL + status.st_mtim.tv_nsec;
  std::error_code error;
  const auto normalized = std::filesystem::weakly_canonical(path, error).string();
  return (error ? path : normalized) + "|size=" + std::to_string(status.st_size) + "|mtime_ns=" + std::to_string(ns);
}

CacheSummary cacheSummary(const std::filesystem::path& root) {
  CacheSummary result;
  std::error_code error;
  const auto profilePath = root / "profiles.json";
  if (std::filesystem::exists(profilePath, error)) {
    result.bytes += std::filesystem::file_size(profilePath, error);
    try {
      std::ifstream input(profilePath); json value; input >> value;
      result.profileCount = value.value("profiles", json::object()).size();
    } catch (...) {}
  }
  for (const auto& [directory, extension, count] : std::vector<std::tuple<std::filesystem::path,std::string,std::uint64_t*>>{
         {root / "spectra", ".fsp", &result.spectrumCount},
         {root / "waveforms", ".fwp", &result.waveformCount}}) {
    error.clear();
    for (std::filesystem::directory_iterator it(directory, error), end; it != end && !error; it.increment(error)) {
      if (!it->is_regular_file() || it->path().extension() != extension) continue;
      ++*count;
      result.bytes += it->file_size(error);
      error.clear();
    }
  }
  return result;
}

std::optional<std::string> spectrumCacheKey(const std::string& path,
                                            const VisualizationSettings& settings) {
  const auto file = fileCacheIdentity(path); if (!file) return std::nullopt;
  return sha256("spectrum-v2-centered|" + *file + "|fps=" + fixed3(settings.updateFps) +
                "|fft_size=" + std::to_string(settings.fftSize) +
                "|bands=" + std::to_string(settings.fftColumns) + "|scale=" + settings.fftScale);
}

std::optional<std::string> waveformCacheKey(const std::string& path,
                                            const VisualizationSettings& settings) {
  const auto file = fileCacheIdentity(path); if (!file) return std::nullopt;
  return sha256("waveform-v2-current-sample|" + *file + "|fps=" + fixed3(settings.updateFps) +
                "|window_ms=" + fixed3(settings.waveformWindowMs) + "|points=512");
}

std::optional<TrackProfile> loadCachedTrackProfile(
    const std::string& path, const std::filesystem::path& root) {
  const auto key = fileCacheIdentity(path);
  if (!key) return std::nullopt;
  try {
    std::ifstream input(root / "profiles.json");
    json values; input >> values;
    const auto profiles = values.value("profiles", json::object());
    if (!profiles.contains(*key) || !profiles[*key].is_object()) return std::nullopt;
    const auto& value = profiles[*key];
    if (!value.contains("rms") || !value.contains("peak") ||
        !value["rms"].is_number() || !value["peak"].is_number()) return std::nullopt;
    return TrackProfile{value["rms"].get<double>(), value["peak"].get<double>()};
  } catch (...) { return std::nullopt; }
}

LegacyVisualizationCache::LegacyVisualizationCache(std::filesystem::path root) : root_(std::move(root)) {}

std::optional<SpectrumProfile> LegacyVisualizationCache::loadSpectrum(
    const std::string& path, const VisualizationSettings& settings) const {
  const auto key = spectrumCacheKey(path, settings); if (!key) return std::nullopt;
  const auto decoded = legacyFile(root_ / "spectra" / (*key + ".fsp")); if (!decoded) return std::nullopt;
  const auto& [header, bytes] = *decoded;
  if (header.value("version", 0) != 2 || header.value("cache_key", "") != *key) return std::nullopt;
  const auto p = header.value("profile", json::object());
  SpectrumProfile result{p.value("sample_rate", 0), p.value("fps", 0.0), p.value("fft_size", 0),
                         p.value("bands", 0), p.value("fft_scale", ""), p.value("frame_count", 0U), bytes};
  if (result.sampleRate != 48000 || std::abs(result.fps - settings.updateFps) > .001 ||
      result.fftSize != settings.fftSize || result.bands != settings.fftColumns ||
      result.scale != settings.fftScale || result.payload.size() != result.frameCount * result.bands)
    return std::nullopt;
  return result;
}

std::optional<WaveformProfile> LegacyVisualizationCache::loadWaveform(
    const std::string& path, const VisualizationSettings& settings) const {
  const auto key = waveformCacheKey(path, settings); if (!key) return std::nullopt;
  const auto decoded = legacyFile(root_ / "waveforms" / (*key + ".fwp")); if (!decoded) return std::nullopt;
  const auto& [header, bytes] = *decoded;
  if (header.value("version", 0) != 2 || header.value("cache_key", "") != *key) return std::nullopt;
  const auto p = header.value("profile", json::object());
  WaveformProfile result;
  result.sampleRate = p.value("sample_rate", 0); result.fps = p.value("fps", 0.0);
  result.windowMs = p.value("waveform_window_ms", 0.0); result.points = p.value("waveform_points", 0);
  result.frameCount = p.value("frame_count", 0U);
  result.payload.resize(bytes.size());
  std::memcpy(result.payload.data(), bytes.data(), bytes.size());
  if (result.sampleRate != 48000 || std::abs(result.fps - settings.updateFps) > .001 ||
      std::abs(result.windowMs - settings.waveformWindowMs) > .001 || result.points != 512 ||
      result.payload.size() != result.frameCount * result.points) return std::nullopt;
  return result;
}

std::vector<VisualizationFrame> LegacyVisualizationCache::loadFrames(
    const std::string& path, const VisualizationSettings& settings) const {
  const auto spectrum = loadSpectrum(path, settings);
  const auto waveform = loadWaveform(path, settings);
  if (!spectrum && !waveform) return {};
  const auto frameCount = spectrum && waveform
      ? std::min(spectrum->frameCount, waveform->frameCount)
      : spectrum ? spectrum->frameCount : waveform->frameCount;
  std::vector<VisualizationFrame> frames;
  frames.reserve(frameCount);
  const auto interval = static_cast<std::int64_t>(std::llround(1'000'000'000.0 / settings.updateFps));
  for (std::uint32_t index = 0; index < frameCount; ++index) {
    VisualizationFrame frame;
    frame.ptsNs = index * interval;
    frame.waveform.assign(512, 0.0F);
    frame.spectrum.assign(settings.fftColumns, 0.0F);
    if (waveform) {
      const auto offset = static_cast<std::size_t>(index) * waveform->points;
      double square = 0;
      for (int point = 0; point < waveform->points; ++point) {
        frame.waveform[point] = waveform->payload[offset + point] / 127.0F;
        frame.peak = std::max(frame.peak, std::abs(frame.waveform[point]));
        square += frame.waveform[point] * frame.waveform[point];
      }
      frame.rms = static_cast<float>(std::sqrt(square / waveform->points));
    }
    if (spectrum) {
      const auto offset = static_cast<std::size_t>(index) * spectrum->bands;
      for (int band = 0; band < spectrum->bands; ++band)
        frame.spectrum[band] = spectrum->payload[offset + band] / 255.0F;
    }
    frames.push_back(std::move(frame));
  }
  return frames;
}

std::optional<LinuxVisualFile> decodeFlv1(const std::vector<std::uint8_t>& bytes) {
  constexpr std::size_t kHeader = 72;
  if (bytes.size() < kHeader || std::memcmp(bytes.data(), "FLV1", 4) != 0 || be16(bytes.data() + 4) != 1)
    return std::nullopt;
  const auto flags = be16(bytes.data() + 6);
  const auto headerSize = be32(bytes.data() + 8);
  if (headerSize < kHeader || headerSize > bytes.size()) return std::nullopt;
  LinuxVisualFile file;
  file.sampleRate = static_cast<int>(be32(bytes.data() + 12));
  file.settings.updateFps = beFloat(bytes.data() + 16);
  file.settings.waveformWindowMs = beFloat(bytes.data() + 20);
  file.settings.fftSize = static_cast<int>(be32(bytes.data() + 24));
  file.settings.fftColumns = static_cast<int>(be32(bytes.data() + 28));
  file.waveformPoints = static_cast<int>(be32(bytes.data() + 32));
  file.frameCount = be32(bytes.data() + 36);
  file.frameIntervalNs = be64(bytes.data() + 40);
  file.settings.fftScale = (flags & 1) ? "log" : "linear";
  const auto rawBytes = be32(bytes.data() + 64);
  const auto compressedBytes = be32(bytes.data() + 68);
  const auto stride = static_cast<std::uint64_t>(file.waveformPoints + file.settings.fftColumns);
  if (file.sampleRate != 48000 || file.waveformPoints != 512 || file.frameCount == 0 ||
      rawBytes != stride * file.frameCount || compressedBytes != bytes.size() - headerSize) return std::nullopt;
  std::vector<std::uint8_t> payload(rawBytes); uLongf length = rawBytes;
  if (uncompress(payload.data(), &length, bytes.data() + headerSize, compressedBytes) != Z_OK || length != rawBytes)
    return std::nullopt;
  file.frames.reserve(file.frameCount);
  for (std::uint32_t index = 0; index < file.frameCount; ++index) {
    VisualizationFrame frame; frame.ptsNs = index * file.frameIntervalNs;
    const auto offset = index * stride;
    frame.waveform.resize(file.waveformPoints);
    double square = 0;
    for (int point = 0; point < file.waveformPoints; ++point) {
      frame.waveform[point] = static_cast<std::int8_t>(payload[offset + point]) / 127.0F;
      frame.peak = std::max(frame.peak, std::abs(frame.waveform[point]));
      square += frame.waveform[point] * frame.waveform[point];
    }
    frame.rms = static_cast<float>(std::sqrt(square / file.waveformPoints));
    frame.spectrum.resize(file.settings.fftColumns);
    for (int band = 0; band < file.settings.fftColumns; ++band)
      frame.spectrum[band] = payload[offset + file.waveformPoints + band] / 255.0F;
    file.frames.push_back(std::move(frame));
  }
  file.settings.normalize();
  return file;
}

}  // namespace fredplayer
