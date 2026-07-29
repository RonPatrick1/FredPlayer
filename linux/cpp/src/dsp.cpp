#include "fredplayer/dsp.hpp"

#include <algorithm>
#include <cmath>

namespace fredplayer {
namespace {
constexpr double kTargetRms = .18;
constexpr double kMinLevel = .012;
constexpr double kMaxGain = 4.5;
constexpr double kMinGain = .2;
constexpr double kCompressorRatio = 6.0;
}

VolumeNormalizer::VolumeNormalizer(int sampleRate, std::optional<TrackProfile> profile,
                                   LevelingSettings settings)
    : sampleRate_(std::max(1, sampleRate)), settings_(settings) {
  updateSettings(settings);
  if (profile) {
    const auto measured = std::max(profile->rms, profile->peak * .35);
    envelope_ = clamp(measured, kMinLevel, .8);
    gain_ = clamp(kTargetRms / std::max(kMinLevel, envelope_), kMinGain, kMaxGain);
  }
}

double VolumeNormalizer::coefficient(double seconds) const {
  return 1.0 - std::exp(-1.0 / (sampleRate_ * std::max(seconds, .000001)));
}

void VolumeNormalizer::updateSettings(LevelingSettings settings) {
  settings.normalize();
  settings_ = settings;
  levelAttack_ = coefficient(settings.levelAttackMs / 1000.0);
  levelRelease_ = coefficient(settings.levelReleaseMs / 1000.0);
  gainDown_ = coefficient(settings.gainDownMs / 1000.0);
  gainUp_ = coefficient(settings.gainUpMs / 1000.0);
}

float VolumeNormalizer::nextGain(float left, float right, float strength) {
  const auto rms = std::sqrt((left * left + right * right) * .5);
  const auto peak = std::max(std::abs(left), std::abs(right));
  const auto instant = std::max(rms, static_cast<double>(peak * .35F));
  envelope_ += (instant - envelope_) * (instant > envelope_ ? levelAttack_ : levelRelease_);
  auto desired = clamp(kTargetRms / std::max(kMinLevel, envelope_), kMinGain, kMaxGain);
  desired = 1.0 + (desired - 1.0) * clamp(static_cast<double>(strength), 0.0, 1.0);
  gain_ += (desired - gain_) * (desired < gain_ ? gainDown_ : gainUp_);
  return static_cast<float>(gain_);
}

float VolumeNormalizer::protect(float value) const {
  const auto sign = value < 0 ? -1.0F : 1.0F;
  auto amount = std::abs(value);
  if (amount > settings_.compressorThreshold)
    amount = static_cast<float>(settings_.compressorThreshold +
             (amount - settings_.compressorThreshold) / kCompressorRatio);
  amount = std::min(amount, static_cast<float>(settings_.outputCeiling));
  return sign * amount;
}

DspProcessor::DspProcessor(int sampleRate)
    : sampleRate_(sampleRate), normalizer_(sampleRate, std::nullopt, settings_) {}

void DspProcessor::configure(double outputLevel, double levelingStrength,
                             const LevelingSettings& settings,
                             std::optional<TrackProfile> profile) {
  std::lock_guard lock(mutex_);
  outputLevel_ = clamp(outputLevel, .1, 1.0);
  strength_ = clamp(levelingStrength, 0.0, 1.0);
  settings_ = settings;
  settings_.normalize();
  normalizer_ = VolumeNormalizer(sampleRate_, profile, settings_);
}

void DspProcessor::process(float* values, std::size_t frames) {
  std::lock_guard lock(mutex_);
  for (std::size_t index = 0; index < frames; ++index) {
    auto& left = values[index * 2];
    auto& right = values[index * 2 + 1];
    const auto gain = normalizer_.nextGain(left, right, static_cast<float>(strength_));
    left = normalizer_.protect(left * gain * static_cast<float>(outputLevel_));
    right = normalizer_.protect(right * gain * static_cast<float>(outputLevel_));
  }
}

}  // namespace fredplayer
