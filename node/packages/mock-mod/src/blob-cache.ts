import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const SUFFIX = '.blob';
const HEX_NAME = /^[0-9a-f]{64}\.blob$/;

interface Entry {
  size: number;
  /** Absent for entries indexed from disk and not read since. */
  bytes: Uint8Array | undefined;
}

/**
 * The mod's blob cache: payloads by SHA-256 hex, least recently used
 * evicted first once `capacityBytes` is exceeded. With a directory, blobs are also written there and
 * found again by a later mock on the same directory, as the real mod's disk cache survives restarts.
 * File operations are synchronous; the cache is small and used by tests.
 */
export class BlobCache {
  private readonly entries = new Map<string, Entry>();
  private readonly capacity: number;
  private readonly dir: string | undefined;
  private bytes = 0;

  constructor(capacityBytes: number, dir: string | undefined) {
    this.capacity = capacityBytes;
    this.dir = dir;
    if (dir === undefined || !existsSync(dir)) return;
    const files = readdirSync(dir)
      .filter((f) => HEX_NAME.test(f))
      .map((f) => ({ hex: f.slice(0, -SUFFIX.length), stat: statSync(join(dir, f)) }))
      .sort((a, b) => a.stat.mtimeMs - b.stat.mtimeMs);
    for (const f of files) this.insert(f.hex, { size: f.stat.size, bytes: undefined });
  }

  /** Total payload bytes held. */
  get size(): number {
    return this.bytes;
  }

  /** True when the blob is cached; counts as a use for LRU order. */
  has(hex: string): boolean {
    const e = this.entries.get(hex);
    if (e === undefined) return false;
    this.entries.delete(hex);
    this.entries.set(hex, e);
    return true;
  }

  /** The payload, or `undefined` when not cached. Does not change LRU order. */
  get(hex: string): Uint8Array | undefined {
    const e = this.entries.get(hex);
    if (e === undefined) return undefined;
    if (e.bytes === undefined && this.dir !== undefined) e.bytes = new Uint8Array(readFileSync(join(this.dir, hex + SUFFIX)));
    return e.bytes;
  }

  put(hex: string, payload: Uint8Array): void {
    if (this.entries.has(hex)) {
      this.has(hex);
      return;
    }
    if (this.dir !== undefined) {
      mkdirSync(this.dir, { recursive: true });
      writeFileSync(join(this.dir, hex + SUFFIX), payload);
    }
    this.insert(hex, { size: payload.length, bytes: payload });
  }

  /** Cached blobs, least recently used first. */
  list(): { hash: string; size: number }[] {
    return [...this.entries].map(([hash, e]) => ({ hash, size: e.size }));
  }

  private insert(hex: string, e: Entry): void {
    this.entries.set(hex, e);
    this.bytes += e.size;
    for (const [old, o] of this.entries) {
      if (this.bytes <= this.capacity || old === hex) break;
      this.entries.delete(old);
      this.bytes -= o.size;
      if (this.dir !== undefined) rmSync(join(this.dir, old + SUFFIX), { force: true });
    }
  }
}
