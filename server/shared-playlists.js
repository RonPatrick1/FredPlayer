const crypto = require('crypto');
const fsp = require('fs').promises;
const path = require('path');

const PLAYLIST_NAME_RE = /^[^/\\\u0000-\u001f]{1,100}$/;
const MAX_TRACKS = 25_000;

function validPlaylistName(name) {
  return typeof name === 'string'
    && name === name.trim()
    && PLAYLIST_NAME_RE.test(name);
}

async function findPlaylistFile(playlistsDir, name) {
  const files = await fsp.readdir(playlistsDir);
  const exact = `${name}.json`;
  if (files.includes(exact)) return exact;

  const wanted = name.toLocaleLowerCase();
  for (const file of files) {
    if (!file.endsWith('.json')) continue;
    try {
      const value = JSON.parse(await fsp.readFile(path.join(playlistsDir, file), 'utf8'));
      if (typeof value.name === 'string' && value.name.toLocaleLowerCase() === wanted) {
        return file;
      }
    } catch (_error) {
      // Corrupt files are ignored and never block the remaining shared playlists.
    }
  }
  return null;
}

function normalizedPlaylist(value, fallbackUpdatedAt = null) {
  if (!value || !validPlaylistName(value.name) || !Array.isArray(value.tracks)) return null;
  const tracks = value.tracks.filter((track) => typeof track === 'string');
  if (tracks.length !== value.tracks.length) return null;
  return {
    version: 1,
    shared: true,
    name: value.name,
    tracks,
    updatedAt: typeof value.updatedAt === 'string' ? value.updatedAt : fallbackUpdatedAt,
  };
}

async function listSharedPlaylists(playlistsDir) {
  await fsp.mkdir(playlistsDir, { recursive: true });
  const files = await fsp.readdir(playlistsDir);
  const playlists = [];
  for (const file of files) {
    if (!file.endsWith('.json')) continue;
    try {
      const filePath = path.join(playlistsDir, file);
      const [contents, stats] = await Promise.all([
        fsp.readFile(filePath, 'utf8'),
        fsp.stat(filePath),
      ]);
      const playlist = normalizedPlaylist(JSON.parse(contents), stats.mtime.toISOString());
      if (!playlist) continue;
      playlists.push({
        name: playlist.name,
        count: playlist.tracks.length,
        shared: true,
        updatedAt: playlist.updatedAt,
      });
    } catch (_error) {
      // Skip unreadable/corrupt playlist files.
    }
  }
  playlists.sort((a, b) => a.name.localeCompare(b.name));
  return playlists;
}

async function readSharedPlaylist(playlistsDir, name) {
  if (!validPlaylistName(name)) return null;
  await fsp.mkdir(playlistsDir, { recursive: true });
  const file = await findPlaylistFile(playlistsDir, name);
  if (!file) return null;
  try {
    const filePath = path.join(playlistsDir, file);
    const [contents, stats] = await Promise.all([
      fsp.readFile(filePath, 'utf8'),
      fsp.stat(filePath),
    ]);
    return normalizedPlaylist(JSON.parse(contents), stats.mtime.toISOString());
  } catch (_error) {
    return null;
  }
}

async function writeSharedPlaylist(playlistsDir, name, tracks, validTrackPaths) {
  if (!validPlaylistName(name)) {
    const error = new Error('name must be 1-100 trimmed characters with no slashes');
    error.code = 'INVALID_NAME';
    throw error;
  }
  if (!Array.isArray(tracks) || tracks.length === 0 || tracks.length > MAX_TRACKS) {
    const error = new Error(`tracks must contain 1-${MAX_TRACKS} server paths`);
    error.code = 'INVALID_TRACKS';
    throw error;
  }

  const uniqueTracks = [];
  const seen = new Set();
  for (const track of tracks) {
    if (typeof track !== 'string' || !track || track.length > 2_048 || !validTrackPaths.has(track)) {
      const error = new Error('every track must be a path in this server library');
      error.code = 'INVALID_TRACKS';
      throw error;
    }
    if (!seen.has(track)) {
      seen.add(track);
      uniqueTracks.push(track);
    }
  }

  await fsp.mkdir(playlistsDir, { recursive: true });
  const existingFile = await findPlaylistFile(playlistsDir, name);
  const targetFile = existingFile || `${name}.json`;
  const playlist = {
    version: 1,
    shared: true,
    name,
    tracks: uniqueTracks,
    updatedAt: new Date().toISOString(),
  };
  const tempFile = `.${crypto.randomUUID()}.playlist.tmp`;
  const tempPath = path.join(playlistsDir, tempFile);
  try {
    await fsp.writeFile(tempPath, `${JSON.stringify(playlist)}\n`, { flag: 'wx' });
    await fsp.rename(tempPath, path.join(playlistsDir, targetFile));
  } finally {
    await fsp.rm(tempPath, { force: true });
  }
  return { playlist, created: !existingFile };
}

module.exports = {
  MAX_TRACKS,
  listSharedPlaylists,
  readSharedPlaylist,
  validPlaylistName,
  writeSharedPlaylist,
};
