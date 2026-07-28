const assert = require('node:assert/strict');
const fsp = require('node:fs').promises;
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const {
  listSharedPlaylists,
  readSharedPlaylist,
  validPlaylistName,
  writeSharedPlaylist,
} = require('../shared-playlists');

test('shared playlists are atomically published, listed, read, and updated', async (context) => {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), 'fredplayer-playlists-'));
  context.after(() => fsp.rm(root, { recursive: true, force: true }));
  const validTracks = new Set(['Artist/Album/One.flac', 'Artist/Album/Two.flac']);

  const first = await writeSharedPlaylist(
    root,
    'Road Trip',
    ['Artist/Album/One.flac', 'Artist/Album/One.flac'],
    validTracks,
  );
  assert.equal(first.created, true);
  assert.deepEqual(first.playlist.tracks, ['Artist/Album/One.flac']);
  assert.deepEqual(await listSharedPlaylists(root), [{
    name: 'Road Trip',
    count: 1,
    shared: true,
    updatedAt: first.playlist.updatedAt,
  }]);

  const second = await writeSharedPlaylist(
    root,
    'road trip',
    ['Artist/Album/Two.flac'],
    validTracks,
  );
  assert.equal(second.created, false);
  assert.equal((await listSharedPlaylists(root)).length, 1);
  assert.deepEqual((await readSharedPlaylist(root, 'ROAD TRIP')).tracks, ['Artist/Album/Two.flac']);
});

test('legacy playlist files remain visible as shared server copies', async (context) => {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), 'fredplayer-playlists-legacy-'));
  context.after(() => fsp.rm(root, { recursive: true, force: true }));
  await fsp.writeFile(
    path.join(root, 'Favorites.json'),
    JSON.stringify({ name: 'Favorites', tracks: ['Artist/Track.flac'] }),
  );
  const listed = await listSharedPlaylists(root);
  assert.equal(listed.length, 1);
  assert.equal(listed[0].name, 'Favorites');
  assert.equal(listed[0].shared, true);
  assert.ok(listed[0].updatedAt);
});

test('invalid names and tracks are rejected', async (context) => {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), 'fredplayer-playlists-invalid-'));
  context.after(() => fsp.rm(root, { recursive: true, force: true }));
  assert.equal(validPlaylistName(' Good'), false);
  assert.equal(validPlaylistName('../Bad'), false);
  await assert.rejects(
    writeSharedPlaylist(root, 'Missing', ['not/in/library.flac'], new Set()),
    /path in this server library/,
  );
});
