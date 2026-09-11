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
    findReleaseIds: async (_artist, candidate) => {
      lookups.push(candidate);
      return [];
    },
  });
  assert.equal(first, null);
  assert.deepEqual(lookups, [album]);
  assert.equal(artwork.artworkAttemptStatus(directory, artist, album, now + 1).due, false);

  const secondNow = now + artwork.ARTWORK_CANDIDATE_RETRY_MS;
  const second = await artwork.ensureAlbumArt(directory, artist, album, {
    nowMs: secondNow,
    findReleaseIds: async (_artist, candidate) => {
      lookups.push(candidate);
      return ['release-id'];
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
    findReleaseIds: async () => [],
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

test('embedded JPEG artwork is cached without a remote lookup', async (t) => {
  const directory = await fsp.mkdtemp(path.join(os.tmpdir(), 'fredplayer-artwork-'));
  t.after(() => fsp.rm(directory, { recursive: true, force: true }));
  const artist = 'Embedded Artist';
  const album = 'Embedded Album';
  const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xd9]);

  const result = await artwork.ensureEmbeddedAlbumArt(
    directory, artist, album, ['/music/track.flac'], {
      nowMs: 3_000_000,
      findEmbeddedPicture: async (paths) => {
        assert.deepEqual(paths, ['/music/track.flac']);
        return { format: 'image/jpeg', data: jpeg };
      },
      convertToJpeg: async () => {
        assert.fail('JPEG artwork should not be converted');
      },
    });

  assert.ok(result.endsWith('.jpg'));
  assert.deepEqual(await fsp.readFile(result), jpeg);
  assert.equal(artwork.artworkAttemptStatus(directory, artist, album).resolved, true);
});

test('artwork tries distinct releases for one title over time', async (t) => {
  const directory = await fsp.mkdtemp(path.join(os.tmpdir(), 'fredplayer-artwork-'));
  t.after(() => fsp.rm(directory, { recursive: true, force: true }));
  const artist = 'Release Artist';
  const album = 'Release Album';
  const now = 4_000_000;
  let searches = 0;
  const fetched = [];

  await artwork.ensureAlbumArt(directory, artist, album, {
    nowMs: now,
    findReleaseIds: async () => {
      searches += 1;
      return ['release-without-art', 'release-with-art'];
    },
    fetchCoverArtWithRetries: async (releaseId) => {
      fetched.push(releaseId);
      const error = new Error('not found');
      error.statusCode = 404;
      throw error;
    },
  });

  const second = await artwork.ensureAlbumArt(directory, artist, album, {
    nowMs: now + artwork.ARTWORK_CANDIDATE_RETRY_MS,
    findReleaseIds: async () => {
      assert.fail('the persisted MusicBrainz result should be reused');
    },
    fetchCoverArtWithRetries: async (releaseId) => {
      fetched.push(releaseId);
      return Buffer.from('jpeg');
    },
  });

  assert.equal(searches, 1);
  assert.deepEqual(fetched, ['release-without-art', 'release-with-art']);
  assert.ok(second.endsWith('.jpg'));
  assert.equal(await fsp.readFile(second, 'utf8'), 'jpeg');
});
