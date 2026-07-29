#include "fredplayer/visualization.hpp"

#include <fftw3.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <numeric>
#include <stdexcept>

namespace fredplayer {
namespace {
constexpr double kPi = 3.14159265358979323846;

struct FftWorkspace {
  ~FftWorkspace() { if (plan) fftwf_destroy_plan(plan); if(output)fftwf_free(output); }

  void prepare(int requestedSize) {
    if (requestedSize == size && plan) {
      std::fill(input.begin(),input.end(),0.0F);
      return;
    }
    if (plan) { fftwf_destroy_plan(plan); plan=nullptr; }
    if(output){fftwf_free(output);output=nullptr;}
    size=requestedSize;
    input.assign(size,0.0F);
    output=fftwf_alloc_complex(size/2+1);
    if(!output)throw std::runtime_error("FFTW could not allocate a visualization buffer");
    magnitudes.assign(size/2+1,0.0F);
    window.resize(size);windowSum=0;
    for(int i=0;i<size;++i){window[i]=.5F-.5F*std::cos(static_cast<float>(2.0*kPi*i/(size-1)));windowSum+=window[i];}
    plan=fftwf_plan_dft_r2c_1d(size,input.data(),output,FFTW_ESTIMATE);
    if(!plan)throw std::runtime_error("FFTW could not create a visualization plan");
  }

  int size{0};
  std::vector<float> input;
  fftwf_complex* output{nullptr};
  std::vector<float> magnitudes;
  std::vector<float> window;
  double windowSum{1};
  fftwf_plan plan{nullptr};
};
}

VisualizationEngine::VisualizationEngine(int sampleRate) : sampleRate_(sampleRate) {
  settings_.normalize();
  worker_ = std::thread([this] { workerLoop(); });
}

VisualizationEngine::~VisualizationEngine() {
  stopping_.store(true);
  queueCv_.notify_all();
  if (worker_.joinable()) worker_.join();
}

void VisualizationEngine::setSettings(VisualizationSettings settings) {
  settings.normalize();
  { std::lock_guard lock(settingsMutex_); settings_ = std::move(settings); }
  averageFrameComputeNs_.store(0, std::memory_order_relaxed);
  producedFrames_.store(0, std::memory_order_relaxed);
  reset(latestAudioPtsNs_.load());
}

void VisualizationEngine::reset(std::int64_t startPtsNs, bool clearCached) {
  if (clearCached) cachedActive_.store(false, std::memory_order_release);
  { std::lock_guard lock(queueMutex_); queue_.clear(); }
  {
    std::lock_guard lock(framesMutex_);
    liveFrames_.clear();
    if (clearCached) cachedFrames_.clear();
  }
  resetGeneration_.fetch_add(1);
  latestAudioPtsNs_.store(std::max<std::int64_t>(0, startPtsNs));
}

void VisualizationEngine::submit(const float* values, std::size_t frames,
                                 std::int64_t ptsNs) {
  if (!values || !frames) return;
  const auto blockEnd = std::max<std::int64_t>(0, ptsNs) +
      static_cast<std::int64_t>(frames * 1'000'000'000ULL / sampleRate_);
  latestAudioPtsNs_.store(blockEnd, std::memory_order_relaxed);
  if (cachedActive_.load(std::memory_order_acquire)) return;
  AudioBlock block;
  block.ptsNs = std::max<std::int64_t>(0, ptsNs);
  block.mono.resize(frames);
  for (std::size_t i = 0; i < frames; ++i)
    block.mono[i] = clamp((values[i * 2] + values[i * 2 + 1]) * .5F, -1.0F, 1.0F);
  {
    std::lock_guard lock(queueMutex_);
    if (queue_.size() >= kMaxQueuedBlocks) {
      queue_.pop_front();
      droppedBlocks_.fetch_add(1);
    }
    queue_.push_back(std::move(block));
    queuedBlocks_.fetch_add(1, std::memory_order_relaxed);
  }
  queueCv_.notify_one();
}

void VisualizationEngine::setCachedFrames(std::vector<VisualizationFrame> frames) {
  if (frames.empty()) { clearCachedFrames(); return; }
  std::sort(frames.begin(), frames.end(), [](const auto& a, const auto& b) { return a.ptsNs < b.ptsNs; });
  cachedActive_.store(true, std::memory_order_release);
  { std::lock_guard lock(queueMutex_); queue_.clear(); }
  {
    std::lock_guard lock(framesMutex_);
    liveFrames_.clear();
    cachedFrames_.clear();
    cachedFrames_.reserve(frames.size());
    for (auto& frame : frames)
      cachedFrames_.push_back(std::make_shared<const VisualizationFrame>(std::move(frame)));
  }
}

void VisualizationEngine::clearCachedFrames() {
  cachedActive_.store(false, std::memory_order_release);
  std::lock_guard lock(framesMutex_);
  cachedFrames_.clear();
}

std::shared_ptr<const VisualizationFrame> VisualizationEngine::frameAt(
    std::int64_t presentationNs, int delayMs) const {
  const auto target = std::max<std::int64_t>(0, presentationNs -
      static_cast<std::int64_t>(clamp(delayMs, 0, 1500)) * 1'000'000);
  std::lock_guard lock(framesMutex_);
  const auto choose = [target](const auto& frames) -> std::shared_ptr<const VisualizationFrame> {
    if (frames.empty()) return {};
    auto found = std::upper_bound(frames.begin(), frames.end(), target,
      [](std::int64_t time, const auto& frame) { return time < frame->ptsNs; });
    if (found == frames.begin()) return *found;
    return *std::prev(found);
  };
  if (!cachedFrames_.empty()) return choose(cachedFrames_);
  return choose(liveFrames_);
}

void VisualizationEngine::workerLoop() {
  std::vector<float> ring;
  std::int64_t nextFrameNs = 0;
  auto observedReset = resetGeneration_.load();
  while (!stopping_.load()) {
    AudioBlock block;
    {
      std::unique_lock lock(queueMutex_);
      queueCv_.wait(lock, [this] { return stopping_.load() || !queue_.empty(); });
      if (stopping_.load()) break;
      block = std::move(queue_.front());
      queue_.pop_front();
    }
    if (cachedActive_.load(std::memory_order_acquire)) continue;
    VisualizationSettings settings;
    { std::lock_guard lock(settingsMutex_); settings = settings_; }
    const auto currentReset = resetGeneration_.load();
    if (currentReset != observedReset) {
      ring.clear();
      nextFrameNs = block.ptsNs;
      observedReset = currentReset;
    }
    const auto maxSamples = static_cast<std::size_t>(std::max(settings.fftSize,
        static_cast<int>(std::ceil(sampleRate_ * settings.waveformWindowMs / 1000.0))));
    ring.insert(ring.end(), block.mono.begin(), block.mono.end());
    if (ring.size() > maxSamples * 2)
      ring.erase(ring.begin(), ring.end() - static_cast<std::ptrdiff_t>(maxSamples));
    const auto frameInterval = static_cast<std::int64_t>(std::llround(1'000'000'000.0 / settings.updateFps));
    if (nextFrameNs < block.ptsNs) nextFrameNs = block.ptsNs;
    const auto blockEnd = block.ptsNs + static_cast<std::int64_t>(block.mono.size() * 1'000'000'000ULL / sampleRate_);
    while (nextFrameNs <= blockEnd) {
      const auto started = std::chrono::steady_clock::now();
      auto frame = std::make_shared<const VisualizationFrame>(buildFrame(nextFrameNs, ring, settings));
      const auto computeNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
          std::chrono::steady_clock::now() - started).count();
      const auto previous = averageFrameComputeNs_.load(std::memory_order_relaxed);
      averageFrameComputeNs_.store(previous > 0 ? (previous * 7 + computeNs) / 8 : computeNs,
                                   std::memory_order_relaxed);
      producedFrames_.fetch_add(1, std::memory_order_relaxed);
      {
        std::lock_guard lock(framesMutex_);
        liveFrames_.push_back(std::move(frame));
        while (!liveFrames_.empty() && liveFrames_.front()->ptsNs < nextFrameNs - 10'000'000'000LL)
          liveFrames_.pop_front();
      }
      nextFrameNs += frameInterval;
    }
  }
}

VisualizationFrame VisualizationEngine::buildFrame(
    std::int64_t ptsNs, const std::vector<float>& samples,
    const VisualizationSettings& settings) {
  VisualizationFrame frame;
  frame.ptsNs = ptsNs;
  constexpr int waveformPoints = 512;
  frame.waveform.assign(waveformPoints, 0.0F);
  const auto windowSamples = std::max(1, static_cast<int>(sampleRate_ * settings.waveformWindowMs / 1000.0));
  const auto available = std::min<std::size_t>(samples.size(), windowSamples);
  const auto start = samples.size() - available;
  for (int point = 0; point < waveformPoints; ++point) {
    const auto a = start + static_cast<std::size_t>(available * point / waveformPoints);
    auto b = start + static_cast<std::size_t>(available * (point + 1) / waveformPoints);
    b = std::max(a + 1, std::min(b, samples.size()));
    float chosen = 0;
    for (auto i = a; i < b; ++i)
      if (std::abs(samples[i]) > std::abs(chosen)) chosen = samples[i];
    frame.waveform[point] = chosen;
  }
  double sum = 0;
  for (float sample : frame.waveform) { frame.peak = std::max(frame.peak, std::abs(sample)); sum += sample * sample; }
  frame.rms = static_cast<float>(std::sqrt(sum / frame.waveform.size()));

  const int size = settings.fftSize;
  thread_local FftWorkspace fft;
  fft.prepare(size);
  const auto count = std::min<std::size_t>(samples.size(), size);
  std::copy(samples.end() - static_cast<std::ptrdiff_t>(count), samples.end(),
            fft.input.end() - static_cast<std::ptrdiff_t>(count));
  for (int i = 0; i < size; ++i) fft.input[i] *= fft.window[i];
  fftwf_execute(fft.plan);
  for (int i = 1; i <= size / 2; ++i)
    fft.magnitudes[i] = static_cast<float>(2.0 * std::hypot(fft.output[i][0], fft.output[i][1]) / std::max(1.0, fft.windowSum));

  frame.spectrum.assign(settings.fftColumns, 0.0F);
  const auto nyquist = sampleRate_ * .5;
  const auto low = 32.0;
  const auto high = std::min(18000.0, nyquist);
  for (int band = 0; band < settings.fftColumns; ++band) {
    double startHz, endHz;
    if (settings.fftScale == "linear") {
      startHz = low + (high - low) * band / settings.fftColumns;
      endHz = low + (high - low) * (band + 1) / settings.fftColumns;
    } else {
      startHz = low * std::pow(high / low, static_cast<double>(band) / settings.fftColumns);
      endHz = low * std::pow(high / low, static_cast<double>(band + 1) / settings.fftColumns);
    }
    const int first = clamp(static_cast<int>(startHz * size / sampleRate_), 1, size / 2);
    const int last = clamp(std::max(first + 1, static_cast<int>(endHz * size / sampleRate_)), 1, size / 2 + 1);
    float amplitude = 0;
    for (int bin = first; bin < last; ++bin) amplitude = std::max(amplitude, fft.magnitudes[bin]);
    const auto db = 20.0 * std::log10(std::max(amplitude, .000001F));
    frame.spectrum[band] = static_cast<float>(clamp((db + 80.0) / 80.0, 0.0, 1.0));
  }
  return frame;
}

}  // namespace fredplayer
