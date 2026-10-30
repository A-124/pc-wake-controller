import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { normalizeMac, isPlainObject, hasControlCharacters } from './util.js';

export const VERSION = '1.0.0';

export const DEFAULT_RATE_LIMITS = Object.freeze({
  authPerMinutePerDevice: 60,
  authFailuresPerMinutePerIp: 10,
  wakesPerMinutePerDevice: 20,
  pairAttemptsPerMinutePerIp: 30,
});

const TOP_LEVEL_KEYS = new Set([
  'name',
  'listenHost',
  'listenPort',
  'publicHost',
  'tlsCertPath',
  'tlsKeyPath',
  'stateDir',
  'adminPort',
  'targets',
  'rateLimits',
  'auditMaxBytes',
]);

const TARGET_KEYS = new Set(['id', 'name', 'mac', 'broadcast', 'port', 'repeats']);
const RATE_LIMIT_KEYS = new Set(Object.keys(DEFAULT_RATE_LIMITS));

export class ConfigError extends Error {
  constructor(message) {
    super(message);
    this.name = 'ConfigError';
  }
}

function fail(field, message) {
  throw new ConfigError(`${field}: ${message}`);
}

function assertKnownKeys(object, allowed, field) {
  for (const key of Object.keys(object)) {
    if (!allowed.has(key)) fail(`${field}.${key}`, 'unknown configuration key');
  }
}

function requireString(value, field, { min = 1, max = 255 } = {}) {
  if (typeof value !== 'string') fail(field, 'must be a string');
  if (value.length < min || value.length > max) {
    fail(field, `must be ${min}..${max} characters long`);
  }
  if (hasControlCharacters(value)) fail(field, 'must not contain control characters');
  return value;
}

function validateDisplayName(value, field) {
  const name = requireString(value, field, { min: 1, max: 64 }).trim();
  if (!name) fail(field, 'must not be empty');
  return name;
}

function isHostname(value) {
  if (net.isIP(value)) return true;
  if (value.length > 253) return false;
  return value.split('.').every((label) => {
    return (
      label.length >= 1 &&
      label.length <= 63 &&
      /^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$/.test(label)
    );
  });
}

function validateHost(value, field) {
  const host = requireString(value, field, { min: 1, max: 253 }).trim();
  if (!isHostname(host)) fail(field, `invalid host name or IP address: ${JSON.stringify(host)}`);
  return host;
}

function validatePort(value, field, { allowZero = true } = {}) {
  if (!Number.isInteger(value) || value < (allowZero ? 0 : 1) || value > 65535) {
    fail(field, `must be an integer in ${allowZero ? '0' : '1'}..65535`);
  }
  return value;
}

function validatePositiveInteger(value, field, max = 1_000_000) {
  if (!Number.isInteger(value) || value < 1 || value > max) {
    fail(field, `must be an integer in 1..${max}`);
  }
  return value;
}

function resolvePath(value, configDir, field) {
  const raw = requireString(value, field, { min: 1, max: 4096 });
  return path.resolve(configDir, raw);
}

function validateTarget(raw, index, seenIds, seenMacs) {
  const field = `targets[${index}]`;
  if (!isPlainObject(raw)) fail(field, 'must be an object');
  assertKnownKeys(raw, TARGET_KEYS, field);

  if (typeof raw.id !== 'string' || !/^[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?$/.test(raw.id)) {
    fail(`${field}.id`, 'must be a lowercase slug of 1..32 characters (a-z, 0-9, hyphen; no leading/trailing hyphen)');
  }
  if (seenIds.has(raw.id)) fail(`${field}.id`, `duplicate target id ${JSON.stringify(raw.id)}`);
  seenIds.add(raw.id);

  const name = validateDisplayName(raw.name, `${field}.name`);
  const mac = normalizeMacOrFail(raw.mac, `${field}.mac`);
  if (seenMacs.has(mac)) fail(`${field}.mac`, `duplicate MAC address ${mac}`);
  seenMacs.add(mac);

  if (typeof raw.broadcast !== 'string' || !net.isIPv4(raw.broadcast)) {
    fail(`${field}.broadcast`, 'must be an IPv4 address');
  }
  if (raw.broadcast === '0.0.0.0') fail(`${field}.broadcast`, '0.0.0.0 is not a usable broadcast address');

  const port = raw.port === undefined ? 9 : validatePort(raw.port, `${field}.port`, { allowZero: false });
  const repeats = raw.repeats === undefined ? 3 : raw.repeats;
  if (!Number.isInteger(repeats) || repeats < 1 || repeats > 10) {
    fail(`${field}.repeats`, 'must be an integer in 1..10');
  }

  return Object.freeze({
    id: raw.id,
    name,
    mac,
    broadcast: raw.broadcast,
    port,
    repeats,
  });
}

function normalizeMacOrFail(value, field) {
  try {
    return normalizeMac(value);
  } catch {
    return fail(field, `invalid MAC address ${JSON.stringify(value)}`);
  }
}

function validateRateLimits(raw) {
  if (raw === undefined) return Object.freeze({ ...DEFAULT_RATE_LIMITS });
  if (!isPlainObject(raw)) fail('rateLimits', 'must be an object');
  assertKnownKeys(raw, RATE_LIMIT_KEYS, 'rateLimits');
  const limits = { ...DEFAULT_RATE_LIMITS };
  for (const key of RATE_LIMIT_KEYS) {
    if (raw[key] !== undefined) {
      limits[key] = validatePositiveInteger(raw[key], `rateLimits.${key}`);
    }
  }
  return Object.freeze(limits);
}

/**
 * Validate and normalize a configuration object. All paths are resolved
 * relative to `configDir` (normally the directory containing config.json).
 */
export function validateConfig(raw, { configDir = process.cwd() } = {}) {
  if (!isPlainObject(raw)) throw new ConfigError('configuration root must be a JSON object');
  assertKnownKeys(raw, TOP_LEVEL_KEYS, 'config');

  const name = raw.name === undefined ? 'Wake Bridge' : validateDisplayName(raw.name, 'name');
  const listenHost = raw.listenHost === undefined ? '0.0.0.0' : validateHost(raw.listenHost, 'listenHost');
  const listenPort = raw.listenPort === undefined ? 8443 : validatePort(raw.listenPort, 'listenPort');
  const publicHost = raw.publicHost === undefined ? null : validateHost(raw.publicHost, 'publicHost');
  const adminPort = raw.adminPort === undefined ? 47111 : validatePort(raw.adminPort, 'adminPort');

  if (raw.tlsCertPath === undefined) fail('tlsCertPath', 'is required (refusing to start without TLS)');
  if (raw.tlsKeyPath === undefined) fail('tlsKeyPath', 'is required (refusing to start without TLS)');
  const tlsCertPath = resolvePath(raw.tlsCertPath, configDir, 'tlsCertPath');
  const tlsKeyPath = resolvePath(raw.tlsKeyPath, configDir, 'tlsKeyPath');
  const stateDir = raw.stateDir === undefined
    ? path.resolve(configDir, 'state')
    : resolvePath(raw.stateDir, configDir, 'stateDir');

  if (raw.targets !== undefined && !Array.isArray(raw.targets)) fail('targets', 'must be an array');
  const seenIds = new Set();
  const seenMacs = new Set();
  const targets = (raw.targets ?? []).map((target, index) => validateTarget(target, index, seenIds, seenMacs));

  const rateLimits = validateRateLimits(raw.rateLimits);
  const auditMaxBytes = raw.auditMaxBytes === undefined
    ? 5 * 1024 * 1024
    : validatePositiveInteger(raw.auditMaxBytes, 'auditMaxBytes', 1024 * 1024 * 1024);

  return Object.freeze({
    name,
    listenHost,
    listenPort,
    publicHost,
    tlsCertPath,
    tlsKeyPath,
    stateDir,
    adminPort,
    targets: Object.freeze(targets),
    rateLimits,
    auditMaxBytes,
    configDir,
    version: VERSION,
  });
}

export function loadConfig(configPath) {
  const absolute = path.resolve(configPath);
  let text;
  try {
    text = fs.readFileSync(absolute, 'utf8');
  } catch (error) {
    throw new ConfigError(`cannot read config file ${absolute}: ${error.message}`);
  }
  let raw;
  try {
    raw = JSON.parse(text);
  } catch (error) {
    throw new ConfigError(`invalid JSON in config file ${absolute}: ${error.message}`);
  }
  return validateConfig(raw, { configDir: path.dirname(absolute) });
}

function isWildcardHost(host) {
  return host === '0.0.0.0' || host === '::' || host === '[::]';
}

/**
 * Determine the address that should be embedded in pairing payloads.
 * Explicit `publicHost` always wins; otherwise derive from the listen
 * address or the first usable external interface.
 */
export function resolvePublicHost(config) {
  if (config.publicHost) return config.publicHost;
  if (config.listenHost === 'localhost') return '127.0.0.1';
  if (!isWildcardHost(config.listenHost)) return config.listenHost.replace(/^\[|\]$/g, '');

  const interfaces = os.networkInterfaces();
  const candidates = [];
  for (const addresses of Object.values(interfaces)) {
    for (const address of addresses ?? []) {
      if (address.internal) continue;
      if (address.family === 'IPv4' || address.family === 4) {
        candidates.push({ address: address.address, priority: 0 });
      } else if (address.family === 'IPv6' || address.family === 6) {
        // Prefer global unicast over link-local.
        const priority = address.address.toLowerCase().startsWith('fe80:') ? 2 : 1;
        candidates.push({ address: address.address.split('%')[0], priority });
      }
    }
  }
  candidates.sort((a, b) => a.priority - b.priority);
  if (candidates.length > 0) return candidates[0].address;
  throw new ConfigError(
    'cannot determine a publicHost: set "publicHost" in config (the listen host is a wildcard address)'
  );
}
