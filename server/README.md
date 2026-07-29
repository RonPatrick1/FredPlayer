# FredPlayer media server

The running server streams the library and serves device caches. Older Android
and Linux clients retain `/api/visual/*` under `data/visual/`. The native
Ubuntu client uses settings-keyed `FLV1` files under
`data/linux-visual-variant/`.
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

`precompute-cache.js` is also available as an offline manual job. The HTTP
process runs conservative startup/rescan passes for common variants and can
fill an authenticated device's missing track at idle I/O priority. Every path
preserves valid files and writes atomically, so a stopped run resumes without
discarding completed work.

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

## Ubuntu visualization variants

Native Ubuntu requests
`/api/linux-visual-variant/<settings>/*`. The canonical key includes FPS,
waveform window, FFT size, spectrum bars, scale, and the canonical leveling
preset, for example `fps30-wave80-fft4096-bars96-log1-level1`.

`FLV1` uses a fixed 72-byte big-endian header followed by a zlib-compressed
payload. The header records source identity, sample rate, every analysis
setting, 512 waveform points, frame count, and frame interval. Each decoded
frame contains signed waveform bytes followed by unsigned spectrum bytes.

If a requested track is not ready, the server returns `202` with
`Retry-After` and queues one low-priority job. Authenticated requests update an
atomic usage registry. Background passes fill only settings variants that have
actually been requested; there is no hardcoded Ubuntu default pass. Old
variants are not deleted automatically.

Manual examples:

```bash
npm run precompute-cache -- --platform linux --linux-variant --dry-run
nice -n 10 npm run precompute-cache -- --platform linux --linux-variant --nice
```

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
