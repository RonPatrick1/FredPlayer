const assert = require('node:assert/strict');
const test = require('node:test');

const {
  REMEMBERED_DEVICE_SECONDS,
  cookieValue,
  createSession,
  hashPassword,
  verifyPassword,
  verifySession,
} = require('../web-auth');

const NOW = 1_800_000_000_000;
const SECRET = 'test-session-secret';

test('remembered devices use an effectively permanent signed lifetime', () => {
  assert.equal(REMEMBERED_DEVICE_SECONDS, 100 * 365 * 24 * 60 * 60);
  const session = createSession('ron', SECRET, {
    nowMs: NOW,
    ttlSeconds: REMEMBERED_DEVICE_SECONDS,
  });
  assert.equal(verifySession(session, SECRET, {
    nowMs: NOW + 99 * 365 * 24 * 60 * 60 * 1000,
  }).sub, 'ron');
});

test('web passwords use salted scrypt hashes and constant-time verification', async () => {
  const hash = await hashPassword('correct horse battery staple', 'fixed-test-salt');
  assert.match(hash, /^scrypt\$fixed-test-salt\$/);
  assert.equal(await verifyPassword('correct horse battery staple', hash), true);
  assert.equal(await verifyPassword('incorrect password', hash), false);
  assert.equal(await verifyPassword('correct horse battery staple', 'malformed'), false);
});

test('web sessions reject tampering, expiration, and revoked versions', () => {
  const session = createSession('ron', SECRET, {
    nowMs: NOW,
    ttlSeconds: 60,
    version: 4,
  });
  assert.equal(verifySession(session, SECRET, { nowMs: NOW, version: 4 }).sub, 'ron');
  assert.equal(verifySession(`${session}x`, SECRET, { nowMs: NOW, version: 4 }), null);
  assert.equal(verifySession(session, SECRET, { nowMs: NOW + 61_000, version: 4 }), null);
  assert.equal(verifySession(session, SECRET, { nowMs: NOW, version: 5 }), null);
});

test('cookie parsing returns only the exact named cookie', () => {
  const header = 'theme=dark; __Secure-fredplayer_session=abc.def%2Bghi; other=value';
  assert.equal(cookieValue(header, '__Secure-fredplayer_session'), 'abc.def+ghi');
  assert.equal(cookieValue(header, 'session'), null);
});
