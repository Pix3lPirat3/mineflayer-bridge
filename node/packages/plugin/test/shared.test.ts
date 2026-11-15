import { describe, expect, it } from 'vitest';
import { BridgeError, type SharedObject } from '../src/index.ts';
import { FakeBot, fixture, load, OVERWORLD, until, type Env } from './helpers.ts';

let e: Env;
const { hub } = fixture((f) => (e = f.e));

const zone = (x: number) => ({ min: { x, y: 0, z: 0 }, size: { x: 4, y: 4, z: 4 } });

describe('shared objects', () => {
  it('creates, updates with expectRev, broadcasts to subscribers and rejects stale revisions with conflict', async () => {
    const h = hub();
    const a = load(h.plugin(), new FakeBot('Alice'));
    const b = load(h.plugin(), new FakeBot('Bob'));
    b.shared.subscribe({ kinds: ['core:box'] });
    const changes: SharedObject[] = [];
    b.shared.on('change', (o) => changes.push(o));

    const created = await a.shared.create('zone-1', 'core:box', zone(0));
    expect(created).toEqual({ key: 'shared/user:default/zone-1', rev: 1 });
    await until(() => b.shared.get(created.key));
    expect(b.shared.get(created.key)).toMatchObject({ kind: 'core:box', rev: 1, data: zone(0) });

    const updated = await a.shared.update(created.key, 'core:box', zone(5), 1);
    expect(updated.rev).toBe(2);
    await until(() => b.shared.get(created.key)?.rev === 2);
    expect((b.shared.get(created.key)?.data as { min: { x: number } }).min.x).toBe(5);

    const conflict = (await b.shared.update(created.key, 'core:box', zone(9), 1).catch((x: unknown) => x)) as BridgeError;
    expect(conflict).toBeInstanceOf(BridgeError);
    expect(conflict.code).toBe('conflict');
    expect(conflict.details).toEqual({ rev: 2 });

    const dup = (await a.shared.create('zone-1', 'core:box', zone(0)).catch((x: unknown) => x)) as BridgeError;
    expect(dup.code).toBe('conflict');

    const removed = new Promise<string>((resolve) => b.shared.on('remove', resolve));
    await expect(a.shared.remove(created.key, 1)).rejects.toMatchObject({ code: 'conflict' });
    await a.shared.remove(created.key, 2);
    expect(await removed).toBe(created.key);
    expect(b.shared.list()).toEqual([]);
    expect(changes.map((c) => c.rev)).toEqual([1, 2]);
  });

  it('rebases after a player edit: conflict, re-read, retry', async () => {
    const a = load(hub().plugin(), new FakeBot('Alice'));
    a.shared.subscribe();
    const { key } = await a.shared.create('home', 'core:waypoint', { pos: { x: 0, y: 64, z: 0 }, name: 'Home' });
    e.mod.putShared(key, 'core:waypoint', { pos: { x: 3, y: 64, z: 3 }, name: 'Home (moved)' });
    await until(() => a.shared.get(key)?.rev === 2);
    await expect(a.shared.update(key, 'core:waypoint', { pos: { x: 1, y: 64, z: 1 }, name: 'Home' }, 1)).rejects.toMatchObject({ code: 'conflict', details: { rev: 2 } });
    const current = a.shared.get(key)!;
    const r = await a.shared.update(key, 'core:waypoint', { pos: { x: 1, y: 64, z: 1 }, name: 'Home' }, current.rev);
    expect(r.rev).toBe(3);
  });

  it('filters by extension and sends world-scoped objects only to sessions in that world', async () => {
    const h = hub();
    const inWorld = load(h.plugin(), new FakeBot('Alice'));
    const nether = new FakeBot('Bob');
    nether.game.dimension = 'the_nether';
    const other = load(h.plugin(), nether);
    inWorld.shared.subscribe({ extensions: ['bridge:zones'] });
    other.shared.subscribe();
    await until(() => e.mod.session('bot:bob@play.example.net:25565')?.world && e.mod.session('bot:alice@play.example.net:25565')?.world);
    e.mod.putShared('shared/bridge:zones/z', 'core:box', zone(1), { world: OVERWORLD });
    e.mod.putShared('shared/other:ext/g', 'core:box', zone(2));
    await until(() => other.shared.get('shared/other:ext/g'));
    await until(() => inWorld.shared.get('shared/bridge:zones/z'));
    expect(inWorld.shared.get('shared/other:ext/g')).toBeUndefined();
    expect(other.shared.get('shared/bridge:zones/z')).toBeUndefined();
    expect(inWorld.shared.list({ prefix: 'shared/bridge:zones/' })).toHaveLength(1);
  });

  it('queues ops made before the session opens', async () => {
    const a = load(hub().plugin(), new FakeBot('Alice'));
    expect(a.state.connected).toBe(false);
    const r = await a.shared.create('shared/acme:x/early', 'core:box', zone(0));
    expect(r).toEqual({ key: 'shared/acme:x/early', rev: 1 });
  });
});
