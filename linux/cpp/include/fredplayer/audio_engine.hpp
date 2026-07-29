#pragma once

#include "fredplayer/clock.hpp"
#include "fredplayer/dsp.hpp"
#include "fredplayer/server_client.hpp"
#include "fredplayer/types.hpp"
#include "fredplayer/visualization.hpp"

#include <gst/gst.h>

#include <atomic>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>

namespace fredplayer {

struct AudioCallbacks {
  std::function<void()> onEnded;
  std::function<void(const std::string&)> onError;
  std::function<void()> onStateChanged;
};

class AudioEngine {
 public:
  explicit AudioEngine(AudioCallbacks callbacks = {});
  ~AudioEngine();
  AudioEngine(const AudioEngine&) = delete;
  AudioEngine& operator=(const AudioEngine&) = delete;

  void configure(double outputLevel, double levelingStrength,
                 const LevelingSettings& leveling,
                 const VisualizationSettings& visualization,
                 int visualDelayMs);
  void configureServer(std::string baseUrl, std::string token);
  bool play(const TrackEntry& track, std::int64_t positionMs = 0,
            bool initiallyPaused = false);
  void pause();
  void resume();
  void stop();
  bool seek(std::int64_t positionMs);
  [[nodiscard]] bool paused() const;
  [[nodiscard]] bool playing() const;
  [[nodiscard]] std::int64_t positionMs() const;
  [[nodiscard]] std::int64_t durationMs() const;
  [[nodiscard]] std::shared_ptr<const VisualizationFrame> currentVisualization();
  [[nodiscard]] VisualizationEngine& visualizer() { return visualizer_; }

 private:
  static gboolean busMessageThunk(GstBus* bus, GstMessage* message, gpointer data);
  gboolean onBusMessage(GstMessage* message);
  static void padAddedThunk(GstElement* source, GstPad* pad, gpointer data);
  void onPadAdded(GstPad* pad);
  static void sourceSetupThunk(GstElement* decode, GstElement* source, gpointer data);
  static GstPadProbeReturn dspProbeThunk(GstPad* pad, GstPadProbeInfo* info,
                                        gpointer data);
  GstPadProbeReturn processBuffer(GstPadProbeInfo* info);
  void startServerVisualFetch(TrackEntry track, std::uint64_t generation);
  void clockLoop();
  void resetPositionClock(std::int64_t positionNs = 0);
  [[nodiscard]] std::int64_t estimatedPositionNs() const;
  void destroyPipeline();

  AudioCallbacks callbacks_;
  mutable std::mutex mutex_;
  GstElement* pipeline_{nullptr};
  GstElement* decode_{nullptr};
  GstElement* convert_{nullptr};
  GstElement* dspIdentity_{nullptr};
  guint busWatch_{0};
  DspProcessor dsp_;
  VisualizationEngine visualizer_;
  mutable std::mutex positionMutex_;
  PresentationClock clock_;
  std::int64_t positionAnchorNs_{0};
  PresentationClock::SteadyClock::time_point positionAnchorAt_{};
  ServerClient server_;
  std::string serverToken_;
  std::atomic<bool> sourceNeedsAuthorization_{false};
  TrackEntry currentTrack_;
  LevelingSettings leveling_;
  VisualizationSettings visualization_;
  double outputLevel_{0.55};
  double levelingStrength_{0.9};
  std::atomic<int> visualDelayMs_{0};
  std::atomic<std::int64_t> durationMs_{0};
  std::atomic<bool> paused_{false};
  std::atomic<bool> playing_{false};
  std::atomic<std::uint64_t> generation_{0};
  std::atomic<bool> clockStopping_{false};
  std::mutex clockWaitMutex_;
  std::condition_variable clockCv_;
  std::thread clockThread_;
  std::thread cacheFetchThread_;
};

}  // namespace fredplayer
