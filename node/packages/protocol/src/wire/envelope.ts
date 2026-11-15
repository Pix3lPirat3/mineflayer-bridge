import { WireError } from './errors.ts';
import { WireReader } from './reader.ts';
import type { WireWriter } from './writer.ts';

/** Envelope flag bits. */
export const Flags = {
  RELIABLE: 1 << 0,
  RESPONSE: 1 << 1,
  ERROR: 1 << 2,
  HAS_ACK: 1 << 3,
  HAS_REQ: 1 << 4,
} as const;

const RESERVED_MASK = 0xe0;

/** Frame header fields. All values are varints on the wire. */
export interface EnvelopeHeader {
  /** {@link Flags} bits. On write, `RELIABLE`, `HAS_ACK` and `HAS_REQ` are derived from the optional fields. */
  flags: number;
  /** Message wire id. */
  type: number;
  /** 0 for the connection, otherwise the session id. */
  session: number;
  /** Sequence number of a reliable message, per session and direction, starting at 1. */
  seq?: number;
  /** Highest peer `seq` applied for this session. */
  ack?: number;
  /** Request id, unique per sender per session; the response reuses it. */
  req?: number;
}

/**
 * Writes the header; the payload follows immediately.
 * @throws WireError `invalid_message` when `RESPONSE` is set without `req`.
 */
export function writeHeader(w: WireWriter, h: EnvelopeHeader): void {
  let flags = h.flags & ~(Flags.RELIABLE | Flags.HAS_ACK | Flags.HAS_REQ);
  if (h.seq !== undefined) flags |= Flags.RELIABLE;
  if (h.ack !== undefined) flags |= Flags.HAS_ACK;
  if (h.req !== undefined) flags |= Flags.HAS_REQ;
  if ((flags & Flags.RESPONSE) !== 0 && h.req === undefined) {
    throw new WireError('invalid_message', 'response without request id');
  }
  w.varint(flags);
  w.varint(h.type);
  w.varint(h.session);
  if (h.seq !== undefined) w.varint(h.seq);
  if (h.ack !== undefined) w.varint(h.ack);
  if (h.req !== undefined) w.varint(h.req);
}

/** A frame split into its header and payload. */
export interface DecodedFrame {
  header: EnvelopeHeader;
  /** Reader positioned at the payload. */
  payload: WireReader;
}

/**
 * Decodes the header without touching the payload.
 * @throws WireError `invalid_message` on reserved flag bits or `RESPONSE` without `req`.
 */
export function readHeader(frame: Uint8Array): DecodedFrame {
  const r = new WireReader(frame);
  const flags = r.varint();
  if ((flags & RESERVED_MASK) !== 0 || flags > 0xff) throw new WireError('invalid_message', `reserved flag bits set: ${flags}`);
  const header: EnvelopeHeader = { flags, type: r.varint(), session: r.varint() };
  if ((flags & Flags.RELIABLE) !== 0) header.seq = r.varint();
  if ((flags & Flags.HAS_ACK) !== 0) header.ack = r.varint();
  if ((flags & Flags.HAS_REQ) !== 0) header.req = r.varint();
  if ((flags & Flags.RESPONSE) !== 0 && header.req === undefined) {
    throw new WireError('invalid_message', 'response without request id');
  }
  return { header, payload: r };
}

/** Throws unless the payload was consumed exactly. */
export function expectFullyRead(r: WireReader): void {
  if (r.remaining !== 0) throw new WireError('invalid_message', `${r.remaining} trailing bytes after payload`);
}
