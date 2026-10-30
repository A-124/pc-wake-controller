import assert from 'node:assert/strict';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

import {
  ConfigError,
  DEFAULT_RATE_LIMITS,
  loadConfig,
  resolvePublicHost,
  validateConfig,
} from '../src/config.js';
import { loadTlsCredentials } from '../src/server.js';
import {
  FIXTURE_CERT,
  FIXTURE_KEY,
  certificateFingerprint,
} from './support/bridge.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const EXAMPLE = path.resolve(HERE, '..', 'config.example.json');

function baseConfig(overrides = {}) {
  return {
    name: 'Test Bridge',
    listenHost: '0.0.0.0',
    listenPort: 8443,
    publicHost: '192.168.1.10',
    tlsCertPath: './cert.pem',
    tlsKeyPath: './key.pem',
    stateDir: './state',
    adminPort: 47111,
    targets: [
      {
        id: 'gaming-pc',
        name: 'Gaming PC',
        mac: 'AA:BB:CC:DD:EE:FF',
        broadcast: '192.168.1.255',
        port: 9,
        repeats: 3,
      },
    ],
    ...overrides,
  };
}

test('config.example.json is valid and resolves paths relative to itself', () => {
  const config = loadConfig(EXAMPLE);
  assert.equal(config.name, 'Home Wake Bridge');
  assert.equal(config.listenHost, '0.0.0.0');
  assert.equal(config.listenPort, 8443);
  assert.equal(config.publicHost, '192.168.1.10');
  assert.equal(config.adminPort, 47111);
  assert.equal(config.stateDir, path.resolve(path.dirname(EXAMPLE), './state'));
  assert.equal(config.tlsCertPath, path.resolve(path.dirname(EXAMPLE), './certs/bridge-cert.pem'));
  assert.equal(config.targets.length, 1);
  assert.deepEqual(config.targets[0], {
    id: 'gaming-pc',
    name: 'Gaming PC',
    mac: 'AA:BB:CC:DD:EE:FF',
    broadcast: '192.168.1.255',
    port: 9,
    repeats: 3,
  });
  assert.deepEqual(config.rateLimits, DEFAULT_RATE_LIMITS);
});

test('defaults are applied for optional fields', () => {
  const raw = baseConfig();
  delete raw.name;
  delete raw.listenHost;
  delete raw.listenPort;
  delete raw.publicHost;
  delete raw.adminPort;
  delete raw.targets;
  const config = validateConfig(raw, { configDir: '/tmp/example' });
  assert.equal(config.name, 'Wake Bridge');
  assert.equal(config.listenHost, '0.0.0.0');
  assert.equal(config.listenPort, 8443);
  assert.equal(config.publicHost, null);
  assert.equal(config.adminPort, 47111);
  assert.deepEqual(config.targets, []);
  assert.equal(config.auditMaxBytes, 5 * 1024 * 1024);
});

test('MAC addresses are normalized and duplicate MACs are rejected', () => {
  const config = validateConfig(
    baseConfig({
      targets: [
        { id: 'pc-one', name: 'One', mac: 'aa-bb-cc-dd-ee-ff', broadcast: '192.168.1.255' },
      ],
    }),
    { configDir: '/tmp/example' }
  );
  assert.equal(config.targets[0].mac, 'AA:BB:CC:DD:EE:FF');
  assert.equal(config.targets[0].port, 9);
  assert.equal(config.targets[0].repeats, 3);

  assert.throws(
    () =>
      validateConfig(
        baseConfig({
          targets: [
            { id: 'pc-one', name: 'One', mac: 'AA:BB:CC:DD:EE:FF', broadcast: '192.168.1.255' },
            { id: 'pc-two', name: 'Two', mac: 'aabbccddeeff', broadcast: '192.168.1.255' },
          ],
        }),
        { configDir: '/tmp/example' }
      ),
    /targets\[1\]\.mac: duplicate MAC address AA:BB:CC:DD:EE:FF/
  );
});

test('invalid target fields produce clear errors', () => {
  assert.throws(
    () =>
      validateConfig(
        baseConfig({ targets: [{ id: 'pc', name: 'PC', mac: 'nope', broadcast: '192.168.1.255' }] }),
        { configDir: '/tmp' }
      ),
    /targets\[0\]\.mac: invalid MAC address/
  );
  assert.throws(
    () =>
      validateConfig(
        baseConfig({ targets: [{ id: 'pc', name: 'PC', mac: 'AA:BB:CC:DD:EE:FF', broadcast: 'not-an-ip' }] }),
        { configDir: '/tmp' }
      ),
    /targets\[0\]\.broadcast: must be an IPv4 address/
  );
  assert.throws(
    () =>
      validateConfig(
        baseConfig({
          targets: [{ id: 'pc', name: 'PC', mac: 'AA:BB:CC:DD:EE:FF', broadcast: '192.168.1.255', port: 70000 }],
        }),
        { configDir: '/tmp' }
      ),
    /targets\[0\]\.port/
  );
  assert.throws(
    () =>
      validateConfig(
        baseConfig({
          targets: [{ id: 'pc', name: 'PC', mac: 'AA:BB:CC:DD:EE:FF', broadcast: '192.168.1.255', repeats: 11 }],
        }),
        { configDir: '/tmp' }
      ),
    /targets\[0\]\.repeats/
  );
  assert.throws(
    () =>
      validateConfig(
        baseConfig({ targets: [{ id: 'Bad_ID', name: 'PC', mac: 'AA:BB:CC:DD:EE:FF', broadcast: '192.168.1.255' }] }),
        { configDir: '/tmp' }
      ),
    /targets\[0\]\.id: must be a lowercase slug/
  );
});

test('duplicate target ids, unknown keys and missing TLS are rejected', () => {
  assert.throws(
    () =>
      validateConfig(
        baseConfig({
          targets: [
            { id: 'pc', name: 'One', mac: 'AA:BB:CC:DD:EE:FF', broadcast: '192.168.1.255' },
            { id: 'pc', name: 'Two', mac: '11:22:33:44:55:66', broadcast: '192.168.1.255' },
          ],
        }),
        { configDir: '/tmp' }
      ),
    /duplicate target id/
  );
  assert.throws(
    () => validateConfig(baseConfig({ unexpected: true }), { configDir: '/tmp' }),
    /unknown configuration key/
  );
  assert.throws(
    () =>
      validateConfig(
        baseConfig({ targets: [{ id: 'pc', name: 'PC', mac: 'AA:BB:CC:DD:EE:FF', broadcast: '192.168.1.255', secret: 1 }] }),
        { configDir: '/tmp' }
      ),
    /unknown configuration key/
  );
  const noCert = baseConfig();
  delete noCert.tlsCertPath;
  assert.throws(() => validateConfig(noCert, { configDir: '/tmp' }), /tlsCertPath: is required/);
  const noKey = baseConfig();
  delete noKey.tlsKeyPath;
  assert.throws(() => validateConfig(noKey, { configDir: '/tmp' }), /tlsKeyPath: is required/);
});

test('ports, rate limits and audit size are validated', () => {
  assert.throws(
    () => validateConfig(baseConfig({ listenPort: 70000 }), { configDir: '/tmp' }),
    /listenPort/
  );
  assert.throws(
    () => validateConfig(baseConfig({ adminPort: -1 }), { configDir: '/tmp' }),
    /adminPort/
  );
  assert.throws(
    () => validateConfig(baseConfig({ rateLimits: { authPerMinutePerDevice: 0 } }), { configDir: '/tmp' }),
    /rateLimits\.authPerMinutePerDevice/
  );
  assert.throws(
    () => validateConfig(baseConfig({ rateLimits: { mystery: 1 } }), { configDir: '/tmp' }),
    /unknown configuration key/
  );
  assert.throws(
    () => validateConfig(baseConfig({ auditMaxBytes: 0 }), { configDir: '/tmp' }),
    /auditMaxBytes/
  );

  const config = validateConfig(
    baseConfig({ rateLimits: { wakesPerMinutePerDevice: 2 } }),
    { configDir: '/tmp' }
  );
  assert.equal(config.rateLimits.wakesPerMinutePerDevice, 2);
  assert.equal(config.rateLimits.authPerMinutePerDevice, 60);
});

test('resolvePublicHost prefers explicit publicHost', () => {
  const config = validateConfig(baseConfig({ publicHost: 'bridge.tailnet.ts.net' }), { configDir: '/tmp' });
  assert.equal(resolvePublicHost(config), 'bridge.tailnet.ts.net');
});

test('loadConfig reports unreadable or invalid files clearly', () => {
  assert.throws(() => loadConfig(path.join(HERE, 'does-not-exist.json')), /cannot read config file/);
  assert.throws(() => validateConfig({}, { configDir: '/tmp' }), ConfigError);
});

test('TLS credentials are required and the reported pin is the DER SHA-256', () => {
  const config = validateConfig(
    baseConfig({ tlsCertPath: FIXTURE_CERT, tlsKeyPath: FIXTURE_KEY }),
    { configDir: '/tmp' }
  );
  const credentials = loadTlsCredentials(config);
  assert.match(credentials.fingerprint, /^[0-9a-f]{64}$/);
  assert.equal(credentials.fingerprint, certificateFingerprint());
  assert.match(credentials.subject, /pcwake-bridge-test/);

  const missing = validateConfig(
    baseConfig({ tlsCertPath: path.join(HERE, 'missing-cert.pem') }),
    { configDir: '/tmp' }
  );
  assert.throws(() => loadTlsCredentials(missing), /cannot read TLS certificate/);
});
