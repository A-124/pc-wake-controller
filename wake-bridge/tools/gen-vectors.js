#!/usr/bin/env node
/**
 * Regenerate the normative test vectors from PROTOCOL.md section 8.
 * Zero dependencies; safe to run at any time.
 */
import { bodySha256, canonicalString, computeSignature } from '../src/auth.js';

const token = 'test-token-0123456789';
const nonce = 'AAECAwQFBgcICQoLDA0ODw';

const requestA = {
  method: 'POST',
  path: '/api/v1/power/shutdown',
  timestamp: '1720000000',
  nonce,
  body: Buffer.from('{"graceSeconds":15}', 'utf8'),
};
const canonicalA = canonicalString(requestA);
const signatureA = computeSignature(token, canonicalA);

const requestB = {
  method: 'GET',
  path: '/api/v1/status',
  timestamp: '1720000000',
  nonce,
  body: Buffer.alloc(0),
};
const canonicalB = canonicalString(requestB);
const signatureB = computeSignature(token, canonicalB);

const output = {
  token,
  requestA: {
    method: requestA.method,
    path: requestA.path,
    timestamp: requestA.timestamp,
    nonce: requestA.nonce,
    body: requestA.body.toString('utf8'),
    bodySha256: bodySha256(requestA.body),
    canonical: canonicalA,
    signature: signatureA,
  },
  requestB: {
    method: requestB.method,
    path: requestB.path,
    timestamp: requestB.timestamp,
    nonce: requestB.nonce,
    body: '',
    bodySha256: bodySha256(requestB.body),
    canonical: canonicalB,
    signature: signatureB,
  },
};

process.stdout.write(`${JSON.stringify(output, null, 2)}\n`);
