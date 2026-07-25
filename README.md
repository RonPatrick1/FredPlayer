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
