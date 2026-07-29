#include "fredplayer/application.hpp"

#include <cstdlib>

int main(int argc, char** argv) {
  setenv("__GL_SYNC_TO_VBLANK", "0", 0);
  return fredplayer::runApplication(argc, argv);
}
