import http from 'node:http';

import { VERSION } from './config.js';
import {
  HttpError,
  clientIp,
  parseJsonObject,
  readBody,
  sendError,
  sendJson,
} from './http-util.js';
import { silentLogger } from './logger.js';
import { isLoopbackAddress, safeEqualStrings, hasControlCharacters } from './util.js';

const ADMIN_HOST = '127.0.0.1';
const MAX_ADMIN_BODY = 8 * 1024;

function extractAdminToken(req) {
  const authorization = req.headers.authorization;
  if (typeof authorization === 'string') {
    const match = /^Bearer\s+(.+)$/i.exec(authorization.trim());
    if (match) return match[1].trim();
  }
  const header = req.headers['x-pcwake-admin'];
  return typeof header === 'string' ? header.trim() : '';
}

function validateLabel(value) {
  if (value === undefined || value === null) return '';
  if (typeof value !== 'string') throw new HttpError(400, 'invalid_request');
  const label = value.trim();
  if (label.length > 64 || hasControlCharacters(label)) throw new HttpError(400, 'invalid_request');
  return label;
}

/**
 * Loopback-only administrative HTTP API used by src/cli.js. This is a
 * separate listener on 127.0.0.1; it is never reachable through the public
 * HTTPS server. Every request requires the admin token from admin.json.
 */
export async function startAdminServer({
  config,
  state,
  pairing,
  audit,
  logger = silentLogger,
  clock = { nowMs: Date.now },
  getPairingInfo,
}) {
  const startedAt = clock.nowMs();
  const nowSeconds = () => Math.floor(clock.nowMs() / 1000);

  async function handler(req, res) {
    const ip = clientIp(req);
    const url = req.url || '/';
    const pathname = url.split('?')[0];
    const method = (req.method || 'GET').toUpperCase();

    try {
      if (!isLoopbackAddress(req.socket?.remoteAddress)) {
        audit.log('admin_rejected', { ip, reason: 'not_loopback' });
        sendError(res, 403, 'forbidden');
        return;
      }

      const suppliedToken = extractAdminToken(req);
      if (!suppliedToken || !safeEqualStrings(suppliedToken, state.getAdminToken())) {
        audit.log('admin_auth_failed', { ip, path: pathname });
        sendError(res, 401, 'unauthorized');
        return;
      }

      if (pathname === '/admin/pair') {
        if (method !== 'POST') {
          sendError(res, 405, 'method_not_allowed');
          return;
        }
        let label = '';
        try {
          const raw = await readBody(req, { limit: MAX_ADMIN_BODY });
          if (raw.length > 0) label = validateLabel(parseJsonObject(raw).label);
        } catch (error) {
          if (error instanceof HttpError) {
            sendError(res, error.status, error.code);
            return;
          }
          throw error;
        }

        let info;
        try {
          info = getPairingInfo();
        } catch (error) {
          logger.error('cannot build pairing payload', { error });
          audit.log('pairing_failed', { ip, reason: 'no_public_host' });
          sendError(res, 500, 'no_public_host');
          return;
        }

        const { code, expiresAt, replaced } = pairing.start({ label });
        const payload = {
          v: 1,
          kind: 'bridge',
          name: config.name,
          host: info.host,
          port: info.port,
          fingerprint: info.fingerprint,
          code,
        };
        audit.log('pairing_started', {
          ip,
          label,
          expiresAt,
          replaced,
        });
        sendJson(res, 200, {
          ok: true,
          code,
          expiresAt,
          expiresInSeconds: Math.max(0, Math.round((expiresAt - clock.nowMs()) / 1000)),
          payload,
        });
        return;
      }

      if (pathname === '/admin/status') {
        if (method !== 'GET') {
          sendError(res, 405, 'method_not_allowed');
          return;
        }
        let info = null;
        try {
          info = getPairingInfo();
        } catch {
          info = null;
        }
        sendJson(res, 200, {
          ok: true,
          service: 'pcwake-bridge',
          version: VERSION,
          kind: 'bridge',
          name: config.name,
          online: true,
          time: nowSeconds(),
          uptimeSeconds: Math.floor((clock.nowMs() - startedAt) / 1000),
          listen: {
            host: config.listenHost,
            port: info?.port ?? config.listenPort,
          },
          fingerprint: info?.fingerprint ?? null,
          targets: config.targets.map((target) => ({
            id: target.id,
            name: target.name,
            mac: target.mac,
            broadcast: target.broadcast,
            port: target.port,
            repeats: target.repeats,
          })),
          devices: state.counts(),
          pairing: pairing.status(),
        });
        return;
      }

      if (pathname === '/admin/devices') {
        if (method !== 'GET') {
          sendError(res, 405, 'method_not_allowed');
          return;
        }
        sendJson(res, 200, { ok: true, devices: state.listDevices() });
        return;
      }

      if (pathname === '/admin/revoke') {
        if (method !== 'POST') {
          sendError(res, 405, 'method_not_allowed');
          return;
        }
        let deviceId;
        try {
          const body = parseJsonObject(await readBody(req, { limit: MAX_ADMIN_BODY }));
          deviceId = body.deviceId;
        } catch (error) {
          if (error instanceof HttpError) {
            sendError(res, error.status, error.code);
            return;
          }
          throw error;
        }
        if (typeof deviceId !== 'string' || !/^[0-9a-f]{32}$/.test(deviceId)) {
          sendError(res, 400, 'invalid_request');
          return;
        }
        if (!state.revokeDevice(deviceId)) {
          sendError(res, 404, 'not_found');
          return;
        }
        audit.log('revoke', { ip, deviceId, via: 'admin' });
        sendJson(res, 200, { ok: true });
        return;
      }

      sendError(res, 404, 'not_found');
    } catch (error) {
      if (error instanceof HttpError) {
        sendError(res, error.status, error.code);
        return;
      }
      logger.error('admin request failed', { error, path: pathname });
      if (!res.headersSent) sendError(res, 500, 'internal_error');
      else res.destroy();
    }
  }

  const server = http.createServer((req, res) => {
    handler(req, res).catch((error) => {
      logger.error('unhandled admin request error', { error });
      if (!res.headersSent) sendError(res, 500, 'internal_error');
      else res.destroy();
    });
  });
  server.requestTimeout = 10_000;
  server.headersTimeout = 5_000;
  server.keepAliveTimeout = 2_000;

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(config.adminPort, ADMIN_HOST, () => {
      server.off('error', reject);
      resolve();
    });
  });

  const address = server.address();
  const port = typeof address === 'object' && address ? address.port : config.adminPort;
  try {
    state.setAdminPort(port);
  } catch (error) {
    server.closeAllConnections?.();
    await new Promise((resolve) => server.close(() => resolve()));
    throw error;
  }
  logger.info('admin listening', { host: ADMIN_HOST, port });

  return {
    server,
    port,
    close: async () => {
      server.closeAllConnections?.();
      await new Promise((resolve) => server.close(() => resolve()));
    },
  };
}
