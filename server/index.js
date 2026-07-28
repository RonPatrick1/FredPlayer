require('dotenv').config();

const crypto = require('crypto');
const fs = require('fs');
const fsp = fs.promises;
const http = require('http');
const path = require('path');
const express = require('express');
const mm = require('music-metadata');
const precomputeCache = require('./precompute-cache.js');
const sharedPlaylists = require('./shared-playlists.js');

const MUSIC_DIR = path.resolve(process.env.MUSIC_DIR || '');
const PORT = parseInt(process.env.PORT || '8790', 10);
const AUTH_TOKEN = process.env.AUTH_TOKEN || '';
const DATA_DIR = path.join(__dirname, 'data');
const PROFILES_DIR = path.join(DATA_DIR, 'profiles');
const VISUAL_DIR = path.join(DATA_DIR, 'visual');
const ANDROID_VISUAL_DIR = path.join(DATA_DIR, 'android-visual');
const APPLE_VISUAL_DIR = path.join(DATA_DIR, 'apple-visual');
const APPLE_VISUAL_VARIANT_DIR = path.join(DATA_DIR, 'apple-visual-variant');
const PLAYLISTS_DIR = path.join(DATA_DIR, 'playlists');
const LIAM_ASK_URL = process.env.LIAM_ASK_URL || 'http://127.0.0.1:8787/fredplayer-ask';
// This hop is localhost-only (Node -> LiamAgent), not through nginx, so it
// can afford real headroom for handle_fredplayer_ask's up-to-3 retry
// attempts against a slow local model.
const LIAM_ASK_TIMEOUT_MS = 550000;

const AUDIO_EXTENSIONS = new Set([
  '.mp3', '.flac', '.m4a', '.wav', '.ogg', '.aac', '.wma', '.opus', '.alac',
]);

// Both current Android devices use these settings. The 30-FPS legacy cache
// remains available as a fallback; this second settings-keyed variant makes
// true 60-FPS playback possible without asking a phone to analyze the track.
const ANDROID_60_SETTINGS = Object.freeze({
  fps: 60,
  waveformMs: 90,
  fftSize: 2048,
  bars: 64,
  logarithmic: true,
});

// The Apple client's current defaults (PlayerController.swift). The legacy
// flat apple-visual cache was baked at the older 24fps/1024-FFT/32-bar
// settings, so it never matches these and every fetch falls back to local
// analysis. This variant lets the precomputed cache serve the app as it's
// actually configured today.
const APPLE_60_SETTINGS = Object.freeze({
  fps: 60,
  waveformMs: 80,
  fftSize: 2048,
  bars: 64,
  logarithmic: true,
});

if (!MUSIC_DIR) {
  console.error('MUSIC_DIR is not set. Configure it in server/.env');
  process.exit(1);
}
if (!AUTH_TOKEN) {
  console.error('AUTH_TOKEN is not set. Configure it in server/.env');
  process.exit(1);
}

const app = express();

function checkToken(req) {
  const header = req.get('authorization') || '';
  const prefix = 'Bearer ';
  if (!header.startsWith(prefix)) {
    return false;
  }
  const supplied = Buffer.from(header.slice(prefix.length));
  const expected = Buffer.from(AUTH_TOKEN);
  if (supplied.length !== expected.length) {
    return false;
  }
  return crypto.timingSafeEqual(supplied, expected);
}

app.use((req, res, next) => {
  if (!checkToken(req)) {
    res.status(401).json({ error: 'unauthorized' });
    return;
  }
  next();
});

// Resolves a request-supplied relative path against a base directory,
// rejecting anything that would escape it (path traversal guard).
function resolveWithin(baseDir, relPath) {
  const decoded = decodeURIComponent(relPath || '');
  const resolvedBase = path.resolve(baseDir);
  const resolved = path.resolve(resolvedBase, decoded);
  if (resolved !== resolvedBase && !resolved.startsWith(resolvedBase + path.sep)) {
    return null;
  }
  return resolved;
}

async function walkAudioFiles(dir, baseDir, out) {
  const entries = await fsp.readdir(dir, { withFileTypes: true });
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      await walkAudioFiles(full, baseDir, out);
    } else if (entry.isFile() && AUDIO_EXTENSIONS.has(path.extname(entry.name).toLowerCase())) {
      out.push(path.relative(baseDir, full));
    }
  }
}

async function buildLibraryIndex() {
  const relPaths = [];
  await walkAudioFiles(MUSIC_DIR, MUSIC_DIR, relPaths);
  const tracks = [];
  for (const relPath of relPaths) {
    const posixPath = relPath.split(path.sep).join('/');
    let title = path.basename(relPath, path.extname(relPath));
    let artist = '';
    let album = '';
    let genre = '';
    try {
      const metadata = await mm.parseFile(path.join(MUSIC_DIR, relPath), {
        duration: false,
        skipCovers: true,
      });
      if (metadata.common.title) {
        title = metadata.common.title;
      }
      artist = metadata.common.artist || (metadata.common.artists || []).join(', ') || '';
      album = metadata.common.album || '';
      if (metadata.common.genre && metadata.common.genre.length) {
        genre = metadata.common.genre.join(', ');
      }
    } catch (err) {
      // Unreadable tags — fall back to the filename-derived title above.
    }
    tracks.push({ path: posixPath, title, artist, album, genre });
  }
  tracks.sort((a, b) => a.path.localeCompare(b.path));
  console.log(`Library index built: ${tracks.length} tracks`);
  return tracks;
}

let libraryPromise = buildLibraryIndex();

app.get('/api/library', async (req, res) => {
  try {
    const tracks = await libraryPromise;
    res.json(tracks);
  } catch (err) {
    res.status(500).json({ error: 'library scan failed' });
  }
});

app.post('/api/rescan', (req, res) => {
  libraryPromise = buildLibraryIndex();
  libraryPromise
    .then((tracks) => {
      res.json({ count: tracks.length });
      triggerAutoPrecompute();
    })
    .catch(() => res.status(500).json({ error: 'rescan failed' }));
});

// Fills in any missing visualization/leveling cache data for the library
// on its own — no manual `node precompute-cache.js` run required. Triggered
// once at startup (catches anything added while the process was down) and
// after every /api/rescan (the library-changed signal). jobForTrack() only
// ever selects tracks that are actually missing valid cache data, so calling
// this liberally costs nothing for the overwhelming majority of tracks that
// are already cached — it just confirms "nothing to do" quickly.
let autoPrecomputeRunning = false;
let autoPrecomputeQueued = false;

async function triggerAutoPrecompute() {
  if (autoPrecomputeRunning) {
    autoPrecomputeQueued = true;
    return;
  }
  autoPrecomputeRunning = true;
  try {
    do {
      autoPrecomputeQueued = false;
      await runAutoPrecomputePass();
    } while (autoPrecomputeQueued);
  } catch (err) {
    console.error(`Auto-precompute failed: ${err.message}`);
  } finally {
    autoPrecomputeRunning = false;
  }
}

async function runAutoPrecomputePass() {
  const inferred = await precomputeCache.inferAndroidSettings(VISUAL_DIR);
  const baseOptions = {
    musicDir: MUSIC_DIR,
    dataDir: DATA_DIR,
    platform: 'both',
    analysisSeconds: 10,
    visualOnly: false,
    profilesOnly: false,
    limit: Infinity,
    // Lower than the manual CLI's default (8) — this runs inside the live
    // streaming process alongside real traffic, not as a supervised
    // maintenance job, so it should stay a background citizen.
    concurrency: 3,
    dryRun: false,
    force: false,
    nice: true,
    android: {
      ...precomputeCache.DEFAULT_ANDROID,
      ...(inferred ? { fps: inferred.fps, bars: inferred.bars } : {}),
    },
    apple: { ...precomputeCache.DEFAULT_APPLE },
  };
  const tracks = await precomputeCache.walkAudioFiles(MUSIC_DIR, MUSIC_DIR);
  const passes = [
    { label: 'legacy/Apple/profile', options: baseOptions },
    {
      label: `Android ${precomputeCache.androidVariantKey(ANDROID_60_SETTINGS)}`,
      options: {
        ...baseOptions,
        platform: 'android',
        visualOnly: true,
        androidVariant: true,
        android: { ...ANDROID_60_SETTINGS },
      },
    },
    {
      label: `Apple ${precomputeCache.appleVariantKey(APPLE_60_SETTINGS)}`,
      options: {
        ...baseOptions,
        platform: 'apple',
        visualOnly: true,
        appleVariant: true,
        apple: { ...APPLE_60_SETTINGS },
      },
    },
  ];
  let totalJobs = 0;
  for (const pass of passes) {
    const jobs = tracks
      .map((track) => precomputeCache.jobForTrack(track, pass.options))
      .filter(Boolean);
    totalJobs += jobs.length;
    if (jobs.length === 0) continue;
    console.log(`Auto-precompute (${pass.label}): ${jobs.length} track(s) need cache data`);
    await precomputeCache.runJobs(jobs, pass.options);
  }
  if (totalJobs === 0) console.log('Auto-precompute: library cache is already up to date');
}

libraryPromise.then(() => triggerAutoPrecompute());

app.get('/api/playlists', async (req, res) => {
  try {
    res.json(await sharedPlaylists.listSharedPlaylists(PLAYLISTS_DIR));
  } catch (err) {
    res.status(500).json({ error: 'could not list shared playlists' });
  }
});

app.get('/api/playlists/:name', async (req, res) => {
  const name = req.params.name;
  if (!sharedPlaylists.validPlaylistName(name)) {
    res.status(400).json({ error: 'invalid playlist name' });
    return;
  }
  try {
    const playlist = await sharedPlaylists.readSharedPlaylist(PLAYLISTS_DIR, name);
    if (!playlist) {
      res.status(404).json({ error: 'not found' });
      return;
    }
    res.json(playlist);
  } catch (err) {
    res.status(500).json({ error: 'could not read shared playlist' });
  }
});

app.post('/api/playlists', express.json({ limit: '2mb' }), async (req, res) => {
  const { name, tracks } = req.body || {};
  try {
    const library = await libraryPromise;
    const validTrackPaths = new Set(library.map((track) => track.path));
    const result = await sharedPlaylists.writeSharedPlaylist(
      PLAYLISTS_DIR,
      name,
      tracks,
      validTrackPaths,
    );
    res.status(result.created ? 201 : 200).json({
      name: result.playlist.name,
      count: result.playlist.tracks.length,
      shared: true,
      updatedAt: result.playlist.updatedAt,
    });
  } catch (err) {
    if (err.code === 'INVALID_NAME' || err.code === 'INVALID_TRACKS') {
      res.status(400).json({ error: err.message });
      return;
    }
    res.status(500).json({ error: 'could not save shared playlist' });
  }
});

// Plain Node http request — Liam's agent bridge is a local, unauthenticated
// http.server on this same machine (127.0.0.1:8787), never reachable except
// through this already-authenticated relay. No new dependency needed for
// one internal localhost call.
function postJson(urlString, body, timeoutMs) {
  return new Promise((resolve, reject) => {
    const url = new URL(urlString);
    const data = Buffer.from(JSON.stringify(body));
    const request = http.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname,
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Content-Length': data.length },
        timeout: timeoutMs,
      },
      (response) => {
        const chunks = [];
        response.on('data', (chunk) => chunks.push(chunk));
        response.on('end', () => {
          try {
            resolve({ status: response.statusCode, body: JSON.parse(Buffer.concat(chunks).toString('utf8')) });
          } catch (err) {
            reject(err);
          }
        });
      },
    );
    request.on('timeout', () => request.destroy(new Error('Liam request timed out')));
    request.on('error', reject);
    request.write(data);
    request.end();
  });
}

app.post('/api/ask-liam', express.json({ limit: '64kb' }), async (req, res) => {
  const { device_id: deviceId, message } = req.body || {};
  if (typeof deviceId !== 'string' || !deviceId.trim()) {
    res.status(400).json({ error: 'device_id is required' });
    return;
  }
  if (typeof message !== 'string' || !message.trim()) {
    res.status(400).json({ error: 'message is required' });
    return;
  }
  try {
    const { status, body } = await postJson(
      LIAM_ASK_URL,
      { device_id: deviceId.trim(), message: message.trim() },
      LIAM_ASK_TIMEOUT_MS,
    );
    if (status !== 200) {
      res.status(502).json({ error: body.error || 'Liam request failed' });
      return;
    }
    res.json(body);
  } catch (err) {
    res.status(502).json({ error: 'Could not reach Liam: ' + err.message });
  }
});

app.get('/stream/*', (req, res) => {
  const filePath = resolveWithin(MUSIC_DIR, req.params[0]);
  if (!filePath) {
    res.status(400).json({ error: 'invalid path' });
    return;
  }
  res.sendFile(filePath, (err) => {
    if (err && !res.headersSent) {
      res.status(404).json({ error: 'not found' });
    }
  });
});

app.get('/api/profile/*', async (req, res) => {
  const filePath = resolveWithin(PROFILES_DIR, req.params[0] + '.json');
  if (!filePath) {
    res.status(400).json({ error: 'invalid path' });
    return;
  }
  try {
    const contents = await fsp.readFile(filePath, 'utf8');
    res.type('application/json').send(contents);
  } catch (err) {
    res.status(404).json({ error: 'not found' });
  }
});

app.put('/api/profile/*', express.json({ limit: '64kb' }), async (req, res) => {
  const filePath = resolveWithin(PROFILES_DIR, req.params[0] + '.json');
  if (!filePath) {
    res.status(400).json({ error: 'invalid path' });
    return;
  }
  const { rms, peak } = req.body || {};
  if (typeof rms !== 'number' || typeof peak !== 'number') {
    res.status(400).json({ error: 'expected {rms, peak}' });
    return;
  }
  try {
    await fsp.mkdir(path.dirname(filePath), { recursive: true });
    await fsp.writeFile(filePath, JSON.stringify({ rms, peak }));
    res.status(204).end();
  } catch (err) {
    res.status(500).json({ error: 'write failed' });
  }
});

app.get('/api/android-visual/:variant/*', async (req, res) => {
  const settings = precomputeCache.parseAndroidVariantKey(req.params.variant);
  if (!settings) {
    res.status(400).json({ error: 'invalid Android visual settings' });
    return;
  }
  const variantDirectory = path.join(
    ANDROID_VISUAL_DIR,
    precomputeCache.androidVariantKey(settings),
  );
  const filePath = resolveWithin(variantDirectory, req.params[0] + '.fvz');
  if (!filePath) {
    res.status(400).json({ error: 'invalid path' });
    return;
  }
  const variantHeader = precomputeCache.readAndroidHeader(filePath);
  if (variantHeader
      && variantHeader.fps === settings.fps
      && variantHeader.bars === settings.bars) {
    res.type('application/octet-stream').sendFile(filePath);
    return;
  }
  // A settings-keyed 30-FPS request may reuse the original cache. Its v2
  // header records the two playback-critical dimensions (FPS and bars), so
  // only fall back when those values match the requested variant.
  const legacyPath = resolveWithin(VISUAL_DIR, req.params[0] + '.fvz');
  const legacyHeader = legacyPath && precomputeCache.readAndroidHeader(legacyPath);
  if (legacyHeader
      && legacyHeader.fps === settings.fps
      && legacyHeader.bars === settings.bars) {
    res.type('application/octet-stream').sendFile(legacyPath);
    return;
  }
  res.status(404).json({ error: 'not found' });
});

app.put(
  '/api/android-visual/:variant/*',
  express.raw({ type: '*/*', limit: '20mb' }),
  async (req, res) => {
    const settings = precomputeCache.parseAndroidVariantKey(req.params.variant);
    if (!settings) {
      res.status(400).json({ error: 'invalid Android visual settings' });
      return;
    }
    const variantDirectory = path.join(
      ANDROID_VISUAL_DIR,
      precomputeCache.androidVariantKey(settings),
    );
    const filePath = resolveWithin(variantDirectory, req.params[0] + '.fvz');
    if (!filePath) {
      res.status(400).json({ error: 'invalid path' });
      return;
    }
    if (!Buffer.isBuffer(req.body) || req.body.length === 0) {
      res.status(400).json({ error: 'expected raw bytes' });
      return;
    }
    const existingHeader = precomputeCache.readAndroidHeader(filePath);
    if (existingHeader
        && existingHeader.fps === settings.fps
        && existingHeader.bars === settings.bars) {
      res.status(204).end();
      return;
    }
    const tempPath = `${filePath}.upload-${crypto.randomBytes(8).toString('hex')}`;
    try {
      await fsp.mkdir(path.dirname(filePath), { recursive: true });
      await fsp.writeFile(tempPath, req.body, { flag: 'wx' });
      const header = precomputeCache.readAndroidHeader(tempPath);
      if (!header || header.fps !== settings.fps || header.bars !== settings.bars) {
        await fsp.unlink(tempPath).catch(() => {});
        res.status(400).json({ error: 'cache header does not match requested settings' });
        return;
      }
      await fsp.rename(tempPath, filePath);
      res.status(204).end();
    } catch (err) {
      await fsp.unlink(tempPath).catch(() => {});
      res.status(500).json({ error: 'write failed' });
    }
  },
);

app.get('/api/visual/*', async (req, res) => {
  const filePath = resolveWithin(VISUAL_DIR, req.params[0] + '.fvz');
  if (!filePath) {
    res.status(400).json({ error: 'invalid path' });
    return;
  }
  try {
    const contents = await fsp.readFile(filePath);
    res.type('application/octet-stream').send(contents);
  } catch (err) {
    res.status(404).json({ error: 'not found' });
  }
});

app.put('/api/visual/*', express.raw({ type: '*/*', limit: '20mb' }), async (req, res) => {
  const filePath = resolveWithin(VISUAL_DIR, req.params[0] + '.fvz');
  if (!filePath) {
    res.status(400).json({ error: 'invalid path' });
    return;
  }
  if (!Buffer.isBuffer(req.body) || req.body.length === 0) {
    res.status(400).json({ error: 'expected raw bytes' });
    return;
  }
  try {
    await fsp.mkdir(path.dirname(filePath), { recursive: true });
    await fsp.writeFile(filePath, req.body);
    res.status(204).end();
  } catch (err) {
    res.status(500).json({ error: 'write failed' });
  }
});

// Apple uses a settings-aware compact format that is intentionally kept out
// of the Android/Linux .fvz namespace. This prevents either client family
// from treating another platform's bytes as a corrupt cache entry.
app.get('/api/apple-visual/*', async (req, res) => {
  const filePath = resolveWithin(APPLE_VISUAL_DIR, req.params[0] + '.fav');
  if (!filePath) {
    res.status(400).json({ error: 'invalid path' });
    return;
  }
  try {
    const contents = await fsp.readFile(filePath);
    res.type('application/octet-stream').send(contents);
  } catch (err) {
    res.status(404).json({ error: 'not found' });
  }
});

app.put('/api/apple-visual/*', express.raw({ type: '*/*', limit: '20mb' }), async (req, res) => {
  const filePath = resolveWithin(APPLE_VISUAL_DIR, req.params[0] + '.fav');
  if (!filePath) {
    res.status(400).json({ error: 'invalid path' });
    return;
  }
  if (!Buffer.isBuffer(req.body) || req.body.length === 0) {
    res.status(400).json({ error: 'expected raw bytes' });
    return;
  }
  try {
    await fsp.mkdir(path.dirname(filePath), { recursive: true });
    await fsp.writeFile(filePath, req.body);
    res.status(204).end();
  } catch (err) {
    res.status(500).json({ error: 'write failed' });
  }
});

// A distinct top-level prefix, not a sub-path of /api/apple-visual/* — Apple
// track-relative paths themselves are multi-segment (Artist/Album/Song.flac),
// so a :variant/* route nested under the existing flat prefix would collide
// with ordinary flat requests. Mirrors why Android's variant route lives
// under /api/android-visual/ rather than as a sub-path of /api/visual/.
function appleHeaderMatchesSettings(header, settings) {
  return Boolean(header)
    && header.fps === settings.fps
    && header.waveformMs === settings.waveformMs
    && header.fftSize === settings.fftSize
    && header.bars === settings.bars
    && header.logarithmic === settings.logarithmic;
}

app.get('/api/apple-visual-variant/:variant/*', async (req, res) => {
  const settings = precomputeCache.parseAppleVariantKey(req.params.variant);
  if (!settings) {
    res.status(400).json({ error: 'invalid Apple visual settings' });
    return;
  }
  const variantDirectory = path.join(
    APPLE_VISUAL_VARIANT_DIR,
    precomputeCache.appleVariantKey(settings),
  );
  const filePath = resolveWithin(variantDirectory, req.params[0] + '.fav');
  if (!filePath) {
    res.status(400).json({ error: 'invalid path' });
    return;
  }
  const variantHeader = precomputeCache.readAppleHeader(filePath);
  if (appleHeaderMatchesSettings(variantHeader, settings)) {
    res.type('application/octet-stream').sendFile(filePath);
    return;
  }
  // A settings-keyed request may reuse the legacy flat cache if it happens
  // to already have been written at the exact requested settings.
  const legacyPath = resolveWithin(APPLE_VISUAL_DIR, req.params[0] + '.fav');
  const legacyHeader = legacyPath && precomputeCache.readAppleHeader(legacyPath);
  if (appleHeaderMatchesSettings(legacyHeader, settings)) {
    res.type('application/octet-stream').sendFile(legacyPath);
    return;
  }
  res.status(404).json({ error: 'not found' });
});

app.put(
  '/api/apple-visual-variant/:variant/*',
  express.raw({ type: '*/*', limit: '20mb' }),
  async (req, res) => {
    const settings = precomputeCache.parseAppleVariantKey(req.params.variant);
    if (!settings) {
      res.status(400).json({ error: 'invalid Apple visual settings' });
      return;
    }
    const variantDirectory = path.join(
      APPLE_VISUAL_VARIANT_DIR,
      precomputeCache.appleVariantKey(settings),
    );
    const filePath = resolveWithin(variantDirectory, req.params[0] + '.fav');
    if (!filePath) {
      res.status(400).json({ error: 'invalid path' });
      return;
    }
    if (!Buffer.isBuffer(req.body) || req.body.length === 0) {
      res.status(400).json({ error: 'expected raw bytes' });
      return;
    }
    const existingHeader = precomputeCache.readAppleHeader(filePath);
    if (appleHeaderMatchesSettings(existingHeader, settings)) {
      res.status(204).end();
      return;
    }
    const tempPath = `${filePath}.upload-${crypto.randomBytes(8).toString('hex')}`;
    try {
      await fsp.mkdir(path.dirname(filePath), { recursive: true });
      await fsp.writeFile(tempPath, req.body, { flag: 'wx' });
      const header = precomputeCache.readAppleHeader(tempPath);
      if (!appleHeaderMatchesSettings(header, settings)) {
        await fsp.unlink(tempPath).catch(() => {});
        res.status(400).json({ error: 'cache header does not match requested settings' });
        return;
      }
      await fsp.rename(tempPath, filePath);
      res.status(204).end();
    } catch (err) {
      await fsp.unlink(tempPath).catch(() => {});
      res.status(500).json({ error: 'write failed' });
    }
  },
);

app.listen(PORT, '127.0.0.1', () => {
  console.log(`FredPlayer media server listening on 127.0.0.1:${PORT}`);
  console.log(`Serving library from ${MUSIC_DIR}`);
});
