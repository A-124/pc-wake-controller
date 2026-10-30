import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import https from 'node:https';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { startAdminServer } from '../../src/admin.js';
import { AuditLog } from '../../src/audit.js';
import { createNonce, signRequest } from '../../src/auth.js';
import { validateConfig } from '../../src/config.js';
import { silentLogger } from '../../src/logger.js';
import { PairingManager } from '../../src/pairing.js';
import { startBridgeServer } from '../../src/server.js';
import { StateStore, readAdminRecord } from '../../src/state.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const FIXTURE_CERT = path.resolve(HERE, '..', 'fixtures', 'TEST-ONLY-cert.pem');
export const FIXTURE_KEY = path.resolve(HERE, '..', 'fixtures', 'TEST-ONLY-key.pem');
export const REPO_ROOT = path.resolve(HERE, '..', '..');

export function makeClock(start = Date.now()) {
  let current = start;
  return {
    nowMs: () => current,
    advance(ms) {
      current += ms;
    },
    set(ms) {
      current = ms;
    },
  };
}

function request({ transport, host, port, method = 'GET', requestPath = '/', headers = {}, body }) {
  return new Promise((resolve, reject) => {
    const data = body === undefined || body === null
      ? null
      : Buffer.isBuffer(body)
        ? body
        : Buffer.from(typeof body === 'string' ? body : JSON.stringify(body));
    const finalHeaders = { Connection: 'close', Accept: 'application/json', ...headers };
    if (data && finalHeaders['Content-Length'] === undefined && finalHeaders['content-length'] === undefined) {
      finalHeaders['Content-Length'] = data.length;
    }
    const options = {
      host,
      port,
      method,
      path: requestPath,
      headers: finalHeaders,
      agent: false,
      timeout: 5000,
    };
    if (transport === 'https') {
      options.rejectUnauthorized = false;
      options.servername = 'localhost';
    }
    const req = transport === 'https' ? https.request(options) : http.request(options);
    req.on('timeout', () => req.destroy(new Error('request timed out')));
    req.on('error', reject);
    req.on('response', (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => {
        const raw = Buffer.concat(chunks);
        const text = raw.toString('utf8');
        let json = null;
        try {
          json = text ? JSON.parse(text) : null;
        } catch {
          json = null;
        }
        resolve({ status: res.statusCode, headers: res.headers, body: raw, text, json });
      });
    });
    if (data) req.write(data);
    req.end();
  });
}

export function certificateFingerprint(certPath = FIXTURE_CERT) {
  const certificate = new crypto.X509Certificate(fs.readFileSync(certPath));
  return crypto.createHash('sha256').update(certificate.raw).digest('hex');
}

/**
 * Start a fully real bridge (HTTPS + admin listener + state + audit) on
 * ephemeral ports, using the test-only certificate fixture.
 */
export async function createTestBridge({
  targets,
  rateLimits,
  clock = makeClock(),
  sendMagicPacketFn,
  wakeIntervalMs = 5,
  name = 'Test Wake Bridge',
} = {}) {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcwake-bridge-test-'));
  const stateDir = path.join(tempDir, 'state');
  const rawConfig = {
    name,
    listenHost: '127.0.0.1',
    listenPort: 0,
    publicHost: '127.0.0.1',
    tlsCertPath: FIXTURE_CERT,
    tlsKeyPath: FIXTURE_KEY,
    stateDir,
    adminPort: 0,
    targets: targets ?? [
      {
        id: 'test-pc',
        name: 'Test PC',
        mac: 'AA:BB:CC:DD:EE:FF',
        broadcast: '127.0.0.1',
        port: 9,
        repeats: 1,
      },
    ],
    rateLimits,
    auditMaxBytes: 5 * 1024 * 1024,
  };
  const configPath = path.join(tempDir, 'config.json');
  fs.writeFileSync(configPath, `${JSON.stringify(rawConfig, null, 2)}\n`);
  const config = validateConfig(rawConfig, { configDir: tempDir });

  const state = new StateStore({ stateDir, nowMs: clock.nowMs });
  const audit = new AuditLog({ stateDir, maxBytes: config.auditMaxBytes, logger: silentLogger });
  const pairing = new PairingManager({ nowMs: clock.nowMs });

  const bridge = await startBridgeServer({
    config,
    state,
    pairing,
    audit,
    logger: silentLogger,
    clock,
    sendMagicPacketFn,
    wakeIntervalMs,
  });
  const admin = await startAdminServer({
    config,
    state,
    pairing,
    audit,
    logger: silentLogger,
    clock,
    getPairingInfo: () => ({
      host: '127.0.0.1',
      port: bridge.port,
      fingerprint: bridge.fingerprint,
    }),
  });
  const adminRecord = readAdminRecord(stateDir);

  const ctx = {
    tempDir,
    stateDir,
    configPath,
    rawConfig,
    config,
    state,
    audit,
    pairing,
    bridge,
    admin,
    clock,
    port: bridge.port,
    adminPort: admin.port,
    fingerprint: bridge.fingerprint,
    token: adminRecord.adminToken,
    request: (opts = {}) =>
      request({
        transport: 'https',
        host: '127.0.0.1',
        port: bridge.port,
        ...opts,
      }),
    adminRequest: (opts = {}) => {
      const { headers: extraHeaders, ...rest } = opts;
      return request({
        transport: 'http',
        host: '127.0.0.1',
        port: admin.port,
        ...rest,
        headers: {
          Authorization: `Bearer ${adminRecord.adminToken}`,
          ...(extraHeaders ?? {}),
        },
      });
    },
    async startPairing(label = 'Test device') {
      const response = await ctx.adminRequest({
        method: 'POST',
        requestPath: '/admin/pair',
        body: { label },
      });
      if (response.status !== 200) {
        throw new Error(`admin pair failed: HTTP ${response.status} ${response.text}`);
      }
      return response.json;
    },
    async pair({
      label = 'Test device',
      deviceName = 'Pixel 8',
      deviceModel = 'Google Pixel 8',
      appVersion = '1.0.0',
    } = {}) {
      const window = await ctx.startPairing(label);
      const response = await ctx.request({
        method: 'POST',
        requestPath: '/api/v1/pair',
        body: { code: window.code, deviceName, deviceModel, appVersion },
        headers: { 'Content-Type': 'application/json' },
      });
      if (response.status !== 200) {
        throw new Error(`pair failed: HTTP ${response.status} ${response.text}`);
      }
      return { window, response, ...response.json };
    },
    signedRequest({
      token,
      deviceId,
      method = 'GET',
      requestPath = '/api/v1/status',
      body = Buffer.alloc(0),
      timestamp,
      nonce,
      extraHeaders = {},
    }) {
      const bytes = Buffer.isBuffer(body) ? body : Buffer.from(body ?? '');
      const ts = timestamp ?? Math.floor(clock.nowMs() / 1000);
      const headerNonce = nonce ?? createNonce();
      const { signature } = signRequest({
        token,
        method,
        path: requestPath,
        timestamp: ts,
        nonce: headerNonce,
        body: bytes,
      });
      const headers = {
        'X-PCWake-Device': deviceId,
        'X-PCWake-Timestamp': String(ts),
        'X-PCWake-Nonce': headerNonce,
        'X-PCWake-Signature': signature,
        ...extraHeaders,
      };
      if (bytes.length > 0) headers['Content-Type'] = 'application/json';
      return ctx.request({ method, requestPath, headers, body: bytes });
    },
    async close() {
      await admin.close();
      await bridge.close();
      fs.rmSync(tempDir, { recursive: true, force: true });
    },
  };
  return ctx;
}

export function readDevicesFile(stateDir) {
  return JSON.parse(fs.readFileSync(path.join(stateDir, 'devices.json'), 'utf8'));
}

export function readAuditLines(stateDir) {
  const file = path.join(stateDir, 'audit.log');
  if (!fs.existsSync(file)) return [];
  return fs
    .readFileSync(file, 'utf8')
    .split('\n')
    .filter(Boolean)
    .map((line) => JSON.parse(line));
}
