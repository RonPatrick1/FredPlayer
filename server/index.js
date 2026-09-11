require('dotenv').config();

const crypto = require('crypto');
const fs = require('fs');
const fsp = fs.promises;
const http = require('http');
const path = require('path');
const { spawn } = require('child_process');
const { Transform } = require('stream');
const express = require('express');
const mm = require('music-metadata');
const precomputeCache = require('./precompute-cache.js');
const sharedPlaylists = require('./shared-playlists.js');
const artwork = require('./artwork.js');
const lyrics = require('./lyrics.js');
const { encodeServerPath, issueStreamTicket, validStreamTicket } = require('./stream-tickets.js');
const webAuth = require('./web-auth.js');
const webLeveling = require('./web-leveling.js');
const { TeslaTelemetryRedisBridge } = require('./tesla-telemetry.js');

const MUSIC_DIR = path.resolve(process.env.MUSIC_DIR || '');
const PORT = parseInt(process.env.PORT || '8790', 10);
const AUTH_TOKEN = process.env.AUTH_TOKEN || '';
const DATA_DIR = process.env.DATA_DIR
  ? path.resolve(process.env.DATA_DIR)
  : path.join(__dirname, 'data');
const PROFILES_DIR = path.join(DATA_DIR, 'profiles');
const VISUAL_DIR = path.join(DATA_DIR, 'visual');
const ANDROID_VISUAL_DIR = path.join(DATA_DIR, 'android-visual');
const APPLE_VISUAL_DIR = path.join(DATA_DIR, 'apple-visual');
const APPLE_VISUAL_VARIANT_DIR = path.join(DATA_DIR, 'apple-visual-variant');
const LINUX_VISUAL_VARIANT_DIR = path.join(DATA_DIR, 'linux-visual-variant');
const LINUX_VISUAL_USAGE_PATH = path.join(DATA_DIR, 'linux-visual-usage.json');
const ARTWORK_DIR = path.join(DATA_DIR, 'artwork');
// Small per-pass cap: MusicBrainz's usage policy asks for ~1 request/sec, and
// the auto-precompute loop already retriggers every 15s while work remains,
// so a big backlog just gets worked through incrementally across ticks
// rather than one pass blocking everything else for minutes.
const ARTWORK_PASS_LIMIT = 5;
const ARTWORK_EMBEDDED_PASS_LIMIT = 1000;
const ARTWORK_PASS_INTERVAL_MS = 5 * 60 * 1000;
const PLAYLISTS_DIR = path.join(DATA_DIR, 'playlists');
const LIAM_ASK_URL = process.env.LIAM_ASK_URL || 'http://127.0.0.1:8787/fredplayer-ask';
const REVIEW_PROXY_PORT = parseInt(process.env.REVIEW_PROXY_PORT || '0', 10);
// This hop is localhost-only (Node -> LiamAgent), not through nginx, so it
// can afford real headroom for handle_fredplayer_ask's up-to-3 retry
// attempts against a slow local model.
const LIAM_ASK_TIMEOUT_MS = 550000;
const WEB_ENABLED = process.env.WEB_ENABLED === '1';
const WEB_USERNAME = process.env.WEB_USERNAME || '';
const WEB_PASSWORD_HASH = process.env.WEB_PASSWORD_HASH || '';
const WEB_SESSION_SECRET = process.env.WEB_SESSION_SECRET || '';
const WEB_SESSION_VERSION = parseInt(process.env.WEB_SESSION_VERSION || '1', 10);
const WEB_COOKIE_NAME = '__Secure-fredplayer_session';
const WEB_COOKIE_PATH = process.env.WEB_COOKIE_PATH || '/fredplayer-media/web';
const WEB_DIR = path.join(__dirname, 'web');
const WEB_DIAGNOSTICS_PATH = path.join(DATA_DIR, 'web-diagnostics.jsonl');
const WEB_AUDIO_STREAM_SCRIPT = path.join(__dirname, 'web-audio-stream.js');
// Fixed output rate for the Web streaming pipeline (web-audio-stream.js
// forces -ar 48000 on both the decode and encode side, no resampling in
// between) — kept in sync with that script's hardcoded rate.
const WEB_AUDIO_SAMPLE_RATE = 48000;
const TESLA_TELEMETRY_REDIS_URL = process.env.TESLA_TELEMETRY_REDIS_URL || '';
const TESLA_TELEMETRY_REDIS_PATTERN = process.env.TESLA_TELEMETRY_REDIS_PATTERN
  || 'tesla_telemetry_V_*';
const TESLA_CONTROL_URL = process.env.TESLA_CONTROL_URL || '';
const TESLA_CONTROL_TOKEN = process.env.TESLA_CONTROL_TOKEN || '';
const TESLA_CONTROL_ENV_PATH = process.env.TESLA_CONTROL_ENV_PATH || '';

const AUDIO_EXTENSIONS = new Set([
  '.mp3', '.flac', '.m4a', '.wav', '.ogg', '.aac', '.wma', '.opus', '.alac',
]);

function artworkArtistForTrack(track) {
  return track?.albumArtist || track?.artist || '';
}

function cachedArtworkFileForTrack(track) {
  if (!track?.album) return null;
  const artists = [...new Set([artworkArtistForTrack(track), track.artist].filter(Boolean))];
  for (const artist of artists) {
    const filePath = path.join(ARTWORK_DIR,
      `${artwork.albumCacheKey(artist, track.album)}.jpg`);
    if (fs.existsSync(filePath)) return filePath;
  }
  return null;
}

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
let libraryPromise;
const webDurationCache = new Map();
const fluxaGrants = new Map();
const fluxaLaunchTickets = new Map();
const fluxaQueueTickets = new Map();

function issueFluxaGrant() {
  const grant = crypto.randomBytes(32).toString('base64url');
  const expiresAt = Date.now() + 2 * 60 * 1000;
  fluxaGrants.set(grant, expiresAt);
  for (const [candidate, expiry] of fluxaGrants) {
    if (expiry <= Date.now()) fluxaGrants.delete(candidate);
  }
  return grant;
}

function issueFluxaLaunchTicket(launch) {
  const ticket = crypto.randomBytes(32).toString('base64url');
  fluxaLaunchTickets.set(ticket, {
    ...launch,
    expiresAt: Date.now() + 2 * 60 * 1000,
  });
  return ticket;
}

function issueFluxaQueueTicket(launch) {
  const ticket = crypto.randomBytes(32).toString('base64url');
  fluxaQueueTickets.set(ticket, { ...launch, expiresAt: Date.now() + 2 * 60 * 1000 });
  for (const [candidate, value] of fluxaQueueTickets) {
    if (value.expiresAt <= Date.now()) fluxaQueueTickets.delete(candidate);
  }
  return ticket;
}

function probeAudioDuration(filePath) {
  const cached = webDurationCache.get(filePath);
  if (cached) return cached;
  const pending = new Promise((resolve, reject) => {
    const probe = spawn('ffprobe', [
      '-v', 'error', '-show_entries', 'format=duration',
      '-of', 'default=noprint_wrappers=1:nokey=1', filePath,
    ], { stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    let errorOutput = '';
    probe.stdout.setEncoding('utf8');
    probe.stderr.setEncoding('utf8');
    probe.stdout.on('data', (chunk) => { output += chunk; });
    probe.stderr.on('data', (chunk) => { errorOutput += chunk; });
    probe.on('error', reject);
    probe.on('close', (code) => {
      const duration = Number(output.trim());
      if (code === 0 && Number.isFinite(duration) && duration > 0) resolve(duration);
      else reject(new Error(errorOutput.trim() || 'Could not determine track duration'));
    });
  }).catch((error) => {
    webDurationCache.delete(filePath);
    throw error;
  });
  webDurationCache.set(filePath, pending);
  return pending;
}

// FLAC's STREAMINFO metadata block carries the stream's total sample count
// (and an MD5 checksum), which a normal encoder writes by seeking back to
// the start of its output once it's done. web-audio-stream.js always
// writes to a pipe (so playback can start immediately instead of waiting
// for a full-track encode) — pipes aren't seekable, so ffmpeg silently
// leaves total_samples at zero instead. A stream reporting zero total
// samples reads as "unknown/live duration" to a stricter decoder, which
// can plausibly affect how it hands the decoded audio off for output
// routing. We already know the real answer before the encode even starts
// (source duration minus the requested start position, at this pipeline's
// fixed sample rate), so patch it into the first ~42 bytes of the
// response in memory as they pass through — no temp file, and no added
// latency for the rest of the stream since everything after that first
// chunk just flows through unmodified.
//
// STREAMINFO byte layout (FLAC spec): 4-byte "fLaC" magic, 4-byte metadata
// block header, then a 34-byte STREAMINFO body whose last 36 bits (byte 21
// low nibble through byte 25) hold total_samples — see
// https://xiph.org/flac/format.html#metadata_block_streaminfo
function patchFlacStreaminfoTotalSamples(totalSamples) {
  let patched = false;
  let pending = Buffer.alloc(0);
  return new Transform({
    transform(chunk, _encoding, callback) {
      if (patched) {
        callback(null, chunk);
        return;
      }
      pending = pending.length ? Buffer.concat([pending, chunk]) : chunk;
      if (pending.length < 42) {
        callback();
        return;
      }
      patched = true;
      if (pending.toString('ascii', 0, 4) === 'fLaC') {
        const total = BigInt(Math.max(0, Math.round(totalSamples)));
        pending[21] = (pending[21] & 0xf0) | Number((total >> 32n) & 0xfn);
        pending[22] = Number((total >> 24n) & 0xffn);
        pending[23] = Number((total >> 16n) & 0xffn);
        pending[24] = Number((total >> 8n) & 0xffn);
        pending[25] = Number(total & 0xffn);
      }
      const output = pending;
      pending = Buffer.alloc(0);
      callback(null, output);
    },
    flush(callback) {
      // Stream ended before 42 bytes ever arrived (e.g. a near-empty
      // source) — pass along whatever was buffered, unpatched.
      callback(null, pending.length ? pending : undefined);
    },
  });
}

function stopWebAudioProcess(child) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  const signalGroup = (signal) => {
    try { process.kill(-child.pid, signal); } catch (_error) {}
  };
  signalGroup('SIGTERM');
  const forceTimer = setTimeout(() => signalGroup('SIGKILL'), 1000);
  forceTimer.unref();
  child.once('close', () => clearTimeout(forceTimer));
}

// Keep store-review data and credentials in a separate local process while
// exposing it through the existing HTTPS FredPlayer route. This middleware
// intentionally runs before the personal-server authentication check; the
// review process performs its own token validation using a different token.
if (Number.isInteger(REVIEW_PROXY_PORT)
    && REVIEW_PROXY_PORT > 0
    && REVIEW_PROXY_PORT <= 65_535
    && REVIEW_PROXY_PORT !== PORT) {
  app.use('/review', (req, res) => {
    const headers = { ...req.headers, host: `127.0.0.1:${REVIEW_PROXY_PORT}` };
    const upstreamRequest = http.request({
      hostname: '127.0.0.1',
      port: REVIEW_PROXY_PORT,
      method: req.method,
      path: req.url || '/',
      headers,
    }, (upstreamResponse) => {
      res.writeHead(upstreamResponse.statusCode || 502, upstreamResponse.headers);
      upstreamResponse.pipe(res);
    });
    upstreamRequest.on('error', () => {
      if (!res.headersSent) {
        res.status(502).json({ error: 'review server unavailable' });
      } else {
        res.destroy();
      }
    });
    req.pipe(upstreamRequest);
  });
}

const webLoginAttempts = new Map();
const webTeslaEventClients = new Set();
const webTeslaGestures = new Map();
let webTeslaEventId = 0;
let teslaTelemetry = null;
let teslaControlTokenPromise = null;

async function appendWebDiagnostic(entry) {
  await fsp.mkdir(path.dirname(WEB_DIAGNOSTICS_PATH), { recursive: true });
  await fsp.appendFile(WEB_DIAGNOSTICS_PATH, `${JSON.stringify(entry)}\n`, { mode: 0o600 });
}

async function resolvedTeslaControlToken() {
  if (TESLA_CONTROL_TOKEN) return TESLA_CONTROL_TOKEN;
  if (!TESLA_CONTROL_ENV_PATH) return '';
  if (!teslaControlTokenPromise) {
    teslaControlTokenPromise = fsp.readFile(TESLA_CONTROL_ENV_PATH, 'utf8').then((raw) => {
      const line = raw.split(/\r?\n/).find((candidate) => candidate.startsWith('WATCH_API_TOKEN='));
      if (!line) return '';
      const value = line.slice('WATCH_API_TOKEN='.length).trim();
      if (value.length >= 2 && value[0] === value.at(-1) && ['"', "'"].includes(value[0])) {
        return value.slice(1, -1);
      }
      return value;
    });
  }
  return teslaControlTokenPromise;
}

async function restoreTeslaVolume(direction) {
  if (!TESLA_CONTROL_URL) throw new Error('Tesla volume control URL is not configured');
  const token = await resolvedTeslaControlToken();
  if (!token) throw new Error('Tesla volume control token is not configured');
  const command = direction === 'up' ? 'media_volume_down'
    : direction === 'down' ? 'media_volume_up' : '';
  if (!command) throw new Error('Tesla volume gesture direction is invalid');
  const response = await fetch(TESLA_CONTROL_URL, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ command }),
    signal: AbortSignal.timeout(8000),
  });
  if (!response.ok) throw new Error(`Tesla volume correction failed (${response.status})`);
}

function broadcastTeslaTelemetryEvent(event) {
  webTeslaEventId += 1;
  if (event.type === 'volume-gesture-candidate'
    && event.count === 1
    && ['up', 'down'].includes(event.direction)) {
    webTeslaGestures.set(webTeslaEventId, {
      direction: event.direction,
      previousVolume: event.previousVolume,
      claimed: false,
      expiresAt: Date.now() + 15_000,
    });
  }
  for (const [id, gesture] of webTeslaGestures) {
    if (gesture.expiresAt <= Date.now()) webTeslaGestures.delete(id);
  }
  const payload = JSON.stringify({ id: webTeslaEventId, ...event });
  const message = `id: ${webTeslaEventId}\nevent: ${event.type}\ndata: ${payload}\n\n`;
  for (const response of webTeslaEventClients) response.write(message);
  appendWebDiagnostic({
    receivedAt: new Date().toISOString(),
    report: { automatic: true, kind: 'tesla-fleet-telemetry', event },
  }).catch((error) => console.error(`Could not store Tesla telemetry diagnostic: ${error.message}`));
}

function webClientAddress(req) {
  const forwarded = (req.get('x-forwarded-for') || '').split(',').map((value) => value.trim());
  return forwarded.filter(Boolean).at(-1) || req.socket.remoteAddress || 'unknown';
}

function sameOriginWebRequest(req) {
  const origin = req.get('origin');
  if (!origin) return true;
  try {
    const protocol = req.get('x-forwarded-proto') || req.protocol;
    return new URL(origin).origin === `${protocol}://${req.get('host')}`;
  } catch (_error) {
    return false;
  }
}

function webSession(req) {
  const cookie = webAuth.cookieValue(req.get('cookie'), WEB_COOKIE_NAME);
  const cookieSession = webAuth.verifySession(cookie, WEB_SESSION_SECRET, { version: WEB_SESSION_VERSION });
  if (cookieSession) return cookieSession;
  const authorization = req.get('authorization') || '';
  if (!authorization.startsWith('Bearer ')) return null;
  return webAuth.verifySession(authorization.slice(7), WEB_SESSION_SECRET, { version: WEB_SESSION_VERSION });
}

function requireWebSession(req, res, next) {
  const session = webSession(req);
  if (!session || session.sub !== WEB_USERNAME) {
    res.status(401).json({ error: 'login required' });
    return;
  }
  req.webSession = session;
  next();
}

function requireWebAudioAccess(req, res, next) {
  const session = webSession(req);
  if (session && session.sub === WEB_USERNAME) {
    req.webSession = session;
    next();
    return;
  }
  const serverPath = req.params[0];
  const ticketRequest = {
    method: req.method,
    path: `/stream/${encodeServerPath(serverPath)}`,
    query: req.query,
  };
  if (validStreamTicket(ticketRequest, AUTH_TOKEN)) {
    next();
    return;
  }
  res.status(401).json({ error: 'audio authorization required' });
}

function setWebSecurityHeaders(_req, res, next) {
  const frameAncestors = _req.query?.platform === 'tizen'
    ? "'self' http://192.168.0.178:8097 file:"
    : "'self' http://192.168.0.178:8097";
  res.set({
    'Content-Security-Policy': `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; media-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors ${frameAncestors}; form-action 'self'`,
    'Permissions-Policy': 'camera=(), geolocation=(), microphone=()',
    'Referrer-Policy': 'no-referrer',
    'X-Content-Type-Options': 'nosniff',
  });
  next();
}

if (WEB_ENABLED) {
  if (!WEB_USERNAME || !WEB_PASSWORD_HASH || WEB_SESSION_SECRET.length < 32) {
    console.error('WEB_ENABLED requires WEB_USERNAME, WEB_PASSWORD_HASH, and a 32+ character WEB_SESSION_SECRET');
    process.exit(1);
  }

  app.use('/web', setWebSecurityHeaders);
  app.get(['/web', '/web/'], (req, res) => {
    if (!req.path.endsWith('/')) {
      // Keep this redirect relative so it also preserves a reverse proxy prefix
      // such as /fredplayer-media. Without the trailing slash, browsers resolve
      // ./assets outside the Web UI and the application cannot start.
      res.redirect(308, 'web/');
      return;
    }
    res.set('Cache-Control', 'no-store').sendFile(path.join(WEB_DIR, 'index.html'));
  });
  app.use('/web/assets', express.static(path.join(WEB_DIR, 'assets'), {
    etag: true,
    fallthrough: false,
    maxAge: '1h',
  }));
  app.get('/web/manifest.webmanifest', (_req, res) => {
    res.type('application/manifest+json').sendFile(path.join(WEB_DIR, 'manifest.webmanifest'));
  });
  app.get('/web/service-worker.js', (_req, res) => {
    res.set('Cache-Control', 'no-cache').type('application/javascript')
      .sendFile(path.join(WEB_DIR, 'service-worker.js'));
  });

  app.get('/web/auth/session', (req, res) => {
    res.set('Cache-Control', 'no-store');
    const session = webSession(req);
    res.json({
      authenticated: Boolean(session && session.sub === WEB_USERNAME),
      username: WEB_USERNAME,
    });
  });

  app.post('/web/auth/login', express.json({ limit: '4kb' }), async (req, res) => {
    res.set('Cache-Control', 'no-store');
    if (!sameOriginWebRequest(req)) {
      res.status(403).json({ error: 'invalid request origin' });
      return;
    }
    const address = webClientAddress(req);
    const now = Date.now();
    const previous = webLoginAttempts.get(address);
    const attempt = previous && previous.resetAt > now
      ? previous
      : { count: 0, resetAt: now + 15 * 60 * 1000 };
    if (attempt.count >= 5) {
      res.set('Retry-After', String(Math.max(1, Math.ceil((attempt.resetAt - now) / 1000))));
      res.status(429).json({ error: 'too many login attempts; try again later' });
      return;
    }
    const username = typeof req.body?.username === 'string' ? req.body.username : '';
    const password = typeof req.body?.password === 'string' ? req.body.password : '';
    const passwordMatches = await webAuth.verifyPassword(password, WEB_PASSWORD_HASH);
    if (username !== WEB_USERNAME || !passwordMatches) {
      attempt.count += 1;
      webLoginAttempts.set(address, attempt);
      res.status(401).json({ error: 'incorrect username or password' });
      return;
    }
    webLoginAttempts.delete(address);
    const remembered = req.body?.remember !== false;
    const sessionSeconds = remembered
      ? webAuth.REMEMBERED_DEVICE_SECONDS : webAuth.DEFAULT_SESSION_SECONDS;
    const session = webAuth.createSession(WEB_USERNAME, WEB_SESSION_SECRET, {
      version: WEB_SESSION_VERSION,
      ttlSeconds: sessionSeconds,
    });
    const maxAge = remembered ? `; Max-Age=${sessionSeconds}` : '';
    res.set('Set-Cookie', `${WEB_COOKIE_NAME}=${encodeURIComponent(session)}${maxAge}; Path=${WEB_COOKIE_PATH}; Secure; HttpOnly; SameSite=None`);
    const deviceToken = remembered
      ? webAuth.createSession(WEB_USERNAME, WEB_SESSION_SECRET, {
        version: WEB_SESSION_VERSION,
        ttlSeconds: webAuth.REMEMBERED_DEVICE_SECONDS,
      })
      : null;
    res.json({ authenticated: true, deviceToken });
  });

  app.post('/web/auth/device', express.json({ limit: '8kb' }), (req, res) => {
    res.set('Cache-Control', 'no-store');
    if (!sameOriginWebRequest(req)) {
      res.status(403).json({ error: 'invalid request origin' });
      return;
    }
    const token = typeof req.body?.token === 'string' ? req.body.token : '';
    const device = webAuth.verifySession(token, WEB_SESSION_SECRET, { version: WEB_SESSION_VERSION });
    if (!device || device.sub !== WEB_USERNAME) {
      res.status(401).json({ error: 'saved TV sign-in is no longer valid' });
      return;
    }
    const session = webAuth.createSession(WEB_USERNAME, WEB_SESSION_SECRET, {
      version: WEB_SESSION_VERSION,
      ttlSeconds: webAuth.REMEMBERED_DEVICE_SECONDS,
    });
    res.set('Set-Cookie', `${WEB_COOKIE_NAME}=${encodeURIComponent(session)}; Max-Age=${webAuth.REMEMBERED_DEVICE_SECONDS}; Path=${WEB_COOKIE_PATH}; Secure; HttpOnly; SameSite=None`);
    res.json({ authenticated: true });
  });

  app.post('/web/auth/device/enroll', requireWebSession, (_req, res) => {
    res.set('Cache-Control', 'no-store');
    const deviceToken = webAuth.createSession(WEB_USERNAME, WEB_SESSION_SECRET, {
      version: WEB_SESSION_VERSION,
      ttlSeconds: webAuth.REMEMBERED_DEVICE_SECONDS,
    });
    res.json({ deviceToken });
  });

  app.post('/web/auth/fluxa-grant', requireWebSession, (req, res) => {
    res.set('Cache-Control', 'no-store');
    if (!sameOriginWebRequest(req)) {
      res.status(403).json({ error: 'invalid request origin' });
      return;
    }
    res.json({ grant: issueFluxaGrant(), expiresIn: 120 });
  });

  app.post('/web/auth/logout', (req, res) => {
    res.set('Cache-Control', 'no-store');
    if (!sameOriginWebRequest(req)) {
      res.status(403).json({ error: 'invalid request origin' });
      return;
    }
    res.set('Set-Cookie', `${WEB_COOKIE_NAME}=; Max-Age=0; Path=${WEB_COOKIE_PATH}; Secure; HttpOnly; SameSite=None`);
    res.status(204).end();
  });

  app.get('/web/auth/fluxa-launch/:ticket', (req, res) => {
    res.set('Cache-Control', 'no-store');
    const ticket = String(req.params.ticket || '');
    const launch = fluxaLaunchTickets.get(ticket);
    fluxaLaunchTickets.delete(ticket);
    if (!launch || launch.expiresAt <= Date.now()) {
      res.status(401).send('This Fluxa music launch has expired. Return to Fluxa and select the track again.');
      return;
    }
    const session = webAuth.createSession(WEB_USERNAME, WEB_SESSION_SECRET, {
      version: WEB_SESSION_VERSION,
      ttlSeconds: webAuth.REMEMBERED_DEVICE_SECONDS,
    });
    res.set('Set-Cookie', `${WEB_COOKIE_NAME}=${encodeURIComponent(session)}; Max-Age=${webAuth.REMEMBERED_DEVICE_SECONDS}; Path=${WEB_COOKIE_PATH}; Secure; HttpOnly; SameSite=None`);
    const query = new URLSearchParams({
      fluxa: '1',
      platform: 'tizen',
      return: launch.returnUrl,
    });
    if (Array.isArray(launch.trackPaths) && launch.trackPaths.length) {
      query.set('queue', issueFluxaQueueTicket(launch));
    } else {
      query.set('play', launch.trackPath);
    }
    res.redirect(303, `${WEB_COOKIE_PATH}/?${query}`);
  });

  app.get('/web/api/fluxa-queue/:ticket', requireWebSession, (req, res) => {
    res.set('Cache-Control', 'no-store');
    const ticket = String(req.params.ticket || '');
    const launch = fluxaQueueTickets.get(ticket);
    fluxaQueueTickets.delete(ticket);
    if (!launch || launch.expiresAt <= Date.now()) {
      res.status(401).json({ error: 'This Fluxa playback queue has expired.' });
      return;
    }
    res.json({
      paths: launch.trackPaths,
      sourceName: launch.sourceName,
      sourceKind: launch.sourceKind,
      shuffle: launch.shuffle === true,
      startPath: launch.startPath || '',
    });
  });

  app.get('/web/api/library', requireWebSession, async (_req, res) => {
    res.json(await libraryPromise);
  });

  app.get('/web/api/playlists', requireWebSession, async (_req, res) => {
    res.json(await sharedPlaylists.listSharedPlaylists(PLAYLISTS_DIR));
  });

  app.get('/web/api/playlists/:name', requireWebSession, async (req, res) => {
    const playlist = await sharedPlaylists.readSharedPlaylist(PLAYLISTS_DIR, req.params.name);
    if (!playlist) {
      res.status(404).json({ error: 'not found' });
      return;
    }
    res.json(playlist);
  });

  app.post('/web/api/stream-ticket', requireWebSession, express.json({ limit: '8kb' }), async (req, res) => {
    const serverPath = req.body?.path;
    if (typeof serverPath !== 'string' || !serverPath || serverPath.startsWith('/')) {
      res.status(400).json({ error: 'path is required' });
      return;
    }
    const filePath = resolveWithin(MUSIC_DIR, serverPath, true);
    try {
      const stats = filePath && await fsp.stat(filePath);
      if (!stats?.isFile() || !AUDIO_EXTENSIONS.has(path.extname(filePath).toLowerCase())) {
        res.status(404).json({ error: 'not found' });
        return;
      }
      res.json(issueStreamTicket(serverPath, AUTH_TOKEN));
    } catch (_error) {
      res.status(404).json({ error: 'not found' });
    }
  });

  app.get('/web/api/gain/*', requireWebSession, async (req, res) => {
    const filePath = resolveWithin(PROFILES_DIR, `${req.params[0]}.json`, true);
    try {
      const profile = JSON.parse(await fsp.readFile(filePath, 'utf8'));
      res.set('Cache-Control', 'private, no-store');
      res.json({
        gain: webLeveling.gainForProfile(profile),
        rms: Number.isFinite(profile.rms) ? profile.rms : null,
        peak: Number.isFinite(profile.peak) ? profile.peak : null,
      });
    } catch (_error) {
      res.status(404).json({ error: 'not found' });
    }
  });

  app.get('/web/api/audio-info/*', requireWebSession, async (req, res) => {
    const filePath = resolveWithin(MUSIC_DIR, req.params[0], true);
    try {
      const stats = filePath && await fsp.stat(filePath);
      if (!stats?.isFile() || !AUDIO_EXTENSIONS.has(path.extname(filePath).toLowerCase())) {
        res.status(404).json({ error: 'not found' });
        return;
      }
      const duration = await probeAudioDuration(filePath);
      res.set('Cache-Control', 'private, max-age=21600').json({ duration });
    } catch (_error) {
      res.status(404).json({ error: 'duration not available' });
    }
  });

  app.get('/web/api/audio/*', requireWebAudioAccess, async (req, res) => {
    const serverPath = req.params[0];
    const filePath = resolveWithin(MUSIC_DIR, serverPath, true);
    const start = Math.max(0, Number(req.query.start) || 0);
    const format = req.query.format === 'mp3' ? 'mp3' : 'flac';
    const leveling = req.query.leveling !== '0';
    const bassGain = Math.max(0, Math.min(9, Number(req.query.bass_gain) || 0));
    try {
      const stats = filePath && await fsp.stat(filePath);
      if (!stats?.isFile() || !AUDIO_EXTENSIONS.has(path.extname(filePath).toLowerCase())) {
        res.status(404).json({ error: 'not found' });
        return;
      }

      let profile = null;
      if (leveling) {
        const profilePath = resolveWithin(PROFILES_DIR, `${serverPath}.json`, true);
        try { profile = JSON.parse(await fsp.readFile(profilePath, 'utf8')); } catch (_error) {}
      }

      // See patchFlacStreaminfoTotalSamples() — only meaningful for FLAC.
      // probeAudioDuration() is cached and usually already warm (the client
      // fetches audio-info in parallel with starting playback), so this
      // rarely adds a real ffprobe round-trip on the hot path; if it fails
      // for any reason we just skip the patch instead of failing the
      // stream.
      let totalSamples = null;
      if (format === 'flac') {
        try {
          const duration = await probeAudioDuration(filePath);
          totalSamples = Math.max(0, Math.round((duration - start) * WEB_AUDIO_SAMPLE_RATE));
        } catch (_error) {
          totalSamples = null;
        }
      }

      const args = [
        WEB_AUDIO_STREAM_SCRIPT,
        '--source', filePath,
        '--start', start.toFixed(3),
        '--format', format,
        '--leveling', leveling ? '1' : '0',
        '--bass-gain', bassGain.toFixed(2),
      ];
      if (Number.isFinite(profile?.rms) && Number.isFinite(profile?.peak)) {
        args.push('--rms', String(profile.rms), '--peak', String(profile.peak));
      }

      // Give each Web stream its own process group so disconnecting a browser
      // can reliably stop the worker and both ffmpeg children together.
      const child = spawn(process.execPath, args, {
        detached: true,
        stdio: ['ignore', 'pipe', 'pipe'],
      });
      let disconnected = false;
      let errorOutput = '';
      res.set({
        'Accept-Ranges': 'none',
        'Cache-Control': 'private, no-store',
        'Content-Type': format === 'mp3' ? 'audio/mpeg' : 'audio/flac',
        'X-Accel-Buffering': 'no',
      });
      child.stderr.setEncoding('utf8');
      child.stderr.on('data', (chunk) => { errorOutput = (errorOutput + chunk).slice(-4096); });
      if (Number.isFinite(totalSamples)) {
        child.stdout.pipe(patchFlacStreaminfoTotalSamples(totalSamples)).pipe(res);
      } else {
        child.stdout.pipe(res);
      }
      child.on('error', (error) => {
        if (!res.headersSent) res.status(500).json({ error: 'Could not start Web audio processor' });
        else res.destroy(error);
      });
      child.on('close', (code, signal) => {
        if (!disconnected && code !== 0 && signal !== 'SIGTERM') {
          console.error(`Web audio processor failed for ${serverPath}: ${errorOutput.trim() || `exit ${code}`}`);
          if (!res.headersSent) res.status(500).json({ error: 'Web audio processing failed' });
          else if (!res.writableEnded) res.destroy();
        }
      });
      res.on('close', () => {
        disconnected = true;
        stopWebAudioProcess(child);
      });
    } catch (_error) {
      res.status(404).json({ error: 'not found' });
    }
  });

  app.get('/web/api/visual/*', requireWebSession, (req, res) => {
    const variant = precomputeCache.androidVariantKey(ANDROID_60_SETTINGS);
    const variantDirectory = path.join(ANDROID_VISUAL_DIR, variant);
    const filePath = resolveWithin(variantDirectory, `${req.params[0]}.fvz`, true);
    if (!filePath) {
      res.status(400).json({ error: 'invalid path' });
      return;
    }
    const header = precomputeCache.readAndroidHeader(filePath);
    if (!header
        || header.fps !== ANDROID_60_SETTINGS.fps
        || header.waveformPoints !== 96
        || header.bars !== ANDROID_60_SETTINGS.bars) {
      res.status(404).json({ error: 'visualization not available' });
      return;
    }
    res.set('Cache-Control', 'private, max-age=21600');
    res.type('application/octet-stream').sendFile(filePath, (error) => {
      if (error && !res.headersSent) res.status(404).end();
    });
  });

  app.get('/web/api/lyrics/*', requireWebSession, async (req, res) => {
    const sidecarRelativePath = req.params[0].replace(/\.[^./\\]+$/, '') + '.lyrics.txt';
    const filePath = resolveWithin(MUSIC_DIR, sidecarRelativePath, true);
    if (!filePath) {
      res.status(400).json({ error: 'invalid path' });
      return;
    }
    try {
      const raw = await fsp.readFile(filePath, 'utf8');
      res.json(lyrics.parseLyricsSidecar(raw));
    } catch (_error) {
      res.status(404).json({ error: 'lyrics not available' });
    }
  });

  app.get('/web/api/artwork/*', requireWebSession, async (req, res) => {
    const library = await libraryPromise;
    const track = library.find((entry) => entry.path === req.params[0]);
    if (!track?.artist || !track?.album) {
      res.status(404).end();
      return;
    }
    const filePath = cachedArtworkFileForTrack(track);
    if (!filePath) {
      res.status(404).end();
      return;
    }
    res.type('image/jpeg').sendFile(filePath, (error) => {
      if (error && !res.headersSent) res.status(404).end();
    });
  });

  app.get('/web/api/tesla-events', requireWebSession, (req, res) => {
    res.status(200).set({
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-store, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    });
    res.flushHeaders();
    res.write(`event: telemetry-status\ndata: ${JSON.stringify({
      configured: Boolean(TESLA_TELEMETRY_REDIS_URL),
    })}\n\n`);
    webTeslaEventClients.add(res);
    const keepalive = setInterval(() => res.write(': keepalive\n\n'), 20_000);
    keepalive.unref?.();
    req.on('close', () => {
      clearInterval(keepalive);
      webTeslaEventClients.delete(res);
    });
  });

  app.post('/web/api/tesla-events/:id/claim', requireWebSession, express.json({ limit: '1kb' }), (req, res) => {
    if (!sameOriginWebRequest(req)) {
      res.status(403).json({ error: 'cross-origin Tesla control request denied' });
      return;
    }
    const id = Number(req.params.id);
    const gesture = Number.isSafeInteger(id) ? webTeslaGestures.get(id) : null;
    if (!gesture || gesture.expiresAt <= Date.now() || gesture.claimed) {
      res.json({ claimed: false });
      return;
    }
    gesture.claimed = true;
    const action = gesture.direction === 'up' ? 'nexttrack' : 'previoustrack';
    teslaTelemetry?.suppressNextVolume(gesture.previousVolume, { ttlMs: 10_000 });
    restoreTeslaVolume(gesture.direction)
      .then(() => appendWebDiagnostic({
        receivedAt: new Date().toISOString(),
        report: {
          automatic: true,
          kind: 'tesla-volume-compensation',
          eventId: id,
          direction: gesture.direction,
          restoredVolume: gesture.previousVolume,
          status: 'sent',
        },
      }))
      .catch((error) => appendWebDiagnostic({
        receivedAt: new Date().toISOString(),
        report: {
          automatic: true,
          kind: 'tesla-volume-compensation',
          eventId: id,
          direction: gesture.direction,
          status: 'failed',
          error: error.message,
        },
      }).catch(() => {}));
    res.json({ claimed: true, action });
  });

  app.post('/web/api/diagnostics', requireWebSession, express.json({ limit: '32kb' }), async (req, res) => {
    const report = req.body;
    if (!report || typeof report !== 'object' || Array.isArray(report)) {
      res.status(400).json({ error: 'diagnostic report is required' });
      return;
    }
    const entry = {
      receivedAt: new Date().toISOString(),
      userAgent: String(req.get('user-agent') || '').slice(0, 512),
      report,
    };
    await appendWebDiagnostic(entry);
    res.status(204).end();
  });
}

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

app.post('/api/fluxa-grant/:grant', (req, res) => {
  const grant = String(req.params.grant || '');
  const expiresAt = fluxaGrants.get(grant);
  fluxaGrants.delete(grant);
  if (!expiresAt || expiresAt <= Date.now()) {
    res.status(401).json({ error: 'invalid or expired grant' });
    return;
  }
  res.set('Cache-Control', 'no-store').json({ authenticated: true });
});

app.post('/api/fluxa-launch-ticket', express.json({ limit: '2mb' }), async (req, res) => {
  const trackPath = typeof req.body?.trackPath === 'string' ? req.body.trackPath : '';
  const trackPaths = Array.isArray(req.body?.trackPaths)
    ? req.body.trackPaths.filter((value) => typeof value === 'string')
    : [];
  const returnUrl = typeof req.body?.returnUrl === 'string' ? req.body.returnUrl : '';
  let destination;
  try { destination = new URL(returnUrl); } catch (_error) {}
  const library = await libraryPromise;
  const libraryPaths = new Set(library.map((track) => track.path));
  const validSingle = trackPath && trackPath.length <= 4096 && libraryPaths.has(trackPath);
  const validQueue = trackPaths.length > 0 && trackPaths.length <= 2000
    && trackPaths.every((path) => path.length <= 4096 && libraryPaths.has(path));
  if ((!validSingle && !validQueue)
      || !destination || !['http:', 'https:'].includes(destination.protocol)) {
    res.status(400).json({ error: 'invalid Fluxa launch request' });
    return;
  }
  res.set('Cache-Control', 'no-store').json({
    ticket: issueFluxaLaunchTicket({
      trackPath,
      trackPaths: validQueue ? trackPaths : [],
      sourceName: typeof req.body?.sourceName === 'string' ? req.body.sourceName.slice(0, 500) : '',
      sourceKind: typeof req.body?.sourceKind === 'string' ? req.body.sourceKind.slice(0, 50) : 'Queue',
      shuffle: req.body?.shuffle === true,
      startPath: typeof req.body?.startPath === 'string' && libraryPaths.has(req.body.startPath)
        ? req.body.startPath : '',
      returnUrl: destination.href,
    }),
    expiresIn: 120,
  });
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
    let albumArtist = '';
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
      albumArtist = metadata.common.albumartist
        || (metadata.common.compilation ? 'Various Artists' : '');
      album = metadata.common.album || '';
      if (metadata.common.genre && metadata.common.genre.length) {
        genre = metadata.common.genre.join(', ');
      }
    } catch (err) {
      // Unreadable tags — fall back to the filename-derived title above.
    }
    tracks.push({ path: posixPath, title, artist, albumArtist, album, genre });
  }
  tracks.sort((a, b) => a.path.localeCompare(b.path));
  console.log(`Library index built: ${tracks.length} tracks`);
  return tracks;
}

libraryPromise = buildLibraryIndex();

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
  try {
    didWork = await runArtworkPass() || didWork;
  } catch (error) {
    console.error(`Auto-precompute (artwork) failed: ${error.message}`);
  }
  return didWork;
}

// Album art, unlike the visual/leveling passes above, is lightweight tag
// parsing plus throttled HTTP (MusicBrainz + Cover Art Archive), so it runs
// directly in this process instead of a spawned child.
async function runArtworkPass() {
  const nowMs = Date.now();
  if (runArtworkPass.nextRunAt && runArtworkPass.nextRunAt > nowMs) return false;
  runArtworkPass.nextRunAt = nowMs + ARTWORK_PASS_INTERVAL_MS;
  const library = await libraryPromise;
  await fsp.mkdir(ARTWORK_DIR, { recursive: true });
  const albums = new Map();
  for (const track of library) {
    if (!track.artist || !track.album) continue;
    const artist = track.albumArtist || track.artist;
    const key = artwork.albumCacheKey(artist, track.album);
    let entry = albums.get(key);
    if (!entry) {
      entry = { key, artist, album: track.album, tracks: [], legacyArtists: new Set() };
      albums.set(key, entry);
    }
    entry.tracks.push(track);
    entry.legacyArtists.add(track.artist);
  }

  for (const entry of albums.values()) {
    entry.wasNew = artwork.artworkAttemptStatus(
      ARTWORK_DIR, entry.artist, entry.album, nowMs).isNew;
  }

  // Reuse artwork cached under the old track-artist key when album-artist
  // grouping changes a compilation's canonical key.
  for (const entry of albums.values()) {
    const canonicalPath = path.join(ARTWORK_DIR, `${entry.key}.jpg`);
    if (fs.existsSync(canonicalPath)) continue;
    for (const legacyArtist of entry.legacyArtists) {
      const legacyPath = path.join(ARTWORK_DIR,
        `${artwork.albumCacheKey(legacyArtist, entry.album)}.jpg`);
      if (legacyPath === canonicalPath || !fs.existsSync(legacyPath)) continue;
      const temporary = `${canonicalPath}.tmp-${process.pid}-${crypto.randomBytes(4).toString('hex')}`;
      try {
        await fsp.copyFile(legacyPath, temporary);
        await fsp.rename(temporary, canonicalPath);
      } finally {
        await fsp.unlink(temporary).catch(() => {});
      }
      break;
    }
  }

  // Embedded images do not consume a web-service quota, so recover them in
  // substantially larger batches than remote MusicBrainz lookups.
  const embeddedPending = [...albums.values()]
    .filter((entry) => artwork.artworkAttemptStatus(
      ARTWORK_DIR, entry.artist, entry.album, nowMs).needsEmbeddedCheck)
    .slice(0, ARTWORK_EMBEDDED_PASS_LIMIT);
  for (const entry of embeddedPending) {
    const sourcePaths = entry.tracks
      .map((track) => resolveWithin(MUSIC_DIR, track.path, true))
      .filter(Boolean);
    const result = await artwork.ensureEmbeddedAlbumArt(
      ARTWORK_DIR, entry.artist, entry.album, sourcePaths, { nowMs });
    if (result) console.log(`Recovered embedded album art: ${entry.artist} - ${entry.album}`);
  }

  const newAlbums = [];
  const retries = [];
  for (const entry of albums.values()) {
    const status = artwork.artworkAttemptStatus(ARTWORK_DIR, entry.artist, entry.album, nowMs);
    if (!status.due || status.needsEmbeddedCheck) continue;
    (entry.wasNew ? newAlbums : retries).push({ entry, status });
  }
  // Newly added albums do not sit behind the historical retry backlog.
  const pending = [...newAlbums, ...retries].slice(0, ARTWORK_PASS_LIMIT);
  for (const { entry, status } of pending) {
    const variation = status.candidate === entry.album ? '' : ` as "${status.candidate}"`;
    console.log(`Fetching album art (${status.candidateIndex + 1}/${status.candidateCount}): ${entry.artist} - ${entry.album}${variation}`);
    const result = await artwork.ensureAlbumArt(ARTWORK_DIR, entry.artist, entry.album);
    if (!result) console.log(`  candidate did not produce art: ${status.candidate}`);
  }
  // Artwork has its own five-minute budget. Do not accelerate the global
  // precompute loop to 15 seconds merely because an artwork attempt ran.
  return false;
}
runArtworkPass.nextRunAt = 0;

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

// Keyed by track path (like /api/profile/*), but the actual cached file is
// shared across every track in the same album — this just looks up which
// (artist, album) the requested track belongs to and serves whatever's
// cached for that pair. Never fetches on-demand here; that only happens in
// the background precompute pass, so this stays a fast, simple read.
app.get('/api/artwork/*', async (req, res) => {
  const library = await libraryPromise;
  const track = library.find((t) => t.path === req.params[0]);
  if (!track || !track.artist || !track.album) {
    res.status(404).json({ error: 'not found' });
    return;
  }
  const filePath = cachedArtworkFileForTrack(track);
  if (!filePath) {
    res.status(404).json({ error: 'not found' });
    return;
  }
  try {
    const contents = await fsp.readFile(filePath);
    res.type('image/jpeg').send(contents);
  } catch (err) {
    res.status(404).json({ error: 'not found' });
  }
});

// The sidecar sits next to the audio file with the same base name, e.g.
// "Artist/Album/01 Song.flac" -> "Artist/Album/01 Song.lyrics.txt".
app.get('/api/lyrics/*', async (req, res) => {
  const trackPath = req.params[0];
  const sidecarRelPath = trackPath.replace(/\.[^./\\]+$/, '') + '.lyrics.txt';
  const filePath = resolveWithin(MUSIC_DIR, sidecarRelPath, true);
  if (!filePath) {
    res.status(400).json({ error: 'invalid path' });
    return;
  }
  try {
    const raw = await fsp.readFile(filePath, 'utf8');
    res.json(lyrics.parseLyricsSidecar(raw));
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
    && Boolean(header.flags & 1) === settings.logarithmic;
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

if (WEB_ENABLED && TESLA_TELEMETRY_REDIS_URL) {
  teslaTelemetry = new TeslaTelemetryRedisBridge({
    redisUrl: TESLA_TELEMETRY_REDIS_URL,
    channelPattern: TESLA_TELEMETRY_REDIS_PATTERN,
  });
  teslaTelemetry.on('event', broadcastTeslaTelemetryEvent);
  teslaTelemetry.on('status', (status) => {
    if (status.connected) console.log('Tesla Fleet Telemetry Redis bridge connected');
    else if (status.error) console.error(`Tesla Fleet Telemetry Redis bridge: ${status.error}`);
  });
  teslaTelemetry.start();
}
