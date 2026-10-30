import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

import {
  certificateFingerprint,
  createTestBridge,
} from './support/bridge.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CLI = path.resolve(HERE, '..', 'src', 'cli.js');
const execFileAsync = promisify(execFile);

async function runCli(args) {
  try {
    const { stdout, stderr } = await execFileAsync(process.execPath, [CLI, ...args], {
      encoding: 'utf8',
      timeout: 20_000,
    });
    return { status: 0, stdout, stderr };
  } catch (error) {
    return {
      status: typeof error.code === 'number' ? error.code : 1,
      stdout: error.stdout ?? '',
      stderr: error.stderr ?? error.message,
    };
  }
}

function firstJsonLine(stdout) {
  const line = stdout.split(/\r?\n/).find((candidate) => candidate.trim().length > 0);
  return JSON.parse(line);
}

test('CLI --help documents pair/status/devices/revoke without needing config', async () => {
  const result = await runCli(['--help']);
  assert.equal(result.status, 0);
  assert.match(result.stdout, /Usage:/);
  assert.match(result.stdout, /pair \[--label <text>\]/);
  assert.match(result.stdout, /status/);
  assert.match(result.stdout, /devices/);
  assert.match(result.stdout, /revoke <deviceId>/);
});

test('CLI pair starts a real pairing window, prints the exact payload line and instructions', async (t) => {
  const ctx = await createTestBridge();
  t.after(() => ctx.close());

  const result = await runCli(['pair', '--config', ctx.configPath, '--label', 'Pixel']);
  assert.equal(result.status, 0, result.stderr);

  const payload = firstJsonLine(result.stdout);
  assert.deepEqual(Object.keys(payload).sort(), [
    'code',
    'fingerprint',
    'host',
    'kind',
    'name',
    'port',
    'v',
  ]);
  assert.equal(payload.v, 1);
  assert.equal(payload.kind, 'bridge');
  assert.equal(payload.name, 'Test Wake Bridge');
  assert.equal(payload.host, '127.0.0.1');
  assert.equal(payload.port, ctx.port);
  assert.equal(payload.fingerprint, ctx.fingerprint);
  assert.equal(payload.fingerprint, certificateFingerprint());
  assert.match(payload.code, /^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/);

  assert.match(result.stdout, /Pairing window open: 5 minutes, single use\./);
  assert.match(result.stdout, new RegExp(`Certificate SHA-256 fingerprint: ${ctx.fingerprint}`));
  assert.match(result.stdout, /Label: Pixel/);
  assert.equal(result.stdout.includes(ctx.token), false);
  assert.equal(result.stderr.includes(ctx.token), false);

  // The code printed by the CLI is the real active code.
  const paired = await ctx.request({
    method: 'POST',
    requestPath: '/api/v1/pair',
    body: { code: payload.code, deviceName: 'Pixel 8' },
    headers: { 'Content-Type': 'application/json' },
  });
  assert.equal(paired.status, 200);
  assert.equal(paired.json.ok, true);

  const status = await runCli(['status', '--config', ctx.configPath]);
  assert.equal(status.status, 0, status.stderr);
  const statusJson = JSON.parse(status.stdout);
  assert.equal(statusJson.ok, true);
  assert.equal(statusJson.name, 'Test Wake Bridge');
  assert.equal(statusJson.fingerprint, ctx.fingerprint);
  assert.equal(statusJson.listen.port, ctx.port);
  assert.equal(statusJson.devices.active, 1);
  assert.equal(statusJson.pairing.active, false);

  const devices = await runCli(['devices', '--config', ctx.configPath]);
  assert.equal(devices.status, 0, devices.stderr);
  const devicesJson = JSON.parse(devices.stdout);
  assert.equal(devicesJson.devices.length, 1);
  assert.equal(devicesJson.devices[0].id, paired.json.deviceId);
  assert.equal(Object.hasOwn(devicesJson.devices[0], 'tokenHash'), false);
  assert.equal(devices.stdout.includes(paired.json.token), false);

  const revoke = await runCli(['revoke', paired.json.deviceId, '--config', ctx.configPath]);
  assert.equal(revoke.status, 0, revoke.stderr);
  assert.deepEqual(firstJsonLine(revoke.stdout), { ok: true });

  const afterRevoke = await ctx.signedRequest({
    token: paired.json.token,
    deviceId: paired.json.deviceId,
  });
  assert.equal(afterRevoke.status, 401);
});

test('CLI reports a clear error when the admin service is unavailable', async () => {
  const result = await runCli(['status', '--config', path.join(HERE, 'missing-config.json')]);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /cannot read config file|error:/);
});
