#pragma once

#include "fredplayer/types.hpp"

#include <cstddef>
#include <mutex>
#include <optional>

namespace fredplayer {

class VolumeNormalizer {
 public:
  VolumeNormalizer(int sampleRate, std::optional<TrackProfile> profile,
                   LevelingSettings settings);
  void updateSettings(LevelingSettings settings);
  float nextGain(float left, float right, float strength);
  float protect(float sample) const;

 private:
  double coefficient(double seconds) const;
  int sampleRate_;
  LevelingSettings settings_;
  double levelAttack_{0};
  double levelRelease_{0};
  double gainDown_{0};
  double gainUp_{0};
  double envelope_{0.18};
  double gain_{1.0};
};

class DspProcessor {
 public:
  explicit DspProcessor(int sampleRate = 48000);
  void configure(double outputLevel, double levelingStrength,
                 const LevelingSettings& settings,
                 std::optional<TrackProfile> profile = std::nullopt);
  void process(float* interleavedStereo, std::size_t frames);

 private:
  int sampleRate_;
  std::mutex mutex_;
  double outputLevel_{0.55};
  double strength_{0.9};
  LevelingSettings settings_;
  VolumeNormalizer normalizer_;
};

}  // namespace fredplayer
