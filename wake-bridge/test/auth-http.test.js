import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { after, before, describe, test } from 'node:test';

import { createNonce, signRequest } from '../src/auth.js';
import { sha256Hex } from '../src/util.js';
import {
  createTestBridge,
  readDevicesFile,
} from './support/bridge.js';

function signedHeaders({ token, deviceId, method, path, body, timestamp, nonce }) {
  const ts = timestamp;
  const headerNonce = nonce ?? createNonce();
  const { signature } = signRequest({
    token,
    method,
    path,
    timestamp: ts,
    nonce: headerNonce,
    body,
  });
  return {
    'X-PCWake-Device': deviceId,
    'X-PCWake-Timestamp': String(ts),
    'X-PCWake-Nonce': headerNonce,
    'X-PCWake-Signature': signature,
  };
}

describe('HTTPS API over a real TLS listener', () => {
  let ctx;
  let paired;
  let nowSeconds;

  before(async () => {
    ctx = await createTestBridge({
      rateLimits: { authFailuresPerMinutePerIp: 100 },
    });
    paired = await ctx.pair();
    nowSeconds = Math.floor(ctx.clock.nowMs() / 1000);
  });

  after(async () => {
    await ctx.close();
  });

  test('GET /health is exact and leaks no host, pin or pairing state', async () => {
    const response = await ctx.request({ requestPath: '/health' });
    assert.equal(response.status, 200);
    assert.match(response.headers['content-type'], /^application\/json; charset=utf-8$/);
    assert.deepEqual(response.json, {
      ok: true,
      service: 'pcwake-bridge',
      version: '1.0.0',
      kind: 'bridge',
    });
    assert.deepEqual(Object.keys(response.json).sort(), ['kind', 'ok', 'service', 'version']);
    assert.equal(response.text.includes('fingerprint'), false);
    assert.equal(response.text.includes(ctx.fingerprint), false);
    assert.equal(response.text.includes('Test Wake Bridge'), false);
    assert.equal(response.text.includes('pairing'), false);
  });

  test('pairing returns a 43-char token, 32-hex device id and public server targets', async () => {
    assert.equal(paired.ok, true);
    assert.match(paired.deviceId, /^[0-9a-f]{32}$/);
    assert.match(paired.token, /^[A-Za-z0-9_-]{43}$/);
    assert.deepEqual(paired.server, {
      kind: 'bridge',
      name: 'Test Wake Bridge',
      version: '1.0.0',
      targets: [{ id: 'test-pc', name: 'Test PC', mac: 'AA:BB:CC:DD:EE:FF' }],
    });
    assert.equal(Object.hasOwn(paired.server.targets[0], 'broadcast'), false);
    assert.equal(Object.hasOwn(paired.server.targets[0], 'port'), false);

    const stored = readDevicesFile(ctx.stateDir);
    assert.equal(stored.devices.length, 1);
    assert.equal(stored.devices[0].tokenHash, sha256Hex(paired.token));
    assert.equal(JSON.stringify(stored).includes(paired.token), false);

    const auditText = fs.readFileSync(path.join(ctx.stateDir, 'audit.log'), 'utf8');
    assert.equal(auditText.includes(paired.token), false);
    assert.equal(auditText.includes(paired.window.code), false);
    assert.equal(auditText.includes(ctx.token), false);

    const adminWindow = paired.window.payload;
    assert.equal(adminWindow.v, 1);
    assert.equal(adminWindow.kind, 'bridge');
    assert.equal(adminWindow.name, 'Test Wake Bridge');
    assert.equal(adminWindow.host, '127.0.0.1');
    assert.equal(adminWindow.port, ctx.port);
    assert.equal(adminWindow.fingerprint, ctx.fingerprint);
    assert.match(adminWindow.code, /^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/);
  });

  test('pairing codes are single-use over HTTPS', async () => {
    const response = await ctx.request({
      method: 'POST',
      requestPath: '/api/v1/pair',
      body: { code: paired.window.code, deviceName: 'Second try' },
      headers: { 'Content-Type': 'application/json' },
    });
    assert.equal(response.status, 409);
    assert.deepEqual(response.json, { ok: false, error: 'pairing_closed' });
  });

  test('wrong pairing codes are rejected and a later correct code succeeds', async () => {
    const window = await ctx.startPairing('Retry test');
    const wrong = await ctx.request({
      method: 'POST',
      requestPath: '/api/v1/pair',
      body: { code: 'ZZZZ-ZZZZ', deviceName: 'Phone' },
      headers: { 'Content-Type': 'application/json' },
    });
    assert.equal(wrong.status, 401);
    assert.deepEqual(wrong.json, { ok: false, error: 'invalid_code' });

    const normalized = window.code.toLowerCase();
    const correct = await ctx.request({
      method: 'POST',
      requestPath: '/api/v1/pair',
      body: { code: normalized, deviceName: 'Phone' },
      headers: { 'Content-Type': 'application/json' },
    });
    assert.equal(correct.status, 200);
    assert.equal(correct.json.ok, true);
  });

  test('five failed HTTP pairing attempts invalidate the window', async () => {
    const window = await ctx.startPairing('Lockout test');
    for (let attempt = 1; attempt <= 4; attempt += 1) {
      const response = await ctx.request({
        method: 'POST',
        requestPath: '/api/v1/pair',
        body: { code: 'ZZZZ-ZZZZ', deviceName: 'Phone' },
        headers: { 'Content-Type': 'application/json' },
      });
      assert.equal(response.status, 401);
      assert.deepEqual(response.json, { ok: false, error: 'invalid_code' });
    }
    const fifth = await ctx.request({
      method: 'POST',
      requestPath: '/api/v1/pair',
      body: { code: 'ZZZZ-ZZZZ', deviceName: 'Phone' },
      headers: { 'Content-Type': 'application/json' },
    });
    assert.equal(fifth.status, 429);
    assert.deepEqual(fifth.json, { ok: false, error: 'too_many_attempts' });

    const correctAfterLockout = await ctx.request({
      method: 'POST',
      requestPath: '/api/v1/pair',
      body: { code: window.code, deviceName: 'Phone' },
      headers: { 'Content-Type': 'application/json' },
    });
    assert.equal(correctAfterLockout.status, 409);
    assert.deepEqual(correctAfterLockout.json, { ok: false, error: 'pairing_closed' });
  });

  test('signed status succeeds and preserves the query string in the canonical path', async () => {
    const response = await ctx.signedRequest({
      token: paired.token,
      deviceId: paired.deviceId,
      requestPath: '/api/v1/status?probe=1',
    });
    assert.equal(response.status, 200);
    assert.equal(response.json.ok, true);
    assert.equal(response.json.name, 'Test Wake Bridge');
    assert.equal(response.json.kind, 'bridge');
    assert.equal(response.json.version, '1.0.0');
    assert.equal(response.json.online, true);
    assert.equal(Number.isInteger(response.json.time), true);
    assert.deepEqual(response.json.targets, [
      {
        id: 'test-pc',
        name: 'Test PC',
        mac: 'AA:BB:CC:DD:EE:FF',
        broadcast: '127.0.0.1',
        port: 9,
      },
    ]);
    assert.equal(Object.hasOwn(response.json, 'token'), false);
  });

  test('unauthenticated and tampered requests receive the generic 401 envelope', async () => {
    const missing = await ctx.request({ requestPath: '/api/v1/status' });
    assert.equal(missing.status, 401);
    assert.deepEqual(missing.json, { ok: false, error: 'unauthorized' });

    const signed = await ctx.signedRequest({
      token: paired.token,
      deviceId: paired.deviceId,
      extraHeaders: { 'X-PCWake-Signature': 'A'.repeat(43) },
    });
    assert.equal(signed.status, 401);
    assert.deepEqual(signed.json, { ok: false, error: 'unauthorized' });

    const unknownDevice = await ctx.signedRequest({
      token: paired.token,
      deviceId: '0'.repeat(32),
    });
    assert.equal(unknownDevice.status, 401);
    assert.deepEqual(unknownDevice.json, { ok: false, error: 'unauthorized' });
  });

  test('timestamp skew beyond +/-60 seconds is rejected', async () => {
    const stale = await ctx.signedRequest({
      token: paired.token,
      deviceId: paired.deviceId,
      timestamp: nowSeconds - 61,
    });
    assert.equal(stale.status, 401);

    const future = await ctx.signedRequest({
      token: paired.token,
      deviceId: paired.deviceId,
      timestamp: nowSeconds + 61,
    });
    assert.equal(future.status, 401);

    const atBoundary = await ctx.signedRequest({
      token: paired.token,
      deviceId: paired.deviceId,
      timestamp: nowSeconds - 60,
    });
    assert.equal(atBoundary.status, 200);
  });

  test('nonces are stored only after signature success, and replay is rejected', async () => {
    const nonce = createNonce();
    const headers = signedHeaders({
      token: paired.token,
      deviceId: paired.deviceId,
      method: 'GET',
      path: '/api/v1/status',
      body: Buffer.alloc(0),
      timestamp: nowSeconds,
      nonce,
    });

    const badSignature = await ctx.request({
      requestPath: '/api/v1/status',
      headers: { ...headers, 'X-PCWake-Signature': 'A'.repeat(43) },
    });
    assert.equal(badSignature.status, 401);

    const first = await ctx.request({ requestPath: '/api/v1/status', headers });
    assert.equal(first.status, 200);
    const replay = await ctx.request({ requestPath: '/api/v1/status', headers });
    assert.equal(replay.status, 401);
    assert.deepEqual(replay.json, { ok: false, error: 'unauthorized' });
  });

  test('a signature is bound to the body and method', async () => {
    const body = Buffer.from(JSON.stringify({ mac: 'AA:BB:CC:DD:EE:FF', repeats: 1 }));
    const headers = signedHeaders({
      token: paired.token,
      deviceId: paired.deviceId,
      method: 'POST',
      path: '/api/v1/wake',
      body,
      timestamp: nowSeconds,
    });
    const tamperedBody = Buffer.from(JSON.stringify({ mac: 'AA:BB:CC:DD:EE:FF', repeats: 2 }));
    const response = await ctx.request({
      method: 'POST',
      requestPath: '/api/v1/wake',
      body: tamperedBody,
      headers: { ...headers, 'Content-Type': 'application/json' },
    });
    assert.equal(response.status, 401);
  });

  test('lastSeenAt is persisted for authenticated requests', () => {
    const stored = readDevicesFile(ctx.stateDir);
    const device = stored.devices.find((entry) => entry.id === paired.deviceId);
    assert.equal(device.lastSeenAt, nowSeconds);
  });

  test('revoke invalidates the calling token; admin can revoke too', async () => {
    const second = await ctx.pair({ label: 'Revoke test', deviceName: 'Old Phone' });
    const revoked = await ctx.signedRequest({
      token: second.token,
      deviceId: second.deviceId,
      method: 'POST',
      requestPath: '/api/v1/revoke',
      body: Buffer.alloc(0),
    });
    assert.equal(revoked.status, 200);
    assert.deepEqual(revoked.json, { ok: true });

    const afterRevoke = await ctx.signedRequest({
      token: second.token,
      deviceId: second.deviceId,
    });
    assert.equal(afterRevoke.status, 401);

    const stored = readDevicesFile(ctx.stateDir);
    const record = stored.devices.find((entry) => entry.id === second.deviceId);
    assert.equal(Number.isInteger(record.revokedAt), true);

    const adminRevoke = await ctx.pair({ label: 'Admin revoke', deviceName: 'Tablet' });
    const adminResponse = await ctx.adminRequest({
      method: 'POST',
      requestPath: '/admin/revoke',
      body: { deviceId: adminRevoke.deviceId },
    });
    assert.equal(adminResponse.status, 200);
    assert.deepEqual(adminResponse.json, { ok: true });
  });

  test('admin endpoints require the admin token and are not mounted on HTTPS', async () => {
    const wrong = await ctx.adminRequest({
      method: 'GET',
      requestPath: '/admin/status',
      headers: { Authorization: 'Bearer wrong-token' },
    });
    assert.equal(wrong.status, 401);
    assert.deepEqual(wrong.json, { ok: false, error: 'unauthorized' });

    const overHttps = await ctx.request({ requestPath: '/admin/status' });
    assert.equal(overHttps.status, 404);
    assert.equal(overHttps.text.includes('adminToken'), false);
  });

  test('unknown unauthenticated API paths do not expose handlers', async () => {
    const response = await ctx.request({ requestPath: '/api/v1/unknown' });
    assert.equal(response.status, 401);
    assert.deepEqual(response.json, { ok: false, error: 'unauthorized' });
  });
});
