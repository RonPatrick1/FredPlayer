#include "fredplayer/metadata.hpp"

#include <taglib/fileref.h>
#include <taglib/tag.h>

#include <algorithm>
#include <filesystem>
#include <set>

namespace fredplayer {

bool isAudioFile(const std::string& path) {
  static const std::set<std::string> extensions{
    ".aac", ".aif", ".aiff", ".alac", ".flac", ".m4a", ".mp3", ".oga", ".ogg", ".opus", ".wav"};
  auto extension = std::filesystem::path(path).extension().string();
  std::transform(extension.begin(), extension.end(), extension.begin(), ::tolower);
  return extensions.count(extension) != 0;
}

TrackEntry readLocalMetadata(const std::string& path, const std::string& sourceFolder) {
  TrackEntry entry;
  std::error_code error;
  entry.path = std::filesystem::weakly_canonical(path, error).string();
  if (entry.path.empty() || error) entry.path = path;
  entry.sourceFolder = sourceFolder.empty() ? std::filesystem::path(entry.path).parent_path().string() : sourceFolder;
  TagLib::FileRef file(entry.path.c_str());
  if (!file.isNull() && file.tag()) {
    auto* tag = file.tag();
    entry.title = tag->title().to8Bit(true);
    entry.artist = tag->artist().to8Bit(true);
    entry.album = tag->album().to8Bit(true);
  }
  if (entry.title.empty()) entry.title = std::filesystem::path(entry.path).stem().string();
  return entry;
}

}  // namespace fredplayer
