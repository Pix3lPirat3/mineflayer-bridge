import {
  BridgeError,
  encodeKind,
  Kinds,
  type KindName,
  type KindPayloads,
  type ObjectTraits,
  type Scope as WireScope,
  type SlotItem,
  type Vec3d,
  type WorldKey,
} from '@mineflayer-bridge/protocol';
import { extensionKind } from './ext-wire.ts';
import type {
  BlockDisplayOptions,
  DisplayOptions,
  EmitterOptions,
  EntityDisplayOptions,
  GroupOptions,
  ItemDisplayOptions,
  ParticleEmitter,
  SoundEmitter,
  SoundEmitterOptions,
  SoundSource,
  PlacementOptions,
  TextDisplayOptions,
} from './display-types.ts';
import {
  builders,
  checkQuota,
  checkShapeId,
  content,
  DisplayImpl,
  EmitterImpl,
  GroupImpl,
  groupPosition,
  groupState,
  type DisplayKind,
  type GroupHost,
  type PlacedKind,
} from './displays.ts';
import type { BlobData, BlobLimits } from './blobs.ts';
import { nonEmpty } from './check.ts';
import { checkEditable, type EditRegistry } from './edits.ts';
import type { AnyPlaced, HandleKind } from './handle.ts';
import type { InputRouter } from './input.ts';
import type { ItemInput } from './input-types.ts';
import type { ItemContext } from './items.ts';
import { BlockHighlightImpl, GhostBlocksImpl, HeatmapImpl, type RetainedHost, type RetainedKind } from './retained.ts';
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
import { ShapeImpl, type ShapeHost, type ShapeKind, type ShapeStates } from './shapes.ts';
import { soundCategory, soundContent, SoundEmitterImpl } from './sounds.ts';
import { spatialHandle, type SpatialHandleImpl, type SpatialHost } from './spatial.ts';
import type { PointHandle, PointInput, PrivateSpatialOptions, RouteHandle, RouteInput, SpatialKind, ZoneHandle, ZoneInput } from './spatial-types.ts';
import type { DeclaredStore } from './store.ts';
import { traitsData } from './traits.ts';
import type { TraitOptions } from './traits-types.ts';
import type {
  BridgeLimits,
  BoxHandle,
  BoxOptions,
  EntityTagHandle,
  EntityTagOptions,
  LabelHandle,
  LabelOptions,
  Layer,
  LayerOptions,
  LineHandle,
  LineOptions,
  ObjectsApi,
  PathHandle,
  PathOptions,
  PutOptions,
  ShapeOptions,
  StyleOptions,
  Vec3Like,
  WaypointHandle,
  WaypointOptions,
} from './types.ts';
import { toColor, toVec3 } from './util.ts';

/** Prefix of style objects within a scope; layer names cannot start with `~`, so it never collides. */
const STYLE_DIR = '~style/';
const INLINE = '~inline/';

/** What a scope needs from its session; {@link ItemContext.self} resolves `head: 'self'`. */
export interface ScopeHost extends ItemContext {
  readonly store: DeclaredStore;
  /** The bot's current world, for world-scoped shapes. */
  currentWorld(): WorldKey | undefined;
  layerVisible(scope: string, layer: string, defaultVisible: boolean): boolean;
  /** Routes `element.event`s to interactive displays. Read lazily, after the session is built. */
  readonly router: InputRouter;
  /** Reports an error thrown by a user handler (the session's `error` event). */
  reportError(error: BridgeError): void;
  /** Blob limits of the current connection, for the client-side quota check. */
  blobLimits(): BlobLimits;
  /** Limits of the current connection, for the client-side quota checks. */
  readonly limits: BridgeLimits;
  /** Objects declared by every session of the hub, which share the mod's object limit. */
  objectCount(): number;
  /** Handlers of player edits for the session's declarations. */
  readonly edits: EditRegistry;
}

/** Store options of a shape in `layer`, taking only the placement fields of `o`. */
function placement(layer: string, o: { ttlMs?: number | undefined; persistent?: boolean | undefined; world?: PutOptions['world'] }): PutOptions {
  const put: PutOptions = { layer };
  if (o.ttlMs !== undefined) put.ttlMs = o.ttlMs;
  if (o.persistent !== undefined) put.persistent = o.persistent;
  if (o.world !== undefined) put.world = o.world;
  return put;
}

/** Encodes style options as `core:style` data. */
function styleData(o: StyleOptions): KindPayloads['core:style'] {
  const d: KindPayloads['core:style'] = {};
  if (o.color !== undefined) d.color = toColor(o.color);
  if (o.fill !== undefined) d.fill = o.fill;
  if (o.lineWidth !== undefined) d.lineWidth = o.lineWidth;
  if (o.depth !== undefined) d.depth = o.depth;
  if (o.xrayAlpha !== undefined) d.xrayAlpha = o.xrayAlpha;
  if (o.pulse !== undefined) d.pulse = o.pulse;
  return d;
}

/**
 * Everything one extension scope (`user:default` or an extension id) declares in a session. Keys are
 * `<scope>/<local id>` relative to the owner; shapes live at `<scope>/<layer>/<id>`, styles at
 * `<scope>/~style/<id>`, and the layer object itself at `<scope>/<layer>`.
 */
export class ScopeObjects implements ObjectsApi {
  readonly scope: string;
  private readonly host: ScopeHost;
  private readonly layers = new Map<string, LayerImpl>();

  constructor(host: ScopeHost, scope: string) {
    this.host = host;
    this.scope = scope;
  }

  private rel(localId: string): string {
    return `${this.scope}/${localId}`;
  }

  /** Resolves a shape's world binding: explicit world, `global`, or the bot's current world. */
  binding(world: WorldKey | 'global' | undefined): { scope: WireScope; world: WorldKey | undefined } {
    if (world === 'global') return { scope: 'global', world: undefined };
    const w = world ?? this.host.currentWorld();
    return w === undefined ? { scope: 'global', world: undefined } : { scope: 'world', world: w };
  }

  put<K extends KindName>(localId: string, kind: K, data: KindPayloads[K], options?: PutOptions): void;
  put(localId: string, kind: string, data: Uint8Array, options?: PutOptions): void;
  put(localId: string, kind: string, data: unknown, options: PutOptions = {}): void {
    this.write(localId, kind, data, options, undefined, undefined);
  }

  /** Declares an object with encoded traits (see {@link traits}); `data` is core-kind data or pre-encoded bytes. */
  putWithTraits(localId: string, kind: string, data: object, options: PutOptions, traits: ObjectTraits | undefined, blob?: BlobData): void {
    this.write(localId, kind, data, options, blob, traits);
  }

  /** Encodes trait options for an object of this scope. @throws BridgeError `invalid_message`. */
  traits(o: TraitOptions): ObjectTraits | undefined {
    return traitsData(o, this.scope);
  }

  /**
   * Declares a core-kind object whose data refers to `blob` by hash. The blob goes to the mod before
   * the object and is kept while the object is declared.
   * @throws BridgeError `quota_exceeded` when the blob breaks the per-blob or in-flight limit.
   */
  putWithBlob<K extends KindName>(localId: string, kind: K, data: KindPayloads[K], blob: BlobData | undefined, options: PutOptions): void {
    this.write(localId, kind, data, options, blob, undefined);
  }

  private write(localId: string, kind: string, data: unknown, options: PutOptions, blob: BlobData | undefined, traits: ObjectTraits | undefined): void {
    nonEmpty('object id', localId);
    let bytes: Uint8Array;
    if (data instanceof Uint8Array) bytes = data;
    else if (Object.hasOwn(Kinds, kind)) bytes = encodeKind(Kinds[kind as KindName] as (typeof Kinds)['core:box'], data as KindPayloads['core:box']);
    else {
      const ext = extensionKind(kind);
      if (ext === undefined) throw new BridgeError('invalid_message', `data for kind ${kind} must be pre-encoded bytes, or the kind listed with its codec in the extension's kinds`);
      bytes = encodeKind(ext, data);
    }
    if (options.ttlMs !== undefined && (!Number.isInteger(options.ttlMs) || options.ttlMs < 0)) throw new BridgeError('invalid_message', 'ttlMs must be a non-negative integer');
    if (traits?.editable !== undefined) checkEditable(kind, bytes, traits.editable.map((f) => f.field));
    // UTF-8 takes at most 3 bytes per UTF-16 unit, which is close enough for a limit check.
    const around = 3 * (this.rel(localId).length + (options.layer?.length ?? 0)) + (traits === undefined ? 0 : 3 * JSON.stringify(traits).length);
    checkQuota(this.host.store, this.rel(localId), kind, { bytes: bytes.length + around, interactive: traits?.interactive !== undefined, objects: this.host.objectCount() }, this.host.limits);
    if (blob !== undefined) this.host.store.blobs.check(blob, this.host.blobLimits(), this.host.store.get(this.rel(localId))?.blob);
    const { scope, world } = this.binding(options.world);
    this.host.store.put({
      key: this.rel(localId),
      kind,
      layer: options.layer,
      lifetime: options.persistent === true && options.ttlMs === undefined ? 'persistent' : 'session',
      scope,
      world,
      ttlMs: options.ttlMs,
      data: bytes,
      blob,
      traits,
    });
  }

  remove(localId: string): void {
    this.host.store.remove(this.rel(localId));
  }

  removePrefix(prefix: string): void {
    this.host.store.removePrefix(this.rel(prefix));
  }

  has(localId: string): boolean {
    return this.host.store.has(this.rel(localId));
  }

  keys(): string[] {
    const p = `${this.scope}/`;
    return this.host.store.keys(p).map((k) => k.slice(p.length));
  }

  /** Removes everything the scope declared, as one prefix removal. */
  removeAll(): void {
    this.host.store.removePrefix(`${this.scope}/`);
    for (const l of this.layers.values()) l.retireAll();
    this.layers.clear();
  }

  style(id: string, options: StyleOptions): void {
    nonEmpty('style id', id);
    this.put(`${STYLE_DIR}${id}`, 'core:style', styleData(options), { world: 'global' });
  }

  layer(name: string, options: LayerOptions = {}): Layer {
    nonEmpty('layer name', name);
    if (name.includes('/') || name.startsWith('~')) throw new BridgeError('invalid_message', `layer name must not contain / or start with ~: ${name}`);
    let layer = this.layers.get(name);
    if (layer === undefined) {
      layer = new LayerImpl(this, this.host, name);
      this.layers.set(name, layer);
    }
    layer.declare(options);
    return layer;
  }
}

type Text = DisplayImpl<'core:display.text', TextDisplayOptions, string>;
type Item = DisplayImpl<'core:display.item', ItemDisplayOptions, SlotItem>;
type Block = DisplayImpl<'core:display.block', BlockDisplayOptions, string>;
type Entity = DisplayImpl<'core:display.entity', EntityDisplayOptions, string>;

class LayerImpl implements Layer, GroupHost, RetainedHost, SpatialHost, ShapeHost {
  readonly name: string;
  private readonly objects: ScopeObjects;
  private readonly host: ScopeHost;
  private defaultVisible = true;
  private category: string | undefined;
  /** Handles of displays, groups and emitters by shape id. */
  private readonly handles = new Map<string, AnyPlaced>();

  constructor(objects: ScopeObjects, host: ScopeHost, name: string) {
    this.objects = objects;
    this.host = host;
    this.name = name;
  }

  declare(options: LayerOptions): void {
    if (options.defaultVisible !== undefined) this.defaultVisible = options.defaultVisible;
    if (options.category !== undefined) this.category = options.category;
    const data: KindPayloads['core:layer'] = { defaultVisible: this.defaultVisible };
    if (this.category !== undefined) data.category = this.category;
    this.objects.put(this.name, 'core:layer', data, { layer: this.name, world: 'global' });
  }

  /**
   * The style reference of a shape: a style id, or an inline style declared for it. `color` fills
   * the inline colour where it is missing.
   * @throws BridgeError `invalid_message` for `color` together with a style id.
   */
  private styleRef(id: string, style: ShapeOptions['style'], color: ShapeOptions['color']): string | undefined {
    if (typeof style === 'string') {
      if (color !== undefined) throw new BridgeError('invalid_message', 'color is a shorthand for an inline style; it cannot be combined with a style id');
      return style;
    }
    if (style === undefined && color === undefined) return undefined;
    const ref = `${INLINE}${this.name}/${id}`;
    this.objects.style(ref, style?.color === undefined && color !== undefined ? { ...style, color } : (style ?? {}));
    return ref;
  }

  traits(o: TraitOptions): ObjectTraits | undefined {
    return this.objects.traits(o);
  }

  private shape<K extends ShapeKind>(id: string, kind: K, state: ShapeStates[K]): ShapeImpl<K> {
    nonEmpty('shape id', id);
    return this.place(
      id,
      kind,
      () => new ShapeImpl(this, id, kind, state),
      (h) => h.redeclare(state),
      (h) => h.declare(),
    );
  }

  putShape<K extends ShapeKind>(shapeId: string, kind: K, data: KindPayloads[K], o: ShapeOptions): void {
    const traits = this.traits(o);
    const ref = this.styleRef(shapeId, o.style, o.color);
    if (ref !== undefined) (data as { style?: string }).style = ref;
    this.objects.putWithTraits(`${this.name}/${shapeId}`, kind, data, placement(this.name, o), traits);
  }

  box(id: string, min: Vec3Like, size: Vec3Like | number, o: BoxOptions = {}): BoxHandle {
    return this.shape(id, 'core:box', { ...o, min, size });
  }

  line(id: string, a: Vec3Like, b: Vec3Like, o: LineOptions = {}): LineHandle {
    return this.shape(id, 'core:line', { ...o, from: a, to: b });
  }

  path(id: string, points: readonly Vec3Like[] | Float64Array, o: PathOptions = {}): PathHandle {
    return this.shape(id, 'core:path', { ...o, points });
  }

  label(id: string, pos: Vec3Like, text: string, o: LabelOptions = {}): LabelHandle {
    return this.shape(id, 'core:label', { ...o, pos, text });
  }

  entityTag(id: string, entity: number | { readonly id: number }, o: EntityTagOptions = {}): EntityTagHandle {
    return this.shape(id, 'core:entity-tag', { ...o, entity });
  }

  waypoint(id: string, pos: Vec3Like, name: string, o: WaypointOptions = {}): WaypointHandle {
    return this.shape(id, 'core:waypoint', { ...o, pos, name });
  }

  get router(): InputRouter {
    return this.host.router;
  }

  reportError(error: BridgeError): void {
    this.host.reportError(error);
  }

  put(shapeId: string, kind: PlacedKind, data: object, o: PlacementOptions, blob?: BlobData): void {
    this.objects.putWithTraits(`${this.name}/${shapeId}`, kind, data as KindPayloads[PlacedKind], placement(this.name, o), this.traits(o), blob);
  }

  delete(shapeId: string, what: 'self' | 'children' | 'both'): void {
    if (what !== 'children') this.objects.remove(`${this.name}/${shapeId}`);
    if (what !== 'self') this.objects.removePrefix(`${this.name}/${shapeId}/`);
  }

  routeKey(shapeId: string): string {
    return `${this.objects.scope}/${this.name}/${shapeId}`;
  }

  forget(handle: AnyPlaced): void {
    if (this.handles.get(handle.id) === handle) this.handles.delete(handle.id);
  }

  retirePrefix(prefix: string): void {
    for (const [id, h] of [...this.handles]) if (id.startsWith(prefix)) h.retire();
  }

  /** Makes every handle inert; the objects go with the caller's removal. */
  retireAll(): void {
    for (const h of [...this.handles.values()]) h.retire();
    this.handles.clear();
  }

  /**
   * Same id and kind: the handle is redeclared and returned. Otherwise a new handle writes first, so
   * a bad option or a quota leaves everything as it was, and only then retires the old one.
   */
  private place<H extends AnyPlaced>(shapeId: string, kind: HandleKind, create: () => H, redeclare: (h: H) => void, declare: (h: H) => void): H {
    const prev = this.handles.get(shapeId);
    if (prev !== undefined && prev.kind === kind) {
      redeclare(prev as H);
      return prev as H;
    }
    const h = create();
    declare(h);
    prev?.replaced();
    this.handles.set(shapeId, h);
    return h;
  }

  private display<K extends DisplayKind, O extends DisplayOptions, C>(
    shapeId: string,
    kind: K,
    pos: Vec3Like,
    raw: C,
    o: O,
    contentKey: string,
    toContent: (v: never) => C,
    build: (pos: Vec3d, content: C, o: O) => KindPayloads[K],
  ): DisplayImpl<K, O, C> {
    const p = toVec3(pos, 'pos');
    return this.place(
      shapeId,
      kind,
      () => new DisplayImpl(this, shapeId, kind, p, raw, o, contentKey, toContent, build),
      (h) => h.redeclare(p, raw, o),
      (h) => h.declare(),
    );
  }

  makeText(shapeId: string, pos: Vec3Like, text: string, o: TextDisplayOptions): Text {
    return this.display(shapeId, 'core:display.text', pos, content.text(text), o, 'text', content.text, builders.text);
  }

  makeItem(shapeId: string, pos: Vec3Like, item: ItemInput, o: ItemDisplayOptions): Item {
    const toItem = (v: ItemInput): SlotItem => content.item(v, this.host);
    return this.display(shapeId, 'core:display.item', pos, toItem(item), o, 'item', toItem, builders.item);
  }

  makeBlock(shapeId: string, pos: Vec3Like, state: string, o: BlockDisplayOptions): Block {
    return this.display(shapeId, 'core:display.block', pos, content.block(state), o, 'state', content.block, builders.block);
  }

  makeEntity(shapeId: string, pos: Vec3Like, type: string, o: EntityDisplayOptions): Entity {
    const build = (p: Vec3d, t: string, opts: EntityDisplayOptions): KindPayloads['core:display.entity'] => builders.entity(p, t, opts, this.host);
    return this.display(shapeId, 'core:display.entity', pos, content.entity(type), o, 'type', content.entity, build);
  }

  makeGroup(shapeId: string, o: GroupOptions, parent: GroupImpl | undefined): GroupImpl {
    const pos = groupPosition(o, parent !== undefined);
    const state = groupState(o);
    return this.place(
      shapeId,
      'core:group',
      () => new GroupImpl(this, shapeId, pos, state, parent),
      (h) => h.regroup(o),
      (h) => {
        h.declare();
        h.attach();
      },
    );
  }

  text(id: string, pos: Vec3Like, text: string, o: TextDisplayOptions = {}): Text {
    checkShapeId(id);
    return this.makeText(id, pos, text, o);
  }

  item(id: string, pos: Vec3Like, item: ItemInput, o: ItemDisplayOptions = {}): Item {
    checkShapeId(id);
    return this.makeItem(id, pos, item, o);
  }

  block(id: string, pos: Vec3Like, state: string, o: BlockDisplayOptions = {}): Block {
    checkShapeId(id);
    return this.makeBlock(id, pos, state, o);
  }

  entity(id: string, pos: Vec3Like, type: string, o: EntityDisplayOptions = {}): Entity {
    checkShapeId(id);
    return this.makeEntity(id, pos, type, o);
  }

  group(id: string, o: GroupOptions): GroupImpl {
    checkShapeId(id);
    return this.makeGroup(id, o, undefined);
  }

  emitter(id: string, pos: Vec3Like, particle: string, o: EmitterOptions = {}): ParticleEmitter {
    checkShapeId(id);
    return this.makeEmitter(id, pos, particle, o);
  }

  sound(id: string, pos: Vec3Like, sound: SoundSource, o: SoundEmitterOptions = {}): SoundEmitter {
    checkShapeId(id);
    return this.makeSound(id, pos, sound, o);
  }

  makeEmitter(shapeId: string, pos: Vec3Like, particle: string, o: EmitterOptions): EmitterImpl {
    const p = toVec3(pos, 'pos');
    const type = content.particle(particle);
    return this.place(
      shapeId,
      'core:particle-emitter',
      () => new EmitterImpl(this, shapeId, 'core:particle-emitter', p, type, o),
      (h) => h.redeclare(p, type, o),
      (h) => h.declare(),
    );
  }

  makeSound(shapeId: string, pos: Vec3Like, sound: SoundSource, o: SoundEmitterOptions): SoundEmitterImpl {
    if (o.category !== undefined) soundCategory(o.category);
    const p = toVec3(pos, 'pos');
    const c = soundContent(sound);
    return this.place(
      shapeId,
      'core:sound-emitter',
      () => new SoundEmitterImpl(this, shapeId, 'core:sound-emitter', p, c, o),
      (h) => h.redeclare(p, c, o),
      (h) => h.declare(),
    );
  }

  putRetained<K extends RetainedKind>(shapeId: string, kind: K, data: KindPayloads[K], blob: BlobData | undefined, o: RetainedOptions): void {
    const put = placement(this.name, { ttlMs: o.ttlMs, world: o.world });
    const traits = this.traits(o);
    if (kind !== 'core:heatmap') {
      const ref = this.styleRef(shapeId, o.style, o.color);
      if (ref !== undefined) (data as KindPayloads['core:ghost-blocks' | 'core:block-highlight']).style = ref;
    }
    this.objects.putWithTraits(`${this.name}/${shapeId}`, kind, data, put, traits, blob);
  }

  removeShape(shapeId: string): void {
    this.objects.remove(`${this.name}/${shapeId}`);
    this.objects.remove(`${STYLE_DIR}${INLINE}${this.name}/${shapeId}`);
  }

  ghostBlocks(id: string, volume: Volume, o: GhostBlocksOptions = {}): GhostBlocks {
    nonEmpty('shape id', id);
    return this.place(
      id,
      'core:ghost-blocks',
      () => new GhostBlocksImpl(this, id, volume, o),
      (h) => h.declare(volume, o),
      (h) => h.declare(volume, o),
    );
  }

  heatmap(id: string, cells: BlockPositions, values: readonly number[] | Float32Array, o: HeatmapOptions = {}): Heatmap {
    nonEmpty('shape id', id);
    return this.place(
      id,
      'core:heatmap',
      () => new HeatmapImpl(this, id, o),
      (h) => h.declare(cells, values, o),
      (h) => h.declare(cells, values, o),
    );
  }

  blocks(id: string, positions: BlockPositions, o: BlockHighlightOptions = {}): BlockHighlight {
    nonEmpty('shape id', id);
    return this.place(
      id,
      'core:block-highlight',
      () => new BlockHighlightImpl(this, id, o),
      (h) => h.declare(positions, o),
      (h) => h.declare(positions, o),
    );
  }

  get edits(): EditRegistry {
    return this.host.edits;
  }

  putSpatial(shapeId: string, kind: SpatialKind, data: Uint8Array, o: PrivateSpatialOptions): void {
    this.objects.putWithTraits(`${this.name}/${shapeId}`, kind, data, placement(this.name, { persistent: o.persistent, world: o.world }), this.traits(o));
  }

  storedRev(shapeId: string): number | undefined {
    return this.host.store.get(this.routeKey(shapeId))?.rev;
  }

  private spatial<K extends SpatialKind>(id: string, kind: K, definition: object, o: PrivateSpatialOptions): SpatialHandleImpl<K, object> {
    nonEmpty('shape id', id);
    if (id.includes('/')) throw new BridgeError('invalid_message', `spatial id must not contain /: ${id}`);
    return this.place(
      id,
      kind,
      () => spatialHandle(this, id, kind, definition, o),
      (h) => h.redeclare(definition, o),
      (h) => h.declare(),
    );
  }

  zone(id: string, definition: ZoneInput, o: PrivateSpatialOptions = {}): ZoneHandle {
    return this.spatial(id, 'core:zone', definition, o) as unknown as ZoneHandle;
  }

  point(id: string, definition: PointInput, o: PrivateSpatialOptions = {}): PointHandle {
    return this.spatial(id, 'core:point', definition, o) as unknown as PointHandle;
  }

  route(id: string, definition: RouteInput, o: PrivateSpatialOptions = {}): RouteHandle {
    return this.spatial(id, 'core:route', definition, o) as unknown as RouteHandle;
  }

  has(id: string): boolean {
    return this.objects.has(`${this.name}/${id}`);
  }

  remove(id: string): void {
    const h = this.handles.get(id);
    if (h !== undefined) h.remove();
    else this.objects.remove(`${this.name}/${id}`);
    this.objects.remove(`${STYLE_DIR}${INLINE}${this.name}/${id}`);
  }

  clear(): void {
    this.retireAll();
    this.objects.removePrefix(`${this.name}/`);
    this.objects.removePrefix(`${STYLE_DIR}${INLINE}${this.name}/`);
  }

  isVisible(): boolean {
    return this.host.layerVisible(this.objects.scope, this.name, this.defaultVisible);
  }
}
