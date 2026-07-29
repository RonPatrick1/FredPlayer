#include "fredplayer/server_client.hpp"

#include "fredplayer/state.hpp"

#include <curl/curl.h>
#include <nlohmann/json.hpp>

#include <algorithm>
#include <mutex>
#include <stdexcept>

namespace fredplayer {
namespace {
using json = nlohmann::json;

std::once_flag curlOnce;

size_t writeBody(char* data, size_t size, size_t count, void* user) {
  auto* output = static_cast<std::vector<std::uint8_t>*>(user);
  const auto bytes = size * count;
  output->insert(output->end(), reinterpret_cast<std::uint8_t*>(data),
                 reinterpret_cast<std::uint8_t*>(data) + bytes);
  return bytes;
}

size_t readHeaders(char* data, size_t size, size_t count, void* user) {
  auto* result = static_cast<HttpResult*>(user);
  std::string line(data, size * count);
  std::string lower = line;
  std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
  if (lower.rfind("retry-after:", 0) == 0) {
    try { result->retryAfterSeconds = std::stol(line.substr(12)); } catch (...) {}
  }
  return size * count;
}

std::string stripTrailingSlash(std::string value) {
  while (!value.empty() && value.back() == '/') value.pop_back();
  return value;
}

std::string encode(const std::string& value, bool preserveSlash = false) {
  std::call_once(curlOnce, [] { curl_global_init(CURL_GLOBAL_DEFAULT); });
  CURL* curl = curl_easy_init();
  if (!curl) return {};
  std::string result;
  std::size_t start = 0;
  do {
    const auto end = preserveSlash ? value.find('/', start) : std::string::npos;
    const auto part = value.substr(start, end == std::string::npos ? end : end - start);
    char* escaped = curl_easy_escape(curl, part.c_str(), static_cast<int>(part.size()));
    if (!result.empty() && preserveSlash) result.push_back('/');
    if (escaped) { result += escaped; curl_free(escaped); }
    if (end == std::string::npos) break;
    start = end + 1;
  } while (true);
  curl_easy_cleanup(curl);
  return result;
}

std::string decode(const std::string& value) {
  std::call_once(curlOnce, [] { curl_global_init(CURL_GLOBAL_DEFAULT); });
  CURL* curl = curl_easy_init(); if (!curl) return value;
  int length = 0; char* decoded = curl_easy_unescape(curl, value.c_str(), value.size(), &length);
  std::string result = decoded ? std::string(decoded, length) : value;
  curl_free(decoded); curl_easy_cleanup(curl); return result;
}

}  // namespace

ServerClient::ServerClient(std::string baseUrl, std::string token) {
  configure(std::move(baseUrl), std::move(token));
}

void ServerClient::configure(std::string baseUrl, std::string token) {
  baseUrl_ = stripTrailingSlash(std::move(baseUrl)); token_ = std::move(token);
}

HttpResult ServerClient::request(const std::string& url, const char* method,
                                 const std::string& contentType,
                                 const std::vector<std::uint8_t>& body,
                                 long timeoutSeconds) const {
  std::call_once(curlOnce, [] { curl_global_init(CURL_GLOBAL_DEFAULT); });
  HttpResult result;
  CURL* curl = curl_easy_init();
  if (!curl) { result.error = "Could not initialize HTTP client"; return result; }
  struct curl_slist* headers = nullptr;
  const auto authorization = "Authorization: Bearer " + token_;
  headers = curl_slist_append(headers, authorization.c_str());
  if (!contentType.empty()) headers = curl_slist_append(headers, ("Content-Type: " + contentType).c_str());
  curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
  curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
  curl_easy_setopt(curl, CURLOPT_CUSTOMREQUEST, method);
  curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 6L);
  curl_easy_setopt(curl, CURLOPT_TIMEOUT, timeoutSeconds);
  curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
  curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, writeBody);
  curl_easy_setopt(curl, CURLOPT_WRITEDATA, &result.body);
  curl_easy_setopt(curl, CURLOPT_HEADERFUNCTION, readHeaders);
  curl_easy_setopt(curl, CURLOPT_HEADERDATA, &result);
  if (!body.empty()) {
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, body.data());
    curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, static_cast<long>(body.size()));
  }
  char error[CURL_ERROR_SIZE]{}; curl_easy_setopt(curl, CURLOPT_ERRORBUFFER, error);
  const auto code = curl_easy_perform(curl);
  if (code != CURLE_OK) result.error = error[0] ? error : curl_easy_strerror(code);
  curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &result.status);
  curl_slist_free_all(headers); curl_easy_cleanup(curl);
  return result;
}

std::string ServerClient::apiPath(const std::string& prefix, const std::string& path) const {
  return baseUrl_ + prefix + encode(path, true);
}

std::string ServerClient::streamUrl(const std::string& serverPath) const {
  return apiPath("/stream/", serverPath);
}

std::optional<std::string> ServerClient::ticketedStreamUrl(
    const TrackEntry& track) const {
  const auto path = decode(track.serverPath(baseUrl_));
  if (path.empty()) return std::nullopt;
  try {
    const auto body = json{{"path", path}}.dump();
    const auto response = request(baseUrl_ + "/api/stream-ticket", "POST",
        "application/json", {body.begin(), body.end()}, 4);
    if (response.status != 200) return std::nullopt;
    const auto value = json::parse(response.body);
    const auto ticketPath = value.value("path", "");
    const auto signature = value.value("signature", "");
    if (ticketPath.empty() || signature.empty() || !value.contains("expires") ||
        !value["expires"].is_number_integer())
      return std::nullopt;
    return baseUrl_ + "/" + ticketPath + "?expires=" +
        std::to_string(value["expires"].get<std::int64_t>()) +
        "&signature=" + encode(signature);
  } catch (...) {
    return std::nullopt;
  }
}

std::vector<TrackEntry> ServerClient::library() const {
  const auto response = request(baseUrl_ + "/api/library");
  if (response.status != 200) throw std::runtime_error(response.error.empty() ? "Server library request failed" : response.error);
  std::vector<TrackEntry> result;
  const auto values = json::parse(response.body);
  for (const auto& value : values) {
    const auto path = value.value("path", ""); if (path.empty()) continue;
    const auto split = path.find_last_of('/');
    result.push_back(TrackEntry{streamUrl(path), split == std::string::npos ? "Server" : path.substr(0, split), true,
      value.value("title", ""), value.value("artist", ""), value.value("album", "")});
  }
  return result;
}

std::vector<std::string> ServerClient::sharedPlaylists() const {
  const auto response = request(baseUrl_ + "/api/playlists");
  if (response.status != 200) throw std::runtime_error("Could not load shared playlists");
  std::vector<std::string> result;
  for (const auto& value : json::parse(response.body))
    if (value.is_object() && value.contains("name")) result.push_back(value["name"].get<std::string>());
  return result;
}

std::vector<std::string> ServerClient::playlistTracks(const std::string& name) const {
  const auto response = request(baseUrl_ + "/api/playlists/" + encode(name));
  if (response.status != 200) throw std::runtime_error("Could not load shared playlist");
  return json::parse(response.body).value("tracks", std::vector<std::string>{});
}

void ServerClient::sharePlaylist(const std::string& name,
                                 const std::vector<TrackEntry>& tracks) const {
  json paths = json::array();
  for (const auto& track : tracks) {
    const auto path = track.serverPath(baseUrl_);
    if (path.empty()) throw std::runtime_error("Only tracks from this server can be shared");
    paths.push_back(decode(path));
  }
  const auto encoded = json{{"name", name}, {"tracks", paths}}.dump();
  const auto response = request(baseUrl_ + "/api/playlists", "POST", "application/json",
      {encoded.begin(), encoded.end()});
  if (response.status < 200 || response.status >= 300) throw std::runtime_error("Could not share playlist");
}

std::optional<TrackProfile> ServerClient::profile(const TrackEntry& track) const {
  const auto path = decode(track.serverPath(baseUrl_)); if (path.empty()) return std::nullopt;
  const auto response = request(apiPath("/api/profile/", path));
  if (response.status != 200) return std::nullopt;
  try {
    const auto value = json::parse(response.body);
    if (!value["rms"].is_number() || !value["peak"].is_number()) return std::nullopt;
    return TrackProfile{value["rms"].get<double>(), value["peak"].get<double>()};
  } catch (...) { return std::nullopt; }
}

HttpResult ServerClient::linuxVisual(const TrackEntry& track,
                                     const VisualizationSettings& settings) const {
  const auto path = decode(track.serverPath(baseUrl_));
  if (path.empty()) return HttpResult{0, {}, "Track is not from the configured server", 0};
  return request(baseUrl_ + "/api/linux-visual-variant/" + settings.variantKey() + "/" + encode(path, true));
}

std::string ServerClient::askLiam(const std::string& deviceId,
                                  const std::string& message) const {
  const auto body = json{{"device_id", deviceId}, {"message", message}}.dump();
  const auto response = request(baseUrl_ + "/api/ask-liam", "POST", "application/json",
      {body.begin(), body.end()}, 620);
  if (response.status != 200) throw std::runtime_error("Ask Liam request failed");
  const auto value = json::parse(response.body);
  return value.value("response", value.value("answer", value.dump()));
}

}  // namespace fredplayer
