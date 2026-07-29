#include "fredplayer/state.hpp"

#include <glib.h>
#include <nlohmann/json.hpp>

#include <cstdlib>
#include <fstream>
#include <iomanip>
#include <set>
#include <sstream>
#include <stdexcept>
#include <system_error>

namespace fredplayer {
namespace {

using json = nlohmann::json;

std::filesystem::path homeDirectory() {
  if (const char* home = std::getenv("HOME"); home && *home) return home;
  return std::filesystem::current_path();
}

double number(const json& value, const char* key, double fallback) {
  if (!value.contains(key) || !value[key].is_number()) return fallback;
  return value[key].get<double>();
}

int integer(const json& value, const char* key, int fallback) {
  if (!value.contains(key) || !value[key].is_number_integer()) return fallback;
  return value[key].get<int>();
}

std::string text(const json& value, const char* key, std::string fallback = {}) {
  if (!value.contains(key) || !value[key].is_string()) return fallback;
  return value[key].get<std::string>();
}

TrackEntry trackFromJson(const json& value) {
  TrackEntry entry;
  if (value.is_string()) {
    entry.path = value.get<std::string>();
    entry.sourceFolder = std::filesystem::path(entry.path).parent_path().string();
    return entry;
  }
  if (!value.is_object()) return entry;
  entry.path = text(value, "path");
  entry.sourceFolder = text(value, "source_folder");
  entry.remote = value.value("remote", false);
  entry.title = text(value, "title");
  entry.artist = text(value, "artist");
  entry.album = text(value, "album");
  if (entry.sourceFolder.empty() && !entry.remote)
    entry.sourceFolder = std::filesystem::path(entry.path).parent_path().string();
  return entry;
}

json trackToJson(const TrackEntry& entry) {
  json value{{"path", entry.path}, {"source_folder", entry.sourceFolder}};
  if (entry.remote) {
    value["remote"] = true;
    value["title"] = entry.title;
    value["artist"] = entry.artist;
    value["album"] = entry.album;
  }
  return value;
}

std::vector<TrackEntry> playlistFromJson(const json& value) {
  std::vector<TrackEntry> result;
  if (!value.is_array()) return result;
  std::set<std::string> seen;
  for (const auto& item : value) {
    auto entry = trackFromJson(item);
    if (!entry.path.empty() && seen.insert(entry.path).second)
      result.push_back(std::move(entry));
  }
  return result;
}

json playlistToJson(const std::vector<TrackEntry>& entries) {
  json result = json::array();
  for (const auto& entry : entries) result.push_back(trackToJson(entry));
  return result;
}

}  // namespace

void LevelingSettings::normalize() {
  analysisSeconds = clamp(analysisSeconds, 0.0, 45.0);
  levelAttackMs = clamp(levelAttackMs, 1.0, 250.0);
  levelReleaseMs = clamp(levelReleaseMs, 100.0, 5000.0);
  gainDownMs = clamp(gainDownMs, 5.0, 500.0);
  gainUpMs = clamp(gainUpMs, 500.0, 10000.0);
  compressorThreshold = clamp(compressorThreshold, 0.3, 0.95);
  outputCeiling = clamp(outputCeiling, 0.5, 1.0);
}

bool LevelingSettings::isCanonical() const {
  return std::abs(levelAttackMs - 15.0) < .001 &&
         std::abs(levelReleaseMs - 750.0) < .001 &&
         std::abs(gainDownMs - 40.0) < .001 &&
         std::abs(gainUpMs - 2800.0) < .001 &&
         std::abs(compressorThreshold - .68) < .0001 &&
         std::abs(outputCeiling - .96) < .0001;
}

void VisualizationSettings::normalize() {
  static constexpr int choices[] = {512, 1024, 2048, 4096, 8192, 16384, 32768};
  updateFps = clamp(updateFps, 5.0, 144.0);
  waveformWindowMs = clamp(waveformWindowMs, 10.0, 500.0);
  fftColumns = clamp(fftColumns, 24, 256);
  fftSmoothing = clamp(fftSmoothing, 0.0, 100.0);
  fftScale = fftScale == "linear" ? "linear" : "log";
  fftSize = *std::min_element(std::begin(choices), std::end(choices),
      [this](int a, int b) { return std::abs(a - fftSize) < std::abs(b - fftSize); });
}

std::string VisualizationSettings::variantKey() const {
  auto compact = [](double value) {
    std::ostringstream out;
    if (std::abs(value - std::round(value)) < .0005) out << std::llround(value);
    else out << std::fixed << std::setprecision(3) << value;
    auto result = out.str();
    while (result.find('.') != std::string::npos && result.back() == '0') result.pop_back();
    if (!result.empty() && result.back() == '.') result.pop_back();
    return result;
  };
  return "fps" + compact(updateFps) + "-wave" + compact(waveformWindowMs) +
         "-fft" + std::to_string(fftSize) + "-bars" + std::to_string(fftColumns) +
         "-log" + (fftScale == "log" ? "1" : "0") + "-level1";
}

std::string TrackEntry::displayTitle() const {
  if (!title.empty()) return title;
  auto name = std::filesystem::path(path).filename().string();
  auto extension = std::filesystem::path(name).extension().string();
  if (!extension.empty()) name.resize(name.size() - extension.size());
  return name.empty() ? path : name;
}

std::string TrackEntry::subtitle() const {
  if (artist.empty()) return album;
  if (album.empty()) return artist;
  return artist + " - " + album;
}

std::string TrackEntry::serverPath(const std::string& baseUrl) const {
  auto prefix = baseUrl;
  while (!prefix.empty() && prefix.back() == '/') prefix.pop_back();
  prefix += "/stream/";
  if (remote && path.rfind(prefix, 0) == 0) return path.substr(prefix.size());
  return {};
}

std::filesystem::path configDirectory() {
  std::filesystem::path base;
  if (const char* value = std::getenv("XDG_CONFIG_HOME"); value && *value) base = value;
  else base = homeDirectory() / ".config";
  auto path = base / "fredplayer-ubuntu";
  std::error_code error;
  std::filesystem::create_directories(path, error);
  return path;
}

std::filesystem::path dataDirectory() {
  std::filesystem::path base;
  if (const char* value = std::getenv("XDG_DATA_HOME"); value && *value) base = value;
  else base = homeDirectory() / ".local" / "share";
  auto path = base / "fredplayer-ubuntu";
  std::error_code error;
  std::filesystem::create_directories(path, error);
  return path;
}

std::string persistentDeviceId() {
  const auto path = configDirectory() / "device_id.txt";
  std::ifstream input(path);
  std::string value;
  if (input >> value; !value.empty()) return value;
  gchar* generated = g_uuid_string_random();
  value = generated ? generated : "fredplayer-native";
  g_free(generated);
  value.erase(std::remove(value.begin(), value.end(), '-'), value.end());
  std::ofstream(path, std::ios::trunc) << value;
  return value;
}

StateStore::StateStore(std::filesystem::path path) : path_(std::move(path)) {}

AppState StateStore::load() const {
  AppState state;
  json root;
  try {
    std::ifstream input(path_);
    if (input) input >> root;
  } catch (...) { root = json::object(); }
  if (!root.is_object()) root = json::object();

  const auto legacy = playlistFromJson(root.value("playlist", json::array()));
  if (root.contains("named_playlists") && root["named_playlists"].is_object()) {
    for (auto item = root["named_playlists"].begin(); item != root["named_playlists"].end(); ++item) {
      if (!item.key().empty()) state.namedPlaylists[item.key()] = playlistFromJson(item.value());
    }
  }
  if (state.namedPlaylists.empty()) state.namedPlaylists["Default"] = legacy;
  state.activePlaylist = text(root, "active_playlist", state.namedPlaylists.begin()->first);
  if (!state.namedPlaylists.count(state.activePlaylist)) state.activePlaylist = state.namedPlaylists.begin()->first;
  if (root.contains("playlist") && root["playlist"].is_array())
    state.namedPlaylists[state.activePlaylist] = legacy;
  state.playlist = state.namedPlaylists[state.activePlaylist];

  state.outputLevel = clamp(number(root, "output_level", .55), .1, 1.0);
  state.levelingStrength = clamp(number(root, "leveling_strength", .9), 0.0, 1.0);
  if (const auto& value = root.value("leveling_settings", json::object()); value.is_object()) {
    state.leveling.analysisSeconds = number(value, "analysis_seconds", 10);
    state.leveling.levelAttackMs = number(value, "level_attack_ms", 15);
    state.leveling.levelReleaseMs = number(value, "level_release_ms", 750);
    state.leveling.gainDownMs = number(value, "gain_down_ms", 40);
    state.leveling.gainUpMs = number(value, "gain_up_ms", 2800);
    state.leveling.compressorThreshold = number(value, "compressor_threshold", .68);
    state.leveling.outputCeiling = number(value, "output_ceiling", .96);
    state.leveling.normalize();
  }
  if (const auto& value = root.value("visualization_settings", json::object()); value.is_object()) {
    state.visualization.updateFps = number(value, "update_fps", 30);
    state.visualization.waveformWindowMs = number(value, "waveform_window_ms", 80);
    state.visualization.fftScale = text(value, "fft_scale", "log");
    state.visualization.fftColumns = integer(value, "fft_columns", 96);
    state.visualization.fftSize = integer(value, "fft_size", 4096);
    state.visualization.fftSmoothing = number(value, "fft_smoothing", 15);
    state.visualization.normalize();
  }
  if (const auto& value = root.value("window_state", json::object()); value.is_object()) {
    state.window.x = integer(value, "x", 80); state.window.y = integer(value, "y", 80);
    state.window.width = std::max(480, integer(value, "width", 1120));
    state.window.height = std::max(620, integer(value, "height", 720));
    state.window.maximized = value.value("maximized", false);
    state.window.monitorX = integer(value, "monitor_x", 0);
    state.window.monitorY = integer(value, "monitor_y", 0);
    state.window.monitorWidth = std::max(0, integer(value, "monitor_width", 0));
    state.window.monitorHeight = std::max(0, integer(value, "monitor_height", 0));
  }
  state.serverBaseUrl = text(root, "server_base_url");
  while (!state.serverBaseUrl.empty() && state.serverBaseUrl.back() == '/') state.serverBaseUrl.pop_back();
  state.serverToken = text(root, "server_token");
  state.shuffleEnabled = root.value("shuffle_enabled", true);
  state.selectedMicrophone = text(root, "selected_microphone");
  if (const auto& values = root.value("speaker_latencies", json::object()); values.is_object()) {
    for (auto item = values.begin(); item != values.end(); ++item) {
      if (!item.value().is_object() || item.key().empty()) continue;
      state.speakerLatencies[item.key()] = SpeakerLatency{
        item.key(), text(item.value(), "label", item.key()),
        clamp(integer(item.value(), "delay_ms", 0), 0, 1500)};
    }
  }
  return state;
}

void StateStore::save(const AppState& source) const {
  AppState state = source;
  if (!state.namedPlaylists.count(state.activePlaylist)) {
    if (state.namedPlaylists.empty()) state.namedPlaylists["Default"] = state.playlist;
    state.activePlaylist = state.namedPlaylists.begin()->first;
  }
  state.namedPlaylists[state.activePlaylist] = state.playlist;
  json named = json::object();
  for (const auto& [name, entries] : state.namedPlaylists)
    if (!name.empty()) named[name] = playlistToJson(entries);
  json speakers = json::object();
  for (const auto& [key, value] : state.speakerLatencies)
    speakers[key] = {{"label", value.label}, {"delay_ms", clamp(value.delayMs, 0, 1500)}};
  json root{
    {"version", 3}, {"playlist", playlistToJson(state.playlist)},
    {"named_playlists", named}, {"active_playlist", state.activePlaylist},
    {"output_level", clamp(state.outputLevel, .1, 1.0)},
    {"leveling_strength", clamp(state.levelingStrength, 0.0, 1.0)},
    {"leveling_settings", {
      {"analysis_seconds", state.leveling.analysisSeconds},
      {"level_attack_ms", state.leveling.levelAttackMs},
      {"level_release_ms", state.leveling.levelReleaseMs},
      {"gain_down_ms", state.leveling.gainDownMs},
      {"gain_up_ms", state.leveling.gainUpMs},
      {"compressor_threshold", state.leveling.compressorThreshold},
      {"output_ceiling", state.leveling.outputCeiling}}},
    {"visualization_settings", {
      {"update_fps", state.visualization.updateFps},
      {"waveform_window_ms", state.visualization.waveformWindowMs},
      {"fft_scale", state.visualization.fftScale},
      {"fft_columns", state.visualization.fftColumns},
      {"fft_size", state.visualization.fftSize},
      {"fft_smoothing", state.visualization.fftSmoothing}}},
    {"window_state", {
      {"x", state.window.x}, {"y", state.window.y},
      {"width", state.window.width}, {"height", state.window.height},
      {"maximized", state.window.maximized},
      {"monitor_x", state.window.monitorX}, {"monitor_y", state.window.monitorY},
      {"monitor_width", state.window.monitorWidth}, {"monitor_height", state.window.monitorHeight}}},
    {"server_base_url", state.serverBaseUrl}, {"server_token", state.serverToken},
    {"shuffle_enabled", state.shuffleEnabled}, {"speaker_latencies", speakers},
    {"selected_microphone", state.selectedMicrophone}
  };
  std::error_code error;
  std::filesystem::create_directories(path_.parent_path(), error);
  const auto temporary = path_.string() + ".native.tmp";
  { std::ofstream output(temporary, std::ios::trunc); output << std::setw(2) << root << '\n'; }
  std::filesystem::rename(temporary, path_, error);
  if (error) throw std::runtime_error("Could not save FredPlayer state: " + error.message());
}

}  // namespace fredplayer
