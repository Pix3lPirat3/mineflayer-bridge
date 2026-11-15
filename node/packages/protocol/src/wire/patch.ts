import { WireError } from './errors.ts';
import { WireReader } from './reader.ts';
import { WireWriter } from './writer.ts';

const NIL = Uint8Array.of(0xc0);

/**
 * Splits an encoded MessagePack array into the raw encoded bytes of each element.
 * Element bytes are copied, so the result does not alias `data`.
 *
 * @throws WireError `invalid_message` when `data` is not exactly one array.
 */
export function splitArray(data: Uint8Array): Uint8Array[] {
  const r = new WireReader(data);
  const n = r.arrayHeader();
  const out: Uint8Array[] = new Array(n);
  for (let i = 0; i < n; i++) {
    const start = r.position;
    r.skip();
    out[i] = r.slice(start);
  }
  if (r.remaining !== 0) throw new WireError('invalid_message', `${r.remaining} trailing bytes after array`);
  return out;
}

/** Encodes raw element bytes as one MessagePack array. Each element must already be one encoded value. */
export function joinArray(elements: readonly Uint8Array[]): Uint8Array {
  const w = new WireWriter(64);
  w.arrayHeader(elements.length);
  for (const e of elements) w.raw(e);
  return w.toBytes();
}

/**
 * Decodes patch `fields` (a MessagePack map from field index to value) into raw encoded values.
 *
 * @throws WireError `invalid_message` on a malformed map or a negative or duplicate index.
 */
export function decodePatchFields(fields: Uint8Array): Map<number, Uint8Array> {
  const r = new WireReader(fields);
  const n = r.mapHeader();
  const out = new Map<number, Uint8Array>();
  for (let i = 0; i < n; i++) {
    const index = r.uint();
    if (out.has(index)) throw new WireError('invalid_message', `duplicate patch field ${index}`);
    const start = r.position;
    r.skip();
    out.set(index, r.slice(start));
  }
  if (r.remaining !== 0) throw new WireError('invalid_message', `${r.remaining} trailing bytes after patch fields`);
  return out;
}

/** Encodes raw encoded values keyed by field index as patch `fields`, in ascending index order. */
export function encodePatchFields(values: ReadonlyMap<number, Uint8Array>): Uint8Array {
  const w = new WireWriter(64);
  const indices = [...values.keys()].sort((a, b) => a - b);
  w.mapHeader(indices.length);
  for (const i of indices) {
    w.uint(i);
    w.raw(values.get(i)!);
  }
  return w.toBytes();
}

/**
 * Applies a field patch to encoded object data: the data is read as an
 * array, the given indices are replaced (padding with nil), and the array is re-encoded.
 * Untouched elements keep their exact bytes.
 */
export function applyPatch(data: Uint8Array, fields: Uint8Array): Uint8Array {
  const elements = splitArray(data);
  for (const [index, value] of decodePatchFields(fields)) {
    while (elements.length <= index) elements.push(NIL);
    elements[index] = value;
  }
  return joinArray(elements);
}

/**
 * Computes the patch `fields` that turn `previous` into `next`, both encoded arrays.
 * Returns `null` when `next` is shorter than `previous`, because a patch cannot remove elements;
 * callers then send a full write. Returns an empty map encoding when both are identical.
 */
export function diffPatch(previous: Uint8Array, next: Uint8Array): Uint8Array | null {
  const a = splitArray(previous);
  const b = splitArray(next);
  if (b.length < a.length) return null;
  const changed = new Map<number, Uint8Array>();
  for (let i = 0; i < b.length; i++) {
    const x = a[i];
    const y = b[i]!;
    if (x === undefined || !bytesEqual(x, y)) changed.set(i, y);
  }
  return encodePatchFields(changed);
}

/** Byte-wise equality. */
export function bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}
