import { defineExtension } from './extensions.ts';
import { SpectateMessages } from './generated/bridge-spectate.ts';

/** Public API of `bridge:spectate`, from `bot.bridge.get('bridge:spectate')`. */
export interface SpectateApi {
  /** The player is spectating this bot right now. */
  readonly active: boolean;
  /** Calls `fn` when the player starts (`true`) or stops (`false`) spectating this bot. Returns a remover. */
  on(fn: (active: boolean) => void): () => void;
}

/**
 * `bridge:spectate`, the Node half: tells a bot when the player spectates it. In the mod the
 * Spectate button on a bot's row moves the camera to the bot; the bot can then show more than
 * usual, as `botConsole` does with what the server puts on the bot's screen. Spectating ends for
 * the bot when the player stops, detaches it or the connection drops.
 */
export const spectate = defineExtension({
  id: 'bridge:spectate',
  version: '1.1.0',
  messages: [SpectateMessages['bridge:spectate.watching']],
  setup(ctx): SpectateApi {
    const listeners = new Set<(active: boolean) => void>();
    let active = false;
    const set = (on: boolean): void => {
      if (on === active) return;
      active = on;
      for (const fn of [...listeners]) {
        try {
          fn(on);
        } catch (e) {
          ctx.log.warn('bridge:spectate listener failed', { error: String(e) });
        }
      }
    };
    ctx.message(SpectateMessages['bridge:spectate.watching']).on(({ on }) => set(on));
    ctx.onDisconnected(() => set(false));
    ctx.onDetached(() => set(false));
    return {
      get active() {
        return active;
      },
      on(fn) {
        listeners.add(fn);
        return () => listeners.delete(fn);
      },
    };
  },
});
