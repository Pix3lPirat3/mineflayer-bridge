import {
  BridgeError,
  DepthModeValues,
  HeatmapBlobCodec,
  HighlightBlobCodec,
  VolumeBlobCodec,
  type KindPayloads,
  type Vec3i,
} from '@mineflayer-bridge/protocol';
import { encodeBlob, type BlobData } from './blobs.ts';
import { finite, oneOf, positive, unit } from './check.ts';
import { LayerHandle, type HandleHost } from './handle.ts';
import type {
  BlockHighlight,
  BlockHighlightOptions,
  BlockPositions,
  GhostBlocks,
  GhostBlocksOptions,
  Heatmap,
  HeatmapOptions,
  RetainedOptions,
  Volume,
} from './retained-types.ts';
import type { Vec3Like } from './types.ts';
import { toVec3 } from './util.ts';
import { VolumeImpl } from './volume.ts';

/** Kinds of the retained tier. */
export type RetainedKind = 'core:ghost-blocks' | 'core:heatmap' | 'core:block-highlight';

/** Highlights up to 4096 blocks travel inline in the object, larger sets as a blob. */
const INLINE_HIGHLIGHT_BLOCKS = 4096;
/** Encoded size of one volume section, used to size the encode buffer up front. */
const SECTION_BYTES_HINT = 8 * 1024 + 64;

const COLORMAPS: ReadonlySet<string> = new Set(['viridis', 'magma', 'turbo', 'diverging', 'grey']);
const MODES: ReadonlySet<string> = new Set(['outline', 'faces', 'both']);
const DEPTHS: ReadonlySet<string> = new Set(DepthModeValues);

/** What a retained handle needs from its layer. */
export interface RetainedHost extends HandleHost {
  /**
   * Declares `<layer>/<shapeId>` with its blob, resolving an inline style. Checks quotas first, so a
   * throw leaves the previous declaration in place.
   */
  putRetained<K extends RetainedKind>(shapeId: string, kind: K, data: KindPayloads[K], blob: BlobData | undefined, o: RetainedOptions): void;
  /** Removes the shape and its inline style. */
  removeShape(shapeId: string): void;
}

function blockVec(v: Vec3Like, what: string): Vec3i {
  const p = toVec3(v, what);
  if (!Number.isInteger(p.x) || !Number.isInteger(p.y) || !Number.isInteger(p.z)) throw new BridgeError('invalid_message', `${what} must be integer block coordinates`);
  return p;
}

/** Flattens positions to `x, y, z` triples of integer block coordinates. */
function toCells(positions: BlockPositions, what: string): Int32Array {
  if (positions instanceof Int32Array) {
    if (positions.length % 3 !== 0) throw new BridgeError('invalid_message', `flat ${what} must be x, y, z triples`);
    return positions.slice();
  }
  const out = new Int32Array(positions.length * 3);
  positions.forEach((p, i) => {
    const v = blockVec(p, what);
    out[i * 3] = v.x;
    out[i * 3 + 1] = v.y;
    out[i * 3 + 2] = v.z;
  });
  return out;
}

function toValues(values: readonly number[] | Float32Array, cells: number): Float32Array {
  const out = values instanceof Float32Array ? values.slice() : Float32Array.from(values);
  if (out.length !== cells) throw new BridgeError('invalid_message', `heatmap has ${cells} cells but ${out.length} values`);
  for (const v of out) if (!Number.isFinite(v)) throw new BridgeError('invalid_message', 'heatmap values must be finite numbers');
  return out;
}

/** Keeps the previous blob object when the content hashes the same, so nothing is re-sent. */
function same(next: BlobData, prev: BlobData | undefined): BlobData {
  return prev !== undefined && prev.hex === next.hex ? prev : next;
}

/**
 * One retained shape. Its data refers to a blob by hash; the blob is attached to the declared object,
 * so the store keeps it exactly as long as the object is declared.
 */
abstract class Retained<K extends RetainedKind, O extends RetainedOptions> extends LayerHandle<K, RetainedHost> {
  protected blob: BlobData | undefined;

  constructor(
    host: RetainedHost,
    id: string,
    kind: K,
    protected opts: O,
  ) {
    super(host, id, kind);
  }

  get blobHash(): string | undefined {
    return this.blob?.hex;
  }

  protected abstract encode(blob: BlobData | undefined, opts: O): KindPayloads[K];

  /** Encodes first, so a bad option or a quota throws before any state changes. */
  protected commit(blob: BlobData | undefined, opts: O): void {
    const data = this.encode(blob, opts);
    this.host.putRetained(this.id, this.kind, data, blob, opts);
    this.blob = blob;
    this.opts = opts;
    this.bound(opts);
  }

  protected drop(): void {
    this.host.removeShape(this.id);
  }

  /** Retained data refers to blobs by hash, which no editor changes. */
  protected editWrite(): undefined {
    return undefined;
  }
}

function asVolume(v: Volume): VolumeImpl {
  if (!(v instanceof VolumeImpl)) throw new BridgeError('invalid_message', 'ghost blocks need a volume from bridge.volume()');
  return v;
}

export class GhostBlocksImpl extends Retained<'core:ghost-blocks', GhostBlocksOptions> implements GhostBlocks {
  private volume: VolumeImpl;
  /** Volume revision the current blob was encoded from. */
  private encoded = -1;

  constructor(host: RetainedHost, id: string, volume: Volume, opts: GhostBlocksOptions) {
    super(host, id, 'core:ghost-blocks', opts);
    this.volume = asVolume(volume);
  }

  private blobFor(volume: VolumeImpl): BlobData {
    if (volume === this.volume && volume.revision === this.encoded && this.blob !== undefined) return this.blob;
    const value = volume.encode();
    return same(encodeBlob(VolumeBlobCodec, value, value.sections.length * SECTION_BYTES_HINT + 16), this.blob);
  }

  /** Writes the full state; used for the first declaration and for `layer.ghostBlocks` on the same id. */
  declare(volume: Volume, opts: GhostBlocksOptions): void {
    if (!this.alive) return;
    const v = asVolume(volume);
    const rev = v.revision;
    this.commit(this.blobFor(v), opts);
    this.volume = v;
    this.encoded = rev;
  }

  update(volume?: Volume, options?: GhostBlocksOptions): void {
    this.declare(volume ?? this.volume, options === undefined ? this.opts : { ...this.opts, ...options });
  }

  protected encode(blob: BlobData | undefined, o: GhostBlocksOptions): KindPayloads['core:ghost-blocks'] {
    const data: KindPayloads['core:ghost-blocks'] = { blob: blob!.hash, size: blob!.size };
    if (o.origin !== undefined) data.origin = blockVec(o.origin, 'origin');
    if (o.alpha !== undefined) data.alpha = unit('alpha', o.alpha);
    if (o.diffAgainstWorld !== undefined) data.diffAgainstWorld = o.diffAgainstWorld;
    if (o.hideMatching !== undefined) data.hideMatching = o.hideMatching;
    return data;
  }
}

export class HeatmapImpl extends Retained<'core:heatmap', HeatmapOptions> implements Heatmap {
  constructor(host: RetainedHost, id: string, opts: HeatmapOptions) {
    super(host, id, 'core:heatmap', opts);
  }

  declare(cells: BlockPositions, values: readonly number[] | Float32Array, opts: HeatmapOptions): void {
    if (!this.alive) return;
    const flat = toCells(cells, 'heatmap cells');
    const v = toValues(values, flat.length / 3);
    this.commit(same(encodeBlob(HeatmapBlobCodec, { cells: flat, values: v }, flat.byteLength + v.byteLength + 32), this.blob), opts);
  }

  update(cells?: BlockPositions, values?: readonly number[] | Float32Array, options?: HeatmapOptions): void {
    if (!this.alive) return;
    if ((cells === undefined) !== (values === undefined)) throw new BridgeError('invalid_message', 'heatmap update needs both cells and values, or neither');
    const opts = options === undefined ? this.opts : { ...this.opts, ...options };
    if (cells !== undefined && values !== undefined) this.declare(cells, values, opts);
    else this.commit(this.blob, opts);
  }

  protected encode(blob: BlobData | undefined, o: HeatmapOptions): KindPayloads['core:heatmap'] {
    const data: KindPayloads['core:heatmap'] = { blob: blob!.hash, size: blob!.size };
    if (o.min !== undefined) data.min = finite('min', o.min);
    if (o.max !== undefined) data.max = finite('max', o.max);
    if (o.colormap !== undefined) data.colormap = oneOf('colormap', o.colormap, COLORMAPS);
    if (o.cellSize !== undefined) data.cellSize = positive('cellSize', o.cellSize);
    if (o.alpha !== undefined) data.alpha = unit('alpha', o.alpha);
    if (o.depth !== undefined) data.depth = oneOf('depth mode', o.depth, DEPTHS);
    return data;
  }
}

export class BlockHighlightImpl extends Retained<'core:block-highlight', BlockHighlightOptions> implements BlockHighlight {
  /** Inline positions when the set is small enough; otherwise the blob carries them. */
  private inline: Int32Array | undefined;

  constructor(host: RetainedHost, id: string, opts: BlockHighlightOptions) {
    super(host, id, 'core:block-highlight', opts);
  }

  declare(positions: BlockPositions, opts: BlockHighlightOptions): void {
    if (!this.alive) return;
    const flat = toCells(positions, 'highlight positions');
    const inline = flat.length / 3 <= INLINE_HIGHLIGHT_BLOCKS ? flat : undefined;
    const prevInline = this.inline;
    this.inline = inline;
    try {
      this.commit(inline !== undefined ? undefined : same(encodeBlob(HighlightBlobCodec, { positions: flat }, flat.byteLength + 16), this.blob), opts);
    } catch (e) {
      this.inline = prevInline;
      throw e;
    }
  }

  update(positions?: BlockPositions, options?: BlockHighlightOptions): void {
    if (!this.alive) return;
    const opts = options === undefined ? this.opts : { ...this.opts, ...options };
    if (positions !== undefined) this.declare(positions, opts);
    else this.commit(this.blob, opts);
  }

  protected encode(blob: BlobData | undefined, o: BlockHighlightOptions): KindPayloads['core:block-highlight'] {
    const data: KindPayloads['core:block-highlight'] = blob === undefined ? { positions: this.inline ?? new Int32Array(0) } : { blob: blob.hash, size: blob.size };
    if (o.mode !== undefined) {
      if (!MODES.has(o.mode)) throw new BridgeError('invalid_message', `highlight mode must be outline, faces or both: ${String(o.mode)}`);
      data.mode = o.mode;
    }
    return data;
  }
}
