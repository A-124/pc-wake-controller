import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  MAX_PAIRING_FAILURES,
  PAIRING_TTL_MS,
  PairingManager,
  normalizePairingCode,
} from '../src/pairing.js';

function fixedRandom(bytes) {
  return () => Buffer.from(bytes);
}

function sequenceRandom(sequences) {
  let index = 0;
  return () => {
    const bytes = sequences[Math.min(index, sequences.length - 1)];
    index += 1;
    return Buffer.from(bytes);
  };
}

function managerAt(clock, options = {}) {
  return new PairingManager({ nowMs: clock.nowMs, ...options });
}

function clockAt(start = 1_720_000_000_000) {
  let current = start;
  return {
    nowMs: () => current,
    advance(ms) {
      current += ms;
    },
  };
}

const KNOWN_BYTES = [0, 1, 2, 3, 4, 5, 6, 7]; // -> 0123-4567

test('pairing codes are 8 Crockford symbols displayed XXXX-XXXX', () => {
  const clock = clockAt();
  const pairing = managerAt(clock);
  const start = pairing.start({ label: 'Pixel' });
  assert.match(start.code, /^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/);
  assert.equal(start.expiresAt, clock.nowMs() + PAIRING_TTL_MS);

  const status = pairing.status();
  assert.equal(status.active, true);
  assert.equal(status.expiresAt, start.expiresAt);
  assert.equal(status.label, 'Pixel');
  assert.equal(Object.hasOwn(status, 'code'), false);
  assert.equal(JSON.stringify(status).includes(start.code.replace('-', '')), false);
});

test('normalization handles case, separators and the ambiguity map', () => {
  assert.equal(normalizePairingCode('0123-4567'), '01234567');
  assert.equal(normalizePairingCode('o123 4567'), '01234567');
  assert.equal(normalizePairingCode('i234-567v'), '1234567V');
  assert.equal(normalizePairingCode('l234 u567'), '1234V567');
  assert.equal(normalizePairingCode('short'), null);
  assert.equal(normalizePairingCode('!!!!-!!!!'), null);
  assert.equal(normalizePairingCode(12345678), null);
});

test('a correct code pairs once and is consumed immediately', () => {
  const clock = clockAt();
  const pairing = managerAt(clock, { randomBytesFn: fixedRandom(KNOWN_BYTES) });
  const { code } = pairing.start();
  assert.equal(code, '0123-4567');

  assert.deepEqual(pairing.attempt('0123-4567'), { ok: true });
  const second = pairing.attempt('0123-4567');
  assert.equal(second.ok, false);
  assert.equal(second.status, 409);
  assert.equal(second.error, 'pairing_closed');
});

test('codes normalize before comparison and failed attempts do not consume them', () => {
  const clock = clockAt();
  const pairing = managerAt(clock, { randomBytesFn: fixedRandom(KNOWN_BYTES) });
  pairing.start();

  const wrong = pairing.attempt('ZZZZ-ZZZZ');
  assert.equal(wrong.status, 401);
  assert.equal(wrong.error, 'invalid_code');
  assert.equal(pairing.status().failedAttempts, 1);

  assert.deepEqual(pairing.attempt('o123 4567'), { ok: true });
});

test('the window expires after 300 seconds (injected clock)', () => {
  const clock = clockAt();
  const pairing = managerAt(clock, { randomBytesFn: fixedRandom(KNOWN_BYTES) });
  pairing.start();

  clock.advance(PAIRING_TTL_MS - 1);
  assert.deepEqual(pairing.attempt('0123-4567'), { ok: true });

  const clock2 = clockAt();
  const pairing2 = managerAt(clock2, { randomBytesFn: fixedRandom(KNOWN_BYTES) });
  pairing2.start();
  clock2.advance(PAIRING_TTL_MS);
  const expired = pairing2.attempt('0123-4567');
  assert.equal(expired.status, 401);
  assert.equal(expired.error, 'invalid_code');
  assert.equal(pairing2.status().active, false);
  assert.equal(pairing2.attempt('0123-4567').status, 409);
});

test('five failed attempts invalidate the current code and lock the window', () => {
  const clock = clockAt();
  const pairing = managerAt(clock, { randomBytesFn: fixedRandom(KNOWN_BYTES) });
  pairing.start();

  for (let attempt = 1; attempt < MAX_PAIRING_FAILURES; attempt += 1) {
    const result = pairing.attempt('ZZZZ-ZZZZ');
    assert.equal(result.status, 401);
    assert.equal(result.error, 'invalid_code');
    assert.equal(pairing.status().failedAttempts, attempt);
  }

  const finalFailure = pairing.attempt('ZZZZ-ZZZZ');
  assert.equal(finalFailure.status, 429);
  assert.equal(finalFailure.error, 'too_many_attempts');
  assert.equal(pairing.status().active, false);

  const after = pairing.attempt('0123-4567');
  assert.equal(after.status, 409);
  assert.equal(after.error, 'pairing_closed');
});

test('starting a new window replaces the previous unresolved code', () => {
  const clock = clockAt();
  const pairing = managerAt(clock, {
    randomBytesFn: sequenceRandom([KNOWN_BYTES, [8, 9, 10, 11, 12, 13, 14, 15]]),
  });
  pairing.start();
  const replacement = pairing.start();
  assert.equal(replacement.replaced, true);
  assert.equal(replacement.code, '89AB-CDEF');

  const old = pairing.attempt('0123-4567');
  assert.equal(old.status, 401);
  assert.deepEqual(pairing.attempt('89AB-CDEF'), { ok: true });
  assert.equal(replacement.expiresAt, clock.nowMs() + PAIRING_TTL_MS);
});
