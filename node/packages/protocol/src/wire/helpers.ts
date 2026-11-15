import type { Codec, KindType } from './codec.ts';
import { writeHeader, type EnvelopeHeader } from './envelope.ts';
import { WireReader } from './reader.ts';
import { WireWriter } from './writer.ts';
import { WireError } from './errors.ts';

/** Encodes one framed message, header then payload. Resets `w` first; returns a copy of its bytes. */
export function encodeMessage<T>(w: WireWriter, header: EnvelopeHeader, codec: Codec<T>, payload: T): Uint8Array {
  w.reset();
  writeHeader(w, header);
  codec.write(w, payload);
  return w.toBytes();
}

const scratch = new WireWriter(1024);

/** Encodes kind data for `ObjectFull.data`. */
export function encodeKind<T>(kind: KindType<T>, value: T): Uint8Array {
  scratch.reset();
  kind.codec.write(scratch, value);
  return scratch.toBytes();
}

/** Decodes `ObjectFull.data`; trailing bytes are an error. */
export function decodeKind<T>(kind: KindType<T>, data: Uint8Array): T {
  const r = new WireReader(data);
  const value = kind.codec.read(r);
  if (r.remaining !== 0) throw new WireError('invalid_message', `${kind.name}: trailing bytes in object data`);
  return value;
}

/** Integer block coordinates. */
export interface BlockPos {
  x: number;
  y: number;
  z: number;
}

/**
 * Packs a block position into the vanilla signed 64-bit layout:
 * x in 26 bits at 38, z in 26 bits at 12, y in the low 12 bits. Out-of-range coordinates wrap.
 */
export function packBlockPos(p: BlockPos): bigint {
  return BigInt.asIntN(
    64,
    (BigInt.asUintN(26, BigInt(p.x)) << 38n) | (BigInt.asUintN(26, BigInt(p.z)) << 12n) | BigInt.asUintN(12, BigInt(p.y)),
  );
}

/** Inverse of {@link packBlockPos}. */
export function unpackBlockPos(v: bigint): BlockPos {
  return {
    x: Number(BigInt.asIntN(26, v >> 38n)),
    y: Number(BigInt.asIntN(12, v)),
    z: Number(BigInt.asIntN(26, v >> 12n)),
  };
}
