import type { WireReader } from './reader.ts';
import type { WireWriter } from './writer.ts';

/** Encoder and decoder for one schema type. Generated codecs implement this. */
export interface Codec<T> {
  /** Appends `value` to `w`. */
  write(w: WireWriter, value: T): void;
  /** Reads one value from `r`; throws `WireError` when the bytes do not match the schema. */
  read(r: WireReader): T;
}

/** Side that sends a message: the Node plugin, the mod, or either. */
export type Direction = 'node' | 'mod' | 'both';

/**
 * Delivery class:
 * - `control`: connection or handshake traffic, never sequenced.
 * - `reliable`: sequenced per session and replayed on resume.
 * - `latest`: coalesced by type per session, never logged.
 * - `unreliable`: dropped under backpressure.
 */
export type Delivery = 'control' | 'reliable' | 'latest' | 'unreliable';

/** A registered message type with payload `T`. */
export interface MessageType<T> {
  /** Wire id carried in the envelope `type` field. */
  readonly id: number;
  /** Schema name, e.g. `obj.upsert`. */
  readonly name: string;
  readonly from: Direction;
  readonly delivery: Delivery;
  readonly codec: Codec<T>;
  /** Name of the response message, set only for requests. */
  readonly response?: string;
  /** Default request timeout in ms, set only for requests that have one. */
  readonly timeoutMs?: number;
}

/** A registered object kind with data `T`. */
export interface KindType<T> {
  /** Wire id carried in `ObjectFull.kind`. */
  readonly id: number;
  /** Schema name, e.g. `core:box`. */
  readonly name: string;
  readonly codec: Codec<T>;
}
