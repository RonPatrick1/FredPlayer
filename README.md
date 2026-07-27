# FredPlayer

Shuffled sleep-music player with real-time loudness leveling and visualization,
across three clients sharing one media server.

- `server/` — shared Node.js media server (library browsing, streaming, profile
  and platform-specific visualization sync, offline cache precompute, Ask Liam
  AI chat).
- `android/` — Android client (Java), including Android Auto support.
- `linux/` — Ubuntu/GTK desktop client (Python), including MPRIS integration.
- `apple/` — iOS/macOS client (Swift), including CarPlay support.

Android and Linux clients talk to `server/` over HTTP (`/api/library`,
`/stream/*`, `/api/profile/*`, `/api/visual/*`, `/api/ask-liam`). The Apple
client is currently local-file-only and does not yet integrate with the
shared server.

## Known gaps / notes

- **Apple has no remote-server client.** `apple/FredPlayer/PlaylistStore.swift`
  only imports local files via security-scoped bookmarks (plus a bundled
  "Classical" folder) — there's no networking code in the Swift project at
  all. Android (`RemoteLibraryClient.java`) and Linux (`fredplayer/remote.py`)
  both fetch the library, stream tracks, and sync loudness/visualization
  profiles through `server/`; Apple's loudness/visual analysis
  (`MediaCache.swift`) is real but purely a local disk cache, never synced.
  Bringing Apple to parity means adding a Swift equivalent of
  `RemoteLibraryClient`/`remote.py` (`/api/library`, `/stream/*`,
  `/api/profile/*`, `/api/visual/*`).
  The server now has a separate `/api/apple-visual/*`/`data/apple-visual/`
  namespace, an offline generator, and a matching Swift format decoder ready
  for that client integration; it does not make Apple's local-only audio engine
  stream remote tracks by itself.
- **`apple/` was imported byte-for-byte, unmodified**, from the Mac Mini's
  working copy — intentional, so the Apple-side development thread starts
  from exactly what was already there.
- **LiamAgent (`/var/www/LiamAgent`) is a separate project/repo**, not part
  of this monorepo. It only relates to this repo by calling `server/`'s
  `/api/ask-liam` endpoint. Its own playlist-tool fix (`fredplayer_save_playlist`
  / `fredplayer_propose_playlist` taking `playlist_name` instead of `name`)
  lives entirely in LiamAgent's own repo and has no corresponding code here.
- This repo was assembled by copying `/var/www/FredPlayer` (android+server),
  `/var/www/FredPlayerForAliens` (linux), and `/var/www/FredPlayerApple`
  (apple) — those original folders were left in place untouched (not moved),
  since `FredPlayerForAliens` has an installed desktop launcher and a running
  process pointing at its original path.
- **nginx `client_max_body_size` is still the default (1MB)** on the
  `/fredplayer-media/` location block in `/etc/nginx/sites-available/default`
  on the host. Large visual-cache uploads (spectrum/waveform data, ~3.3MB
  observed) get rejected with a 413 — the upload code degrades gracefully
  (silently fails, recomputes locally next time) so nothing breaks, but
  cross-device cache sharing doesn't fully work at that size yet. Fix needs
  the host's own sudo access: add `client_max_body_size 25m;` next to the
  existing `proxy_read_timeout 300;` in that block, then
  `sudo nginx -t && sudo systemctl reload nginx`.

## Operational cautions (learned the hard way)

- **Don't interactively test the Android app's playlist features against a
  real device without care.** A UI test once wiped a real, non-backed-up
  106-song local playlist. Prefer code review + `./gradlew assembleDebug` +
  targeted `adb` checks over exploratory tapping through playlist
  add/remove/switch flows on a device with real data on it.
- **Restarting `fredplayer-media.service` (the `server/` process) drops any
  client currently streaming from it.** Avoid restarting it during active
  playback; if a restart is needed, treat it like a deploy, not a routine
  dev-loop action.
