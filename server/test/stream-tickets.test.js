const assert = require('node:assert/strict');
const test = require('node:test');

const {
  issueStreamTicket,
  validStreamTicket,
} = require('../stream-tickets');

const SECRET = 'test-only-secret';
const NOW = 1_800_000_000_000;

function requestFor(ticket, method = 'GET') {
  return {
    method,
    path: `/${ticket.path}`,
    query: {
      expires: String(ticket.expires),
      signature: ticket.signature,
    },
  };
}

test('stream tickets authorize only their exact encoded track', () => {
  const ticket = issueStreamTicket("Artist's Name/100% Good (Live).flac", SECRET, {
    nowMs: NOW,
    ttlSeconds: 60,
  });
  const request = requestFor(ticket);

  assert.equal(
    ticket.path,
    'stream/Artist%27s%20Name/100%25%20Good%20%28Live%29.flac',
  );
  assert.equal(validStreamTicket(request, SECRET, { nowMs: NOW }), true);
  assert.equal(validStreamTicket({ ...request, method: 'HEAD' }, SECRET, { nowMs: NOW }), true);
  assert.equal(validStreamTicket({ ...request, path: '/stream/Another.flac' }, SECRET, { nowMs: NOW }), false);
});

test('stream tickets reject tampering and expiration', () => {
  const ticket = issueStreamTicket('Artist/Track.flac', SECRET, {
    nowMs: NOW,
    ttlSeconds: 10,
  });
  const request = requestFor(ticket);

  assert.equal(validStreamTicket(request, 'different-secret', { nowMs: NOW }), false);
  assert.equal(validStreamTicket(request, SECRET, { nowMs: NOW + 11_000 }), false);
  assert.equal(validStreamTicket({ ...request, method: 'POST' }, SECRET, { nowMs: NOW }), false);
});

test('ticket paths keep percent signs and Unicode filenames unambiguous', () => {
  const ticket = issueStreamTicket('Beyoncé/100% Hits/Ça va.flac', SECRET, { nowMs: NOW });
  const request = requestFor(ticket);
  assert.equal(request.path, '/stream/Beyonc%C3%A9/100%25%20Hits/%C3%87a%20va.flac');
  assert.equal(validStreamTicket(request, SECRET, { nowMs: NOW }), true);
});
