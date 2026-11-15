import { createHash } from 'node:crypto';
import * as zlib from 'node:zlib';
import { HeatmapBlobCodec, HighlightBlobCodec, VolumeBlobCodec, type MessagePayloads } from '@mineflayer-bridge/protocol';
import { BlobCache } from './blob-cache.ts';
import type { ModCore } from './core.ts';
import type { Session } from './session.ts';
import type { MockBlob, MockGhostBlocks, MockHeatmap, MockHighlights } from './types.ts';
import { decodeStruct, hex } from './util.ts';

/** The mod's blob cache is 256 MiB by default. */
const DEFAULT_BLOB_CACHE_BYTES = 256 * 1024 * 1024;
/** 32 MiB per blob. */
const DEFAULT_MAX_BLOB_BYTES = 32 * 1024 * 1024;
const DEFAULT_STREAM_WINDOW = 1024 * 1024;
/** Chunks carry at most 256 KiB. */
const MAX_CHUNK_BYTES = 256 * 1024;
/** `zstdDecompressSync` exists only on newer Node releases. */
const zstdDecompress = (zlib as Partial<typeof zlib>).zstdDecompressSync;

/** A blob stream the plugin offered. */
export interface InStream {
  readonly id: number;
  readonly hash: Uint8Array;
  readonly hex: string;
  /** Payload bytes after decoding. */
  readonly size: number;
  readonly codec: string;
  /** `waiting` until the in-flight budget has room, then `receiving`. */
  state: 'waiting' | 'receiving';
  readonly chunks: Uint8Array[];
  received: number;
  /** Bytes granted and not yet received. */
  credit: number;
}

/** Incoming blob streams and the blob cache shared by every session. */
export class Streams {
  private readonly cache: BlobCache;

  constructor(private readonly core: ModCore) {
    this.cache = new BlobCache(core.opts.blobCacheBytes ?? DEFAULT_BLOB_CACHE_BYTES, core.opts.blobDir);
  }

  get maxBlobBytes(): number {
    return this.core.opts.quotas?.blobBytes ?? DEFAULT_MAX_BLOB_BYTES;
  }

  list(): MockBlob[] {
    return this.cache.list();
  }

  get(hash: string): Uint8Array | undefined {
    return this.cache.get(hash);
  }

  ghost(key: string): MockGhostBlocks | undefined {
    const data = this.core.store.data(key, 'core:ghost-blocks');
    const payload = data === undefined ? undefined : this.cache.get(hex(data.blob));
    if (data === undefined || payload === undefined) return undefined;
    const { sections } = decodeStruct(VolumeBlobCodec, payload);
    const bySection = new Map(sections.map((sec) => [`${sec.x},${sec.y},${sec.z}`, sec]));
    let count = 0;
    for (const sec of sections) for (const i of sec.indices) if (i !== 0) count++;
    return {
      data,
      sections,
      count,
      get: (pos) => {
        const sec = bySection.get(`${pos.x >> 4},${pos.y >> 4},${pos.z >> 4}`);
        const i = sec?.indices[((pos.y & 15) * 16 + (pos.z & 15)) * 16 + (pos.x & 15)];
        return sec === undefined || i === undefined || i === 0 ? undefined : sec.palette[i];
      },
    };
  }

  heatmap(key: string): MockHeatmap | undefined {
    const data = this.core.store.data(key, 'core:heatmap');
    const payload = data === undefined ? undefined : this.cache.get(hex(data.blob));
    if (data === undefined || payload === undefined) return undefined;
    return { data, ...decodeStruct(HeatmapBlobCodec, payload) };
  }

  highlights(key: string): MockHighlights | undefined {
    const data = this.core.store.data(key, 'core:block-highlight');
    if (data === undefined) return undefined;
    if (data.blob === undefined) return { data, positions: data.positions ?? new Int32Array(0), fromBlob: false };
    const payload = this.cache.get(hex(data.blob));
    return payload === undefined ? undefined : { data, positions: decodeStruct(HighlightBlobCodec, payload).positions, fromBlob: true };
  }

  /** `have` for a cached hash, otherwise accept once the in-flight budget has room. */
  onOffer(s: Session, p: MessagePayloads['stream.offer']): void {
    const h = hex(p.hash);
    const refuse = (reason: string): void => {
      this.core.sendSession(s, 'stream.reject', { streamId: p.streamId, reason });
      this.core.emit('streamRejected', { session: this.core.info(s), hash: h, reason, by: 'mock' });
    };
    if (s.streams.has(p.streamId)) return refuse('duplicate_stream');
    if (p.hash.length !== 32) return refuse('invalid_hash');
    if (p.codec !== 'raw' && p.codec !== 'zstd') return refuse('unsupported_codec');
    if (p.size > this.maxBlobBytes) return refuse('quota_exceeded');
    if (this.cache.has(h)) {
      this.core.sendSession(s, 'stream.have', { streamId: p.streamId });
      this.core.emit('blobHave', { session: this.core.info(s), hash: h });
      return;
    }
    s.streams.set(p.streamId, { id: p.streamId, hash: p.hash, hex: h, size: p.size, codec: p.codec, state: 'waiting', chunks: [], received: 0, credit: 0 });
    this.admit(s);
  }

  onChunk(s: Session, p: MessagePayloads['stream.chunk']): void {
    const st = this.receiving(s, p.streamId);
    if (st === undefined) return;
    if (p.offset !== st.received) return this.fail(s, st, 'bad_offset');
    if (p.data.length > MAX_CHUNK_BYTES) return this.fail(s, st, 'chunk_too_large');
    if (p.data.length > st.credit) return this.fail(s, st, 'window_exceeded');
    // The sender uses zstd only when it is smaller, so a stream never carries more than the payload size.
    if (st.received + p.data.length > st.size) return this.fail(s, st, 'too_large');
    st.chunks.push(p.data);
    st.received += p.data.length;
    st.credit -= p.data.length;
    if (this.core.opts.streamWindow !== 0 && p.data.length > 0) {
      st.credit += p.data.length;
      this.core.sendSession(s, 'stream.window', { streamId: st.id, window: p.data.length });
    }
  }

  /** Decode, check size and hash, then cache. */
  onEnd(s: Session, streamId: number): void {
    const st = this.receiving(s, streamId);
    if (st === undefined) return;
    const wire = Buffer.concat(st.chunks);
    let payload: Uint8Array;
    try {
      if (st.codec === 'raw') payload = wire;
      else if (zstdDecompress === undefined) return this.fail(s, st, 'unsupported_codec');
      else payload = zstdDecompress(wire, { maxOutputLength: st.size });
    } catch {
      return this.fail(s, st, 'decode_failed');
    }
    if (payload.length !== st.size || createHash('sha256').update(payload).digest('hex') !== st.hex) return this.fail(s, st, 'hash_mismatch');
    s.streams.delete(st.id);
    this.cache.put(st.hex, new Uint8Array(payload.buffer, payload.byteOffset, payload.length));
    this.core.emit('blobStored', { session: this.core.info(s), hash: st.hex, size: st.size, codec: st.codec });
    this.admit(s);
  }

  /** The plugin cancelled a stream it offered; the mock offers none of its own. */
  onReject(s: Session, p: MessagePayloads['stream.reject']): void {
    const st = s.streams.get(p.streamId);
    if (st === undefined) return;
    s.streams.delete(st.id);
    this.core.emit('streamRejected', { session: this.core.info(s), hash: st.hex, reason: p.reason, by: 'plugin' });
    this.admit(s);
  }

  /** The stream when it is receiving; otherwise rejects the id as `unknown_stream`. */
  private receiving(s: Session, streamId: number): InStream | undefined {
    const st = s.streams.get(streamId);
    if (st?.state === 'receiving') return st;
    this.core.sendSession(s, 'stream.reject', { streamId, reason: 'unknown_stream' });
    return undefined;
  }

  /** Accepts waiting offers in order while their payloads fit in `budget.streamBytesInFlight`. */
  private admit(s: Session): void {
    let inFlight = 0;
    for (const st of s.streams.values()) if (st.state === 'receiving') inFlight += st.size;
    const window = this.core.opts.streamWindow ?? DEFAULT_STREAM_WINDOW;
    for (const st of s.streams.values()) {
      if (st.state !== 'waiting') continue;
      if (inFlight > 0 && inFlight + st.size > this.core.player.budget.streamBytesInFlight) return;
      st.state = 'receiving';
      st.credit = window;
      inFlight += st.size;
      this.core.sendSession(s, 'stream.accept', { streamId: st.id, window });
    }
  }

  private fail(s: Session, st: InStream, reason: string): void {
    s.streams.delete(st.id);
    this.core.sendSession(s, 'stream.reject', { streamId: st.id, reason });
    this.core.emit('streamRejected', { session: this.core.info(s), hash: st.hex, reason, by: 'mock' });
    this.admit(s);
  }
}
