#!/usr/bin/env node
import http from 'node:http';
import process from 'node:process';

import { VERSION, loadConfig } from './config.js';
import { readAdminRecord } from './state.js';

const HELP = `PC Wake Bridge management CLI ${VERSION}

Usage:
  node src/cli.js [--config <path>] <command> [options]

Commands:
  pair [--label <text>]   Open a 5-minute single-use pairing window and print
                          the pairing payload JSON line (for QR/paste).
  status                  Show bridge status, fingerprint and pairing window.
  devices                 List paired devices.
  revoke <deviceId>       Revoke a paired device by its 32-hex device id.

Global options:
  --config, -c <path>     Path to config.json (default: ./config.json)
  --help, -h              Show this help and exit
  --version               Print version and exit

The CLI talks only to the loopback-only admin endpoint using the token in
<stateDir>/admin.json. The bridge service must be running.
`;

class CliError extends Error {}

function parseArgs(argv) {
  const options = {
    config: process.env.PCWAKE_CONFIG || 'config.json',
    command: null,
    label: '',
    deviceId: null,
    help: false,
    version: false,
  };
  const rest = [];
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--help' || arg === '-h') {
      options.help = true;
    } else if (arg === '--version') {
      options.version = true;
    } else if (arg === '--config' || arg === '-c') {
      index += 1;
      if (index >= argv.length) throw new CliError(`${arg} requires a path`);
      options.config = argv[index];
    } else if (arg.startsWith('--config=')) {
      options.config = arg.slice('--config='.length);
    } else if (arg === '--label') {
      index += 1;
      if (index >= argv.length) throw new CliError('--label requires a value');
      options.label = argv[index];
    } else if (arg.startsWith('--label=')) {
      options.label = arg.slice('--label='.length);
    } else if (arg.startsWith('-')) {
      throw new CliError(`unknown option: ${arg}`);
    } else {
      rest.push(arg);
    }
  }
  options.command = rest[0] ?? null;
  if (options.command === 'revoke') options.deviceId = rest[1] ?? null;
  if (rest.length > (options.command === 'revoke' ? 2 : 1)) {
    throw new CliError(`unexpected argument: ${rest[options.command === 'revoke' ? 2 : 1]}`);
  }
  return options;
}

function adminRequest({ port, token, method, path, body }) {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? null : Buffer.from(JSON.stringify(body));
    const headers = {
      Authorization: `Bearer ${token}`,
      Accept: 'application/json',
      Connection: 'close',
    };
    if (data) {
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = data.length;
    }
    const req = http.request(
      {
        host: '127.0.0.1',
        port,
        method,
        path,
        headers,
        agent: false,
        timeout: 5000,
      },
      (res) => {
        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        res.on('end', () => {
          const raw = Buffer.concat(chunks).toString('utf8');
          let parsed = null;
          try {
            parsed = raw ? JSON.parse(raw) : null;
          } catch {
            reject(new CliError(`admin endpoint returned invalid JSON (HTTP ${res.statusCode})`));
            return;
          }
          if (res.statusCode < 200 || res.statusCode >= 300) {
            reject(new CliError(`admin request failed (HTTP ${res.statusCode}): ${parsed?.error ?? 'unknown error'}`));
            return;
          }
          resolve(parsed);
        });
      }
    );
    req.on('timeout', () => req.destroy(new Error('timed out connecting to the admin endpoint')));
    req.on('error', (error) => {
      reject(new CliError(`cannot reach the bridge admin endpoint on 127.0.0.1:${port}: ${error.message}`));
    });
    if (data) req.write(data);
    req.end();
  });
}

function validatePayload(payload) {
  if (!payload || typeof payload !== 'object') throw new CliError('admin response is missing the pairing payload');
  const required = ['v', 'kind', 'name', 'host', 'port', 'fingerprint', 'code'];
  for (const field of required) {
    if (payload[field] === undefined) throw new CliError(`pairing payload is missing ${field}`);
  }
  return payload;
}

async function pairCommand(options, config, admin) {
  const response = await adminRequest({
    ...admin,
    method: 'POST',
    path: '/admin/pair',
    body: options.label ? { label: options.label } : {},
  });
  const payload = validatePayload(response.payload);
  process.stdout.write(`${JSON.stringify(payload)}\n`);
  process.stdout.write('\n');
  process.stdout.write('Pairing window open: 5 minutes, single use.\n');
  process.stdout.write(`Bridge: ${config.name} (${payload.host}:${payload.port})\n`);
  process.stdout.write(`Certificate SHA-256 fingerprint: ${payload.fingerprint}\n`);
  process.stdout.write(`Pairing code: ${payload.code}\n`);
  process.stdout.write(`Expires: ${new Date(response.expiresAt).toISOString()}\n`);
  if (options.label) process.stdout.write(`Label: ${options.label}\n`);
  process.stdout.write('\n');
  process.stdout.write('In the PC Wake Controller Android app, choose Pair, then scan or paste the\n');
  process.stdout.write('single JSON line above. The code is consumed on success and invalidated\n');
  process.stdout.write('after 5 failed attempts. Do not share the code or the payload publicly.\n');
}

async function statusCommand(admin) {
  const response = await adminRequest({ ...admin, method: 'GET', path: '/admin/status' });
  process.stdout.write(`${JSON.stringify(response, null, 2)}\n`);
}

async function devicesCommand(admin) {
  const response = await adminRequest({ ...admin, method: 'GET', path: '/admin/devices' });
  process.stdout.write(`${JSON.stringify(response, null, 2)}\n`);
}

async function revokeCommand(options, admin) {
  if (!options.deviceId || !/^[0-9a-f]{32}$/.test(options.deviceId)) {
    throw new CliError('revoke requires a 32-character lowercase hex device id');
  }
  const response = await adminRequest({
    ...admin,
    method: 'POST',
    path: '/admin/revoke',
    body: { deviceId: options.deviceId },
  });
  process.stdout.write(`${JSON.stringify(response)}\n`);
  process.stdout.write(`Revoked device ${options.deviceId}.\n`);
}

async function main() {
  let options;
  try {
    options = parseArgs(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`${error.message}\n\n${HELP}`);
    process.exitCode = 1;
    return;
  }

  if (options.help) {
    process.stdout.write(HELP);
    return;
  }
  if (options.version) {
    process.stdout.write(`${VERSION}\n`);
    return;
  }
  if (!options.command) {
    process.stderr.write(`no command given\n\n${HELP}`);
    process.exitCode = 1;
    return;
  }
  if (!['pair', 'status', 'devices', 'revoke'].includes(options.command)) {
    process.stderr.write(`unknown command: ${options.command}\n\n${HELP}`);
    process.exitCode = 1;
    return;
  }

  try {
    const config = loadConfig(options.config);
    const adminRecord = readAdminRecord(config.stateDir);
    const admin = { port: adminRecord.adminPort, token: adminRecord.adminToken };

    if (options.command === 'pair') await pairCommand(options, config, admin);
    else if (options.command === 'status') await statusCommand(admin);
    else if (options.command === 'devices') await devicesCommand(admin);
    else if (options.command === 'revoke') await revokeCommand(options, admin);
  } catch (error) {
    process.stderr.write(`error: ${error.message}\n`);
    process.exitCode = 1;
  }
}

void main();
