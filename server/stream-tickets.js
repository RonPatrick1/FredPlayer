const crypto = require('crypto');

const DEFAULT_TTL_SECONDS = 6 * 60 * 60;

function encodeServerPath(serverPath) {
  return serverPath
    .split('/')
    .map((segment) => encodeURIComponent(segment).replace(/[!'()*]/g, (character) => (
      `%${character.charCodeAt(0).toString(16).toUpperCase()}`
    )))
    .join('/');
}

function signatureFor(requestPath, expires, secret) {
  return crypto
    .createHmac('sha256', secret)
    .update(`${expires}\n${requestPath}`)
    .digest('base64url');
}

function issueStreamTicket(serverPath, secret, options = {}) {
  if (typeof serverPath !== 'string' || !serverPath || serverPath.startsWith('/')) {
    throw new Error('a relative server path is required');
  }
  if (typeof secret !== 'string' || !secret) {
    throw new Error('a ticket secret is required');
  }
  const nowSeconds = Math.floor((options.nowMs ?? Date.now()) / 1000);
  const ttlSeconds = options.ttlSeconds ?? DEFAULT_TTL_SECONDS;
  const expires = nowSeconds + ttlSeconds;
  const requestPath = `/stream/${encodeServerPath(serverPath)}`;
  const signature = signatureFor(requestPath, expires, secret);
  return {
    expires,
    path: requestPath.slice(1),
    signature,
  };
}

function validStreamTicket(req, secret, options = {}) {
  if (!req || !['GET', 'HEAD'].includes(req.method)) return false;
  const requestPath = req.path;
  if (typeof requestPath !== 'string' || !requestPath.startsWith('/stream/')) return false;
  const expiresText = req.query?.expires;
  const suppliedText = req.query?.signature;
  if (typeof expiresText !== 'string' || typeof suppliedText !== 'string') return false;

  const expires = Number(expiresText);
  const nowSeconds = Math.floor((options.nowMs ?? Date.now()) / 1000);
  if (!Number.isSafeInteger(expires) || expires < nowSeconds) return false;

  const supplied = Buffer.from(suppliedText);
  const expected = Buffer.from(signatureFor(requestPath, expires, secret));
  return supplied.length === expected.length && crypto.timingSafeEqual(supplied, expected);
}

module.exports = {
  DEFAULT_TTL_SECONDS,
  encodeServerPath,
  issueStreamTicket,
  validStreamTicket,
};
