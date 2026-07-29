# FredPlayer media server

The running server streams the library and serves device caches. Android and
Linux retain their existing `/api/visual/*` namespace under `data/visual/`.
Apple's compact `FAV1` files use `/api/apple-visual/*` and
`data/apple-visual/`, so an upload from one platform cannot displace another
platform's incompatible bytes. Leveling profiles remain shared JSON at
`/api/profile/*` and `data/profiles/`.

## Progressive playback

`POST /api/stream-ticket` accepts a server-library `path` under normal bearer
authentication and returns a six-hour, track-scoped URL signature. Apple uses
that URL with `AVPlayer`, allowing the same `/stream/*` byte-range behavior
used by Android without placing the permanent server token in a media URL.
Tickets permit only `GET` and `HEAD`, are bound to one exact encoded track, and
cannot authorize any API request or another library file.

## Shared playlists

Shared playlists are durable server-owned snapshots under
`data/playlists/`. All three clients can publish the current playlist with
`POST /api/playlists`, browse the summaries returned by `GET /api/playlists`,
and download a local copy from `GET /api/playlists/:name`. Publishing the same
name updates the server snapshot. There is intentionally no device-facing
delete endpoint, so deleting or editing a downloaded playlist on Android,
Apple, or Ubuntu cannot remove the shared copy.

Only paths in the server's current music library can be published. A local
file URI would not be playable by another device, so clients reject playlists
containing local files or tracks from a different server instead of publishing
a misleading partial copy. Existing JSON files in `data/playlists/` remain
visible as shared playlists and are upgraded to the versioned format the next
time they are published.

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

## Android visualization variants

Older Android builds continue to use `/api/visual/*` and `data/visual/`. Newer
builds request their exact settings from
`/api/android-visual/<settings>/*`; the corresponding files live under
`data/android-visual/<settings>/`. The canonical key includes FPS, waveform
window, FFT size, bar count, and logarithmic/linear scale. This allows 30- and
60-FPS data to coexist without either device overwriting the other.

At startup the server fills the common 60-FPS variant used by the Android
devices (`fps60-wave90-fft2048-bars64-log1`). Android now syncs only its next
two tracks in the background and fetches the current track on demand instead
of downloading the full library to every device.
