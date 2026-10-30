import crypto from 'node:crypto';
import { safeEqualStrings, sha256Hex } from './util.js';

export const PAIRING_TTL_MS = 300_000;
export const MAX_PAIRING_FAILURES = 5;
export const PAIRING_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/**
 * Normalize a user-supplied pairing code per PROTOCOL.md section 9:
 * uppercase, remove hyphens/spaces, then apply the ambiguity map
 * I,L -> 1, O -> 0, U -> V. Returns null for structurally invalid codes.
 */
export function normalizePairingCode(value) {
  if (typeof value !== 'string') return null;
  let normalized = value.trim().toUpperCase().replace(/[\s-]+/g, '');
  normalized = normalized.replace(/[IL]/g, '1').replace(/O/g, '0').replace(/U/g, 'V');
  if (normalized.length !== 8) return null;
  for (const character of normalized) {
    if (!PAIRING_ALPHABET.includes(character)) return null;
  }
  return normalized;
}

export function formatPairingCode(raw) {
  return `${raw.slice(0, 4)}-${raw.slice(4)}`;
}

function generateCode(randomBytesFn = crypto.randomBytes) {
  const bytes = randomBytesFn(8);
  let raw = '';
  for (let i = 0; i < 8; i += 1) {
    // The alphabet has exactly 32 symbols, so the low 5 bits are unbiased.
    raw += PAIRING_ALPHABET[bytes[i] & 31];
  }
  return formatPairingCode(raw);
}

/**
 * In-memory, single-use pairing windows. Only a hash of the active code is
 * retained; the plaintext code is returned once from start().
 */
export class PairingManager {
  constructor({
    nowMs = Date.now,
    ttlMs = PAIRING_TTL_MS,
    maxFailures = MAX_PAIRING_FAILURES,
    randomBytesFn = crypto.randomBytes,
  } = {}) {
    this.nowMs = nowMs;
    this.ttlMs = ttlMs;
    this.maxFailures = maxFailures;
    this.randomBytesFn = randomBytesFn;
    this.active = null;
  }

  start({ label = '' } = {}) {
    const code = generateCode(this.randomBytesFn);
    const normalized = normalizePairingCode(code);
    const now = this.nowMs();
    const replaced = this.active !== null;
    this.active = {
      codeHash: sha256Hex(normalized),
      startedAt: now,
      expiresAt: now + this.ttlMs,
      failedAttempts: 0,
      label: typeof label === 'string' ? label : '',
    };
    return { code, expiresAt: this.active.expiresAt, replaced };
  }

  /**
   * Attempt to consume the current window. Success consumes the code; the
   * caller must then issue credentials. Errors use the protocol HTTP status.
   */
  attempt(rawCode) {
    const now = this.nowMs();
    if (!this.active) {
      return { ok: false, status: 409, error: 'pairing_closed' };
    }
    if (now >= this.active.expiresAt) {
      this.active = null;
      return { ok: false, status: 401, error: 'invalid_code' };
    }

    const normalized = normalizePairingCode(rawCode);
    const matches = normalized !== null && safeEqualStrings(sha256Hex(normalized), this.active.codeHash);
    if (!matches) {
      this.active.failedAttempts += 1;
      if (this.active.failedAttempts >= this.maxFailures) {
        this.active = null;
        return { ok: false, status: 429, error: 'too_many_attempts' };
      }
      return { ok: false, status: 401, error: 'invalid_code' };
    }

    // Consume before returning so the code can never be used twice.
    this.active = null;
    return { ok: true };
  }

  status() {
    if (!this.active) {
      return { active: false, expiresAt: null, failedAttempts: 0, label: null };
    }
    return {
      active: true,
      expiresAt: this.active.expiresAt,
      failedAttempts: this.active.failedAttempts,
      label: this.active.label,
    };
  }

  clear() {
    this.active = null;
  }
}
