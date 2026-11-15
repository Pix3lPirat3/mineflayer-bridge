import { defineExtension } from './extensions.ts';
import { MapMessages, type MapOrderType } from './generated/bridge-map.ts';
import type { TaskRef, TasksApi } from './tasks.ts';

/** An order the player gave on the Bridge map. */
export interface MapOrder {
  readonly type: MapOrderType;
  /** The target of a move: the top of the clicked column, at its centre. */
  readonly pos: { readonly x: number; readonly y: number; readonly z: number } | undefined;
  /** Shift was held: run after the current orders. */
  readonly queue: boolean;
}

/** A goal in mineflayer-pathfinder's shape, as `goals.GoalNear` builds it. */
interface GoalLike {
  heuristic(node: { x: number; y: number; z: number }): number;
  isEnd(node: { x: number; y: number; z: number }): boolean;
  hasChanged(): boolean;
  isValid(): boolean;
}

/** The parts of a bot `bridge:map` drives: mineflayer-pathfinder's `goto` and `stop`. */
export interface MapBotLike {
  pathfinder?: { goto(goal: GoalLike): Promise<unknown>; stop(): void };
}

/** Public API of `bridge:map`, from `bot.bridge.get('bridge:map')`. */
export interface MapOrdersApi {
  /** Calls `fn` for every order, before the extension runs it. Returns a remover. */
  onOrder(fn: (order: MapOrder) => void): () => void;
  /** Moves still to run, the current one first. */
  readonly queue: readonly { x: number; y: number; z: number }[];
  /** Clears the queue and stops moving. */
  stop(): void;
}

/** A pathfinder goal: within `range` blocks of a point, as `goals.GoalNear`. */
function near(x: number, y: number, z: number, range: number): GoalLike {
  const r2 = range * range;
  return {
    heuristic: (n) => Math.abs(n.x - x) + Math.abs(n.y - y) + Math.abs(n.z - z),
    isEnd: (n) => (n.x - x) ** 2 + (n.y - y) ** 2 + (n.z - z) ** 2 <= r2,
    hasChanged: () => false,
    isValid: () => true,
  };
}

/**
 * `bridge:map`, the Node half: takes the orders the player gives on the Bridge map. With `drive`
 * (default true) and mineflayer-pathfinder loaded on the bot, a move goes within `range` blocks
 * (default 1) of its target, Shift-orders queue behind it, and stop clears the queue. When
 * `bridge:tasks` is loaded, each move is a task the player can see and cancel there. Without
 * `drive`, or without pathfinder, orders only reach `onOrder`.
 */
export const mapOrders = defineExtension({
  id: 'bridge:map',
  version: '1.0.0',
  requires: { optionalExtensions: ['bridge:tasks'] },
  messages: [MapMessages['bridge:map.order']],
  options: {
    drive: { type: 'boolean', default: true },
    range: { type: 'number', default: 1, min: 0, max: 64 },
  },
  setup(ctx, options): MapOrdersApi {
    const bot = ctx.bot as (MapBotLike & object) | null;
    const listeners = new Set<(order: MapOrder) => void>();
    let queue: { x: number; y: number; z: number }[] = [];
    let running = false;
    let task: TaskRef | undefined;
    let generation = 0;

    const pathfinder = (): MapBotLike['pathfinder'] => (options.drive ? bot?.pathfinder : undefined);
    const tasks = (): TasksApi | undefined => ctx.optional<TasksApi>('bridge:tasks');

    const halt = (): void => {
      generation++;
      queue = [];
      running = false;
      task?.cancel();
      task = undefined;
      pathfinder()?.stop();
    };

    const runNext = async (): Promise<void> => {
      const pf = pathfinder();
      const target = queue[0];
      if (pf === undefined || target === undefined || running) return;
      running = true;
      const mine = generation;
      const label = `Move to ${Math.floor(target.x)} ${Math.floor(target.y)} ${Math.floor(target.z)}`;
      const t = tasks()?.task(label, { pausable: false, onCancel: () => mine === generation && halt() });
      task = t;
      try {
        await pf.goto(near(target.x, target.y, target.z, options.range));
        if (mine !== generation) return;
        t?.done('Arrived');
      } catch (e) {
        if (mine !== generation) return;
        t?.fail(e instanceof Error ? e.message : String(e));
        ctx.log.warn('bridge:map move failed', { error: String(e) });
      } finally {
        if (mine === generation) {
          running = false;
          task = undefined;
          queue.shift();
          void runNext();
        }
      }
    };

    ctx.message(MapMessages['bridge:map.order']).on((p) => {
      const order: MapOrder = { type: p.type, pos: p.pos === undefined ? undefined : { x: p.pos.x, y: p.pos.y, z: p.pos.z }, queue: p.queue === true };
      for (const fn of [...listeners]) {
        try {
          fn(order);
        } catch (e) {
          ctx.log.warn('bridge:map order listener failed', { error: String(e) });
        }
      }
      if (order.type === 'stop') return halt();
      if (order.pos === undefined) return;
      if (!order.queue) halt();
      queue.push(order.pos);
      void runNext();
    });
    ctx.signal.addEventListener('abort', () => halt(), { once: true });

    return {
      onOrder(fn) {
        listeners.add(fn);
        return () => {
          listeners.delete(fn);
        };
      },
      get queue() {
        return queue.map((p) => ({ ...p }));
      },
      stop: halt,
    };
  },
});
