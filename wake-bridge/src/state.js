import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { randomToken, safeEqualStrings, sha256Hex } from './util.js';

export const STATE_VERSION = 1;

const DEVICE_FIELDS = ['id', 'tokenHash', 'name', 'model', 'appVersion', 'createdAt', 'lastSeenAt', 'revokedAt'];

function atomicWriteJson(file, value, mode = 0o600) {
  const directory = path.dirname(file);
  const temporary = path.join(
    directory,
    `.${path.basename(file)}.${process.pid}.${crypto.randomBytes(4).toString('hex')}.tmp`
  );
  const data = `${JSON.stringify(value, null, 2)}\n`;
  try {
    fs.writeFileSync(temporary, data, { mode });
    fs.chmodSync(temporary, mode);
    fs.renameSync(temporary, file);
    fs.chmodSync(file, mode);
  } catch (error) {
    try {
      fs.unlinkSync(temporary);
    } catch {
      // ignore cleanup failure
    }
    throw error;
  }
}

function assertFiniteTime(value, field) {
  if (value !== null && (!Number.isInteger(value) || value < 0)) {
    throw new Error(`state file is corrupt: ${field} must be null or a non-negative integer`);
  }
}

function normalizeDevice(raw, index) {
  const field = `devices[${index}]`;
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
    throw new Error(`state file is corrupt: ${field} must be an object`);
  }
  if (typeof raw.id !== 'string' || !/^[0-9a-f]{32}$/.test(raw.id)) {
    throw new Error(`state file is corrupt: ${field}.id must be 32 lowercase hex characters`);
  }
  if (typeof raw.tokenHash !== 'string' || !/^[0-9a-f]{64}$/.test(raw.tokenHash)) {
    throw new Error(`state file is corrupt: ${field}.tokenHash must be 64 lowercase hex characters`);
  }
  const device = {
    id: raw.id,
    tokenHash: raw.tokenHash,
    name: typeof raw.name === 'string' ? raw.name : 'Unknown device',
    model: typeof raw.model === 'string' ? raw.model : '',
    appVersion: typeof raw.appVersion === 'string' ? raw.appVersion : '',
    createdAt: raw.createdAt,
    lastSeenAt: raw.lastSeenAt ?? null,
    revokedAt: raw.revokedAt ?? null,
  };
  assertFiniteTime(device.createdAt, `${field}.createdAt`);
  assertFiniteTime(device.lastSeenAt, `${field}.lastSeenAt`);
  assertFiniteTime(device.revokedAt, `${field}.revokedAt`);
  if (device.createdAt === null) {
    throw new Error(`state file is corrupt: ${field}.createdAt is required`);
  }
  return device;
}

/**
 * Persistent bridge state.
 *
 * devices.json contains only device metadata and SHA-256 token hashes. Raw
 * tokens are never written to disk. The token key is derived from the admin
 * token in admin.json (mode 0600), which lets the bridge verify HMACs after a
 * restart without persisting raw bearer tokens.
 */
export class StateStore {
  constructor({ stateDir, nowMs = Date.now, logger = null } = {}) {
    if (!stateDir) throw new Error('StateStore requires stateDir');
    this.stateDir = path.resolve(stateDir);
    this.nowMs = nowMs;
    this.logger = logger;
    this.devicesFile = path.join(this.stateDir, 'devices.json');
    this.adminFile = path.join(this.stateDir, 'admin.json');
    this.devices = new Map();
    this.adminRecord = null;

    this.ensureDirectory();
    this.loadAdmin();
    this.loadDevices();
  }

  ensureDirectory() {
    fs.mkdirSync(this.stateDir, { recursive: true, mode: 0o700 });
    try {
      fs.chmodSync(this.stateDir, 0o700);
    } catch (error) {
      this.logger?.warn?.('could not set state directory mode to 0700', { error: error.message });
    }
  }

  loadAdmin() {
    let raw;
    if (fs.existsSync(this.adminFile)) {
      try {
        raw = JSON.parse(fs.readFileSync(this.adminFile, 'utf8'));
      } catch (error) {
        throw new Error(`cannot read ${this.adminFile}: ${error.message}`);
      }
    } else {
      raw = {
        version: STATE_VERSION,
        adminToken: randomToken(32),
        adminPort: null,
        createdAt: Math.floor(this.nowMs() / 1000),
      };
      atomicWriteJson(this.adminFile, raw, 0o600);
    }

    if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
      throw new Error(`${this.adminFile} is corrupt: expected a JSON object`);
    }
    if (typeof raw.adminToken !== 'string' || !/^[A-Za-z0-9_-]{32,128}$/.test(raw.adminToken)) {
      throw new Error(`${this.adminFile} is corrupt: adminToken is missing or invalid`);
    }
    if (raw.adminPort !== null && raw.adminPort !== undefined && (!Number.isInteger(raw.adminPort) || raw.adminPort < 0 || raw.adminPort > 65535)) {
      throw new Error(`${this.adminFile} is corrupt: adminPort is invalid`);
    }
    this.adminRecord = {
      version: STATE_VERSION,
      adminToken: raw.adminToken,
      adminPort: raw.adminPort ?? null,
      createdAt: Number.isInteger(raw.createdAt) ? raw.createdAt : Math.floor(this.nowMs() / 1000),
    };
    try {
      fs.chmodSync(this.adminFile, 0o600);
    } catch (error) {
      this.logger?.warn?.('could not set admin.json mode to 0600', { error: error.message });
    }
  }

  loadDevices() {
    if (!fs.existsSync(this.devicesFile)) {
      this.saveDevices();
      return;
    }
    let raw;
    try {
      raw = JSON.parse(fs.readFileSync(this.devicesFile, 'utf8'));
    } catch (error) {
      throw new Error(`cannot read ${this.devicesFile}: ${error.message}`);
    }
    if (typeof raw !== 'object' || raw === null || !Array.isArray(raw.devices)) {
      throw new Error(`${this.devicesFile} is corrupt: expected an object with a devices array`);
    }
    this.devices.clear();
    raw.devices.forEach((entry, index) => {
      const device = normalizeDevice(entry, index);
      if (this.devices.has(device.id)) {
        throw new Error(`${this.devicesFile} is corrupt: duplicate device id ${device.id}`);
      }
      this.devices.set(device.id, device);
    });
    try {
      fs.chmodSync(this.devicesFile, 0o600);
    } catch (error) {
      this.logger?.warn?.('could not set devices.json mode to 0600', { error: error.message });
    }
  }

  saveDevices() {
    const devices = [...this.devices.values()].map((device) => {
      const output = {};
      for (const field of DEVICE_FIELDS) output[field] = device[field];
      return output;
    });
    atomicWriteJson(this.devicesFile, { version: STATE_VERSION, devices }, 0o600);
  }

  saveAdmin() {
    atomicWriteJson(this.adminFile, this.adminRecord, 0o600);
  }

  getAdminToken() {
    return this.adminRecord.adminToken;
  }

  getAdminPort() {
    return this.adminRecord.adminPort;
  }

  setAdminPort(port) {
    if (!Number.isInteger(port) || port < 0 || port > 65535) {
      throw new Error(`invalid admin port: ${port}`);
    }
    if (this.adminRecord.adminPort === port) return;
    this.adminRecord.adminPort = port;
    this.saveAdmin();
  }

  nowSeconds() {
    return Math.floor(this.nowMs() / 1000);
  }

  /**
   * Derive the 32-byte device token from the administrative secret and the
   * device id. This preserves the protocol guarantee that only token hashes
   * are persisted while still allowing HMAC verification after a restart.
   */
  deriveToken(deviceId) {
    if (typeof deviceId !== 'string' || !/^[0-9a-f]{32}$/.test(deviceId)) {
      throw new Error('invalid device id for token derivation');
    }
    const rootKey = crypto
      .createHmac('sha256', this.adminRecord.adminToken)
      .update('pcwake-bridge/token-key/v1')
      .digest();
    return crypto
      .createHmac('sha256', rootKey)
      .update(`pcwake-bridge/device-token/v1\u0000${deviceId}`)
      .digest('base64url');
  }

  tokenMatches(device, token) {
    if (!device || typeof token !== 'string') return false;
    return safeEqualStrings(sha256Hex(token), device.tokenHash);
  }

  createDevice({ name, model = '', appVersion = '' }) {
    let id;
    do {
      id = crypto.randomBytes(16).toString('hex');
    } while (this.devices.has(id));

    const token = this.deriveToken(id);
    const device = {
      id,
      tokenHash: sha256Hex(token),
      name: typeof name === 'string' && name ? name : 'Unknown device',
      model: typeof model === 'string' ? model : '',
      appVersion: typeof appVersion === 'string' ? appVersion : '',
      createdAt: this.nowSeconds(),
      lastSeenAt: null,
      revokedAt: null,
    };
    this.devices.set(id, device);
    this.saveDevices();
    return { device: { ...device }, token };
  }

  getDevice(deviceId) {
    const device = this.devices.get(deviceId);
    return device ? { ...device } : null;
  }

  markSeen(deviceId) {
    const device = this.devices.get(deviceId);
    if (!device || device.revokedAt !== null) return false;
    const now = this.nowSeconds();
    if (device.lastSeenAt === now) return true;
    device.lastSeenAt = now;
    this.saveDevices();
    return true;
  }

  revokeDevice(deviceId) {
    const device = this.devices.get(deviceId);
    if (!device) return false;
    if (device.revokedAt !== null) return true;
    device.revokedAt = this.nowSeconds();
    this.saveDevices();
    return true;
  }

  listDevices() {
    return [...this.devices.values()]
      .map((device) => ({
        id: device.id,
        name: device.name,
        model: device.model,
        appVersion: device.appVersion,
        createdAt: device.createdAt,
        lastSeenAt: device.lastSeenAt,
        revokedAt: device.revokedAt,
      }))
      .sort((a, b) => a.createdAt - b.createdAt);
  }

  counts() {
    let active = 0;
    for (const device of this.devices.values()) {
      if (device.revokedAt === null) active += 1;
    }
    return { total: this.devices.size, active };
  }
}

/** Read admin.json without creating or mutating state (used by the CLI). */
export function readAdminRecord(stateDir) {
  const file = path.join(path.resolve(stateDir), 'admin.json');
  let raw;
  try {
    raw = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`cannot read admin credentials from ${file}: ${error.message}`);
  }
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
    throw new Error(`${file} is corrupt: expected a JSON object`);
  }
  if (typeof raw.adminToken !== 'string' || !/^[A-Za-z0-9_-]{32,128}$/.test(raw.adminToken)) {
    throw new Error(`${file} is corrupt: adminToken is missing or invalid`);
  }
  if (!Number.isInteger(raw.adminPort) || raw.adminPort < 1 || raw.adminPort > 65535) {
    throw new Error(
      `${file} does not contain a running admin port; start the Wake Bridge service first`
    );
  }
  return { adminToken: raw.adminToken, adminPort: raw.adminPort };
}
