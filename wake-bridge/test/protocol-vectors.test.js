import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  NonceCache,
  bodySha256,
  canonicalString,
  computeSignature,
  signRequest,
} from '../src/auth.js';

const TOKEN = 'test-token-0123456789';
const NONCE = 'AAECAwQFBgcICQoLDA0ODw';
const TIMESTAMP = '1720000000';

test('PROTOCOL.md section 8: request A (POST with body) is reproduced exactly', () => {
  const body = Buffer.from('{"graceSeconds":15}', 'utf8');
  assert.equal(
    bodySha256(body),
    '41d72c15ac4723b83324e5ff7a275bc1a6bbe34fa3de3a7c27d249c1b97eacb5'
  );

  const canonical = canonicalString({
    method: 'POST',
    path: '/api/v1/power/shutdown',
    timestamp: TIMESTAMP,
    nonce: NONCE,
    body,
  });
  assert.equal(
    canonical,
    'POST\n/api/v1/power/shutdown\n1720000000\nAAECAwQFBgcICQoLDA0ODw\n41d72c15ac4723b83324e5ff7a275bc1a6bbe34fa3de3a7c27d249c1b97eacb5'
  );
  assert.equal(computeSignature(TOKEN, canonical), 'qEPDkh0eZIiTzT1ZGTXR4z398xh3XNG3iL6buuhenkQ');

  const signed = signRequest({
    token: TOKEN,
    method: 'POST',
    path: '/api/v1/power/shutdown',
    timestamp: TIMESTAMP,
    nonce: NONCE,
    body,
  });
  assert.equal(signed.canonical, canonical);
  assert.equal(signed.signature, 'qEPDkh0eZIiTzT1ZGTXR4z398xh3XNG3iL6buuhenkQ');
});

test('PROTOCOL.md section 8: request B (GET, empty body) is reproduced exactly', () => {
  const body = Buffer.alloc(0);
  assert.equal(
    bodySha256(body),
    'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
  );

  const canonical = canonicalString({
    method: 'GET',
    path: '/api/v1/status',
    timestamp: TIMESTAMP,
    nonce: NONCE,
    body,
  });
  assert.equal(
    canonical,
    'GET\n/api/v1/status\n1720000000\nAAECAwQFBgcICQoLDA0ODw\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
  );
  assert.equal(computeSignature(TOKEN, canonical), '6S_CySELEsBTeWGBaBgzKQqWjAkz3CMQTwxPMNKyVUI');
});

test('signature changes when the canonical request is tampered with', () => {
  const original = canonicalString({
    method: 'GET',
    path: '/api/v1/status',
    timestamp: TIMESTAMP,
    nonce: NONCE,
    body: Buffer.alloc(0),
  });
  const tampered = canonicalString({
    method: 'GET',
    path: '/api/v1/status?evil=1',
    timestamp: TIMESTAMP,
    nonce: NONCE,
    body: Buffer.alloc(0),
  });
  assert.notEqual(computeSignature(TOKEN, original), computeSignature(TOKEN, tampered));
  assert.match(computeSignature(TOKEN, original), /^[A-Za-z0-9_-]{43}$/);
});

test('nonce replay cache is per-device and lasts 180 seconds', () => {
  let now = 1_720_000_000_000;
  const cache = new NonceCache({ nowMs: () => now });
  const nonce = 'AAECAwQFBgcICQoLDA0ODw';

  assert.equal(cache.isReplayOrStore('a'.repeat(32), nonce, now), false);
  assert.equal(cache.isReplayOrStore('a'.repeat(32), nonce, now), true);
  assert.equal(cache.isReplayOrStore('b'.repeat(32), nonce, now), false);

  now += 179_999;
  assert.equal(cache.isReplayOrStore('a'.repeat(32), nonce, now), true);
  now += 1;
  assert.equal(cache.isReplayOrStore('a'.repeat(32), nonce, now), false);
});
