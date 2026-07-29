#include "fredplayer/clock.hpp"

#include <algorithm>
#include <cmath>

namespace fredplayer {

void PresentationClock::reset(std::int64_t positionNs) {
  initialized_ = true;
  querySynchronized_ = false;
  estimatedNs_ = lastValidNs_ = std::max<std::int64_t>(0, positionNs);
  lastUpdate_ = lastValidAt_ = SteadyClock::now();
  failures_ = 0;
}

std::int64_t PresentationClock::update(std::optional<std::int64_t> queried,
                                       std::int64_t latestAudioPtsNs,
                                       SteadyClock::time_point now) {
  if (!initialized_) {
    reset(queried.value_or(0));
    lastUpdate_ = lastValidAt_ = now;
  }
  const auto elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(now - lastUpdate_).count();
  estimatedNs_ = std::max<std::int64_t>(0, estimatedNs_ + std::max<std::int64_t>(0, elapsed));
  lastUpdate_ = now;
  if (queried && *queried >= 0) {
    if (!querySynchronized_) {
      estimatedNs_ = *queried;
      querySynchronized_ = true;
    } else {
      const auto error = *queried - estimatedNs_;
      constexpr std::int64_t kDiscontinuity = 250'000'000;
      constexpr std::int64_t kMaxSlewPerSample = 250'000;
      if (std::abs(error) >= kDiscontinuity) {
        estimatedNs_ = *queried;
      } else {
        estimatedNs_ += std::clamp(error / 16,
            -kMaxSlewPerSample, kMaxSlewPerSample);
      }
    }
    lastValidNs_ = *queried;
    lastValidAt_ = now;
  } else {
    ++failures_;
    constexpr auto kMaxExtrapolation = std::chrono::seconds(5);
    if (now - lastValidAt_ > kMaxExtrapolation)
      estimatedNs_ = std::max(estimatedNs_, lastValidNs_);
  }
  if (latestAudioPtsNs > 0)
    estimatedNs_ = std::min(estimatedNs_, latestAudioPtsNs + 250'000'000);
  return std::max<std::int64_t>(0, estimatedNs_);
}

}  // namespace fredplayer
