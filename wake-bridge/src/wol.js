import dgram from 'node:dgram';
import net from 'node:net';
import { normalizeMac } from './util.js';

export const MAGIC_PACKET_LENGTH = 102;
export const DEFAULT_WAKE_INTERVAL_MS = 100;

/**
 * Build the 102-byte Magic Packet: six 0xFF bytes followed by the target MAC
 * repeated sixteen times. Identical to the Android local-WoL implementation.
 */
export function buildMagicPacket(mac) {
  const normalized = normalizeMac(mac);
  const macBytes = Buffer.from(normalized.replace(/:/g, ''), 'hex');
  if (macBytes.length !== 6) throw new Error('invalid MAC address');
  const packet = Buffer.alloc(MAGIC_PACKET_LENGTH, 0xff);
  for (let i = 0; i < 16; i += 1) {
    macBytes.copy(packet, 6 + i * 6);
  }
  return packet;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Send a real UDP Magic Packet. `sent` counts datagrams whose send callback
 * completed successfully; it is not a boot confirmation.
 */
export async function sendMagicPacket({
  mac,
  broadcast,
  port,
  repeats = 3,
  intervalMs = DEFAULT_WAKE_INTERVAL_MS,
  socketFactory = () => dgram.createSocket('udp4'),
}) {
  if (!net.isIPv4(broadcast)) throw new Error(`invalid IPv4 broadcast address: ${broadcast}`);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error(`invalid UDP port: ${port}`);
  if (!Number.isInteger(repeats) || repeats < 1 || repeats > 10) {
    throw new Error(`invalid repeat count: ${repeats}`);
  }
  if (!Number.isInteger(intervalMs) || intervalMs < 0 || intervalMs > 5000) {
    throw new Error(`invalid wake interval: ${intervalMs}`);
  }

  const packet = buildMagicPacket(mac);
  const socket = socketFactory();
  let asyncError = null;
  socket.on('error', (error) => {
    asyncError = error;
  });

  try {
    // SO_BROADCAST must be enabled on a bound socket. Windows reports EBADF if
    // setBroadcast() is called before the implicit bind performed by send().
    await new Promise((resolve, reject) => {
      const onError = (error) => {
        socket.off('listening', onListening);
        reject(error);
      };
      const onListening = () => {
        socket.off('error', onError);
        resolve();
      };
      socket.once('error', onError);
      socket.once('listening', onListening);
      socket.bind(0, '0.0.0.0');
    });
    socket.setBroadcast(true);
  } catch (error) {
    try {
      socket.close();
    } catch {
      // ignore
    }
    throw error;
  }

  let sent = 0;
  let lastError = null;
  try {
    for (let attempt = 0; attempt < repeats; attempt += 1) {
      if (attempt > 0 && intervalMs > 0) await sleep(intervalMs);
      try {
        await new Promise((resolve, reject) => {
          socket.send(packet, port, broadcast, (error) => {
            if (error) reject(error);
            else resolve();
          });
        });
        sent += 1;
      } catch (error) {
        lastError = error;
        break;
      }
    }
  } finally {
    try {
      socket.close();
    } catch {
      // already closed
    }
  }

  if (sent === 0) {
    const error = new Error(`failed to send Magic Packet: ${lastError?.message ?? asyncError?.message ?? 'unknown error'}`);
    error.code = lastError?.code ?? asyncError?.code ?? 'WAKE_SEND_FAILED';
    error.sent = 0;
    throw error;
  }
  return {
    sent,
    failed: lastError !== null || asyncError !== null,
    error: lastError?.message ?? asyncError?.message ?? null,
  };
}
