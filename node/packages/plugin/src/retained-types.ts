import type { DepthMode, KindPayloads, Vec3i } from '@mineflayer-bridge/protocol';
import type { LayerObjectHandle } from './traits-types.ts';
import type { ShapeOptions, Vec3Like } from './types.ts';

/** A block state string such as `minecraft:oak_stairs[facing=east]`, or an id into the volume's palette. */
export type BlockStateLike = string | number;

/** Options of `bridge.volume()`. */
export interface VolumeOptions {
  /** Block states for numeric ids: with it, `set(pos, 2)` writes `palette[2]`. */
  palette?: readonly string[];
}

/** Inclusive block bounds of a volume. */
export interface VolumeBounds {
  readonly min: Vec3i;
  readonly max: Vec3i;
}

/**
 * A ghost-block volume, built with `bridge.volume()`. Blocks are written straight into paletted
 * 16x16x16 sections, so large plans cost no per-block objects. Positions are integer block
 * coordinates (relative to the shape's `origin` when one is given). Invalid positions or states
 * throw `BridgeError` `invalid_message`. Not thread-safe; use from one event loop.
 */
export interface Volume {
  /** Number of set blocks. */
  readonly count: number;
  /** Sets one block. */
  set(pos: Vec3Like, state: BlockStateLike): this;
  /** The state at `pos`, or `undefined` when unset. */
  get(pos: Vec3Like): string | undefined;
  /** Unsets one block. Returns false when it was not set. */
  delete(pos: Vec3Like): boolean;
  /**
   * Sets every block in the box between two corners, inclusive, in any order.
   * @throws BridgeError `quota_exceeded` above 16,777,216 blocks per call.
   */
  fill(min: Vec3Like, max: Vec3Like, state: BlockStateLike): this;
  /** Sets many blocks to one state, or each to its own state (`states.length` must match). */
  setMany(positions: readonly Vec3Like[] | Int32Array, states: BlockStateLike | readonly BlockStateLike[]): this;
  /** Unsets every block. */
  clear(): this;
  /** Inclusive bounds of the set blocks, or `undefined` when empty. Scans every section. */
  bounds(): VolumeBounds | undefined;
}

/** Fields common to retained shapes. They are not persisted, as the plugin does not store their blobs. */
export type RetainedOptions = Omit<ShapeOptions, 'persistent'>;

/** Options of `layer.ghostBlocks`. */
export interface GhostBlocksOptions extends RetainedOptions {
  /** Added to every position of the volume, in blocks. Lets one volume be placed anywhere. */
  origin?: Vec3Like;
  /** Opacity, 0 to 1. Default 0.5. */
  alpha?: number;
  /** Colour each block as missing, extra or wrong state against the client's world. Default false. */
  diffAgainstWorld?: boolean;
  /** With `diffAgainstWorld`, blocks that already match the world are not drawn. Default true. */
  hideMatching?: boolean;
}

/** Built-in heatmap colour maps. */
export type Colormap = 'viridis' | 'magma' | 'turbo' | 'diverging' | 'grey';

/** Options of `layer.heatmap`. A heatmap has no style; its colours come from the value range and map. */
export interface HeatmapOptions extends Omit<RetainedOptions, 'style' | 'color'> {
  /** Value mapped to the start of the colour map. Default: the smallest value. */
  min?: number;
  /** Value mapped to the end of the colour map. Default: the largest value. */
  max?: number;
  /** Default `viridis`. */
  colormap?: Colormap;
  /** Edge length of a cell in blocks. Default 1. */
  cellSize?: number;
  /** Opacity, 0 to 1. Default 0.4. */
  alpha?: number;
  /** Default `test`. */
  depth?: DepthMode;
}

/** How highlighted blocks are drawn. */
export type HighlightMode = 'outline' | 'faces' | 'both';

/** Options of `layer.blocks`. */
export interface BlockHighlightOptions extends RetainedOptions {
  /** Default `outline`. Internal edges between highlighted blocks are merged by the mod. */
  mode?: HighlightMode;
}

/** Cell positions of a heatmap or highlight: block positions or flat `x, y, z` triples. */
export type BlockPositions = readonly Vec3Like[] | Int32Array;

/**
 * A retained shape. Its data travels as a blob by hash; the blob is kept while the shape is
 * declared and released when it is removed or replaced. Updates with unchanged content send
 * nothing. After `remove`, or when another shape takes the id, the handle is inert. Player edits of
 * retained shapes answer `unsupported`, as their data lives in blobs.
 */
export interface RetainedHandle<D> extends LayerObjectHandle<D> {
  /** Lowercase hex SHA-256 of the current blob, or `undefined` for an inline highlight. */
  readonly blobHash: string | undefined;
}

/** Handle of `layer.ghostBlocks`. */
export interface GhostBlocks extends RetainedHandle<KindPayloads['core:ghost-blocks']> {
  /**
   * Re-declares the shape. Without `volume` the current volume is used again, so edits made to it
   * since the last call are picked up; options are merged into the current ones.
   * @throws BridgeError `quota_exceeded` when the blob is over the mod's size limit or would push
   * the bytes in flight past it; `invalid_message` for bad options.
   */
  update(volume?: Volume, options?: GhostBlocksOptions): void;
}

/** Handle of `layer.heatmap`. */
export interface Heatmap extends RetainedHandle<KindPayloads['core:heatmap']> {
  /** Replaces cells and values (both or neither) and merges options. Same errors as `layer.heatmap`. */
  update(cells?: BlockPositions, values?: readonly number[] | Float32Array, options?: HeatmapOptions): void;
}

/** Handle of `layer.blocks`. */
export interface BlockHighlight extends RetainedHandle<KindPayloads['core:block-highlight']> {
  /** Replaces the positions when given and merges options. */
  update(positions?: BlockPositions, options?: BlockHighlightOptions): void;
}

/** Retained-tier members of a layer. */
export interface RetainedFactory {
  /**
   * Ghost blocks from a volume: paletted sections sent as a blob.
   * @throws BridgeError `quota_exceeded` when the encoded volume is over the mod's blob limit
   * (32 MiB by default) or would push the blob bytes in flight past 128 MiB (retryable);
   * `invalid_message` when `volume` does not come from `bridge.volume()` or an option is invalid.
   */
  ghostBlocks(id: string, volume: Volume, options?: GhostBlocksOptions): GhostBlocks;
  /**
   * Coloured cells at integer block positions with one value each (`values.length` equals the number of
   * cells). Sent as a blob. Same quota errors as {@link ghostBlocks}.
   */
  heatmap(id: string, cells: BlockPositions, values: readonly number[] | Float32Array, options?: HeatmapOptions): Heatmap;
  /**
   * Many block outlines or faces sharing one style. Up to 4096 positions travel inline in the object;
   * more go as a blob. Same quota errors as {@link ghostBlocks}.
   */
  blocks(id: string, positions: BlockPositions, options?: BlockHighlightOptions): BlockHighlight;
}
