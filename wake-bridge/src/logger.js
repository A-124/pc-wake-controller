const LEVELS = { debug: 10, info: 20, warn: 30, error: 40 };

function serializeError(error) {
  if (error instanceof Error) {
    return { name: error.name, message: error.message, code: error.code ?? null };
  }
  return error;
}

/**
 * Minimal JSON-lines logger. Never pass tokens, pairing codes, signatures or
 * request bodies to this logger.
 */
export function createLogger({
  level = 'info',
  stream = process.stdout,
  service = 'pcwake-bridge',
  nowMs = Date.now,
} = {}) {
  const threshold = LEVELS[level] ?? LEVELS.info;

  function emit(levelName, message, fields = {}) {
    if (LEVELS[levelName] < threshold) return;
    const record = {
      ts: new Date(nowMs()).toISOString(),
      level: levelName,
      service,
      msg: typeof message === 'string' ? message : String(message),
    };
    if (fields && typeof fields === 'object') {
      for (const [key, value] of Object.entries(fields)) {
        record[key] = value instanceof Error ? serializeError(value) : value;
      }
    }
    let line;
    try {
      line = `${JSON.stringify(record)}\n`;
    } catch {
      line = `${JSON.stringify({ ts: record.ts, level: levelName, service, msg: record.msg })}\n`;
    }
    try {
      stream.write(line);
    } catch {
      // Logging must never take the service down.
    }
  }

  return {
    debug: (message, fields) => emit('debug', message, fields),
    info: (message, fields) => emit('info', message, fields),
    warn: (message, fields) => emit('warn', message, fields),
    error: (message, fields) => emit('error', message, fields),
    level,
  };
}

export const silentLogger = createLogger({
  level: 'error',
  stream: { write() {} },
});
