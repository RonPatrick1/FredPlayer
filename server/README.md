# FredPlayer media server

The running server streams the library and serves device caches. Android and
Linux retain their existing `/api/visual/*` namespace under `data/visual/`.
Apple's compact `FAV1` files use `/api/apple-visual/*` and
`data/apple-visual/`, so an upload from one platform cannot displace another
platform's incompatible bytes. Leveling profiles remain shared JSON at
`/api/profile/*` and `data/profiles/`.

## Precomputing device caches

`precompute-cache.js` is an offline, manual job; the HTTP process never starts
it automatically. It scans `MUSIC_DIR`, preserves every valid existing cache,
and atomically fills missing 10-second leveling profiles plus both visual
formats. A stopped run can be resumed with the same command.

```bash
cd server
npm run precompute-cache -- --dry-run
nice -n 10 npm run precompute-cache
```

The default Android FPS and bar count are inferred from the most frequent valid
Android files already in `data/visual/`. Other settings can be overridden; run
`npm run precompute-cache -- --help` for all options. The current observed
server set is 30 FPS and 64 bars. Apple uses the app defaults: 24 FPS, an 80 ms
waveform window, FFT 1024, and 32 logarithmic bars.

The Android output is its existing big-endian `FVZ2` byte format. Apple `FAV1`
has a 60-byte big-endian header (version, settings, frame count/interval, and
creation time), followed by frames containing 128 signed waveform bytes and one
unsigned byte per spectrum bar. `MediaCache.serverVisual(from:settings:)`
validates and expands those bytes into Apple's existing `VisualCacheEntry`.
The matching `MediaCache.serverLoudness(from:)` helper converts the shared
`{rms, peak}` JSON into Apple's dB-based cache entry.
