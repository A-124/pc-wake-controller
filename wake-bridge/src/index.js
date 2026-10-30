#!/usr/bin/env node
import process from 'node:process';

import { startAdminServer } from './admin.js';
import { AuditLog } from './audit.js';
import { ConfigError, VERSION, loadConfig, resolvePublicHost } from './config.js';
import { createLogger } from './logger.js';
import { PairingManager } from './pairing.js';
import { startBridgeServer } from './server.js';
import { StateStore } from './state.js';

const HELP = `PC Wake Bridge ${VERSION}

Usage:
  node src/index.js [--config <path>]

Options:
  --config, -c <path>   Path to config.json (default: ./config.json)
  --version             Print version and exit
  --help, -h            Show this help and exit

The bridge always serves HTTPS. There is no plaintext/insecure mode. Use a
self-signed certificate (deploy/gen-cert.sh) or "tailscale cert". Management
commands (pair/status/devices/revoke) are available through src/cli.js.
`;

function parseArgs(argv) {
  const options = { config: process.env.PCWAKE_CONFIG || 'config.json' };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--help' || arg === '-h') {
      options.help = true;
    } else if (arg === '--version') {
      options.version = true;
    } else if (arg === '--config' || arg === '-c') {
      index += 1;
      if (index >= argv.length) throw new ConfigError(`${arg} requires a path`);
      options.config = argv[index];
    } else if (arg.startsWith('--config=')) {
      options.config = arg.slice('--config='.length);
    } else if (arg === '--behind-tailscale') {
      throw new ConfigError(
        '--behind-tailscale is not supported: the bridge always requires TLS. ' +
          'Use "tailscale cert" to obtain a certificate, or terminate TLS in front of it.'
      );
    } else {
      throw new ConfigError(`unknown option: ${arg}`);
    }
  }
  return options;
}

function fatal(message) {
  const record = {
    ts: new Date().toISOString(),
    level: 'error',
    service: 'pcwake-bridge',
    msg: message,
  };
  process.stderr.write(`${JSON.stringify(record)}\n`);
}

async function main() {
  let options;
  try {
    options = parseArgs(process.argv.slice(2));
  } catch (error) {
    fatal(error.message);
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

  let config;
  try {
    config = loadConfig(options.config);
  } catch (error) {
    fatal(error.message);
    process.exitCode = 1;
    return;
  }

  const logger = createLogger({ level: process.env.PCWAKE_LOG_LEVEL || 'info' });
  let state;
  let audit;
  let pairing;
  let bridge;
  let admin;
  let shuttingDown = false;

  async function shutdown(signal, exitCode = 0) {
    if (shuttingDown) return;
    shuttingDown = true;
    logger.info('shutting down', { signal });
    pairing?.clear();
    try {
      await admin?.close();
      await bridge?.close();
    } catch (error) {
      logger.warn('error during shutdown', { error });
    }
    audit?.log('shutdown', { signal });
    logger.info('stopped');
    process.exit(exitCode);
  }

  process.on('SIGTERM', () => {
    void shutdown('SIGTERM');
  });
  process.on('SIGINT', () => {
    void shutdown('SIGINT');
  });
  process.on('uncaughtException', (error) => {
    logger.error('uncaught exception', { error });
    void shutdown('uncaughtException', 1);
  });
  process.on('unhandledRejection', (error) => {
    logger.error('unhandled rejection', { error });
    void shutdown('unhandledRejection', 1);
  });

  try {
    const pairingHost = resolvePublicHost(config);
    state = new StateStore({ stateDir: config.stateDir, logger });
    audit = new AuditLog({
      stateDir: config.stateDir,
      maxBytes: config.auditMaxBytes,
      logger,
    });
    pairing = new PairingManager();

    bridge = await startBridgeServer({
      config,
      state,
      pairing,
      audit,
      logger,
    });
    admin = await startAdminServer({
      config,
      state,
      pairing,
      audit,
      logger,
      getPairingInfo: () => ({
        host: pairingHost,
        port: bridge.port,
        fingerprint: bridge.fingerprint,
      }),
    });

    audit.log('startup', {
      host: config.listenHost,
      port: bridge.port,
      adminPort: admin.port,
      fingerprint: bridge.fingerprint,
      targets: config.targets.length,
    });
    logger.info('wake bridge started', {
      name: config.name,
      port: bridge.port,
      adminPort: admin.port,
      fingerprint: bridge.fingerprint,
      pairingHost,
      stateDir: config.stateDir,
    });
  } catch (error) {
    logger.error('failed to start wake bridge', { error });
    try {
      await admin?.close();
      await bridge?.close();
    } catch {
      // ignore cleanup failures
    }
    process.exitCode = 1;
  }
}

void main();
