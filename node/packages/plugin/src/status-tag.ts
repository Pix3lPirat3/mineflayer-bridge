import { BridgeError } from '@mineflayer-bridge/protocol';
import type { TextDisplay } from './display-types.ts';
import { defineExtension } from './extensions.ts';

/** Public API of `bridge:status-tag`, from `bot.bridge.get('bridge:status-tag')`. */
export interface StatusTagApi {
  /**
   * Shows lines above the bot, such as its task and health; an empty list or string hides the tag.
   * Unchanged lines send nothing.
   * @throws BridgeError `invalid_message` for more than 4 lines or a line over 64 characters.
   */
  set(lines: string | readonly string[]): void;
  /** Hides the tag. */
  clear(): void;
  /** The lines shown now. */
  readonly lines: readonly string[];
}

const MAX_LINES = 4;
const MAX_LINE = 64;

/**
 * `bridge:status-tag`: a few lines of text that float above the bot and follow it, such as what it
 * is doing. Built only on displays; the player can hide it with the bot's `status-tag` layer.
 * Needs a bot session.
 */
export const statusTag = defineExtension({
  id: 'bridge:status-tag',
  version: '1.0.0',
  options: {
    /** Height of the tag's bottom line above the bot's feet, in blocks; above the name tag by default. */
    height: { type: 'number', default: 2.55, min: 0, max: 16 },
    /** Hidden beyond this distance, in blocks. */
    maxDistance: { type: 'number', default: 48, min: 4, max: 256 },
    /** Shown through blocks. */
    seeThrough: { type: 'boolean', default: false },
  },
  setup(ctx, options): StatusTagApi {
    if (ctx.bot === null) throw new BridgeError('unsupported', 'bridge:status-tag needs a bot session');
    const layer = ctx.layer('status-tag', { category: 'labels' });
    let lines: readonly string[] = [];
    let text: TextDisplay | undefined;
    let group: ReturnType<typeof layer.group> | undefined;

    const clear = (): void => {
      group?.remove();
      group = undefined;
      text = undefined;
      lines = [];
    };

    return {
      set(input) {
        const next = typeof input === 'string' ? (input === '' ? [] : input.split('\n')) : [...input];
        if (next.length > MAX_LINES) throw new BridgeError('invalid_message', `a status tag shows at most ${MAX_LINES} lines`);
        for (const l of next) {
          if (typeof l !== 'string' || l.length > MAX_LINE) throw new BridgeError('invalid_message', `a status tag line is a string of at most ${MAX_LINE} characters`);
        }
        if (next.length === 0) return clear();
        if (next.length === lines.length && next.every((l, i) => l === lines[i])) return;
        lines = next;
        const joined = next.join('\n');
        if (text !== undefined) {
          text.update({ text: joined });
          return;
        }
        group = layer.group('tag', { follow: 'self', offset: [0, options.height, 0] });
        text = group.text('lines', [0, 0, 0], joined, { billboard: 'center', maxDistance: options.maxDistance, seeThrough: options.seeThrough, scale: 0.75 });
      },
      clear,
      get lines() {
        return lines;
      },
    };
  },
});
