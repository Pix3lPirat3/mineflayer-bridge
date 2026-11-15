import { BridgeError } from '@mineflayer-bridge/protocol';

type Entry<T, E> = E & { readonly owner: string; readonly settle: (value: T) => void; readonly timer: NodeJS.Timeout };

/**
 * Requests the mock sent to a session and awaits an answer for, by id. An answer counts only from
 * the session that owns the request; a late answer finds nothing and is dropped.
 */
export class Pending<T, E extends object = object> {
  private readonly entries = new Map<number, Entry<T, E>>();

  /**
   * Registers request `id` for `owner`, then calls `send`. When `timeoutMs` passes first, the entry
   * is dropped and the promise settles with `onTimeout()`: a `BridgeError` rejects, anything else
   * resolves.
   */
  expect(id: number, owner: string, extra: E, timeoutMs: number, onTimeout: () => T | BridgeError, send: () => void): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.entries.delete(id);
        const v = onTimeout();
        if (v instanceof BridgeError) reject(v);
        else resolve(v);
      }, timeoutMs);
      this.entries.set(id, { ...extra, owner, settle: resolve, timer });
      send();
    });
  }

  /** Settles request `id` with `value` when `owner` sent it and `match` accepts it. Returns false otherwise. */
  answer(id: number, owner: string, value: T, match: (e: E) => boolean = () => true): boolean {
    const e = this.entries.get(id);
    if (e === undefined || e.owner !== owner || !match(e)) return false;
    this.entries.delete(id);
    clearTimeout(e.timer);
    e.settle(value);
    return true;
  }

  /** Entries still waiting, oldest first. */
  values(): IterableIterator<Entry<T, E>> {
    return this.entries.values();
  }

  /** Drops every entry without settling it. */
  clear(): void {
    for (const e of this.entries.values()) clearTimeout(e.timer);
    this.entries.clear();
  }
}
