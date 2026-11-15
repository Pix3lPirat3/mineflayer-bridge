import { BridgeError } from '@mineflayer-bridge/protocol';
import { defineExtension } from './extensions.ts';
import { detailGate, isFiniteNumber, itemId } from './ext-util.ts';
import type { GhostBlocks } from './retained-types.ts';
import type { BotLike } from './types.ts';

/** A block as mineflayer's `bot.blockAt` returns it (prismarine-block), read structurally. */
export interface BlockLike {
  /** Registry name without namespace, such as `oak_stairs`. */
  readonly name: string;
  /** Block state properties, such as `{ facing: 'east', half: 'bottom' }`. */
  getProperties?(): Readonly<Record<string, string | number | boolean>>;
}

/** A position with mineflayer `Vec3` helpers; `bot.blockAt` expects one. */
export interface Vec3WithOffset {
  readonly x: number;
  readonly y: number;
  readonly z: number;
  floored?(): Vec3WithOffset;
  offset?(dx: number, dy: number, dz: number): Vec3WithOffset;
}

/** The parts of a mineflayer bot `bridge:desync-view` reads. */
export interface DesyncBotLike extends BotLike {
  entity?: { readonly position: Vec3WithOffset; readonly id?: number } | null;
  /** The block the bot believes is at a position, or `null` in an unloaded chunk. */
  blockAt?(point: Vec3WithOffset): BlockLike | null;
}

/** Public API of `bridge:desync-view`. */
export interface DesyncViewApi {
  /** Rebuilds the volume now, when the layer is visible and detail is full. */
  refresh(): void;
  /** Blocks in the volume as last sent; 0 when none is shown. */
  readonly blocks: number;
}

const AIR = new Set(['air', 'cave_air', 'void_air']);

/** The full block state string of a block: `minecraft:name[key=value,...]` with keys sorted. */
export function blockState(block: BlockLike): string {
  const id = itemId(block.name);
  const props = block.getProperties?.();
  if (props === undefined) return id;
  const keys = Object.keys(props).sort();
  if (keys.length === 0) return id;
  return `${id}[${keys.map((k) => `${k}=${String(props[k])}`).join(',')}]`;
}

/**
 * `bridge:desync-view`: shows where the bot's world model differs from the player's. Every
 * `intervalMs` it reads `bot.blockAt` for each block within `radius` of the bot (a cube, in blocks)
 * into a ghost-block volume declared with `diffAgainstWorld`, so the mod colours the blocks the bot
 * believes in that the player's client sees differently and hides the matching ones. Air is
 * included by default, so blocks the bot thinks are gone also show. Blocks in chunks the bot has
 * not loaded are left out.
 *
 * It costs `(2 * radius + 1)^3` lookups per refresh, so it runs only while its layer `desync`
 * (category `debug`) is visible and detail is full; otherwise the volume is removed. An unchanged
 * volume is not sent again (its blob hash is the same).
 *
 * Options: `radius` (default 6, 1 to 16), `intervalMs` (default 1000 ms, at least 100), `includeAir`
 * (default true), `alpha` (default 0.45), `defaultVisible` (default true). Setup throws
 * `unsupported` in a controller session or for a bot without `blockAt`.
 */
export const desyncView = defineExtension({
  id: 'bridge:desync-view',
  version: '0.1.0',
  options: {
    radius: { type: 'integer', default: 6, min: 1, max: 16 },
    intervalMs: { type: 'integer', default: 1000, min: 100, max: 600_000 },
    includeAir: { type: 'boolean', default: true },
    alpha: { type: 'number', default: 0.45, min: 0, max: 1 },
    defaultVisible: { type: 'boolean', default: true },
  },
  setup(ctx, options): DesyncViewApi {
    const bot = ctx.bot as DesyncBotLike | null;
    if (bot === null) throw new BridgeError('unsupported', 'bridge:desync-view needs a bot session');
    if (typeof bot.blockAt !== 'function') throw new BridgeError('unsupported', 'bridge:desync-view needs bot.blockAt');
    const blockAt = bot.blockAt.bind(bot);
    const layer = ctx.layer('desync', { category: 'debug', defaultVisible: options.defaultVisible });
    let active = false;
    let shape: GhostBlocks | undefined;
    let shown = 0;

    const removeShape = (): void => {
      shape?.remove();
      shape = undefined;
      shown = 0;
    };

    const build = (): void => {
      if (!active || !fullDetail()) {
        removeShape();
        return;
      }
      const pos = bot.entity?.position;
      if (pos === undefined) return;
      if (!isFiniteNumber(pos.x) || !isFiniteNumber(pos.y) || !isFiniteNumber(pos.z)) return;
      const base: Vec3WithOffset = typeof pos.floored === 'function' ? pos.floored() : { x: Math.floor(pos.x), y: Math.floor(pos.y), z: Math.floor(pos.z) };
      const at = (dx: number, dy: number, dz: number): Vec3WithOffset =>
        typeof base.offset === 'function' ? base.offset(dx, dy, dz) : { x: base.x + dx, y: base.y + dy, z: base.z + dz };
      const volume = ctx.volume();
      const r = options.radius;
      for (let dy = -r; dy <= r; dy++) {
        for (let dz = -r; dz <= r; dz++) {
          for (let dx = -r; dx <= r; dx++) {
            const block = blockAt(at(dx, dy, dz));
            if (block === null || block === undefined || typeof block.name !== 'string') continue;
            if (!options.includeAir && AIR.has(block.name)) continue;
            volume.set([base.x + dx, base.y + dy, base.z + dz], blockState(block));
          }
        }
      }
      if (volume.count === 0) {
        removeShape();
        return;
      }
      const o = { alpha: options.alpha, diffAgainstWorld: true, hideMatching: true };
      if (shape === undefined || !shape.alive) shape = layer.ghostBlocks('belief', volume, o);
      else shape.update(volume, o);
      shown = volume.count;
    };

    const safeBuild = (): void => {
      try {
        build();
      } catch (e) {
        ctx.log.error('desync volume failed', { error: (e as Error).message });
      }
    };

    ctx.whenVisible(layer, (signal) => {
      active = true;
      safeBuild();
      const timer = setInterval(safeBuild, options.intervalMs);
      signal.addEventListener('abort', () => clearInterval(timer), { once: true });
      return () => {
        clearInterval(timer);
        active = false;
        removeShape();
      };
    });
    const fullDetail = detailGate(ctx, 'full', () => safeBuild());
    return {
      refresh: safeBuild,
      get blocks() {
        return shown;
      },
    };
  },
});
