import { WireError } from './errors.ts';

const textDecoder = new TextDecoder('utf-8', { fatal: true });
const LITTLE_ENDIAN = new Uint8Array(new Uint16Array([1]).buffer)[0] === 1;
const MAX_SAFE = BigInt(Number.MAX_SAFE_INTEGER);
const MIN_SAFE = BigInt(Number.MIN_SAFE_INTEGER);

/**
 * MessagePack reader over a byte array. Every read validates the type tag and bounds and throws
 * {@link WireError} with code `invalid_message` on mismatch.
 */
export class WireReader {
  private readonly buf: Uint8Array;
  private readonly view: DataView;
  private pos: number;
  private readonly end: number;

  /** Reads `bytes` from `offset` up to, not including, `end`. The bytes are not copied. */
  constructor(bytes: Uint8Array, offset = 0, end = bytes.length) {
    this.buf = bytes;
    this.view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    this.pos = offset;
    this.end = end;
  }

  /** Byte offset of the next read. */
  get position(): number {
    return this.pos;
  }

  /** Bytes left before `end`. */
  get remaining(): number {
    return this.end - this.pos;
  }

  private need(n: number): void {
    if (this.pos + n > this.end) throw new WireError('invalid_message', 'unexpected end of message');
  }

  private u8(): number {
    this.need(1);
    return this.buf[this.pos++]!;
  }

  private peek(): number {
    this.need(1);
    return this.buf[this.pos]!;
  }

  /** Unsigned LEB128, used only by the envelope header; at most 8 bytes and a safe integer. */
  varint(): number {
    let result = 0;
    let scale = 1;
    for (let i = 0; i < 8; i++) {
      const b = this.u8();
      result += (b & 0x7f) * scale;
      if ((b & 0x80) === 0) {
        if (!Number.isSafeInteger(result)) break;
        return result;
      }
      scale *= 0x80;
    }
    throw new WireError('invalid_message', 'varint too long');
  }

  /** Consumes a nil if one is next. */
  tryNil(): boolean {
    if (this.peek() === 0xc0) {
      this.pos++;
      return true;
    }
    return false;
  }

  /** Reads a bool. */
  bool(): boolean {
    const t = this.u8();
    if (t === 0xc2) return false;
    if (t === 0xc3) return true;
    throw this.typeError('bool', t);
  }

  /** Any integer encoding as a bigint. */
  private integer(): bigint {
    const t = this.u8();
    if (t < 0x80) return BigInt(t);
    if (t >= 0xe0) return BigInt(t - 0x100);
    let v: bigint;
    switch (t) {
      case 0xcc: this.need(1); v = BigInt(this.view.getUint8(this.pos)); this.pos += 1; return v;
      case 0xcd: this.need(2); v = BigInt(this.view.getUint16(this.pos)); this.pos += 2; return v;
      case 0xce: this.need(4); v = BigInt(this.view.getUint32(this.pos)); this.pos += 4; return v;
      case 0xcf: this.need(8); v = this.view.getBigUint64(this.pos); this.pos += 8; return v;
      case 0xd0: this.need(1); v = BigInt(this.view.getInt8(this.pos)); this.pos += 1; return v;
      case 0xd1: this.need(2); v = BigInt(this.view.getInt16(this.pos)); this.pos += 2; return v;
      case 0xd2: this.need(4); v = BigInt(this.view.getInt32(this.pos)); this.pos += 4; return v;
      case 0xd3: this.need(8); v = this.view.getBigInt64(this.pos); this.pos += 8; return v;
      default: throw this.typeError('int', t);
    }
  }

  /** Reads any integer encoding; throws when it is outside the safe integer range. */
  int(): number {
    const t = this.peek();
    if (t < 0x80) {
      this.pos++;
      return t;
    }
    const v = this.integer();
    if (v > MAX_SAFE || v < MIN_SAFE) throw new WireError('invalid_message', `integer exceeds safe range: ${v}`);
    return Number(v);
  }

  /** Like {@link int}, and throws for a negative value. */
  uint(): number {
    const v = this.int();
    if (v < 0) throw new WireError('invalid_message', `expected unsigned integer, got ${v}`);
    return v;
  }

  /** Reads any integer encoding as a bigint, for packed values such as BlockPos. */
  bigint(): bigint {
    return this.integer();
  }

  /** Reads a float; integer encodings are accepted for robustness against dynamic encoders. */
  float(): number {
    const t = this.peek();
    if (t === 0xca) {
      this.need(5);
      const v = this.view.getFloat32(this.pos + 1);
      this.pos += 5;
      return v;
    }
    if (t === 0xcb) {
      this.need(9);
      const v = this.view.getFloat64(this.pos + 1);
      this.pos += 9;
      return v;
    }
    return Number(this.integer());
  }

  /** Reads a string; throws on invalid UTF-8. */
  str(): string {
    const t = this.u8();
    let len: number;
    if ((t & 0xe0) === 0xa0) len = t & 0x1f;
    else if (t === 0xd9) len = this.u8();
    else if (t === 0xda) { this.need(2); len = this.view.getUint16(this.pos); this.pos += 2; }
    else if (t === 0xdb) { this.need(4); len = this.view.getUint32(this.pos); this.pos += 4; }
    else throw this.typeError('str', t);
    this.need(len);
    const s = textDecoder.decode(this.buf.subarray(this.pos, this.pos + len));
    this.pos += len;
    return s;
  }

  private binLength(): number {
    const t = this.u8();
    if (t === 0xc4) return this.u8();
    if (t === 0xc5) { this.need(2); const n = this.view.getUint16(this.pos); this.pos += 2; return n; }
    if (t === 0xc6) { this.need(4); const n = this.view.getUint32(this.pos); this.pos += 4; return n; }
    throw this.typeError('bin', t);
  }

  /** Copy of a `bin` value. */
  bin(): Uint8Array {
    const len = this.binLength();
    this.need(len);
    const out = this.buf.slice(this.pos, this.pos + len);
    this.pos += len;
    return out;
  }

  private typedBytes(width: number): Uint8Array {
    const len = this.binLength();
    if (len % width !== 0) throw new WireError('invalid_message', `packed array length ${len} not a multiple of ${width}`);
    this.need(len);
    const out = new Uint8Array(len);
    if (LITTLE_ENDIAN) {
      out.set(this.buf.subarray(this.pos, this.pos + len));
    } else {
      for (let i = 0; i < len; i += width) {
        for (let b = 0; b < width; b++) out[i + b] = this.buf[this.pos + i + width - 1 - b]!;
      }
    }
    this.pos += len;
    return out;
  }

  /** Reads a packed little-endian array written by {@link WireWriter.typedArray}. */
  f64Array(): Float64Array {
    const b = this.typedBytes(8);
    return new Float64Array(b.buffer, 0, b.length / 8);
  }

  /** Reads a packed little-endian array written by {@link WireWriter.typedArray}. */
  f32Array(): Float32Array {
    const b = this.typedBytes(4);
    return new Float32Array(b.buffer, 0, b.length / 4);
  }

  /** Reads a packed little-endian array written by {@link WireWriter.typedArray}. */
  i32Array(): Int32Array {
    const b = this.typedBytes(4);
    return new Int32Array(b.buffer, 0, b.length / 4);
  }

  /** Reads a packed little-endian array written by {@link WireWriter.typedArray}. */
  u16Array(): Uint16Array {
    const b = this.typedBytes(2);
    return new Uint16Array(b.buffer, 0, b.length / 2);
  }

  /** Reads an array header and returns the element count. */
  arrayHeader(): number {
    const t = this.u8();
    if ((t & 0xf0) === 0x90) return t & 0x0f;
    if (t === 0xdc) { this.need(2); const n = this.view.getUint16(this.pos); this.pos += 2; return n; }
    if (t === 0xdd) { this.need(4); const n = this.view.getUint32(this.pos); this.pos += 4; return n; }
    throw this.typeError('array', t);
  }

  /** Reads a map header and returns the entry count. */
  mapHeader(): number {
    const t = this.u8();
    if ((t & 0xf0) === 0x80) return t & 0x0f;
    if (t === 0xde) { this.need(2); const n = this.view.getUint16(this.pos); this.pos += 2; return n; }
    if (t === 0xdf) { this.need(4); const n = this.view.getUint32(this.pos); this.pos += 4; return n; }
    throw this.typeError('map', t);
  }

  /** Decodes any value. Integers outside the safe range become bigint; maps become plain objects. */
  any(): unknown {
    const t = this.peek();
    if (t === 0xc0) { this.pos++; return null; }
    if (t === 0xc2 || t === 0xc3) return this.bool();
    if (t < 0x80 || t >= 0xe0 || (t >= 0xcc && t <= 0xd3)) {
      const v = this.integer();
      return v > MAX_SAFE || v < MIN_SAFE ? v : Number(v);
    }
    if (t === 0xca || t === 0xcb) return this.float();
    if ((t & 0xe0) === 0xa0 || t === 0xd9 || t === 0xda || t === 0xdb) return this.str();
    if (t === 0xc4 || t === 0xc5 || t === 0xc6) return this.bin();
    if ((t & 0xf0) === 0x90 || t === 0xdc || t === 0xdd) {
      const n = this.arrayHeader();
      const out: unknown[] = new Array(n);
      for (let i = 0; i < n; i++) out[i] = this.any();
      return out;
    }
    if ((t & 0xf0) === 0x80 || t === 0xde || t === 0xdf) {
      const n = this.mapHeader();
      const out: Record<string, unknown> = Object.create(null) as Record<string, unknown>;
      for (let i = 0; i < n; i++) {
        const k = this.str();
        out[k] = this.any();
      }
      return out;
    }
    throw this.typeError('value', t);
  }

  /** Skips one value of any type. */
  skip(): void {
    this.any();
  }

  /** Raw bytes from `start` to the current position. */
  slice(start: number): Uint8Array {
    return this.buf.slice(start, this.pos);
  }

  private typeError(expected: string, tag: number): WireError {
    return new WireError('invalid_message', `expected ${expected}, found tag 0x${tag.toString(16)} at ${this.pos - 1}`);
  }
}
