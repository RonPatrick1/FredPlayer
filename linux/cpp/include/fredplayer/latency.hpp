#pragma once

#include <string>
#include <vector>

namespace fredplayer {

struct AudioOutput {
  std::string key;
  std::string label;
  bool bluetooth{false};
};

struct Microphone {
  std::string key;
  std::string label;
  bool isDefault{false};
};

struct CalibrationResult {
  AudioOutput output;
  int delayMs{0};
  double confidence{0};
};

AudioOutput currentOutput();
std::vector<Microphone> microphones();
int probeSystemLatency(const AudioOutput& output);
CalibrationResult calibrateWithMicrophone(const std::string& microphoneKey);

}  // namespace fredplayer
