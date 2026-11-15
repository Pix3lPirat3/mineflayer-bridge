import type { Target, WorldKey } from '@mineflayer-bridge/protocol';
import { defineExtension } from './extensions.ts';
import { PingMessages } from './generated/bridge-ping.ts';
import { currentOrigin } from './players.ts';

/** A spot the player pinged with the ping key: the block or entity under their crosshair. */
export interface Ping {
  /** `block` or `entity`. */
  readonly type: 'block' | 'entity';
  /** The point: where the crosshair hit the block, or the entity's position. */
  readonly pos: { readonly x: number; readonly y: number; readonly z: number };
  /** The block, for a block ping. */
  readonly block: { readonly x: number; readonly y: number; readonly z: number } | undefined;
  /** The face hit, such as `up`, for a block ping. */
  readonly face: string | undefined;
  /** The block's id, such as `minecraft:chest`, for a block ping. */
  readonly blockName: string | undefined;
  /** The entity, for an entity ping: its id (the same as in mineflayer's `bot.entities`), type and name. */
  readonly entity: { readonly id: number; readonly type: string | undefined; readonly name: string | undefined } | undefined;
  /** The player's world. */
  readonly world: WorldKey | undefined;
  /** The hub link of the player who pinged. */
  readonly playerId: string | undefined;
  /** When it arrived, `Date.now()`. */
  readonly at: number;
}

/** Public API of `bridge:ping`, from `bot.bridge.get('bridge:ping')`. */
export interface PingApi {
  /** Calls `fn` for every ping. Returns a remover. */
  on(fn: (ping: Ping) => void): () => void;
  /** The latest ping, if any. */
  readonly last: Ping | undefined;
}

function toPing(p: { target: Target; world?: WorldKey | undefined; entityType?: string | undefined; entityName?: string | undefined; block?: string | undefined }): Ping | undefined {
  const t = p.target;
  if (t.pos === undefined || (t.type !== 'block' && t.type !== 'entity')) return undefined;
  return {
    type: t.type,
    pos: { x: t.pos.x, y: t.pos.y, z: t.pos.z },
    block: t.block === undefined ? undefined : { x: t.block.x, y: t.block.y, z: t.block.z },
    face: t.face,
    blockName: p.block,
    entity: t.entity === undefined ? undefined : { id: t.entity, type: p.entityType, name: p.entityName },
    world: p.world,
    playerId: currentOrigin()?.id,
    at: Date.now(),
  };
}

/**
 * `bridge:ping`, the Node half: hears the player's pings. In the mod a key (unbound by default, in
 * the Mineflayer Bridge section of Controls) marks the block or entity under the crosshair up to 192
 * blocks away and shows a beam there; every bot and controller that loaded this extension gets it.
 * Bots can then treat the last ping as a target, such as the spot to walk to.
 */
export const ping = defineExtension({
  id: 'bridge:ping',
  version: '1.0.0',
  messages: [PingMessages['bridge:ping.mark']],
  setup(ctx): PingApi {
    const listeners = new Set<(ping: Ping) => void>();
    let last: Ping | undefined;
    ctx.message(PingMessages['bridge:ping.mark']).on((p) => {
      const ping = toPing(p);
      if (ping === undefined) return;
      last = ping;
      for (const fn of [...listeners]) {
        try {
          fn(ping);
        } catch (e) {
          ctx.log.warn('bridge:ping listener failed', { error: String(e) });
        }
      }
    });
    return {
      on(fn) {
        listeners.add(fn);
        return () => listeners.delete(fn);
      },
      get last() {
        return last;
      },
    };
  },
});
