import crypto from 'node:crypto';
import fs from 'node:fs';
import https from 'node:https';
import tls from 'node:tls';

import {
  NonceCache,
  verifyAuthenticatedRequest,
} from './auth.js';
import { VERSION } from './config.js';
import { HttpError, clientIp, parseJsonObject, readBody, sendError, sendJson } from './http-util.js';
import { silentLogger } from './logger.js';
import { WindowCounter } from './rate-limit.js';
import { parseMac, clampInteger, hasControlCharacters } from './util.js';
import { sendMagicPacket as defaultSendMagicPacket } from './wol.js';

const MAX_BODY_BYTES = 64 * 1024;
const RATE_WINDOW_MS = 60_000;
const DEVICE_ID_RE = /^[0-9a-f]{32}$/;

/**
 * Load and validate the TLS certificate/key. Refuses to resolve when either
 * file is missing, unreadable, malformed or mismatched.
 */
export function loadTlsCredentials(config) {
  let cert;
  let key;
  try {
    cert = fs.readFileSync(config.tlsCertPath, 'utf8');
  } catch (error) {
    throw new Error(`cannot read TLS certificate ${config.tlsCertPath}: ${error.message}`);
  }
  try {
    key = fs.readFileSync(config.tlsKeyPath, 'utf8');
  } catch (error) {
    throw new Error(`cannot read TLS private key ${config.tlsKeyPath}: ${error.message}`);
  }
  if (!cert.trim()) throw new Error(`TLS certificate file ${config.tlsCertPath} is empty`);
  if (!key.trim()) throw new Error(`TLS private key file ${config.tlsKeyPath} is empty`);

  let certificate;
  try {
    certificate = new crypto.X509Certificate(cert);
  } catch (error) {
    throw new Error(`TLS certificate ${config.tlsCertPath} is invalid: ${error.message}`);
  }
  try {
    tls.createSecureContext({ cert, key, minVersion: 'TLSv1.2' });
  } catch (error) {
    throw new Error(`TLS certificate/key pair is invalid: ${error.message}`);
  }

  return {
    cert,
    key,
    fingerprint: crypto.createHash('sha256').update(certificate.raw).digest('hex'),
    subject: certificate.subject,
    validTo: certificate.validTo,
  };
}

function validateDeviceMetadata(body) {
  const clean = (value, field, max, fallback) => {
    if (value === undefined || value === null) return fallback;
    if (typeof value !== 'string') throw new HttpError(400, 'invalid_request');
    const trimmed = value.trim();
    if (trimmed.length > max || hasControlCharacters(trimmed)) {
      throw new HttpError(400, 'invalid_request');
    }
    return trimmed || fallback;
  };
  return {
    name: clean(body.deviceName, 'deviceName', 64, 'Unknown device'),
    model: clean(body.deviceModel, 'deviceModel', 128, ''),
    appVersion: clean(body.appVersion, 'appVersion', 32, ''),
  };
}

export async function startBridgeServer({
  config,
  state,
  pairing,
  audit,
  logger = silentLogger,
  clock = { nowMs: Date.now },
  sendMagicPacketFn = defaultSendMagicPacket,
  wakeIntervalMs = 100,
  limiters = null,
}) {
  const credentials = loadTlsCredentials(config);
  const nowMs = () => clock.nowMs();
  const targetsByMac = new Map(config.targets.map((target) => [target.mac, target]));

  const counters = limiters ?? {
    authFailures: new WindowCounter({
      limit: config.rateLimits.authFailuresPerMinutePerIp,
      windowMs: RATE_WINDOW_MS,
      nowMs,
    }),
    authenticatedRequests: new WindowCounter({
      limit: config.rateLimits.authPerMinutePerDevice,
      windowMs: RATE_WINDOW_MS,
      nowMs,
    }),
    wakes: new WindowCounter({
      limit: config.rateLimits.wakesPerMinutePerDevice,
      windowMs: RATE_WINDOW_MS,
      nowMs,
    }),
    pairAttempts: new WindowCounter({
      limit: config.rateLimits.pairAttemptsPerMinutePerIp,
      windowMs: RATE_WINDOW_MS,
      nowMs,
    }),
  };
  const nonceCache = new NonceCache({ nowMs });

  const pairServerInfo = () => ({
    kind: 'bridge',
    name: config.name,
    version: VERSION,
    targets: config.targets.map((target) => ({
      id: target.id,
      name: target.name,
      mac: target.mac,
    })),
  });

  function auditAuthFailure(req, ip, reason, extra = {}) {
    const deviceHeader = req.headers['x-pcwake-device'];
    audit.log('auth_failed', {
      ip,
      reason,
      path: req.url,
      deviceId: typeof deviceHeader === 'string' && DEVICE_ID_RE.test(deviceHeader) ? deviceHeader : null,
      ...extra,
    });
  }

  function authenticate({ req, method, path, body, nowSeconds, ip }) {
    if (counters.authFailures.isLimited(ip)) {
      audit.log('auth_rate_limited', { ip, path });
      return { ok: false, rateLimited: true };
    }

    const rawDeviceId = req.headers['x-pcwake-device'];
    const deviceId = typeof rawDeviceId === 'string' && DEVICE_ID_RE.test(rawDeviceId)
      ? rawDeviceId
      : null;
    const device = deviceId ? state.getDevice(deviceId) : null;

    let result;
    if (!device) {
      result = { ok: false, reason: 'unknown_device' };
    } else if (device.revokedAt !== null) {
      result = { ok: false, reason: 'revoked_device' };
    } else {
      const token = state.deriveToken(device.id);
      if (!state.tokenMatches(device, token)) {
        result = { ok: false, reason: 'token_mismatch' };
      } else {
        result = verifyAuthenticatedRequest({
          token,
          headers: req.headers,
          method,
          path,
          body,
          nowSeconds,
          nowMs: nowMs(),
          nonceCache,
        });
      }
    }

    if (!result.ok) {
      counters.authFailures.hit(ip);
      auditAuthFailure(req, ip, result.reason);
      return { ok: false, deviceId: device?.id ?? null };
    }

    const count = counters.authenticatedRequests.hit(device.id);
    if (count > config.rateLimits.authPerMinutePerDevice) {
      audit.log('auth_rate_limited', { ip, deviceId: device.id, path });
      return { ok: false, rateLimited: true, deviceId: device.id };
    }

    state.markSeen(device.id);
    return { ok: true, device, ip };
  }

  async function handlePair(req, res, { ip, nowSeconds }) {
    const pairCount = counters.pairAttempts.hit(ip);
    if (pairCount > config.rateLimits.pairAttemptsPerMinutePerIp) {
      audit.log('pairing_failed', { ip, reason: 'rate_limited' });
      sendError(res, 429, 'rate_limited');
      return;
    }

    let body;
    try {
      body = parseJsonObject(await readBody(req, { limit: MAX_BODY_BYTES }));
    } catch (error) {
      if (error instanceof HttpError) {
        audit.log('pairing_failed', { ip, reason: 'invalid_request' });
        sendError(res, error.status, error.code);
        return;
      }
      throw error;
    }

    if (typeof body.code !== 'string') {
      audit.log('pairing_failed', { ip, reason: 'invalid_request' });
      sendError(res, 400, 'invalid_request');
      return;
    }

    let metadata;
    try {
      metadata = validateDeviceMetadata(body);
    } catch (error) {
      if (error instanceof HttpError) {
        audit.log('pairing_failed', { ip, reason: 'invalid_request' });
        sendError(res, error.status, error.code);
        return;
      }
      throw error;
    }

    const result = pairing.attempt(body.code);
    if (!result.ok) {
      audit.log('pairing_failed', { ip, reason: result.error, status: result.status });
      sendError(res, result.status, result.error);
      return;
    }

    let issued;
    try {
      issued = state.createDevice(metadata);
    } catch (error) {
      logger.error('failed to persist paired device', { error });
      audit.log('pairing_failed', { ip, reason: 'state_write_failed' });
      sendError(res, 500, 'internal_error');
      return;
    }

    audit.log('pairing_success', {
      ip,
      deviceId: issued.device.id,
      deviceName: issued.device.name,
    });
    sendJson(res, 200, {
      ok: true,
      deviceId: issued.device.id,
      token: issued.token,
      server: pairServerInfo(),
    });
  }

  async function handleWake(req, res, { device, ip, body, nowSeconds }) {
    const wakeCount = counters.wakes.hit(device.id);
    if (wakeCount > config.rateLimits.wakesPerMinutePerDevice) {
      audit.log('wake', {
        deviceId: device.id,
        ip,
        mac: null,
        target: null,
        repeats: null,
        result: 'rejected',
        reason: 'rate_limited',
      });
      sendError(res, 429, 'rate_limited');
      return;
    }

    let payload;
    try {
      payload = parseJsonObject(body);
    } catch {
      audit.log('wake', {
        deviceId: device.id,
        ip,
        mac: null,
        target: null,
        repeats: null,
        result: 'rejected',
        reason: 'invalid_request',
      });
      sendError(res, 400, 'invalid_request');
      return;
    }

    if (typeof payload.mac !== 'string') {
      audit.log('wake', {
        deviceId: device.id,
        ip,
        mac: null,
        target: null,
        repeats: null,
        result: 'rejected',
        reason: 'invalid_request',
      });
      sendError(res, 400, 'invalid_request');
      return;
    }

    const mac = parseMac(payload.mac);
    const target = mac ? targetsByMac.get(mac) : null;
    if (!target) {
      audit.log('wake', {
        deviceId: device.id,
        ip,
        mac: mac ?? payload.mac.slice(0, 64),
        target: null,
        repeats: null,
        result: 'rejected',
        reason: 'target_not_allowed',
      });
      sendError(res, 403, 'target_not_allowed');
      return;
    }

    let repeats;
    if (payload.repeats === undefined) {
      repeats = target.repeats;
    } else if (!Number.isInteger(payload.repeats)) {
      audit.log('wake', {
        deviceId: device.id,
        ip,
        mac,
        target: target.id,
        repeats: null,
        result: 'rejected',
        reason: 'invalid_repeats',
      });
      sendError(res, 400, 'invalid_repeats');
      return;
    } else {
      repeats = clampInteger(payload.repeats, 1, 10);
    }

    let outcome;
    try {
      outcome = await sendMagicPacketFn({
        mac,
        broadcast: target.broadcast,
        port: target.port,
        repeats,
        intervalMs: wakeIntervalMs,
      });
    } catch (error) {
      logger.warn('magic packet send failed', { error, target: target.id });
      audit.log('wake', {
        deviceId: device.id,
        ip,
        mac,
        target: target.id,
        repeats,
        result: 'rejected',
        reason: 'send_failed',
        sent: 0,
      });
      sendError(res, 502, 'send_failed');
      return;
    }

    const sent = outcome?.sent ?? 0;
    if (sent === 0) {
      audit.log('wake', {
        deviceId: device.id,
        ip,
        mac,
        target: target.id,
        repeats,
        result: 'rejected',
        reason: 'send_failed',
        sent: 0,
      });
      sendError(res, 502, 'send_failed');
      return;
    }

    audit.log('wake', {
      deviceId: device.id,
      ip,
      mac,
      target: target.id,
      repeats,
      sent,
      result: 'accepted',
      partial: Boolean(outcome?.failed),
    });
    sendJson(res, 200, {
      ok: true,
      target: target.id,
      sent,
      port: target.port,
      broadcast: target.broadcast,
      at: nowSeconds,
    });
  }

  function handleStatus(res, { nowSeconds }) {
    sendJson(res, 200, {
      ok: true,
      name: config.name,
      kind: 'bridge',
      version: VERSION,
      online: true,
      time: nowSeconds,
      targets: config.targets.map((target) => ({
        id: target.id,
        name: target.name,
        mac: target.mac,
        broadcast: target.broadcast,
        port: target.port,
      })),
    });
  }

  function handleRevoke(res, { device, ip }) {
    state.revokeDevice(device.id);
    audit.log('revoke', { ip, deviceId: device.id, via: 'api' });
    sendJson(res, 200, { ok: true });
  }

  async function handler(req, res) {
    const now = nowMs();
    const nowSeconds = Math.floor(now / 1000);
    const ip = clientIp(req);
    const url = req.url || '/';
    const method = (req.method || 'GET').toUpperCase();
    const pathname = url.split('?')[0];

    try {
      if (pathname === '/health') {
        if (method !== 'GET') {
          sendError(res, 405, 'method_not_allowed');
          return;
        }
        sendJson(res, 200, {
          ok: true,
          service: 'pcwake-bridge',
          version: VERSION,
          kind: 'bridge',
        });
        return;
      }

      if (pathname === '/api/v1/pair') {
        if (method !== 'POST') {
          sendError(res, 405, 'method_not_allowed');
          return;
        }
        await handlePair(req, res, { ip, nowSeconds });
        return;
      }

      if (pathname.startsWith('/api/v1/')) {
        const body = await readBody(req, { limit: MAX_BODY_BYTES });
        const auth = authenticate({ req, method, path: url, body, nowSeconds, ip });
        if (!auth.ok) {
          if (pathname === '/api/v1/wake') {
            audit.log('wake', {
              deviceId: auth.deviceId ?? null,
              ip,
              mac: null,
              target: null,
              repeats: null,
              result: 'rejected',
              reason: auth.rateLimited ? 'rate_limited' : 'unauthorized',
            });
          }
          sendError(res, auth.rateLimited ? 429 : 401, auth.rateLimited ? 'rate_limited' : 'unauthorized');
          return;
        }

        if (pathname === '/api/v1/status') {
          if (method !== 'GET') {
            sendError(res, 405, 'method_not_allowed');
            return;
          }
          handleStatus(res, { nowSeconds });
          return;
        }
        if (pathname === '/api/v1/wake') {
          if (method !== 'POST') {
            audit.log('wake', {
              deviceId: auth.device.id,
              ip,
              mac: null,
              target: null,
              repeats: null,
              result: 'rejected',
              reason: 'method_not_allowed',
            });
            sendError(res, 405, 'method_not_allowed');
            return;
          }
          await handleWake(req, res, { device: auth.device, ip, body, nowSeconds });
          return;
        }
        if (pathname === '/api/v1/revoke') {
          if (method !== 'POST') {
            sendError(res, 405, 'method_not_allowed');
            return;
          }
          handleRevoke(res, { device: auth.device, ip });
          return;
        }
        sendError(res, 404, 'not_found');
        return;
      }

      sendError(res, 404, 'not_found');
    } catch (error) {
      if (error instanceof HttpError) {
        sendError(res, error.status, error.code);
        return;
      }
      logger.error('request handler failed', { error, path: pathname });
      if (!res.headersSent) sendError(res, 500, 'internal_error');
      else res.destroy();
    }
  }

  const server = https.createServer(
    {
      cert: credentials.cert,
      key: credentials.key,
      minVersion: 'TLSv1.2',
    },
    (req, res) => {
      handler(req, res).catch((error) => {
        logger.error('unhandled request error', { error });
        if (!res.headersSent) sendError(res, 500, 'internal_error');
        else res.destroy();
      });
    }
  );
  server.requestTimeout = 20_000;
  server.headersTimeout = 10_000;
  server.keepAliveTimeout = 5_000;
  server.on('clientError', (error, socket) => {
    if (socket.writable) {
      socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');
    }
  });

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(config.listenPort, config.listenHost, () => {
      server.off('error', reject);
      resolve();
    });
  });

  const address = server.address();
  const port = typeof address === 'object' && address ? address.port : config.listenPort;
  logger.info('https listening', {
    host: config.listenHost,
    port,
    fingerprint: credentials.fingerprint,
    targets: config.targets.length,
  });

  return {
    server,
    port,
    fingerprint: credentials.fingerprint,
    subject: credentials.subject,
    validTo: credentials.validTo,
    counters,
    nonceCache,
    close: async () => {
      server.closeAllConnections?.();
      await new Promise((resolve) => server.close(() => resolve()));
    },
  };
}
