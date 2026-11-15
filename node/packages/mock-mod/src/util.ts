import { BridgeError, expectFullyRead, WireReader, WireWriter, type Codec, type WorldKey } from '@mineflayer-bridge/protocol';
import type { RawData } from 'ws';

/** `Omit` applied to each member of a union. */
export type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never;

const DISPLAY_KINDS: ReadonlySet<string> = new Set(['core:display.text', 'core:display.item', 'core:display.block', 'core:display.entity']);

/** True for the four display kinds. */
export const isDisplay = (kind: string): boolean => DISPLAY_KINDS.has(kind);

/** Lowercase hex of `bytes`. */
export function hex(bytes: Uint8Array): string {
  return Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength).toString('hex');
}

/** Encodes `value` with `codec` into a fresh buffer. */
export function encode<T>(codec: Codec<T>, value: T): Uint8Array {
  const writer = new WireWriter(256);
  codec.write(writer, value);
  return writer.toBytes();
}

/** Decodes a blob payload struct; trailing bytes are an error. */
export function decodeStruct<T>(codec: Codec<T>, bytes: Uint8Array): T {
  const r = new WireReader(bytes);
  const v = codec.read(r);
  expectFullyRead(r);
  return v;
}

/** Orders items by `key`, as every inspector listing is sorted. */
export function byKey(a: { readonly key: string }, b: { readonly key: string }): number {
  return a.key < b.key ? -1 : a.key > b.key ? 1 : 0;
}

/** A bot identity without its `bot:` owner prefix. */
export function bareIdentity(identity: string): string {
  return identity.startsWith('bot:') ? identity.slice(4) : identity;
}

/** Default world matcher: server, then backend when both have one, then dimension. */
export function worldsMatch(a: WorldKey, b: WorldKey): boolean {
  if (a.server.toLowerCase() !== b.server.toLowerCase()) return false;
  if (a.backend !== undefined && b.backend !== undefined && a.backend !== b.backend) return false;
  return a.dimension === b.dimension;
}

/** A WebSocket message as one byte array. */
export function toBytes(data: RawData): Uint8Array {
  if (Array.isArray(data)) return new Uint8Array(Buffer.concat(data));
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
}

/** `e` as a `BridgeError`; anything else becomes `internal`. */
export function toBridgeError(e: unknown): BridgeError {
  return e instanceof BridgeError ? e : new BridgeError('internal', e instanceof Error ? e.message : String(e));
}
