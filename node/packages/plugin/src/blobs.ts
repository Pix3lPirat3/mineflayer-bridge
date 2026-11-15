import { createHash } from 'node:crypto';
import * as zlib from 'node:zlib';
import { BridgeError, WireWriter, type Codec } from '@mineflayer-bridge/protocol';

/** Only blobs over 4 KiB are compressed. */
const ZSTD_THRESHOLD_BYTES = 4 * 1024;
/** Default size cap of one blob payload, in bytes. */
export const DEFAULT_MAX_BLOB_BYTES = 32 * 1024 * 1024;
/** Default cap of blob bytes in flight per session. */
export const DEFAULT_STREAM_BYTES_IN_FLIGHT = 128 * 1024 * 1024;

/** Stream codec of a blob transfer. */
export type StreamCodec = 'raw' | 'zstd';

const zstdCompress = (zlib as Partial<typeof zlib>).zstdCompressSync;

/**
 * One immutable blob: the MessagePack payload of a blob struct (`VolumeBlob`, `HeatmapBlob`,
 * `HighlightBlob`) and its SHA-256, which objects carry as their `blob` field. The transfer form is
 * computed on first use and kept with the blob.
 */
export class BlobData {
  readonly payload: Uint8Array;
  readonly hash: Uint8Array;
  /** Lowercase hex of {@link hash}; the identity of the blob. */
  readonly hex: string;
  private transfer: { codec: StreamCodec; bytes: Uint8Array } | undefined;

  constructor(payload: Uint8Array) {
    this.payload = payload;
    const digest = createHash('sha256').update(payload).digest();
    this.hash = new Uint8Array(digest);
    this.hex = digest.toString('hex');
  }

  /** Payload size in bytes, as sent in the object's `size` field and in `stream.offer`. */
  get size(): number {
    return this.payload.length;
  }

  /**
   * The bytes the stream carries. zstd is used for payloads over 4 KiB when Node provides it and the
   * result is smaller; otherwise raw. The stream therefore never carries more than {@link size} bytes.
   */
  wire(): { codec: StreamCodec; bytes: Uint8Array } {
    if (this.transfer !== undefined) return this.transfer;
    let out: { codec: StreamCodec; bytes: Uint8Array } = { codec: 'raw', bytes: this.payload };
    if (this.payload.length > ZSTD_THRESHOLD_BYTES && zstdCompress !== undefined) {
      const packed = zstdCompress(this.payload);
      if (packed.length < this.payload.length) out = { codec: 'zstd', bytes: new Uint8Array(packed.buffer, packed.byteOffset, packed.length) };
    }
    this.transfer = out;
    return out;
  }
}

/** Encodes `value` with a generated struct codec and wraps it as a {@link BlobData}. */
export function encodeBlob<T>(codec: Codec<T>, value: T, sizeHint = 4096): BlobData {
  const w = new WireWriter(sizeHint);
  codec.write(w, value);
  return new BlobData(w.toBytes());
}

/** Transfer state of a blob within the current mod session. */
export type BlobState = 'pending' | 'settled' | 'failed';

interface Entry {
  readonly blob: BlobData;
  refs: number;
  state: BlobState;
}

/**
 * Blobs referenced by declared objects, counted per object. A blob is kept while any declared object
 * refers to it and dropped when the last reference goes; the session is told so it can abandon an
 * unfinished transfer.
 */
export class BlobTable {
  private readonly entries = new Map<string, Entry>();
  /** Called when the last reference to a blob is dropped. */
  onRelease: ((blob: BlobData) => void) | undefined;
  private readonly releaseListeners = new Set<(blob: BlobData) => void>();

  /** Adds a listener for the last reference to a blob going away, next to {@link onRelease}. Returns a remover. */
  onReleased(fn: (blob: BlobData) => void): () => void {
    this.releaseListeners.add(fn);
    return () => {
      this.releaseListeners.delete(fn);
    };
  }

  get size(): number {
    return this.entries.size;
  }

  has(hex: string): boolean {
    return this.entries.has(hex);
  }

  get(hex: string): BlobData | undefined {
    return this.entries.get(hex)?.blob;
  }

  state(hex: string): BlobState | undefined {
    return this.entries.get(hex)?.state;
  }

  acquire(blob: BlobData): void {
    const e = this.entries.get(blob.hex);
    if (e !== undefined) e.refs++;
    else this.entries.set(blob.hex, { blob, refs: 1, state: 'pending' });
  }

  release(blob: BlobData): void {
    const e = this.entries.get(blob.hex);
    if (e === undefined) return;
    if (--e.refs > 0) return;
    this.entries.delete(blob.hex);
    this.onRelease?.(e.blob);
    for (const fn of [...this.releaseListeners]) fn(e.blob);
  }

  /** Records the outcome of a transfer; ignored for blobs no longer referenced. */
  mark(hex: string, state: BlobState): void {
    const e = this.entries.get(hex);
    if (e !== undefined) e.state = state;
  }

  /** A new mod session knows nothing yet: every blob must be offered again. */
  resetStates(): void {
    for (const e of this.entries.values()) e.state = 'pending';
  }

  /** Bytes of referenced blobs not yet held by the mod, excluding `except`. */
  pendingBytes(except?: BlobData): number {
    let n = 0;
    for (const e of this.entries.values()) if (e.state === 'pending' && e.blob !== except) n += e.blob.size;
    return n;
  }

  /**
   * Rejects a new blob that would exceed the per-blob cap or the bytes in flight. A blob already
   * referenced, or one replacing `replacing` (whose only reference goes away), does not count
   * twice.
   * @throws BridgeError `quota_exceeded`; retryable for the in-flight cap, as transfers drain it.
   */
  check(blob: BlobData, limits: BlobLimits, replacing: BlobData | undefined): void {
    if (blob.size > limits.maxBlobBytes) {
      throw new BridgeError('quota_exceeded', `blob of ${blob.size} bytes exceeds the ${limits.maxBlobBytes} byte limit`, { details: { size: blob.size, limit: limits.maxBlobBytes } });
    }
    if (this.entries.has(blob.hex)) return;
    const prev = replacing === undefined ? undefined : this.entries.get(replacing.hex);
    const freed = prev !== undefined && prev.refs === 1 ? prev.blob : undefined;
    const inFlight = this.pendingBytes(freed) + blob.size;
    if (inFlight > limits.streamBytesInFlight) {
      throw new BridgeError('quota_exceeded', `${inFlight} blob bytes would be in flight; the limit is ${limits.streamBytesInFlight}`, {
        retryable: true,
        details: { inFlight, limit: limits.streamBytesInFlight },
      });
    }
  }
}

/** Blob limits in bytes, as the mod advertises them in `welcome` and `budget.update`. */
export interface BlobLimits {
  readonly maxBlobBytes: number;
  readonly streamBytesInFlight: number;
}
