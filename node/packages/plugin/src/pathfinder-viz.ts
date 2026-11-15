import { BridgeError } from '@mineflayer-bridge/protocol';
import { defineExtension } from './extensions.ts';
import { batch, detailGate, isFiniteNumber, WHEN_UNFOCUSED } from './ext-util.ts';
import type { BotLike, Vec3Like } from './types.ts';

/** One step of a mineflayer-pathfinder path (its `Move`), read structurally. Coordinates are block positions. */
export interface PathfinderMoveLike {
  readonly x: number;
  readonly y: number;
  readonly z: number;
  /** Blocks dug to make the step. */
  readonly toBreak?: readonly unknown[];
  /** Blocks placed to make the step. */
  readonly toPlace?: readonly unknown[];
  /** True for a parkour jump. */
  readonly parkour?: boolean;
}

/** The search mineflayer-pathfinder ran (its A* context), read structurally. */
export interface PathfinderSearchLike {
  /** Explored nodes in exploration order: `"x,y,z"` hashes or positions. */
  readonly closedDataSet?: Iterable<unknown>;
}

/** The argument of mineflayer-pathfinder's `path_update` event. */
export interface PathfinderResultLike {
  /** `success`, `partial`, `timeout` or `noPath`. */
  readonly status?: string;
  readonly path: readonly PathfinderMoveLike[];
  readonly context?: PathfinderSearchLike | null;
}

/** A pathfinder goal: `GoalBlock` and `GoalNear` have x, y and z; `GoalXZ` only x and z. */
export interface PathfinderGoalLike {
  readonly x?: number;
  readonly y?: number;
  readonly z?: number;
}

/** A bot with mineflayer-pathfinder loaded. */
export interface PathfinderBotLike extends BotLike {
  readonly pathfinder?: { readonly goal?: PathfinderGoalLike | null } | null;
}

/** Move classes of the route path, in `classes` order. */
export const MOVE_CLASSES = ['walk', 'jump', 'parkour', 'dig', 'place'] as const;
/** A move class of {@link MOVE_CLASSES}. */
export type MoveClass = (typeof MOVE_CLASSES)[number];

const CLASS_STYLE: Readonly<Record<MoveClass, { color: string; lineWidth: number }>> = {
  walk: { color: '#4fc3f7', lineWidth: 2 },
  jump: { color: '#ffd54f', lineWidth: 2 },
  parkour: { color: '#ff8a65', lineWidth: 3 },
  dig: { color: '#e57373', lineWidth: 3 },
  place: { color: '#81c784', lineWidth: 3 },
};

/** Public API of `bridge:pathfinder-viz`, from `bot.bridge.get('bridge:pathfinder-viz')`. */
export interface PathfinderVizApi {
  /** The route as last drawn, start first, in blocks; empty when there is none. */
  readonly route: readonly { readonly x: number; readonly y: number; readonly z: number }[];
  /** Move class of each route segment (`route.length - 1` entries). */
  readonly classes: readonly MoveClass[];
  /** Removes the route, goal and explored nodes until the next path update. */
  clear(): void;
}

/** Classifies the step into `move` from `prev`. */
export function moveClass(prev: { readonly y: number }, move: PathfinderMoveLike): MoveClass {
  if (move.parkour === true) return 'parkour';
  if ((move.toBreak?.length ?? 0) > 0) return 'dig';
  if ((move.toPlace?.length ?? 0) > 0) return 'place';
  return move.y > prev.y ? 'jump' : 'walk';
}

function explorePosition(node: unknown): [number, number, number] | undefined {
  if (typeof node === 'string') {
    const parts = node.split(',');
    if (parts.length !== 3) return undefined;
    const [x, y, z] = parts.map(Number) as [number, number, number];
    return Number.isInteger(x) && Number.isInteger(y) && Number.isInteger(z) ? [x, y, z] : undefined;
  }
  if (typeof node === 'object' && node !== null) {
    const p = node as Partial<Record<'x' | 'y' | 'z', unknown>>;
    if (isFiniteNumber(p.x) && isFiniteNumber(p.y) && isFiniteNumber(p.z)) return [Math.floor(p.x), Math.floor(p.y), Math.floor(p.z)];
  }
  return undefined;
}

function vec(p: Vec3Like): { x: number; y: number; z: number } {
  if (Array.isArray(p)) {
    const t = p as readonly [number, number, number];
    return { x: t[0], y: t[1], z: t[2] };
  }
  const v = p as { readonly x: number; readonly y: number; readonly z: number };
  return { x: v.x, y: v.y, z: v.z };
}

/**
 * `bridge:pathfinder-viz`: draws mineflayer-pathfinder's route, goal and search. It is duck-typed on
 * pathfinder's bot events, so pathfinder is not a dependency:
 * - `path_update` draws the route from the bot through every path node, one segment class per
 *   move (walk, jump, parkour, dig, place) with its own style; `goal_updated` moves the goal
 *   waypoint, read from `bot.pathfinder.goal`;
 * - `path_reset` removes the route, `goal_reached` and `path_stop` remove everything.
 *
 * The explored-nodes heatmap (layer `nodes`, category `debug`, hidden by default) colours the nodes
 * the search closed by exploration order. It is computed only while that layer is visible and
 * detail is full. While detail is reduced or the bot is not focused the route is drawn in one style
 * and the heatmap is removed.
 *
 * Layers: `route` (category `navigation`) and `nodes`. Options:
 * - `colorByMove` (default true): per-class styles; false draws every segment as `walk`;
 * - `goal` (default true): the goal waypoint;
 * - `explored` (default true): offer the explored-nodes layer;
 * - `maxExploredNodes` (default 20000): heatmap cell cap, the earliest explored are kept;
 * - `yOffset` (default 0.1 blocks): height of the route above the block surface;
 * - `throttleMs` (default 100 ms): least time between redraws;
 * - `whenUnfocused` (default `summary`): `full` keeps the detail while another bot is focused.
 *
 * Setup throws `unsupported` in a controller session.
 */
export const pathfinderViz = defineExtension({
  id: 'bridge:pathfinder-viz',
  version: '0.1.0',
  options: {
    colorByMove: { type: 'boolean', default: true },
    goal: { type: 'boolean', default: true },
    explored: { type: 'boolean', default: true },
    maxExploredNodes: { type: 'integer', default: 20_000, min: 1, max: 1_000_000 },
    yOffset: { type: 'number', default: 0.1, min: -2, max: 2 },
    throttleMs: { type: 'integer', default: 100, min: 0, max: 10_000 },
    whenUnfocused: WHEN_UNFOCUSED,
  },
  setup(ctx, options): PathfinderVizApi {
    const bot = ctx.bot as PathfinderBotLike | null;
    if (bot === null) throw new BridgeError('unsupported', 'bridge:pathfinder-viz needs a bot session');
    for (const c of MOVE_CLASSES) ctx.style(c, { ...CLASS_STYLE[c], depth: 'both', xrayAlpha: 0.35 });
    const route = ctx.layer('route', { category: 'navigation' });
    const nodes = options.explored ? ctx.layer('nodes', { category: 'debug', defaultVisible: false }) : undefined;
    let result: PathfinderResultLike | undefined;
    let points: { x: number; y: number; z: number }[] = [];
    let classes: MoveClass[] = [];
    let nodesVisible = false;
    let goalShown = false;

    const drawRoute = (): void => {
      if (result === undefined || result.path.length === 0) {
        points = [];
        classes = [];
        route.remove('path');
        return;
      }
      const startPos = bot.entity?.position;
      const pts: { x: number; y: number; z: number }[] = [];
      const cls: MoveClass[] = [];
      let prev: { y: number } | undefined;
      if (startPos !== undefined) {
        const s = vec(startPos);
        pts.push({ x: s.x, y: s.y + options.yOffset, z: s.z });
        prev = { y: Math.floor(s.y) };
      }
      for (const m of result.path) {
        if (!isFiniteNumber(m.x) || !isFiniteNumber(m.y) || !isFiniteNumber(m.z)) continue;
        if (prev !== undefined) cls.push(moveClass(prev, m));
        pts.push({ x: Math.floor(m.x) + 0.5, y: m.y + options.yOffset, z: Math.floor(m.z) + 0.5 });
        prev = m;
      }
      points = pts;
      classes = cls;
      if (pts.length < 2) {
        route.remove('path');
        return;
      }
      // Per-segment classes are optional data dropped while reduced.
      if (options.colorByMove && rich()) {
        route.path('path', pts, {
          style: 'walk',
          classes: cls.map((c) => MOVE_CLASSES.indexOf(c)),
          classStyles: Object.fromEntries(MOVE_CLASSES.map((c, i) => [i, c])),
        });
      } else {
        route.path('path', pts, { style: 'walk' });
      }
    };

    const drawGoal = (): void => {
      if (!options.goal) return;
      const goal = bot.pathfinder?.goal;
      if (goal === undefined || goal === null || !isFiniteNumber(goal.x) || !isFiniteNumber(goal.z)) {
        if (goalShown) route.remove('goal');
        goalShown = false;
        return;
      }
      const last = result?.path[result.path.length - 1];
      const y = isFiniteNumber(goal.y) ? goal.y : last !== undefined && isFiniteNumber(last.y) ? last.y : bot.entity !== undefined && bot.entity !== null ? vec(bot.entity.position).y : 64;
      route.waypoint('goal', { x: Math.floor(goal.x) + 0.5, y, z: Math.floor(goal.z) + 0.5 }, 'Goal', { beam: true, edgePin: true, style: 'walk' });
      goalShown = true;
    };

    const drawExplored = (): void => {
      if (nodes === undefined) return;
      const closed = result?.context?.closedDataSet;
      if (!nodesVisible || !rich() || closed === undefined || closed === null) {
        nodes.remove('explored');
        return;
      }
      const cells: number[] = [];
      const values: number[] = [];
      let order = 0;
      for (const node of closed) {
        if (values.length >= options.maxExploredNodes) break;
        const p = explorePosition(node);
        if (p === undefined) continue;
        cells.push(p[0], p[1], p[2]);
        values.push(order++);
      }
      if (values.length === 0) {
        nodes.remove('explored');
        return;
      }
      nodes.heatmap('explored', Int32Array.from(cells), Float32Array.from(values), { colormap: 'viridis', alpha: 0.35 });
    };

    const redraw = batch(ctx, options.throttleMs, () => {
      drawRoute();
      drawGoal();
      drawExplored();
    });

    const clearAll = (): void => {
      result = undefined;
      points = [];
      classes = [];
      route.clear();
      nodes?.clear();
      goalShown = false;
    };

    ctx.on(bot, 'path_update', (r: PathfinderResultLike) => {
      if (typeof r !== 'object' || r === null || !Array.isArray(r.path)) return;
      result = r;
      redraw.request();
    });
    ctx.on(bot, 'goal_updated', () => redraw.request());
    ctx.on(bot, 'path_reset', () => {
      result = undefined;
      redraw.request();
    });
    ctx.on(bot, 'goal_reached', clearAll);
    ctx.on(bot, 'path_stop', clearAll);
    const rich = detailGate(ctx, options.whenUnfocused, () => redraw.request());
    if (nodes !== undefined) {
      ctx.whenVisible(nodes, () => {
        nodesVisible = true;
        redraw.request();
        return () => {
          nodesVisible = false;
          nodes.remove('explored');
        };
      });
    }
    return {
      get route() {
        return points;
      },
      get classes() {
        return classes;
      },
      clear: clearAll,
    };
  },
});
