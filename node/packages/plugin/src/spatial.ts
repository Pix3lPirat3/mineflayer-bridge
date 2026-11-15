import {
  BridgeError,
  diffPatch,
  encodeKind,
  encodePatchFields,
  Kinds,
  splitArray,
  type MessagePayloads,
  type PointData,
  type RouteData,
  type RouteNode,
  type Vec3d,
  type Vec3i,
  type WorldKey,
  type ZoneBox,
  type ZoneData,
} from '@mineflayer-bridge/protocol';
import { bool, finite, int, invalid, nonEmpty, segment } from './check.ts';
import { LayerHandle, type HandleHost } from './handle.ts';
import {
  boxContains,
  closestOnRoute,
  distance,
  distanceToRoute,
  distanceToZone,
  routeBlocks,
  routeIntersects,
  routeLength,
  routePoints,
  toBlockBox,
  zoneBoxes,
  zoneBounds,
  zoneContains,
  zoneIntersects,
  type BoxLike,
  type RouteSampleOptions,
  type ZoneGeometry,
} from './geometry.ts';
import type { SharedState } from './shared.ts';
import type {
  NearestOptions,
  PointInput,
  PointShape,
  PrivateSpatialOptions,
  RouteInput,
  RouteShape,
  SharedEntry,
  SharedPoint,
  SharedPoints,
  SharedRoute,
  SharedRoutes,
  SharedZone,
  SharedZones,
  SpatialCreateOptions,
  SpatialHandle,
  SpatialKind,
  SpatialPatch,
  SpatialRef,
  SpatialView,
  SpatialWriteOptions,
  ZoneHandle,
  ZoneInput,
  ZoneShape,
  PointHandle,
  RouteHandle,
} from './spatial-types.ts';
import type { SharedCreateOptions, SharedObject, Vec3Like } from './types.ts';
import { Emitter, toColor, toVec3, waitFor, type Settle } from './util.ts';

/** Shared spatial definitions live under `shared/bridge:zones/`. */
const SPATIAL_EXTENSION = 'bridge:zones';
const PREFIX = `shared/${SPATIAL_EXTENSION}/`;
/** Kinds the spatial views subscribe to. */
export const SPATIAL_KINDS: readonly SpatialKind[] = ['core:zone', 'core:point', 'core:route'];
const isSpatialKind = (k: string): k is SpatialKind => (SPATIAL_KINDS as readonly string[]).includes(k);

/** How long a conflict retry waits for the view to receive the newer revision, in ms. */
const REBASE_WAIT_MS = 2000;
const DEFAULT_RETRIES = 3;
/** Column cell size of the zone index in blocks (one chunk). */
const CELL = 16;
/** Zones spanning more columns than this skip the index and are always tested. */
const MAX_INDEXED_CELLS = 1024;

/** Data type of each spatial kind. */
export interface SpatialData {
  'core:zone': ZoneData;
  'core:point': PointData;
  'core:route': RouteData;
}

type AnyData = SpatialData[SpatialKind];

function list(v: unknown, what: string): readonly unknown[] {
  if (!Array.isArray(v)) throw invalid(`${what} must be an array`);
  return v;
}

function tags(v: unknown): string[] {
  return list(v, 'tags').map((t) => nonEmpty('tag', t));
}

function block(v: unknown, what: string): Vec3i {
  const p = toVec3(v as Vec3Like, what);
  return { x: Math.floor(p.x), y: Math.floor(p.y), z: Math.floor(p.z) };
}

function zoneOp(v: unknown): string {
  if (v !== 'add' && v !== 'subtract' && v !== 'intersect') throw invalid(`zone box op must be add, subtract or intersect: ${String(v)}`);
  return v;
}

/** Vec3 tuples hold numbers; box tuples hold corners, so the first element tells them apart. */
function zoneBox(v: unknown): ZoneBox {
  let a: unknown;
  let b: unknown;
  let op: unknown = 'add';
  if (Array.isArray(v) && v.length >= 2 && typeof v[0] !== 'number') {
    [a, b] = v;
    if (v.length > 2) op = v[2];
  } else if (typeof v === 'object' && v !== null && 'min' in v && 'max' in v) {
    const o = v as { min: unknown; max: unknown; op?: unknown };
    a = o.min;
    b = o.max;
    if (o.op !== undefined) op = o.op;
  } else {
    throw invalid('zone box must be [min, max], [min, max, op] or { min, max, op }');
  }
  const { min, max } = toBlockBox([a as Vec3Like, b as Vec3Like]);
  return { min, max, op: zoneOp(op) };
}

function routeNode(v: unknown): RouteNode {
  if (typeof v === 'object' && v !== null && !Array.isArray(v) && 'pos' in v) {
    const o = v as { pos: unknown; data?: unknown };
    const n: RouteNode = { pos: toVec3(o.pos as Vec3Like, 'route node pos') };
    if (o.data !== undefined && o.data !== null) n.data = o.data;
    return n;
  }
  return { pos: toVec3(v as Vec3Like, 'route node') };
}

function interpolation(v: unknown): string {
  if (v === 'catmull-rom') return 'catmull_rom';
  if (v !== 'linear' && v !== 'bresenham' && v !== 'catmull_rom' && v !== 'bezier') throw invalid(`interpolation must be linear, bresenham, catmull_rom or bezier: ${String(v)}`);
  return v;
}

type Converter = (v: unknown) => unknown;

const COMMON: Readonly<Record<string, Converter>> = {
  name: (v) => nonEmpty('name', v),
  tags,
  color: (v) => toColor(v as string | number),
  editable: (v) => bool('editable', v),
};

/** Field converters per kind: friendly input to wire data. Unlisted fields are rejected. */
const FIELDS: Readonly<Record<SpatialKind, Readonly<Record<string, Converter>>>> = {
  'core:zone': {
    ...COMMON,
    boxes: (v) => list(v, 'boxes').map(zoneBox),
    polygon: (v) => list(v, 'polygon').map((p) => block(p, 'polygon vertex')),
    minY: (v) => int('minY', v),
    maxY: (v) => int('maxY', v),
    fill: (v) => {
      const f = finite('fill', v);
      if (f < 0 || f > 1) throw invalid('fill must be from 0 to 1');
      return f;
    },
    showLabel: (v) => bool('showLabel', v),
  },
  'core:point': {
    ...COMMON,
    pos: (v) => toVec3(v as Vec3Like, 'pos'),
    yaw: (v) => finite('yaw', v),
    pitch: (v) => {
      const p = finite('pitch', v);
      if (p < -90 || p > 90) throw invalid('pitch must be from -90 to 90');
      return p;
    },
    icon: (v) => nonEmpty('icon', v),
  },
  'core:route': {
    ...COMMON,
    nodes: (v) => list(v, 'nodes').map(routeNode),
    looped: (v) => bool('looped', v),
    interpolation,
    width: (v) => {
      const w = finite('width', v);
      if (w <= 0) throw invalid('width must be positive');
      return w;
    },
  },
};

const REQUIRED: Readonly<Record<SpatialKind, readonly string[]>> = {
  'core:zone': ['name'],
  'core:point': ['name', 'pos'],
  'core:route': ['name', 'nodes'],
};

/** Checks whole-definition rules that single fields cannot. @throws BridgeError `invalid_message`. */
function checkDefinition(kind: SpatialKind, data: AnyData): void {
  if (kind !== 'core:zone') return;
  const z = data as ZoneData;
  const boxes = z.boxes !== undefined && z.boxes.length > 0;
  const polygon = z.polygon !== undefined && z.polygon.length > 0;
  if (boxes && polygon) throw invalid('a zone has either boxes or a polygon, not both');
  if (!boxes && !polygon) throw invalid('a zone needs boxes or a polygon');
  if (polygon && z.polygon!.length < 3) throw invalid('a zone polygon needs at least three vertices');
}

/**
 * Applies a friendly patch to `base` (or builds from nothing): given fields are converted and
 * replace the current ones, `null` clears an optional field, `undefined` is ignored.
 * @throws BridgeError `invalid_message` on an unknown field, a bad value, a missing required field or
 *   an invalid whole definition.
 */
function mergeDefinition<K extends SpatialKind>(kind: K, base: SpatialData[K] | undefined, patch: object): SpatialData[K] {
  if (typeof patch !== 'object' || patch === null) throw invalid('a definition must be an object');
  const out: Record<string, unknown> = base === undefined ? {} : { ...base };
  const fields = FIELDS[kind];
  const required = REQUIRED[kind];
  for (const [k, v] of Object.entries(patch)) {
    const convert = fields[k];
    if (convert === undefined) throw invalid(`${kind} has no field ${k}`);
    if (v === undefined) continue;
    if (v === null) {
      if (required.includes(k)) throw invalid(`${k} is required and cannot be cleared`);
      delete out[k];
      continue;
    }
    out[k] = convert(v);
  }
  for (const k of required) if (out[k] === undefined) throw invalid(`${kind} needs ${k}`);
  const data = out as unknown as SpatialData[K];
  checkDefinition(kind, data);
  return data;
}

function encodeData<K extends SpatialKind>(kind: K, data: SpatialData[K]): Uint8Array {
  return encodeKind(Kinds[kind] as (typeof Kinds)['core:zone'], data as ZoneData);
}


/** A box-set zone as its disjoint add-only boxes, so repeated queries skip the op evaluation. */
function flatten(data: ZoneData): ZoneGeometry {
  return data.boxes !== undefined && data.boxes.length > 0 ? { boxes: zoneBoxes(data).map((b) => ({ min: b.min, max: b.max, op: 'add' })) } : data;
}

/** Geometry methods over fixed zone data; the decomposition is computed once, on first use. */
function zoneShape(data: ZoneData): ZoneShape {
  let flat: ZoneGeometry | undefined;
  const geo = (): ZoneGeometry => (flat ??= flatten(data));
  return {
    data,
    contains: (pos) => zoneContains(data, pos),
    bounds: () => zoneBounds(geo()),
    intersects: (box) => zoneIntersects(geo(), box),
    distanceTo: (pos) => distanceToZone(geo(), pos),
  };
}

/** Geometry methods over fixed point data. */
function pointShape(data: PointData): PointShape {
  return { data, pos: data.pos, distanceTo: (pos) => distance(data.pos, pos) };
}

/** Geometry methods over fixed route data; each call samples the route again. */
function routeShape(data: RouteData): RouteShape {
  return {
    data,
    points: (o) => routePoints(data, o),
    blocks: (o) => routeBlocks(data, o),
    length: (o) => routeLength(data, o),
    closest: (pos, o) => closestOnRoute(data, pos, o),
    distanceTo: (pos) => distanceToRoute(data, pos),
  };
}

type Shape<K extends SpatialKind> = K extends 'core:zone' ? ZoneShape : K extends 'core:point' ? PointShape : RouteShape;
type Entry<K extends SpatialKind> = K extends 'core:zone' ? SharedZone : K extends 'core:point' ? SharedPoint : SharedRoute;
type AnyEntry = SharedZone | SharedPoint | SharedRoute;

function shapeOf<K extends SpatialKind>(kind: K, data: SpatialData[K]): Shape<K> {
  if (kind === 'core:zone') return zoneShape(data as ZoneData) as Shape<K>;
  if (kind === 'core:point') return pointShape(data as PointData) as Shape<K>;
  return routeShape(data as RouteData) as Shape<K>;
}

function entryOf(o: SharedObject, kind: SpatialKind): AnyEntry {
  const data = o.data as AnyData;
  return Object.freeze({
    id: o.key.slice(PREFIX.length),
    key: o.key,
    rev: o.rev,
    world: o.world,
    name: data.name,
    tags: Object.freeze([...(data.tags ?? [])]),
    ...shapeOf(kind, data),
  }) as AnyEntry;
}

function cellRange(min: number, max: number): [number, number] {
  return [Math.floor(min / CELL), Math.floor(max / CELL)];
}

/**
 * The session-wide local index of shared spatial definitions, fed by the shared view. Zones are
 * indexed by the chunk columns their bounds cover; zones too wide (or unbounded) are kept aside and
 * always tested.
 */
export class SpatialIndex {
  readonly events = new Emitter<{ add: [SpatialKind, AnyEntry]; change: [SpatialKind, AnyEntry, AnyEntry]; remove: [SpatialKind, AnyEntry] }>();
  private readonly entries = new Map<string, { kind: SpatialKind; entry: AnyEntry }>();
  private readonly columns = new Map<string, Set<string>>();
  private readonly zoneCells = new Map<string, string[]>();
  private readonly wide = new Set<string>();

  constructor(private readonly state: SharedState) {
    for (const o of state.objects.values()) this.onChange(o);
    state.events.on('change', (o) => this.onChange(o));
    state.events.on('remove', (k) => this.onRemove(k));
  }

  /** Raw encoded data of an entry at the revision the view holds. */
  raw(key: string): Uint8Array | undefined {
    return this.state.objects.get(key)?.raw;
  }

  get<K extends SpatialKind>(kind: K, key: string): Entry<K> | undefined {
    const e = this.entries.get(key);
    return e?.kind === kind ? (e.entry as Entry<K>) : undefined;
  }

  all<K extends SpatialKind>(kind: K): Entry<K>[] {
    const out: Entry<K>[] = [];
    for (const e of this.entries.values()) if (e.kind === kind) out.push(e.entry as Entry<K>);
    return out;
  }

  /** Zones whose bounds may reach the columns of `box`, plus every wide zone; callers test the geometry. */
  zoneCandidates(box: { min: Vec3i; max: Vec3i }): SharedZone[] {
    const [x0, x1] = cellRange(box.min.x, box.max.x);
    const [z0, z1] = cellRange(box.min.z, box.max.z);
    if ((x1 - x0 + 1) * (z1 - z0 + 1) > MAX_INDEXED_CELLS) return this.all('core:zone');
    const keys = new Set(this.wide);
    for (let x = x0; x <= x1; x++) for (let z = z0; z <= z1; z++) for (const k of this.columns.get(`${x},${z}`) ?? []) keys.add(k);
    const out: SharedZone[] = [];
    for (const k of keys) {
      const z = this.get('core:zone', k);
      if (z !== undefined) out.push(z);
    }
    return out;
  }

  private onChange(o: SharedObject): void {
    const prev = this.entries.get(o.key);
    const kind = o.key.startsWith(PREFIX) && isSpatialKind(o.kind) && !(o.data instanceof Uint8Array) ? o.kind : undefined;
    if (kind === undefined || (prev !== undefined && prev.kind !== kind)) this.onRemove(o.key);
    if (kind === undefined) return;
    const entry = entryOf(o, kind);
    this.unindex(o.key);
    this.entries.set(o.key, { kind, entry });
    if (kind === 'core:zone') this.indexZone(entry as SharedZone);
    if (prev !== undefined && prev.kind === kind) this.events.emit('change', kind, entry, prev.entry);
    else this.events.emit('add', kind, entry);
  }

  private onRemove(key: string): void {
    const prev = this.entries.get(key);
    if (prev === undefined) return;
    this.entries.delete(key);
    this.unindex(key);
    this.events.emit('remove', prev.kind, prev.entry);
  }

  private indexZone(z: SharedZone): void {
    const b = z.bounds();
    if (b === undefined) return;
    if (![b.min.x, b.max.x, b.min.z, b.max.z].every(Number.isFinite)) {
      this.wide.add(z.key);
      return;
    }
    const [x0, x1] = cellRange(b.min.x, b.max.x);
    const [z0, z1] = cellRange(b.min.z, b.max.z);
    if ((x1 - x0 + 1) * (z1 - z0 + 1) > MAX_INDEXED_CELLS) {
      this.wide.add(z.key);
      return;
    }
    const cells: string[] = [];
    for (let x = x0; x <= x1; x++) {
      for (let zz = z0; zz <= z1; zz++) {
        const c = `${x},${zz}`;
        cells.push(c);
        let set = this.columns.get(c);
        if (set === undefined) this.columns.set(c, (set = new Set()));
        set.add(z.key);
      }
    }
    this.zoneCells.set(z.key, cells);
  }

  private unindex(key: string): void {
    this.wide.delete(key);
    for (const c of this.zoneCells.get(key) ?? []) {
      const set = this.columns.get(c);
      set?.delete(key);
      if (set?.size === 0) this.columns.delete(c);
    }
    this.zoneCells.delete(key);
  }
}

/** What a view needs from its shared API. */
export interface ViewHost {
  readonly index: SpatialIndex;
  /** Subscribes the session to the spatial kinds; idempotent. */
  subscribe(): void;
  create(key: string, kind: SpatialKind, data: Uint8Array, options: SharedCreateOptions): Promise<{ key: string; rev: number }>;
  op(body: MessagePayloads['shared.op']['op'], timeoutMs: number | undefined): Promise<{ key: string; rev: number }>;
  /** The bot's current world, for the default binding. */
  world(): WorldKey | undefined;
  readonly signal: AbortSignal | undefined;
}

function retries(r: boolean | number | undefined): number {
  if (r === undefined || r === false) return 0;
  if (r === true) return DEFAULT_RETRIES;
  if (!Number.isInteger(r) || r < 0) throw invalid('retry must be a boolean or a non-negative integer');
  return r;
}

function conflictRev(e: unknown): number | undefined {
  if (!(e instanceof BridgeError) || e.code !== 'conflict') return undefined;
  const rev = (e.details as { rev?: unknown } | undefined)?.rev;
  return typeof rev === 'number' ? rev : 0;
}

class SpatialViewImpl<K extends SpatialKind, I extends object> implements SpatialView<Entry<K>, I> {
  private subscribed = false;

  constructor(
    readonly kind: K,
    protected readonly host: ViewHost,
  ) {}

  protected get index(): SpatialIndex {
    if (!this.subscribed) {
      this.subscribed = true;
      this.host.subscribe();
    }
    return this.host.index;
  }

  private keyOf(idOrKey: string): string {
    if (idOrKey.startsWith('shared/')) return idOrKey;
    segment('spatial id', idOrKey);
    return `${PREFIX}${idOrKey}`;
  }

  list(): Entry<K>[] {
    return this.index.all(this.kind);
  }

  get(idOrKey: string): Entry<K> | undefined {
    return this.index.get(this.kind, this.keyOf(idOrKey));
  }

  byTag(tag: string): Entry<K>[] {
    return this.list().filter((e) => e.tags.includes(tag));
  }

  intersecting(box: BoxLike): Entry<K>[] {
    const b = toBlockBox(box);
    if (this.kind === 'core:zone') return this.index.zoneCandidates(b).filter((z) => z.intersects(b)) as Entry<K>[];
    if (this.kind === 'core:point') return this.list().filter((p) => boxContains(b, (p as SharedPoint).pos));
    return this.list().filter((r) => routeIntersects((r as SharedRoute).data, b));
  }

  on(event: 'add', fn: (entry: Entry<K>) => void): () => void;
  on(event: 'change', fn: (entry: Entry<K>, previous: Entry<K>) => void): () => void;
  on(event: 'remove', fn: (entry: Entry<K>) => void): () => void;
  on(event: 'add' | 'change' | 'remove', fn: (entry: Entry<K>, previous: Entry<K>) => void): () => void {
    const events = this.index.events;
    const off =
      event === 'change'
        ? events.on('change', (k, e, p) => {
            if (k === this.kind) fn(e as Entry<K>, p as Entry<K>);
          })
        : events.on(event, (k, e) => {
            if (k === this.kind) (fn as (entry: Entry<K>) => void)(e as Entry<K>);
          });
    this.host.signal?.addEventListener('abort', off, { once: true });
    return off;
  }

  async create(definition: I, options: SpatialCreateOptions = {}): Promise<SpatialRef> {
    const id = options.id ?? crypto.randomUUID();
    segment('spatial id', id);
    // Subscribing first means the view receives the object this creates.
    void this.index;
    const data = mergeDefinition(this.kind, undefined, definition);
    const world = options.world === 'global' ? undefined : (options.world ?? this.host.world());
    const o: SharedCreateOptions = {};
    if (world !== undefined) o.world = world;
    if (options.timeoutMs !== undefined) o.timeoutMs = options.timeoutMs;
    const r = await this.host.create(`${PREFIX}${id}`, this.kind, encodeData(this.kind, data), o);
    return { id, key: r.key, rev: r.rev };
  }

  async update(idOrKey: string, patch: SpatialPatch<I> | ((current: Entry<K>) => SpatialPatch<I>), options: SpatialWriteOptions = {}): Promise<SpatialRef> {
    const key = this.keyOf(idOrKey);
    let left = retries(options.retry);
    let expectRev = options.expectRev;
    for (;;) {
      const entry = this.index.get(this.kind, key);
      if (entry === undefined) throw invalid(`no ${this.kind} ${key} in the shared view`);
      const rev = expectRev ?? entry.rev;
      const p = typeof patch === 'function' ? patch(entry) : patch;
      const data = mergeDefinition(this.kind, entry.data as SpatialData[K], p);
      const bytes = encodeData(this.kind, data);
      const raw = this.index.raw(key);
      // A diff is only valid against the revision the view holds; otherwise every field is sent.
      const diff = raw !== undefined && rev === entry.rev ? diffPatch(raw, bytes) : null;
      const fields = diff ?? encodePatchFields(new Map(splitArray(bytes).map((v, i) => [i, v] as const)));
      try {
        const r = await this.host.op({ type: 'update', value: { key, expectRev: rev, fields } }, options.timeoutMs);
        return { id: entry.id, key, rev: r.rev };
      } catch (e) {
        const current = conflictRev(e);
        if (current === undefined || left-- <= 0) throw e;
        await this.catchUp(key, current, e as BridgeError);
        expectRev = undefined;
      }
    }
  }

  async remove(idOrKey: string, options: SpatialWriteOptions = {}): Promise<void> {
    const key = this.keyOf(idOrKey);
    let left = retries(options.retry);
    let expectRev = options.expectRev;
    for (;;) {
      const rev = expectRev ?? this.index.get(this.kind, key)?.rev;
      if (rev === undefined) throw invalid(`no ${this.kind} ${key} in the shared view`);
      try {
        await this.host.op({ type: 'remove', value: { key, expectRev: rev } }, options.timeoutMs);
        return;
      } catch (e) {
        const current = conflictRev(e);
        if (current === undefined || left-- <= 0) throw e;
        await this.catchUp(key, current, e as BridgeError);
        expectRev = undefined;
      }
    }
  }

  /**
   * Waits until the view holds `rev` (or newer) of `key`. A conflict reply can overtake the broadcast
   * of the write that caused it on another session, so the view may lag briefly. Rethrows `conflict`
   * when the object is gone.
   */
  private catchUp(key: string, rev: number, conflict: BridgeError): Promise<void> {
    const index = this.host.index;
    const end = (s: Settle<void>): void => (index.get(this.kind, key) === undefined ? s.fail(conflict) : s.ok());
    return waitFor<void>(this.host.signal, REBASE_WAIT_MS, 'request cancelled', end, (s) => {
      const check = (): void => {
        const e = index.get(this.kind, key);
        if (e === undefined || e.rev >= rev) end(s);
      };
      const offChange = index.events.on('change', check);
      const offRemove = index.events.on('remove', check);
      check();
      return () => {
        offChange();
        offRemove();
      };
    });
  }
}

class SharedZonesImpl extends SpatialViewImpl<'core:zone', ZoneInput> implements SharedZones {
  containing(pos: Vec3Like): SharedZone[] {
    const p = toVec3(pos, 'pos');
    const b = { x: Math.floor(p.x), y: Math.floor(p.y), z: Math.floor(p.z) };
    return this.index.zoneCandidates({ min: b, max: b }).filter((z) => z.contains(p));
  }
}

function nearestBy<E extends SharedEntry>(entries: readonly E[], dist: (e: E) => number, o: NearestOptions): E | undefined {
  const max = o.maxDistance ?? Infinity;
  let best: E | undefined;
  let bestD = Infinity;
  for (const e of entries) {
    if (o.tag !== undefined && !e.tags.includes(o.tag)) continue;
    const d = dist(e);
    if (d <= max && d < bestD) {
      best = e;
      bestD = d;
    }
  }
  return best;
}

class SharedPointsImpl extends SpatialViewImpl<'core:point', PointInput> implements SharedPoints {
  nearest(pos: Vec3Like, options: NearestOptions = {}): SharedPoint | undefined {
    const p = toVec3(pos, 'pos');
    return nearestBy(this.list(), (e) => e.distanceTo(p), options);
  }

  within(pos: Vec3Like, radius: number, options: { tag?: string } = {}): SharedPoint[] {
    const p = toVec3(pos, 'pos');
    const r = finite('radius', radius);
    return this.list()
      .filter((e) => options.tag === undefined || e.tags.includes(options.tag))
      .map((e) => ({ e, d: e.distanceTo(p) }))
      .filter((x) => x.d <= r)
      .sort((a, b) => a.d - b.d)
      .map((x) => x.e);
  }
}

class SharedRoutesImpl extends SpatialViewImpl<'core:route', RouteInput> implements SharedRoutes {
  nearest(pos: Vec3Like, options: NearestOptions = {}): SharedRoute | undefined {
    const p = toVec3(pos, 'pos');
    return nearestBy(this.list(), (e) => e.distanceTo(p), options);
  }
}

/** The three views of one shared API scope. */
export function spatialViews(host: ViewHost): { zones: SharedZones; points: SharedPoints; routes: SharedRoutes } {
  return { zones: new SharedZonesImpl('core:zone', host), points: new SharedPointsImpl('core:point', host), routes: new SharedRoutesImpl('core:route', host) };
}

/** What a private definition needs from its layer. */
export interface SpatialHost extends HandleHost {
  /** Writes the object at `<layer>/<shapeId>` with the traits of `o`. */
  putSpatial(shapeId: string, kind: SpatialKind, data: Uint8Array, o: PrivateSpatialOptions): void;
  delete(shapeId: string, what: 'self'): void;
  /** Revision of the declared object, or `undefined` when it is not declared. */
  storedRev(shapeId: string): number | undefined;
}

/** Shared behaviour of private spatial handles: declaration, patches and retirement. */
export abstract class SpatialHandleImpl<K extends SpatialKind, I extends object> extends LayerHandle<K, SpatialHost, SpatialData[K]> implements SpatialHandle<SpatialData[K], I> {
  private cached: Shape<K> | undefined;

  constructor(
    host: SpatialHost,
    id: string,
    kind: K,
    private current: SpatialData[K],
    private opts: PrivateSpatialOptions,
  ) {
    super(host, id, kind);
  }

  get data(): SpatialData[K] {
    return this.current;
  }

  get rev(): number {
    return this.alive ? (this.host.storedRev(this.id) ?? 0) : 0;
  }

  protected get shape(): Shape<K> {
    return (this.cached ??= shapeOf(this.kind, this.current));
  }

  private commit(next: SpatialData[K]): void {
    this.host.putSpatial(this.id, this.kind, encodeData(this.kind, next), this.opts);
    this.current = next;
    this.cached = undefined;
    this.bound(this.opts);
  }

  declare(): void {
    this.commit(this.current);
  }

  redeclare(definition: I, opts: PrivateSpatialOptions): void {
    if (!this.alive) return;
    const next = mergeDefinition(this.kind, undefined, definition);
    this.opts = opts;
    this.commit(next);
  }

  update(patch: SpatialPatch<I> | ((current: SpatialData[K]) => SpatialPatch<I>)): void {
    if (!this.alive) return;
    const p = typeof patch === 'function' ? patch(this.current) : patch;
    this.commit(mergeDefinition(this.kind, this.current, p));
  }

  protected drop(): void {
    this.host.delete(this.id, 'self');
  }

  protected editWrite(proposed: SpatialData[K]): () => void {
    checkDefinition(this.kind, proposed);
    encodeData(this.kind, proposed);
    return () => this.commit(proposed);
  }
}

/** `layer.zone()` handle. */
class ZoneHandleImpl extends SpatialHandleImpl<'core:zone', ZoneInput> implements ZoneHandle {
  contains(pos: Vec3Like): boolean {
    return this.shape.contains(pos);
  }
  bounds(): ReturnType<ZoneShape['bounds']> {
    return this.shape.bounds();
  }
  intersects(box: BoxLike): boolean {
    return this.shape.intersects(box);
  }
  distanceTo(pos: Vec3Like): number {
    return this.shape.distanceTo(pos);
  }
}

/** `layer.point()` handle. */
class PointHandleImpl extends SpatialHandleImpl<'core:point', PointInput> implements PointHandle {
  get pos(): Vec3d {
    return this.data.pos;
  }
  distanceTo(pos: Vec3Like): number {
    return this.shape.distanceTo(pos);
  }
}

/** `layer.route()` handle. */
class RouteHandleImpl extends SpatialHandleImpl<'core:route', RouteInput> implements RouteHandle {
  points(options?: RouteSampleOptions): Vec3d[] {
    return this.shape.points(options);
  }
  blocks(options?: RouteSampleOptions): Vec3i[] {
    return this.shape.blocks(options);
  }
  length(options?: RouteSampleOptions): number {
    return this.shape.length(options);
  }
  closest(pos: Vec3Like, options?: RouteSampleOptions): ReturnType<RouteShape['closest']> {
    return this.shape.closest(pos, options);
  }
  distanceTo(pos: Vec3Like): number {
    return this.shape.distanceTo(pos);
  }
}

/** Builds a handle of the right class; the definition is validated before anything is written. */
export function spatialHandle<K extends SpatialKind>(host: SpatialHost, id: string, kind: K, definition: object, opts: PrivateSpatialOptions): SpatialHandleImpl<K, object> {
  const data = mergeDefinition(kind, undefined, definition);
  const H = kind === 'core:zone' ? ZoneHandleImpl : kind === 'core:point' ? PointHandleImpl : RouteHandleImpl;
  return new (H as unknown as new (h: SpatialHost, id: string, k: K, d: SpatialData[K], o: PrivateSpatialOptions) => SpatialHandleImpl<K, object>)(host, id, kind, data, opts);
}

