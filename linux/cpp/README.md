# FredPlayer native Ubuntu build

This is the C++17/GTKmm implementation of the Ubuntu desktop app. It's the
only Linux desktop build now — the earlier Python/PyGObject build was
removed after manual parity approval, and `linux/fredplayer.desktop` launches
this build directly.

## Dependencies

Ubuntu 24.04:

```bash
sudo apt install build-essential cmake ninja-build pkg-config \
  libgtkmm-3.0-dev libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev \
  libfftw3-dev libepoxy-dev libcurl4-openssl-dev libtag1-dev \
  nlohmann-json3-dev libpulse-dev zlib1g-dev \
  gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
  gstreamer1.0-plugins-ugly gstreamer1.0-libav pulseaudio-utils
```

## Headless build and tests

```bash
linux/cpp/build.sh
```

The script only compiles and runs headless tests. It does not open FredPlayer.

## Manual test

After the build finishes, launch it yourself:

```bash
linux/cpp/build/fredplayer-native
```

It reads the existing `~/.config/fredplayer-ubuntu/state.json`,
`profiles.json`, `.fsp`, and `.fwp` data — the same files the old Python
build used.
