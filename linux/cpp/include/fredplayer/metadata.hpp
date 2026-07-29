#pragma once

#include "fredplayer/types.hpp"

#include <string>

namespace fredplayer {

TrackEntry readLocalMetadata(const std::string& path,
                             const std::string& sourceFolder = {});
bool isAudioFile(const std::string& path);

}  // namespace fredplayer
