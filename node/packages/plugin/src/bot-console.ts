import { BridgeError } from '@mineflayer-bridge/protocol';
import { defineExtension } from './extensions.ts';
import { ConsoleMessages, type ConsoleLevel, type ConsoleLine } from './generated/bridge-console.ts';
import type { Vec3Like } from './types.ts';
import { toVec3 } from './util.ts';

/** A console line's level. */
export type ConsoleLevelName = ConsoleLevel;

/** One line of the bot's console. */
export interface ConsoleEntry {
  readonly time: number;
  readonly level: ConsoleLevelName;
  readonly text: string;
  readonly pos: { readonly x: number; readonly y: number; readonly z: number } | undefined;
}

/** Public API of `bridge:console`, from `bot.bridge.get('bridge:console')`. */
export interface BotConsoleApi {
  /**
   * Adds a line. `pos` names a position the line is about; the player can mark it from the console.
   * @throws BridgeError `invalid_message` for a non-string text or a bad position.
   */
  log(text: string, options?: { level?: ConsoleLevelName; pos?: Vec3Like }): void;
  debug(text: string, pos?: Vec3Like): void;
  info(text: string, pos?: Vec3Like): void;
  warn(text: string, pos?: Vec3Like): void;
  error(text: string, pos?: Vec3Like): void;
  /** The last lines, oldest first, up to `keep`. */
  readonly lines: readonly ConsoleEntry[];
  /** Calls `fn` for each line the player types into the console. Returns a remover. */
  onInput(fn: (text: string) => void): () => void;
}

const MAX_TEXT = 1000;

/**
 * `bridge:console`: a per-bot log the player reads in game. Lines go to the mod in batches every
 * `flushMs` (default 100 ms) while the session is open; the mod keeps the last 2000 per bot and
 * shows them from the bot's row on Bridge home, with search, a level filter and positions the
 * player can mark as a waypoint. Lines the player types arrive through `onInput`; with `echo` they
 * are logged as `> text` first.
 *
 * `capture` logs mineflayer events: `chat` (each message string as info), `lifecycle` (spawn, death,
 * kicked and error), both on by default. The plugin keeps the last `keep` lines (default 500) for
 * {@link BotConsoleApi.lines}.
 */
export const botConsole = defineExtension({
  id: 'bridge:console',
  version: '1.0.0',
  messages: [ConsoleMessages['bridge:console.lines'], ConsoleMessages['bridge:console.input']],
  options: {
    flushMs: { type: 'integer', default: 100, min: 0, max: 10_000 },
    keep: { type: 'integer', default: 500, min: 0, max: 100_000 },
    chat: { type: 'boolean', default: true },
    lifecycle: { type: 'boolean', default: true },
    echo: { type: 'boolean', default: true },
  },
  setup(ctx, options): BotConsoleApi {
    const channel = ctx.message(ConsoleMessages['bridge:console.lines']);
    const kept: ConsoleEntry[] = [];
    let pending: ConsoleLine[] = [];
    let timer: ReturnType<typeof setTimeout> | undefined;
    const inputs = new Set<(text: string) => void>();

    const flush = (): void => {
      timer = undefined;
      if (pending.length === 0 || ctx.signal.aborted) return;
      const lines = pending;
      pending = [];
      channel.send({ lines });
    };

    const log = (text: string, o: { level?: ConsoleLevelName; pos?: Vec3Like } = {}): void => {
      if (typeof text !== 'string') throw new BridgeError('invalid_message', 'console text must be a string');
      const level = o.level ?? 'info';
      const pos = o.pos === undefined ? undefined : toVec3(o.pos, 'pos');
      const entry: ConsoleEntry = { time: Date.now(), level, text: text.length > MAX_TEXT ? `${text.slice(0, MAX_TEXT - 3)}...` : text, pos };
      if (options.keep > 0) {
        kept.push(entry);
        if (kept.length > options.keep) kept.splice(0, kept.length - options.keep);
      }
      const line: ConsoleLine = { time: entry.time, level, text: entry.text };
      if (pos !== undefined) line.pos = pos;
      pending.push(line);
      if (options.flushMs === 0) flush();
      else if (timer === undefined) {
        timer = setTimeout(flush, options.flushMs);
        timer.unref?.();
      }
    };
    ctx.signal.addEventListener('abort', () => {
      if (timer !== undefined) clearTimeout(timer);
    }, { once: true });

    ctx.message(ConsoleMessages['bridge:console.input']).on(({ text }) => {
      if (options.echo) log(`> ${text}`);
      for (const fn of [...inputs]) {
        try {
          fn(text);
        } catch (e) {
          log(`input handler failed: ${String(e)}`, { level: 'error' });
        }
      }
    });

    const bot = ctx.bot;
    if (bot !== null) {
      const at = (): Vec3Like | undefined => bot.entity?.position;
      if (options.chat) ctx.on(bot, 'messagestr', (message: unknown) => log(String(message)));
      if (options.lifecycle) {
        ctx.on(bot, 'spawn', () => log('Spawned', posOpt(at())));
        ctx.on(bot, 'death', () => log('Died', { level: 'warn', ...posOpt(at()) }));
        ctx.on(bot, 'kicked', (reason: unknown) => log(`Kicked: ${typeof reason === 'string' ? reason : JSON.stringify(reason)}`, { level: 'error' }));
        ctx.on(bot, 'error', (err: unknown) => log(`Error: ${err instanceof Error ? err.message : String(err)}`, { level: 'error' }));
      }
    }

    return {
      log,
      debug: (text, pos) => log(text, { level: 'debug', ...posOpt(pos) }),
      info: (text, pos) => log(text, { level: 'info', ...posOpt(pos) }),
      warn: (text, pos) => log(text, { level: 'warn', ...posOpt(pos) }),
      error: (text, pos) => log(text, { level: 'error', ...posOpt(pos) }),
      get lines() {
        return [...kept];
      },
      onInput(fn) {
        inputs.add(fn);
        return () => {
          inputs.delete(fn);
        };
      },
    };
  },
});

function posOpt(pos: Vec3Like | undefined): { pos?: Vec3Like } {
  return pos === undefined ? {} : { pos };
}
