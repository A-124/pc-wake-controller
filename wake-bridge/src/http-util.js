import { normalizeIpAddress } from './util.js';

export class HttpError extends Error {
  constructor(status, code) {
    super(code);
    this.name = 'HttpError';
    this.status = status;
    this.code = code;
  }
}

export function sendJson(res, status, payload, extraHeaders = {}) {
  if (res.headersSent) return;
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'Content-Length': Buffer.byteLength(body),
    ...extraHeaders,
  });
  res.end(body);
}

export function sendError(res, status, error, extraHeaders = {}) {
  sendJson(res, status, { ok: false, error }, extraHeaders);
}

export function clientIp(req) {
  return normalizeIpAddress(req.socket?.remoteAddress ?? '');
}

/**
 * Read the exact request body bytes (needed for signature verification).
 * Rejects oversized or aborted bodies.
 */
export function readBody(req, { limit = 64 * 1024 } = {}) {
  return new Promise((resolve, reject) => {
    const declared = Number(req.headers['content-length']);
    if (Number.isFinite(declared) && declared > limit) {
      reject(new HttpError(413, 'payload_too_large'));
      return;
    }

    const chunks = [];
    let size = 0;
    let settled = false;

    function finish(error, value) {
      if (settled) return;
      settled = true;
      req.off('data', onData);
      req.off('end', onEnd);
      req.off('error', onError);
      req.off('aborted', onAborted);
      if (error) reject(error);
      else resolve(value);
    }

    function onData(chunk) {
      size += chunk.length;
      if (size > limit) {
        finish(new HttpError(413, 'payload_too_large'));
        return;
      }
      chunks.push(chunk);
    }

    function onEnd() {
      finish(null, Buffer.concat(chunks, size));
    }

    function onError() {
      finish(new HttpError(400, 'bad_request'));
    }

    function onAborted() {
      finish(new HttpError(400, 'bad_request'));
    }

    req.on('data', onData);
    req.on('end', onEnd);
    req.on('error', onError);
    req.on('aborted', onAborted);
  });
}

export function parseJsonObject(body) {
  if (!body || body.length === 0) throw new HttpError(400, 'invalid_request');
  let parsed;
  try {
    parsed = JSON.parse(body.toString('utf8'));
  } catch {
    throw new HttpError(400, 'invalid_request');
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new HttpError(400, 'invalid_request');
  }
  return parsed;
}
