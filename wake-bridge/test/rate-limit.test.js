import assert from 'node:assert/strict';
import { test } from 'node:test';

import { WindowCounter } from '../src/rate-limit.js';
import { createTestBridge } from './support/bridge.js';

test('WindowCounter is a sliding window with injectable time', () => {
  let now = 1_000_000;
  const counter = new WindowCounter({ limit: 2, windowMs: 60_000, nowMs: () => now });

  assert.equal(counter.hit('ip'), 1);
  assert.equal(counter.hit('ip'), 2);
  assert.equal(counter.isLimited('ip'), true);
  assert.equal(counter.isLimited('other'), false);
  assert.equal(counter.retryAfterMs('ip'), 60_000);

  now += 59_999;
  assert.equal(counter.count('ip'), 2);
  now += 1;
  assert.equal(counter.count('ip'), 0);
  assert.equal(counter.isLimited('ip'), false);
  assert.equal(counter.hit('ip'), 1);
});

test('the 11th authentication failure from one IP is rate limited (default=10)', async (t) => {
  const ctx = await createTestBridge();
  t.after(() => ctx.close());
  const paired = await ctx.pair();

  for (let attempt = 1; attempt <= 10; attempt += 1) {
    const response = await ctx.signedRequest({
      token: paired.token,
      deviceId: paired.deviceId,
      extraHeaders: { 'X-PCWake-Signature': 'A'.repeat(43) },
    });
    assert.equal(response.status, 401, `attempt ${attempt}`);
  }

  const blocked = await ctx.signedRequest({
    token: paired.token,
    deviceId: paired.deviceId,
  });
  assert.equal(blocked.status, 429);
  assert.deepEqual(blocked.json, { ok: false, error: 'rate_limited' });

  ctx.clock.advance(60_000);
  const afterWindow = await ctx.signedRequest({
    token: paired.token,
    deviceId: paired.deviceId,
  });
  assert.equal(afterWindow.status, 200);
});

test('per-device authenticated request limit returns 429', async (t) => {
  const ctx = await createTestBridge({
    rateLimits: { authPerMinutePerDevice: 2, authFailuresPerMinutePerIp: 100 },
  });
  t.after(() => ctx.close());
  const paired = await ctx.pair();

  for (let request = 1; request <= 2; request += 1) {
    const response = await ctx.signedRequest({
      token: paired.token,
      deviceId: paired.deviceId,
    });
    assert.equal(response.status, 200);
  }
  const limited = await ctx.signedRequest({
    token: paired.token,
    deviceId: paired.deviceId,
  });
  assert.equal(limited.status, 429);
  assert.deepEqual(limited.json, { ok: false, error: 'rate_limited' });
});

test('per-device wake limit counts authenticated wake attempts and blocks sends', async (t) => {
  const sends = [];
  const ctx = await createTestBridge({
    sendMagicPacketFn: async ({ repeats, mac }) => {
      sends.push({ repeats, mac });
      return { sent: repeats, failed: false, error: null };
    },
    rateLimits: { wakesPerMinutePerDevice: 2, authFailuresPerMinutePerIp: 100 },
  });
  t.after(() => ctx.close());
  const paired = await ctx.pair();

  for (let attempt = 1; attempt <= 2; attempt += 1) {
    const body = Buffer.from(JSON.stringify({ mac: 'AA:BB:CC:DD:EE:FF', repeats: 1 }));
    const response = await ctx.signedRequest({
      token: paired.token,
      deviceId: paired.deviceId,
      method: 'POST',
      requestPath: '/api/v1/wake',
      body,
    });
    assert.equal(response.status, 200);
    assert.equal(response.json.sent, 1);
  }

  const body = Buffer.from(JSON.stringify({ mac: 'AA:BB:CC:DD:EE:FF', repeats: 1 }));
  const limited = await ctx.signedRequest({
    token: paired.token,
    deviceId: paired.deviceId,
    method: 'POST',
    requestPath: '/api/v1/wake',
    body,
  });
  assert.equal(limited.status, 429);
  assert.deepEqual(limited.json, { ok: false, error: 'rate_limited' });
  assert.equal(sends.length, 2);
});

test('pairing attempts are rate limited per source IP', async (t) => {
  const ctx = await createTestBridge({
    rateLimits: { pairAttemptsPerMinutePerIp: 2, authFailuresPerMinutePerIp: 100 },
  });
  t.after(() => ctx.close());
  const window = await ctx.startPairing('Rate test');

  for (let attempt = 1; attempt <= 2; attempt += 1) {
    const response = await ctx.request({
      method: 'POST',
      requestPath: '/api/v1/pair',
      body: { code: 'ZZZZ-ZZZZ', deviceName: 'Phone' },
      headers: { 'Content-Type': 'application/json' },
    });
    assert.equal(response.status, 401);
  }

  const limited = await ctx.request({
    method: 'POST',
    requestPath: '/api/v1/pair',
    body: { code: window.code, deviceName: 'Phone' },
    headers: { 'Content-Type': 'application/json' },
  });
  assert.equal(limited.status, 429);
  assert.deepEqual(limited.json, { ok: false, error: 'rate_limited' });
});
