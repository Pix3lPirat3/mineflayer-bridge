import { EventEmitter } from 'node:events';
import { describe, expect, it } from 'vitest';
import { blockState, desyncView, formatTicks, inventoryHud, moveClass, pathfinderViz, taskHud, tasks, vitalsHud, type BlockLike, type BotBridge, type DesyncViewApi, type ExtensionUse, type MineflayerItemLike, type PathfinderVizApi, type TasksApi } from '../src/index.ts';
import { ALICE, FakeBot, fixture, load, OVERWORLD, sleep, until, type Env } from './helpers.ts';

/** A Vec3-like position with the helpers `bot.blockAt` callers use. */
class V {
  constructor(
    readonly x: number,
    readonly y: number,
    readonly z: number,
  ) {}
  floored(): V {
    return new V(Math.floor(this.x), Math.floor(this.y), Math.floor(this.z));
  }
  offset(dx: number, dy: number, dz: number): V {
    return new V(this.x + dx, this.y + dy, this.z + dz);
  }
}

/** A fake bot with the fields the first-party extensions read. */
class RichBot extends FakeBot {
  override entity = { position: new V(0.5, 64, 0.5) as { x: number; y: number; z: number }, id: 1, effects: {} as Record<string, { id: number; amplifier: number; duration: number }> };
  health = 20;
  food = 18;
  foodSaturation = 5;
  oxygenLevel = 20;
  registry = { effects: { 1: { name: 'speed', displayName: 'Speed' }, 19: { name: 'poison', displayName: 'Poison' } } };
  quickBarSlot = 0;
  heldItem: MineflayerItemLike | null = null;
  inventory = Object.assign(new EventEmitter(), { slots: new Array<MineflayerItemLike | null>(46).fill(null) });
  pathfinder: { goal: { x?: number; y?: number; z?: number } | null } = { goal: null };
  world = new Map<string, BlockLike>();
  blockAt(p: V): BlockLike | null {
    return this.world.get(`${p.x},${p.y},${p.z}`) ?? null;
  }

  setSlot(slot: number, item: MineflayerItemLike | null): void {
    const old = this.inventory.slots[slot];
    this.inventory.slots[slot] = item;
    this.inventory.emit('updateSlot', slot, old, item);
  }
}

let e: Env;
const { hub } = fixture((f) => (e = f.e));

async function start(...extensions: ExtensionUse[]): Promise<{ b: BotBridge; bot: RichBot }> {
  const h = hub();
  e.mod.setPlayerContext(OVERWORLD);
  const bot = new RichBot('Alice');
  const b = load(h.plugin({ extensions }), bot);
  await b.ready;
  await until(() => b.state.attached, 5000, 'attach');
  return { b, bot };
}

const key = (ext: string, rest: string): string => `${ALICE}/${ext}/${rest}`;
const panel = (ext: string, id: string) => e.mod.hudPanels().find((p) => p.key === key(ext, `~hud/${id}`));
const widgets = (ext: string, id: string): Record<string, { key: string; kind: string; data: Record<string, unknown> }> =>
  Object.fromEntries((panel(ext, id)?.widgets ?? []).map((w) => [w.id, { key: w.key, kind: w.kind, data: w.data as Record<string, unknown> }]));

describe('bridge:pathfinder-viz', () => {
  const EXT = 'bridge:pathfinder-viz';
  const path = [
    { x: 1, y: 64, z: 0 },
    { x: 2, y: 65, z: 0 },
    { x: 4, y: 65, z: 0, parkour: true },
    { x: 5, y: 65, z: 0, toBreak: [{}] },
    { x: 6, y: 66, z: 0, toPlace: [{}] },
  ];
  const result = { status: 'success', path, context: { closedDataSet: new Set(['0,64,0', '1,64,0', '2,65,0', 'bogus']) } };

  it('classifies moves', () => {
    expect(moveClass({ y: 64 }, { x: 0, y: 64, z: 0 })).toBe('walk');
    expect(moveClass({ y: 64 }, { x: 0, y: 65, z: 0 })).toBe('jump');
    expect(moveClass({ y: 64 }, { x: 0, y: 65, z: 0, parkour: true, toBreak: [1] })).toBe('parkour');
    expect(moveClass({ y: 64 }, { x: 0, y: 64, z: 0, toBreak: [1], toPlace: [1] })).toBe('dig');
    expect(moveClass({ y: 64 }, { x: 0, y: 64, z: 0, toPlace: [1] })).toBe('place');
  });

  it('draws the route with per-class styles and the goal, and clears on goal_reached', async () => {
    const { b, bot } = await start(pathfinderViz({ throttleMs: 0 }));
    bot.pathfinder.goal = { x: 6, y: 66, z: 0 };
    bot.emit('path_update', result);
    await until(() => e.mod.object(key(EXT, 'route/path')) && e.mod.object(key(EXT, 'route/goal')));
    const data = e.mod.object(key(EXT, 'route/path'))!.data as { points: Float64Array; classes: Uint16Array; classStyles: Record<string, string>; style: string };
    expect(Array.from(data.points).slice(0, 6)).toEqual([0.5, 64.1, 0.5, 1.5, 64.1, 0.5]);
    expect(Array.from(data.classes)).toEqual([0, 1, 2, 3, 4]);
    expect(data.classStyles).toEqual({ 0: 'walk', 1: 'jump', 2: 'parkour', 3: 'dig', 4: 'place' });
    expect(e.mod.object(key(EXT, '~style/dig'))?.kind).toBe('core:style');
    expect(e.mod.object(key(EXT, 'route/goal'))?.data).toMatchObject({ pos: { x: 6.5, y: 66, z: 0.5 }, name: 'Goal', beam: true });
    const api = b.get<PathfinderVizApi>(EXT)!;
    expect(api.classes).toEqual(['walk', 'jump', 'parkour', 'dig', 'place']);
    expect(api.route).toHaveLength(6);
    // The heatmap layer is hidden by default, so nothing is computed.
    expect(e.mod.object(key(EXT, 'nodes/explored'))).toBeUndefined();

    bot.emit('path_reset', 'stuck');
    await until(() => !e.mod.object(key(EXT, 'route/path')));
    expect(e.mod.object(key(EXT, 'route/goal'))).toBeDefined();
    bot.emit('goal_reached', bot.pathfinder.goal);
    await until(() => !e.mod.object(key(EXT, 'route/goal')));
    expect(api.route).toEqual([]);
  });

  it('computes the explored heatmap only while visible and detail is full; reduced detail drops classes', async () => {
    const { bot } = await start(pathfinderViz({ throttleMs: 0 }));
    bot.emit('path_update', result);
    await until(() => e.mod.object(key(EXT, 'route/path')));
    e.mod.setLayerVisible({ extension: EXT, layer: 'nodes' }, true);
    await until(() => e.mod.heatmap(key(EXT, 'nodes/explored')), 3000, 'heatmap');
    const heat = e.mod.heatmap(key(EXT, 'nodes/explored'))!;
    expect(Array.from(heat.cells)).toEqual([0, 64, 0, 1, 64, 0, 2, 65, 0]);
    expect(Array.from(heat.values)).toEqual([0, 1, 2]);

    e.mod.setDetail('reduced');
    await until(() => !e.mod.object(key(EXT, 'nodes/explored')), 3000, 'heatmap removed');
    await until(() => (e.mod.object(key(EXT, 'route/path'))?.data as { classes?: Uint16Array }).classes === undefined, 3000, 'plain route');
    e.mod.setDetail('full');
    await until(() => e.mod.object(key(EXT, 'nodes/explored')), 3000, 'heatmap back');
    e.mod.setLayerVisible({ extension: EXT, layer: 'nodes' }, false);
    await until(() => !e.mod.object(key(EXT, 'nodes/explored')));
  });

  it('removes everything on unload', async () => {
    const { b, bot } = await start(pathfinderViz({ throttleMs: 0 }));
    bot.emit('path_update', result);
    await until(() => e.mod.object(key(EXT, 'route/path')));
    await b.unload(EXT);
    await until(() => e.mod.objects(key(EXT, '')).length === 0);
    bot.emit('path_update', result);
    await sleep(50);
    expect(e.mod.objects(key(EXT, ''))).toEqual([]);
    expect(bot.listenerCount('path_update')).toBe(0);
  });
});

describe('bridge:vitals-hud', () => {
  const EXT = 'bridge:vitals-hud';

  it('formats effect durations', () => {
    expect(formatTicks(20 * 65)).toBe('1:05');
    expect(formatTicks(20 * 3600)).toBe('**:**');
    expect(formatTicks(-1)).toBe('');
  });

  it('shows bars and effects, batches updates and follows detail', async () => {
    const { bot } = await start(vitalsHud({ intervalMs: 50 }));
    await until(() => widgets(EXT, 'vitals')['health']);
    expect(panel(EXT, 'vitals')?.data).toMatchObject({ title: 'Vitals', anchor: 'top_left' });
    let w = widgets(EXT, 'vitals');
    expect(Object.keys(w)).toEqual(['health', 'food', 'saturation']);
    expect(w['health']!.data).toMatchObject({ value: 20, max: 20, label: 'Health 20/20', color: 0xffff4b4b });
    expect(w['food']!.data).toMatchObject({ value: 18 });

    const changes: number[] = [];
    e.mod.on('objectChanged', (c) => {
      if (c.key === key(EXT, '~hud/vitals/health')) changes.push(c.object.rev);
    });
    for (let h = 19; h >= 10; h--) {
      bot.health = h;
      bot.emit('health');
    }
    bot.oxygenLevel = 12;
    bot.emit('breath');
    bot.entity.effects['1'] = { id: 1, amplifier: 1, duration: 20 * 90 };
    bot.emit('entityEffect', bot.entity, bot.entity.effects['1']);
    await until(() => widgets(EXT, 'vitals')['effects']);
    w = widgets(EXT, 'vitals');
    expect(changes).toHaveLength(1);
    expect(w['health']!.data).toMatchObject({ value: 10 });
    expect(w['oxygen']!.data).toMatchObject({ value: 12, color: 0xff4fa3ff });
    expect(w['effects']!.data).toMatchObject({ entries: [{ key: 'Speed II', value: '1:30' }] });

    e.mod.setDetail('reduced');
    await until(() => !widgets(EXT, 'vitals')['effects']);
    expect(Object.keys(widgets(EXT, 'vitals'))).toEqual(['health', 'food', 'oxygen']);
  });

  it('waits while detached and catches up on attach', async () => {
    const { b, bot } = await start(vitalsHud({ intervalMs: 0 }));
    await until(() => widgets(EXT, 'vitals')['health']);
    e.mod.setAttached(ALICE, false);
    await until(() => !b.state.attached);
    bot.health = 4;
    bot.emit('health');
    await sleep(50);
    expect(widgets(EXT, 'vitals')['health']!.data).toMatchObject({ value: 20 });
    e.mod.setAttached(ALICE, null);
    await until(() => (widgets(EXT, 'vitals')['health']!.data as { value: number }).value === 4);
    await b.unload(EXT);
    await until(() => panel(EXT, 'vitals') === undefined);
    expect(bot.listenerCount('health')).toBe(0);
  });
});

describe('bridge:inventory-hud', () => {
  const EXT = 'bridge:inventory-hud';

  it('shows the held item, hotbar and armor, and only the held item at reduced detail', async () => {
    const { bot } = await start(inventoryHud({ intervalMs: 0 }));
    await until(() => widgets(EXT, 'inventory')['armor-feet']);
    let w = widgets(EXT, 'inventory');
    expect(Object.keys(w)).toEqual(['held', ...Array.from({ length: 9 }, (_, i) => `hotbar-${i}`), 'armor-head', 'armor-chest', 'armor-legs', 'armor-feet', 'offhand']);
    expect(w['held']!.data).toMatchObject({ item: 'minecraft:air', count: 0, label: 'Held' });
    expect(w['hotbar-0']!.data).toMatchObject({ label: '>1' });

    const pick = { name: 'diamond_pickaxe', count: 1 };
    bot.setSlot(36, pick);
    bot.heldItem = pick;
    bot.emit('heldItemChanged', pick);
    bot.setSlot(5, { name: 'iron_helmet', count: 1 });
    bot.setSlot(40, { name: 'cobblestone', count: 32 });
    await until(() => (widgets(EXT, 'inventory')['hotbar-4']?.data as { item?: string }).item === 'minecraft:cobblestone');
    w = widgets(EXT, 'inventory');
    expect(w['held']!.data).toMatchObject({ item: 'minecraft:diamond_pickaxe', count: 1, label: 'Held diamond_pickaxe' });
    expect(w['hotbar-4']!.data).toMatchObject({ count: 32, label: '5 cobblestone x32' });
    expect(w['armor-head']!.data).toMatchObject({ item: 'minecraft:iron_helmet', label: 'Head iron_helmet' });

    e.mod.setDetail('reduced');
    await until(() => Object.keys(widgets(EXT, 'inventory')).length === 1);
    expect(Object.keys(widgets(EXT, 'inventory'))).toEqual(['held']);
  });
});

describe('bridge:task-hud', () => {
  const EXT = 'bridge:task-hud';
  /** The text the player sees, with the extension's variables rendered into its template. */
  const shownText = (id: string): string | undefined => {
    const w = widgets(EXT, 'tasks')[id];
    return w === undefined ? undefined : e.mod.rendered(w.key);
  };

  it('shows the current task, progress, queue, result and boss bar', async () => {
    // bridge:tasks is loaded with the HUD.
    const { b } = await start(taskHud({ intervalMs: 0, bossbar: true }));
    expect(b.get(EXT)).toBeUndefined();
    const api = b.get<TasksApi>('bridge:tasks')!;
    await until(() => shownText('current') === 'Idle');
    expect(widgets(EXT, 'tasks')['current']!.data).toMatchObject({ text: '{task}' });

    const mine = api.task('Mine iron').status('Digging').progress(3, 10);
    api.task('Deliver');
    expect(api.task('Mine iron').id).toBe(mine.id);
    await until(() => widgets(EXT, 'tasks')['queue']);
    let w = widgets(EXT, 'tasks');
    expect(shownText('current')).toBe('Mine iron: Digging');
    expect(w['progress']!.data).toMatchObject({ value: 3, max: 10, label: '{done}/{total}' });
    expect(e.mod.rendered(w['progress']!.key)).toBe('3/10');
    expect(w['queue']!.data).toMatchObject({ items: ['Deliver'] });
    await until(() => e.mod.bossbars().length === 1);
    expect(e.mod.bossbars()[0]!.data).toMatchObject({ text: '{task}', color: 'blue' });
    expect(e.mod.rendered(e.mod.bossbars()[0]!.key)).toBe('Mine iron: Digging');
    expect((e.mod.bossbars()[0]!.data as { progress: number }).progress).toBeCloseTo(0.3);
    // A progress step patches only the variables, the bar value and the boss bar progress.
    const writes: string[] = [];
    const off = e.mod.on('objectChanged', (c) => writes.push(c.key));
    mine.progress(4);
    await until(() => e.mod.rendered(widgets(EXT, 'tasks')['progress']!.key) === '4/10');
    off();
    expect(writes.filter((k) => k.includes(`${EXT}/`)).map((k) => k.slice(k.indexOf(`${EXT}/`) + EXT.length + 1)).sort()).toEqual(['~bossbar/task', '~hud/tasks/progress', '~vars']);

    mine.done();
    expect(mine.active).toBe(false);
    expect(mine.progress(9, 10)).toBe(mine);
    await until(() => shownText('current') === 'Deliver');
    w = widgets(EXT, 'tasks');
    expect(w['progress']).toBeUndefined();
    expect(w['queue']).toBeUndefined();
    expect(w['last']!.data).toMatchObject({ text: 'Done: Mine iron', color: 0xff81c784 });

    api.task('Deliver').fail('No chest');
    await until(() => e.mod.bossbars().length === 0);
    expect(widgets(EXT, 'tasks')['last']!.data).toMatchObject({ text: 'No chest', color: 0xffe57373 });
  });

  it('drops the queue and result at reduced detail', async () => {
    const { b } = await start(taskHud({ intervalMs: 0 }));
    const api = b.get<TasksApi>('bridge:tasks')!;
    api.task('A');
    api.task('B');
    api.task('C').done();
    await until(() => widgets(EXT, 'tasks')['queue'] && widgets(EXT, 'tasks')['last']);
    e.mod.setDetail('reduced');
    await until(() => !widgets(EXT, 'tasks')['queue'] && !widgets(EXT, 'tasks')['last']);
    expect(shownText('current')).toBe('A');
  });

  it('leaves subtasks out and shows pauses, cancels and the end', async () => {
    const { b } = await start(tasks({ keepMs: 0 }), taskHud({ intervalMs: 0 }));
    const control = b.get<TasksApi>('bridge:tasks')!;
    const job = control.task('Build house', { total: 4 }).status('Walls').progress(1);
    job.subtask('Fetch planks');
    await until(() => shownText('current') === 'Build house: Walls');
    expect(widgets(EXT, 'tasks')['queue']).toBeUndefined();
    job.pause();
    await until(() => shownText('current') === 'Build house: Paused, Walls');
    job.resume().done('Built');
    await until(() => shownText('current') === 'Idle');
    expect(widgets(EXT, 'tasks')['last']!.data).toMatchObject({ text: 'Built', color: 0xff81c784 });
    control.task('Dig').cancel();
    await until(() => (widgets(EXT, 'tasks')['last']!.data as { text: string }).text === 'Cancelled');
  });
});

describe('bridge:desync-view', () => {
  const EXT = 'bridge:desync-view';

  it('formats block states', () => {
    expect(blockState({ name: 'stone' })).toBe('minecraft:stone');
    expect(blockState({ name: 'oak_stairs', getProperties: () => ({ half: 'bottom', facing: 'east', waterlogged: false }) })).toBe('minecraft:oak_stairs[facing=east,half=bottom,waterlogged=false]');
  });

  it('builds a diffed ghost volume of the bot world around it while visible and at full detail', async () => {
    const { b, bot } = await start();
    bot.world.set('0,63,0', { name: 'grass_block', getProperties: () => ({ snowy: false }) });
    bot.world.set('1,63,0', { name: 'stone' });
    bot.world.set('0,64,0', { name: 'air' });
    bot.world.set('5,64,0', { name: 'stone' });
    await b.use(desyncView({ radius: 2, intervalMs: 100 }));
    const k = key(EXT, 'desync/belief');
    await until(() => e.mod.ghost(k), 3000, 'ghost volume');
    const g = e.mod.ghost(k)!;
    expect(g.data).toMatchObject({ diffAgainstWorld: true, hideMatching: true });
    expect(g.count).toBe(3);
    expect(g.get({ x: 0, y: 63, z: 0 })).toBe('minecraft:grass_block[snowy=false]');
    expect(g.get({ x: 0, y: 64, z: 0 })).toBe('minecraft:air');
    expect(g.get({ x: 5, y: 64, z: 0 })).toBeUndefined();
    expect(b.get<DesyncViewApi>(EXT)?.blocks).toBe(3);

    bot.world.set('1,64,1', { name: 'dirt' });
    await until(() => e.mod.ghost(k)?.count === 4, 3000, 'refresh');

    e.mod.setDetail('reduced');
    await until(() => !e.mod.object(k));
    e.mod.setDetail('full');
    await until(() => e.mod.object(k));
    e.mod.setLayerVisible({ extension: EXT, layer: 'desync' }, false);
    await until(() => !e.mod.object(k));
    bot.world.set('2,64,2', { name: 'dirt' });
    await sleep(250);
    expect(e.mod.object(k)).toBeUndefined();
    expect(b.get<DesyncViewApi>(EXT)?.blocks).toBe(0);
  });

  it('needs bot.blockAt', async () => {
    const h = hub({ reconnect: false });
    const b = load(h.plugin(), new FakeBot('Alice'));
    await expect(b.use(desyncView())).rejects.toMatchObject({ code: 'unsupported' });
  });
});
