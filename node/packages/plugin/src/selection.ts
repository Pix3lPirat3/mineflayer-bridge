import { BridgeError } from '@mineflayer-bridge/protocol';
import { defineExtension } from './extensions.ts';
import type { Target, Vec3Value } from './input-types.ts';
import type { Vec3Like } from './types.ts';
import { toVec3 } from './util.ts';

/** The colours of corner 1 and corner 2. */
const CORNER_COLORS = ['#55ff55', '#ff5555'] as const;

/** A box of whole blocks; `min` and `max` are both inside it. */
export interface SelectionArea {
  readonly min: Vec3Value;
  readonly max: Vec3Value;
}

/** What the label over the box says: `<title> 5×1×7 · <hint>`. */
export interface SelectionLabel {
  /** Before the size, such as `Selection` or `Editing farm`. Default: the `title` option. */
  readonly title?: string;
  /** After the size, how to use it, such as `sel` or `@eco zone save`. Default: the `hint` option; none when empty. */
  readonly hint?: string;
}

/** Public API of `bridge:selection`, from `bot.bridge.get(selection)`. */
export interface SelectionApi {
  /** The first corner (left click), as a block position, or `undefined`. */
  readonly corner1: Vec3Value | undefined;
  /** The second corner (right click), or `undefined`. */
  readonly corner2: Vec3Value | undefined;
  /** The box between the corners; one corner alone is a single block. `undefined` with no corner. */
  area(): SelectionArea | undefined;
  /**
   * Sets a corner to the block that holds `pos` and draws the selection again.
   * @throws BridgeError `invalid_message` when `which` is not 1 or 2 or `pos` is not three finite numbers.
   */
  set(which: 1 | 2, pos: Vec3Like): void;
  /** Forgets both corners, removes the drawing and puts the label back to the options. */
  clear(): void;
  /** The text over the box, such as `Selection 5×1×7 · sel`, or `undefined` with nothing selected. */
  readonly label: string | undefined;
  /**
   * Changes what the label over the box says, for this selection: `clear` puts it back. Left out,
   * or a field left out, is the option's text.
   * @throws BridgeError `invalid_message` when a field is not a string.
   */
  setLabel(label?: SelectionLabel): void;
  /**
   * The selection in words: `5 x 1 x 7 (35 blocks) from 1 64 -2 to 5 64 4`, with `; corner 2 not
   * set yet` while one corner is missing, or `nothing selected`.
   */
  describe(): string;
  /** Calls `fn` with the new area after every change. Returns a remover; listeners end on unload. */
  on(event: 'change', fn: (area: SelectionArea | undefined) => void): () => void;
}

/** The box between two corners, in whole blocks; one corner alone is a single block. */
export function selectionArea(corner1: Vec3Value | undefined, corner2: Vec3Value | undefined): SelectionArea | undefined {
  const a = corner1 ?? corner2;
  const b = corner2 ?? corner1;
  if (a === undefined || b === undefined) return undefined;
  return {
    min: { x: Math.min(a.x, b.x), y: Math.min(a.y, b.y), z: Math.min(a.z, b.z) },
    max: { x: Math.max(a.x, b.x), y: Math.max(a.y, b.y), z: Math.max(a.z, b.z) },
  };
}

function sizeOf(a: SelectionArea): Vec3Value {
  return { x: a.max.x - a.min.x + 1, y: a.max.y - a.min.y + 1, z: a.max.z - a.min.z + 1 };
}

const at = (p: Vec3Value): string => `${p.x} ${p.y} ${p.z}`;

/** Labels read `{var}` as templates; the text here is shown as given. */
const literal = (s: string): string => s.replaceAll('{', '(').replaceAll('}', ')');

/** The label over the box: the title, the size and the hint. */
function selectionLabel(a: SelectionArea, title: string, hint: string): string {
  const s = sizeOf(a);
  return [title, `${s.x}×${s.y}×${s.z}`].filter((t) => t !== '').join(' ') + (hint === '' ? '' : ` · ${hint}`);
}

/**
 * `bridge:selection`: a region the player selects with two corners. It adds a tool to the tool
 * wheel (left click sets corner 1 on the block under the crosshair, right click corner 2), draws
 * the box and its numbered corners on the `selection` layer, with a label over the box that says
 * what it is, its size and how to use it ({@link SelectionApi.setLabel}), and keeps the corners
 * for the bot to read, such as the area of a job. It works in a controller session as well as for a bot; a bot's
 * drawing is bound to the bot's world, a controller's shows in whatever world the player is in.
 *
 * Options:
 * - `tool` (default true): register the tool. Without it the corners are only set through the API.
 * - `label` (default `Region select`) and `icon` (default `minecraft:golden_hoe`): how the tool
 *   shows in the wheel.
 * - `notify` (default true): tell the player what each click selected, and how the tool works
 *   when they pick it.
 * - `color` (default `#ffaa00`): colour of the box. Corner 1 is green, corner 2 red.
 * - `title` (default `Selection`) and `hint` (default empty): the label over the box,
 *   `<title> 5×1×7 · <hint>`; the hint is left out when empty.
 */
export const selection = defineExtension({
  id: 'bridge:selection',
  version: '1.1.0',
  options: {
    tool: { type: 'boolean', default: true },
    label: { type: 'string', default: 'Region select' },
    icon: { type: 'string', default: 'minecraft:golden_hoe' },
    notify: { type: 'boolean', default: true },
    color: { type: 'string', default: '#ffaa00' },
    title: { type: 'string', default: 'Selection' },
    hint: { type: 'string', default: '' },
  },
  setup(ctx, options): SelectionApi {
    const corners: [Vec3Value | undefined, Vec3Value | undefined] = [undefined, undefined];
    const listeners = new Set<(area: SelectionArea | undefined) => void>();
    const layer = ctx.layer('selection', { category: 'planning' });
    let labelled: SelectionLabel = {};

    const area = (): SelectionArea | undefined => selectionArea(corners[0], corners[1]);
    const describe = (): string => {
      const a = area();
      if (a === undefined) return 'nothing selected';
      const s = sizeOf(a);
      const text = `${s.x} x ${s.y} x ${s.z} (${s.x * s.y * s.z} blocks) from ${at(a.min)} to ${at(a.max)}`;
      const missing = corners[0] === undefined ? 1 : corners[1] === undefined ? 2 : undefined;
      return missing === undefined ? text : `${text}; corner ${missing} not set yet`;
    };

    const label = (): string | undefined => {
      const a = area();
      return a === undefined ? undefined : selectionLabel(a, labelled.title ?? options.title, labelled.hint ?? options.hint);
    };

    const draw = (): void => {
      const a = area();
      ctx.transaction(() => {
        layer.clear();
        if (a === undefined) return;
        layer.box('box', a.min, sizeOf(a), { color: options.color });
        // Above the corner numbers, so neither hides the other.
        layer.label('title', { x: (a.min.x + a.max.x + 1) / 2, y: a.max.y + 2.1, z: (a.min.z + a.max.z + 1) / 2 }, literal(label()!), { color: options.color });
        corners.forEach((c, i) => {
          if (c === undefined) return;
          const color = CORNER_COLORS[i]!;
          layer.box(`corner${i + 1}`, c, 1, { color });
          layer.label(`label${i + 1}`, { x: c.x + 0.5, y: c.y + 1.5, z: c.z + 0.5 }, String(i + 1), { color });
        });
      });
    };

    const changed = (): void => {
      draw();
      const a = area();
      for (const fn of [...listeners]) {
        try {
          fn(a);
        } catch (e) {
          ctx.log.warn('bridge:selection change listener failed', { error: String(e) });
        }
      }
    };

    const set = (which: 1 | 2, pos: Vec3Like): void => {
      if (which !== 1 && which !== 2) throw new BridgeError('invalid_message', `corner must be 1 or 2, got ${String(which)}`);
      const p = toVec3(pos, 'corner position');
      const block = { x: Math.floor(p.x), y: Math.floor(p.y), z: Math.floor(p.z) };
      const old = corners[which - 1];
      if (old !== undefined && old.x === block.x && old.y === block.y && old.z === block.z) return;
      corners[which - 1] = block;
      changed();
    };

    /** A click of the tool. */
    const click = (which: 1 | 2, target: Target | undefined): void => {
      if (target?.block === undefined) {
        if (options.notify) ctx.notify(`${options.label}: look at a block first`, { level: 'warn' });
        return;
      }
      set(which, target.block);
      if (options.notify) ctx.notify(`Corner ${which}: ${describe()}`);
    };
    if (options.tool) {
      ctx.tools.register('region', {
        label: options.label,
        icon: options.icon,
        targets: ['block'],
        on: { primary: (t) => click(1, t), secondary: (t) => click(2, t) },
        onActivate: () => {
          if (options.notify) ctx.notify(`${options.label}: left-click a block for corner 1, right-click for corner 2`);
        },
      });
    }
    ctx.signal.addEventListener('abort', () => listeners.clear(), { once: true });

    return {
      get corner1() {
        return corners[0] === undefined ? undefined : { ...corners[0] };
      },
      get corner2() {
        return corners[1] === undefined ? undefined : { ...corners[1] };
      },
      area,
      set,
      clear() {
        labelled = {};
        if (corners[0] === undefined && corners[1] === undefined) return;
        corners[0] = undefined;
        corners[1] = undefined;
        changed();
      },
      get label() {
        return label();
      },
      setLabel(l = {}) {
        if (typeof l !== 'object' || l === null) throw new BridgeError('invalid_message', 'selection label must be an object with title and hint');
        for (const k of ['title', 'hint'] as const) {
          if (l[k] !== undefined && typeof l[k] !== 'string') throw new BridgeError('invalid_message', `selection label ${k} must be a string`);
        }
        labelled = { ...(l.title === undefined ? {} : { title: l.title }), ...(l.hint === undefined ? {} : { hint: l.hint }) };
        draw();
      },
      describe,
      on(_event, fn) {
        listeners.add(fn);
        return () => {
          listeners.delete(fn);
        };
      },
    };
  },
});
