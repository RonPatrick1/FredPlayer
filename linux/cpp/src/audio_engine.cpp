#include "fredplayer/audio_engine.hpp"

#include <gst/audio/audio.h>

#include <chrono>
#include <filesystem>
#include <stdexcept>

namespace fredplayer {
namespace {

GstElement* make(const char* factory, const char* name) {
  auto* element = gst_element_factory_make(factory, name);
  if (!element) throw std::runtime_error(std::string("Missing GStreamer element: ") + factory);
  return element;
}

void invokeOnMain(std::function<void()> callback) {
  auto* stored = new std::function<void()>(std::move(callback));
  g_idle_add_full(G_PRIORITY_DEFAULT, [](gpointer data) -> gboolean {
    std::unique_ptr<std::function<void()>> action(static_cast<std::function<void()>*>(data));
    (*action)();
    return G_SOURCE_REMOVE;
  }, stored, nullptr);
}

bool sameVisualization(const VisualizationSettings& left,
                       const VisualizationSettings& right) {
  return left.updateFps == right.updateFps &&
      left.waveformWindowMs == right.waveformWindowMs &&
      left.fftScale == right.fftScale &&
      left.fftColumns == right.fftColumns &&
      left.fftSize == right.fftSize &&
      left.fftSmoothing == right.fftSmoothing;
}

}  // namespace

AudioEngine::AudioEngine(AudioCallbacks callbacks)
    : callbacks_(std::move(callbacks)), dsp_(48000), visualizer_(48000) {
  static std::once_flag initialized;
  std::call_once(initialized, [] { gst_init(nullptr, nullptr); });
  resetPositionClock();
  clockThread_ = std::thread([this] { clockLoop(); });
}

AudioEngine::~AudioEngine() {
  callbacks_ = {};
  stop();
  clockStopping_.store(true);
  clockCv_.notify_all();
  if (clockThread_.joinable()) clockThread_.join();
  generation_.fetch_add(1);
  if (cacheFetchThread_.joinable()) cacheFetchThread_.join();
}

void AudioEngine::configure(double outputLevel, double levelingStrength,
                            const LevelingSettings& leveling,
                            const VisualizationSettings& visualization,
                            int visualDelayMs) {
  auto normalizedVisualization = visualization;
  normalizedVisualization.normalize();
  std::lock_guard lock(mutex_);
  const auto visualizationChanged =
      !sameVisualization(visualization_, normalizedVisualization);
  outputLevel_ = outputLevel;
  levelingStrength_ = levelingStrength;
  leveling_ = leveling;
  visualization_ = normalizedVisualization;
  visualDelayMs_.store(clamp(visualDelayMs, 0, 1500));
  dsp_.configure(outputLevel_, levelingStrength_, leveling_);
  if (visualizationChanged) visualizer_.setSettings(visualization_);
}

void AudioEngine::configureServer(std::string baseUrl, std::string token) {
  std::lock_guard lock(mutex_);
  serverToken_ = token;
  server_.configure(std::move(baseUrl), std::move(token));
}

bool AudioEngine::play(const TrackEntry& track, std::int64_t positionMs,
                       bool initiallyPaused) {
  stop();
  if (cacheFetchThread_.joinable()) cacheFetchThread_.join();
  const auto generation = generation_.fetch_add(1) + 1;
  std::string playbackUri = track.path;
  bool hasStreamTicket = false;
  if (track.remote) {
    ServerClient server;
    {
      std::lock_guard lock(mutex_);
      server = server_;
    }
    if (const auto ticket = server.ticketedStreamUrl(track)) {
      playbackUri = *ticket;
      hasStreamTicket = true;
    }
  }
  try {
    std::lock_guard lock(mutex_);
    currentTrack_ = track;
    sourceNeedsAuthorization_.store(track.remote && !hasStreamTicket);
    dsp_.configure(outputLevel_, levelingStrength_, leveling_, loadCachedTrackProfile(track.path));
    visualizer_.reset(positionMs * 1'000'000);
    resetPositionClock(positionMs * 1'000'000);
    pipeline_ = gst_pipeline_new("fredplayer-native-pipeline");
    decode_ = make("uridecodebin", "fred-decode");
    auto* inputQueue = make("queue", "fred-input-queue");
    convert_ = make("audioconvert", "fred-to-float");
    auto* resample = make("audioresample", "fred-to-48k");
    auto* capsFilter = make("capsfilter", "fred-dsp-caps");
    dspIdentity_ = make("identity", "fred-native-dsp");
    auto* outputQueue = make("queue", "fred-output-queue");
    auto* outputConvert = make("audioconvert", "fred-output-convert");
    auto* outputResample = make("audioresample", "fred-output-resample");
    auto* sink = make("autoaudiosink", "fred-audio-output");
    auto* caps = gst_caps_from_string("audio/x-raw,format=F32LE,channels=2,rate=48000,layout=interleaved");
    g_object_set(capsFilter, "caps", caps, nullptr); gst_caps_unref(caps);
    g_object_set(inputQueue, "max-size-time", static_cast<guint64>(500 * GST_MSECOND), nullptr);
    g_object_set(outputQueue, "max-size-time", static_cast<guint64>(500 * GST_MSECOND), nullptr);

    gst_bin_add_many(GST_BIN(pipeline_), decode_, inputQueue, convert_, resample,
                     capsFilter, dspIdentity_, outputQueue, outputConvert,
                     outputResample, sink, nullptr);
    if (!gst_element_link_many(inputQueue, convert_, resample, capsFilter,
                               dspIdentity_, outputQueue, outputConvert,
                               outputResample, sink, nullptr))
      throw std::runtime_error("Could not link the native audio pipeline");
    g_signal_connect(decode_, "pad-added", G_CALLBACK(padAddedThunk), this);
    g_signal_connect(decode_, "source-setup", G_CALLBACK(sourceSetupThunk), this);
    GstPad* probePad = gst_element_get_static_pad(dspIdentity_, "sink");
    gst_pad_add_probe(probePad, GST_PAD_PROBE_TYPE_BUFFER, dspProbeThunk, this, nullptr);
    gst_object_unref(probePad);

    std::string uri;
    if (track.remote) uri = playbackUri;
    else {
      GError* error = nullptr;
      gchar* converted = gst_filename_to_uri(track.path.c_str(), &error);
      if (!converted) {
        const auto message = error ? error->message : "Invalid local file path";
        g_clear_error(&error); throw std::runtime_error(message);
      }
      uri = converted; g_free(converted);
    }
    g_object_set(decode_, "uri", uri.c_str(), nullptr);
    GstBus* bus = gst_element_get_bus(pipeline_);
    busWatch_ = gst_bus_add_watch(bus, busMessageThunk, this);
    gst_object_unref(bus);
    auto state = gst_element_set_state(pipeline_, initiallyPaused ? GST_STATE_PAUSED : GST_STATE_PLAYING);
    if (state == GST_STATE_CHANGE_FAILURE) throw std::runtime_error("GStreamer rejected playback");
    paused_.store(initiallyPaused);
    playing_.store(true);
    resetPositionClock(positionMs * 1'000'000);
    if (positionMs > 0) {
      gst_element_seek_simple(pipeline_, GST_FORMAT_TIME,
          static_cast<GstSeekFlags>(GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_KEY_UNIT),
          positionMs * GST_MSECOND);
    }
  } catch (const std::exception& error) {
    destroyPipeline();
    if (callbacks_.onError) callbacks_.onError(error.what());
    return false;
  }
  clockCv_.notify_all();
  startServerVisualFetch(track, generation);
  if (callbacks_.onStateChanged) callbacks_.onStateChanged();
  return true;
}

void AudioEngine::pause() {
  const auto position = estimatedPositionNs();
  std::lock_guard lock(mutex_);
  if (pipeline_) gst_element_set_state(pipeline_, GST_STATE_PAUSED);
  paused_.store(true);
  resetPositionClock(position);
  clockCv_.notify_all();
  if (callbacks_.onStateChanged) callbacks_.onStateChanged();
}

void AudioEngine::resume() {
  const auto position = estimatedPositionNs();
  std::lock_guard lock(mutex_);
  if (pipeline_) gst_element_set_state(pipeline_, GST_STATE_PLAYING);
  paused_.store(false);
  resetPositionClock(position);
  clockCv_.notify_all();
  if (callbacks_.onStateChanged) callbacks_.onStateChanged();
}

void AudioEngine::stop() {
  generation_.fetch_add(1);
  std::lock_guard lock(mutex_);
  destroyPipeline();
  playing_.store(false); paused_.store(false); durationMs_.store(0);
  visualizer_.reset(); resetPositionClock();
  clockCv_.notify_all();
  if (callbacks_.onStateChanged) callbacks_.onStateChanged();
}

bool AudioEngine::seek(std::int64_t positionMs) {
  std::lock_guard lock(mutex_);
  if (!pipeline_) return false;
  const auto success = gst_element_seek_simple(pipeline_, GST_FORMAT_TIME,
      static_cast<GstSeekFlags>(GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_KEY_UNIT),
      std::max<std::int64_t>(0, positionMs) * GST_MSECOND);
  if (success) {
    resetPositionClock(positionMs * 1'000'000);
    visualizer_.reset(positionMs * 1'000'000, false);
    clockCv_.notify_all();
  }
  return success;
}

bool AudioEngine::paused() const { return paused_.load(); }
bool AudioEngine::playing() const { return playing_.load(); }

std::int64_t AudioEngine::positionMs() const {
  return estimatedPositionNs() / 1'000'000;
}

std::int64_t AudioEngine::durationMs() const { return durationMs_.load(); }

std::shared_ptr<const VisualizationFrame> AudioEngine::currentVisualization() {
  return visualizer_.frameAt(estimatedPositionNs(), visualDelayMs_.load());
}

void AudioEngine::resetPositionClock(std::int64_t positionNs) {
  const auto now = PresentationClock::SteadyClock::now();
  std::lock_guard lock(positionMutex_);
  clock_.reset(positionNs);
  positionAnchorNs_ = std::max<std::int64_t>(0, positionNs);
  positionAnchorAt_ = now;
}

std::int64_t AudioEngine::estimatedPositionNs() const {
  const auto now = PresentationClock::SteadyClock::now();
  std::int64_t position = 0;
  {
    std::lock_guard lock(positionMutex_);
    position = positionAnchorNs_;
    if (playing_.load() && !paused_.load()) {
      position += std::max<std::int64_t>(0,
          std::chrono::duration_cast<std::chrono::nanoseconds>(
              now - positionAnchorAt_).count());
    }
  }
  const auto latestAudio = visualizer_.latestAudioPtsNs();
  if (latestAudio > 0) position = std::min(position, latestAudio + 250'000'000);
  const auto duration = durationMs_.load();
  if (duration > 0) position = std::min(position, duration * 1'000'000);
  return std::max<std::int64_t>(0, position);
}

void AudioEngine::clockLoop() {
  unsigned durationSample = 0;
  while (!clockStopping_.load()) {
    GstElement* pipeline = nullptr;
    if (playing_.load()) {
      std::lock_guard lock(mutex_);
      if (pipeline_) pipeline = GST_ELEMENT(gst_object_ref(pipeline_));
    }

    std::optional<std::int64_t> queried;
    if (pipeline) {
      gint64 position = 0;
      if (gst_element_query_position(pipeline, GST_FORMAT_TIME, &position) &&
          position >= 0) queried = position;
      if (++durationSample >= 20 || durationMs_.load() == 0) {
        gint64 duration = 0;
        if (gst_element_query_duration(pipeline, GST_FORMAT_TIME, &duration) &&
            duration >= 0) durationMs_.store(duration / GST_MSECOND);
        durationSample = 0;
      }
      gst_object_unref(pipeline);
    }

    const auto now = PresentationClock::SteadyClock::now();
    {
      std::lock_guard lock(positionMutex_);
      if (!playing_.load()) {
        positionAnchorNs_ = 0;
        positionAnchorAt_ = now;
      } else if (paused_.load()) {
        if (queried) {
          clock_.reset(*queried);
          positionAnchorNs_ = *queried;
        }
        positionAnchorAt_ = now;
      } else {
        positionAnchorNs_ = clock_.update(
            queried, visualizer_.latestAudioPtsNs(), now);
        positionAnchorAt_ = now;
      }
    }

    std::unique_lock waitLock(clockWaitMutex_);
    clockCv_.wait_for(waitLock, std::chrono::milliseconds(50));
  }
}

gboolean AudioEngine::busMessageThunk(GstBus*, GstMessage* message, gpointer data) {
  return static_cast<AudioEngine*>(data)->onBusMessage(message);
}

gboolean AudioEngine::onBusMessage(GstMessage* message) {
  if (GST_MESSAGE_TYPE(message) == GST_MESSAGE_EOS) {
    playing_.store(false); paused_.store(false);
    if (callbacks_.onEnded) invokeOnMain(callbacks_.onEnded);
  } else if (GST_MESSAGE_TYPE(message) == GST_MESSAGE_ERROR) {
    GError* error = nullptr; gchar* debug = nullptr;
    gst_message_parse_error(message, &error, &debug);
    const std::string text = error ? error->message : "Unknown playback error";
    g_clear_error(&error); g_free(debug);
    playing_.store(false);
    if (callbacks_.onError) invokeOnMain([callback = callbacks_.onError, text] { callback(text); });
  } else if (GST_MESSAGE_TYPE(message) == GST_MESSAGE_STATE_CHANGED &&
             GST_MESSAGE_SRC(message) == GST_OBJECT(pipeline_)) {
    if (callbacks_.onStateChanged) callbacks_.onStateChanged();
  }
  return G_SOURCE_CONTINUE;
}

void AudioEngine::padAddedThunk(GstElement*, GstPad* pad, gpointer data) {
  static_cast<AudioEngine*>(data)->onPadAdded(pad);
}

void AudioEngine::onPadAdded(GstPad* pad) {
  GstCaps* caps = gst_pad_get_current_caps(pad);
  if (!caps) caps = gst_pad_query_caps(pad, nullptr);
  const auto* structure = caps ? gst_caps_get_structure(caps, 0) : nullptr;
  const auto* name = structure ? gst_structure_get_name(structure) : "";
  if (g_str_has_prefix(name, "audio/")) {
    GstElement* queue = gst_bin_get_by_name(GST_BIN(pipeline_), "fred-input-queue");
    GstPad* sink = gst_element_get_static_pad(queue, "sink");
    if (!gst_pad_is_linked(sink)) gst_pad_link(pad, sink);
    gst_object_unref(sink); gst_object_unref(queue);
  }
  if (caps) gst_caps_unref(caps);
}

void AudioEngine::sourceSetupThunk(GstElement*, GstElement* source, gpointer data) {
  auto* self = static_cast<AudioEngine*>(data);
  if (!self->sourceNeedsAuthorization_.load() || self->serverToken_.empty()) return;
  auto* type = G_OBJECT_GET_CLASS(source);
  if (!g_object_class_find_property(type, "extra-headers")) return;
  GstStructure* headers = gst_structure_new("headers", "Authorization", G_TYPE_STRING,
      ("Bearer " + self->serverToken_).c_str(), nullptr);
  g_object_set(source, "extra-headers", headers, nullptr);
  gst_structure_free(headers);
}

GstPadProbeReturn AudioEngine::dspProbeThunk(GstPad*, GstPadProbeInfo* info,
                                             gpointer data) {
  return static_cast<AudioEngine*>(data)->processBuffer(info);
}

GstPadProbeReturn AudioEngine::processBuffer(GstPadProbeInfo* info) {
  auto* buffer = GST_PAD_PROBE_INFO_BUFFER(info);
  if (!buffer) return GST_PAD_PROBE_OK;
  buffer = gst_buffer_make_writable(buffer);
  GST_PAD_PROBE_INFO_DATA(info) = buffer;
  GstMapInfo map{};
  if (!gst_buffer_map(buffer, &map, static_cast<GstMapFlags>(GST_MAP_READ | GST_MAP_WRITE)))
    return GST_PAD_PROBE_OK;
  const auto frames = map.size / (sizeof(float) * 2);
  auto* samples = reinterpret_cast<float*>(map.data);
  dsp_.process(samples, frames);
  const auto pts = GST_BUFFER_PTS_IS_VALID(buffer) ? static_cast<std::int64_t>(GST_BUFFER_PTS(buffer)) :
      visualizer_.latestAudioPtsNs();
  visualizer_.submit(samples, frames, pts);
  gst_buffer_unmap(buffer, &map);
  return GST_PAD_PROBE_OK;
}

void AudioEngine::startServerVisualFetch(TrackEntry track, std::uint64_t generation) {
  ServerClient server;
  VisualizationSettings settings;
  bool canonical = false;
  {
    std::lock_guard lock(mutex_);
    server = server_;
    settings = visualization_;
    canonical = leveling_.isCanonical();
  }
  cacheFetchThread_ = std::thread([this, track = std::move(track), generation,
                                   server = std::move(server), settings, canonical]() mutable {
    LegacyVisualizationCache local;
    auto localFrames = local.loadFrames(track.path, settings);
    if (!localFrames.empty() && generation_.load() == generation)
      visualizer_.setCachedFrames(std::move(localFrames));
    if (!track.remote || !canonical || generation_.load() != generation) return;
    for (int attempt = 0; attempt < 4 && generation_.load() == generation; ++attempt) {
      const auto result = server.linuxVisual(track, settings);
      if (result.status == 200) {
        auto decoded = decodeFlv1(result.body);
        if (decoded && decoded->settings.variantKey() == settings.variantKey() &&
            generation_.load() == generation)
          visualizer_.setCachedFrames(std::move(decoded->frames));
        return;
      }
      if (result.status != 202) return;
      const auto waitSeconds = std::max<long>(1, result.retryAfterSeconds ? result.retryAfterSeconds : 5);
      const auto steps = waitSeconds * 4;
      for (long step = 0; step < steps && generation_.load() == generation; ++step)
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
    }
  });
}

void AudioEngine::destroyPipeline() {
  if (!pipeline_) return;
  gst_element_set_state(pipeline_, GST_STATE_NULL);
  if (busWatch_) { g_source_remove(busWatch_); busWatch_ = 0; }
  gst_object_unref(pipeline_);
  pipeline_ = decode_ = convert_ = dspIdentity_ = nullptr;
}

}  // namespace fredplayer
