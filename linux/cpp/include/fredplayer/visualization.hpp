#pragma once

#include "fredplayer/types.hpp"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

namespace fredplayer {

class VisualizationEngine {
 public:
  explicit VisualizationEngine(int sampleRate = 48000);
  ~VisualizationEngine();
  VisualizationEngine(const VisualizationEngine&) = delete;
  VisualizationEngine& operator=(const VisualizationEngine&) = delete;

  void setSettings(VisualizationSettings settings);
  void reset(std::int64_t startPtsNs = 0, bool clearCached = true);
  void submit(const float* interleavedStereo, std::size_t frames,
              std::int64_t ptsNs);
  void setCachedFrames(std::vector<VisualizationFrame> frames);
  void clearCachedFrames();
  [[nodiscard]] std::shared_ptr<const VisualizationFrame> frameAt(
      std::int64_t presentationNs, int delayMs) const;
  [[nodiscard]] std::int64_t latestAudioPtsNs() const {
    return latestAudioPtsNs_.load(std::memory_order_relaxed);
  }
  [[nodiscard]] std::uint64_t droppedBlockCount() const {
    return droppedBlocks_.load(std::memory_order_relaxed);
  }
  [[nodiscard]] bool cachedFramesActive() const {
    return cachedActive_.load(std::memory_order_acquire);
  }
  [[nodiscard]] std::uint64_t queuedBlockCount() const {
    return queuedBlocks_.load(std::memory_order_relaxed);
  }
  [[nodiscard]] std::uint64_t producedFrameCount() const {
    return producedFrames_.load(std::memory_order_relaxed);
  }
  [[nodiscard]] double averageAnalysisMs() const {
    return averageFrameComputeNs_.load(std::memory_order_relaxed) / 1'000'000.0;
  }
  [[nodiscard]] double estimatedAnalysisCapacityFps() const {
    const auto nanoseconds = averageFrameComputeNs_.load(std::memory_order_relaxed);
    return nanoseconds > 0 ? 1'000'000'000.0 / nanoseconds : 0.0;
  }

 private:
  struct AudioBlock {
    std::vector<float> mono;
    std::int64_t ptsNs{0};
  };

  void workerLoop();
  VisualizationFrame buildFrame(std::int64_t ptsNs,
                                const std::vector<float>& samples,
                                const VisualizationSettings& settings);

  int sampleRate_;
  mutable std::mutex settingsMutex_;
  VisualizationSettings settings_;
  mutable std::mutex queueMutex_;
  std::condition_variable queueCv_;
  std::deque<AudioBlock> queue_;
  static constexpr std::size_t kMaxQueuedBlocks = 32;
  mutable std::mutex framesMutex_;
  std::deque<std::shared_ptr<const VisualizationFrame>> liveFrames_;
  std::vector<std::shared_ptr<const VisualizationFrame>> cachedFrames_;
  std::atomic<std::int64_t> latestAudioPtsNs_{0};
  std::atomic<std::uint64_t> droppedBlocks_{0};
  std::atomic<std::uint64_t> queuedBlocks_{0};
  std::atomic<std::uint64_t> producedFrames_{0};
  std::atomic<std::int64_t> averageFrameComputeNs_{0};
  std::atomic<std::uint64_t> resetGeneration_{0};
  std::atomic<bool> cachedActive_{false};
  std::atomic<bool> stopping_{false};
  std::thread worker_;
};

}  // namespace fredplayer
