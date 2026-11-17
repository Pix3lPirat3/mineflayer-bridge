import { readFile } from 'node:fs/promises';
import { basename, extname } from 'node:path';
import { gunzipSync } from 'node:zlib';
import { BridgeError, type Vec3i } from '@mineflayer-bridge/protocol';
import { defineExtension } from './extensions.ts';
import { batch, isFiniteNumber } from './ext-util.ts';
import type { Vec3WithOffset } from './desync-view.ts';
import type { HudAnchorName, HudPanel } from './input-types.ts';
import { readNbt, type NbtTag } from './nbt.ts';
import type { GhostBlocks } from './retained-types.ts';
import type { BotLike, BoxHandle, Vec3Like } from './types.ts';
import { toVec3 } from './util.ts';

/** A schematic holds at most this many positions (air included): 64 MiB of palette indices. */
export const MAX_SCHEMATIC_BLOCKS = 16 * 1024 * 1024;
/** Gunzipped schematics larger than this are refused. */
const MAX_INFLATED_BYTES = 256 * 1024 * 1024;

const AIR: ReadonlySet<string> = new Set(['minecraft:air', 'minecraft:cave_air', 'minecraft:void_air']);
const DIRS = ['north', 'east', 'south', 'west'] as const;

/**
 * A parsed schematic. Positions are local: `x` in `0..size.x - 1` (Sponge `Width`), `y` in
 * `0..size.y - 1` (`Height`), `z` in `0..size.z - 1` (`Length`). Immutable; {@link rotated} returns
 * a new one.
 */
export interface Schematic {
  /** `Metadata.Name` of the file, when it has one. */
  readonly name: string | undefined;
  /** Sponge format version: 1, 2 or 3. */
  readonly version: number;
  /** Minecraft data version the blocks were saved with, when given. */
  readonly dataVersion: number | undefined;
  readonly size: Vec3i;
  /** Sponge `Offset`: where the schematic sat relative to the player who saved it. Not applied by placement. */
  readonly offset: Vec3i;
  /** Block states, namespaced, such as `minecraft:oak_stairs[facing=east,half=bottom]`. */
  readonly palette: readonly string[];
  /** Palette index per position, at `(y * size.z + z) * size.x + x`. */
  readonly blocks: Uint32Array;
  /** Positions that are not air. */
  readonly blockCount: number;
  /** The block state at a local position, air included, or `undefined` outside the schematic. */
  blockAt(pos: Vec3Like): string | undefined;
  /** Count per block id (state properties dropped), air left out, most common first. */
  materials(): Record<string, number>;
  /**
   * The schematic turned by `steps` quarter turns clockwise seen from above (negative steps turn
   * counter-clockwise), with its minimum corner kept at local `0, 0, 0`. Block states are turned
   * by {@link rotateBlockState}.
   */
  rotated(steps: number): Schematic;
}

/** The namespaced block id of a block state or name: `stone` and `minecraft:stone[x=y]` give `minecraft:stone`. */
export function blockId(state: string): string {
  const open = state.indexOf('[');
  const id = (open < 0 ? state : state.slice(0, open)).trim();
  return id.includes(':') ? id : `minecraft:${id}`;
}

function normalState(state: string): string {
  const s = state.trim();
  const open = s.indexOf('[');
  const id = open < 0 ? s : s.slice(0, open);
  return id.includes(':') ? s : `minecraft:${s}`;
}

function turn(dir: string, steps: number): string {
  const i = (DIRS as readonly string[]).indexOf(dir);
  return i < 0 ? dir : DIRS[(i + steps) % 4]!;
}

function turnRail(shape: string, steps: number): string {
  if (shape.startsWith('ascending_')) return `ascending_${turn(shape.slice('ascending_'.length), steps)}`;
  const parts = shape.split('_');
  if (parts.length !== 2 || !parts.every((p) => (DIRS as readonly string[]).includes(p))) return shape;
  const turned = parts.map((p) => turn(p, steps));
  const ns = turned.find((p) => p === 'north' || p === 'south');
  const ew = turned.find((p) => p === 'east' || p === 'west');
  if (ns !== undefined && ew !== undefined) return `${ns}_${ew}`;
  return ns !== undefined ? 'north_south' : 'east_west';
}

/**
 * Turns a block state by `steps` quarter turns clockwise seen from above, best effort: `facing`
 * values, the side keys `north`, `east`, `south` and `west` (fences, walls, panes, redstone, vines),
 * `axis` (`x` and `z` swap on odd steps), `rotation` (signs, banners, heads: 0 to 15) and rail
 * `shape`. Other properties are kept, which is right for those relative to the block's own facing
 * (stair `shape`, door `hinge`, chest `type`). Properties come back sorted by key.
 */
export function rotateBlockState(state: string, steps: number): string {
  const k = ((Math.trunc(steps) % 4) + 4) % 4;
  const s = normalState(state);
  const open = s.indexOf('[');
  if (k === 0 || open < 0 || !s.endsWith(']')) return s;
  const props = new Map<string, string>();
  for (const part of s.slice(open + 1, -1).split(',')) {
    const eq = part.indexOf('=');
    if (eq < 0) continue;
    const key = part.slice(0, eq).trim();
    let value = part.slice(eq + 1).trim();
    let name = key;
    if ((DIRS as readonly string[]).includes(key)) name = turn(key, k);
    else if (key === 'facing') value = turn(value, k);
    else if (key === 'axis' && k % 2 === 1) value = value === 'x' ? 'z' : value === 'z' ? 'x' : value;
    else if (key === 'rotation' && /^\d+$/.test(value)) value = String((Number(value) + 4 * k) % 16);
    else if (key === 'shape') value = turnRail(value, k);
    props.set(name, value);
  }
  const keys = [...props.keys()].sort();
  return `${s.slice(0, open)}[${keys.map((key) => `${key}=${props.get(key)!}`).join(',')}]`;
}

class SchematicData implements Schematic {
  readonly blockCount: number;
  /** True per palette index that is air. */
  private readonly air: boolean[];

  constructor(
    readonly name: string | undefined,
    readonly version: number,
    readonly dataVersion: number | undefined,
    readonly size: Vec3i,
    readonly offset: Vec3i,
    readonly palette: readonly string[],
    readonly blocks: Uint32Array,
  ) {
    this.air = palette.map((p) => AIR.has(blockId(p)));
    let n = 0;
    for (const b of blocks) if (!this.air[b]) n++;
    this.blockCount = n;
  }

  isAir(index: number): boolean {
    return this.air[index] === true;
  }

  blockAt(pos: Vec3Like): string | undefined {
    const p = toVec3(pos, 'pos');
    const { x, y, z } = { x: Math.floor(p.x), y: Math.floor(p.y), z: Math.floor(p.z) };
    if (x < 0 || y < 0 || z < 0 || x >= this.size.x || y >= this.size.y || z >= this.size.z) return undefined;
    return this.palette[this.blocks[(y * this.size.z + z) * this.size.x + x]!];
  }

  /** Calls `fn` for every non-air position with its palette index. */
  forEach(fn: (x: number, y: number, z: number, index: number) => void): void {
    const { x: w, y: h, z: l } = this.size;
    let i = 0;
    for (let y = 0; y < h; y++) {
      for (let z = 0; z < l; z++) {
        for (let x = 0; x < w; x++, i++) {
          const b = this.blocks[i]!;
          if (!this.air[b]) fn(x, y, z, b);
        }
      }
    }
  }

  materials(): Record<string, number> {
    const perIndex = new Array<number>(this.palette.length).fill(0);
    for (const b of this.blocks) perIndex[b]!++;
    const counts = new Map<string, number>();
    perIndex.forEach((n, i) => {
      if (n === 0 || this.air[i]) return;
      const id = blockId(this.palette[i]!);
      counts.set(id, (counts.get(id) ?? 0) + n);
    });
    return sortedCounts(counts);
  }

  rotated(steps: number): SchematicData {
    if (!Number.isInteger(steps)) throw new BridgeError('invalid_message', `rotation steps must be an integer, got ${String(steps)}`);
    const k = ((steps % 4) + 4) % 4;
    if (k === 0) return this;
    const { x: w, y: h, z: l } = this.size;
    const odd = k % 2 === 1;
    const nw = odd ? l : w;
    const nl = odd ? w : l;
    const out = new Uint32Array(this.blocks.length);
    let i = 0;
    for (let y = 0; y < h; y++) {
      for (let z = 0; z < l; z++) {
        for (let x = 0; x < w; x++, i++) {
          // Clockwise from above: north (-z) becomes east (+x).
          const nx = k === 1 ? l - 1 - z : k === 2 ? w - 1 - x : z;
          const nz = k === 1 ? x : k === 2 ? l - 1 - z : w - 1 - x;
          out[(y * nl + nz) * nw + nx] = this.blocks[i]!;
        }
      }
    }
    return new SchematicData(this.name, this.version, this.dataVersion, { x: nw, y: h, z: nl }, this.offset, this.palette.map((p) => rotateBlockState(p, k)), out);
  }
}

function sortedCounts(counts: Map<string, number>): Record<string, number> {
  const out: Record<string, number> = {};
  for (const [id, n] of [...counts].sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1))) out[id] = n;
  return out;
}

type Compound = { readonly [key: string]: NbtTag };

function isCompound(v: NbtTag | undefined): v is Compound {
  return typeof v === 'object' && v !== null && !Array.isArray(v) && !ArrayBuffer.isView(v);
}

function bad(why: string): never {
  throw new BridgeError('invalid_message', `schematic: ${why}`);
}

function intTag(c: Compound, key: string, required: true): number;
function intTag(c: Compound, key: string, required?: false): number | undefined;
function intTag(c: Compound, key: string, required = false): number | undefined {
  const v = c[key];
  if (typeof v === 'number' && Number.isInteger(v)) return v;
  if (v === undefined && !required) return undefined;
  return bad(`${key} must be an integer tag`);
}

/** Sponge `BlockData`: one unsigned LEB128 varint per position. */
function varints(data: Int8Array, count: number, paletteSize: number): Uint32Array {
  const out = new Uint32Array(count);
  let at = 0;
  for (let i = 0; i < count; i++) {
    let value = 0;
    let shift = 0;
    for (;;) {
      if (at >= data.length) bad(`block data ends after ${i} of ${count} blocks`);
      const b = data[at++]! & 0xff;
      value |= (b & 0x7f) << shift;
      if ((b & 0x80) === 0) break;
      shift += 7;
      if (shift > 28) bad('block data has a varint longer than 5 bytes');
    }
    if (value < 0 || value >= paletteSize) bad(`block data refers to palette index ${value}, but the palette has ${paletteSize} entries`);
    out[i] = value;
  }
  if (at !== data.length) bad(`block data has ${data.length - at} bytes left after ${count} blocks`);
  return out;
}

/**
 * Parses a Sponge schematic (`.schem`, versions 1 to 3), gzipped or not. Block entities, entities
 * and biomes are ignored.
 * @throws BridgeError `invalid_message` for data that is not a Sponge schematic, `quota_exceeded`
 *   above {@link MAX_SCHEMATIC_BLOCKS} positions.
 */
export function parseSchematic(bytes: Uint8Array): Schematic {
  if (!(bytes instanceof Uint8Array)) bad('expected a Buffer or Uint8Array');
  let raw = bytes;
  if (bytes.length >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b) {
    try {
      raw = gunzipSync(bytes, { maxOutputLength: MAX_INFLATED_BYTES });
    } catch (e) {
      bad(`gunzip failed: ${(e as Error).message}`);
    }
  }
  const { value } = readNbt(raw);
  if (!isCompound(value)) bad('the root must be a compound');
  // Version 3 nests everything in a `Schematic` compound.
  const root = isCompound(value.Schematic) ? value.Schematic : value;
  const version = intTag(root, 'Version') ?? 1;
  if (version < 1 || version > 3) bad(`unsupported Sponge version ${version}`);
  const w = intTag(root, 'Width', true) & 0xffff;
  const h = intTag(root, 'Height', true) & 0xffff;
  const l = intTag(root, 'Length', true) & 0xffff;
  const count = w * h * l;
  if (count === 0) bad(`empty size ${w}x${h}x${l}`);
  if (count > MAX_SCHEMATIC_BLOCKS) throw new BridgeError('quota_exceeded', `schematic: ${w}x${h}x${l} is ${count} blocks, above ${MAX_SCHEMATIC_BLOCKS}`, { details: { blocks: count, limit: MAX_SCHEMATIC_BLOCKS } });

  const container = version >= 3 ? root.Blocks : root;
  if (!isCompound(container)) bad('version 3 needs a Blocks compound');
  const paletteTag = container.Palette;
  if (!isCompound(paletteTag)) bad('no block Palette (version 1 files without one are not supported)');
  const entries = Object.entries(paletteTag);
  const palette: string[] = new Array<string>(entries.length);
  for (const [state, index] of entries) {
    if (typeof index !== 'number' || !Number.isInteger(index) || index < 0 || index >= entries.length) bad(`palette index of ${state} must be from 0 to ${entries.length - 1}`);
    if (palette[index] !== undefined) bad(`palette index ${index} is used twice`);
    palette[index] = normalState(state);
  }
  const data = version >= 3 ? container.Data : container.BlockData;
  if (!(data instanceof Int8Array)) bad(`${version >= 3 ? 'Blocks.Data' : 'BlockData'} must be a byte array`);
  const blocks = varints(data, count, palette.length);

  const off = root.Offset;
  const offset: Vec3i = off instanceof Int32Array && off.length === 3 ? { x: off[0]!, y: off[1]!, z: off[2]! } : { x: 0, y: 0, z: 0 };
  const meta = root.Metadata;
  const name = isCompound(meta) && typeof meta.Name === 'string' && meta.Name !== '' ? meta.Name : undefined;
  return new SchematicData(name, version, intTag(root, 'DataVersion'), { x: w, y: h, z: l }, offset, palette, blocks);
}

/** Reads and parses a Sponge schematic file. Same errors as {@link parseSchematic}. */
export async function readSchematic(path: string): Promise<Schematic> {
  return parseSchematic(await readFile(path));
}

/** What the world holds at a position: a block name or state (`stone`, `minecraft:oak_log[axis=y]`), or nothing when unknown. */
export type BlockLookup = (pos: Vec3i) => string | null | undefined;

/** Build progress of a placement. */
export interface SchematicProgress {
  /** Non-air blocks of the schematic whose id the world already has. */
  readonly placed: number;
  /** Non-air blocks of the schematic. */
  readonly total: number;
}

/**
 * What the player can do to a placement from the context menu (R by default) while looking at its
 * frame: turn it a quarter clockwise or counter-clockwise, as {@link SchematicHandle.rotate} with 1
 * or -1, or remove it, as {@link SchematicHandle.remove}. The handle's `rotated` and `removed`
 * events follow as for the bot's own calls.
 */
export type SchematicAction = 'rotate-cw' | 'rotate-ccw' | 'remove';

const ACTIONS: readonly { readonly id: SchematicAction; readonly label: string; readonly icon?: string }[] = [
  { id: 'rotate-cw', label: 'Turn clockwise' },
  { id: 'rotate-ccw', label: 'Turn counter-clockwise' },
  { id: 'remove', label: 'Remove', icon: 'mineflayer_bridge:trash' },
];
const ACTION_IDS: ReadonlySet<string> = new Set(ACTIONS.map((a) => a.id));

/** Options of `load` and `place`. */
export interface SchematicPlaceOptions {
  /** World position of the minimum corner. Floored. Default: the block the bot stands in. */
  origin?: Vec3Like;
  /**
   * Placement name, also the ghost-block shape id; loading a name again replaces that placement.
   * Default: the file's `Metadata.Name`, the file name without extension, or `schematic`.
   */
  name?: string;
  /** Quarter turns clockwise seen from above. Default 0. */
  rotation?: number;
  /** Shift+click on the frame moves the placement with the gizmo. Default: the extension's `editable` option. */
  editable?: boolean;
  /**
   * Actions offered in the context menu on the frame. Default: all three when the
   * placement is editable, none otherwise. A placement that is neither editable nor has actions
   * draws no frame.
   */
  actions?: readonly SchematicAction[];
}

/** Events of a placement. */
export interface SchematicHandleEvents {
  /** The origin changed, by `moveTo` or by the player's gizmo. */
  moved: [origin: Vec3i];
  /** The rotation changed. */
  rotated: [rotation: number];
  /** `progress` or `remaining` computed new numbers. */
  progress: [progress: SchematicProgress];
  removed: [];
}

/**
 * One placed schematic: ghost blocks with `diffAgainstWorld` (missing blocks and wrong ones are
 * coloured, matching ones hidden) and an outline box whose `min` the player can move with the
 * gizmo. After `remove` the handle is inert.
 */
export interface SchematicHandle {
  readonly name: string;
  readonly alive: boolean;
  /** The schematic as placed, turned by {@link rotation}. */
  readonly schematic: Schematic;
  /** World position of the minimum corner. */
  readonly origin: Vec3i;
  /** Quarter turns clockwise, 0 to 3. */
  readonly rotation: number;
  /** Inclusive world bounds. */
  readonly bounds: { readonly min: Vec3i; readonly max: Vec3i };
  /** Count per block id, air left out, most common first. */
  materials(): Record<string, number>;
  /**
   * Count per block id still missing: positions where the world's block id differs from the
   * schematic's. Block state properties are not compared. Default lookup: `bot.blockAt`.
   * @throws BridgeError `invalid_message` without a lookup when the bot has no `blockAt`.
   */
  remaining(lookup?: BlockLookup): Record<string, number>;
  /**
   * Placed and total blocks, compared as in {@link remaining}. Updates the HUD bar and, when
   * `bridge:tasks` is loaded, its task `Build <name>`.
   */
  progress(lookup?: BlockLookup): SchematicProgress;
  /** The block state the schematic wants at a world position (air included), or `undefined` outside it. */
  blockAt(pos: Vec3Like): string | undefined;
  /** Moves the minimum corner to `pos` (floored). Sends no blocks again. */
  moveTo(pos: Vec3Like): void;
  /** Turns by `steps` quarter turns clockwise (negative: counter-clockwise), keeping the origin. */
  rotate(steps: number): void;
  on<E extends keyof SchematicHandleEvents>(event: E, fn: (...args: SchematicHandleEvents[E]) => void): () => void;
  remove(): void;
}

/** Public API of `bridge:schematic`, from `bot.bridge.get('bridge:schematic')`. */
export interface SchematicApi {
  /**
   * Places a schematic from a file path, file bytes or a parsed {@link Schematic}.
   * @throws BridgeError from {@link parseSchematic}, or `invalid_message` for bad options.
   */
  load(source: string | Uint8Array | Schematic, options?: SchematicPlaceOptions): Promise<SchematicHandle>;
  /** Places a parsed schematic. */
  place(schematic: Schematic, options?: SchematicPlaceOptions): SchematicHandle;
  get(name: string): SchematicHandle | undefined;
  /** Placements, in load order. */
  readonly placed: readonly SchematicHandle[];
  /** Removes every placement. */
  clear(): void;
}

/** What `bridge:schematic` uses of a `bridge:tasks` task, read structurally. */
interface TaskLike {
  progress?(current: number, total?: number): unknown;
  status?(text: string): unknown;
  done?(text?: string): unknown;
}

interface TasksLike {
  task(name: string): TaskLike;
}

/** The parts of a mineflayer bot `bridge:schematic` reads, all optional. */
export interface SchematicBotLike extends BotLike {
  entity?: { readonly position: Vec3WithOffset; readonly id?: number } | null;
  blockAt?(point: Vec3WithOffset): { readonly name: string } | null;
}

function blockPos(v: Vec3Like, what: string): Vec3i {
  const p = toVec3(v, what);
  if (!isFiniteNumber(p.x) || !isFiniteNumber(p.y) || !isFiniteNumber(p.z)) throw new BridgeError('invalid_message', `${what} must be finite`);
  return { x: Math.floor(p.x), y: Math.floor(p.y), z: Math.floor(p.z) };
}

function isSchematic(v: unknown): v is SchematicData {
  return v instanceof SchematicData;
}

function placementName(name: string): string {
  if (typeof name !== 'string' || name === '' || name.includes('/') || name.includes('~')) throw new BridgeError('invalid_message', `schematic name must be a non-empty string without / or ~: ${String(name)}`);
  return name;
}

function actionList(list: unknown): SchematicAction[] {
  if (!Array.isArray(list)) throw new BridgeError('invalid_message', 'schematic actions must be a list');
  for (const a of list) if (!ACTION_IDS.has(a as string)) throw new BridgeError('invalid_message', `unknown schematic action ${String(a)}; use ${[...ACTION_IDS].join(', ')}`);
  return [...new Set(list as SchematicAction[])];
}

/** Templates read `{var}`; names are shown literally. */
const literal = (s: string): string => s.replaceAll('{', '(').replaceAll('}', ')');

/**
 * `bridge:schematic`: imports Sponge schematics (`.schem` v1 to v3), places them as ghost blocks
 * diffed against the player's world, lists materials and tracks build progress.
 *
 * Each placement is a ghost-block shape in layer `schematic` (id: the placement name) plus an
 * outline box (`<name>~frame`) with `min` editable through the move gizmo: Shift+click the frame
 * and drag, and the ghost blocks follow. Looking at the frame, the context menu key (R by default)
 * offers the placement's {@link SchematicAction}s: turn it or remove it. They are context actions
 * of this extension's scope (`<name>~rotate-cw` and so on) bound to the frame through its
 * `actions` trait, so they show on that frame only. `moveTo` only changes the shape's origin, so
 * no blocks are sent again; `rotate` rebuilds the volume. With `hud`, a panel `schematics` shows a bar per
 * placement, updated by `progress()`. When `bridge:tasks` is loaded (optional), `progress()` also
 * reports through `task('Build <name>')`.
 *
 * Options: `alpha` (default 0.5), `hideMatching` (default true), `editable` (default true), `hud`
 * (default true), `title` (default `Schematics`), `anchor` (default `top-left`), `intervalMs`
 * (HUD redraws, default 100 ms) and `defaultVisible` (default true). Works in bot and controller
 * sessions; without a bot, `origin` and a lookup must be given.
 */
export const schematic = defineExtension({
  id: 'bridge:schematic',
  version: '0.1.0',
  requires: { optionalExtensions: ['bridge:tasks'] },
  options: {
    alpha: { type: 'number', default: 0.5, min: 0, max: 1 },
    hideMatching: { type: 'boolean', default: true },
    editable: { type: 'boolean', default: true },
    hud: { type: 'boolean', default: true },
    title: { type: 'string', default: 'Schematics' },
    anchor: { type: 'string', default: 'top-left', enum: ['top-left', 'top-center', 'top-right', 'middle-left', 'middle-right', 'bottom-left', 'bottom-right'] },
    intervalMs: { type: 'integer', default: 100, min: 0, max: 60_000 },
    defaultVisible: { type: 'boolean', default: true },
  },
  setup(ctx, options): SchematicApi {
    const bot = ctx.bot as SchematicBotLike | null;
    const layer = ctx.layer('schematic', { category: 'planning', defaultVisible: options.defaultVisible });
    const placements = new Map<string, Placement>();
    let panel: HudPanel | undefined;

    const draw = (): void => {
      if (!options.hud) return;
      if (placements.size === 0) {
        panel?.destroy();
        panel = undefined;
        return;
      }
      panel ??= ctx.hud('schematics', { title: options.title, anchor: options.anchor as HudAnchorName });
      panel.clear();
      let order = 0;
      for (const p of placements.values()) {
        const total = p.data.blockCount;
        const label = p.last === undefined ? `${literal(p.name)}: ${total} blocks` : `${literal(p.name)}: ${p.last.placed}/${p.last.total}`;
        panel.bar(`p${p.serial}`, { value: Math.min(p.last?.placed ?? 0, Math.max(total, 1)), min: 0, max: Math.max(total, 1), label, color: '#81c784', order: order++ });
      }
    };
    const redraw = batch(ctx, options.intervalMs, draw);

    const defaultLookup = (): BlockLookup => {
      const blockAt = bot?.blockAt;
      if (bot === null || typeof blockAt !== 'function') throw new BridgeError('invalid_message', 'this session has no bot.blockAt; pass a lookup');
      return (pos) => {
        const at = bot.entity?.position;
        // mineflayer wants a Vec3; derive one from the bot's own position when it can.
        const base = at !== undefined && typeof at.floored === 'function' ? at.floored() : undefined;
        const v: Vec3WithOffset = base !== undefined && typeof base.offset === 'function' ? base.offset(pos.x - base.x, pos.y - base.y, pos.z - base.z) : pos;
        return blockAt.call(bot, v)?.name;
      };
    };

    const tasks = (): TasksLike | undefined => {
      const api = ctx.optional<TasksLike>('bridge:tasks');
      return api !== undefined && api !== null && typeof api.task === 'function' ? api : undefined;
    };

    let serials = 0;

    class Placement implements SchematicHandle {
      readonly serial = serials++;
      data: SchematicData;
      private turns: number;
      private at: Vec3i;
      private live = true;
      private readonly ghost: GhostBlocks;
      private readonly frame: BoxHandle | undefined;
      /** Unregisters the frame's actions. */
      private readonly unregister: (() => void)[] = [];
      /** The frame's declared `min`, which differs from {@link at} right after a gizmo edit. */
      private frameAt: Vec3i;
      private readonly listeners = new Map<string, Set<(...args: never[]) => void>>();
      last: SchematicProgress | undefined;
      /** Task progress was reported and the build is not complete yet. */
      private taskOpen = false;

      constructor(
        readonly name: string,
        private readonly base: SchematicData,
        o: SchematicPlaceOptions,
      ) {
        if (o.rotation !== undefined && !Number.isInteger(o.rotation)) throw new BridgeError('invalid_message', `rotation must be an integer, got ${String(o.rotation)}`);
        this.turns = (((o.rotation ?? 0) % 4) + 4) % 4;
        this.at = o.origin !== undefined ? blockPos(o.origin, 'origin') : this.botOrigin();
        this.frameAt = this.at;
        this.data = base.rotated(this.turns);
        const editable = o.editable ?? options.editable;
        const offered = actionList(o.actions ?? (editable ? ACTIONS.map((a) => a.id) : []));
        this.ghost = layer.ghostBlocks(name, this.volume(), { origin: this.at, alpha: options.alpha, diffAgainstWorld: true, hideMatching: options.hideMatching });
        if (editable || offered.length > 0) {
          try {
            this.frame = layer.box(`${name}~frame`, this.at, this.data.size, {
              color: '#4fc3f7',
              ...(editable ? { editable: [{ field: 'min', editor: 'gizmo', label: 'Origin' }] } : {}),
              hover: { glow: '#4fc3f7' },
              actions: offered.map((id) => `${name}~${id}`),
            });
            for (const id of offered) this.unregister.push(this.registerAction(id));
          } catch (e) {
            for (const off of this.unregister) off();
            this.frame?.remove();
            this.ghost.remove();
            throw e;
          }
        }
        if (this.frame !== undefined && editable) {
          this.frame.onEdit((proposed, _current, info) => {
            if (info.fields.some((f) => f !== 'min')) return 'only the origin can be moved';
            const m = proposed.min;
            return isFiniteNumber(m.x) && isFiniteNumber(m.y) && isFiniteNumber(m.z);
          });
          this.frame.on('edited', (d) => {
            this.frameAt = { x: d.min.x, y: d.min.y, z: d.min.z };
            if (this.live) this.moveTo(d.min);
          });
        }
      }

      private registerAction(id: SchematicAction): () => void {
        const a = ACTIONS.find((x) => x.id === id)!;
        return ctx.actions.register(`${this.name}~${id}`, {
          label: a.label,
          ...(a.icon === undefined ? {} : { icon: a.icon }),
          // Bound to the frame by its trait; the tag keeps it off other objects' menus.
          target: { type: 'element', tags: ['bridge:schematic'] },
          order: ACTIONS.indexOf(a),
          run: () => {
            if (id === 'remove') this.remove();
            else this.rotate(id === 'rotate-cw' ? 1 : -1);
          },
        });
      }

      private botOrigin(): Vec3i {
        const p = bot?.entity?.position;
        if (p === undefined) throw new BridgeError('invalid_message', 'schematic origin is required without a bot position');
        return blockPos(p, 'bot position');
      }

      private volume() {
        const v = ctx.volume();
        const { palette } = this.data;
        this.data.forEach((x, y, z, i) => v.set([x, y, z], palette[i]!));
        return v;
      }

      private emit<E extends keyof SchematicHandleEvents>(event: E, ...args: SchematicHandleEvents[E]): void {
        for (const fn of this.listeners.get(event) ?? []) {
          try {
            (fn as (...a: SchematicHandleEvents[E]) => void)(...args);
          } catch (e) {
            ctx.log.error('schematic listener failed', { event, error: (e as Error).message });
          }
        }
      }

      get alive(): boolean {
        return this.live;
      }

      get schematic(): Schematic {
        return this.data;
      }

      get origin(): Vec3i {
        return { ...this.at };
      }

      get rotation(): number {
        return this.turns;
      }

      get bounds(): { min: Vec3i; max: Vec3i } {
        const { x, y, z } = this.at;
        const s = this.data.size;
        return { min: { x, y, z }, max: { x: x + s.x - 1, y: y + s.y - 1, z: z + s.z - 1 } };
      }

      materials(): Record<string, number> {
        return this.data.materials();
      }

      private scan(lookup: BlockLookup | undefined): { placed: number; missing: Map<string, number> } {
        const look = lookup ?? defaultLookup();
        if (typeof look !== 'function') throw new BridgeError('invalid_message', 'lookup must be a function');
        const ids = this.data.palette.map(blockId);
        const missing = new Map<string, number>();
        let placed = 0;
        const { x: ox, y: oy, z: oz } = this.at;
        this.data.forEach((x, y, z, i) => {
          const have = look({ x: ox + x, y: oy + y, z: oz + z });
          const want = ids[i]!;
          if (typeof have === 'string' && blockId(have) === want) placed++;
          else missing.set(want, (missing.get(want) ?? 0) + 1);
        });
        return { placed, missing };
      }

      remaining(lookup?: BlockLookup): Record<string, number> {
        const { placed, missing } = this.scan(lookup);
        this.report(placed);
        return sortedCounts(missing);
      }

      progress(lookup?: BlockLookup): SchematicProgress {
        return this.report(this.scan(lookup).placed);
      }

      private report(placed: number): SchematicProgress {
        const p: SchematicProgress = { placed, total: this.data.blockCount };
        if (!this.live) return p;
        const changed = this.last === undefined || this.last.placed !== p.placed || this.last.total !== p.total;
        this.last = p;
        if (changed) {
          redraw.request();
          this.emit('progress', p);
        }
        const api = changed ? tasks() : undefined;
        if (api !== undefined) {
          const t = api.task(`Build ${this.name}`);
          t.progress?.(p.placed, p.total);
          if (p.placed >= p.total) {
            t.done?.(`Built ${this.name}`);
            this.taskOpen = false;
          } else {
            t.status?.(`${p.total - p.placed} blocks left`);
            this.taskOpen = true;
          }
        }
        return p;
      }

      blockAt(pos: Vec3Like): string | undefined {
        const p = blockPos(pos, 'pos');
        return this.data.blockAt({ x: p.x - this.at.x, y: p.y - this.at.y, z: p.z - this.at.z });
      }

      moveTo(pos: Vec3Like): void {
        if (!this.live) return;
        const next = blockPos(pos, 'origin');
        const moved = next.x !== this.at.x || next.y !== this.at.y || next.z !== this.at.z;
        this.ghost.update(undefined, { origin: next });
        this.at = next;
        // A gizmo edit leaves the frame at the dragged, unfloored position.
        const fm = this.frameAt;
        if (this.frame?.alive === true && (fm.x !== next.x || fm.y !== next.y || fm.z !== next.z)) {
          this.frame.update({ min: next });
          this.frameAt = next;
        }
        if (moved) {
          this.last = undefined;
          redraw.request();
          this.emit('moved', this.origin);
        }
      }

      rotate(steps: number): void {
        if (!this.live) return;
        if (!Number.isInteger(steps)) throw new BridgeError('invalid_message', `rotation steps must be an integer, got ${String(steps)}`);
        const turns = (((this.turns + steps) % 4) + 4) % 4;
        if (turns === this.turns) return;
        const data = this.base.rotated(turns);
        const prev = this.data;
        this.data = data;
        try {
          this.ghost.update(this.volume());
        } catch (e) {
          this.data = prev;
          throw e;
        }
        this.turns = turns;
        if (this.frame?.alive === true) this.frame.update({ size: data.size });
        this.last = undefined;
        redraw.request();
        this.emit('rotated', turns);
      }

      on<E extends keyof SchematicHandleEvents>(event: E, fn: (...args: SchematicHandleEvents[E]) => void): () => void {
        let set = this.listeners.get(event);
        if (set === undefined) this.listeners.set(event, (set = new Set()));
        set.add(fn as (...args: never[]) => void);
        return () => void set.delete(fn as (...args: never[]) => void);
      }

      remove(): void {
        if (!this.live) return;
        this.live = false;
        this.ghost.remove();
        this.frame?.remove();
        for (const off of this.unregister) off();
        if (placements.get(this.name) === this) placements.delete(this.name);
        if (this.taskOpen) tasks()?.task(`Build ${this.name}`).done?.(`Removed ${this.name}`);
        this.taskOpen = false;
        redraw.request();
        this.emit('removed');
        this.listeners.clear();
      }
    }

    const place = (s: Schematic, o: SchematicPlaceOptions = {}, fallbackName?: string): SchematicHandle => {
      if (!isSchematic(s)) throw new BridgeError('invalid_message', 'place needs a schematic from parseSchematic or readSchematic');
      if (typeof o !== 'object' || o === null) throw new BridgeError('invalid_message', 'schematic options must be an object');
      const name = placementName(o.name ?? s.name?.replaceAll('/', '_').replaceAll('~', '_') ?? fallbackName ?? 'schematic');
      placements.get(name)?.remove();
      const p = new Placement(name, s, o);
      placements.set(name, p);
      redraw.request();
      return p;
    };

    return {
      async load(source, o) {
        if (typeof source === 'string') {
          const file = basename(source, extname(source));
          return place(await readSchematic(source), o, file === '' ? undefined : file);
        }
        if (source instanceof Uint8Array) return place(parseSchematic(source), o);
        return place(source, o);
      },
      place: (s, o) => place(s, o),
      get: (name) => placements.get(name),
      get placed() {
        return [...placements.values()];
      },
      clear() {
        for (const p of [...placements.values()]) p.remove();
      },
    };
  },
});
