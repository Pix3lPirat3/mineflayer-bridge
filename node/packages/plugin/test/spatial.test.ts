import { describe, expect, it } from 'vitest';
import { BridgeError, defineExtension, type BotBridge, type BridgeHub, type ExtensionContext, type SharedZone, type ZoneHandle } from '../src/index.ts';
import { ALICE, FakeBot, fixture, load, OVERWORLD, rejection, sleep, until, within, type Env } from './helpers.ts';

const PRIVATE = `${ALICE}/user:default`;

let e: Env;
let hubs: readonly BridgeHub[];
const { hub } = fixture((f) => ({ e, hubs } = f));

/** A bot whose session has reported its world, so world-scoped shared objects reach it. */
async function alice(): Promise<BotBridge> {
  const b = load(hub().plugin(), new FakeBot('Alice'));
  await until(() => e.mod.session(ALICE)?.world);
  return b;
}

const farm = { name: 'Farm', boxes: [[[0, 60, 0], [15, 70, 15]], [[4, 60, 4], [6, 70, 6], 'subtract']] as const, tags: ['farm'], color: '#ff0000' };

describe('shared spatial views', () => {
  it('creates definitions from friendly input and answers local queries', async () => {
    const a = await alice();
    const added: string[] = [];
    a.shared.zones.on('add', (z) => added.push(z.id));

    const ref = await a.shared.zones.create(farm, { id: 'farm' });
    expect(ref).toEqual({ id: 'farm', key: 'shared/bridge:zones/farm', rev: 1 });
    const stored = e.mod.zones();
    expect(stored).toHaveLength(1);
    expect(stored[0]).toMatchObject({ key: 'shared/bridge:zones/farm', owner: 'shared', shared: true, world: OVERWORLD });
    expect(stored[0]!.data).toMatchObject({ name: 'Farm', color: 0xffff0000, tags: ['farm'], boxes: [{ min: { x: 0, y: 60, z: 0 }, max: { x: 15, y: 70, z: 15 }, op: 'add' }, { op: 'subtract' }] });

    const z = await until(() => a.shared.zones.get('farm'));
    expect(a.shared.zones.get('shared/bridge:zones/farm')).toBe(z);
    expect(z).toMatchObject({ id: 'farm', key: ref.key, rev: 1, name: 'Farm', tags: ['farm'], world: OVERWORLD });
    expect(a.shared.zones.containing([1, 65, 1]).map((x) => x.id)).toEqual(['farm']);
    expect(a.shared.zones.containing({ x: 5.5, y: 65, z: 5.5 })).toEqual([]);
    expect(a.shared.zones.containing([40, 65, 40])).toEqual([]);
    expect(a.shared.zones.intersecting([[4, 60, 4], [6, 70, 6]])).toEqual([]);
    expect(a.shared.zones.intersecting([[-5, 0, -5], [0, 100, 0]])).toHaveLength(1);
    expect(a.shared.zones.byTag('farm')).toHaveLength(1);
    expect(a.shared.zones.byTag('mine')).toEqual([]);
    expect(z.bounds()).toEqual({ min: { x: 0, y: 60, z: 0 }, max: { x: 15, y: 70, z: 15 } });
    expect(added).toEqual(['farm']);

    await a.shared.points.create({ name: 'Home', pos: { x: 2.5, y: 64, z: 2.5 }, yaw: 90, tags: ['home'] }, { id: 'home' });
    await a.shared.points.create({ name: 'Mine', pos: [100, 12, 0], tags: ['mine'], icon: 'minecraft:iron_pickaxe' }, { id: 'mine', world: 'global' });
    await until(() => a.shared.points.list().length === 2);
    expect(a.shared.points.get('mine')?.world).toBeUndefined();
    expect(a.shared.points.nearest([0, 64, 0])?.id).toBe('home');
    expect(a.shared.points.nearest([0, 64, 0], { tag: 'mine' })?.id).toBe('mine');
    expect(a.shared.points.nearest([0, 64, 0], { tag: 'mine', maxDistance: 50 })).toBeUndefined();
    expect(a.shared.points.within([0, 64, 0], 200).map((p) => p.id)).toEqual(['home', 'mine']);
    expect(a.shared.points.intersecting([[0, 60, 0], [5, 70, 5]]).map((p) => p.id)).toEqual(['home']);

    const route = await a.shared.routes.create({ name: 'Patrol', nodes: [[0, 64, 0], { pos: [10, 64, 0], data: { wait: 20 } }, [10, 64, 10]], interpolation: 'catmull-rom', looped: true, width: 3 });
    expect(route.id).toMatch(/^[0-9a-f-]{36}$/);
    const r = await until(() => a.shared.routes.get(route.id));
    expect(r.data).toMatchObject({ interpolation: 'catmull_rom', looped: true, width: 3 });
    expect(r.data.nodes[1]).toEqual({ pos: { x: 10, y: 64, z: 0 }, data: { wait: 20 } });
    expect(r.points({ segments: 2 })).toHaveLength(7);
    expect(a.shared.routes.nearest([5, 64, -1])?.id).toBe(route.id);
    expect(a.shared.routes.intersecting([[3, 60, -3], [7, 70, 3]])).toHaveLength(1);

    const b = load(hubs[0]!.plugin(), new FakeBot('Bob'));
    await until(() => e.mod.session('bot:bob@play.example.net:25565')?.world);
    await until(() => b.shared.zones.list().length === 1 && b.shared.points.list().length === 2 && b.shared.routes.list().length === 1);
  });

  it('updates with the view revision as expectRev, clears fields with null and emits change and remove', async () => {
    const a = await alice();
    const changes: [string | undefined, string | undefined][] = [];
    const removed: string[] = [];
    a.shared.points.on('change', (p, prev) => changes.push([prev.name, p.name]));
    a.shared.points.on('remove', (p) => removed.push(p.name));
    await a.shared.points.create({ name: 'Home', pos: [0, 64, 0], yaw: 45 }, { id: 'home' });
    await until(() => a.shared.points.get('home'));

    const r = await a.shared.points.update('home', { name: 'Home 2', yaw: null, pos: [1, 64, 1] });
    expect(r).toEqual({ id: 'home', key: 'shared/bridge:zones/home', rev: 2 });
    const p = await until(() => (a.shared.points.get('home')?.rev === 2 ? a.shared.points.get('home') : undefined));
    expect(p.data.yaw).toBeUndefined();
    expect(p.pos).toEqual({ x: 1, y: 64, z: 1 });
    expect(changes).toEqual([['Home', 'Home 2']]);
    expect(e.mod.points()[0]!.data).toMatchObject({ name: 'Home 2', pos: { x: 1, y: 64, z: 1 } });

    await a.shared.points.remove('home');
    await until(() => removed.length === 1);
    expect(removed).toEqual(['Home 2']);
    expect(e.mod.points()).toEqual([]);
    expect((await rejection(a.shared.points.update('home', { name: 'x' }))).code).toBe('invalid_message');
    expect((await rejection(a.shared.points.remove('home'))).code).toBe('invalid_message');
  });

  it('rejects stale writes with conflict and rebases on a retry after a player edit', async () => {
    const a = await alice();
    const { key } = await a.shared.zones.create(farm, { id: 'farm' });
    await until(() => a.shared.zones.get('farm'));
    expect(e.mod.playerEditShared(key, { name: 'Renamed by player' }, { expectRev: 1 })).toBe(2);
    expect(() => e.mod.playerEditShared(key, { name: 'stale' }, { expectRev: 1 })).toThrow(/revision/);
    expect(() => e.mod.playerCreateShared('core:zone', { name: 'dup', boxes: [] }, { id: 'farm' })).toThrow(BridgeError);

    const stale = await rejection(a.shared.zones.update('farm', { color: '#00ff00' }, { expectRev: 1 }));
    expect(stale).toMatchObject({ code: 'conflict', details: { rev: 2 } });

    const r = await a.shared.zones.update('farm', { color: '#00ff00' }, { expectRev: 1, retry: true });
    expect(r.rev).toBe(3);
    expect(e.mod.zones()[0]!.data).toMatchObject({ name: 'Renamed by player', color: 0xff00ff00 });

    const seen: number[] = [];
    e.mod.playerEditShared(key, { tags: ['player'] });
    await until(() => a.shared.zones.get('farm')?.rev === 4);
    const r2 = await a.shared.zones.update('farm', (cur: SharedZone) => {
      seen.push(cur.rev);
      return { tags: [...cur.tags, 'bot'] };
    }, { expectRev: 3, retry: 2 });
    expect(r2.rev).toBe(5);
    expect(seen).toEqual([4, 4]);
    expect(e.mod.zones()[0]!.data.tags).toEqual(['player', 'bot']);

    let calls = 0;
    const exhausted = await rejection(
      a.shared.zones.update('farm', () => {
        calls++;
        // The player edits again before every attempt reaches the mod.
        e.mod.playerEditShared(key, { fill: calls / 10 });
        return { name: `try ${calls}` };
      }, { retry: 1 }),
    );
    expect(exhausted.code).toBe('conflict');
    expect(calls).toBe(2);

    e.mod.playerEditShared(key, { name: 'again' });
    await a.shared.zones.remove('farm', { expectRev: 1, retry: true });
    expect(e.mod.zones()).toEqual([]);
    expect((await rejection(a.shared.zones.create(farm, { id: 'a/b' }))).code).toBe('invalid_message');
  });

  it('validates definitions before sending anything', async () => {
    const a = await alice();
    const bad: [Promise<unknown>, RegExp][] = [
      [a.shared.zones.create({ name: 'Both', boxes: [[[0, 0, 0], [1, 1, 1]]], polygon: [[0, 0, 0], [1, 0, 0], [0, 0, 1]] }), /not both/],
      [a.shared.zones.create({ name: 'None' }), /needs boxes or a polygon/],
      [a.shared.zones.create({ name: 'Two', polygon: [[0, 0, 0], [1, 0, 0]] }), /three vertices/],
      [a.shared.zones.create({ name: 'Op', boxes: [[[0, 0, 0], [1, 1, 1], 'xor' as 'add']] }), /op must be/],
      [a.shared.zones.create({ name: 'Fill', boxes: [[[0, 0, 0], [1, 1, 1]]], fill: 2 }), /fill/],
      [a.shared.zones.create({ name: '', boxes: [[[0, 0, 0], [1, 1, 1]]] }), /name/],
      [a.shared.points.create({ name: 'NoPos' } as never), /needs pos/],
      [a.shared.points.create({ name: 'Extra', pos: [0, 0, 0], extra: 1 } as never), /no field extra/],
      [a.shared.routes.create({ name: 'Mode', nodes: [[0, 0, 0]], interpolation: 'spline' as 'linear' }), /interpolation/],
      [a.shared.routes.create({ name: 'Colour', nodes: [[0, 0, 0]], color: 'red' }), /colour/],
    ];
    for (const [p, msg] of bad) {
      const err = await rejection(p);
      expect(err.code).toBe('invalid_message');
      expect(err.message).toMatch(msg);
    }
    expect(e.mod.objects('shared/')).toEqual([]);
  });

  it('subscribes the views for kinds alongside an existing kind subscription', async () => {
    const a = await alice();
    a.shared.subscribe({ kinds: ['core:box'] });
    e.mod.putShared('shared/acme:x/b', 'core:box', { min: { x: 0, y: 0, z: 0 }, size: { x: 1, y: 1, z: 1 } });
    e.mod.playerCreateShared('core:point', { name: 'Spawn', pos: { x: 0, y: 64, z: 0 } }, { id: 'spawn' });
    expect(a.shared.points.list()).toEqual([]);
    await until(() => a.shared.points.get('spawn'));
    await until(() => a.shared.get('shared/acme:x/b'));
    e.mod.playerCreateShared('core:point', { name: 'Second', pos: { x: 0, y: 64, z: 0 } }, { id: 'x' });
    e.mod.putShared('shared/acme:x/p', 'core:point', { name: 'Not first-party', pos: { x: 0, y: 0, z: 0 } });
    await until(() => a.shared.get('shared/acme:x/p'));
    expect(a.shared.points.list().map((p) => p.id).sort()).toEqual(['spawn', 'x']);
  });
});

describe('private definitions', () => {
  it('declares zones, points and routes on layers as handles with geometry', async () => {
    const a = await alice();
    const layer = a.layer('plan');
    const z = layer.zone('quarry', { name: 'Quarry', boxes: [{ min: [0, 0, 0], max: [9, 9, 9] }], showLabel: false });
    const p = layer.point('chest', { name: 'Chest', pos: [3, 64, 3], editable: false });
    const r = layer.route('belt', { name: 'Belt', nodes: [[0, 64, 0], [5, 64, 0]], interpolation: 'bresenham' });
    expect(z.key).toBe('user:default/plan/quarry');
    expect(z.contains([5, 5, 5])).toBe(true);
    expect(p.distanceTo([3, 64, 7])).toBe(4);
    expect(r.points()).toHaveLength(6);
    expect(r.length()).toBe(5);

    const stored = await until(() => (e.mod.zones().length === 1 && e.mod.points().length === 1 && e.mod.routes().length === 1 ? e.mod.zones()[0] : undefined));
    expect(stored).toMatchObject({ key: `${PRIVATE}/plan/quarry`, owner: ALICE, shared: false, layer: 'plan', world: OVERWORLD });
    expect(stored.data).toMatchObject({ name: 'Quarry', showLabel: false });
    expect(z.rev).toBe(e.mod.object(`${PRIVATE}/plan/quarry`)!.rev);

    z.update({ boxes: [[[0, 0, 0], [9, 9, 9]], [[0, 0, 0], [4, 9, 9], 'subtract']] });
    expect(z.contains([2, 2, 2])).toBe(false);
    await until(() => e.mod.zones()[0]!.data.boxes?.length === 2);
    z.update((cur) => ({ tags: [...(cur.tags ?? []), 'dig'] }));
    expect(z.data.tags).toEqual(['dig']);
    expect(() => z.update({ polygon: [[0, 0, 0], [1, 0, 0], [0, 0, 1]] })).toThrow(/not both/);
    expect(z.data.polygon).toBeUndefined();

    expect(layer.zone('quarry', { name: 'Quarry', polygon: [[0, 0, 0], [8, 0, 0], [0, 0, 8]], minY: 0, maxY: 3 })).toBe(z);
    expect(z.data.boxes).toBeUndefined();
    const replaced = layer.point('quarry', { name: 'Now a point', pos: [0, 0, 0] });
    expect(z.alive).toBe(false);
    expect(replaced.kind).toBe('core:point');
    await until(() => e.mod.zones().length === 0);

    p.remove();
    expect(p.alive).toBe(false);
    expect(p.rev).toBe(0);
    p.update({ name: 'ignored' });
    expect(layer.has('chest')).toBe(false);
    layer.clear();
    expect(r.alive).toBe(false);
    await until(() => e.mod.points().length === 0 && e.mod.routes().length === 0);
    expect(() => layer.zone('bad', { name: 'Bad', boxes: [] })).toThrow(/needs boxes/);
    expect(layer.has('bad')).toBe(false);
    expect(() => layer.zone('a/b', farm)).toThrow(/must not contain/);
  });
});

describe('player edits of bot-owned definitions', () => {
  async function quarry(): Promise<{ a: BotBridge; z: ZoneHandle; key: string }> {
    const a = await alice();
    const z = a.layer('plan').zone('quarry', { name: 'Quarry', boxes: [[[0, 0, 0], [9, 9, 9]]] });
    const key = `${PRIVATE}/plan/quarry`;
    await until(() => e.mod.object(key));
    return { a, z, key };
  }

  it('accepts editable definitions by default, applies the edit and emits edited', async () => {
    const { z, key } = await quarry();
    const edited: [string, string][] = [];
    z.on('edited', (data, prev) => edited.push([prev.name, data.name]));
    const before = e.mod.object(key)!.rev;
    const answer = await e.mod.proposeEdit<'core:zone'>(key, { name: 'Bigger', boxes: [{ min: { x: 0, y: 0, z: 0 }, max: { x: 19, y: 9, z: 9 }, op: 'add' }] });
    expect(answer).toEqual({ accepted: true, reason: undefined });
    // The accepted data is logged ahead of the answer, so the mod already holds it.
    const after = e.mod.object(key)!;
    expect(after.rev).toBeGreaterThan(before);
    expect((after.data as { name: string }).name).toBe('Bigger');
    expect(z.data.name).toBe('Bigger');
    expect(z.contains([15, 0, 0])).toBe(true);
    expect(edited).toEqual([['Quarry', 'Bigger']]);
    expect(e.mod.pendingEdits()).toEqual([]);
  });

  it('rejects with false, a reason string, a thrown error, or when not editable', async () => {
    const { a, z, key } = await quarry();
    const errors: Error[] = [];
    a.on('error', (err) => errors.push(err));
    const seen: [string, string][] = [];
    z.onEdit((proposed, current) => {
      seen.push([current.name, proposed.name]);
      return proposed.name === 'ok' || (proposed.name === 'no' ? false : `name ${proposed.name} is taken`);
    });
    expect(await e.mod.proposeEdit<'core:zone'>(key, { name: 'no' })).toEqual({ accepted: false, reason: 'rejected' });
    expect(await e.mod.proposeEdit<'core:zone'>(key, { name: 'Farm' })).toEqual({ accepted: false, reason: 'name Farm is taken' });
    expect(seen).toEqual([['Quarry', 'no'], ['Quarry', 'Farm']]);
    expect(z.data.name).toBe('Quarry');
    expect(await e.mod.proposeEdit<'core:zone'>(key, { name: 'ok' })).toEqual({ accepted: true, reason: undefined });

    const clear = z.onEdit(() => {
      throw new Error('handler failed');
    });
    expect(await e.mod.proposeEdit<'core:zone'>(key, { name: 'boom' })).toEqual({ accepted: false, reason: 'handler failed' });
    expect(errors.map((x) => x.message)).toEqual(['handler failed']);
    clear();
    z.update({ editable: false });
    await until(() => (e.mod.object(key)?.data as { editable: boolean }).editable === false);
    expect(await e.mod.proposeEdit<'core:zone'>(key, { name: 'locked' })).toEqual({ accepted: false, reason: 'not_editable' });
    expect(z.data.name).toBe('ok');
  });

  it('waits for an async handler while the edit is pending, and rejects edits that went stale', async () => {
    const { z, key } = await quarry();
    let release!: (v: boolean) => void;
    z.onEdit(() => new Promise<boolean>((resolve) => (release = resolve)));
    const pending = e.mod.proposeEdit<'core:zone'>(key, { name: 'Async' });
    await until(() => e.mod.pendingEdits().length === 1);
    expect(e.mod.pendingEdits()[0]).toMatchObject({ key, owner: ALICE, proposed: { name: 'Async' } });
    await sleep(30);
    release(true);
    expect(await pending).toEqual({ accepted: true, reason: undefined });
    expect(z.data.name).toBe('Async');

    // The bot writes while the handler runs, so the proposal no longer matches the declared state.
    z.onEdit(async () => {
      z.update({ name: 'Bot wins' });
      await sleep(10);
      return true;
    });
    expect(await e.mod.proposeEdit<'core:zone'>(key, { name: 'Player' })).toEqual({ accepted: false, reason: 'conflict' });
    expect(z.data.name).toBe('Bot wins');
    await until(() => (e.mod.object(key)?.data as { name: string }).name === 'Bot wins');

    expect(await e.mod.proposeEdit<'core:zone'>(key, { name: 'Old base' }, { baseRev: 1 })).toEqual({ accepted: false, reason: 'conflict' });
    expect(await e.mod.proposeEdit<'core:zone'>(key, {}, { fields: Uint8Array.of(0x81, 0x00, 0xc3) })).toEqual({ accepted: false, reason: 'invalid_message' });
  });

  it('answers not_editable for kinds without editable traits and accepts handle-less spatial objects by policy', async () => {
    const a = await alice();
    a.layer('plan').box('b', [0, 0, 0], 1);
    a.objects.put('raw-point', 'core:point', { name: 'Raw', pos: { x: 0, y: 0, z: 0 } });
    a.objects.put('locked-point', 'core:point', { name: 'Locked', pos: { x: 0, y: 0, z: 0 }, editable: false });
    await until(() => e.mod.object(`${PRIVATE}/locked-point`));
    expect(() => e.mod.proposeEdit<'core:box'>(`${PRIVATE}/plan/b`, { size: { x: 2, y: 2, z: 2 } })).toThrow(/no editable fields/);
    expect(await e.mod.proposeEdit<'core:box'>(`${PRIVATE}/plan/b`, { size: { x: 2, y: 2, z: 2 } }, { unchecked: true })).toEqual({ accepted: false, reason: 'not_editable' });
    expect(await e.mod.proposeEdit<'core:point'>(`${PRIVATE}/raw-point`, { name: 'Moved' })).toEqual({ accepted: true, reason: undefined });
    expect((e.mod.object(`${PRIVATE}/raw-point`)!.data as { name: string }).name).toBe('Moved');
    expect(await e.mod.proposeEdit<'core:point'>(`${PRIVATE}/locked-point`, { name: 'Moved' })).toEqual({ accepted: false, reason: 'not_editable' });
  });
});

describe('lifecycle', () => {
  it('cleans up extension definitions, view listeners and pending ops on unload', async () => {
    const a = await alice();
    let ctx!: ExtensionContext;
    let handle!: ZoneHandle;
    const heard: string[] = [];
    const ext = defineExtension({
      id: 'test:spatial',
      version: '1.0.0',
      setup(c) {
        ctx = c;
        handle = c.layer('work').zone('site', { name: 'Site', boxes: [[[0, 0, 0], [3, 3, 3]]] });
        c.shared.zones.on('add', (z) => heard.push(z.id));
      },
    });
    await a.use(ext());
    const key = `${ALICE}/test:spatial/work/site`;
    await until(() => e.mod.object(key));
    expect(await e.mod.proposeEdit<'core:zone'>(key, { name: 'Edited' })).toEqual({ accepted: true, reason: undefined });
    expect(handle.data.name).toBe('Edited');
    e.mod.playerCreateShared('core:zone', { name: 'Before', boxes: [{ min: { x: 0, y: 0, z: 0 }, max: { x: 1, y: 1, z: 1 } }] }, { id: 'before' });
    await until(() => heard.includes('before'));

    const pending = ctx.shared.zones.create({ name: 'Late', boxes: [[[0, 0, 0], [1, 1, 1]]] }, { id: 'late' });
    await a.unload('test:spatial');
    expect((await rejection(pending)).code).toBe('cancelled');
    expect(handle.alive).toBe(false);
    await until(() => e.mod.object(key) === undefined);
    e.mod.playerCreateShared('core:zone', { name: 'After', boxes: [{ min: { x: 0, y: 0, z: 0 }, max: { x: 1, y: 1, z: 1 } }] }, { id: 'after' });
    await until(() => a.shared.zones.get('after'));
    expect(heard).toEqual(['before']);
    expect(e.mod.zones().map((z) => z.key)).toEqual(['shared/bridge:zones/after', 'shared/bridge:zones/before']);
  });

  it('keeps views and edit handlers across a resume and refills them after a mod restart', async () => {
    const a = await alice();
    const z = a.layer('plan').zone('quarry', { name: 'Quarry', boxes: [[[0, 0, 0], [9, 9, 9]]] });
    z.onEdit((proposed) => proposed.name !== 'nope');
    await a.shared.points.create({ name: 'Home', pos: [0, 64, 0] }, { id: 'home' });
    await until(() => a.shared.points.get('home'));
    const removed: string[] = [];
    a.shared.points.on('remove', (p) => removed.push(p.id));

    e.mod.disconnect(ALICE);
    await e.mod.waitFor('sessionResumed');
    expect(a.shared.points.get('home')).toBeDefined();
    const key = `${PRIVATE}/plan/quarry`;
    expect(await e.mod.proposeEdit<'core:zone'>(key, { name: 'nope' })).toEqual({ accepted: false, reason: 'rejected' });
    expect(await e.mod.proposeEdit<'core:zone'>(key, { name: 'Resumed' })).toEqual({ accepted: true, reason: undefined });

    const reconnected = new Promise<void>((resolve) => a.on('connected', resolve));
    const mod = await within(e.restart(), 5000, 'the mock mod to restart');
    await within(reconnected, 5000, 'the plugin to reconnect after the restart');
    await until(() => removed.includes('home'));
    const replayed = await until(() => mod.object(key));
    expect((replayed.data as { name: string }).name).toBe('Resumed');
    await until(() => mod.session(ALICE)?.world);
    mod.playerCreateShared('core:point', { name: 'New world point', pos: { x: 1, y: 2, z: 3 } }, { id: 'fresh', world: OVERWORLD });
    await until(() => a.shared.points.get('fresh'));
    expect(a.shared.points.list().map((p) => p.id)).toEqual(['fresh']);
    expect(await within(mod.proposeEdit<'core:zone'>(key, { name: 'After restart' }), 5000, 'the edit after the restart')).toEqual({ accepted: true, reason: undefined });
    expect(z.data.name).toBe('After restart');
  });
});
