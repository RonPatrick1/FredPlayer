const crypto = require('crypto');
const { promisify } = require('util');

const scrypt = promisify(crypto.scrypt);
const PASSWORD_BYTES = 32;
const DEFAULT_SESSION_SECONDS = 30 * 24 * 60 * 60;
const REMEMBERED_DEVICE_SECONDS = 100 * 365 * 24 * 60 * 60;

async function hashPassword(password, salt = crypto.randomBytes(16).toString('base64url')) {
  if (typeof password !== 'string' || password.length < 12) {
    throw new Error('web password must contain at least 12 characters');
  }
  const derived = await scrypt(password, salt, PASSWORD_BYTES);
  return `scrypt$${salt}$${derived.toString('base64url')}`;
}

async function verifyPassword(password, storedHash) {
  if (typeof password !== 'string' || typeof storedHash !== 'string') return false;
  const [algorithm, salt, expectedText, extra] = storedHash.split('$');
  if (algorithm !== 'scrypt' || !salt || !expectedText || extra !== undefined) return false;
  try {
    const expected = Buffer.from(expectedText, 'base64url');
    const supplied = await scrypt(password, salt, expected.length);
    return supplied.length === expected.length && crypto.timingSafeEqual(supplied, expected);
  } catch (_error) {
    return false;
  }
}

function sign(encodedPayload, secret) {
  return crypto.createHmac('sha256', secret).update(encodedPayload).digest('base64url');
}

function createSession(username, secret, options = {}) {
  if (typeof username !== 'string' || !username || typeof secret !== 'string' || !secret) {
    throw new Error('username and session secret are required');
  }
  const nowSeconds = Math.floor((options.nowMs ?? Date.now()) / 1000);
  const payload = Buffer.from(JSON.stringify({
    v: Number(options.version ?? 1),
    sub: username,
    iat: nowSeconds,
    exp: nowSeconds + Number(options.ttlSeconds ?? DEFAULT_SESSION_SECONDS),
  })).toString('base64url');
  return `${payload}.${sign(payload, secret)}`;
}

function verifySession(value, secret, options = {}) {
  if (typeof value !== 'string' || typeof secret !== 'string' || !secret) return null;
  const separator = value.lastIndexOf('.');
  if (separator <= 0 || separator === value.length - 1) return null;
  const encodedPayload = value.slice(0, separator);
  const supplied = Buffer.from(value.slice(separator + 1));
  const expected = Buffer.from(sign(encodedPayload, secret));
  if (supplied.length !== expected.length || !crypto.timingSafeEqual(supplied, expected)) return null;
  try {
    const payload = JSON.parse(Buffer.from(encodedPayload, 'base64url').toString('utf8'));
    const nowSeconds = Math.floor((options.nowMs ?? Date.now()) / 1000);
    if (payload.v !== Number(options.version ?? 1)
        || typeof payload.sub !== 'string'
        || !Number.isSafeInteger(payload.iat)
        || !Number.isSafeInteger(payload.exp)
        || payload.iat > nowSeconds + 60
        || payload.exp < nowSeconds) {
      return null;
    }
    return payload;
  } catch (_error) {
    return null;
  }
}

function cookieValue(header, name) {
  if (typeof header !== 'string' || !header || typeof name !== 'string' || !name) return null;
  for (const part of header.split(';')) {
    const separator = part.indexOf('=');
    if (separator < 0 || part.slice(0, separator).trim() !== name) continue;
    try {
      return decodeURIComponent(part.slice(separator + 1).trim());
    } catch (_error) {
      return null;
    }
  }
  return null;
}

module.exports = {
  DEFAULT_SESSION_SECONDS,
  REMEMBERED_DEVICE_SECONDS,
  cookieValue,
  createSession,
  hashPassword,
  verifyPassword,
  verifySession,
};
