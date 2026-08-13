// Fetches and caches album artwork from MusicBrainz + the Cover Art Archive
// (both free, no API key). Keyed by (artist, album), not per-track, since
// many tracks share one album — this avoids redundant downloads and keeps
// the whole thing to one lookup per unique album, not per file.
//
// MusicBrainz's usage policy asks for a descriptive User-Agent and roughly
// one request per second; throttleMusicBrainz() enforces that regardless of
// how many albums are queued, so this is safe to just run through a full
// missing-artwork backlog without extra coordination.

const https = require('https');
const crypto = require('crypto');
const fs = require('fs');
const fsp = fs.promises;
const path = require('path');

const USER_AGENT = 'FredPlayer/1.0 (+personal media server)';
const MB_MIN_INTERVAL_MS = 1100;
const MISS_VERSION = 2;
const MAX_ALBUM_TITLE_CANDIDATES = 5;
const ARTWORK_CANDIDATE_RETRY_MS = 60 * 60 * 1000;
const ARTWORK_EXHAUSTED_RETRY_MS = 30 * 24 * 60 * 60 * 1000;
let lastMusicBrainzRequestAt = 0;

function albumCacheKey(artist, album) {
  const normalized = `${(artist || '').trim().toLowerCase()}|${(album || '').trim().toLowerCase()}`;
  return crypto.createHash('sha256').update(normalized).digest('hex');
}

function httpsJson(url, headers) {
  return new Promise((resolve, reject) => {
    https.get(url, { headers }, (res) => {
      if (res.statusCode !== 200) {
        res.resume();
        reject(new Error(`HTTP ${res.statusCode} for ${url}`));
        return;
      }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        try {
          resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
        } catch (err) {
          reject(err);
        }
      });
    }).on('error', reject);
  });
}

function httpsBinary(url, headers, redirectsLeft = 5) {
  return new Promise((resolve, reject) => {
    const request = https.get(url, { headers }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirectsLeft > 0) {
        res.resume();
        httpsBinary(res.headers.location, headers, redirectsLeft - 1).then(resolve, reject);
        return;
      }
      if (res.statusCode !== 200) {
        res.resume();
        const err = new Error(`HTTP ${res.statusCode} for ${url}`);
        err.statusCode = res.statusCode;
        reject(err);
        return;
      }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve(Buffer.concat(chunks)));
    });
    request.on('error', reject);
  });
}

// A 404 from the Cover Art Archive is a definitive "this release has no
// cover art" — safe to cache as a permanent miss. Anything else (a 5xx from
// a flaky CDN node, a timeout, a reset) is transient and must not
// permanently blacklist an album over what's likely a temporary hiccup.
async function fetchCoverArtWithRetries(releaseId, attempts = 3) {
  let lastError;
  for (let attempt = 0; attempt < attempts; attempt++) {
    try {
      return await fetchCoverArt(releaseId);
    } catch (err) {
      if (err.statusCode === 404) throw err;
      lastError = err;
      if (attempt < attempts - 1) {
        await new Promise((resolve) => setTimeout(resolve, 1500 * (attempt + 1)));
      }
    }
  }
  throw lastError;
}

async function throttleMusicBrainz() {
  const wait = Math.max(0, lastMusicBrainzRequestAt + MB_MIN_INTERVAL_MS - Date.now());
  if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
  lastMusicBrainzRequestAt = Date.now();
}

function normalizedQualifier(value) {
  return value.trim().toLowerCase()
    .replace(/[–—]/g, '-')
    .replace(/&/g, ' and ')
    .replace(/\s+/g, ' ');
}

function isPackagingQualifier(value) {
  const parts = normalizedQualifier(value).split(/\s*(?:;|\band\b)\s*/).filter(Boolean);
  if (!parts.length) return false;
  return parts.every((part) => [
    /^(?:\d{4}\s+)?remaster(?:ed)?(?:\s+(?:edition|version))?(?:\s+\d{4})?$/,
    /^(?:(?:super|special|limited|digital)\s+)?deluxe(?:\s+(?:edition|version))?$/,
    /^expanded(?:\s+(?:edition|version))?$/,
    /^legacy(?:\s+(?:edition|version))?$/,
    /^(?:\d+(?:st|nd|rd|th)\s+)?anniversary(?:\s+(?:deluxe\s+)?edition)?$/,
    /^bonus tracks?(?:\s+(?:edition|version))?$/,
    /^(?:collector'?s?|collectors')\s+edition$/,
  ].some((pattern) => pattern.test(part)));
}

function isMixQualifier(value) {
  const parts = normalizedQualifier(value).split(/\s*(?:;|\band\b)\s*/).filter(Boolean);
  if (!parts.length) return false;
  let containsMixVariant = false;
  const allKnown = parts.every((part) => {
    if (isPackagingQualifier(part)) return true;
    const mixVariant = [
      /^(?:album|radio|single|mono|stereo)\s+(?:version|edit|mix)$/,
      /^(?:mono|stereo)$/,
      /^(?:\d{4}\s+)?remix(?:ed)?(?:\s+(?:edition|version))?(?:\s+\d{4})?$/,
      /^(?:original|alternate|extended)\s+(?:version|edit|mix)$/,
    ].some((pattern) => pattern.test(part));
    containsMixVariant ||= mixVariant;
    return mixVariant;
  });
  return allKnown && containsMixVariant;
}

function stripTrailingQualifier(value, includeMixVariants) {
  const singleOrEp = value.match(/^(.*?)\s*[-:–—]\s*(?:single|ep)\s*$/i);
  if (singleOrEp?.[1]?.trim()) return singleOrEp[1].trim();
  const bracketed = value.match(/^(.*?)\s*(?:\(([^()]*)\)|\[([^\[\]]*)\])\s*$/);
  if (!bracketed?.[1]?.trim()) return value;
  const qualifier = bracketed[2] ?? bracketed[3] ?? '';
  if (!isPackagingQualifier(qualifier)
      && !(includeMixVariants && isMixQualifier(qualifier))) return value;
  return bracketed[1].trim();
}

// Generate a conservative, bounded search plan. The tagged title always
// comes first. Only allowlisted packaging suffixes are removed in the
// high-confidence phase; mix variants are considered afterward. Meaningful
// suffixes such as Live, soundtrack/cast text, reprise, interlude, and
// instrumental are never stripped.
function albumNameVariants(album) {
  const trimmed = (album || '').trim();
  if (!trimmed) return [];
  const variants = [trimmed];
  const add = (candidate) => {
    const value = candidate.trim();
    if (value && !variants.includes(value)) variants.push(value);
  };

  for (const includeMixVariants of [false, true]) {
    let current = trimmed;
    while (variants.length < MAX_ALBUM_TITLE_CANDIDATES) {
      const next = stripTrailingQualifier(current, includeMixVariants);
      if (next === current || !next) break;
      current = next;
      add(current);
    }
  }

  return variants.slice(0, MAX_ALBUM_TITLE_CANDIDATES);
}

async function findReleaseId(artist, albumCandidate) {
  await throttleMusicBrainz();
  const query = encodeURIComponent(`release:"${albumCandidate}" AND artist:"${artist}"`);
  const url = `https://musicbrainz.org/ws/2/release/?query=${query}&fmt=json&limit=1`;
  const body = await httpsJson(url, { 'User-Agent': USER_AGENT, Accept: 'application/json' });
  const release = body.releases && body.releases[0];
  return release?.id || null;
}

async function fetchCoverArt(releaseId) {
  const url = `https://coverartarchive.org/release/${releaseId}/front-500`;
  return httpsBinary(url, { 'User-Agent': USER_AGENT });
}

function freshMissState(artist, album) {
  return {
    version: MISS_VERSION,
    artist,
    album,
    cycle: 1,
    attemptedCandidates: [],
    history: [],
    nextAttemptAt: 0,
    retryAfter: 0,
  };
}

function readMissState(missPath, artist, album) {
  try {
    const parsed = JSON.parse(fs.readFileSync(missPath, 'utf8'));
    if (parsed?.version !== MISS_VERSION || !Array.isArray(parsed.attemptedCandidates)) {
      return freshMissState(artist, album);
    }
    return {
      ...freshMissState(artist, album),
      ...parsed,
      artist,
      album,
      attemptedCandidates: parsed.attemptedCandidates.filter((value) => typeof value === 'string'),
      history: Array.isArray(parsed.history) ? parsed.history.slice(-20) : [],
    };
  } catch (_error) {
    // Empty v1 markers are migrated into a fresh progressive search plan.
    return freshMissState(artist, album);
  }
}

function artworkAttemptStatus(artworkDir, artist, album, nowMs = Date.now()) {
  const key = albumCacheKey(artist, album);
  const filePath = path.join(artworkDir, `${key}.jpg`);
  const missPath = path.join(artworkDir, `${key}.miss`);
  if (fs.existsSync(filePath)) return { resolved: true, due: false, filePath };
  const isNew = !fs.existsSync(missPath);
  const state = isNew ? freshMissState(artist, album) : readMissState(missPath, artist, album);
  const candidates = albumNameVariants(album);
  let candidateIndex = candidates.findIndex((candidate) => !state.attemptedCandidates.includes(candidate));
  let restartCycle = false;
  if (candidateIndex < 0 && Number(state.retryAfter) <= nowMs) {
    candidateIndex = 0;
    restartCycle = true;
  }
  const due = candidateIndex >= 0 && Number(state.nextAttemptAt || 0) <= nowMs
    && (candidateIndex < candidates.length);
  return {
    resolved: false,
    due,
    isNew,
    candidate: due ? candidates[candidateIndex] : '',
    candidateIndex,
    candidateCount: candidates.length,
    restartCycle,
    nextAttemptAt: Number(state.nextAttemptAt || state.retryAfter || 0),
  };
}

async function writeMissState(missPath, state) {
  const tempPath = `${missPath}.tmp-${process.pid}-${crypto.randomBytes(4).toString('hex')}`;
  await fsp.writeFile(tempPath, `${JSON.stringify(state, null, 2)}\n`, { flag: 'wx' });
  await fsp.rename(tempPath, missPath);
}

function advanceMissState(state, candidates, candidate, result, nowMs) {
  if (!state.attemptedCandidates.includes(candidate)) state.attemptedCandidates.push(candidate);
  state.history = [...state.history, {
    at: new Date(nowMs).toISOString(),
    candidate,
    result,
  }].slice(-20);
  const remaining = candidates.some((value) => !state.attemptedCandidates.includes(value));
  if (remaining) {
    state.nextAttemptAt = nowMs + ARTWORK_CANDIDATE_RETRY_MS;
    state.retryAfter = 0;
  } else {
    state.retryAfter = nowMs + ARTWORK_EXHAUSTED_RETRY_MS;
    state.nextAttemptAt = state.retryAfter;
  }
}

// Attempts exactly one title candidate per call and saves its progress in a
// JSON .miss file. Subsequent background passes advance through at most five
// conservative candidates; an exhausted plan gets a slow 30-day recheck.
async function ensureAlbumArt(artworkDir, artist, album, options = {}) {
  if (!artist || !album) return null;
  const nowMs = Number(options.nowMs ?? Date.now());
  const lookupRelease = options.findReleaseId || findReleaseId;
  const downloadCover = options.fetchCoverArtWithRetries || fetchCoverArtWithRetries;
  const key = albumCacheKey(artist, album);
  const filePath = path.join(artworkDir, `${key}.jpg`);
  const missPath = path.join(artworkDir, `${key}.miss`);

  try {
    await fsp.access(filePath);
    return filePath;
  } catch (_notCached) { /* fall through to fetch */ }
  await fsp.mkdir(artworkDir, { recursive: true });
  const status = artworkAttemptStatus(artworkDir, artist, album, nowMs);
  if (!status.due) return null;
  const candidates = albumNameVariants(album);
  const state = readMissState(missPath, artist, album);
  if (status.restartCycle) {
    state.cycle = Math.max(1, Number(state.cycle) || 1) + 1;
    state.attemptedCandidates = [];
    state.retryAfter = 0;
  }
  const candidate = status.candidate;
  let releaseId;
  try {
    releaseId = await lookupRelease(artist, candidate);
  } catch (err) {
    state.nextAttemptAt = nowMs + ARTWORK_CANDIDATE_RETRY_MS;
    state.lastTransientError = String(err.message || err).slice(0, 300);
    await writeMissState(missPath, state);
    return null;
  }
  if (!releaseId) {
    advanceMissState(state, candidates, candidate, 'no-release', nowMs);
    delete state.lastTransientError;
    await writeMissState(missPath, state);
    return null;
  }
  try {
    const image = await downloadCover(releaseId);
    const tempPath = `${filePath}.tmp-${process.pid}`;
    await fsp.writeFile(tempPath, image);
    await fsp.rename(tempPath, filePath);
    await fsp.unlink(missPath).catch(() => {});
    return filePath;
  } catch (err) {
    if (err.statusCode === 404) {
      advanceMissState(state, candidates, candidate, 'no-cover', nowMs);
      delete state.lastTransientError;
    } else {
      state.nextAttemptAt = nowMs + ARTWORK_CANDIDATE_RETRY_MS;
      state.lastTransientError = String(err.message || err).slice(0, 300);
    }
    await writeMissState(missPath, state);
    return null;
  }
}

module.exports = {
  ARTWORK_CANDIDATE_RETRY_MS,
  ARTWORK_EXHAUSTED_RETRY_MS,
  MAX_ALBUM_TITLE_CANDIDATES,
  albumCacheKey,
  albumNameVariants,
  artworkAttemptStatus,
  ensureAlbumArt,
};
