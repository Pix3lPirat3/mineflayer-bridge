import { BridgeError, type ArmorStandProps, type DisplayTransform, type KindPayloads, type SlotItem, type Vec3d } from '@mineflayer-bridge/protocol';
import type {
  ArmorStandOptions,
  Billboard,
  BlockDisplayOptions,
  DisplayOptions,
  DisplayTransformOptions,
  EmitterOptions,
  EntityDisplayOptions,
  EquipmentSlot,
  EulerAngles,
  GroupFollow,
  GroupOptions,
  GroupUpdate,
  ItemDisplayOptions,
  MoveOptions,
  PlacementOptions,
  Quaternion,
  SoundEmitter,
  SoundEmitterOptions,
  SoundSource,
  TextDisplayOptions,
} from './display-types.ts';
import type { BlobData } from './blobs.ts';
import { finite, nonNegativeInt, oneOf, positive, resourceId, spreadVec, text } from './check.ts';
import { LayerHandle, type HandleHost } from './handle.ts';
import type { ItemInput } from './input-types.ts';
import { NO_SELF, toSlotItem, type ItemContext } from './items.ts';
import { toSnbt } from './nbt.ts';
import { OBJECT_FRAME_OVERHEAD, type DeclaredStore } from './store.ts';
import type { BridgeLimits, Vec3Like } from './types.ts';
import { mergePatch, toColor, toVec3 } from './util.ts';

export type DisplayKind = 'core:display.text' | 'core:display.item' | 'core:display.block' | 'core:display.entity';
export type PlacedKind = DisplayKind | 'core:group' | 'core:particle-emitter' | 'core:sound-emitter';

const DISPLAY_KINDS: ReadonlySet<string> = new Set<DisplayKind>(['core:display.text', 'core:display.item', 'core:display.block', 'core:display.entity']);
const BILLBOARDS: ReadonlySet<string> = new Set<Billboard>(['fixed', 'vertical', 'horizontal', 'center']);
const ANIMATIONS: ReadonlySet<string> = new Set(['bob', 'spin', 'pulse', 'sway']);
const ALIGNS: ReadonlySet<string> = new Set(['left', 'center', 'right']);
const ITEM_MODES: ReadonlySet<string> = new Set(['ground', 'fixed', 'head', 'gui']);
const EQUIPMENT: ReadonlySet<string> = new Set<EquipmentSlot>(['head', 'chest', 'legs', 'feet', 'mainhand', 'offhand']);
/** Wire fields an edit cannot map back to options: they are derived, nested or set by the layer. */
const UNEDITABLE: ReadonlySet<string> = new Set(['group', 'transform', 'animateAmplitude', 'animatePeriodMs', 'equipment', 'armorStand', 'followSelf', 'followEntity', 'hover', 'interactive']);

/**
 * The animation duration of options that accept both spellings, `interpolate` and `interpolateMs`,
 * in ms.
 * @throws BridgeError `invalid_message` when both are given and differ, or the value is not a non-negative integer.
 */
export function interpolation(o: { interpolate?: number | undefined; interpolateMs?: number | undefined }): number | undefined {
  const a = o.interpolate;
  const b = o.interpolateMs;
  if (a !== undefined && b !== undefined && a !== b) throw new BridgeError('invalid_message', 'interpolate and interpolateMs are the same option; give one');
  const v = a ?? b;
  return v === undefined ? undefined : nonNegativeInt('interpolate', v);
}

/** `o` animating over `ms`, under the one spelling `interpolate`. */
function withInterpolate<O extends object>(o: O, ms: number): O {
  const out: Record<string, unknown> = { ...o, interpolate: ms };
  delete out['interpolateMs'];
  return out as O;
}

/** What {@link checkQuota} needs to know about the write besides its key and kind. */
export interface QuotaWrite {
  /** Encoded bytes the object takes in a frame: data, key, layer and traits. */
  readonly bytes: number;
  /** The object carries the interactive trait. */
  readonly interactive: boolean;
  /** Objects declared by every session of the hub; they share `maxObjects`. */
  readonly objects: number;
}

/**
 * Rejects a write the mod would refuse, using the limits it advertised in `welcome`, so the plugin
 * fails at the call instead of the mod later: an object too large for one frame, one more than
 * `maxObjects` or `maxInteractive`, or one past a display or emitter quota. Replacing an object does
 * not count it again.
 * @throws BridgeError `quota_exceeded`.
 */
export function checkQuota(store: DeclaredStore, key: string, kind: string, write: QuotaWrite,
  limits: Pick<BridgeLimits, 'maxFrameBytes' | 'maxObjects' | 'maxInteractive' | 'maxDisplays' | 'maxEntityDisplays' | 'maxEmitters' | 'maxSoundEmitters'>): void {
  const before = store.get(key);
  const prev = before?.kind;
  const exceeded = (what: string, limit: number): BridgeError =>
    new BridgeError('quota_exceeded', `the session already declares ${limit} ${what}`, { details: { key, kind, limit } });
  const room = limits.maxFrameBytes - OBJECT_FRAME_OVERHEAD;
  if (write.bytes > room) {
    throw new BridgeError('quota_exceeded', `${key} takes ${write.bytes} bytes, more than the ${room} that fit in one frame (limit ${limits.maxFrameBytes})`, {
      details: { key, kind, bytes: write.bytes, limit: room },
    });
  }
  if (before === undefined && write.objects >= limits.maxObjects) {
    throw new BridgeError('quota_exceeded', `the sessions of this hub already declare ${limits.maxObjects} objects`, { details: { key, kind, limit: limits.maxObjects } });
  }
  if (write.interactive && before?.traits?.interactive === undefined && store.interactive >= limits.maxInteractive) throw exceeded('interactive objects', limits.maxInteractive);
  if (prev === kind) return;
  if (DISPLAY_KINDS.has(kind) && (prev === undefined || !DISPLAY_KINDS.has(prev))) {
    let total = 0;
    for (const k of DISPLAY_KINDS) total += store.count(k);
    if (total >= limits.maxDisplays) throw exceeded('displays', limits.maxDisplays);
  }
  if (kind === 'core:display.entity' && store.count(kind) >= limits.maxEntityDisplays) throw exceeded('entity displays', limits.maxEntityDisplays);
  if (kind === 'core:particle-emitter' && store.count(kind) >= limits.maxEmitters) throw exceeded('particle emitters', limits.maxEmitters);
  if (kind === 'core:sound-emitter' && store.count(kind) >= limits.maxSoundEmitters) throw exceeded('sound emitters', limits.maxSoundEmitters);
}

function isQuaternion(r: Quaternion | EulerAngles): r is Quaternion {
  return Array.isArray(r);
}

type Quat = [number, number, number, number];

/** Hamilton product `a * b` of `[x, y, z, w]` quaternions: `b` applies first. */
function mul(a: Quat, b: Quat): Quat {
  const [ax, ay, az, aw] = a;
  const [bx, by, bz, bw] = b;
  return [aw * bx + ax * bw + ay * bz - az * by, aw * by - ax * bz + ay * bw + az * bx, aw * bz + ax * by - ay * bx + az * bw, aw * bw - ax * bx - ay * by - az * bz];
}

/**
 * Converts a rotation to the wire quaternion `[x, y, z, w]`. Angles follow Minecraft: yaw turns
 * clockwise seen from above, so it is a right-handed turn of `-yaw` about +Y; pitch is a turn about
 * +X and roll about +Z. The result is `yaw * pitch * roll`, so roll applies first.
 * @throws BridgeError `invalid_message` for a quaternion that is not four finite numbers, or a non-finite angle.
 */
export function toQuaternion(r: Quaternion | EulerAngles): Float32Array {
  if (isQuaternion(r)) {
    if (r.length !== 4 || !r.every((v) => Number.isFinite(v))) throw new BridgeError('invalid_message', 'a rotation quaternion must be four finite numbers [x, y, z, w]');
    return Float32Array.from(r);
  }
  const deg = Math.PI / 180;
  const half = (what: string, v: number | undefined): number => (finite(what, v ?? 0) * deg) / 2;
  const y = -half('yaw', r.yaw);
  const p = half('pitch', r.pitch);
  const z = half('roll', r.roll);
  const q = mul(mul([0, Math.sin(y), 0, Math.cos(y)], [Math.sin(p), 0, 0, Math.cos(p)]), [0, 0, Math.sin(z), Math.cos(z)]);
  return Float32Array.from(q);
}

/** A block state; the block id before any `[properties]` gets `minecraft:` when it has no namespace. */
function blockState(v: string): string {
  if (typeof v !== 'string' || v === '' || /\s/.test(v)) throw new BridgeError('invalid_message', `block state must be a non-empty string without spaces: ${String(v)}`);
  const id = v.split('[', 1)[0]!;
  return id.includes(':') ? v : `minecraft:${v}`;
}


function transformData(t: DisplayTransformOptions): DisplayTransform {
  const d: DisplayTransform = {};
  if (t.rotation !== undefined) d.rotation = toQuaternion(t.rotation);
  if (t.scale !== undefined) d.scale = spreadVec('transform scale', t.scale);
  if (t.offset !== undefined) d.offset = toVec3(t.offset, 'transform offset');
  return d;
}

type Common = Pick<
  KindPayloads['core:display.text'],
  'scale' | 'group' | 'transform' | 'billboard' | 'interpolateMs' | 'animate' | 'animateAmplitude' | 'animatePeriodMs' | 'brightness' | 'glow' | 'maxDistance'
>;

function commonData(o: DisplayOptions): Common {
  const d: Common = {};
  if (o.group !== undefined) {
    if (typeof o.group !== 'string' || o.group === '') throw new BridgeError('invalid_message', 'group must be the non-empty shape id of a group');
    d.group = o.group;
  }
  if (o.scale !== undefined) d.scale = finite('scale', o.scale);
  if (o.transform !== undefined) d.transform = transformData(o.transform);
  if (o.billboard !== undefined) d.billboard = oneOf('billboard', o.billboard, BILLBOARDS);
  const ms = interpolation(o);
  if (ms !== undefined) d.interpolateMs = ms;
  if (o.animate !== undefined) {
    const a = typeof o.animate === 'string' ? { type: o.animate } : o.animate;
    d.animate = oneOf('animation', a.type, ANIMATIONS);
    if (a.amplitude !== undefined) d.animateAmplitude = finite('animation amplitude', a.amplitude);
    if (a.periodMs !== undefined) {
      if (!Number.isInteger(a.periodMs) || a.periodMs <= 0) throw new BridgeError('invalid_message', 'animation periodMs must be a positive integer');
      d.animatePeriodMs = a.periodMs;
    }
  }
  if (o.brightness !== undefined) d.brightness = nonNegativeInt('brightness', o.brightness, 15);
  if (o.glow !== undefined) d.glow = toColor(o.glow);
  if (o.maxDistance !== undefined) d.maxDistance = positive('maxDistance', o.maxDistance);
  return d;
}

function textData(pos: Vec3d, content: string, o: TextDisplayOptions): KindPayloads['core:display.text'] {
  const d: KindPayloads['core:display.text'] = { pos, text: content, ...commonData(o) };
  if (o.lineWidth !== undefined) d.lineWidth = nonNegativeInt('lineWidth', o.lineWidth);
  if (o.background !== undefined) d.background = toColor(o.background);
  if (o.shadow !== undefined) d.shadow = o.shadow;
  if (o.seeThrough !== undefined) d.seeThrough = o.seeThrough;
  if (o.align !== undefined) d.align = oneOf('text align', o.align, ALIGNS);
  return d;
}

function itemData(pos: Vec3d, item: SlotItem, o: ItemDisplayOptions): KindPayloads['core:display.item'] {
  const d: KindPayloads['core:display.item'] = { pos, item, ...commonData(o) };
  if (o.mode !== undefined) d.mode = oneOf('item display mode', o.mode, ITEM_MODES);
  return d;
}

function blockData(pos: Vec3d, state: string, o: BlockDisplayOptions): KindPayloads['core:display.block'] {
  return { pos, state, ...commonData(o) };
}

const POSE_PARTS = ['head', 'body', 'leftArm', 'rightArm', 'leftLeg', 'rightLeg'] as const;

function armorStandData(a: ArmorStandOptions): ArmorStandProps {
  if (typeof a !== 'object' || a === null) throw new BridgeError('invalid_message', 'armorStand must be an object');
  const d: ArmorStandProps = {};
  if (a.small !== undefined) d.small = a.small;
  if (a.showArms !== undefined) d.showArms = a.showArms;
  if (a.noBasePlate !== undefined) d.noBasePlate = a.noBasePlate;
  if (a.marker !== undefined) d.marker = a.marker;
  for (const part of POSE_PARTS) {
    const p = a.pose?.[part];
    if (p === undefined) continue;
    if (!Array.isArray(p) || p.length !== 3) throw new BridgeError('invalid_message', `armor stand ${part} pose must be [x, y, z] in degrees`);
    d[`${part}Pose`] = Float32Array.from(p, (v: number) => finite(`armor stand ${part} pose`, v));
  }
  return d;
}

function nonEmptyText(what: string, v: string): string {
  if (typeof v !== 'string' || v.trim() === '') throw new BridgeError('invalid_message', `${what} must be a non-empty string`);
  return v;
}

function entityData(pos: Vec3d, type: string, o: EntityDisplayOptions, ctx: ItemContext = NO_SELF): KindPayloads['core:display.entity'] {
  const d: KindPayloads['core:display.entity'] = { pos, type, ...commonData(o) };
  if (o.yaw !== undefined) d.yaw = finite('yaw', o.yaw);
  if (o.pitch !== undefined) d.pitch = finite('pitch', o.pitch);
  if (o.headYaw !== undefined) d.headYaw = finite('headYaw', o.headYaw);
  if (o.pose !== undefined) d.pose = text('pose', o.pose);
  if (o.name !== undefined) d.name = text('name', o.name);
  if (o.nameVisible !== undefined) d.nameVisible = o.nameVisible;
  if (o.baby !== undefined) d.baby = o.baby;
  if (o.skin !== undefined) d.skin = text('skin', o.skin);
  if (o.equipment !== undefined) {
    const eq: Record<string, SlotItem> = {};
    for (const [slot, item] of Object.entries(o.equipment) as [string, ItemInput | undefined][]) {
      oneOf('equipment slot', slot, EQUIPMENT);
      if (item !== undefined) eq[slot] = toSlotItem(item, ctx);
    }
    d.equipment = eq;
  }
  if (o.invisible !== undefined) d.invisible = o.invisible;
  if (o.onFire !== undefined) d.onFire = o.onFire;
  if (o.frozen !== undefined) d.frozen = o.frozen;
  if (o.variant !== undefined) d.variant = nonEmptyText('variant', o.variant);
  if (o.color !== undefined) d.color = nonEmptyText('entity colour', o.color);
  if (o.profession !== undefined) d.profession = nonEmptyText('profession', o.profession);
  if (o.charged !== undefined) d.charged = o.charged;
  if (o.saddled !== undefined) d.saddled = o.saddled;
  if (o.armorStand !== undefined) d.armorStand = armorStandData(o.armorStand);
  if (o.effects !== undefined) {
    if (!Array.isArray(o.effects)) throw new BridgeError('invalid_message', 'effects must be a list of status effect ids');
    d.effects = [...new Set(o.effects.map((e: string) => resourceId('status effect', e)))];
  }
  if (o.data !== undefined) d.data = typeof o.data === 'string' ? nonEmptyText('entity data', o.data) : toSnbt(o.data);
  return d;
}

function emitterData(pos: Vec3d, particle: string, o: EmitterOptions): KindPayloads['core:particle-emitter'] {
  const d: KindPayloads['core:particle-emitter'] = { pos, particle };
  if (o.rate !== undefined) d.rate = positive('emitter rate', o.rate);
  if (o.count !== undefined) d.count = nonNegativeInt('emitter count', o.count);
  if (o.spread !== undefined) d.spread = spreadVec('emitter spread', o.spread);
  if (o.speed !== undefined) d.speed = finite('emitter speed', o.speed);
  if (o.options !== undefined) d.options = o.options;
  if (o.group !== undefined) d.group = o.group;
  return d;
}

function groupData(pos: Vec3d, follow: GroupFollow | undefined, o: GroupOptions): KindPayloads['core:group'] {
  const d: KindPayloads['core:group'] = { pos };
  if (follow === 'self') d.followSelf = true;
  else if (follow !== undefined) {
    const id = typeof follow === 'number' ? follow : follow.id;
    if (!Number.isInteger(id)) throw new BridgeError('invalid_message', 'follow must be self, an entity or an integer entity id');
    d.followEntity = id;
  }
  if (o.billboard !== undefined) d.billboard = oneOf('billboard', o.billboard, BILLBOARDS);
  const ms = interpolation(o);
  if (ms !== undefined) d.interpolateMs = ms;
  if (o.transform !== undefined) d.transform = transformData(o.transform);
  return d;
}

/** What placed objects need from their layer. */
export interface PlacedHost extends HandleHost {
  /** Writes the object at `<layer>/<shapeId>` with the traits of `o` and the blob it refers to; checks quotas. */
  put(shapeId: string, kind: PlacedKind, data: Uint8Array | object, o: PlacementOptions, blob?: BlobData): void;
  /**
   * Removes `<layer>/<shapeId>` (`self`), everything under `<layer>/<shapeId>/` (`children`), or
   * both, each as one removal.
   */
  delete(shapeId: string, what: 'self' | 'children' | 'both'): void;
  /** Retires every handle whose shape id starts with `prefix`. */
  retirePrefix(prefix: string): void;
}

/**
 * One object in a layer. Its full state is kept here and re-encoded on every change; the store
 * turns the difference into a field patch.
 */
export abstract class Placed<K extends PlacedKind, O extends PlacementOptions, C> extends LayerHandle<K, PlacedHost> {
  constructor(
    host: PlacedHost,
    id: string,
    kind: K,
    protected pos: Vec3d,
    protected content: C,
    protected opts: O,
  ) {
    super(host, id, kind);
  }

  protected abstract encode(pos: Vec3d, content: C, opts: O): KindPayloads[K];

  /** The wire field holding the content, and its conversion back from wire data; none for groups. */
  protected contentField(): { field: string; from: (v: unknown) => C } | undefined {
    return undefined;
  }

  /** Encodes first, so an option that does not fit throws before any state changes. */
  protected commit(pos: Vec3d, content: C, opts: O): void {
    const data = this.encode(pos, content, opts);
    this.host.put(this.id, this.kind, data, opts, this.blobOf(content));
    this.pos = pos;
    this.content = content;
    this.opts = opts;
    this.bound(opts);
  }

  /** Maps edited wire fields back to the position, content and options, then validates by encoding. */
  protected editWrite(proposed: KindPayloads[K], fields: readonly string[]): (() => void) | undefined {
    const p = proposed as unknown as Record<string, unknown>;
    const c = this.contentField();
    let pos = this.pos;
    let content = this.content;
    const patch: Record<string, unknown> = {};
    for (const f of fields) {
      const v = p[f];
      if (f === 'pos') pos = toVec3(v as Vec3Like, 'pos');
      else if (f === c?.field) content = c.from(v);
      else if (UNEDITABLE.has(f)) return undefined;
      else patch[f === 'interpolateMs' ? 'interpolate' : f] = v ?? null;
    }
    const opts = mergePatch(this.opts, patch, []);
    this.encode(pos, content, opts);
    return () => this.commit(pos, content, opts);
  }

  /** Whether the kind animates moves; emitters jump. */
  protected interpolates(): boolean {
    return true;
  }

  /** The blob the content refers to, sent with the object. */
  protected blobOf(_content: C): BlobData | undefined {
    return undefined;
  }

  /** Writes the initial state. */
  declare(): void {
    this.commit(this.pos, this.content, this.opts);
  }

  redeclare(pos: Vec3d, content: C, opts: O): void {
    if (this.alive) this.commit(pos, content, opts);
  }

  protected applyUpdate(patch: object, content: C, skip: readonly string[]): void {
    if (this.alive) this.commit(this.pos, content, mergePatch(this.opts, patch, skip));
  }

  move(pos: Vec3Like, o: MoveOptions = {}): void {
    if (!this.alive) return;
    const ms = interpolation(o);
    const next = ms === undefined || !this.interpolates() ? this.opts : withInterpolate(this.opts, ms);
    this.commit(toVec3(pos, 'pos'), this.content, next);
  }

  protected drop(): void {
    this.host.delete(this.id, 'self');
  }
}

/** A text, item, block or entity display. */
export class DisplayImpl<K extends DisplayKind, O extends DisplayOptions, C> extends Placed<K, O, C> {
  constructor(
    host: PlacedHost,
    id: string,
    kind: K,
    pos: Vec3d,
    content: C,
    opts: O,
    private readonly contentKey: string,
    private readonly toContent: (v: never) => C,
    private readonly build: (pos: Vec3d, content: C, o: O) => KindPayloads[K],
  ) {
    super(host, id, kind, pos, content, opts);
  }

  protected encode(pos: Vec3d, content: C, opts: O): KindPayloads[K] {
    return this.build(pos, content, opts);
  }

  protected override contentField(): { field: string; from: (v: unknown) => C } {
    return { field: this.contentKey, from: (v) => this.toContent(v as never) };
  }

  update(patch: Record<string, unknown>): void {
    const v = patch[this.contentKey];
    this.applyUpdate(patch, v === undefined || v === null ? this.content : this.toContent(v as never), [this.contentKey, 'group']);
  }
}

export class EmitterImpl extends Placed<'core:particle-emitter', EmitterOptions, string> {
  protected override interpolates(): boolean {
    return false;
  }

  protected encode(pos: Vec3d, particle: string, o: EmitterOptions): KindPayloads['core:particle-emitter'] {
    return emitterData(pos, particle, o);
  }

  protected override contentField(): { field: string; from: (v: unknown) => string } {
    return { field: 'particle', from: (v) => resourceId('particle type', v) };
  }

  update(patch: Record<string, unknown>): void {
    const v = patch['particle'];
    this.applyUpdate(patch, v === undefined || v === null ? this.content : resourceId('particle type', v as string), ['particle']);
  }
}

function add(a: Vec3d, b: Vec3d): Vec3d {
  return { x: a.x + b.x, y: a.y + b.y, z: a.z + b.z };
}

const ORIGIN: Vec3d = { x: 0, y: 0, z: 0 };

/** What a group needs to declare children through its layer. */
export interface GroupHost extends PlacedHost {
  makeText(shapeId: string, pos: Vec3Like, content: string, o: TextDisplayOptions): DisplayImpl<'core:display.text', TextDisplayOptions, string>;
  makeItem(shapeId: string, pos: Vec3Like, item: ItemInput, o: ItemDisplayOptions): DisplayImpl<'core:display.item', ItemDisplayOptions, SlotItem>;
  makeBlock(shapeId: string, pos: Vec3Like, state: string, o: BlockDisplayOptions): DisplayImpl<'core:display.block', BlockDisplayOptions, string>;
  makeEntity(shapeId: string, pos: Vec3Like, type: string, o: EntityDisplayOptions): DisplayImpl<'core:display.entity', EntityDisplayOptions, string>;
  makeGroup(shapeId: string, o: GroupOptions, parent: GroupImpl | undefined): GroupImpl;
  makeEmitter(shapeId: string, pos: Vec3Like, particle: string, o: EmitterOptions): EmitterImpl;
  makeSound(shapeId: string, pos: Vec3Like, sound: SoundSource, o: SoundEmitterOptions): SoundEmitter;
}

/** Group options without `pos` and `offset`, which live in the handle's position. */
type GroupState = Omit<GroupOptions, 'pos' | 'offset'>;

/** Splits group options into the local position and the rest. */
export function groupPosition(o: GroupOptions, nested: boolean): Vec3d {
  if (nested && o.follow !== undefined) throw new BridgeError('invalid_message', 'only top-level groups can follow; nested groups move with their parent');
  if (o.follow !== undefined) {
    if (o.pos !== undefined) throw new BridgeError('invalid_message', 'a following group is placed with offset, not pos');
    return o.offset === undefined ? ORIGIN : toVec3(o.offset, 'group offset');
  }
  if (o.offset !== undefined) throw new BridgeError('invalid_message', 'group offset applies only with follow; use pos');
  if (o.pos === undefined) throw new BridgeError('invalid_message', 'a group needs pos unless it follows something');
  return toVec3(o.pos, 'group pos');
}

export function groupState(o: GroupOptions): GroupState {
  const { pos: _pos, offset: _offset, ...rest } = o;
  return rest;
}

/**
 * A `core:group`. The wire group has no parent field, so a nested group is sent flattened: its
 * position is its parent's plus its own, it follows what the top-level group follows, and it is
 * rewritten whenever an ancestor moves. Ancestor transforms and billboards do not reach the
 * children of a nested group.
 */
export class GroupImpl extends Placed<'core:group', GroupState, undefined> {
  private readonly nested = new Set<GroupImpl>();

  constructor(
    private readonly layerHost: GroupHost,
    id: string,
    pos: Vec3d,
    opts: GroupState,
    private readonly parent: GroupImpl | undefined,
  ) {
    super(layerHost, id, 'core:group', pos, undefined, opts);
  }

  /** Links a nested group to its parent once its first write succeeded. */
  attach(): void {
    this.parent?.nested.add(this);
  }

  private get root(): GroupImpl {
    return this.parent === undefined ? this : this.parent.root;
  }

  /** Position in world space, or the offset from the followed entity. */
  private absolute(pos: Vec3d = this.pos): Vec3d {
    return this.parent === undefined ? pos : add(this.parent.absolute(), pos);
  }

  protected encode(pos: Vec3d, _content: undefined, opts: GroupState): KindPayloads['core:group'] {
    return groupData(this.absolute(pos), this.parent === undefined ? opts.follow : this.root.opts.follow, opts);
  }

  protected override commit(pos: Vec3d, content: undefined, opts: GroupState): void {
    this.commitWith(pos, opts, undefined);
  }

  /** Writes this group, then every nested group below it, animating them like it when `interpolate` is set. */
  private commitWith(pos: Vec3d, opts: GroupState, interpolate: number | undefined): void {
    super.commit(pos, undefined, opts);
    for (const g of this.nested) {
      if (g.alive) g.commitWith(g.pos, interpolate === undefined ? g.opts : withInterpolate(g.opts, interpolate), interpolate);
    }
  }

  regroup(o: GroupOptions): void {
    if (this.alive) this.commit(groupPosition(o, this.parent !== undefined), undefined, groupState(o));
  }

  override move(pos: Vec3Like, o: MoveOptions = {}): void {
    if (!this.alive) return;
    const ms = interpolation(o);
    this.commitWith(toVec3(pos, 'group pos'), ms === undefined ? this.opts : withInterpolate(this.opts, ms), ms);
  }

  /** A nested group's wire position is absolute, so only top-level groups take position edits. */
  protected override editWrite(proposed: KindPayloads['core:group'], fields: readonly string[]): (() => void) | undefined {
    return this.parent !== undefined && fields.includes('pos') ? undefined : super.editWrite(proposed, fields);
  }

  update(patch: GroupUpdate): void {
    if (!this.alive) return;
    const wasFollowing = this.opts.follow !== undefined;
    const following = (patch.follow === undefined ? this.opts.follow : patch.follow) != null;
    if (following && this.parent !== undefined) throw new BridgeError('invalid_message', 'only top-level groups can follow; nested groups move with their parent');
    let pos = this.pos;
    if (following) {
      if (patch.pos !== undefined) throw new BridgeError('invalid_message', 'a following group is placed with offset, not pos');
      if (patch.offset != null) pos = toVec3(patch.offset, 'group offset');
      else if (!wasFollowing || patch.offset === null) pos = ORIGIN;
    } else {
      if (patch.offset != null) throw new BridgeError('invalid_message', 'group offset applies only with follow; use pos');
      if (patch.pos !== undefined) pos = toVec3(patch.pos, 'group pos');
      else if (wasFollowing) throw new BridgeError('invalid_message', 'a group that stops following needs pos');
    }
    this.commit(pos, undefined, mergePatch(this.opts, patch, ['pos', 'offset']));
  }

  private ensureAlive(): void {
    if (!this.alive) throw new BridgeError('invalid_message', `group ${this.id} was removed`);
  }

  /** Child options: the group reference, and the group's world unless the child names one. */
  private child<T extends PlacementOptions & { group?: string }>(o: T): T {
    const out: T = { ...o, group: this.id };
    if (o.world === undefined && this.opts.world !== undefined) out.world = this.opts.world;
    return out;
  }

  private childId(id: string): string {
    this.ensureAlive();
    checkShapeId(id);
    return `${this.id}/${id}`;
  }

  text(id: string, pos: Vec3Like, content: string, o: TextDisplayOptions = {}): DisplayImpl<'core:display.text', TextDisplayOptions, string> {
    return this.layerHost.makeText(this.childId(id), pos, content, this.child(o));
  }

  item(id: string, pos: Vec3Like, item: ItemInput, o: ItemDisplayOptions = {}): DisplayImpl<'core:display.item', ItemDisplayOptions, SlotItem> {
    return this.layerHost.makeItem(this.childId(id), pos, item, this.child(o));
  }

  block(id: string, pos: Vec3Like, state: string, o: BlockDisplayOptions = {}): DisplayImpl<'core:display.block', BlockDisplayOptions, string> {
    return this.layerHost.makeBlock(this.childId(id), pos, state, this.child(o));
  }

  entity(id: string, pos: Vec3Like, type: string, o: EntityDisplayOptions = {}): DisplayImpl<'core:display.entity', EntityDisplayOptions, string> {
    return this.layerHost.makeEntity(this.childId(id), pos, type, this.child(o));
  }

  emitter(id: string, pos: Vec3Like, particle: string, o: EmitterOptions = {}): EmitterImpl {
    return this.layerHost.makeEmitter(this.childId(id), pos, particle, this.child(o));
  }

  sound(id: string, pos: Vec3Like, sound: SoundSource, o: SoundEmitterOptions = {}): SoundEmitter {
    return this.layerHost.makeSound(this.childId(id), pos, sound, this.child(o));
  }

  group(id: string, o: GroupOptions): GroupImpl {
    const shapeId = this.childId(id);
    const opts: GroupOptions = { ...o };
    if (o.world === undefined && this.opts.world !== undefined) opts.world = this.opts.world;
    return this.layerHost.makeGroup(shapeId, opts, this);
  }

  override remove(): void {
    if (!this.alive) return;
    this.dropChildren();
    super.remove();
  }

  protected override drop(): void {
    this.host.delete(this.id, 'both');
  }

  override replaced(): void {
    this.dropChildren();
    this.retire();
    this.host.delete(this.id, 'children');
  }

  private dropChildren(): void {
    this.host.retirePrefix(`${this.id}/`);
    this.nested.clear();
    this.parent?.nested.delete(this);
  }
}

/** Display and group ids name one path segment; `/` is reserved for group children. */
export function checkShapeId(id: string): void {
  if (typeof id !== 'string' || id === '' || id.includes('/')) throw new BridgeError('invalid_message', `display and group ids must be non-empty strings without /: ${String(id)}`);
}

export const content = {
  text: (v: string): string => text('text', v),
  item: (v: ItemInput, ctx: ItemContext = NO_SELF): SlotItem => toSlotItem(v, ctx),
  block: (v: string): string => blockState(v),
  entity: (v: string): string => resourceId('entity type', v),
  particle: (v: string): string => resourceId('particle type', v),
};

export const builders = { text: textData, item: itemData, block: blockData, entity: entityData };
