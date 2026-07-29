#pragma once

#include "fredplayer/types.hpp"
#include "fredplayer/state.hpp"

#include <cstdint>
#include <filesystem>
#include <optional>
#include <string>
#include <vector>

namespace fredplayer {

struct SpectrumProfile {
  int sampleRate{48000};
  double fps{30.0};
  int fftSize{4096};
  int bands{96};
  std::string scale{"log"};
  std::uint32_t frameCount{0};
  std::vector<std::uint8_t> payload;
};

struct WaveformProfile {
  int sampleRate{48000};
  double fps{30.0};
  double windowMs{80.0};
  int points{512};
  std::uint32_t frameCount{0};
  std::vector<std::int8_t> payload;
};

struct CacheSummary {
  std::uint64_t profileCount{0};
  std::uint64_t spectrumCount{0};
  std::uint64_t waveformCount{0};
  std::uint64_t bytes{0};
};

CacheSummary cacheSummary(const std::filesystem::path& root = dataDirectory());

std::optional<std::string> fileCacheIdentity(const std::string& path);
std::optional<std::string> spectrumCacheKey(const std::string& path,
                                            const VisualizationSettings& settings);
std::optional<std::string> waveformCacheKey(const std::string& path,
                                            const VisualizationSettings& settings);
std::optional<TrackProfile> loadCachedTrackProfile(
    const std::string& path,
    const std::filesystem::path& root = dataDirectory());

class LegacyVisualizationCache {
 public:
  explicit LegacyVisualizationCache(std::filesystem::path root = dataDirectory());
  std::optional<SpectrumProfile> loadSpectrum(const std::string& path,
                                              const VisualizationSettings& settings) const;
  std::optional<WaveformProfile> loadWaveform(const std::string& path,
                                              const VisualizationSettings& settings) const;
  std::vector<VisualizationFrame> loadFrames(
      const std::string& path, const VisualizationSettings& settings) const;

 private:
  std::filesystem::path root_;
};

struct LinuxVisualFile {
  VisualizationSettings settings;
  int sampleRate{48000};
  int waveformPoints{512};
  std::uint32_t frameCount{0};
  std::uint64_t frameIntervalNs{0};
  std::vector<VisualizationFrame> frames;
};

std::optional<LinuxVisualFile> decodeFlv1(const std::vector<std::uint8_t>& bytes);

}  // namespace fredplayer
