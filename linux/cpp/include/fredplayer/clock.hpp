#pragma once

#include <chrono>
#include <cstdint>
#include <optional>

namespace fredplayer {

class PresentationClock {
 public:
  using SteadyClock = std::chrono::steady_clock;

  void reset(std::int64_t positionNs = 0);
  std::int64_t update(std::optional<std::int64_t> queriedPositionNs,
                      std::int64_t latestAudioPtsNs,
                      SteadyClock::time_point now = SteadyClock::now());
  [[nodiscard]] std::int64_t positionNs() const { return estimatedNs_; }
  [[nodiscard]] std::uint64_t queryFailureCount() const { return failures_; }

 private:
  bool initialized_{false};
  bool querySynchronized_{false};
  std::int64_t estimatedNs_{0};
  std::int64_t lastValidNs_{0};
  SteadyClock::time_point lastUpdate_{};
  SteadyClock::time_point lastValidAt_{};
  std::uint64_t failures_{0};
};

}  // namespace fredplayer
