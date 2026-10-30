import fs from 'node:fs';
import path from 'node:path';

/**
 * Append-only JSONL audit log with simple size-based rotation. Callers must
 * never pass raw pairing codes or tokens in fields.
 */
export class AuditLog {
  constructor({ stateDir, maxBytes = 5 * 1024 * 1024, logger = null, nowMs = Date.now } = {}) {
    if (!stateDir) throw new Error('AuditLog requires stateDir');
    this.stateDir = path.resolve(stateDir);
    this.file = path.join(this.stateDir, 'audit.log');
    this.rotatedFile = path.join(this.stateDir, 'audit.log.1');
    this.maxBytes = maxBytes;
    this.logger = logger;
    this.nowMs = nowMs;
    fs.mkdirSync(this.stateDir, { recursive: true, mode: 0o700 });
    if (!fs.existsSync(this.file)) {
      fs.writeFileSync(this.file, '', { mode: 0o600 });
    }
    try {
      fs.chmodSync(this.file, 0o600);
    } catch {
      // best effort on platforms without POSIX modes
    }
  }

  log(event, fields = {}) {
    const record = {
      ts: new Date(this.nowMs()).toISOString(),
      event,
      ...fields,
    };
    const line = `${JSON.stringify(record)}\n`;
    try {
      this.rotateIfNeeded(Buffer.byteLength(line));
      fs.appendFileSync(this.file, line, { mode: 0o600 });
      return true;
    } catch (error) {
      this.logger?.warn?.('audit log write failed', { error: error.message });
      return false;
    }
  }

  rotateIfNeeded(incomingBytes) {
    let size = 0;
    try {
      size = fs.statSync(this.file).size;
    } catch {
      size = 0;
    }
    if (size === 0 || size + incomingBytes <= this.maxBytes) return;
    try {
      fs.unlinkSync(this.rotatedFile);
    } catch {
      // no previous rotated file
    }
    fs.renameSync(this.file, this.rotatedFile);
    fs.writeFileSync(this.file, '', { mode: 0o600 });
  }
}
