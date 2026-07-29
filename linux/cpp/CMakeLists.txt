cmake_minimum_required(VERSION 3.20)
project(fredplayer_ubuntu LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

include(GNUInstallDirs)
include(CTest)
find_package(PkgConfig REQUIRED)
find_package(ZLIB REQUIRED)

pkg_check_modules(GTKMM REQUIRED IMPORTED_TARGET gtkmm-3.0)
pkg_check_modules(GSTREAMER REQUIRED IMPORTED_TARGET
  gstreamer-1.0 gstreamer-app-1.0 gstreamer-audio-1.0 gstreamer-base-1.0)
pkg_check_modules(FFTW REQUIRED IMPORTED_TARGET fftw3f)
pkg_check_modules(EPOXY REQUIRED IMPORTED_TARGET epoxy)
pkg_check_modules(X11_GLX REQUIRED IMPORTED_TARGET x11 glx)
pkg_check_modules(CURL REQUIRED IMPORTED_TARGET libcurl)
pkg_check_modules(TAGLIB REQUIRED IMPORTED_TARGET taglib)
pkg_check_modules(PULSE REQUIRED IMPORTED_TARGET libpulse)

add_library(fredplayer_core
  src/audio_engine.cpp
  src/cache.cpp
  src/clock.cpp
  src/dsp.cpp
  src/latency.cpp
  src/metadata.cpp
  src/mpris.cpp
  src/server_client.cpp
  src/state.cpp
  src/visualization.cpp
)
target_include_directories(fredplayer_core PUBLIC include)
target_compile_options(fredplayer_core PRIVATE -Wall -Wextra -Wpedantic)
target_link_directories(fredplayer_core PUBLIC
  ${GSTREAMER_LIBRARY_DIRS} ${FFTW_LIBRARY_DIRS} ${CURL_LIBRARY_DIRS}
  ${TAGLIB_LIBRARY_DIRS} ${PULSE_LIBRARY_DIRS})
target_link_libraries(fredplayer_core PUBLIC
  PkgConfig::GSTREAMER PkgConfig::FFTW PkgConfig::CURL PkgConfig::TAGLIB
  PkgConfig::PULSE ZLIB::ZLIB pthread)

add_executable(fredplayer-native
  src/application.cpp
  src/main.cpp
  src/visualizer_widget.cpp
)
target_compile_options(fredplayer-native PRIVATE -Wall -Wextra -Wpedantic)
target_link_directories(fredplayer-native PRIVATE ${GTKMM_LIBRARY_DIRS} ${EPOXY_LIBRARY_DIRS})
target_link_libraries(fredplayer-native PRIVATE
  fredplayer_core PkgConfig::GTKMM PkgConfig::EPOXY PkgConfig::X11_GLX)

install(TARGETS fredplayer-native RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR})

if(BUILD_TESTING)
  add_executable(fredplayer-native-tests tests/native_tests.cpp)
  target_link_libraries(fredplayer-native-tests PRIVATE fredplayer_core)
  add_test(NAME fredplayer-native-tests COMMAND fredplayer-native-tests)
endif()
