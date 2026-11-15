import { describe, expect, it } from 'vitest';
import type { MockInbound } from '@mineflayer-bridge/mock-mod';
import type { MessagePayloads, ObjectWrite } from '@mineflayer-bridge/protocol';
import { BridgeError, defineExtension, type BotBridge, type HudSparklineHandle } from '../src/index.ts';
import { ALICE, ALICE_PREFIX, FakeBot, fixture, load, sleep, until, type Env } from './helpers.ts';

let e: Env;
const { hub } = fixture((f) => (e = f.e));

async function connected(): Promise<BotBridge> {
  const b = load(hub().plugin(), new FakeBot('Alice'));
  await until(() => b.state.connected);
  return b;
}

/** Records the writes of every `obj.upsert` the mock receives. */
function upserts(): ObjectWrite[][] {
  const seen: ObjectWrite[][] = [];
  e.mod.on('message', (m: MockInbound) => {
    if (m.type === 'obj.upsert') seen.push((m.payload as MessagePayloads['obj.upsert']).writes);
  });
  return seen;
}

/** Waits until the plugin has flushed and the mock has applied everything. */
async function settle(): Promise<void> {
  await sleep(30);
}

describe('HUD panels', () => {
  it('declares a panel and its widgets under ~hud/, in creation order, in one batch', async () => {
    const b = await connected();
    const seen = upserts();
    const hud = b.hud('main', { title: 'Alice', anchor: 'top-left', order: 2 });
    hud.text('task', 'Mining iron', { maxLines: 2, color: '#ffffff' });
    hud.kv('stats', { Task: 'Mining', Queue: 3, Busy: true });
    hud.bar('progress', { value: 34, max: 64, label: 'Iron', color: 0x00ff00 });
    hud.list('queue', ['iron', 'coal'], { selected: 0, maxRows: 5 });
    hud.badge('state', 'busy', '#ff3b30');
    hud.sparkline('tps', { capacity: 4, min: 0, max: 20 }).push(19.8);
    hud.item('held', 'minecraft:diamond_pickaxe', { count: 1, label: 'Held' });
    await until(() => e.mod.hudPanels()[0]?.widgets.length === 7);
    expect(seen).toHaveLength(1);
    const [panel] = e.mod.hudPanels();
    expect(panel).toMatchObject({ key: `${ALICE_PREFIX}/~hud/main`, id: 'main', extension: 'user:default', scope: 'global', data: { title: 'Alice', anchor: 'top_left', order: 2, collapsed: false } });
    expect(panel!.widgets.map((w) => [w.id, w.kind, w.order])).toEqual([
      ['task', 'core:hud.text', 0],
      ['stats', 'core:hud.kv', 1],
      ['progress', 'core:hud.bar', 2],
      ['queue', 'core:hud.list', 3],
      ['state', 'core:hud.badge', 4],
      ['tps', 'core:hud.sparkline', 5],
      ['held', 'core:hud.item', 6],
    ]);
    const data = Object.fromEntries(panel!.widgets.map((w) => [w.id, w.data]));
    expect(data['task']).toMatchObject({ panel: 'main', text: 'Mining iron', maxLines: 2, color: 0xffffffff });
    expect(data['stats']).toMatchObject({ entries: [{ key: 'Task', value: 'Mining' }, { key: 'Queue', value: '3' }, { key: 'Busy', value: 'true' }] });
    expect(data['progress']).toMatchObject({ value: 34, min: 0, max: 64, label: 'Iron', color: 0xff00ff00 });
    expect(data['queue']).toMatchObject({ items: ['iron', 'coal'], selected: 0, maxRows: 5 });
    expect(data['state']).toMatchObject({ text: 'busy', color: 0xffff3b30 });
    expect(Array.from((data['tps'] as { values: Float32Array }).values)).toEqual([Math.fround(19.8)]);
    expect(data['held']).toMatchObject({ item: 'minecraft:diamond_pickaxe', count: 1, label: 'Held' });
  });

  it('orders widgets by an explicit order and returns the same panel and handles on repeat calls', async () => {
    const b = await connected();
    const hud = b.hud('main');
    hud.text('b', 'second', { order: 10 });
    hud.text('a', 'first', { order: 1 });
    expect(b.hud('main')).toBe(hud);
    const kv = hud.kv('stats', { Queue: 1 });
    expect(hud.kv('stats')).toBe(kv);
    await until(() => e.mod.hudPanels()[0]?.widgets.length === 3);
    expect(e.mod.hudPanels()[0]!.widgets.map((w) => w.id)).toEqual(['a', 'b', 'stats']);
    expect(() => hud.text('new')).toThrow(BridgeError);
    expect(() => hud.bar('new')).toThrow(BridgeError);
    expect(() => b.hud('a/b')).toThrow(BridgeError);
  });

  it('sends a key-value change as a field patch with only the entries field', async () => {
    const b = await connected();
    const stats = b.hud('main').kv('stats', { Task: 'Mining', Queue: 3 });
    await until(() => e.mod.objectData(`${ALICE_PREFIX}/~hud/main/stats`, 'core:hud.kv'));
    await settle();
    const seen = upserts();
    stats.set('Queue', 2);
    stats.set('Ores', 5, '#ffaa00');
    await until(() => seen.length === 1);
    await settle();
    expect(seen).toHaveLength(1);
    const [w] = seen[0]!;
    expect(w!.type).toBe('patch');
    expect(w!.value.key).toBe(`${ALICE_PREFIX}/~hud/main/stats`);
    expect(e.mod.objectData(`${ALICE_PREFIX}/~hud/main/stats`, 'core:hud.kv')!.entries).toEqual([
      { key: 'Task', value: 'Mining', color: undefined },
      { key: 'Queue', value: '2', color: undefined },
      { key: 'Ores', value: '5', color: 0xffffaa00 },
    ]);
    expect(stats.get('Queue')).toBe('2');
    stats.remove('Task');
    stats.set('Queue', 2);
    await until(() => e.mod.objectData(`${ALICE_PREFIX}/~hud/main/stats`, 'core:hud.kv')!.entries.length === 2);
    stats.replace([{ key: 'Only', value: 1 }]);
    await until(() => e.mod.objectData(`${ALICE_PREFIX}/~hud/main/stats`, 'core:hud.kv')!.entries.length === 1);
  });

  it('keeps a sparkline ring buffer and sends one patch per tick, only when it changed', async () => {
    const b = await connected();
    const tps = b.hud('main').sparkline('tps', { capacity: 10 });
    await until(() => e.mod.objectData(`${ALICE_PREFIX}/~hud/main/tps`, 'core:hud.sparkline'));
    await settle();
    const seen = upserts();
    for (let i = 0; i < 100; i++) tps.push(i);
    await until(() => seen.length === 1);
    await settle();
    expect(seen).toHaveLength(1);
    expect(seen[0]).toHaveLength(1);
    expect(seen[0]![0]!.type).toBe('patch');
    expect(tps.values()).toEqual([90, 91, 92, 93, 94, 95, 96, 97, 98, 99]);
    expect(Array.from(e.mod.objectData(`${ALICE_PREFIX}/~hud/main/tps`, 'core:hud.sparkline')!.values)).toEqual([90, 91, 92, 93, 94, 95, 96, 97, 98, 99]);

    tps.push();
    await settle();
    expect(seen).toHaveLength(1);

    tps.push(1, 2);
    tps.push(3);
    await until(() => seen.length === 2);
    expect(Array.from(e.mod.objectData(`${ALICE_PREFIX}/~hud/main/tps`, 'core:hud.sparkline')!.values)).toEqual([93, 94, 95, 96, 97, 98, 99, 1, 2, 3]);

    b.hud('main').sparkline('tps', { capacity: 3 });
    expect(tps.values()).toEqual([1, 2, 3]);
    tps.clear();
    expect(tps.values()).toEqual([]);
    await until(() => e.mod.objectData(`${ALICE_PREFIX}/~hud/main/tps`, 'core:hud.sparkline')!.values.length === 0);
  });

  it('updates bars, lists, badges, text and items, and removes widgets, clears and destroys panels', async () => {
    const b = await connected();
    const hud = b.hud('main');
    const bar = hud.bar('progress', { value: 0, max: 64 });
    const list = hud.list('queue', ['a']);
    const badge = hud.badge('state', 'idle');
    const text = hud.text('task', 'none');
    const item = hud.item('held', 'minecraft:stick');
    bar.set(32);
    bar.update({ label: 'Half' });
    list.set(['a', 'b']);
    list.select(1);
    badge.set('busy', '#ff0000');
    text.set('mining');
    item.set('minecraft:diamond', { count: 3 });
    await until(() => e.mod.objectData(`${ALICE_PREFIX}/~hud/main/held`, 'core:hud.item')?.count === 3);
    expect(e.mod.objectData(`${ALICE_PREFIX}/~hud/main/progress`, 'core:hud.bar')).toMatchObject({ value: 32, max: 64, label: 'Half' });
    expect(e.mod.objectData(`${ALICE_PREFIX}/~hud/main/queue`, 'core:hud.list')).toMatchObject({ items: ['a', 'b'], selected: 1 });
    expect(e.mod.objectData(`${ALICE_PREFIX}/~hud/main/state`, 'core:hud.badge')).toMatchObject({ text: 'busy', color: 0xffff0000 });
    expect(e.mod.objectData(`${ALICE_PREFIX}/~hud/main/task`, 'core:hud.text')?.text).toBe('mining');

    hud.remove('task');
    text.set('ignored');
    await until(() => e.mod.object(`${ALICE_PREFIX}/~hud/main/task`) === undefined);
    await settle();
    expect(e.mod.object(`${ALICE_PREFIX}/~hud/main/task`)).toBeUndefined();
    expect(hud.has('task')).toBe(false);

    const removes: MessagePayloads['obj.remove'][] = [];
    e.mod.on('message', (m) => {
      if (m.type === 'obj.remove') removes.push(m.payload as MessagePayloads['obj.remove']);
    });
    hud.clear();
    await until(() => e.mod.hudPanels()[0]?.widgets.length === 0);
    expect(removes).toEqual([{ keys: [], prefixes: [`${ALICE_PREFIX}/~hud/main/`] }]);
    expect(e.mod.hudPanels()).toHaveLength(1);

    hud.destroy();
    await until(() => e.mod.hudPanels().length === 0);
    hud.text('again', 'back');
    await until(() => e.mod.hudPanels()[0]?.widgets.length === 1);
  });

  it('updates panel options with a patch', async () => {
    const b = await connected();
    b.hud('main', { title: 'A' });
    await until(() => e.mod.hudPanels().length === 1);
    await settle();
    const seen = upserts();
    b.hud('main', { collapsed: true });
    await until(() => seen.length === 1);
    expect(seen[0]![0]!.type).toBe('patch');
    expect(e.mod.hudPanels()[0]!.data).toMatchObject({ title: 'A', collapsed: true, anchor: 'top_right' });
  });

  it('does not resurrect widgets of an unloaded extension from a pending sparkline write', async () => {
    let tps!: HudSparklineHandle;
    const ext = defineExtension({
      id: 'acme:tps',
      version: '1.0.0',
      setup(ctx) {
        tps = ctx.hud('perf').sparkline('tps');
      },
    });
    const EXT = `${ALICE}/acme:tps/`;
    const b = await connected();
    await b.use(ext);
    await until(() => e.mod.objects(EXT).length === 2);
    tps.push(20);
    await b.unload('acme:tps');
    tps.push(19);
    await until(() => e.mod.objects(EXT).length === 0);
    await settle();
    expect(e.mod.objects(EXT)).toEqual([]);
  });
});

describe('boss bars', () => {
  it('declares a boss bar at ~bossbar/<id>, patches updates and removes it', async () => {
    const b = await connected();
    const bar = b.bossbar('task', { text: 'Mining', progress: 0.25, color: 'green', overlay: 'notched_10' });
    await until(() => e.mod.bossbars().length === 1);
    expect(e.mod.bossbars()[0]).toMatchObject({ key: `${ALICE_PREFIX}/~bossbar/task`, id: 'task', data: { text: 'Mining', progress: 0.25, color: 'green', overlay: 'notched_10' } });
    await settle();
    const seen = upserts();
    bar.update({ progress: 2 });
    await until(() => seen.length === 1);
    expect(seen[0]![0]!.type).toBe('patch');
    expect(e.mod.bossbars()[0]!.data.progress).toBe(1);
    expect(b.bossbar('task', { text: 'Smelting' })).toBe(bar);
    await until(() => e.mod.bossbars()[0]?.data.text === 'Smelting');
    expect(() => bar.update({ color: 'orange' as 'red' })).toThrow(BridgeError);
    bar.remove();
    bar.update({ text: 'gone' });
    await until(() => e.mod.bossbars().length === 0);
    await settle();
    expect(e.mod.bossbars()).toHaveLength(0);
  });
});
