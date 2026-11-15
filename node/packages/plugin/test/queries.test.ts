import { describe, expect, it } from 'vitest';
import { LOCAL_DEFAULT_PERMISSIONS, REMOTE_DEFAULT_PERMISSIONS } from '@mineflayer-bridge/protocol';
import { defineExtension } from '../src/index.ts';
import { FakeBot, fixture, load, OVERWORLD, rejection, until, type Env } from './helpers.ts';

let e: Env;
let grants: readonly string[] = LOCAL_DEFAULT_PERMISSIONS;
const { attached, hub } = fixture((f) => {
  e = f.e;
  grants = LOCAL_DEFAULT_PERMISSIONS;
}, { permissions: () => [...grants] });

const WORLD = {
  player: { x: 0, y: 64, z: 0 },
  entities: [
    { id: 7, type: 'minecraft:zombie', pos: { x: 5, y: 64, z: 0 } },
    { id: 3, type: 'minecraft:cow', pos: { x: 2, y: 64, z: 0 }, name: 'Bessie' },
    { id: 9, type: 'minecraft:player', pos: { x: -1, y: 64, z: 0 }, name: 'Bob', player: true },
    { id: 11, type: 'minecraft:zombie', pos: { x: 200, y: 64, z: 0 } },
  ],
  blocks: [
    { x: 1, y: 60, z: 1, name: 'minecraft:iron_ore' },
    { x: 1, y: 61, z: 1, name: 'minecraft:stone' },
    { x: 2, y: 60, z: 1, name: 'minecraft:iron_ore' },
    { x: 40, y: 60, z: 1, name: 'minecraft:iron_ore' },
  ],
  loaded: (x: number) => x < 32,
};

describe('client queries', () => {
  it('returns loaded entities nearest first, filtered and capped', async () => {
    const b = await attached();
    e.mod.setLoadedWorld(WORLD);
    const all = await b.query.entities();
    expect(all.map((x) => x.id)).toEqual([9, 3, 7]);
    expect(all[0]).toEqual({ id: 9, type: 'minecraft:player', pos: { x: -1, y: 64, z: 0 }, name: 'Bob', player: true });
    // The radius is capped at 128 blocks, so the zombie 200 blocks away stays out.
    expect((await b.query.entities({ types: ['minecraft:zombie'], radius: 300 })).map((x) => x.id)).toEqual([7]);
    expect((await b.query.entities({ center: [200, 64, 0], radius: 4, limit: 1 })).map((x) => x.id)).toEqual([11]);
    expect((await rejection(b.query.entities({ radius: -1 }))).code).toBe('invalid_message');
  });

  it('returns loaded blocks in a box with a palette, and says when the answer is partial', async () => {
    const b = await attached();
    e.mod.setLoadedWorld(WORLD);
    const ores = await b.query.blocks([0, 55, 0], [10, 65, 10], { blocks: ['minecraft:iron_ore'] });
    expect(ores).toEqual({ blocks: [{ x: 1, y: 60, z: 1, name: 'minecraft:iron_ore' }, { x: 2, y: 60, z: 1, name: 'minecraft:iron_ore' }], complete: true });
    const both = await b.query.blocks([10, 65, 10], [0, 55, 0]);
    expect(both.blocks.map((x) => x.name)).toEqual(['minecraft:iron_ore', 'minecraft:stone', 'minecraft:iron_ore']);
    expect((await b.query.blocks([0, 60, 0], [40, 60, 2])).complete).toBe(false);
    expect((await b.query.blocks([0, 55, 0], [10, 65, 10], { limit: 1 })).complete).toBe(false);
    expect((await rejection(b.query.blocks([0, 0, 0], [100, 100, 100]))).code).toBe('invalid_message');
  });

  it('refuses while detached', async () => {
    const b = await attached();
    e.mod.setLoadedWorld(WORLD);
    e.mod.setAttached('alice@play.example.net:25565', false);
    await until(() => !b.state.attached);
    expect((await rejection(b.query.entities())).code).toBe('not_attached');
  });

  it('refuses a device without the client.query permission, which remote devices lack by default', async () => {
    expect(REMOTE_DEFAULT_PERMISSIONS).not.toContain('client.query');
    grants = REMOTE_DEFAULT_PERMISSIONS;
    const b = await attached();
    e.mod.setLoadedWorld(WORLD);
    expect((await rejection(b.query.entities())).code).toBe('permission_denied');
    expect((await rejection(b.query.blocks([0, 0, 0], [1, 1, 1]))).code).toBe('permission_denied');
  });

  it('gives extensions their own query API', async () => {
    let api: { entities(): Promise<unknown[]> } | undefined;
    const ext = defineExtension({ id: 'acme:radar', version: '1.0.0', setup: (ctx) => (api = ctx.query) });
    const b = load(hub().plugin({ extensions: [ext] }), new FakeBot('Alice'));
    e.mod.setPlayerContext(OVERWORLD);
    e.mod.setLoadedWorld(WORLD);
    await until(() => b.state.attached && b.state.worldMatched);
    expect(await api!.entities()).toHaveLength(3);
  });
});
