# Apple client: add the missing networking layer

Delete this file once this work is done — it's a handoff note, not permanent
documentation.

## Context

This is a monorepo consolidating three FredPlayer clients (Android, Linux,
Apple) around one shared Node.js media server (`server/`). Android and Linux
both talk to that server over HTTP — browsing the library, streaming tracks,
and syncing loudness/visualization cache data. **The Apple client currently
has none of that.** Per the original README note (still true, just checked):
zero `URLSession` usage anywhere in this app, no server config UI, nothing.
It's local-file-only (security-scoped bookmarks + a bundled folder).

The server-side work to support Apple is already done and sitting there
waiting:

- A separate cache namespace just for Apple: `GET/PUT /api/apple-visual/*`,
  stored as `data/apple-visual/*.fav` — deliberately kept apart from
  Android/Linux's `/api/visual/*` (`.fvz`) so neither platform can ever
  clobber or misread the other's bytes.
- `apple/FredPlayer/MediaCache.swift` already has the **decode side** for
  this: `MediaCache.serverVisual(from:settings:)` and
  `MediaCache.storeServerVisual(_:for:settings:)` parse the compact `FAV1`
  format straight into this app's existing `VisualCacheEntry` model.
  `MediaCache.serverLoudness(from:analyzedSeconds:)` /
  `storeServerLoudness(_:for:analyzedSeconds:)` do the same for the shared
  `{rms, peak}` loudness JSON from `/api/profile/*`.
- The server has already precomputed Apple-format visualization data for the
  **entire library** (~3,000 tracks, using this app's own current defaults —
  `fftSize=1024`, `fftBarCount=32`, confirmed matching `PlayerController.swift`
  — so once fetching works, cache hits should work immediately, no
  settings reconciliation needed).

**What's missing is entirely client-side: nothing calls any of this.** No
`URLSession` requests exist, no server URL/token settings, no track-source
abstraction that could point at a remote URL instead of a local
security-scoped bookmark.

## What to build

Mirror what Android and Linux already do — same server, same endpoints, same
auth scheme, just written in Swift:

- **Reference implementations** (read these first, don't design from
  scratch): `android/app/src/main/java/com/fredplayer/app/RemoteLibraryClient.java`
  and `linux/fredplayer/remote.py`. Both are small, plain HTTP clients — no
  frameworks, easy to port the shape of directly.
- **Auth**: every request needs `Authorization: Bearer <token>` header. Token
  and server base URL both need a place to live in this app (Android:
  `PlaylistStore.saveServerBaseUrl`/`saveServerToken`; Linux: fields in its
  JSON state file) — Apple has no equivalent yet, needs a settings UI +
  `UserDefaults` storage (same pattern already used for the existing
  `player.*` keys in `PlayerController.swift`).
- **Endpoints to implement**:
  - `GET /api/library` → JSON array of `{path, title, artist, album, genre}`
    — the whole library index.
  - `GET /stream/<url-encoded relative path>` → the audio bytes (supports
    HTTP range requests, confirmed).
  - `GET/PUT /api/profile/<path>` → `{rms, peak}` JSON. Feed a successful GET
    straight into `MediaCache.storeServerLoudness`.
  - `GET/PUT /api/apple-visual/<path>` → raw `FAV1` bytes. Feed a successful
    GET straight into `MediaCache.storeServerVisual`.
  - `POST /api/rescan` → `{count}` — triggers a library rescan server-side.
  - `POST /api/ask-liam` → body `{device_id, message}` → `{reply, playlist?}`
    (playlist, if present, is `{name, tracks: [path, ...]}` — build a local
    playlist from it, same as Android/Linux's Ask Liam integration; can come
    later, lower priority than library/streaming).
- **Track-source abstraction**: the audio engine currently assumes a local
  file URL (security-scoped bookmark). It needs to accept a remote
  `https://` URL too, matching how Android/Linux both branch on
  `isRemote(path)`/`remote.is_remote(path)` throughout their playback code.
- **Fetch-then-fallback**: on play, try `GET /api/apple-visual/*` and
  `GET /api/profile/*` first; if either is missing/mismatched, fall back to
  this app's own existing local analysis (it already has one, it's just
  never had a remote counterpart to prefer first).

## Order of attack

1. Server URL + token settings (storage + a settings screen field).
2. `/api/library` fetch + a picker UI to add remote tracks to a playlist
   (mirror Android's "Add from server" flow for the shape of this).
3. Remote streaming (the track-source abstraction above) — get a remote
   track actually playing before touching cache sync at all.
4. Wire in the already-built `MediaCache.serverVisual`/`serverLoudness`
   fetch + fallback-to-local-analysis.
5. Ask Liam, last — it's the least connected to the rest of this.
