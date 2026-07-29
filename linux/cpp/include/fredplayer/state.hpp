#pragma once

#include "fredplayer/types.hpp"

#include <filesystem>
#include <string>

namespace fredplayer {

std::filesystem::path configDirectory();
std::filesystem::path dataDirectory();
std::string persistentDeviceId();

class StateStore {
 public:
  explicit StateStore(std::filesystem::path path = configDirectory() / "state.json");
  [[nodiscard]] AppState load() const;
  void save(const AppState& state) const;
  [[nodiscard]] const std::filesystem::path& path() const { return path_; }

 private:
  std::filesystem::path path_;
};

}  // namespace fredplayer
