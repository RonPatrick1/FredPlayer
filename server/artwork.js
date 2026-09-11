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
const { spawn } = require('child_process');
const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const mm = require('music-metadata');

const USER_AGENT = 'FredPlayer/1.0 (+personal media server)';
const MB_MIN_INTERVAL_MS = 1100;
const MISS_VERSION = 3;
const MAX_ALBUM_TITLE_CANDIDATES = 5;
const MAX_RELEASES_PER_CANDIDATE = 5;
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

async function findReleaseIds(artist, albumCandidate) {
  await throttleMusicBrainz();
  const query = encodeURIComponent(`release:"${albumCandidate}" AND artist:"${artist}"`);
  const url = `https://musicbrainz.org/ws/2/release/?query=${query}&fmt=json&limit=${MAX_RELEASES_PER_CANDIDATE}`;
  const body = await httpsJson(url, { 'User-Agent': USER_AGENT, Accept: 'application/json' });
  return [...new Set((body.releases || [])
    .map((release) => release?.id)
    .filter((releaseId) => typeof releaseId === 'string' && releaseId))]
    .slice(0, MAX_RELEASES_PER_CANDIDATE);
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
    embeddedChecked: false,
    attemptedCandidates: [],
    releaseCandidates: {},
    attemptedReleaseIds: [],
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
      embeddedChecked: parsed.embeddedChecked === true,
      attemptedCandidates: parsed.attemptedCandidates.filter((value) => typeof value === 'string'),
      releaseCandidates: parsed.releaseCandidates && typeof parsed.releaseCandidates === 'object'
        ? Object.fromEntries(Object.entries(parsed.releaseCandidates)
          .filter(([, releaseIds]) => Array.isArray(releaseIds))
          .map(([candidate, releaseIds]) => [candidate, [...new Set(releaseIds
            .filter((releaseId) => typeof releaseId === 'string' && releaseId))]
            .slice(0, MAX_RELEASES_PER_CANDIDATE)]))
        : {},
      attemptedReleaseIds: Array.isArray(parsed.attemptedReleaseIds)
        ? [...new Set(parsed.attemptedReleaseIds
          .filter((releaseId) => typeof releaseId === 'string' && releaseId))]
        : [],
      history: Array.isArray(parsed.history) ? parsed.history.slice(-50) : [],
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
    embeddedChecked: state.embeddedChecked,
    needsEmbeddedCheck: !state.embeddedChecked,
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

function recordHistory(state, candidate, result, nowMs, releaseId = '') {
  state.history = [...state.history, {
    at: new Date(nowMs).toISOString(),
    candidate,
    result,
    ...(releaseId ? { releaseId } : {}),
  }].slice(-50);
}

function advanceMissState(state, candidates, candidate, result, nowMs) {
  if (!state.attemptedCandidates.includes(candidate)) state.attemptedCandidates.push(candidate);
  recordHistory(state, candidate, result, nowMs);
  const remaining = candidates.some((value) => !state.attemptedCandidates.includes(value));
  if (remaining) {
    state.nextAttemptAt = nowMs + ARTWORK_CANDIDATE_RETRY_MS;
    state.retryAfter = 0;
  } else {
    state.retryAfter = nowMs + ARTWORK_EXHAUSTED_RETRY_MS;
    state.nextAttemptAt = state.retryAfter;
  }
}

async function writeJpegAtomic(filePath, image) {
  const tempPath = `${filePath}.tmp-${process.pid}-${crypto.randomBytes(4).toString('hex')}`;
  try {
    await fsp.writeFile(tempPath, image, { flag: 'wx' });
    await fsp.rename(tempPath, filePath);
  } finally {
    await fsp.unlink(tempPath).catch(() => {});
  }
}

function convertToJpeg(image) {
  return new Promise((resolve, reject) => {
    const child = spawn('convert', [
      '-', '-auto-orient', '-strip', '-quality', '88', 'jpeg:-',
    ], { stdio: ['pipe', 'pipe', 'pipe'] });
    const stdout = [];
    const stderr = [];
    child.stdout.on('data', (chunk) => stdout.push(chunk));
    child.stderr.on('data', (chunk) => {
      if (stderr.reduce((total, value) => total + value.length, 0) < 4096) stderr.push(chunk);
    });
    child.on('error', reject);
    child.on('close', (code) => {
      if (code === 0 && stdout.length) {
        resolve(Buffer.concat(stdout));
      } else {
        reject(new Error(`embedded artwork conversion failed (${code}): ${Buffer.concat(stderr).toString('utf8').trim()}`));
      }
    });
    child.stdin.on('error', () => {});
    child.stdin.end(image);
  });
}

async function findEmbeddedPicture(sourcePaths) {
  for (const sourcePath of [...new Set(sourcePaths || [])]) {
    try {
      const metadata = await mm.parseFile(sourcePath, { duration: false, skipCovers: false });
      const pictures = metadata.common.picture || [];
      const picture = pictures.find((entry) => /front/i.test(entry.type || '')) || pictures[0];
      if (picture?.data?.length) return picture;
    } catch (_error) {
      // A corrupt file or unsupported tag must not prevent checking the other
      // tracks on the same album for a usable embedded cover.
    }
  }
  return null;
}

// Embedded artwork is authoritative, costs no network requests, and often
// exists even when MusicBrainz/Cover Art Archive has no usable match. This is
// deliberately separate from ensureAlbumArt() so the server can scan a large
// local batch without consuming the small remote-request budget.
async function ensureEmbeddedAlbumArt(artworkDir, artist, album, sourcePaths, options = {}) {
  if (!artist || !album) return null;
  const nowMs = Number(options.nowMs ?? Date.now());
  const readEmbedded = options.findEmbeddedPicture || findEmbeddedPicture;
  const encodeJpeg = options.convertToJpeg || convertToJpeg;
  const key = albumCacheKey(artist, album);
  const filePath = path.join(artworkDir, `${key}.jpg`);
  const missPath = path.join(artworkDir, `${key}.miss`);
  await fsp.mkdir(artworkDir, { recursive: true });
  try {
    await fsp.access(filePath);
    return filePath;
  } catch (_notCached) { /* continue */ }

  const state = readMissState(missPath, artist, album);
  if (state.embeddedChecked) return null;
  let sawPicture = false;
  let lastEmbeddedError = null;
  const uniqueSourcePaths = [...new Set(sourcePaths || [])];
  for (const sourcePath of uniqueSourcePaths) {
    try {
      const picture = await readEmbedded([sourcePath]);
      if (!picture?.data?.length) continue;
      sawPicture = true;
      const format = String(picture.format || '').toLowerCase();
      const isJpeg = format === 'image/jpeg' || format === 'image/jpg'
        || (picture.data[0] === 0xff && picture.data[1] === 0xd8);
      const jpeg = isJpeg ? Buffer.from(picture.data) : await encodeJpeg(Buffer.from(picture.data));
      await writeJpegAtomic(filePath, jpeg);
      await fsp.unlink(missPath).catch(() => {});
      return filePath;
    } catch (err) {
      // A broken image in one track must not hide a valid embedded image in
      // another track from the same album.
      lastEmbeddedError = err;
    }
  }
  if (lastEmbeddedError) {
    state.lastEmbeddedError = String(lastEmbeddedError.message || lastEmbeddedError).slice(0, 300);
  }
  state.embeddedChecked = true;
  recordHistory(state, '', sawPicture ? 'embedded-unusable' : 'embedded-not-found', nowMs);
  await writeMissState(missPath, state);
  return null;
}

// Attempts one release under one title candidate per call and saves its
// progress in a JSON .miss file. Subsequent background passes walk up to five
// ranked releases for each of at most five conservative title candidates;
// an exhausted plan gets a slow 30-day recheck.
async function ensureAlbumArt(artworkDir, artist, album, options = {}) {
  if (!artist || !album) return null;
  const nowMs = Number(options.nowMs ?? Date.now());
  const lookupReleases = options.findReleaseIds || findReleaseIds;
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
    state.releaseCandidates = {};
    state.retryAfter = 0;
  }
  const candidate = status.candidate;
  let releaseIds = state.releaseCandidates[candidate];
  if (!Array.isArray(releaseIds)) {
    try {
      releaseIds = [...new Set((await lookupReleases(artist, candidate)) || [])]
        .filter((releaseId) => typeof releaseId === 'string' && releaseId)
        .slice(0, MAX_RELEASES_PER_CANDIDATE);
      state.releaseCandidates[candidate] = releaseIds;
    } catch (err) {
      state.nextAttemptAt = nowMs + ARTWORK_CANDIDATE_RETRY_MS;
      state.lastTransientError = String(err.message || err).slice(0, 300);
      await writeMissState(missPath, state);
      return null;
    }
  }
  const releaseId = releaseIds.find((id) => !state.attemptedReleaseIds.includes(id));
  if (!releaseId) {
    advanceMissState(state, candidates, candidate,
      releaseIds.length ? 'no-new-release' : 'no-release', nowMs);
    delete state.lastTransientError;
    await writeMissState(missPath, state);
    return null;
  }
  try {
    const image = await downloadCover(releaseId);
    await writeJpegAtomic(filePath, image);
    await fsp.unlink(missPath).catch(() => {});
    return filePath;
  } catch (err) {
    if (err.statusCode === 404) {
      if (!state.attemptedReleaseIds.includes(releaseId)) state.attemptedReleaseIds.push(releaseId);
      recordHistory(state, candidate, 'no-cover', nowMs, releaseId);
      const hasAnotherRelease = releaseIds.some((id) => !state.attemptedReleaseIds.includes(id));
      if (hasAnotherRelease) {
        state.nextAttemptAt = nowMs + ARTWORK_CANDIDATE_RETRY_MS;
        state.retryAfter = 0;
      } else {
        advanceMissState(state, candidates, candidate, 'candidate-releases-exhausted', nowMs);
      }
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
  MAX_RELEASES_PER_CANDIDATE,
  albumCacheKey,
  albumNameVariants,
  artworkAttemptStatus,
  ensureEmbeddedAlbumArt,
  ensureAlbumArt,
};
