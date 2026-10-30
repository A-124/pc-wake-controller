import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';

import { AuditLog } from '../src/audit.js';
import { silentLogger } from '../src/logger.js';

test('audit writes JSONL records and rotates once at the size limit', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pcwake-audit-test-'));
  const nowMs = 1_720_000_000_000;
  const sample = {
    ts: new Date(nowMs).toISOString(),
    event: 'wake',
    deviceId: 'a'.repeat(32),
    n: 1,
  };
  const lineBytes = Buffer.byteLength(`${JSON.stringify(sample)}\n`);

  const audit = new AuditLog({
    stateDir: root,
    maxBytes: lineBytes * 2 + 10,
    logger: silentLogger,
    nowMs: () => nowMs,
  });

  assert.equal(audit.log('wake', { deviceId: 'a'.repeat(32), n: 1 }), true);
  assert.equal(audit.log('wake', { deviceId: 'a'.repeat(32), n: 2 }), true);
  assert.equal(fs.existsSync(path.join(root, 'audit.log.1')), false);
  assert.equal(audit.log('wake', { deviceId: 'a'.repeat(32), n: 3 }), true);
  assert.equal(fs.existsSync(path.join(root, 'audit.log.1')), true);

  const parse = (file) =>
    fs
      .readFileSync(path.join(root, file), 'utf8')
      .split('\n')
      .filter(Boolean)
      .map((line) => JSON.parse(line));

  const rotated = parse('audit.log.1');
  const current = parse('audit.log');
  assert.deepEqual(rotated.map((entry) => entry.n), [1, 2]);
  assert.deepEqual(current.map((entry) => entry.n), [3]);
  assert.equal(rotated[0].ts, new Date(nowMs).toISOString());
  assert.equal(rotated[0].event, 'wake');
  assert.equal(rotated[0].deviceId, 'a'.repeat(32));

  if (process.platform !== 'win32') {
    assert.equal(fs.statSync(path.join(root, 'audit.log')).mode & 0o777, 0o600);
  }
  fs.rmSync(root, { recursive: true, force: true });
});
