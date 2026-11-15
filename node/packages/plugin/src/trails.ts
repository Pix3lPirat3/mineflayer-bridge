import { BridgeError } from '@mineflayer-bridge/protocol';
import { defineExtension } from './extensions.ts';
import { batch, detailGate, WHEN_UNFOCUSED } from './ext-util.ts';
import type { BotLike } from './types.ts';
import { toVec3 } from './util.ts';

/** One recorded position of `bridge:trails`. `t` is `Date.now()` when it was sampled. */
export interface TrailPoint {
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly t: number;
}

/** An event marked on the trail with {@link TrailsApi.mark}. */
export interface TrailMark extends TrailPoint {
  readonly id: number;
  readonly label: string;
}

/** Public API of `bridge:trails`, from `bot.bridge.get('bridge:trails')`. */
export interface TrailsApi {
  /** Recorded points within the ring bounds, oldest first. */
  points(): TrailPoint[];
  /** Marks within the ring bounds, oldest first. */
  marks(): TrailMark[];
  /**
   * Marks an event at the bot's current position (the last point when the bot has no entity) and
   * draws a label there with the time. Returns the mark, or `undefined` when there is no position.
   * @throws BridgeError `invalid_message` for an empty label.
   */
  mark(label: string): TrailMark | undefined;
  /** Removes every point and mark. */
  clear(): void;
  /** Stops recording points; marks still work. */
  pause(): void;
  resume(): void;
  /** True while recording is paused. */
  readonly paused: boolean;
}

/**
 * The subset of a `bridge:tasks` API that trails uses, read defensively: when `on` is a function,
 * `start` and `end` events are marked on the trail. A task argument may be a name or an object with
 * a `name`.
 */
interface TaskEventsLike {
  on?(event: string, fn: (task: unknown) => void): unknown;
}

function taskName(task: unknown): string {
  if (typeof task === 'string' && task !== '') return task;
  if (typeof task === 'object' && task !== null) {
    const name = (task as { name?: unknown }).name;
    if (typeof name === 'string' && name !== '') return name;
  }
  return 'task';
}

function hex2(v: number): string {
  return Math.round(Math.max(0, Math.min(1, v)) * 255)
    .toString(16)
    .padStart(2, '0');
}

/**
 * `bridge:trails`: the bot's recent positions drawn as a trail, with a timeline. Every `intervalMs`
 * the bot's position is sampled and kept when it moved more than `minDistance` blocks since the last
 * point. Points older than `maxAgeMs` and beyond `maxPoints` (the oldest first) are dropped; marks
 * follow the same age bound and `maxMarks`. A change of dimension, or a jump of more than `maxJump`
 * blocks between samples (a teleport), starts the trail over, so it never draws across the gap.
 *
 * The trail is drawn in layer `trails` (category `navigation`) as `segments` paths with rising alpha,
 * the newest opaque, or one path without `fade`. While detail is reduced or the bot is not focused
 * it is one path. Redraws are coalesced to at most one per `redrawMs` and wait while the player is
 * not attached. Marks are labels `mark/<id>` with the label and the time.
 *
 * With `bridge:tasks` loaded in the same `use()` call and an API that has `on`, its `start` and `end`
 * events are marked. The `command` option registers `trails clear|pause|resume|mark <label>`.
 *
 * Options: `intervalMs` (default 500), `minDistance` (default 0.5 blocks), `maxJump` (default 32
 * blocks), `maxAgeMs` (default 5
 * minutes), `maxPoints` (default 600), `maxMarks` (default 50), `color` (`#rrggbb`, default
 * `#4fc3f7`), `fade` (default true), `segments` (default 4), `yOffset` (default 0.1), `redrawMs`
 * (default 1000), `command` (default true) and `whenUnfocused` (`summary` by default, or `full`).
 * Setup throws `unsupported` in a controller session.
 */
export const trails = defineExtension({
  id: 'bridge:trails',
  version: '0.1.0',
  requires: { optionalExtensions: ['bridge:tasks'] },
  options: {
    intervalMs: { type: 'integer', default: 500, min: 10, max: 60_000 },
    minDistance: { type: 'number', default: 0.5, min: 0, max: 64 },
    maxJump: { type: 'number', default: 32, min: 1, max: 100_000 },
    maxAgeMs: { type: 'integer', default: 300_000, min: 1, max: 86_400_000 },
    maxPoints: { type: 'integer', default: 600, min: 2, max: 100_000 },
    maxMarks: { type: 'integer', default: 50, min: 0, max: 1000 },
    color: { type: 'string', default: '#4fc3f7' },
    fade: { type: 'boolean', default: true },
    segments: { type: 'integer', default: 4, min: 1, max: 16 },
    yOffset: { type: 'number', default: 0.1, min: -2, max: 2 },
    redrawMs: { type: 'integer', default: 1000, min: 0, max: 60_000 },
    command: { type: 'boolean', default: true },
    whenUnfocused: WHEN_UNFOCUSED,
  },
  setup(ctx, options): TrailsApi {
    const bot: BotLike | null = ctx.bot;
    if (bot === null) throw new BridgeError('unsupported', 'bridge:trails needs a bot session');
    if (!/^#[0-9a-f]{6}$/i.test(options.color)) throw new BridgeError('invalid_message', `bridge:trails color must be #rrggbb, got ${options.color}`);
    // One style per fade level, oldest first; the newest is opaque.
    for (let i = 0; i < options.segments; i++) {
      ctx.style(`trail-${i}`, { color: `${options.color}${hex2((i + 1) / options.segments)}`, lineWidth: 2, depth: 'both', xrayAlpha: 0.35 });
    }
    const layer = ctx.layer('trails', { category: 'navigation' });
    let points: TrailPoint[] = [];
    let marks: TrailMark[] = [];
    let paused = false;
    let nextMark = 1;
    let dimension = bot.game?.dimension;
    const drawnSegments = new Set<string>();
    const drawnMarks = new Set<string>();

    const prune = (now: number): boolean => {
      const before = points.length + marks.length;
      const cutoff = now - options.maxAgeMs;
      let drop = 0;
      while (drop < points.length && points[drop]!.t < cutoff) drop++;
      drop = Math.max(drop, points.length - options.maxPoints);
      if (drop > 0) points = points.slice(drop);
      marks = marks.filter((m) => m.t >= cutoff);
      if (marks.length > options.maxMarks) marks = marks.slice(marks.length - options.maxMarks);
      return points.length + marks.length !== before;
    };

    const position = (): { x: number; y: number; z: number } | undefined => {
      const p = bot.entity?.position;
      if (p === undefined) return undefined;
      try {
        return toVec3(p);
      } catch {
        return undefined;
      }
    };

    const drawTrail = (): void => {
      const pts = points.map((p) => ({ x: p.x, y: p.y + options.yOffset, z: p.z }));
      const n = options.fade && rich() ? Math.min(options.segments, Math.max(1, pts.length - 1)) : 1;
      const want = new Set<string>();
      if (pts.length >= 2) {
        const edges = pts.length - 1;
        for (let i = 0; i < n; i++) {
          // Segment i covers edges [from, to); neighbours share their end point so the trail is continuous.
          const from = Math.floor((edges * i) / n);
          const to = Math.floor((edges * (i + 1)) / n);
          if (to <= from) continue;
          const id = `seg/${i}`;
          // The newest segment always takes the opaque style, so fewer segments do not shift the fade.
          layer.path(id, pts.slice(from, to + 1), { style: `trail-${options.segments - n + i}` });
          want.add(id);
        }
      }
      for (const id of drawnSegments) if (!want.has(id)) layer.remove(id);
      drawnSegments.clear();
      for (const id of want) drawnSegments.add(id);
    };

    const drawMarks = (): void => {
      const want = new Set<string>();
      for (const m of marks) {
        const id = `mark/${m.id}`;
        want.add(id);
        if (drawnMarks.has(id)) continue;
        const time = new Date(m.t).toTimeString().slice(0, 8);
        layer.label(id, { x: m.x, y: m.y + 0.5, z: m.z }, `${m.label} ${time}`, { scale: 0.6, color: options.color });
      }
      for (const id of drawnMarks) if (!want.has(id)) layer.remove(id);
      drawnMarks.clear();
      for (const id of want) drawnMarks.add(id);
    };

    const redraw = batch(ctx, options.redrawMs, () => {
      prune(Date.now());
      drawTrail();
      drawMarks();
    });
    const rich = detailGate(ctx, options.whenUnfocused, () => redraw.request());

    const sample = (): void => {
      const now = Date.now();
      let changed = prune(now);
      const p = paused ? undefined : position();
      if (p !== undefined) {
        const dim = bot.game?.dimension;
        let last = points[points.length - 1];
        const gap = last === undefined ? 0 : Math.hypot(p.x - last.x, p.y - last.y, p.z - last.z);
        if (last !== undefined && (dim !== dimension || gap > options.maxJump)) {
          points = [];
          last = undefined;
          changed = true;
        }
        dimension = dim;
        const moved = last === undefined || gap > options.minDistance;
        if (moved) {
          points.push({ ...p, t: now });
          prune(now);
          changed = true;
        }
      }
      if (changed) redraw.request();
    };
    const timer = setInterval(sample, options.intervalMs);
    timer.unref();
    ctx.signal.addEventListener('abort', () => clearInterval(timer), { once: true });

    const mark = (label: string): TrailMark | undefined => {
      if (typeof label !== 'string' || label.trim() === '') throw new BridgeError('invalid_message', 'trail mark label must be a non-empty string');
      const p = position() ?? points[points.length - 1];
      if (p === undefined) return undefined;
      const m: TrailMark = { id: nextMark++, label: label.trim(), x: p.x, y: p.y, z: p.z, t: Date.now() };
      marks.push(m);
      prune(m.t);
      redraw.request();
      return { ...m };
    };

    const clear = (): void => {
      points = [];
      marks = [];
      redraw.request();
    };

    const tasks = ctx.optional<TaskEventsLike>('bridge:tasks');
    if (tasks !== undefined && tasks !== null && typeof tasks.on === 'function') {
      for (const [event, prefix] of [['start', 'Start'], ['end', 'End']] as const) {
        const off = tasks.on(event, (task) => {
          if (!ctx.signal.aborted) mark(`${prefix}: ${taskName(task)}`);
        });
        if (typeof off === 'function') ctx.signal.addEventListener('abort', () => (off as () => void)(), { once: true });
      }
    }

    if (options.command) {
      ctx.commands.register('trails', {
        description: 'Bot trail',
        children: {
          clear: {
            description: 'Remove every point and mark',
            run: () => {
              clear();
              return 'Trail cleared';
            },
          },
          pause: {
            description: 'Stop recording',
            run: () => {
              paused = true;
              return 'Trail paused';
            },
          },
          resume: {
            description: 'Resume recording',
            run: () => {
              paused = false;
              return 'Trail recording';
            },
          },
          mark: {
            description: 'Mark an event at the bot',
            args: [{ name: 'label', type: 'greedy' }],
            run: (args) => (mark(String(args.label)) === undefined ? 'No position to mark' : `Marked ${String(args.label).trim()}`),
          },
        },
      });
    }

    sample();
    return {
      points() {
        prune(Date.now());
        return points.map((p) => ({ ...p }));
      },
      marks() {
        prune(Date.now());
        return marks.map((m) => ({ ...m }));
      },
      mark,
      clear,
      pause() {
        paused = true;
      },
      resume() {
        paused = false;
      },
      get paused() {
        return paused;
      },
    };
  },
});
