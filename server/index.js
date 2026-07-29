require('dotenv').config();

const crypto = require('crypto');
const fs = require('fs');
const fsp = fs.promises;
const http = require('http');
const path = require('path');
const { spawn } = require('child_process');
const express = require('express');
const mm = require('music-metadata');
const precomputeCache = require('./precompute-cache.js');
const sharedPlaylists = require('./shared-playlists.js');
const { issueStreamTicket, validStreamTicket } = require('./stream-tickets.js');

const MUSIC_DIR = path.resolve(process.env.MUSIC_DIR || '');
const PORT = parseInt(process.env.PORT || '8790', 10);
const AUTH_TOKEN = process.env.AUTH_TOKEN || '';
const DATA_DIR = path.join(__dirname, 'data');
const PROFILES_DIR = path.join(DATA_DIR, 'profiles');
const VISUAL_DIR = path.join(DATA_DIR, 'visual');
const ANDROID_VISUAL_DIR = path.join(DATA_DIR, 'android-visual');
const APPLE_VISUAL_DIR = path.join(DATA_DIR, 'apple-visual');
const APPLE_VISUAL_VARIANT_DIR = path.join(DATA_DIR, 'apple-visual-variant');
const LINUX_VISUAL_VARIANT_DIR = path.join(DATA_DIR, 'linux-visual-variant');
const LINUX_VISUAL_USAGE_PATH = path.join(DATA_DIR, 'linux-visual-usage.json');
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
  if (!checkToken(req) && !validStreamTicket(req, AUTH_TOKEN)) {
    res.status(401).json({ error: 'unauthorized' });
    return;
  }
  next();
});

// Resolves a request-supplied relative path against a base directory,
// rejecting anything that would escape it (path traversal guard).
function resolveWithin(baseDir, relPath, alreadyDecoded = false) {
  let decoded;
  try {
    decoded = alreadyDecoded ? (relPath || '') : decodeURIComponent(relPath || '');
  } catch (_error) {
    return null;
  }
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

app.post('/api/stream-ticket', express.json({ limit: '8kb' }), async (req, res) => {
  const serverPath = req.body?.path;
  if (typeof serverPath !== 'string' || !serverPath || serverPath.startsWith('/')) {
    res.status(400).json({ error: 'path is required' });
    return;
  }
  const filePath = resolveWithin(MUSIC_DIR, serverPath, true);
  if (!filePath) {
    res.status(400).json({ error: 'invalid path' });
    return;
  }
  try {
    const stats = await fsp.stat(filePath);
    if (!stats.isFile() || !AUDIO_EXTENSIONS.has(path.extname(filePath).toLowerCase())) {
      res.status(404).json({ error: 'not found' });
      return;
    }
    res.json(issueStreamTicket(serverPath, AUTH_TOKEN));
  } catch (_error) {
    res.status(404).json({ error: 'not found' });
  }
});

// Authenticated Ubuntu requests teach the server which settings are actually
// in use. Only observed settings receive full-library background passes.
let linuxVisualUsage = { version: 1, variants: {} };
try {
  const loaded = JSON.parse(fs.readFileSync(LINUX_VISUAL_USAGE_PATH, 'utf8'));
  if (loaded?.version === 1 && loaded.variants && typeof loaded.variants === 'object') {
    linuxVisualUsage = loaded;
  }
} catch (_error) {}

let linuxUsageSaveTimer = null;
function recordLinuxVisualUsage(key) {
  const firstRequest = !linuxVisualUsage.variants[key];
  const current = linuxVisualUsage.variants[key] || { requests: 0, last_used: '' };
  current.requests = Math.max(0, Number(current.requests) || 0) + 1;
  current.last_used = new Date().toISOString();
  linuxVisualUsage.variants[key] = current;
  if (firstRequest) setImmediate(triggerAutoPrecompute);
  if (linuxUsageSaveTimer) return;
  linuxUsageSaveTimer = setTimeout(async () => {
    linuxUsageSaveTimer = null;
    const tempPath = `${LINUX_VISUAL_USAGE_PATH}.tmp-${process.pid}-${crypto.randomBytes(4).toString('hex')}`;
    try {
      await fsp.mkdir(path.dirname(LINUX_VISUAL_USAGE_PATH), { recursive: true });
      await fsp.writeFile(tempPath, `${JSON.stringify(linuxVisualUsage, null, 2)}\n`, { flag: 'wx' });
      await fsp.rename(tempPath, LINUX_VISUAL_USAGE_PATH);
    } catch (error) {
      await fsp.unlink(tempPath).catch(() => {});
      console.error(`Could not save Ubuntu visual usage: ${error.message}`);
    }
  }, 2000);
  linuxUsageSaveTimer.unref?.();
}

function requestedLinuxSettings() {
  return Object.entries(linuxVisualUsage.variants)
    .filter(([key, usage]) => (Number(usage.requests) || 0) > 0
      && precomputeCache.parseLinuxVariantKey(key))
    .sort((left, right) => (Number(right[1].requests) || 0) - (Number(left[1].requests) || 0))
    .map(([key]) => precomputeCache.parseLinuxVariantKey(key));
}

const precomputeChildQueue = [];
let precomputeChildRunning = false;

function enqueuePrecomputeChild(label, args, priority = 0) {
  return new Promise((resolve, reject) => {
    precomputeChildQueue.push({ label, args, priority, resolve, reject });
    precomputeChildQueue.sort((left, right) => right.priority - left.priority);
    if (!precomputeChildRunning) setImmediate(drainPrecomputeChildQueue);
  });
}

async function drainPrecomputeChildQueue() {
  if (precomputeChildRunning) return;
  precomputeChildRunning = true;
  try {
    while (precomputeChildQueue.length) {
      const job = precomputeChildQueue.shift();
      console.log(`Starting background cache child: ${job.label}`);
      try {
        const didWork = await new Promise((resolve, reject) => {
          const script = path.join(__dirname, 'precompute-cache.js');
          const child = spawn('nice', ['-n', '15', process.execPath, script, ...job.args], {
            cwd: __dirname,
            env: process.env,
            stdio: ['ignore', 'inherit', 'inherit'],
          });
          child.once('error', reject);
          child.once('close', (code, signal) => {
            if (code === 0) resolve(true);
            else if (code === 3) resolve(false);
            else reject(new Error(`cache child exited ${code ?? signal}`));
          });
        });
        job.resolve(didWork);
      } catch (error) {
        job.reject(error);
      }
    }
  } finally {
    precomputeChildRunning = false;
    if (precomputeChildQueue.length) setImmediate(drainPrecomputeChildQueue);
  }
}

function commonPrecomputeArgs() {
  return ['--music-dir', MUSIC_DIR, '--data-dir', DATA_DIR,
    '--concurrency', '1', '--limit', '4', '--nice', '--status-exit'];
}

function linuxPrecomputeArgs(settings, track = '') {
  const args = [...commonPrecomputeArgs(), '--platform', 'linux', '--visual-only',
    '--linux-variant', '--linux-fps', String(settings.fps),
    '--linux-waveform-ms', String(settings.waveformMs),
    '--linux-fft-size', String(settings.fftSize),
    '--linux-bars', String(settings.bars)];
  if (!settings.logarithmic) args.push('--linux-linear');
  if (track) args.push('--track', track);
  return args;
}

const linuxVisualQueue = new Map();
let linuxVisualQueueRunning = false;

function queueLinuxVisual(relativePath, settings) {
  const key = `${precomputeCache.linuxVariantKey(settings)}\n${relativePath}`;
  if (!linuxVisualQueue.has(key)) linuxVisualQueue.set(key, { relativePath, settings });
  if (!linuxVisualQueueRunning) setImmediate(drainLinuxVisualQueue);
}

async function drainLinuxVisualQueue() {
  if (linuxVisualQueueRunning) return;
  linuxVisualQueueRunning = true;
  try {
    while (linuxVisualQueue.size) {
      const [key, request] = linuxVisualQueue.entries().next().value;
      linuxVisualQueue.delete(key);
      const sourcePath = resolveWithin(MUSIC_DIR, request.relativePath, true);
      if (!sourcePath) continue;
      await enqueuePrecomputeChild(
        `Ubuntu requested ${request.relativePath}`,
        linuxPrecomputeArgs(request.settings, request.relativePath),
        10,
      );
    }
  } catch (error) {
    console.error(`Ubuntu visual background fill failed: ${error.message}`);
  } finally {
    linuxVisualQueueRunning = false;
    if (linuxVisualQueue.size) setImmediate(drainLinuxVisualQueue);
  }
}

// Fills missing cache data without doing FFT/decoding work on the HTTP event
// loop. Small, low-priority child batches run at startup, after rescans, and
// periodically while work remains. Authenticated track misses have priority
// between batches, keeping both playback requests and requested caches moving.
let autoPrecomputeRunning = false;
let autoPrecomputeQueued = false;
let autoPrecomputeTimer = null;

async function triggerAutoPrecompute() {
  if (autoPrecomputeTimer) {
    clearTimeout(autoPrecomputeTimer);
    autoPrecomputeTimer = null;
  }
  if (autoPrecomputeRunning) {
    autoPrecomputeQueued = true;
    return;
  }
  autoPrecomputeRunning = true;
  let didWork = false;
  try {
    do {
      autoPrecomputeQueued = false;
      didWork = await runAutoPrecomputePass() || didWork;
    } while (autoPrecomputeQueued);
  } catch (err) {
    console.error(`Auto-precompute failed: ${err.message}`);
  } finally {
    autoPrecomputeRunning = false;
    autoPrecomputeTimer = setTimeout(triggerAutoPrecompute, didWork ? 15000 : 300000);
    autoPrecomputeTimer.unref?.();
  }
}

async function runAutoPrecomputePass() {
  const common = commonPrecomputeArgs();
  const passes = [
    { label: 'legacy/Apple/profile', args: [...common, '--platform', 'both'] },
    { label: `Android ${precomputeCache.androidVariantKey(ANDROID_60_SETTINGS)}`,
      args: [...common, '--platform', 'android', '--visual-only', '--android-variant',
        '--android-fps', String(ANDROID_60_SETTINGS.fps),
        '--android-waveform-ms', String(ANDROID_60_SETTINGS.waveformMs),
        '--android-fft-size', String(ANDROID_60_SETTINGS.fftSize),
        '--android-bars', String(ANDROID_60_SETTINGS.bars)] },
    { label: `Apple ${precomputeCache.appleVariantKey(APPLE_60_SETTINGS)}`,
      args: [...common, '--platform', 'apple', '--visual-only', '--apple-variant',
        '--apple-fps', String(APPLE_60_SETTINGS.fps),
        '--apple-waveform-ms', String(APPLE_60_SETTINGS.waveformMs),
        '--apple-fft-size', String(APPLE_60_SETTINGS.fftSize),
        '--apple-bars', String(APPLE_60_SETTINGS.bars)] },
  ];
  for (const settings of requestedLinuxSettings()) {
    passes.push({
      label: `Ubuntu requested ${precomputeCache.linuxVariantKey(settings)}`,
      args: linuxPrecomputeArgs(settings),
    });
  }
  let didWork = false;
  for (const pass of passes) {
    try {
      didWork = await enqueuePrecomputeChild(pass.label, pass.args, 0) || didWork;
    } catch (error) {
      // One corrupt or unusually short track must not prevent the independent
      // Android, Apple, and Ubuntu cache passes from running. The next sweep
      // will retry anything that remains missing.
      console.error(`Auto-precompute (${pass.label}) failed: ${error.message}`);
    }
  }
  return didWork;
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

app.get('/api/linux-visual-variant/:variant/*', async (req, res) => {
  const settings = precomputeCache.parseLinuxVariantKey(req.params.variant);
  if (!settings) {
    res.status(400).json({ error: 'invalid Ubuntu visual settings' });
    return;
  }
  const relativePath = req.params[0];
  const sourcePath = resolveWithin(MUSIC_DIR, relativePath, true);
  if (!sourcePath) {
    res.status(400).json({ error: 'invalid path' });
    return;
  }
  let sourceStats;
  try {
    sourceStats = await fsp.stat(sourcePath);
    if (!sourceStats.isFile() || !AUDIO_EXTENSIONS.has(path.extname(sourcePath).toLowerCase())) {
      res.status(404).json({ error: 'not found' });
      return;
    }
  } catch (_error) {
    res.status(404).json({ error: 'not found' });
    return;
  }
  const key = precomputeCache.linuxVariantKey(settings);
  recordLinuxVisualUsage(key);
  const variantDirectory = path.join(LINUX_VISUAL_VARIANT_DIR, key);
  const filePath = resolveWithin(variantDirectory, `${relativePath}.flv`, true);
  if (!filePath) {
    res.status(400).json({ error: 'invalid path' });
    return;
  }
  const header = precomputeCache.readLinuxHeader(filePath);
  if (header
      && Math.abs(header.fps - settings.fps) < 0.001
      && Math.abs(header.waveformMs - settings.waveformMs) < 0.001
      && header.fftSize === settings.fftSize
      && header.bars === settings.bars
      && Boolean(header.flags & 1) === settings.logarithmic
      && header.sourceSize === sourceStats.size
      && Math.abs(header.sourceMtimeMs - Math.round(sourceStats.mtimeMs)) <= 1) {
    res.type('application/octet-stream').sendFile(filePath);
    return;
  }
  queueLinuxVisual(relativePath, settings);
  res.set('Retry-After', '5');
  res.status(202).json({ status: 'queued', variant: key });
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
