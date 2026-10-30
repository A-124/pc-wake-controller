import crypto from 'node:crypto';

/**
 * Parse a MAC address in common textual forms and return it normalized as
 * uppercase colon-separated hex. Returns null when the value is invalid.
 */
export function parseMac(value) {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  let hex;
  if (/^[0-9a-fA-F]{12}$/.test(trimmed)) {
    hex = trimmed;
  } else if (/^[0-9a-fA-F]{2}([:-][0-9a-fA-F]{2}){5}$/.test(trimmed)) {
    hex = trimmed.replace(/[:-]/g, '');
  } else {
    return null;
  }
  const upper = hex.toUpperCase();
  return upper.match(/.{2}/g).join(':');
}

export function normalizeMac(value) {
  const parsed = parseMac(value);
  if (!parsed) throw new Error(`invalid MAC address: ${JSON.stringify(value)}`);
  return parsed;
}

export function normalizeIpAddress(address) {
  if (typeof address !== 'string') return '';
  let value = address.trim();
  const zone = value.indexOf('%');
  if (zone !== -1) value = value.slice(0, zone);
  if (value.startsWith('::ffff:') && /^::ffff:\d+\.\d+\.\d+\.\d+$/i.test(value)) {
    value = value.slice(7);
  }
  return value;
}

export function isLoopbackAddress(address) {
  const value = normalizeIpAddress(address);
  if (!value) return false;
  return value === '::1' || /^127(?:\.\d{1,3}){3}$/.test(value);
}

export function sha256Hex(data) {
  return crypto.createHash('sha256').update(data).digest('hex');
}

export function sha256Buffer(data) {
  return crypto.createHash('sha256').update(data).digest();
}

/** Constant-time comparison for equal-length buffers. */
export function safeEqualBuffers(a, b) {
  if (!Buffer.isBuffer(a) || !Buffer.isBuffer(b) || a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

/**
 * Constant-time string comparison that does not leak length information.
 * Both inputs are hashed to a fixed-size digest first.
 */
export function safeEqualStrings(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  return crypto.timingSafeEqual(sha256Buffer(a), sha256Buffer(b));
}

export function base64url(buffer) {
  return Buffer.from(buffer).toString('base64url');
}

export function randomToken(bytes = 32) {
  return crypto.randomBytes(bytes).toString('base64url');
}

export function clampInteger(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

export function isPlainObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function hasControlCharacters(value) {
  return /[\u0000-\u001f\u007f]/.test(value);
}
