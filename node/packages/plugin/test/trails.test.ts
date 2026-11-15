import { EventEmitter } from 'node:events';
import { describe, expect, it } from 'vitest';
import { defineExtension, trails, type BotBridge, type ExtensionUse, type TrailsApi } from '../src/index.ts';
import { ALICE, FakeBot, fixture, load, OVERWORLD, sleep, thrown, until, type Env } from './helpers.ts';

const EXT = 'bridge:trails';
let e: Env;
const { hub } = fixture((f) => (e = f.e));

async function start(extension: ExtensionUse, name = 'Alice'): Promise<{ b: BotBridge; bot: FakeBot; api: TrailsApi }> {
  const extensions = [extension];
  e.mod.setPlayerContext(OVERWORLD);
  const bot = new FakeBot(name);
  const b = load(hub().plugin({ extensions }), bot);
  await b.ready;
  await until(() => b.state.attached, 5000, 'attach');
  return { b, bot, api: b.get<TrailsApi>(EXT)! };
}

const key = (rest: string): string => `${ALICE}/${EXT}/trails/${rest}`;
const segments = () => e.mod.objects(`${ALICE}/${EXT}/trails/seg/`);
const pathPoints = (k: string): number[] => Array.from((e.mod.object(k)!.data as { points: Float64Array }).points);

/** Moves the bot one block along x per sample until it has `n` points. */
async function walk(bot: FakeBot, api: TrailsApi, n: number, from = 1): Promise<void> {
  for (let i = from; api.points().length < n; i++) {
    bot.move(i, 64, 0);
    const len = api.points().length;
    await until(() => api.points().length > len || api.points().length >= n, 2000, `sample ${i}`);
  }
}

describe('bridge:trails', () => {
  it('samples only when the bot moves beyond the threshold', async () => {
    const { bot, api } = await start(trails({ intervalMs: 10, minDistance: 0.5, redrawMs: 0 }));
    expect(api.points()).toHaveLength(1);
    expect(api.points()[0]).toMatchObject({ x: 0, y: 64, z: 0 });
    bot.move(0.2, 64, 0);
    await sleep(60);
    expect(api.points()).toHaveLength(1);
    bot.move(2, 64, 0);
    await until(() => api.points().length === 2, 2000, 'second point');
    const [a, b2] = api.points();
    expect(b2).toMatchObject({ x: 2, y: 64, z: 0 });
    expect(b2!.t).toBeGreaterThanOrEqual(a!.t);
    await sleep(60);
    expect(api.points()).toHaveLength(2);
  });

  it('starts over after a teleport or a change of dimension', async () => {
    const { bot, api } = await start(trails({ intervalMs: 10, maxJump: 16, redrawMs: 0 }));
    await walk(bot, api, 3);
    bot.move(500, 64, 0);
    await until(() => api.points().length === 1 && api.points()[0]!.x === 500, 2000, 'teleport');
    bot.move(502, 64, 0);
    await until(() => api.points().length === 2, 2000, 'walk on');
    bot.game.dimension = 'the_nether';
    bot.move(503, 64, 0);
    await until(() => api.points().length === 1 && api.points()[0]!.x === 503, 2000, 'dimension');
  });

  it('keeps the ring within maxPoints and maxAgeMs', async () => {
    const { bot, api } = await start(trails({ intervalMs: 10, maxPoints: 5, redrawMs: 0 }));
    for (let i = 1; i <= 8; i++) {
      bot.move(i, 64, 0);
      await until(() => api.points().at(-1)?.x === i, 2000, `point ${i}`);
    }
    expect(api.points().map((p) => p.x)).toEqual([4, 5, 6, 7, 8]);

    const aged = await start(trails({ intervalMs: 10, maxAgeMs: 80, redrawMs: 0 }), 'Bob');
    await walk(aged.bot, aged.api, 3);
    // Not moving: the ring still ages out on each tick.
    await until(() => aged.api.points().length === 0, 2000, 'aged out');
  });

  it('draws coalesced, fading segments in the trails layer', async () => {
    const { bot, api } = await start(trails({ intervalMs: 10, redrawMs: 200, segments: 3, color: '#ff0000' }));
    let redraws = 0;
    e.mod.on('objectChanged', ({ key: k }) => {
      if (k === key('seg/0')) redraws++;
    });
    await walk(bot, api, 7);
    await until(() => segments().length === 3, 3000, 'three segments');
    // Six samples within about one interval cost at most a few redraws, not one each.
    expect(redraws).toBeLessThanOrEqual(3);
    const styles = [0, 1, 2].map((i) => (e.mod.object(key(`seg/${i}`))!.data as { style: string }).style);
    expect(styles).toEqual(['trail-0', 'trail-1', 'trail-2']);
    const colors = styles.map((s) => (e.mod.object(`${ALICE}/${EXT}/~style/${s}`)!.data as { color: number }).color >>> 24);
    expect(colors[0]).toBeLessThan(colors[1]!);
    expect(colors[2]).toBe(0xff);
    // Segments share end points, so the trail is continuous.
    const s0 = pathPoints(key('seg/0'));
    const s1 = pathPoints(key('seg/1'));
    expect(s1.slice(0, 3)).toEqual(s0.slice(-3));
    expect(s0.slice(0, 3)).toEqual([0, 64.1, 0]);
  });

  it('marks events with labels, clears and pauses, also through the command', async () => {
    const { bot, api } = await start(trails({ intervalMs: 10, redrawMs: 0 }));
    await walk(bot, api, 3);
    const m = api.mark('  found iron ')!;
    expect(m).toMatchObject({ label: 'found iron', x: bot.entity.position.x, y: 64, z: 0 });
    await until(() => e.mod.object(key(`mark/${m.id}`)), 2000, 'mark label');
    expect((e.mod.object(key(`mark/${m.id}`))!.data as { text: string }).text).toMatch(/^found iron \d\d:\d\d:\d\d$/);
    expect(api.marks()).toHaveLength(1);
    expect(thrown(() => api.mark('')).code).toBe('invalid_message');

    api.pause();
    expect(api.paused).toBe(true);
    const len = api.points().length;
    bot.move(50, 64, 0);
    await sleep(60);
    expect(api.points()).toHaveLength(len);
    api.resume();
    await until(() => api.points().at(-1)?.x === 50, 2000, 'resumed');

    api.clear();
    expect(api.points()).toEqual([]);
    expect(api.marks()).toEqual([]);
    await until(() => e.mod.objects(`${ALICE}/${EXT}/trails/`).length === 0, 2000, 'cleared');

    expect(await e.mod.runCommand('alice', 'trails pause')).toMatchObject({ status: 'invoked', path: ['pause'] });
    expect(api.paused).toBe(true);
    expect(await e.mod.runCommand('alice', 'trails mark gate open')).toMatchObject({ status: 'invoked', path: ['mark'] });
    expect(api.marks()[0]?.label).toBe('gate open');
    expect(await e.mod.runCommand('alice', 'trails resume')).toMatchObject({ status: 'invoked' });
    expect(api.paused).toBe(false);
    expect(await e.mod.runCommand('alice', 'trails clear')).toMatchObject({ status: 'invoked' });
    expect(api.marks()).toEqual([]);
  });

  it('loads without bridge:tasks and marks task events when it is loaded', async () => {
    const alone = await start(trails({ intervalMs: 10 }));
    expect(alone.api.marks()).toEqual([]);

    const events = new EventEmitter();
    const tasks = defineExtension({ id: 'bridge:tasks', version: '0.1.0', setup: () => ({ on: (ev: string, fn: (t: unknown) => void) => events.on(ev, fn) }) });
    const bot = new FakeBot('Bob');
    const b = load(hub().plugin({ extensions: [trails({ intervalMs: 10 }), tasks()] }), bot);
    await b.ready;
    const api = b.get<TrailsApi>(EXT)!;
    events.emit('start', { name: 'Mine iron' });
    events.emit('end', 'Mine iron');
    expect(api.marks().map((m) => m.label)).toEqual(['Start: Mine iron', 'End: Mine iron']);

    // An API without `on` is ignored.
    const odd = defineExtension({ id: 'bridge:tasks', version: '0.1.0', setup: () => ({}) });
    const c = load(hub().plugin({ extensions: [trails(), odd()] }), new FakeBot('Carol'));
    await c.ready;
    expect(c.get<TrailsApi>(EXT)!.marks()).toEqual([]);
  });

  it('removes everything and stops sampling on unload', async () => {
    const { b, bot, api } = await start(trails({ intervalMs: 10, redrawMs: 0 }));
    await walk(bot, api, 3);
    api.mark('here');
    await until(() => e.mod.objects(`${ALICE}/${EXT}/`).length > 0);
    await b.unload(EXT);
    await until(() => e.mod.objects(`${ALICE}/${EXT}/`).length === 0, 2000, 'removed');
    bot.move(99, 64, 0);
    await sleep(40);
    expect(e.mod.objects(`${ALICE}/${EXT}/`)).toEqual([]);
  });

  it('rejects a bad colour', async () => {
    const bot = new FakeBot('Alice');
    const b = load(hub().plugin(), bot);
    await expect(b.use(trails({ color: 'red' }))).rejects.toThrow(/#rrggbb/);
  });
});
