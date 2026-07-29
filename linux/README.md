# FredPlayer for Ubuntu

Local desktop version of FredPlayer for shuffled baby sleep music playback on Ubuntu.

## Native C++ preview

The replacement C++17/GTKmm implementation is under `linux/cpp`. It uses
native GStreamer DSP/playback, FFTW, OpenGL/libepoxy, TagLib, libcurl, and
PulseAudio/PipeWire integration, while reading the existing playlists,
settings, profiles, and visualization caches. See `linux/cpp/README.md` for
the headless build and manual test commands.

The installed desktop entry intentionally remains on the Python build until
the native preview has been manually approved. No preview build command opens
or manipulates application windows.

## Run

```bash
python3 run_fredplayer.py
```

The repository includes `fredplayer.desktop`. It can be installed as a desktop shortcut by copying it to `~/Desktop` and `~/.local/share/applications`; this setup has already been done on this machine.

The app uses Ubuntu system packages that are already present on this machine:

- Python 3
- PyGObject / GTK 3
- GStreamer 1.0, including `plugins-base`, `plugins-good`, `plugins-ugly`, and `libav`
- Mutagen for optional track display names
- PyOpenGL and NumPy for GPU-rendered visualization and high-resolution FFT analysis

On a fresh Ubuntu install, the equivalent packages are:

```bash
sudo apt install python3-gi python3-mutagen python3-opengl python3-numpy mesa-utils \
  gir1.2-gtk-3.0 gir1.2-gstreamer-1.0 gir1.2-gst-plugins-base-1.0 \
  gstreamer1.0-tools gstreamer1.0-plugins-base gstreamer1.0-plugins-good pulseaudio-utils \
  gstreamer1.0-plugins-ugly gstreamer1.0-libav
```

## Features

- Add individual audio files from the local filesystem.
- Add a folder and recursively scan subfolders for audio files.
- Supports MP3, FLAC, M4A/AAC, Ogg/Opus, WAV, AIFF, and whatever the installed GStreamer codecs can decode.
- Saves named playlists, output level, leveling strength, and advanced leveling controls.
- Shuffle-loop playback with a shuffled bag to avoid repeating the same few tracks before a cycle completes.
- Track-position scrub bar with elapsed and total time; it stays disabled when a track has no known duration, previews the dragged position locally, and seeks on release.
- Accurate GStreamer seeking from a requested millisecond position, including seeks while paused and position tracking from the output playback clock plus the seek base.
- MPRIS seek support for desktop media controls through both relative `Seek` and absolute `SetPosition`, with current position and duration metadata exposed to the desktop session.
- Playback progress refreshes every 500 ms in the player screen and through the MPRIS position property.
- Multiple named playlists with create, switch, rename, and delete controls. The player summary shows the active playlist name and song count.
- Remove individual files or folder groups from the playlist.
- Displays track metadata from tags, including title, artist, and album, falling back to filenames only when tags are missing.
- Remembers the last window size, position, monitor, and maximized state.
- Startup loudness pre-scan from Off to 45 seconds, defaulting to 10 seconds.
- Per-track loudness profile cache keyed by path plus file size and modified time.
- Real-time moving RMS/peak gain leveling, compression, and hard output ceiling.
- Standard Linux media-key integration through MPRIS: Play/Pause, Stop, Next, Previous, and desktop media controls.
- Per-output visualization synchronization using the latency reported by the local GStreamer/PipeWire audio stack.
- Optional end-to-end speaker-delay calibration with a chosen local microphone; calibrations are saved locally per output device and microphone audio is never stored or uploaded.
- Follows the Ubuntu light/dark theme while the app is running.
- GPU-rendered real-time waveform and multicolor FFT spectrum display from the processed output audio.
- Visualization controls for update rate, waveform time window, display bar count, FFT resolution/window length, FFT smoothing, and log/linear FFT scale.
- Per-track FFT spectrum cache keyed by file identity and visualization settings, with centered FFT windows selected from the GStreamer playback clock.
- Per-track waveform visualization cache keyed by file identity, visual FPS, and waveform window, with playback-clocked waveform frames.
- Low-priority background precompute fills missing loudness, FFT, and waveform caches for playlist tracks while the app is open.
- Foreground and background cache preparation reports percent-complete progress.
- The Settings page shows cache counts, pruning thresholds, keep counts, and total cache disk usage.
- The main player page contains now-playing details, seek and transport controls, and the live waveform/spectrum visualization. Playlist editing, leveling, visualization controls, and cache details live on the Settings page; Back or Escape returns to the player.

`FREDPLAYER_PRECOMPUTE_WORKERS` can be set before launch to choose the number of background cache workers. By default the app uses one or two low-priority workers depending on CPU count.

## Stored Data

- Settings and named playlists: `~/.config/fredplayer-ubuntu/state.json`
- Loudness profile cache: `~/.local/share/fredplayer-ubuntu/profiles.json`
- FFT spectrum cache: `~/.local/share/fredplayer-ubuntu/spectra/`
- Waveform visualization cache: `~/.local/share/fredplayer-ubuntu/waveforms/`

Cache pruning is file-count based:

- Loudness profiles prune above 5000 entries and keep the newest 4000.
- FFT spectra prune above 5000 files and keep the newest 4500.
- Waveform profiles prune above 5000 files and keep the newest 4500.

Named playlist state is stored in `named_playlists` with `active_playlist`. The active
playlist is also mirrored to the legacy `playlist` field so an older desktop build
can still read it after a rollback.

The desktop code remains separate from the Android project at `/var/www/FredPlayer`.
Both projects carry the same FredPlayer launcher artwork in their own platform-specific
icon resources.
