import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';

import { silentLogger } from '../src/logger.js';
import { StateStore, readAdminRecord } from '../src/state.js';
import { sha256Hex } from '../src/util.js';

function makeClock(start = 1_720_000_000_000) {
  let current = start;
  return {
    nowMs: () => current,
    advance(ms) {
      current += ms;
    },
  };
}

function tempStateDir() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcwake-state-test-'));
  return path.join(dir, 'state');
}

test('state files are created with admin credentials and empty device list', () => {
  const stateDir = tempStateDir();
  const clock = makeClock();
  const state = new StateStore({ stateDir, nowMs: clock.nowMs, logger: silentLogger });

  assert.ok(fs.existsSync(path.join(stateDir, 'admin.json')));
  assert.ok(fs.existsSync(path.join(stateDir, 'devices.json')));

  const admin = JSON.parse(fs.readFileSync(path.join(stateDir, 'admin.json'), 'utf8'));
  assert.match(admin.adminToken, /^[A-Za-z0-9_-]{43}$/);
  assert.equal(admin.adminPort, null);

  const devices = JSON.parse(fs.readFileSync(path.join(stateDir, 'devices.json'), 'utf8'));
  assert.deepEqual(devices, { version: 1, devices: [] });

  if (process.platform !== 'win32') {
    assert.equal(fs.statSync(path.join(stateDir, 'admin.json')).mode & 0o777, 0o600);
    assert.equal(fs.statSync(path.join(stateDir, 'devices.json')).mode & 0o777, 0o600);
    assert.equal(fs.statSync(stateDir).mode & 0o777, 0o700);
  }
  fs.rmSync(path.dirname(stateDir), { recursive: true, force: true });
});

test('issued tokens are 32 random-looking bytes and only hashes reach devices.json', () => {
  const stateDir = tempStateDir();
  const clock = makeClock();
  const state = new StateStore({ stateDir, nowMs: clock.nowMs, logger: silentLogger });
  const { device, token } = state.createDevice({
    name: 'Pixel 8',
    model: 'Google Pixel 8',
    appVersion: '1.0.0',
  });

  assert.match(device.id, /^[0-9a-f]{32}$/);
  assert.match(token, /^[A-Za-z0-9_-]{43}$/);
  assert.equal(token.length, 43);
  assert.equal(device.tokenHash, sha256Hex(token));
  assert.equal(state.tokenMatches(device, token), true);
  assert.equal(state.tokenMatches(device, `${token}A`), false);

  const raw = fs.readFileSync(path.join(stateDir, 'devices.json'), 'utf8');
  assert.equal(raw.includes(token), false);
  const stored = JSON.parse(raw);
  assert.equal(stored.devices.length, 1);
  assert.deepEqual(Object.keys(stored.devices[0]).sort(), [
    'appVersion',
    'createdAt',
    'id',
    'lastSeenAt',
    'model',
    'name',
    'revokedAt',
    'tokenHash',
  ]);
  assert.equal(Object.hasOwn(stored.devices[0], 'token'), false);

  const adminRaw = fs.readFileSync(path.join(stateDir, 'admin.json'), 'utf8');
  assert.equal(adminRaw.includes(token), false);

  // A second StateStore (simulated restart) can recover the verification token
  // from admin.json without ever loading a raw token from devices.json.
  const restarted = new StateStore({ stateDir, nowMs: clock.nowMs, logger: silentLogger });
  const restartedDevice = restarted.getDevice(device.id);
  assert.equal(restarted.deriveToken(device.id), token);
  assert.equal(restarted.tokenMatches(restartedDevice, token), true);

  fs.rmSync(path.dirname(stateDir), { recursive: true, force: true });
});

test('lastSeen and revocation are persisted without exposing token hashes in admin listings', () => {
  const stateDir = tempStateDir();
  const clock = makeClock();
  const state = new StateStore({ stateDir, nowMs: clock.nowMs, logger: silentLogger });
  const { device } = state.createDevice({ name: 'Phone' });

  clock.advance(5000);
  assert.equal(state.markSeen(device.id), true);
  let reloaded = new StateStore({ stateDir, nowMs: clock.nowMs, logger: silentLogger });
  assert.equal(reloaded.getDevice(device.id).lastSeenAt, Math.floor(clock.nowMs() / 1000));

  assert.equal(state.revokeDevice(device.id), true);
  assert.equal(state.revokeDevice(device.id), true);
  assert.equal(state.revokeDevice('0'.repeat(32)), false);
  const listed = state.listDevices();
  assert.equal(listed.length, 1);
  assert.equal(listed[0].revokedAt, Math.floor(clock.nowMs() / 1000));
  assert.equal(Object.hasOwn(listed[0], 'tokenHash'), false);

  reloaded = new StateStore({ stateDir, nowMs: clock.nowMs, logger: silentLogger });
  assert.equal(reloaded.getDevice(device.id).revokedAt, listed[0].revokedAt);
  assert.deepEqual(reloaded.counts(), { total: 1, active: 0 });

  fs.rmSync(path.dirname(stateDir), { recursive: true, force: true });
});

test('admin port is persisted for CLI discovery', () => {
  const stateDir = tempStateDir();
  const state = new StateStore({ stateDir, logger: silentLogger });
  state.setAdminPort(47123);
  const record = readAdminRecord(stateDir);
  assert.equal(record.adminPort, 47123);
  assert.equal(record.adminToken, state.getAdminToken());
  assert.throws(() => readAdminRecord(path.join(stateDir, 'missing')), /cannot read admin credentials/);
  fs.rmSync(path.dirname(stateDir), { recursive: true, force: true });
});
