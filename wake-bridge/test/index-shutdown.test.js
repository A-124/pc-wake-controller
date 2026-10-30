import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

import { FIXTURE_CERT, FIXTURE_KEY, REPO_ROOT } from './support/bridge.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ENTRY = path.resolve(HERE, '..', 'src', 'index.js');

function waitForLine(child, predicate, timeoutMs = 10_000) {
  return new Promise((resolve, reject) => {
    let stdout = '';
    let stderr = '';
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error(`timed out waiting for startup; stdout=${stdout} stderr=${stderr}`));
    }, timeoutMs);
    function cleanup() {
      clearTimeout(timer);
      child.stdout.off('data', onOut);
      child.stderr.off('data', onErr);
    }
    function onOut(chunk) {
      stdout += chunk;
      for (const line of stdout.split('\n')) {
        if (!line.trim()) continue;
        try {
          const record = JSON.parse(line);
          if (predicate(record)) {
            cleanup();
            resolve(record);
            return;
          }
        } catch {
          // partial line; wait for more
        }
      }
    }
    function onErr(chunk) {
      stderr += chunk;
    }
    child.stdout.on('data', onOut);
    child.stderr.on('data', onErr);
  });
}

// Windows cannot deliver SIGTERM to a Node process with signal-handler
// semantics, so this end-to-end check is registered only on POSIX hosts.
if (process.platform !== 'win32') {
  test('entry point starts, writes JSON logs, and exits cleanly on SIGTERM', async () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pcwake-index-test-'));
    const configPath = path.join(root, 'config.json');
    const stateDir = path.join(root, 'state');
    fs.writeFileSync(
      configPath,
      `${JSON.stringify(
        {
          name: 'Shutdown Test Bridge',
          listenHost: '127.0.0.1',
          listenPort: 0,
          publicHost: '127.0.0.1',
          tlsCertPath: FIXTURE_CERT,
          tlsKeyPath: FIXTURE_KEY,
          stateDir,
          adminPort: 0,
          targets: [],
        },
        null,
        2
      )}\n`
    );

    const child = spawn(process.execPath, [ENTRY, '--config', configPath], {
      cwd: REPO_ROOT,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const exitPromise = new Promise((resolve) => {
      child.once('exit', (code, signal) => resolve({ code, signal }));
    });

    try {
      const started = await waitForLine(child, (record) => record.msg === 'wake bridge started');
      assert.equal(started.level, 'info');
      assert.equal(started.name, 'Shutdown Test Bridge');
      child.kill('SIGTERM');
      const exit = await Promise.race([
        exitPromise,
        new Promise((_, reject) => setTimeout(() => reject(new Error('process did not exit')), 10_000)),
      ]);
      assert.equal(exit.code, 0);
      assert.equal(exit.signal, null);
      const audit = fs
        .readFileSync(path.join(stateDir, 'audit.log'), 'utf8')
        .split('\n')
        .filter(Boolean)
        .map((line) => JSON.parse(line));
      assert.equal(audit.some((entry) => entry.event === 'shutdown' && entry.signal === 'SIGTERM'), true);
    } finally {
      if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
      fs.rmSync(root, { recursive: true, force: true });
    }
  });
}
