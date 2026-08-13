const assert = require('node:assert/strict');
const fs = require('node:fs');
const fsp = fs.promises;
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const artwork = require('../artwork.js');

test('album artwork candidates strip only bounded allowlisted packaging noise', () => {
  assert.deepEqual(
    artwork.albumNameVariants('Jesus Christ Superstar (50th Anniversary) [2021 Remaster]'),
    [
      'Jesus Christ Superstar (50th Anniversary) [2021 Remaster]',
      'Jesus Christ Superstar (50th Anniversary)',
      'Jesus Christ Superstar',
    ],
  );
  assert.deepEqual(
    artwork.albumNameVariants('Taking Woodstock (Original Motion Picture Soundtrack) [Deluxe Edition]'),
    [
      'Taking Woodstock (Original Motion Picture Soundtrack) [Deluxe Edition]',
      'Taking Woodstock (Original Motion Picture Soundtrack)',
    ],
  );
  assert.deepEqual(artwork.albumNameVariants('Album (Live)'), ['Album (Live)']);
  assert.deepEqual(artwork.albumNameVariants('Album (interlude)'), ['Album (interlude)']);
  assert.deepEqual(artwork.albumNameVariants('The Nat King Cole Story (Mono Version)'), [
    'The Nat King Cole Story (Mono Version)',
    'The Nat King Cole Story',
  ]);
  assert.ok(artwork.albumNameVariants('A (Deluxe) [2020 Remaster] - EP').length
    <= artwork.MAX_ALBUM_TITLE_CANDIDATES);
});

test('artwork search advances one title candidate at a time and can succeed later', async (t) => {
  const directory = await fsp.mkdtemp(path.join(os.tmpdir(), 'fredplayer-artwork-'));
  t.after(() => fsp.rm(directory, { recursive: true, force: true }));
  const now = 1_000_000;
  const artist = 'Example Artist';
  const album = 'Example Album (Deluxe Edition)';
  const lookups = [];

  const first = await artwork.ensureAlbumArt(directory, artist, album, {
    nowMs: now,
    findReleaseId: async (_artist, candidate) => {
      lookups.push(candidate);
      return null;
    },
  });
  assert.equal(first, null);
  assert.deepEqual(lookups, [album]);
  assert.equal(artwork.artworkAttemptStatus(directory, artist, album, now + 1).due, false);

  const secondNow = now + artwork.ARTWORK_CANDIDATE_RETRY_MS;
  const second = await artwork.ensureAlbumArt(directory, artist, album, {
    nowMs: secondNow,
    findReleaseId: async (_artist, candidate) => {
      lookups.push(candidate);
      return 'release-id';
    },
    fetchCoverArtWithRetries: async () => Buffer.from('jpeg'),
  });
  assert.ok(second.endsWith('.jpg'));
  assert.deepEqual(lookups, [album, 'Example Album']);
  assert.equal(fs.readFileSync(second, 'utf8'), 'jpeg');
  assert.equal(artwork.artworkAttemptStatus(directory, artist, album, secondNow).resolved, true);
});

test('legacy empty misses migrate and exhausted searches receive a slow retry', async (t) => {
  const directory = await fsp.mkdtemp(path.join(os.tmpdir(), 'fredplayer-artwork-'));
  t.after(() => fsp.rm(directory, { recursive: true, force: true }));
  const artist = 'Example Artist';
  const album = 'Meaningful Album (Live)';
  const missPath = path.join(directory, `${artwork.albumCacheKey(artist, album)}.miss`);
  await fsp.writeFile(missPath, '');
  const now = 2_000_000;

  assert.equal(artwork.artworkAttemptStatus(directory, artist, album, now).due, true);
  await artwork.ensureAlbumArt(directory, artist, album, {
    nowMs: now,
    findReleaseId: async () => null,
  });
  const state = JSON.parse(await fsp.readFile(missPath, 'utf8'));
  assert.deepEqual(state.attemptedCandidates, [album]);
  assert.equal(state.retryAfter, now + artwork.ARTWORK_EXHAUSTED_RETRY_MS);
  assert.equal(artwork.artworkAttemptStatus(directory, artist, album,
    state.retryAfter - 1).due, false);
  const retry = artwork.artworkAttemptStatus(directory, artist, album, state.retryAfter);
  assert.equal(retry.due, true);
  assert.equal(retry.restartCycle, true);
  assert.equal(retry.candidate, album);
});
