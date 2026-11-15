import { describe, expect, it } from 'vitest';
import { encodeExtension } from '../src/ext-wire.ts';
import { MapMessages, mapOrders, tasks, type BotBridge, type MapOrder, type MapOrdersApi, type TasksApi } from '../src/index.ts';
import { ALICE, FakeBot, fixture, load, OVERWORLD, until, type Env } from './helpers.ts';

let e: Env;
const { hub } = fixture((f) => (e = f.e));

/** A bot with a stand-in mineflayer-pathfinder whose moves finish when the test says so. */
class PathBot extends FakeBot {
  readonly goals: { isEnd(n: { x: number; y: number; z: number }): boolean }[] = [];
  stops = 0;
  private finish: (() => void) | undefined;
  private abort: ((e: Error) => void) | undefined;
  pathfinder = {
    goto: (goal: { isEnd(n: { x: number; y: number; z: number }): boolean }) => {
      this.goals.push(goal);
      return new Promise<void>((resolve, reject) => {
        this.finish = resolve;
        this.abort = reject;
      });
    },
    stop: () => {
      this.stops++;
      this.abort?.(new Error('stopped'));
    },
  };

  arrive(): void {
    this.finish?.();
  }
}

async function start(extensions: unknown[]): Promise<{ b: BotBridge; bot: PathBot }> {
  const bot = new PathBot('Alice');
  const b = load(hub().plugin({ extensions: extensions as never }), bot);
  e.mod.setPlayerContext(OVERWORLD);
  await until(() => b.state.attached && b.state.worldMatched);
  return { b, bot };
}

function order(type: 'move' | 'stop', pos?: [number, number, number], queue = false): void {
  const p = pos === undefined ? { type, queue } : { type, pos: { x: pos[0], y: pos[1], z: pos[2] }, queue };
  expect(e.mod.sendExtension(ALICE, 'bridge:map.order', encodeExtension(MapMessages['bridge:map.order'], p))).toBe(true);
}

describe('bridge:map orders', () => {
  it('moves with pathfinder, queues Shift-orders, replaces and stops', async () => {
    const { b, bot } = await start([mapOrders()]);
    const api = b.get<MapOrdersApi>('bridge:map')!;
    const seen: MapOrder[] = [];
    api.onOrder((o) => seen.push(o));
    order('move', [10.5, 64, 3.5]);
    await until(() => bot.goals.length === 1);
    expect(bot.goals[0]!.isEnd({ x: 10, y: 64, z: 4 })).toBe(true);
    expect(bot.goals[0]!.isEnd({ x: 20, y: 64, z: 4 })).toBe(false);
    order('move', [30.5, 64, 3.5], true);
    await until(() => api.queue.length === 2);
    bot.arrive();
    await until(() => bot.goals.length === 2);
    expect(bot.goals[1]!.isEnd({ x: 30, y: 64, z: 3 })).toBe(true);
    order('move', [0.5, 64, 0.5]);
    await until(() => bot.goals.length === 3);
    expect(api.queue).toEqual([{ x: 0.5, y: 64, z: 0.5 }]);
    order('stop');
    await until(() => api.queue.length === 0);
    expect(bot.stops).toBeGreaterThanOrEqual(2);
    expect(seen.map((o) => [o.type, o.queue])).toEqual([['move', false], ['move', true], ['move', false], ['stop', false]]);
  });

  it('shows each move as a task when bridge:tasks is loaded, and a cancelled task stops the bot', async () => {
    const { b, bot } = await start([tasks({ keepMs: 0 }), mapOrders()]);
    const control = b.get<TasksApi>('bridge:tasks')!;
    order('move', [5.5, 70, 5.5]);
    await until(() => control.tasks.length === 1);
    expect(control.tasks[0]!.name).toBe('Move to 5 70 5');
    bot.arrive();
    await until(() => control.tasks.length === 0);
    order('move', [8.5, 70, 8.5]);
    await until(() => control.tasks.length === 1);
    const stopsBefore = bot.stops;
    control.cancelAll();
    await until(() => bot.stops > stopsBefore);
    expect(b.get<MapOrdersApi>('bridge:map')!.queue).toEqual([]);
  });

  it('only reports orders without drive or pathfinder', async () => {
    const plain = new FakeBot('Alice');
    const b = load(hub().plugin({ extensions: [mapOrders({ drive: false })] }), plain);
    e.mod.setPlayerContext(OVERWORLD);
    await until(() => b.state.attached && b.state.worldMatched);
    const seen: MapOrder[] = [];
    b.get<MapOrdersApi>('bridge:map')!.onOrder((o) => seen.push(o));
    order('move', [1, 2, 3]);
    await until(() => seen.length === 1);
    expect(seen[0]).toEqual({ type: 'move', pos: { x: 1, y: 2, z: 3 }, queue: false });
  });
});
