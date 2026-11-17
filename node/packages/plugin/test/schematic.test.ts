import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';
import {
  defineExtension,
  parseSchematic,
  readNbt,
  rotateBlockState,
  schematic,
  type BotBridge,
  type SchematicApi,
} from '../src/index.ts';
import { ALICE, FakeBot, fixture, thrown, until, type Env } from './helpers.ts';

// A minimal binary NBT writer, enough to build Sponge schematics in tests.
type Tag =
  | { t: 'byte' | 'short' | 'int'; v: number }
  | { t: 'string'; v: string }
  | { t: 'byteArray'; v: Uint8Array }
  | { t: 'intArray'; v: readonly number[] }
  | { t: 'list'; of: number; v: readonly Tag[] }
  | { t: 'compound'; v: Record<string, Tag> };

const TYPE = { byte: 1, short: 2, int: 3, byteArray: 7, string: 8, list: 9, compound: 10, intArray: 11 } as const;

function str(s: string): Buffer {
  const b = Buffer.from(s, 'utf8');
  const n = Buffer.alloc(2);
  n.writeUInt16BE(b.length);
  return Buffer.concat([n, b]);
}

function i32(v: number): Buffer {
  const b = Buffer.alloc(4);
  b.writeInt32BE(v);
  return b;
}

function payload(tag: Tag): Buffer {
  switch (tag.t) {
    case 'byte':
      return Buffer.of(tag.v & 0xff);
    case 'short': {
      const b = Buffer.alloc(2);
      b.writeInt16BE(tag.v);
      return b;
    }
    case 'int':
      return i32(tag.v);
    case 'string':
      return str(tag.v);
    case 'byteArray':
      return Buffer.concat([i32(tag.v.length), Buffer.from(tag.v)]);
    case 'intArray':
      return Buffer.concat([i32(tag.v.length), ...tag.v.map(i32)]);
    case 'list':
      return Buffer.concat([Buffer.of(tag.of), i32(tag.v.length), ...tag.v.map(payload)]);
    case 'compound':
      return Buffer.concat([...Object.entries(tag.v).map(([k, v]) => Buffer.concat([Buffer.of(TYPE[v.t]), str(k), payload(v)])), Buffer.of(0)]);
  }
}

const nbtFile = (name: string, root: Record<string, Tag>): Buffer => Buffer.concat([Buffer.of(10), str(name), payload({ t: 'compound', v: root })]);

function varints(values: readonly number[]): Uint8Array {
  const out: number[] = [];
  for (let v of values) {
    while (v >= 0x80) {
      out.push((v & 0x7f) | 0x80);
      v >>>= 7;
    }
    out.push(v);
  }
  return Uint8Array.from(out);
}

interface Spec {
  version?: 2 | 3;
  size: [number, number, number];
  palette: readonly string[];
  blocks: readonly number[];
  name?: string | undefined;
  offset?: [number, number, number];
  gzip?: boolean;
}

/** A Sponge schematic file: version 2 (flat) or 3 (nested under `Schematic.Blocks`). */
function schem(s: Spec): Buffer {
  const palette: Record<string, Tag> = Object.fromEntries(s.palette.map((p, i) => [p, { t: 'int', v: i }]));
  const common: Record<string, Tag> = {
    Version: { t: 'int', v: s.version ?? 2 },
    DataVersion: { t: 'int', v: 3700 },
    Width: { t: 'short', v: s.size[0] },
    Height: { t: 'short', v: s.size[1] },
    Length: { t: 'short', v: s.size[2] },
    Offset: { t: 'intArray', v: s.offset ?? [0, 0, 0] },
  };
  if (s.name !== undefined) common.Metadata = { t: 'compound', v: { Name: { t: 'string', v: s.name } } };
  const data: Tag = { t: 'byteArray', v: varints(s.blocks) };
  const raw =
    (s.version ?? 2) === 3
      ? nbtFile('', { Schematic: { t: 'compound', v: { ...common, Blocks: { t: 'compound', v: { Palette: { t: 'compound', v: palette }, Data: data, BlockEntities: { t: 'list', of: 10, v: [] } } } } } })
      : nbtFile('Schematic', { ...common, PaletteMax: { t: 'int', v: s.palette.length }, Palette: { t: 'compound', v: palette }, BlockData: data });
  return s.gzip === false ? raw : gzipSync(raw);
}

const STAIRS = 'minecraft:oak_stairs[facing=north,half=bottom]';
const LOG = 'minecraft:oak_log[axis=x]';
/**
 * A 3x2x2 house: a floor of stone at y 0, stairs at local 0,1,0 and a log at 2,1,1. Index order is
 * `(y * length + z) * width + x`.
 */
const HOUSE: Spec = {
  size: [3, 2, 2],
  palette: ['minecraft:air', 'minecraft:stone', STAIRS, LOG],
  blocks: [1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 3],
  name: 'House',
  offset: [-1, 0, -2],
};

describe('schematic parsing', () => {
  it('reads Sponge v2 and v3, gzipped or not, with materials and local lookups', () => {
    for (const s of [parseSchematic(schem(HOUSE)), parseSchematic(schem({ ...HOUSE, version: 3 })), parseSchematic(schem({ ...HOUSE, gzip: false }))]) {
      expect(s.size).toEqual({ x: 3, y: 2, z: 2 });
      expect(s.offset).toEqual({ x: -1, y: 0, z: -2 });
      expect(s.name).toBe('House');
      expect(s.dataVersion).toBe(3700);
      expect(s.blockCount).toBe(8);
      expect(s.materials()).toEqual({ 'minecraft:stone': 6, 'minecraft:oak_log': 1, 'minecraft:oak_stairs': 1 });
      expect(Object.keys(s.materials())[0]).toBe('minecraft:stone');
      expect(s.blockAt([0, 1, 0])).toBe(STAIRS);
      expect(s.blockAt({ x: 2, y: 1, z: 1 })).toBe(LOG);
      expect(s.blockAt([1, 1, 0])).toBe('minecraft:air');
      expect(s.blockAt([3, 0, 0])).toBeUndefined();
    }
    expect(parseSchematic(schem({ ...HOUSE, version: 3 })).version).toBe(3);
  });

  it('decodes multi-byte varints and adds missing namespaces', () => {
    const palette = Array.from({ length: 300 }, (_, i) => (i === 0 ? 'air' : `minecraft:block_${i}`));
    const s = parseSchematic(schem({ size: [2, 1, 1], palette, blocks: [299, 128] }));
    expect(s.blockAt([0, 0, 0])).toBe('minecraft:block_299');
    expect(s.blockAt([1, 0, 0])).toBe('minecraft:block_128');
    expect(s.palette[0]).toBe('minecraft:air');
  });

  it('rejects malformed files', () => {
    expect(thrown(() => parseSchematic(schem({ ...HOUSE, blocks: HOUSE.blocks.slice(0, 5) }))).message).toMatch(/ends after 5 of 12/);
    expect(thrown(() => parseSchematic(schem({ ...HOUSE, blocks: [...HOUSE.blocks.slice(0, 11), 9] }))).message).toMatch(/palette index 9/);
    expect(thrown(() => parseSchematic(schem(HOUSE).subarray(0, 20))).code).toBe('invalid_message');
    expect(thrown(() => parseSchematic(nbtFile('', { Width: { t: 'short', v: 1 } }))).message).toMatch(/Height/);
    expect(thrown(() => parseSchematic(schem({ ...HOUSE, size: [0, 1, 1], blocks: [] }))).message).toMatch(/empty size/);
    expect(thrown(() => parseSchematic(Buffer.of(1, 0, 0))).message).toMatch(/root must be a compound/);
  });

  it('reads binary NBT with readNbt', () => {
    const { name, value } = readNbt(nbtFile('root', { a: { t: 'byte', v: -3 }, s: { t: 'string', v: 'hi' }, l: { t: 'list', of: 3, v: [{ t: 'int', v: 7 }] } }));
    expect(name).toBe('root');
    expect(value).toEqual({ a: -3, s: 'hi', l: [7] });
  });
});

describe('schematic rotation', () => {
  it('turns block states', () => {
    expect(rotateBlockState(STAIRS, 1)).toBe('minecraft:oak_stairs[facing=east,half=bottom]');
    expect(rotateBlockState(STAIRS, -1)).toBe('minecraft:oak_stairs[facing=west,half=bottom]');
    expect(rotateBlockState(STAIRS, 4)).toBe(STAIRS);
    expect(rotateBlockState('minecraft:oak_log[axis=x]', 1)).toBe('minecraft:oak_log[axis=z]');
    expect(rotateBlockState('minecraft:oak_log[axis=y]', 1)).toBe('minecraft:oak_log[axis=y]');
    expect(rotateBlockState('minecraft:oak_sign[rotation=14]', 1)).toBe('minecraft:oak_sign[rotation=2]');
    expect(rotateBlockState('minecraft:oak_fence[east=false,north=true,south=false,west=false]', 1)).toBe('minecraft:oak_fence[east=true,north=false,south=false,west=false]');
    expect(rotateBlockState('minecraft:rail[shape=north_south]', 1)).toBe('minecraft:rail[shape=east_west]');
    expect(rotateBlockState('minecraft:rail[shape=south_east]', 1)).toBe('minecraft:rail[shape=south_west]');
    expect(rotateBlockState('minecraft:rail[shape=ascending_north]', 2)).toBe('minecraft:rail[shape=ascending_south]');
    expect(rotateBlockState('minecraft:oak_stairs[facing=up,shape=inner_left]', 1)).toBe('minecraft:oak_stairs[facing=up,shape=inner_left]');
    expect(rotateBlockState('stone', 1)).toBe('minecraft:stone');
  });

  it('turns positions clockwise around the minimum corner', () => {
    const s = parseSchematic(schem(HOUSE));
    const r1 = s.rotated(1);
    expect(r1.size).toEqual({ x: 2, y: 2, z: 3 });
    expect(r1.blockAt([1, 1, 0])).toBe('minecraft:oak_stairs[facing=east,half=bottom]');
    expect(r1.blockAt([0, 1, 2])).toBe('minecraft:oak_log[axis=z]');
    const r2 = s.rotated(2);
    expect(r2.size).toEqual({ x: 3, y: 2, z: 2 });
    expect(r2.blockAt([2, 1, 1])).toBe('minecraft:oak_stairs[facing=south,half=bottom]');
    expect(r2.blockAt([0, 1, 0])).toBe(LOG);
    expect(s.rotated(-1).blockAt([0, 1, 2])).toBe('minecraft:oak_stairs[facing=west,half=bottom]');
    expect(s.rotated(1).rotated(3).blocks).toEqual(s.blocks);
    expect(r1.materials()).toEqual(s.materials());
    expect(thrown(() => s.rotated(0.5)).code).toBe('invalid_message');
  });
});

let e: Env;
const { attached } = fixture((f) => (e = f.e));

const EXT = 'bridge:schematic';
const shapeKey = (id: string): string => `${ALICE}/${EXT}/schematic/${id}`;

class BuilderBot extends FakeBot {
  world = new Map<string, string>();
  blockAt(p: { x: number; y: number; z: number }): { name: string } | null {
    const name = this.world.get(`${p.x},${p.y},${p.z}`);
    return name === undefined ? null : { name };
  }
}

async function start(bot: FakeBot = new FakeBot('Alice'), ...extra: Parameters<BotBridge['use']>): Promise<{ b: BotBridge; api: SchematicApi }> {
  const b = await attached(bot);
  await b.use(schematic({ intervalMs: 0 }), ...extra);
  return { b, api: b.get<SchematicApi>(EXT)! };
}

describe('bridge:schematic', () => {
  it('shows ghost blocks at the origin and moves them with moveTo and the gizmo', async () => {
    const { api } = await start();
    const h = await api.load(schem(HOUSE), { origin: [10, 64, -5] });
    expect(h.name).toBe('House');
    expect(h.bounds).toEqual({ min: { x: 10, y: 64, z: -5 }, max: { x: 12, y: 65, z: -4 } });
    const ghost = await until(() => e.mod.ghost(shapeKey('House')), 5000, 'ghost');
    expect(ghost.count).toBe(8);
    expect(ghost.get({ x: 0, y: 1, z: 0 })).toBe(STAIRS);
    expect(ghost.data).toMatchObject({ origin: { x: 10, y: 64, z: -5 }, diffAgainstWorld: true, hideMatching: true });
    const frame = await until(() => e.mod.object(shapeKey('House~frame')), 5000, 'frame');
    expect(frame.data).toMatchObject({ min: { x: 10, y: 64, z: -5 }, size: { x: 3, y: 2, z: 2 } });
    expect(frame.traits?.editable).toEqual([{ field: 'min', editor: 'gizmo', label: 'Origin' }]);
    const blob = ghost.data.blob;

    const moves: unknown[] = [];
    h.on('moved', (o) => moves.push(o));
    h.moveTo({ x: 20.7, y: 70, z: 3.2 });
    await until(() => (e.mod.ghost(shapeKey('House'))?.data.origin as { x: number } | undefined)?.x === 20, 5000, 'moved');
    expect(e.mod.ghost(shapeKey('House'))!.data.origin).toEqual({ x: 20, y: 70, z: 3 });
    // Moving sends no blocks again.
    expect(e.mod.ghost(shapeKey('House'))!.data.blob).toEqual(blob);
    expect(h.blockAt([20, 71, 3])).toBe(STAIRS);
    expect(h.blockAt([10, 65, -5])).toBeUndefined();

    expect(await e.mod.proposeEdit<'core:box'>(shapeKey('House~frame'), { min: { x: -3.5, y: 60, z: 1 } })).toEqual({ accepted: true, reason: undefined });
    await until(() => (e.mod.ghost(shapeKey('House'))?.data.origin as { x: number } | undefined)?.x === -4, 5000, 'gizmo move');
    expect(h.origin).toEqual({ x: -4, y: 60, z: 1 });
    await until(() => (e.mod.object(shapeKey('House~frame'))?.data as { min: { x: number } }).min.x === -4, 5000, 'frame snapped');
    expect(moves).toEqual([{ x: 20, y: 70, z: 3 }, { x: -4, y: 60, z: 1 }]);
    expect((await e.mod.proposeEdit<'core:box'>(shapeKey('House~frame'), { size: { x: 9, y: 9, z: 9 } }, { unchecked: true })).accepted).toBe(false);

    h.rotate(1);
    const hex = (b: Uint8Array): string => Buffer.from(b).toString('hex');
    await until(() => {
      const g = e.mod.ghost(shapeKey('House'));
      return g !== undefined && hex(g.data.blob) !== hex(blob);
    }, 5000, 'rotated');
    const turned = e.mod.ghost(shapeKey('House'))!;
    expect(turned.get({ x: 1, y: 1, z: 0 })).toBe('minecraft:oak_stairs[facing=east,half=bottom]');
    expect(h.rotation).toBe(1);
    await until(() => (e.mod.object(shapeKey('House~frame'))?.data as { size: { z: number } }).size.z === 3, 5000, 'frame turned');

    h.remove();
    expect(h.alive).toBe(false);
    await until(() => e.mod.object(shapeKey('House')) === undefined && e.mod.object(shapeKey('House~frame')) === undefined, 5000, 'removed');
    expect(api.placed).toEqual([]);
  });

  it('offers turn and remove on the frame, which the handle events follow', async () => {
    const { api } = await start();
    const h = api.place(parseSchematic(schem(HOUSE)), { origin: [0, 64, 0], name: 'hut' });
    const frameKey = shapeKey('hut~frame');
    await until(() => e.mod.object(frameKey), 5000, 'frame');
    await until(() => e.mod.objectActions(frameKey).length === 3, 5000, 'actions');
    expect(e.mod.objectActions(frameKey).map((a) => [a.id, a.data.label])).toEqual([
      ['hut~rotate-cw', 'Turn clockwise'],
      ['hut~rotate-ccw', 'Turn counter-clockwise'],
      ['hut~remove', 'Remove'],
    ]);
    const turns: number[] = [];
    let removed = 0;
    h.on('rotated', (r) => turns.push(r));
    h.on('removed', () => removed++);

    expect(await e.mod.runObjectAction(frameKey, 'hut~rotate-cw')).toMatchObject({ ok: true });
    expect(h.rotation).toBe(1);
    await e.mod.runObjectAction(frameKey, 'hut~rotate-ccw');
    await e.mod.runObjectAction(frameKey, 'hut~rotate-ccw');
    expect(turns).toEqual([1, 0, 3]);
    await until(() => (e.mod.object(frameKey)?.data as { size: { z: number } }).size.z === 3, 5000, 'frame turned');

    // Another placement's frame offers only its own actions.
    api.place(parseSchematic(schem(HOUSE)), { origin: [9, 64, 0], name: 'shed', actions: ['remove'] });
    await until(() => e.mod.object(shapeKey('shed~frame')), 5000, 'shed frame');
    expect(e.mod.objectActions(shapeKey('shed~frame')).map((a) => a.id)).toEqual(['shed~remove']);
    await expect(e.mod.runObjectAction(shapeKey('shed~frame'), 'hut~remove')).rejects.toThrow(/offers no action/);

    await e.mod.runObjectAction(frameKey, 'hut~remove');
    expect(h.alive).toBe(false);
    expect(removed).toBe(1);
    await until(() => e.mod.object(frameKey) === undefined && !e.mod.actions().some((a) => a.id.startsWith('hut~')), 5000, 'gone');
    expect(api.placed.map((p) => p.name)).toEqual(['shed']);
  });

  it('draws a frame for actions alone, and none without either', async () => {
    const { api } = await start();
    api.place(parseSchematic(schem(HOUSE)), { origin: [0, 64, 0], name: 'a', editable: false, actions: ['rotate-cw'] });
    api.place(parseSchematic(schem(HOUSE)), { origin: [0, 64, 0], name: 'b', editable: false });
    const frame = await until(() => e.mod.object(shapeKey('a~frame')), 5000, 'frame');
    expect(frame.traits?.editable).toBeUndefined();
    await until(() => e.mod.objectActions(shapeKey('a~frame')).length === 1, 5000, 'action');
    await until(() => e.mod.ghost(shapeKey('b')), 5000, 'ghost');
    expect(e.mod.object(shapeKey('b~frame'))).toBeUndefined();
    expect(e.mod.actions().filter((a) => a.id.startsWith('b~'))).toEqual([]);
    expect(thrown(() => api.place(parseSchematic(schem(HOUSE)), { origin: [0, 0, 0], name: 'c', actions: ['flip' as never] })).message).toMatch(/unknown schematic action flip/);
    expect(e.mod.ghost(shapeKey('c'))).toBeUndefined();
  });

  it('lists materials, remaining blocks and progress, with a HUD bar', async () => {
    const bot = new BuilderBot('Alice');
    const { api } = await start(bot);
    const h = api.place(parseSchematic(schem(HOUSE)), { origin: [0, 64, 0], name: 'hut', rotation: 0 });
    expect(h.materials()).toEqual({ 'minecraft:stone': 6, 'minecraft:oak_log': 1, 'minecraft:oak_stairs': 1 });
    const world = new Map<string, string>([
      ['0,64,0', 'stone'],
      ['1,64,0', 'minecraft:stone'],
      ['2,64,0', 'stone'],
      ['0,64,1', 'stone'],
      // Wrong block where the stairs go; the log's state is not compared.
      ['0,65,0', 'stone'],
      ['2,65,1', 'minecraft:oak_log[axis=y]'],
    ]);
    const lookup = (p: { x: number; y: number; z: number }): string | undefined => world.get(`${p.x},${p.y},${p.z}`);
    expect(h.remaining(lookup)).toEqual({ 'minecraft:stone': 2, 'minecraft:oak_stairs': 1 });
    expect(h.progress(lookup)).toEqual({ placed: 5, total: 8 });
    const bar = await until(() => e.mod.hudPanels().find((p) => p.key === `${ALICE}/${EXT}/~hud/schematics`)?.widgets.find((w) => w.kind.endsWith('bar')), 5000, 'hud bar');
    expect(bar.data).toMatchObject({ value: 5, max: 8, label: 'hut: 5/8' });

    // Without a lookup the bot's world is read.
    for (const [k, v] of world) bot.world.set(k, v);
    expect(h.progress()).toEqual({ placed: 5, total: 8 });
    bot.world.set('1,64,1', 'stone');
    bot.world.set('2,64,1', 'stone');
    bot.world.set('0,65,0', 'oak_stairs');
    expect(h.progress()).toEqual({ placed: 8, total: 8 });
    expect(h.remaining()).toEqual({});
    expect(api.get('hut')).toBe(h);

    // Loading the same name replaces the placement.
    const again = api.place(parseSchematic(schem(HOUSE)), { origin: [0, 64, 0], name: 'hut' });
    expect(h.alive).toBe(false);
    expect(api.placed).toEqual([again]);
    api.clear();
    await until(() => e.mod.hudPanels().every((p) => p.key !== `${ALICE}/${EXT}/~hud/schematics`), 5000, 'panel removed');
  });

  it('reports through bridge:tasks when it is loaded', async () => {
    const calls: string[] = [];
    const tasks = defineExtension({
      id: 'bridge:tasks',
      version: '1.0.0',
      setup: () => ({
        task: (name: string) => {
          const t = {
            progress: (c: number, total?: number) => (calls.push(`${name} ${c}/${String(total)}`), t),
            status: (s: string) => (calls.push(`${name}: ${s}`), t),
            done: (s?: string) => void calls.push(`${name} done ${String(s)}`),
          };
          return t;
        },
      }),
    });
    const bot = new BuilderBot('Alice');
    const { api } = await start(bot, tasks());
    const h = api.place(parseSchematic(schem(HOUSE)), { origin: [0, 0, 0], name: 'hut', editable: false });
    h.progress(() => undefined);
    h.progress(() => undefined);
    h.progress(() => 'stone');
    h.progress((p) => h.blockAt(p));
    expect(calls).toEqual(['Build hut 0/8', 'Build hut: 8 blocks left', 'Build hut 6/8', 'Build hut: 2 blocks left', 'Build hut 8/8', 'Build hut done Built hut']);
    expect(e.mod.object(shapeKey('hut~frame'))).toBeUndefined();
  });

  it('loads a file path, defaults the origin to the bot and validates input', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'schem-'));
    try {
      const file = join(dir, 'tower.schem');
      await writeFile(file, schem({ ...HOUSE, name: undefined }));
      const bot = new FakeBot('Alice');
      bot.entity.position = { x: 5.5, y: 64, z: -0.5 };
      const { api } = await start(bot);
      const h = await api.load(file);
      expect(h.name).toBe('tower');
      expect(h.origin).toEqual({ x: 5, y: 64, z: -1 });
      await until(() => e.mod.ghost(shapeKey('tower')), 5000, 'ghost from file');
      expect(() => h.progress()).toThrow(/blockAt/);
      expect(thrown(() => api.place({} as never)).code).toBe('invalid_message');
      expect(thrown(() => api.place(h.schematic, { name: 'a/b' })).message).toMatch(/without \//);
      expect(thrown(() => h.rotate(1.5)).code).toBe('invalid_message');
      await expect(api.load(join(dir, 'missing.schem'))).rejects.toThrow();
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });
});
