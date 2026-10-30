import assert from 'node:assert/strict';
import dgram from 'node:dgram';
import { after, before, describe, test } from 'node:test';

import {
  createTestBridge,
  readAuditLines,
} from './support/bridge.js';

function waitFor(predicate, timeoutMs = 2000) {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const timer = setInterval(() => {
      if (predicate()) {
        clearInterval(timer);
        resolve();
      } else if (Date.now() - started > timeoutMs) {
        clearInterval(timer);
        reject(new Error('timed out waiting for UDP datagram'));
      }
    }, 10);
  });
}

describe('wake API with a real UDP listener', () => {
  let socket;
  let udpPort;
  let ctx;
  let paired;
  let nowSeconds;
  const messages = [];

  before(async () => {
    socket = dgram.createSocket('udp4');
    socket.on('message', (message) => messages.push(Buffer.from(message)));
    await new Promise((resolve, reject) => {
      socket.once('error', reject);
      socket.bind(0, '127.0.0.1', resolve);
    });
    udpPort = socket.address().port;

    ctx = await createTestBridge({
      targets: [
        {
          id: 'test-pc',
          name: 'Test PC',
          mac: 'AA:BB:CC:DD:EE:FF',
          broadcast: '127.0.0.1',
          port: udpPort,
          repeats: 1,
        },
      ],
      rateLimits: { authFailuresPerMinutePerIp: 100 },
    });
    paired = await ctx.pair();
    nowSeconds = Math.floor(ctx.clock.nowMs() / 1000);
  });

  after(async () => {
    await ctx.close();
    await new Promise((resolve) => socket.close(resolve));
  });

  test('POST /api/v1/wake sends the exact 102-byte Magic Packet to the configured target', async () => {
    messages.length = 0;
    const body = Buffer.from(
      JSON.stringify({
        mac: 'AA:BB:CC:DD:EE:FF',
        repeats: 1,
        // Client-supplied broadcast/port must be ignored.
        broadcast: '10.99.99.255',
        port: 12345,
      })
    );
    const response = await ctx.signedRequest({
      token: paired.token,
      deviceId: paired.deviceId,
      method: 'POST',
      requestPath: '/api/v1/wake',
      body,
    });
    assert.equal(response.status, 200);
    assert.deepEqual(response.json, {
      ok: true,
      target: 'test-pc',
      sent: 1,
      port: udpPort,
      broadcast: '127.0.0.1',
      at: nowSeconds,
    });
    assert.equal(Number.isInteger(response.json.sent), true);
    assert.equal(Object.hasOwn(response.json, 'booted'), false);

    await waitFor(() => messages.length >= 1);
    const expected = Buffer.alloc(102, 0xff);
    const mac = Buffer.from('aabbccddeeff', 'hex');
    for (let index = 0; index < 16; index += 1) {
      mac.copy(expected, 6 + index * 6);
    }
    assert.equal(messages[0].length, 102);
    assert.equal(messages[0].equals(expected), true);

    const audited = readAuditLines(ctx.stateDir).filter(
      (entry) => entry.event === 'wake' && entry.result === 'accepted'
    );
    assert.ok(audited.length >= 1);
    assert.equal(audited.at(-1).deviceId, paired.deviceId);
    assert.equal(audited.at(-1).mac, 'AA:BB:CC:DD:EE:FF');
    assert.equal(audited.at(-1).ip, '127.0.0.1');
  });

  test('non-allow-listed and malformed MACs are rejected with 403 and audited', async () => {
    for (const mac of ['11:22:33:44:55:66', 'not-a-mac']) {
      const body = Buffer.from(JSON.stringify({ mac, repeats: 1 }));
      const response = await ctx.signedRequest({
        token: paired.token,
        deviceId: paired.deviceId,
        method: 'POST',
        requestPath: '/api/v1/wake',
        body,
      });
      assert.equal(response.status, 403);
      assert.deepEqual(response.json, { ok: false, error: 'target_not_allowed' });
    }
    const rejected = readAuditLines(ctx.stateDir).filter(
      (entry) => entry.event === 'wake' && entry.reason === 'target_not_allowed'
    );
    assert.equal(rejected.length >= 2, true);
    assert.equal(rejected.at(-1).deviceId, paired.deviceId);
    assert.equal(rejected.at(-1).ip, '127.0.0.1');
  });

  test('repeats above 10 are clamped to 10 and all datagrams are sent', async () => {
    messages.length = 0;
    const body = Buffer.from(JSON.stringify({ mac: 'AA:BB:CC:DD:EE:FF', repeats: 99 }));
    const response = await ctx.signedRequest({
      token: paired.token,
      deviceId: paired.deviceId,
      method: 'POST',
      requestPath: '/api/v1/wake',
      body,
    });
    assert.equal(response.status, 200);
    assert.equal(response.json.sent, 10);
    await waitFor(() => messages.length >= 10);
    assert.equal(messages.length, 10);
    assert.equal(messages.every((message) => message.length === 102), true);
  });

  test('repeats below 1 are clamped to 1 and non-integers are rejected', async () => {
    messages.length = 0;
    const clampedBody = Buffer.from(JSON.stringify({ mac: 'AA:BB:CC:DD:EE:FF', repeats: 0 }));
    const clamped = await ctx.signedRequest({
      token: paired.token,
      deviceId: paired.deviceId,
      method: 'POST',
      requestPath: '/api/v1/wake',
      body: clampedBody,
    });
    assert.equal(clamped.status, 200);
    assert.equal(clamped.json.sent, 1);
    await waitFor(() => messages.length >= 1);

    const invalidBody = Buffer.from(JSON.stringify({ mac: 'AA:BB:CC:DD:EE:FF', repeats: 'three' }));
    const invalid = await ctx.signedRequest({
      token: paired.token,
      deviceId: paired.deviceId,
      method: 'POST',
      requestPath: '/api/v1/wake',
      body: invalidBody,
    });
    assert.equal(invalid.status, 400);
    assert.deepEqual(invalid.json, { ok: false, error: 'invalid_repeats' });
  });

  test('missing repeats uses the target default and missing MAC is a 400', async () => {
    messages.length = 0;
    const defaultBody = Buffer.from(JSON.stringify({ mac: 'AA:BB:CC:DD:EE:FF' }));
    const response = await ctx.signedRequest({
      token: paired.token,
      deviceId: paired.deviceId,
      method: 'POST',
      requestPath: '/api/v1/wake',
      body: defaultBody,
    });
    assert.equal(response.status, 200);
    assert.equal(response.json.sent, 1);
    await waitFor(() => messages.length >= 1);

    const missingMac = await ctx.signedRequest({
      token: paired.token,
      deviceId: paired.deviceId,
      method: 'POST',
      requestPath: '/api/v1/wake',
      body: Buffer.from(JSON.stringify({ repeats: 1 })),
    });
    assert.equal(missingMac.status, 400);
    assert.deepEqual(missingMac.json, { ok: false, error: 'invalid_request' });
  });

  test('authenticated wake without a valid signature is rejected before sending', async () => {
    messages.length = 0;
    const response = await ctx.request({
      method: 'POST',
      requestPath: '/api/v1/wake',
      body: { mac: 'AA:BB:CC:DD:EE:FF', repeats: 1 },
      headers: { 'Content-Type': 'application/json' },
    });
    assert.equal(response.status, 401);
    await new Promise((resolve) => setTimeout(resolve, 100));
    assert.equal(messages.length, 0);
  });
});
