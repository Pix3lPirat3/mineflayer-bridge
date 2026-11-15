import { BridgeError, type Vec3i, type VolumeBlob, type VolumeSection } from '@mineflayer-bridge/protocol';
import type { BlockStateLike, Volume, VolumeBounds, VolumeOptions } from './retained-types.ts';
import type { Vec3Like } from './types.ts';

const SECTION_BLOCKS = 4096;
/** Global state ids are stored in `u16` cells, with 0 meaning unset. */
const MAX_STATES = 0xffff;
/** One `fill` call writes at most this many blocks: 4096 full sections, about the 32 MiB blob cap. */
const MAX_FILL_BLOCKS = 4096 * SECTION_BLOCKS;
const INT32_MIN = -(2 ** 31);
const INT32_MAX = 2 ** 31 - 1;

interface Section {
  readonly x: number;
  readonly y: number;
  readonly z: number;
  /** Global state id per block, 0 for unset, in the schema's `(y * 16 + z) * 16 + x` order. */
  readonly ids: Uint16Array;
  count: number;
}

function blockCoord(v: number, what: string): number {
  if (!Number.isInteger(v) || v < INT32_MIN || v > INT32_MAX) throw new BridgeError('invalid_message', `${what} must be integer block coordinates`);
  return v;
}

/** Reads an integer block position without allocating. */
function coords(pos: Vec3Like, what: string): [number, number, number] {
  if (Array.isArray(pos)) {
    const t = pos as readonly [number, number, number];
    return [blockCoord(t[0], what), blockCoord(t[1], what), blockCoord(t[2], what)];
  }
  const p = pos as { readonly x: number; readonly y: number; readonly z: number };
  return [blockCoord(p.x, what), blockCoord(p.y, what), blockCoord(p.z, what)];
}

function sectionKey(sx: number, sy: number, sz: number): string {
  return `${sx},${sy},${sz}`;
}

/**
 * The ghost-block volume builder. Blocks are written straight into paletted 16x16x16 sections, so
 * no per-block objects exist; {@link encode} produces the `VolumeBlob` payload. Encoding is
 * canonical (sections sorted, palettes in first-use order), so equal contents always give the same
 * blob hash, whatever order they were written in.
 */
export class VolumeImpl implements Volume {
  private readonly sections = new Map<string, Section>();
  /** Interned block states; index 0 is the unset marker. */
  private readonly states: string[] = [''];
  private readonly stateIds = new Map<string, number>();
  private readonly palette: readonly string[] | undefined;
  private blocks = 0;
  private rev = 0;
  private last: Section | undefined;

  constructor(options: VolumeOptions = {}) {
    if (options.palette !== undefined) {
      options.palette.forEach((s, i) => this.checkState(s, `palette[${i}]`));
      this.palette = [...options.palette];
    }
  }

  get count(): number {
    return this.blocks;
  }

  /** Increases on every change; lets handles skip re-encoding an unchanged volume. */
  get revision(): number {
    return this.rev;
  }

  private checkState(state: string, what: string): void {
    if (typeof state !== 'string' || state === '') throw new BridgeError('invalid_message', `${what} must be a non-empty block state string`);
  }

  private stateId(state: BlockStateLike): number {
    let name: string;
    if (typeof state === 'number') {
      const p = this.palette;
      if (p === undefined) throw new BridgeError('invalid_message', 'numeric block states need a palette: bridge.volume({ palette })');
      const s = Number.isInteger(state) ? p[state] : undefined;
      if (s === undefined) throw new BridgeError('invalid_message', `block state id ${state} is not in the palette (0 to ${p.length - 1})`);
      name = s;
    } else {
      this.checkState(state, 'block state');
      name = state;
    }
    let id = this.stateIds.get(name);
    if (id === undefined) {
      if (this.states.length > MAX_STATES) throw new BridgeError('quota_exceeded', `a volume holds at most ${MAX_STATES} distinct block states`);
      id = this.states.length;
      this.states.push(name);
      this.stateIds.set(name, id);
    }
    return id;
  }

  private section(x: number, y: number, z: number, create: boolean): Section | undefined {
    const sx = x >> 4;
    const sy = y >> 4;
    const sz = z >> 4;
    const l = this.last;
    if (l !== undefined && l.x === sx && l.y === sy && l.z === sz) return l;
    const key = sectionKey(sx, sy, sz);
    let s = this.sections.get(key);
    if (s === undefined) {
      if (!create) return undefined;
      s = { x: sx, y: sy, z: sz, ids: new Uint16Array(SECTION_BLOCKS), count: 0 };
      this.sections.set(key, s);
    }
    this.last = s;
    return s;
  }

  /** Writes one cell; `id` 0 clears it. */
  private write(x: number, y: number, z: number, id: number): boolean {
    const s = this.section(x, y, z, id !== 0);
    if (s === undefined) return false;
    const i = (((y & 15) * 16 + (z & 15)) * 16) + (x & 15);
    const prev = s.ids[i]!;
    if (prev === id) return false;
    s.ids[i] = id;
    if (prev === 0) {
      s.count++;
      this.blocks++;
    } else if (id === 0) {
      s.count--;
      this.blocks--;
      if (s.count === 0) {
        this.sections.delete(sectionKey(s.x, s.y, s.z));
        if (this.last === s) this.last = undefined;
      }
    }
    this.rev++;
    return true;
  }

  set(pos: Vec3Like, state: BlockStateLike): this {
    const [x, y, z] = coords(pos, 'pos');
    this.write(x, y, z, this.stateId(state));
    return this;
  }

  get(pos: Vec3Like): string | undefined {
    const [x, y, z] = coords(pos, 'pos');
    const s = this.section(x, y, z, false);
    if (s === undefined) return undefined;
    const id = s.ids[(((y & 15) * 16 + (z & 15)) * 16) + (x & 15)]!;
    return id === 0 ? undefined : this.states[id];
  }

  delete(pos: Vec3Like): boolean {
    const [x, y, z] = coords(pos, 'pos');
    return this.write(x, y, z, 0);
  }

  fill(min: Vec3Like, max: Vec3Like, state: BlockStateLike): this {
    const [ax, ay, az] = coords(min, 'min');
    const [bx, by, bz] = coords(max, 'max');
    const x0 = Math.min(ax, bx);
    const x1 = Math.max(ax, bx);
    const y0 = Math.min(ay, by);
    const y1 = Math.max(ay, by);
    const z0 = Math.min(az, bz);
    const z1 = Math.max(az, bz);
    const n = (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
    if (n > MAX_FILL_BLOCKS) throw new BridgeError('quota_exceeded', `fill of ${n} blocks exceeds ${MAX_FILL_BLOCKS} per call`, { details: { blocks: n, limit: MAX_FILL_BLOCKS } });
    const id = this.stateId(state);
    for (let y = y0; y <= y1; y++) for (let z = z0; z <= z1; z++) for (let x = x0; x <= x1; x++) this.write(x, y, z, id);
    return this;
  }

  setMany(positions: readonly Vec3Like[] | Int32Array, states: BlockStateLike | readonly BlockStateLike[]): this {
    const n = positions instanceof Int32Array ? positions.length / 3 : positions.length;
    if (!Number.isInteger(n)) throw new BridgeError('invalid_message', 'flat positions must be x, y, z triples');
    const perBlock = Array.isArray(states);
    const list = states as readonly BlockStateLike[];
    if (perBlock && list.length !== n) throw new BridgeError('invalid_message', `setMany got ${n} positions and ${list.length} states`);
    const shared = perBlock ? 0 : this.stateId(states as BlockStateLike);
    for (let i = 0; i < n; i++) {
      const id = perBlock ? this.stateId(list[i]!) : shared;
      if (positions instanceof Int32Array) this.write(positions[i * 3]!, positions[i * 3 + 1]!, positions[i * 3 + 2]!, id);
      else {
        const [x, y, z] = coords((positions as readonly Vec3Like[])[i]!, 'pos');
        this.write(x, y, z, id);
      }
    }
    return this;
  }

  clear(): this {
    if (this.blocks === 0) return this;
    this.sections.clear();
    this.last = undefined;
    this.blocks = 0;
    this.rev++;
    return this;
  }

  bounds(): VolumeBounds | undefined {
    if (this.blocks === 0) return undefined;
    const min: Vec3i = { x: INT32_MAX, y: INT32_MAX, z: INT32_MAX };
    const max: Vec3i = { x: INT32_MIN, y: INT32_MIN, z: INT32_MIN };
    for (const s of this.sections.values()) {
      for (let i = 0; i < SECTION_BLOCKS; i++) {
        if (s.ids[i] === 0) continue;
        const x = s.x * 16 + (i & 15);
        const z = s.z * 16 + ((i >> 4) & 15);
        const y = s.y * 16 + (i >> 8);
        if (x < min.x) min.x = x;
        if (y < min.y) min.y = y;
        if (z < min.z) min.z = z;
        if (x > max.x) max.x = x;
        if (y > max.y) max.y = y;
        if (z > max.z) max.z = z;
      }
    }
    return { min, max };
  }

  /** The `VolumeBlob` struct: one section per non-empty 16x16x16 cube, palettes local to each section. */
  encode(): VolumeBlob {
    const sorted = [...this.sections.values()].sort((a, b) => a.x - b.x || a.y - b.y || a.z - b.z);
    const local = new Map<number, number>();
    const sections: VolumeSection[] = sorted.map((s) => {
      local.clear();
      const palette = [''];
      const indices = new Uint16Array(SECTION_BLOCKS);
      for (let i = 0; i < SECTION_BLOCKS; i++) {
        const id = s.ids[i]!;
        if (id === 0) continue;
        let li = local.get(id);
        if (li === undefined) {
          li = palette.length;
          palette.push(this.states[id]!);
          local.set(id, li);
        }
        indices[i] = li;
      }
      return { x: s.x, y: s.y, z: s.z, palette, indices };
    });
    return { sections };
  }
}
