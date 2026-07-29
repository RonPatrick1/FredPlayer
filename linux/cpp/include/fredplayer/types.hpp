#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace fredplayer {

template <typename T>
constexpr T clamp(T value, T low, T high) {
  return std::max(low, std::min(high, value));
}

struct LevelingSettings {
  double analysisSeconds{10.0};
  double levelAttackMs{15.0};
  double levelReleaseMs{750.0};
  double gainDownMs{40.0};
  double gainUpMs{2800.0};
  double compressorThreshold{0.68};
  double outputCeiling{0.96};

  void normalize();
  bool isCanonical() const;
};

struct VisualizationSettings {
  double updateFps{30.0};
  double waveformWindowMs{80.0};
  std::string fftScale{"log"};
  int fftColumns{96};
  int fftSize{4096};
  double fftSmoothing{15.0};

  void normalize();
  [[nodiscard]] std::string variantKey() const;
};

struct TrackProfile {
  double rms{0.18};
  double peak{0.0};
};

struct TrackEntry {
  std::string path;
  std::string sourceFolder;
  bool remote{false};
  std::string title;
  std::string artist;
  std::string album;

  [[nodiscard]] std::string displayTitle() const;
  [[nodiscard]] std::string subtitle() const;
  [[nodiscard]] std::string serverPath(const std::string& baseUrl) const;
};

struct SpeakerLatency {
  std::string key;
  std::string label;
  int delayMs{0};
};

struct WindowState {
  int x{80};
  int y{80};
  int width{1120};
  int height{720};
  bool maximized{false};
  int monitorX{0};
  int monitorY{0};
  int monitorWidth{0};
  int monitorHeight{0};
};

struct AppState {
  std::vector<TrackEntry> playlist;
  std::map<std::string, std::vector<TrackEntry>> namedPlaylists;
  std::string activePlaylist{"Default"};
  double outputLevel{0.55};
  double levelingStrength{0.9};
  LevelingSettings leveling;
  VisualizationSettings visualization;
  WindowState window;
  std::string serverBaseUrl;
  std::string serverToken;
  bool shuffleEnabled{true};
  std::map<std::string, SpeakerLatency> speakerLatencies;
  std::string selectedMicrophone;
};

struct VisualizationFrame {
  std::vector<float> waveform;
  std::vector<float> spectrum;
  float peak{0.0F};
  float rms{0.0F};
  std::int64_t ptsNs{0};
};

}  // namespace fredplayer
