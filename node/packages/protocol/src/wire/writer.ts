import { WireError } from './errors.ts';

const textEncoder = new TextEncoder();
const LITTLE_ENDIAN = new Uint8Array(new Uint16Array([1]).buffer)[0] === 1;

/**
 * Append-only MessagePack writer over a growable buffer.
 *
 * Implements the subset the protocol uses. Integers always use the smallest encoding so output is
 * byte-identical to the Java writer. A writer is reusable: call {@link reset} between messages.
 */
export class WireWriter {
  private buf: Uint8Array;
  private view: DataView;
  private pos = 0;

  /** @param initialCapacity Starting buffer size in bytes; the buffer doubles as needed. */
  constructor(initialCapacity = 256) {
    this.buf = new Uint8Array(initialCapacity);
    this.view = new DataView(this.buf.buffer);
  }

  /** Bytes written so far. */
  get length(): number {
    return this.pos;
  }

  /** Discards the written bytes and keeps the buffer. */
  reset(): void {
    this.pos = 0;
  }

  /** Copy of the bytes written so far. */
  toBytes(): Uint8Array {
    return this.buf.slice(0, this.pos);
  }

  /** View of the bytes written so far; invalidated by the next write or reset. */
  bytesView(): Uint8Array {
    return this.buf.subarray(0, this.pos);
  }

  private ensure(extra: number): void {
    const needed = this.pos + extra;
    if (needed <= this.buf.length) return;
    let cap = this.buf.length * 2;
    while (cap < needed) cap *= 2;
    const next = new Uint8Array(cap);
    next.set(this.buf.subarray(0, this.pos));
    this.buf = next;
    this.view = new DataView(next.buffer);
  }

  /** Appends bytes as they are; they must already be valid MessagePack. */
  raw(bytes: Uint8Array): void {
    this.ensure(bytes.length);
    this.buf.set(bytes, this.pos);
    this.pos += bytes.length;
  }

  private u8(v: number): void {
    this.ensure(1);
    this.buf[this.pos++] = v;
  }

  /** Unsigned LEB128, used only by the envelope header. */
  varint(value: number): void {
    if (!Number.isSafeInteger(value) || value < 0) throw new WireError('invalid_message', `varint out of range: ${value}`);
    let v = value;
    while (v >= 0x80) {
      this.u8((v % 0x80) | 0x80);
      v = Math.floor(v / 0x80);
    }
    this.u8(v);
  }

  /** Writes nil. */
  nil(): void {
    this.u8(0xc0);
  }

  /** Writes a bool. */
  bool(v: boolean): void {
    this.u8(v ? 0xc3 : 0xc2);
  }

  /** Writes a safe integer in its smallest encoding. */
  int(v: number): void {
    if (!Number.isSafeInteger(v)) throw new WireError('invalid_message', `integer out of safe range: ${v}`);
    if (v >= 0) {
      this.uint(v);
      return;
    }
    this.ensure(9);
    if (v >= -32) {
      this.view.setInt8(this.pos, v);
      this.pos += 1;
    } else if (v >= -0x80) {
      this.buf[this.pos] = 0xd0;
      this.view.setInt8(this.pos + 1, v);
      this.pos += 2;
    } else if (v >= -0x8000) {
      this.buf[this.pos] = 0xd1;
      this.view.setInt16(this.pos + 1, v);
      this.pos += 3;
    } else if (v >= -0x80000000) {
      this.buf[this.pos] = 0xd2;
      this.view.setInt32(this.pos + 1, v);
      this.pos += 5;
    } else {
      this.buf[this.pos] = 0xd3;
      this.view.setBigInt64(this.pos + 1, BigInt(v));
      this.pos += 9;
    }
  }

  /** Writes a non-negative safe integer in its smallest encoding. */
  uint(v: number): void {
    if (!Number.isSafeInteger(v) || v < 0) throw new WireError('invalid_message', `unsigned integer out of range: ${v}`);
    this.ensure(9);
    if (v < 0x80) {
      this.buf[this.pos++] = v;
    } else if (v < 0x100) {
      this.buf[this.pos] = 0xcc;
      this.buf[this.pos + 1] = v;
      this.pos += 2;
    } else if (v < 0x10000) {
      this.buf[this.pos] = 0xcd;
      this.view.setUint16(this.pos + 1, v);
      this.pos += 3;
    } else if (v < 0x100000000) {
      this.buf[this.pos] = 0xce;
      this.view.setUint32(this.pos + 1, v);
      this.pos += 5;
    } else {
      this.buf[this.pos] = 0xcf;
      this.view.setBigUint64(this.pos + 1, BigInt(v));
      this.pos += 9;
    }
  }

  /** Signed 64-bit value given as bigint; used for packed values such as BlockPos. */
  bigint(v: bigint): void {
    if (v >= 0n && v <= BigInt(Number.MAX_SAFE_INTEGER)) {
      this.uint(Number(v));
    } else if (v < 0n && v >= BigInt(Number.MIN_SAFE_INTEGER)) {
      this.int(Number(v));
    } else if (v > 0n) {
      this.ensure(9);
      this.buf[this.pos] = 0xcf;
      this.view.setBigUint64(this.pos + 1, v);
      this.pos += 9;
    } else {
      this.ensure(9);
      this.buf[this.pos] = 0xd3;
      this.view.setBigInt64(this.pos + 1, v);
      this.pos += 9;
    }
  }

  /** Writes a float32. */
  f32(v: number): void {
    this.ensure(5);
    this.buf[this.pos] = 0xca;
    this.view.setFloat32(this.pos + 1, v);
    this.pos += 5;
  }

  /** Writes a float64. */
  f64(v: number): void {
    this.ensure(9);
    this.buf[this.pos] = 0xcb;
    this.view.setFloat64(this.pos + 1, v);
    this.pos += 9;
  }

  /** Writes a UTF-8 string with the smallest header for its byte length. */
  str(v: string): void {
    // Worst case is 3 bytes per UTF-16 unit; reserve that plus the largest header, then backfill.
    const max = v.length * 3;
    this.ensure(max + 5);
    const headerSize = max < 32 ? 1 : max < 0x100 ? 2 : max < 0x10000 ? 3 : 5;
    const start = this.pos + headerSize;
    const { written: len } = textEncoder.encodeInto(v, this.buf.subarray(start));
    // Header must use the smallest form for the actual length; move the body if the reserve was larger.
    const actualHeader = len < 32 ? 1 : len < 0x100 ? 2 : len < 0x10000 ? 3 : 5;
    if (actualHeader !== headerSize) this.buf.copyWithin(this.pos + actualHeader, start, start + len);
    if (actualHeader === 1) {
      this.buf[this.pos] = 0xa0 | len;
    } else if (actualHeader === 2) {
      this.buf[this.pos] = 0xd9;
      this.buf[this.pos + 1] = len;
    } else if (actualHeader === 3) {
      this.buf[this.pos] = 0xda;
      this.view.setUint16(this.pos + 1, len);
    } else {
      this.buf[this.pos] = 0xdb;
      this.view.setUint32(this.pos + 1, len);
    }
    this.pos += actualHeader + len;
  }

  private binHeader(len: number): void {
    this.ensure(5 + len);
    if (len < 0x100) {
      this.buf[this.pos] = 0xc4;
      this.buf[this.pos + 1] = len;
      this.pos += 2;
    } else if (len < 0x10000) {
      this.buf[this.pos] = 0xc5;
      this.view.setUint16(this.pos + 1, len);
      this.pos += 3;
    } else {
      this.buf[this.pos] = 0xc6;
      this.view.setUint32(this.pos + 1, len);
      this.pos += 5;
    }
  }

  /** Writes a `bin` value. */
  bin(v: Uint8Array): void {
    this.binHeader(v.length);
    this.buf.set(v, this.pos);
    this.pos += v.length;
  }

  /** Packed little-endian array as `bin`. */
  typedArray(v: Float64Array | Float32Array | Int32Array | Uint16Array): void {
    const bytes = new Uint8Array(v.buffer, v.byteOffset, v.byteLength);
    if (LITTLE_ENDIAN) {
      this.bin(bytes);
      return;
    }
    this.binHeader(bytes.length);
    const width = v.BYTES_PER_ELEMENT;
    for (let i = 0; i < bytes.length; i += width) {
      for (let b = 0; b < width; b++) this.buf[this.pos + i + b] = bytes[i + width - 1 - b]!;
    }
    this.pos += bytes.length;
  }

  /** Writes an array header for `n` elements; the caller writes the elements. */
  arrayHeader(n: number): void {
    this.ensure(5);
    if (n < 16) {
      this.buf[this.pos++] = 0x90 | n;
    } else if (n < 0x10000) {
      this.buf[this.pos] = 0xdc;
      this.view.setUint16(this.pos + 1, n);
      this.pos += 3;
    } else {
      this.buf[this.pos] = 0xdd;
      this.view.setUint32(this.pos + 1, n);
      this.pos += 5;
    }
  }

  /** Writes a map header for `n` entries; the caller writes each key and value. */
  mapHeader(n: number): void {
    this.ensure(5);
    if (n < 16) {
      this.buf[this.pos++] = 0x80 | n;
    } else if (n < 0x10000) {
      this.buf[this.pos] = 0xde;
      this.view.setUint16(this.pos + 1, n);
      this.pos += 3;
    } else {
      this.buf[this.pos] = 0xdf;
      this.view.setUint32(this.pos + 1, n);
      this.pos += 5;
    }
  }

  /**
   * Writes a dynamic value. Integers become MessagePack ints, other numbers float64, plain objects maps
   * with string keys. Matches the Java writer's mapping of decoded values.
   */
  any(v: unknown): void {
    if (v === null || v === undefined) return this.nil();
    switch (typeof v) {
      case 'boolean':
        return this.bool(v);
      case 'number':
        return Number.isSafeInteger(v) ? this.int(v) : this.f64(v);
      case 'bigint':
        return this.bigint(v);
      case 'string':
        return this.str(v);
      case 'object':
        break;
      default:
        throw new WireError('invalid_message', `cannot encode ${typeof v}`);
    }
    if (v instanceof Uint8Array) return this.bin(v);
    if (Array.isArray(v)) {
      this.arrayHeader(v.length);
      for (const item of v) this.any(item);
      return;
    }
    if (v instanceof Map) {
      this.mapHeader(v.size);
      for (const [k, item] of v) {
        if (typeof k !== 'string') throw new WireError('invalid_message', 'map keys must be strings');
        this.str(k);
        this.any(item);
      }
      return;
    }
    const entries = Object.entries(v as Record<string, unknown>).filter(([, item]) => item !== undefined);
    this.mapHeader(entries.length);
    for (const [k, item] of entries) {
      this.str(k);
      this.any(item);
    }
  }
}
