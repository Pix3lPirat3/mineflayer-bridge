import { BridgeError, type Vec3d } from '@mineflayer-bridge/protocol';
import type { ColorLike, Logger, Vec3Like } from './types.ts';

/** Converts any accepted vector form to a plain `{x, y, z}`. */
export function toVec3(v: Vec3Like, what = 'vector'): Vec3d {
  let x: number;
  let y: number;
  let z: number;
  if (isTuple(v)) {
    [x, y, z] = v;
  } else {
    ({ x, y, z } = v);
  }
  if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) {
    throw new BridgeError('invalid_message', `${what} must have three finite numbers`);
  }
  return { x, y, z };
}

function isTuple(v: Vec3Like): v is readonly [number, number, number] {
  return Array.isArray(v);
}

/**
 * Converts a colour to the packed u32 used on the wire, ARGB as vanilla uses (the schema default
 * `0x40000000` for label backgrounds is 25% black only in ARGB).
 * - `'#rrggbb'` is opaque, `'#rrggbbaa'` carries alpha.
 * - Numbers up to `0xFFFFFF` are opaque RGB; larger numbers are taken as ARGB.
 */
export function toColor(c: ColorLike): number {
  if (typeof c === 'number') {
    if (!Number.isInteger(c) || c < 0 || c > 0xffffffff) throw new BridgeError('invalid_message', `colour out of range: ${c}`);
    return c <= 0xffffff ? (0xff000000 | c) >>> 0 : c >>> 0;
  }
  const m = /^#([0-9a-f]{6})([0-9a-f]{2})?$/i.exec(c);
  if (m === null) throw new BridgeError('invalid_message', `colour must be #rrggbb or #rrggbbaa: ${c}`);
  const rgb = Number.parseInt(m[1]!, 16);
  const alpha = m[2] === undefined ? 0xff : Number.parseInt(m[2], 16);
  return ((alpha << 24) | rgb) >>> 0;
}

interface SemVer {
  major: number;
  minor: number;
  patch: number;
}

function parseVersion(v: string): SemVer | undefined {
  const m = /^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:[-+].*)?$/.exec(v.trim());
  if (m === null) return undefined;
  return { major: Number(m[1]), minor: Number(m[2] ?? 0), patch: Number(m[3] ?? 0) };
}

function compare(a: SemVer, b: SemVer): number {
  return a.major - b.major || a.minor - b.minor || a.patch - b.patch;
}

function satisfiesOne(version: SemVer, range: string): boolean {
  if (range === '' || range === '*' || range === 'x') return true;
  const op = /^(\^|~|>=|<=|>|<|=)?(.*)$/.exec(range)!;
  const parts = op[2]!.split('.').filter((p) => p !== '' && p !== 'x' && p !== '*').length;
  const target = parseVersion(op[2]!);
  if (target === undefined) return false;
  const c = compare(version, target);
  switch (op[1]) {
    case '^':
      if (c < 0) return false;
      if (target.major > 0 || parts === 1) return version.major === target.major;
      if (target.minor > 0 || parts === 2) return version.major === 0 && version.minor === target.minor;
      return version.major === 0 && version.minor === 0 && version.patch === target.patch;
    case '~':
      if (c < 0) return false;
      return parts === 1 ? version.major === target.major : version.major === target.major && version.minor === target.minor;
    case '>=':
      return c >= 0;
    case '<=':
      return c <= 0;
    case '>':
      return c > 0;
    case '<':
      return c < 0;
    default:
      if (parts === 1) return version.major === target.major;
      if (parts === 2) return version.major === target.major && version.minor === target.minor;
      return c === 0;
  }
}

/** True when `version` satisfies every space-separated comparator in `range` (`^1`, `~1.2`, `>=1.0.0 <2`, `*`). */
export function satisfies(version: string, range: string): boolean {
  const v = parseVersion(version);
  if (v === undefined) return false;
  return range.trim().split(/\s+/).every((r) => satisfiesOne(v, r));
}

/** Splits a capability spec such as `core:path@^1` into id and range (`*` when absent). */
export function parseSpec(spec: string): { id: string; range: string } {
  const at = spec.lastIndexOf('@');
  return at <= 0 ? { id: spec, range: '*' } : { id: spec.slice(0, at), range: spec.slice(at + 1) };
}

/** A logger that discards debug and info and forwards warnings and errors to the console. */
export const defaultLogger: Logger = {
  debug() {},
  info() {},
  warn(message, data) {
    if (data === undefined) console.warn(`[mineflayer-bridge] ${message}`);
    else console.warn(`[mineflayer-bridge] ${message}`, data);
  },
  error(message, data) {
    if (data === undefined) console.error(`[mineflayer-bridge] ${message}`);
    else console.error(`[mineflayer-bridge] ${message}`, data);
  },
};

/** Wraps a logger so every message starts with `tag`. */
export function tagLogger(base: Logger, tag: string): Logger {
  return {
    debug: (m, d) => base.debug(`${tag} ${m}`, d),
    info: (m, d) => base.info(`${tag} ${m}`, d),
    warn: (m, d) => base.warn(`${tag} ${m}`, d),
    error: (m, d) => base.error(`${tag} ${m}`, d),
  };
}

/** Applies a patch: given fields replace, `null` deletes, `undefined` and the skipped keys keep. */
export function mergePatch<O>(base: O, patch: object, skip: readonly string[]): O {
  const out: Record<string, unknown> = { ...(base as Record<string, unknown>) };
  for (const [k, v] of Object.entries(patch)) {
    if (skip.includes(k) || v === undefined) continue;
    if (v === null) delete out[k];
    else out[k] = v;
  }
  return out as O;
}

/** Normalises any thrown value to a {@link BridgeError}. */
export function asBridgeError(e: unknown, code = 'internal'): BridgeError {
  if (e instanceof BridgeError) return e;
  return new BridgeError(code, e instanceof Error ? e.message : String(e), { cause: e });
}

/** Minimal typed event emitter used by the public objects. */
export class Emitter<Events extends { [K in keyof Events]: unknown[] }> {
  private readonly listeners = new Map<keyof Events, Set<(...args: never[]) => void>>();

  on<E extends keyof Events>(event: E, fn: (...args: Events[E]) => void): () => void {
    let set = this.listeners.get(event);
    if (set === undefined) {
      set = new Set();
      this.listeners.set(event, set);
    }
    set.add(fn as unknown as (...args: never[]) => void);
    return () => this.off(event, fn);
  }

  off<E extends keyof Events>(event: E, fn: (...args: Events[E]) => void): void {
    this.listeners.get(event)?.delete(fn as unknown as (...args: never[]) => void);
  }

  /** How many listeners `event` has. */
  listenerCount(event: keyof Events): number {
    return this.listeners.get(event)?.size ?? 0;
  }

  emit<E extends keyof Events>(event: E, ...args: Events[E]): void {
    const set = this.listeners.get(event);
    if (set === undefined) return;
    for (const fn of [...set]) {
      try {
        (fn as unknown as (...a: Events[E]) => void)(...args);
      } catch (e) {
        queueMicrotask(() => {
          throw e;
        });
      }
    }
  }

  removeAll(): void {
    this.listeners.clear();
  }
}

/**
 * Rejects option keys outside `known`, so a mistyped or misplaced option fails loudly instead of
 * being ignored. Keys whose value is `undefined` count as absent. The message names the key and,
 * when one is obvious, the key or argument meant: from `hints`, then a known key that differs only
 * in case or that starts with the given key (`timeout` for `timeoutMs`).
 *
 * @param what Names the options in the message, such as `recipe prompt option`.
 * @param hints Unknown key to the advice shown for it, such as `options` to `use items ...`.
 * @throws BridgeError `invalid_message` when `o` is not a plain object or has an unknown key.
 */
export function checkKeys(what: string, o: unknown, known: ReadonlySet<string>, hints: Readonly<Record<string, string>> = {}): void {
  if (typeof o !== 'object' || o === null || Array.isArray(o)) throw new BridgeError('invalid_message', `expected an object of ${what}s`);
  for (const [k, v] of Object.entries(o)) {
    if (known.has(k) || v === undefined) continue;
    const lower = k.toLowerCase();
    const near = [...known].find((n) => n.toLowerCase() === lower) ?? [...known].find((n) => lower.length >= 3 && n.toLowerCase().startsWith(lower));
    const advice = Object.hasOwn(hints, k) ? hints[k]! : near !== undefined ? `did you mean ${near}?` : `known: ${[...known].sort().join(', ')}`;
    throw new BridgeError('invalid_message', `unknown ${what} ${JSON.stringify(k)}; ${advice}`);
  }
}

/** Ends a {@link waitFor} wait: `ok` resolves, `fail` rejects. Only the first call counts. */
export interface Settle<T> {
  ok(value: T): void;
  fail(error: unknown): void;
}

/**
 * A wait that settles exactly once: through `start`, after `timeoutMs` (ms) through `onTimeout`, or
 * when `signal` aborts, rejecting with `cancelled` and `cancelMessage`. `start` runs unless the signal
 * already aborted, and returns the teardown, which runs when the wait settles.
 */
export function waitFor<T>(
  signal: AbortSignal | undefined,
  timeoutMs: number,
  cancelMessage: string,
  onTimeout: (settle: Settle<T>) => void,
  start: (settle: Settle<T>) => (() => void) | void,
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    let settled = false;
    let teardown: (() => void) | void = undefined;
    const finish = (fn: () => void): void => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      signal?.removeEventListener('abort', onAbort);
      teardown?.();
      fn();
    };
    const settle: Settle<T> = { ok: (v) => finish(() => resolve(v)), fail: (e) => finish(() => reject(e)) };
    const onAbort = (): void => settle.fail(new BridgeError('cancelled', cancelMessage));
    const timer = setTimeout(() => onTimeout(settle), timeoutMs);
    if (signal?.aborted === true) {
      onAbort();
      return;
    }
    signal?.addEventListener('abort', onAbort, { once: true });
    try {
      teardown = start(settle);
    } catch (e) {
      settle.fail(e);
    }
    // `start` may settle synchronously, before its teardown was known.
    if (settled) teardown?.();
  });
}
