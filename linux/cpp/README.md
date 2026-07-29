# FredPlayer native Ubuntu preview

This is the C++17/GTKmm implementation being tested beside the currently
installed Python build. It deliberately does not replace or launch the
existing desktop entry. The final launcher cutover happens only after manual
approval.

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

After the build finishes, launch the preview yourself:

```bash
linux/cpp/build/fredplayer-native
```

The preview reads the existing `~/.config/fredplayer-ubuntu/state.json`,
`profiles.json`, `.fsp`, and `.fwp` data. Its GTK application, desktop window,
and MPRIS identities are separate, so the Python and C++ builds can run at the
same time for parity testing.

The normal `linux/fredplayer.desktop` entry remains pointed at Python until
manual parity approval.
