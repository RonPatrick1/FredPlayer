# FredPlayer

Shuffled sleep-music player with real-time loudness leveling and visualization,
across three clients sharing one media server.

- `server/` — shared Node.js media server (library browsing, streaming, profile
  and visualization sync, Ask Liam AI chat).
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
