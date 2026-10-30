/**
 * Sliding-window counter used for all request rate limits. Time is injectable
 * so tests can advance the clock without sleeping.
 */
export class WindowCounter {
  constructor({ limit, windowMs = 60_000, nowMs = Date.now } = {}) {
    if (!Number.isInteger(limit) || limit < 1) throw new Error('WindowCounter requires a positive limit');
    if (!Number.isInteger(windowMs) || windowMs < 1) throw new Error('WindowCounter requires a positive window');
    this.limit = limit;
    this.windowMs = windowMs;
    this.nowMs = nowMs;
    this.entries = new Map();
    this.lastSweep = nowMs();
  }

  prune(key, at) {
    const values = this.entries.get(key);
    if (!values) return [];
    const cutoff = at - this.windowMs;
    let firstKept = 0;
    while (firstKept < values.length && values[firstKept] <= cutoff) firstKept += 1;
    if (firstKept > 0) values.splice(0, firstKept);
    if (values.length === 0) this.entries.delete(key);
    return values;
  }

  hit(key, at = this.nowMs()) {
    const values = this.prune(key, at);
    values.push(at);
    this.entries.set(key, values);
    this.maybeSweep(at);
    return values.length;
  }

  count(key, at = this.nowMs()) {
    return this.prune(key, at).length;
  }

  isLimited(key, at = this.nowMs()) {
    return this.count(key, at) >= this.limit;
  }

  retryAfterMs(key, at = this.nowMs()) {
    const values = this.prune(key, at);
    if (values.length < this.limit) return 0;
    return Math.max(0, values[values.length - this.limit] + this.windowMs - at);
  }

  maybeSweep(at) {
    if (at - this.lastSweep < this.windowMs && this.entries.size < 4096) return;
    for (const key of [...this.entries.keys()]) this.prune(key, at);
    this.lastSweep = at;
  }

  reset(key) {
    this.entries.delete(key);
  }

  clear() {
    this.entries.clear();
  }

  get size() {
    return this.entries.size;
  }
}
