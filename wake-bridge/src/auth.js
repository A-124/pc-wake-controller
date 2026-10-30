import crypto from 'node:crypto';
import { safeEqualBuffers, sha256Hex } from './util.js';

export const MAX_CLOCK_SKEW_SECONDS = 60;
export const NONCE_TTL_MS = 180_000;

const DEVICE_ID_RE = /^[0-9a-f]{32}$/;
const NONCE_RE = /^[A-Za-z0-9_-]{16,64}$/;
const SIGNATURE_RE = /^[A-Za-z0-9_-]{43}$/;
const TIMESTAMP_RE = /^\d{1,12}$/;

export function bodySha256(body) {
  const bytes = Buffer.isBuffer(body)
    ? body
    : body === undefined || body === null
      ? Buffer.alloc(0)
      : Buffer.from(String(body), 'utf8');
  return sha256Hex(bytes);
}

/**
 * The exact canonical byte string defined by PROTOCOL.md section 4.1.
 * `timestamp` may be a number or the exact decimal string from the header.
 */
export function canonicalString({ method, path, timestamp, nonce, body }) {
  return [
    String(method),
    String(path),
    String(timestamp),
    String(nonce),
    bodySha256(body),
  ].join('\n');
}

export function computeSignature(token, canonical) {
  return crypto.createHmac('sha256', token).update(canonical, 'utf8').digest('base64url');
}

export function signRequest({ token, method, path, timestamp, nonce, body }) {
  const canonical = canonicalString({ method, path, timestamp, nonce, body });
  return { canonical, signature: computeSignature(token, canonical) };
}

export function createNonce() {
  return crypto.randomBytes(16).toString('base64url');
}

function headerValue(headers, name) {
  if (!headers || typeof headers !== 'object') return undefined;
  const lower = name.toLowerCase();
  let value = headers[lower];
  if (value === undefined) {
    for (const [key, candidate] of Object.entries(headers)) {
      if (key.toLowerCase() === lower) {
        value = candidate;
        break;
      }
    }
  }
  if (Array.isArray(value)) return value.length === 1 ? value[0] : undefined;
  return value;
}

/**
 * Verify an authenticated request. This performs only cryptographic and
 * replay checks; caller is responsible for device lookup and revocation.
 * Returns {ok:true, deviceId, timestamp, nonce} or {ok:false, reason}.
 */
export function verifyAuthenticatedRequest({
  token,
  headers,
  method,
  path,
  body,
  nowSeconds,
  nowMs,
  nonceCache,
}) {
  if (typeof token !== 'string' || token.length < 16) return { ok: false, reason: 'no_token' };

  const deviceId = headerValue(headers, 'x-pcwake-device');
  const timestampRaw = headerValue(headers, 'x-pcwake-timestamp');
  const nonce = headerValue(headers, 'x-pcwake-nonce');
  const signature = headerValue(headers, 'x-pcwake-signature');

  if (
    typeof deviceId !== 'string' ||
    typeof timestampRaw !== 'string' ||
    typeof nonce !== 'string' ||
    typeof signature !== 'string'
  ) {
    return { ok: false, reason: 'missing_headers' };
  }
  if (!DEVICE_ID_RE.test(deviceId)) return { ok: false, reason: 'bad_device_id' };
  if (!TIMESTAMP_RE.test(timestampRaw)) return { ok: false, reason: 'bad_timestamp' };
  if (!NONCE_RE.test(nonce)) return { ok: false, reason: 'bad_nonce' };
  if (!SIGNATURE_RE.test(signature)) return { ok: false, reason: 'bad_signature' };

  const timestamp = Number(timestampRaw);
  if (!Number.isSafeInteger(timestamp)) return { ok: false, reason: 'bad_timestamp' };
  if (Math.abs(nowSeconds - timestamp) > MAX_CLOCK_SKEW_SECONDS) {
    return { ok: false, reason: 'stale_timestamp' };
  }

  const canonical = canonicalString({ method, path, timestamp: timestampRaw, nonce, body });
  const expected = Buffer.from(computeSignature(token, canonical), 'base64url');
  let supplied;
  try {
    supplied = Buffer.from(signature, 'base64url');
  } catch {
    return { ok: false, reason: 'bad_signature' };
  }
  if (!safeEqualBuffers(expected, supplied)) return { ok: false, reason: 'bad_signature' };

  // Replay protection is applied only after the signature has been verified.
  if (nonceCache) {
    const at = Number.isFinite(nowMs) ? nowMs : nowSeconds * 1000;
    if (nonceCache.isReplayOrStore(deviceId, nonce, at)) {
      return { ok: false, reason: 'replayed_nonce' };
    }
  }

  return { ok: true, deviceId, timestamp, nonce };
}

export class NonceCache {
  constructor({ ttlMs = NONCE_TTL_MS, nowMs = Date.now } = {}) {
    this.ttlMs = ttlMs;
    this.nowMs = nowMs;
    this.byDevice = new Map();
    this.lastSweep = 0;
  }

  /** Returns true when the nonce was already used (a replay). */
  isReplayOrStore(deviceId, nonce, at = this.nowMs()) {
    let entries = this.byDevice.get(deviceId);
    if (!entries) {
      entries = new Map();
      this.byDevice.set(deviceId, entries);
    }
    for (const [candidate, expiresAt] of entries) {
      if (expiresAt <= at) entries.delete(candidate);
    }
    if (entries.has(nonce)) return true;
    entries.set(nonce, at + this.ttlMs);

    if (at - this.lastSweep > this.ttlMs) {
      this.sweep(at);
    }
    return false;
  }

  sweep(at = this.nowMs()) {
    for (const [deviceId, entries] of this.byDevice) {
      for (const [nonce, expiresAt] of entries) {
        if (expiresAt <= at) entries.delete(nonce);
      }
      if (entries.size === 0) this.byDevice.delete(deviceId);
    }
    this.lastSweep = at;
  }

  get size() {
    let total = 0;
    for (const entries of this.byDevice.values()) total += entries.size;
    return total;
  }

  clear() {
    this.byDevice.clear();
  }
}
