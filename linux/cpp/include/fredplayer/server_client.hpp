#pragma once

#include "fredplayer/cache.hpp"
#include "fredplayer/types.hpp"

#include <optional>
#include <string>
#include <vector>

namespace fredplayer {

struct HttpResult {
  long status{0};
  std::vector<std::uint8_t> body;
  std::string error;
  long retryAfterSeconds{0};
};

class ServerClient {
 public:
  ServerClient(std::string baseUrl = {}, std::string token = {});
  void configure(std::string baseUrl, std::string token);
  [[nodiscard]] std::vector<TrackEntry> library() const;
  [[nodiscard]] std::vector<std::string> sharedPlaylists() const;
  [[nodiscard]] std::vector<std::string> playlistTracks(const std::string& name) const;
  void sharePlaylist(const std::string& name,
                     const std::vector<TrackEntry>& tracks) const;
  [[nodiscard]] std::optional<TrackProfile> profile(const TrackEntry& track) const;
  [[nodiscard]] HttpResult linuxVisual(const TrackEntry& track,
                                       const VisualizationSettings& settings) const;
  [[nodiscard]] std::string askLiam(const std::string& deviceId,
                                    const std::string& message) const;
  [[nodiscard]] std::string streamUrl(const std::string& serverPath) const;
  [[nodiscard]] std::optional<std::string> ticketedStreamUrl(
      const TrackEntry& track) const;
  [[nodiscard]] const std::string& baseUrl() const { return baseUrl_; }

 private:
  HttpResult request(const std::string& url, const char* method = "GET",
                     const std::string& contentType = {},
                     const std::vector<std::uint8_t>& body = {},
                     long timeoutSeconds = 8) const;
  std::string apiPath(const std::string& prefix, const std::string& serverPath) const;
  std::string baseUrl_;
  std::string token_;
};

}  // namespace fredplayer
