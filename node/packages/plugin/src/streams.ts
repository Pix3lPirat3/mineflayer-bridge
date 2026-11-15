import { BridgeError, type MessageName, type MessagePayloads } from '@mineflayer-bridge/protocol';
import type { BlobData, BlobLimits, BlobTable } from './blobs.ts';

/** A chunk carries at most 256 KiB. */
const MAX_CHUNK_BYTES = 256 * 1024;
/** Room left in a frame for the envelope and the chunk's other fields, in bytes. */
const CHUNK_FRAME_OVERHEAD = 256;

/** What the sender needs from its session. */
export interface StreamHost {
  readonly blobs: BlobTable;
  /** Current blob limits of the connection, in bytes. */
  limits(): BlobLimits;
  /** Largest frame the mod accepts, in bytes. */
  maxFrameBytes(): number;
  /** Logs a reliable message for sending, in order with everything else. */
  reliable<N extends StreamMessage>(type: N, payload: MessagePayloads[N]): void;
  /** A transfer failed at the mod. */
  failed(error: BridgeError): void;
}

/** Stream messages the plugin sends. */
export type StreamMessage = Extract<MessageName, 'stream.offer' | 'stream.chunk' | 'stream.end' | 'stream.reject'>;

interface OutStream {
  readonly id: number;
  readonly blob: BlobData;
  readonly bytes: Uint8Array;
  state: 'offered' | 'sending';
  /** Bytes the mod has granted and not yet received. */
  credit: number;
  /** Next byte of {@link bytes} to send. */
  offset: number;
}

/**
 * Sender side of blob streams for one session: offers each blob a declared object refers to, skips
 * blobs the mod already has, sends chunks only within the granted window and ends each transfer so
 * the mod can verify the hash. State lives for one mod session; a resumed session keeps it because
 * the outbound log replays what was sent, a fresh one starts over.
 *
 * Invariant: a blob is offered at most once per session, unless it was released in between.
 */
export class StreamSender {
  private readonly host: StreamHost;
  private nextId = 1;
  private readonly byHex = new Map<string, OutStream>();
  private readonly byId = new Map<number, OutStream>();
  /** Blobs waiting for room in the in-flight budget, in reference order. */
  private readonly queue = new Map<string, BlobData>();
  /** Blobs the mod holds, or refused, in this session. */
  private readonly done = new Set<string>();
  /** Blob of each stream that ended in this session, so a late `hash_mismatch` can be attributed. */
  private readonly ended = new Map<number, string>();
  private lastSent = 0;

  constructor(host: StreamHost) {
    this.host = host;
  }

  /** Forgets the previous session's transfers; every referenced blob is offered again. */
  reset(): void {
    this.byHex.clear();
    this.byId.clear();
    this.queue.clear();
    this.done.clear();
    this.ended.clear();
    this.host.blobs.resetStates();
  }

  /** Makes sure `blob` reaches the mod in this session. Call before sending the object that refers to it. */
  want(blob: BlobData): void {
    if (this.done.has(blob.hex) || this.byHex.has(blob.hex) || this.queue.has(blob.hex)) return;
    this.queue.set(blob.hex, blob);
  }

  /** Payload bytes of offers the mod has not finished with. */
  inFlight(): number {
    let n = 0;
    for (const s of this.byId.values()) n += s.blob.size;
    return n;
  }

  /**
   * Offers queued blobs while they fit in the in-flight budget. The first offer always goes out, so a
   * blob as large as the budget still moves.
   */
  offerQueued(): void {
    if (this.queue.size === 0) return;
    const limit = this.host.limits().streamBytesInFlight;
    let inFlight = this.inFlight();
    for (const [hex, blob] of this.queue) {
      if (!this.host.blobs.has(hex)) {
        this.queue.delete(hex);
        continue;
      }
      if (inFlight > 0 && inFlight + blob.size > limit) return;
      this.queue.delete(hex);
      const wire = blob.wire();
      const s: OutStream = { id: this.nextId++, blob, bytes: wire.bytes, state: 'offered', credit: 0, offset: 0 };
      this.byHex.set(hex, s);
      this.byId.set(s.id, s);
      inFlight += blob.size;
      this.host.reliable('stream.offer', { streamId: s.id, hash: blob.hash, size: blob.size, codec: wire.codec });
    }
  }

  /** True when a chunk could go out now. */
  get sendable(): boolean {
    for (const s of this.byId.values()) if (s.state === 'sending' && s.credit > 0 && s.offset < s.bytes.length) return true;
    return false;
  }

  /**
   * Sends at most one chunk (and the `stream.end` it completes), so other traffic interleaves between
   * chunks. Streams take turns by id. Returns true when another chunk could go out right away.
   */
  chunk(): boolean {
    const ready = [...this.byId.values()].filter((s) => s.state === 'sending' && s.credit > 0 && s.offset < s.bytes.length);
    if (ready.length === 0) return false;
    const s = ready.find((x) => x.id > this.lastSent) ?? ready[0]!;
    this.lastSent = s.id;
    const max = Math.min(MAX_CHUNK_BYTES, this.host.maxFrameBytes() - CHUNK_FRAME_OVERHEAD);
    const len = Math.min(max, s.credit, s.bytes.length - s.offset);
    this.host.reliable('stream.chunk', { streamId: s.id, offset: s.offset, data: s.bytes.subarray(s.offset, s.offset + len) });
    s.offset += len;
    s.credit -= len;
    if (s.offset === s.bytes.length) {
      this.host.reliable('stream.end', { streamId: s.id });
      this.ended.set(s.id, s.blob.hex);
      this.finish(s, 'settled');
    }
    return this.sendable;
  }

  onAccept(p: MessagePayloads['stream.accept']): void {
    const s = this.byId.get(p.streamId);
    if (s === undefined || s.state !== 'offered') return;
    s.state = 'sending';
    s.credit += p.window;
  }

  /** `stream.window` grants more bytes on top of what is left. */
  onWindow(p: MessagePayloads['stream.window']): void {
    const s = this.byId.get(p.streamId);
    if (s !== undefined && s.state === 'sending') s.credit += p.window;
  }

  onHave(p: MessagePayloads['stream.have']): void {
    const s = this.byId.get(p.streamId);
    if (s !== undefined) this.finish(s, 'settled');
  }

  /**
   * The mod refused or failed a transfer. The blob is not offered again in this session, as the same
   * bytes would fail the same way; a new session tries again.
   */
  onReject(p: MessagePayloads['stream.reject']): void {
    const s = this.byId.get(p.streamId);
    const hex = s?.blob.hex ?? this.ended.get(p.streamId);
    this.ended.delete(p.streamId);
    if (s !== undefined) this.finish(s, 'failed');
    else if (hex !== undefined) this.host.blobs.mark(hex, 'failed');
    if (hex === undefined) return;
    const code = p.reason === 'quota_exceeded' ? 'quota_exceeded' : 'invalid_message';
    this.host.failed(new BridgeError(code, `the mod rejected blob ${hex}: ${p.reason}`, { details: { hash: hex, reason: p.reason } }));
  }

  /** The last object referring to `blob` went away: drop it, abandoning an unfinished transfer. */
  release(blob: BlobData): void {
    this.queue.delete(blob.hex);
    this.done.delete(blob.hex);
    const s = this.byHex.get(blob.hex);
    if (s === undefined) return;
    this.byHex.delete(blob.hex);
    this.byId.delete(s.id);
    this.host.reliable('stream.reject', { streamId: s.id, reason: 'cancelled' });
  }

  private finish(s: OutStream, state: 'settled' | 'failed'): void {
    this.byHex.delete(s.blob.hex);
    this.byId.delete(s.id);
    this.done.add(s.blob.hex);
    this.host.blobs.mark(s.blob.hex, state);
  }
}
