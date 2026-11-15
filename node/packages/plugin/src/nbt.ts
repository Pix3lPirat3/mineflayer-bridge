import { BridgeError } from '@mineflayer-bridge/protocol';
import type { NbtCompound, NbtTyped, NbtValue } from './display-types.ts';

/** Marks values made by {@link nbt}, so a compound that happens to have `nbtType` and `value` keys stays a compound. */
const TYPED = Symbol('nbt.typed');

type Tagged = NbtTyped & { readonly [TYPED]: true };

const RANGES: Readonly<Record<'byte' | 'short' | 'int', readonly [number, number]>> = {
  byte: [-128, 127],
  short: [-32768, 32767],
  int: [-2147483648, 2147483647],
};
const LONG_MIN = -(2n ** 63n);
const LONG_MAX = 2n ** 63n - 1n;

function typed(nbtType: NbtTyped['nbtType'], value: NbtTyped['value']): NbtTyped {
  const t: Tagged = { nbtType, value, [TYPED]: true };
  return Object.freeze(t);
}

function isTyped(v: unknown): v is NbtTyped {
  return typeof v === 'object' && v !== null && (v as Partial<Tagged>)[TYPED] === true;
}

function fail(why: string): never {
  throw new BridgeError('invalid_message', `entity data: ${why}`);
}

function integer(kind: 'byte' | 'short' | 'int', v: number | bigint): string {
  const n = typeof v === 'bigint' ? Number(v) : v;
  const [min, max] = RANGES[kind];
  if (!Number.isInteger(n) || n < min || n > max) fail(`${kind} must be an integer from ${min} to ${max}: ${String(v)}`);
  return String(n);
}

function long(v: number | bigint): string {
  const n = typeof v === 'bigint' ? v : Number.isSafeInteger(v) ? BigInt(v) : fail(`long must be a safe integer or a bigint: ${String(v)}`);
  if (n < LONG_MIN || n > LONG_MAX) fail(`long out of range: ${String(v)}`);
  return String(n);
}

function decimal(v: number | bigint): string {
  const n = Number(v);
  if (!Number.isFinite(n)) fail(`numbers must be finite: ${String(v)}`);
  return String(n);
}

function one(v: number | bigint | readonly (number | bigint)[]): number | bigint {
  if (Array.isArray(v)) fail('a numeric tag holds one number');
  return v as number | bigint;
}

function many(v: number | bigint | readonly (number | bigint)[]): readonly (number | bigint)[] {
  if (!Array.isArray(v)) fail('an array tag holds a list of numbers');
  return v as readonly (number | bigint)[];
}

function typedSnbt(t: NbtTyped): string {
  switch (t.nbtType) {
    case 'byte':
      return `${integer('byte', one(t.value))}b`;
    case 'short':
      return `${integer('short', one(t.value))}s`;
    case 'int':
      return integer('int', one(t.value));
    case 'long':
      return `${long(one(t.value))}L`;
    case 'float':
      return `${decimal(one(t.value))}f`;
    case 'double':
      return `${decimal(one(t.value))}d`;
    case 'byteArray':
      return `[B;${many(t.value).map((x) => `${integer('byte', x)}b`).join(',')}]`;
    case 'intArray':
      return `[I;${many(t.value).map((x) => integer('int', x)).join(',')}]`;
    case 'longArray':
      return `[L;${many(t.value).map((x) => `${long(x)}L`).join(',')}]`;
  }
}

/** SNBT quotes strings with `"`, escaping only the backslash and the quote. */
function quoteSnbt(s: string): string {
  return `"${s.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}"`;
}

/** Keys made only of these characters need no quotes in SNBT. */
const BARE_KEY = /^[A-Za-z0-9._+-]+$/;

function value(v: NbtValue, depth: number): string {
  // Vanilla rejects SNBT nested deeper than 512 levels.
  if (depth > 512) fail('nested too deeply');
  if (typeof v === 'string') return quoteSnbt(v);
  if (typeof v === 'boolean') return v ? '1b' : '0b';
  if (typeof v === 'number') {
    if (!Number.isFinite(v)) fail(`numbers must be finite: ${String(v)}`);
    // An integer outside the int range would be read as a double anyway, so say so explicitly.
    if (Number.isInteger(v)) return v >= RANGES.int[0] && v <= RANGES.int[1] ? String(v) : `${v}d`;
    // Without a suffix SNBT only reads plain decimals as doubles; `1e-7` would become a string.
    const s = String(v);
    return /^-?\d+\.\d+$/.test(s) ? s : `${s}d`;
  }
  if (isTyped(v)) return typedSnbt(v);
  if (Array.isArray(v)) return `[${(v as readonly NbtValue[]).map((x) => value(x, depth + 1)).join(',')}]`;
  if (typeof v === 'object' && v !== null) return compound(v as NbtCompound, depth + 1);
  return fail(`unsupported value ${String(v)}`);
}

function compound(c: NbtCompound, depth: number): string {
  const parts: string[] = [];
  for (const [k, v] of Object.entries(c)) {
    if (v === undefined) continue;
    if (v === null) fail(`${k} is null; leave it out instead`);
    parts.push(`${BARE_KEY.test(k) ? k : quoteSnbt(k)}:${value(v, depth)}`);
  }
  return `{${parts.join(',')}}`;
}

/**
 * Serialises a compound to SNBT, as `/summon` reads it.
 * @throws BridgeError `invalid_message` for non-finite numbers, `null` values, typed numbers out of
 *   range and anything that is not an {@link NbtValue}.
 */
export function toSnbt(c: NbtCompound): string {
  if (typeof c !== 'object' || c === null || Array.isArray(c)) fail('must be an SNBT string or an object');
  return compound(c, 0);
}

/**
 * Helpers for SNBT values with an explicit type, for {@link EntityDisplayOptions.data}. Plain
 * numbers serialise as ints or doubles; use these for the other types:
 * `{ Health: nbt.float(5), Age: nbt.int(-24000), Tags: ['shown'] }`.
 */
export const nbt = {
  byte: (v: number): NbtTyped => typed('byte', v),
  short: (v: number): NbtTyped => typed('short', v),
  int: (v: number): NbtTyped => typed('int', v),
  long: (v: number | bigint): NbtTyped => typed('long', v),
  float: (v: number): NbtTyped => typed('float', v),
  double: (v: number): NbtTyped => typed('double', v),
  byteArray: (v: readonly number[]): NbtTyped => typed('byteArray', [...v]),
  intArray: (v: readonly number[]): NbtTyped => typed('intArray', [...v]),
  longArray: (v: readonly (number | bigint)[]): NbtTyped => typed('longArray', [...v]),
};

/** A tag read from binary NBT by {@link readNbt}. Longs are bigints; array tags are typed arrays. */
export type NbtTag =
  | number
  | bigint
  | string
  | Int8Array
  | Int32Array
  | BigInt64Array
  | readonly NbtTag[]
  | { readonly [key: string]: NbtTag };

/** Vanilla refuses NBT nested deeper than this. */
const MAX_NBT_DEPTH = 512;

/**
 * Reads uncompressed, big-endian binary NBT (Java edition): one named root compound. Strings are
 * decoded as UTF-8, which matches Java's modified UTF-8 except for U+0000 and characters outside
 * the Basic Multilingual Plane.
 * @throws BridgeError `invalid_message` for truncated or malformed data.
 */
export function readNbt(bytes: Uint8Array): { name: string; value: NbtTag } {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const utf8 = new TextDecoder('utf-8');
  let at = 0;
  const bad = (why: string): never => {
    throw new BridgeError('invalid_message', `nbt: ${why} at byte ${at}`);
  };
  const need = (n: number): number => {
    if (n < 0 || at + n > bytes.byteLength) bad('unexpected end of data');
    const p = at;
    at += n;
    return p;
  };
  const length = (): number => {
    const n = view.getInt32(need(4));
    if (n < 0) bad(`negative length ${n}`);
    return n;
  };
  const string = (): string => {
    const n = view.getUint16(need(2));
    const p = need(n);
    return utf8.decode(bytes.subarray(p, p + n));
  };
  const payload = (type: number, depth: number): NbtTag => {
    if (depth > MAX_NBT_DEPTH) bad('nested too deeply');
    switch (type) {
      case 1:
        return view.getInt8(need(1));
      case 2:
        return view.getInt16(need(2));
      case 3:
        return view.getInt32(need(4));
      case 4:
        return view.getBigInt64(need(8));
      case 5:
        return view.getFloat32(need(4));
      case 6:
        return view.getFloat64(need(8));
      case 7: {
        const n = length();
        const p = need(n);
        return Int8Array.from(bytes.subarray(p, p + n), (b) => (b << 24) >> 24);
      }
      case 8:
        return string();
      case 9: {
        const inner = view.getUint8(need(1));
        const n = length();
        if (inner === 0 && n > 0) bad('list of end tags');
        const out: NbtTag[] = [];
        for (let i = 0; i < n; i++) out.push(payload(inner, depth + 1));
        return out;
      }
      case 10: {
        const out: Record<string, NbtTag> = {};
        for (;;) {
          const t = view.getUint8(need(1));
          if (t === 0) return out;
          const key = string();
          // defineProperty, so a key such as `__proto__` stays an ordinary entry.
          Object.defineProperty(out, key, { value: payload(t, depth + 1), enumerable: true, writable: true, configurable: true });
        }
      }
      case 11: {
        const n = length();
        const p = need(n * 4);
        const out = new Int32Array(n);
        for (let i = 0; i < n; i++) out[i] = view.getInt32(p + i * 4);
        return out;
      }
      case 12: {
        const n = length();
        const p = need(n * 8);
        const out = new BigInt64Array(n);
        for (let i = 0; i < n; i++) out[i] = view.getBigInt64(p + i * 8);
        return out;
      }
      default:
        return bad(`unknown tag type ${type}`);
    }
  };
  const type = view.getUint8(need(1));
  if (type !== 10) bad(`the root must be a compound, got tag type ${type}`);
  const name = string();
  return { name, value: payload(type, 0) };
}
