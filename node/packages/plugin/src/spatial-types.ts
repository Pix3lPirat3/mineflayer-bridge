import type { PointData, RouteData, Vec3d, Vec3i, WorldKey, ZoneData } from '@mineflayer-bridge/protocol';
import type { BlockBox, BoxLike, RouteClosest, RouteInterpolation, RouteSampleOptions, ZoneOp } from './geometry.ts';
import type { LayerObjectHandle, TraitOptions } from './traits-types.ts';
import type { ColorLike, Vec3Like } from './types.ts';

export type { BlockBox, BoxLike, RouteClosest, RouteGeometry, RouteInterpolation, RouteSampleOptions, ZoneGeometry, ZoneOp } from './geometry.ts';

/** Kinds of spatial definitions. */
export type SpatialKind = 'core:zone' | 'core:point' | 'core:route';

/**
 * One box of a zone: a `[corner, corner]` pair (op `add`), the same pair with an op, or
 * `{ min, max, op? }`. Corners are floored to blocks and may be in any order; both are inside.
 */
export type ZoneBoxInput = readonly [Vec3Like, Vec3Like] | readonly [Vec3Like, Vec3Like, ZoneOp] | { readonly min: Vec3Like; readonly max: Vec3Like; readonly op?: ZoneOp };

/**
 * A zone definition (`core:zone`). Give either `boxes` (combined in list order) or a polygon prism
 * (`polygon` with at least three vertices, of which `x` and `z` are used, plus `minY`/`maxY`).
 */
export interface ZoneInput {
  name: string;
  boxes?: readonly ZoneBoxInput[];
  /** Prism vertices in blocks; floored. */
  polygon?: readonly Vec3Like[];
  /** Lowest block layer of the prism, inclusive. */
  minY?: number;
  /** Highest block layer of the prism, inclusive. */
  maxY?: number;
  tags?: readonly string[];
  /** Outline colour. Default `0xFF3B82F6`. */
  color?: ColorLike;
  /** Fill alpha, 0 to 1. Default 0.08. */
  fill?: number;
  /** Show the name above the zone. Default true. */
  showLabel?: boolean;
  /** The player may edit it with gizmos. Default true. */
  editable?: boolean;
}

/** A point definition (`core:point`). */
export interface PointInput {
  name: string;
  /** Position in blocks. */
  pos: Vec3Like;
  /** Facing in degrees, Minecraft convention (0 is south, 90 is west). */
  yaw?: number;
  /** Looking up or down in degrees, Minecraft convention (-90 up, 90 down). */
  pitch?: number;
  tags?: readonly string[];
  /** Marker colour. Default `0xFFF59E0B`. */
  color?: ColorLike;
  /** Item id shown above the point, such as `minecraft:chest`. */
  icon?: string;
  /** The player may edit it with gizmos. Default true. */
  editable?: boolean;
}

/** A route node: a position, or a position with per-node data (MessagePack-encodable, e.g. `{ wait: 20 }`). */
export type RouteNodeInput = Vec3Like | { readonly pos: Vec3Like; readonly data?: unknown };

/** A route definition (`core:route`). */
export interface RouteInput {
  name: string;
  nodes: readonly RouteNodeInput[];
  /** The last node connects back to the first. Default false. */
  looped?: boolean;
  /** Default `linear`. `catmull-rom` is accepted as a spelling of `catmull_rom`. */
  interpolation?: RouteInterpolation | 'catmull-rom';
  tags?: readonly string[];
  /** Line colour. Default `0xFF22C55E`. */
  color?: ColorLike;
  /** Line width in pixels. Default 2. */
  width?: number;
  /** The player may edit it with gizmos. Default true. */
  editable?: boolean;
}

type OptionalKeys<T> = { [K in keyof T]-?: object extends Pick<T, K> ? K : never }[keyof T];

/** A partial definition: fields given replace the current ones; `null` clears an optional field. */
export type SpatialPatch<I> = { [K in keyof I]?: K extends OptionalKeys<I> ? I[K] | null : I[K] };

/** Geometry of a zone, shared by shared entries and private handles. */
export interface ZoneShape {
  readonly data: ZoneData;
  /** True when the block containing `pos` is in the zone. */
  contains(pos: Vec3Like): boolean;
  /** The smallest block box holding the zone; `undefined` when it is empty. */
  bounds(): BlockBox | undefined;
  /** True when the zone shares a block with `box`. */
  intersects(box: BoxLike): boolean;
  /** Distance in blocks from `pos` to the zone; 0 inside. */
  distanceTo(pos: Vec3Like): number;
}

/** Geometry of a point. */
export interface PointShape {
  readonly data: PointData;
  /** Position in blocks. */
  readonly pos: Vec3d;
  /** Straight-line distance in blocks. */
  distanceTo(pos: Vec3Like): number;
}

/** Geometry of a route. */
export interface RouteShape {
  readonly data: RouteData;
  /** The interpolated polyline in blocks (see `routePoints`). */
  points(options?: RouteSampleOptions): Vec3d[];
  /** The blocks the route passes through (see `routeBlocks`). */
  blocks(options?: RouteSampleOptions): Vec3i[];
  /** Polyline length in blocks. */
  length(options?: RouteSampleOptions): number;
  /** The nearest point of the polyline, or `undefined` without nodes. */
  closest(pos: Vec3Like, options?: RouteSampleOptions): RouteClosest | undefined;
  /** Distance in blocks from `pos` to the polyline. */
  distanceTo(pos: Vec3Like): number;
}

/** Common fields of a shared definition as last received from the mod. Entries are immutable snapshots. */
export interface SharedEntry {
  /** Local id: the last part of the key. */
  readonly id: string;
  /** Full key, `shared/bridge:zones/<id>`. */
  readonly key: string;
  readonly rev: number;
  /** The world it is bound to, or `undefined` when global. */
  readonly world: WorldKey | undefined;
  readonly name: string;
  readonly tags: readonly string[];
}

/** A shared zone in the view. */
export interface SharedZone extends SharedEntry, ZoneShape {}
/** A shared point in the view. */
export interface SharedPoint extends SharedEntry, PointShape {}
/** A shared route in the view. */
export interface SharedRoute extends SharedEntry, RouteShape {}

/** What a create or update resolved with. */
export interface SpatialRef {
  readonly id: string;
  readonly key: string;
  /** The revision the mod assigned. */
  readonly rev: number;
}

/** Options for creating a shared definition. */
export interface SpatialCreateOptions {
  /** Local id. Default: a random UUID. Must not contain `/`. */
  id?: string;
  /** World binding. Default: the bot's current world when known, otherwise global. */
  world?: WorldKey | 'global';
  /** Request timeout in ms, up to the schema's 10000. */
  timeoutMs?: number;
}

/** Options for updating or removing a shared definition. */
export interface SpatialWriteOptions {
  /** Revision the write is based on. Default: the revision in the view. */
  expectRev?: number;
  /**
   * Retries on `conflict`: the number of further attempts, or `true` for 3. Each retry waits for the
   * view to catch up with the newer revision and re-applies the patch (a function patch is called
   * again with the new entry). Default: no retry.
   */
  retry?: boolean | number;
  /** Request timeout in ms per attempt, up to the schema's 10000. */
  timeoutMs?: number;
}

/** Options for {@link SharedPoints.nearest} and {@link SharedRoutes.nearest}. */
export interface NearestOptions {
  /** Only entries with this tag. */
  tag?: string;
  /** Only entries at most this far away, in blocks. */
  maxDistance?: number;
}

/**
 * A live view of the shared definitions of one kind in the bot's world: objects under
 * `shared/bridge:zones/` that the mod sends this session. The first call subscribes the session to
 * the spatial kinds; the view then fills asynchronously.
 *
 * Writes go through `shared.op` with revisions and reject with `BridgeError` code `conflict` (with
 * `details.rev`) when stale and retries are exhausted, `invalid_message` for a bad definition or an
 * id the view does not hold (without `expectRev`), `timeout`, `disconnected`, or `cancelled` when
 * the extension unloads.
 */
export interface SpatialView<E extends SharedEntry, I> {
  readonly kind: SpatialKind;
  /** Every entry in the view. */
  list(): E[];
  /** An entry by local id or full key. */
  get(idOrKey: string): E | undefined;
  /** Entries carrying `tag`. */
  byTag(tag: string): E[];
  /** Entries with a part in `box`. */
  intersecting(box: BoxLike): E[];
  /** A new entry arrived. Returns an unsubscribe function. */
  on(event: 'add', fn: (entry: E) => void): () => void;
  /** An entry changed; `previous` is the entry before the change. */
  on(event: 'change', fn: (entry: E, previous: E) => void): () => void;
  /** An entry was removed; the argument is its last state. */
  on(event: 'remove', fn: (entry: E) => void): () => void;
  /** Creates a shared definition. Rejects with `conflict` when the id exists. */
  create(definition: I, options?: SpatialCreateOptions): Promise<SpatialRef>;
  /** Patches a shared definition at the view's revision (or `expectRev`). */
  update(idOrKey: string, patch: SpatialPatch<I> | ((current: E) => SpatialPatch<I>), options?: SpatialWriteOptions): Promise<SpatialRef>;
  /** Removes a shared definition at the view's revision (or `expectRev`). */
  remove(idOrKey: string, options?: SpatialWriteOptions): Promise<void>;
}

/** `shared.zones`. */
export interface SharedZones extends SpatialView<SharedZone, ZoneInput> {
  /** Zones holding the block that contains `pos`, answered from a local column index. */
  containing(pos: Vec3Like): SharedZone[];
}

/** `shared.points`. */
export interface SharedPoints extends SpatialView<SharedPoint, PointInput> {
  /** The nearest point, or `undefined` when none qualifies. */
  nearest(pos: Vec3Like, options?: NearestOptions): SharedPoint | undefined;
  /** Points within `radius` blocks, nearest first. */
  within(pos: Vec3Like, radius: number, options?: { tag?: string }): SharedPoint[];
}

/** `shared.routes`. */
export interface SharedRoutes extends SpatialView<SharedRoute, RouteInput> {
  /** The route whose polyline passes nearest, or `undefined` when none qualifies. */
  nearest(pos: Vec3Like, options?: NearestOptions): SharedRoute | undefined;
}

/** Options for private definitions on a layer. */
export interface PrivateSpatialOptions extends TraitOptions {
  /** Persist through the plugin store and re-declare on start. */
  persistent?: boolean;
  /** World binding. Default: the bot's current world when known, otherwise global. */
  world?: WorldKey | 'global';
}

/**
 * A private definition declared on one of the bot's layers. Writes go out as field patches in the
 * next batch. After `remove()`, or once its layer is cleared or its extension unloads, the handle is
 * inert: writes are ignored and edits are no longer offered to it. Without `editable` traits every
 * field is editable while the definition's `editable` field is true.
 */
export interface SpatialHandle<D, I> extends LayerObjectHandle<D> {
  readonly kind: SpatialKind;
  /** Current data, including accepted player edits. */
  readonly data: D;
  /** Revision of the declared object; 0 once removed. */
  readonly rev: number;
  /**
   * Patches the definition. @throws BridgeError `invalid_message` when the result is not a valid
   * definition; the handle keeps its previous data then.
   */
  update(patch: SpatialPatch<I> | ((current: D) => SpatialPatch<I>)): void;
}

/** A private zone; its geometry follows every update and accepted edit. */
export interface ZoneHandle extends SpatialHandle<ZoneData, ZoneInput>, ZoneShape {}
/** A private point. */
export interface PointHandle extends SpatialHandle<PointData, PointInput>, PointShape {}
/** A private route. */
export interface RouteHandle extends SpatialHandle<RouteData, RouteInput>, RouteShape {}

/** Layer methods for private spatial definitions. Same id rules as shapes; a different kind at the id replaces it. */
export interface SpatialFactory {
  /** Declares (or redeclares, replacing all fields) a private zone. @throws BridgeError `invalid_message` on a bad definition. */
  zone(id: string, definition: ZoneInput, options?: PrivateSpatialOptions): ZoneHandle;
  /** Declares a private point. @throws BridgeError `invalid_message` on a bad definition. */
  point(id: string, definition: PointInput, options?: PrivateSpatialOptions): PointHandle;
  /** Declares a private route. @throws BridgeError `invalid_message` on a bad definition. */
  route(id: string, definition: RouteInput, options?: PrivateSpatialOptions): RouteHandle;
}
