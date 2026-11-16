import { BridgeError, type ChatSpan } from '@mineflayer-bridge/protocol';
import { defineExtension } from './extensions.ts';
import { ConsoleMessages, type ConsoleKind, type ConsoleLevel, type ConsoleLine } from './generated/bridge-console.ts';
import type { BossbarColor, BossbarHandle, BossbarOptions, BossbarOverlay, ChatSpanInput, HudPanel } from './input-types.ts';
import { spectate } from './spectate.ts';
import type { EmitterLike, Vec3Like } from './types.ts';
import { toVec3 } from './util.ts';
import { findChatLoader, legacyText, plainText, styledLegacy, styledText } from './window.ts';

/** A console line's level. */
export type ConsoleLevelName = ConsoleLevel;

/**
 * What a console line is: `log` for the bot's own lines, `chat` and `system` for the server's chat,
 * and `action_bar`, `title` and `boss_bar` for what the server put on the bot's screen.
 */
export type ConsoleKindName = 'log' | ConsoleKind;

/** One line of the bot's console. */
export interface ConsoleEntry {
  readonly time: number;
  readonly level: ConsoleLevelName;
  readonly kind: ConsoleKindName;
  readonly text: string;
  readonly pos: { readonly x: number; readonly y: number; readonly z: number } | undefined;
}

/** A boss bar the server shows the bot. */
export interface ConsoleBossBar {
  readonly title: string;
  /** 0 to 1. */
  readonly progress: number;
  readonly color: BossbarColor;
}

/** What is on the bot's screen right now, as plain text. */
export interface ConsoleScreen {
  /** The action bar line, while the game would still show it. */
  readonly actionBar: string | undefined;
  /** The title, while the game would still show it. */
  readonly title: string | undefined;
  /** The subtitle shown with {@link title}. */
  readonly subtitle: string | undefined;
  readonly bossBars: readonly ConsoleBossBar[];
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
  /** What the server shows on the bot's screen right now. */
  readonly screen: ConsoleScreen;
  /** Calls `fn` for each line the player types into the console. Returns a remover. */
  onInput(fn: (text: string) => void): () => void;
}

const MAX_TEXT = 1000;
/** Lines per message when the history is sent again. */
const REPLAY_CHUNK = 100;
/** A screen text (action bar, title, boss bar) that was logged is not logged again for this long. */
const REPEAT_MS = 30_000;
const SEEN_MAX = 64;
/** How long the game shows an action bar line. */
const ACTION_BAR_MS = 3000;
/** The same action bar line is passed on again after this long, as the server keeps it up. */
const ACTION_BAR_AGAIN_MS = 1000;
const MAX_BOSS_BARS = 8;
/** Longest text put on the player's screen. */
const MAX_SCREEN_TEXT = 256;
const BOSS_COLORS: ReadonlySet<string> = new Set(['pink', 'blue', 'red', 'green', 'yellow', 'purple', 'white']);
const BOSS_OVERLAYS: Readonly<Record<number, BossbarOverlay>> = { 6: 'notched_6', 10: 'notched_10', 12: 'notched_12', 20: 'notched_20' };

type Parts = (string | ChatSpanInput)[];

/** A text the server sent: plain, and in parts when it has colours or styles. */
interface ServerText {
  readonly text: string;
  readonly parts: Parts | undefined;
}

interface Kept extends ConsoleEntry {
  readonly seq: number;
  readonly parts: Parts | undefined;
}

interface Bar {
  title: ServerText;
  progress: number;
  color: BossbarColor;
  overlay: BossbarOverlay;
}

function partText(parts: Parts): string {
  return parts.map((p) => (typeof p === 'string' ? p : p.text)).join('');
}

function serverText(parts: Parts): ServerText {
  return { text: partText(parts), parts: parts.some((p) => typeof p !== 'string') ? parts : undefined };
}

function wireSpans(parts: Parts): ChatSpan[] {
  return parts.map((p) => (typeof p === 'string' ? { text: p } : ({ ...p } as ChatSpan)));
}

/** As a string the game draws: legacy codes for the styles, braces doubled so they are not read as variables. */
function drawn(t: ServerText, braces: boolean): string {
  const s = (t.parts === undefined ? t.text : legacyText(t.parts)).replace(/[\r\n]+/g, ' ');
  const cut = s.length > MAX_SCREEN_TEXT ? `${s.slice(0, MAX_SCREEN_TEXT - 3)}...` : s;
  return braces ? cut.replace(/[{}]/g, (c) => c + c) : cut;
}

/**
 * `bridge:console`: a per-bot log the player reads in game. Lines go to the mod in batches every
 * `flushMs` (default 100 ms) while the player has the bot attached; the mod keeps the last 2000 per
 * bot and shows them from the bot's row on Bridge home, with search, filters by level and kind, and
 * positions the player can mark as a waypoint. Lines the player types arrive through `onInput`;
 * with `echo` they are logged as `> text` first.
 *
 * It also records what the server tells the bot, with no code from the bot: `chat` logs chat and
 * system messages, `screen` logs the action bar, titles and boss bars (a text that keeps coming,
 * such as a HUD line with changing numbers, is logged once in 30 s), and `lifecycle` logs spawn,
 * death, kicked and error. All three are on by default. The plugin keeps the last `keep` lines
 * (default 500) for {@link BotConsoleApi.lines} and sends them again whenever the player attaches
 * the bot, so the console shows what happened before.
 *
 * While the player spectates the bot (`bridge:spectate`, loaded with this extension), the bot's
 * screen is shown to the player with the bot's name on it: its boss bars under the player's own,
 * its action bar line and titles where the game draws them, and its last chat lines in a HUD panel
 * (`chatRows` of them, 0 for no panel). Changes are passed on at most every `screenMs`. All of it
 * is removed when spectating ends.
 */
export const botConsole = defineExtension({
  id: 'bridge:console',
  version: '1.1.0',
  requires: { extensions: [spectate] },
  messages: [ConsoleMessages['bridge:console.lines'], ConsoleMessages['bridge:console.input']],
  options: {
    flushMs: { type: 'integer', default: 100, min: 0, max: 10_000 },
    keep: { type: 'integer', default: 500, min: 0, max: 100_000 },
    chat: { type: 'boolean', default: true },
    screen: { type: 'boolean', default: true },
    lifecycle: { type: 'boolean', default: true },
    echo: { type: 'boolean', default: true },
    screenMs: { type: 'integer', default: 200, min: 0, max: 10_000 },
    chatRows: { type: 'integer', default: 8, min: 0, max: 20 },
  },
  setup(ctx, options): BotConsoleApi {
    const channel = ctx.message(ConsoleMessages['bridge:console.lines']);
    const watching = ctx.require(spectate);
    const kept: Kept[] = [];
    let seq = 0;
    let pending: ConsoleLine[] = [];
    let timer: ReturnType<typeof setTimeout> | undefined;
    const inputs = new Set<(text: string) => void>();

    const flush = (): void => {
      timer = undefined;
      if (pending.length === 0 || ctx.signal.aborted) return;
      const lines = pending;
      pending = [];
      // The mod takes lines only from an attached bot; the history is sent when it attaches.
      if (ctx.state.attached) channel.send({ lines });
    };

    const wire = (e: Kept): ConsoleLine => {
      const line: ConsoleLine = { time: e.time, level: e.level, text: e.text };
      if (e.pos !== undefined) line.pos = e.pos;
      if (e.kind !== 'log') line.kind = e.kind;
      if (e.parts !== undefined) line.spans = wireSpans(e.parts);
      return line;
    };

    const add = (text: string, level: ConsoleLevelName, kind: ConsoleKindName, pos: Vec3Like | undefined, parts: Parts | undefined): void => {
      if (typeof text !== 'string') throw new BridgeError('invalid_message', 'console text must be a string');
      const at = pos === undefined ? undefined : toVec3(pos, 'pos');
      const long = text.length > MAX_TEXT;
      const entry: Kept = { seq: seq++, time: Date.now(), level, kind, text: long ? `${text.slice(0, MAX_TEXT - 3)}...` : text, pos: at, parts: long ? undefined : parts };
      if (options.keep > 0) {
        kept.push(entry);
        if (kept.length > options.keep) kept.splice(0, kept.length - options.keep);
      }
      if (kind === 'chat' || kind === 'system') {
        chatDirty = true;
        if (shown) schedule();
      }
      pending.push(wire(entry));
      if (options.flushMs === 0) flush();
      else if (timer === undefined) {
        timer = setTimeout(flush, options.flushMs);
        timer.unref?.();
      }
    };
    const log = (text: string, o: { level?: ConsoleLevelName; pos?: Vec3Like } = {}): void => add(text, o.level ?? 'info', 'log', o.pos, undefined);

    ctx.onAttached(() => {
      if (kept.length === 0) return;
      pending = [];
      for (let i = 0; i < kept.length; i += REPLAY_CHUNK) channel.send({ lines: kept.slice(i, i + REPLAY_CHUNK).map(wire), replay: i === 0 });
    });

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

    // The bot's screen: what the server shows it besides chat.
    let actionBar: { text: ServerText; at: number } | undefined;
    let title: { text: ServerText; at: number } | undefined;
    let subtitle: ServerText | undefined;
    let times = { fadeIn: 10, stay: 70, fadeOut: 20 };
    const bars = new Map<string, Bar>();
    const titleMs = (): number => (times.fadeIn + times.stay + times.fadeOut) * 50;
    const barVisible = (now: number): boolean => actionBar !== undefined && now - actionBar.at < ACTION_BAR_MS;
    const titleVisible = (now: number): boolean => title !== undefined && now - title.at < titleMs();

    // What the player sees of it while spectating.
    let shown = false;
    let screenTimer: ReturnType<typeof setTimeout> | undefined;
    let barDirty = false;
    let barSentAt = 0;
    let barSent = '';
    let titleDirty = false;
    /** A title arrived that the log has not seen. */
    let titleNew = false;
    let titleSent = false;
    let barsDirty = false;
    let chatDirty = false;
    const shownBars = new Map<string, { handle: BossbarHandle; options: Required<BossbarOptions> }>();
    let barIds = 0;
    let panel: HudPanel | undefined;
    const rows = new Set<number>();
    const name = (): string => ctx.bot?.username ?? ctx.session.identity?.username ?? 'bot';

    const seen = new Map<string, number>();
    /** Whether a screen text is news: not logged in the last while, numbers aside. */
    const news = (kind: ConsoleKind, text: string): boolean => {
      if (text.trim() === '') return false;
      const key = `${kind}:${text.replace(/\d+/g, '#')}`;
      const now = Date.now();
      const last = seen.get(key);
      if (last !== undefined && now - last < REPEAT_MS) return false;
      seen.delete(key);
      seen.set(key, now);
      if (seen.size > SEEN_MAX) seen.delete(seen.keys().next().value!);
      return true;
    };

    const syncBars = (): void => {
      for (const [id, s] of shownBars) {
        if (!shown || !bars.has(id)) {
          s.handle.remove();
          shownBars.delete(id);
        }
      }
      if (!shown) return;
      for (const [id, bar] of bars) {
        const next: Required<BossbarOptions> = { text: `[${name()}] ${drawn(bar.title, true)}`, progress: bar.progress, color: bar.color, overlay: bar.overlay };
        const s = shownBars.get(id);
        if (s === undefined) {
          shownBars.set(id, { handle: ctx.bossbar(`screen-${barIds++}`, next), options: next });
          continue;
        }
        const patch: Partial<BossbarOptions> = {};
        if (next.text !== s.options.text) patch.text = next.text;
        if (next.progress !== s.options.progress) patch.progress = next.progress;
        if (next.color !== s.options.color) patch.color = next.color;
        if (next.overlay !== s.options.overlay) patch.overlay = next.overlay;
        if (Object.keys(patch).length > 0) {
          s.handle.update(patch);
          s.options = next;
        }
      }
    };

    const syncChat = (): void => {
      if (!shown || options.chatRows === 0) return;
      const last: Kept[] = [];
      for (let i = kept.length - 1; i >= 0 && last.length < options.chatRows; i--) {
        const k = kept[i]!;
        if (k.kind === 'chat' || k.kind === 'system') last.unshift(k);
      }
      panel ??= ctx.hud('chat', { title: 'Chat', anchor: 'bottom-right' });
      const want = new Set(last.map((k) => k.seq));
      for (const s of rows) {
        if (!want.has(s)) {
          panel.remove(`l${s}`);
          rows.delete(s);
        }
      }
      for (const k of last) {
        if (rows.has(k.seq)) continue;
        panel.text(`l${k.seq}`, drawn(k, true), { order: k.seq, maxLines: 4 });
        rows.add(k.seq);
      }
    };

    const sync = (): void => {
      screenTimer = undefined;
      if (ctx.signal.aborted) return;
      const now = Date.now();
      if (titleNew) {
        titleNew = false;
        if (title !== undefined) {
          const sub = subtitle === undefined || subtitle.text === '' ? '' : ` | ${subtitle.text}`;
          if (news('title', title.text.text + sub)) add(title.text.text + sub, 'info', 'title', undefined, undefined);
        }
      }
      if (titleDirty) {
        titleDirty = false;
        if (title !== undefined) {
          if (shown && titleVisible(now)) {
            // What is left of the title's time when the player joins late.
            const gone = Math.round((now - title.at) / 50);
            const stay = Math.max(0, times.stay - Math.max(0, gone - times.fadeIn));
            ctx.effects.title(drawn(title.text, false), {
              subtitle: subtitle === undefined || subtitle.text === '' ? `[${name()}]` : `[${name()}] ${drawn(subtitle, false)}`,
              fadeIn: gone > 0 ? 0 : times.fadeIn,
              stay,
              fadeOut: times.fadeOut,
            });
            titleSent = true;
          }
        }
      }
      if (barDirty) {
        barDirty = false;
        if (shown && actionBar !== undefined && barVisible(now) && actionBar.text.text.trim() !== '') {
          const text = drawn(actionBar.text, false);
          if (text !== barSent || now - barSentAt >= ACTION_BAR_AGAIN_MS) {
            ctx.effects.actionBar(text);
            barSent = text;
            barSentAt = now;
          }
        }
      }
      if (barsDirty) {
        barsDirty = false;
        syncBars();
      }
      if (chatDirty) {
        chatDirty = false;
        syncChat();
      }
    };
    const guarded = (): void => {
      try {
        sync();
      } catch (e) {
        ctx.log.debug('bridge:console could not show the screen', { error: String(e) });
      }
    };
    const schedule = (): void => {
      if (screenTimer !== undefined || ctx.signal.aborted) return;
      if (options.screenMs === 0) return guarded();
      screenTimer = setTimeout(guarded, options.screenMs);
      screenTimer.unref?.();
    };

    const show = (on: boolean): void => {
      if (on === shown) return;
      shown = on;
      if (on) {
        barDirty = titleDirty = barsDirty = chatDirty = true;
        barSent = '';
        return schedule();
      }
      try {
        syncBars();
        panel?.destroy();
        if (titleSent && titleVisible(Date.now())) ctx.effects.title('', { subtitle: '', fadeIn: 0, stay: 0, fadeOut: 0 });
      } catch (e) {
        ctx.log.debug('bridge:console could not clear the screen', { error: String(e) });
      }
      panel = undefined;
      rows.clear();
      titleSent = false;
    };
    const offWatching = watching.on(show);

    ctx.signal.addEventListener(
      'abort',
      () => {
        if (timer !== undefined) clearTimeout(timer);
        if (screenTimer !== undefined) clearTimeout(screenTimer);
        offWatching();
      },
      { once: true },
    );

    const bot = ctx.bot;
    if (bot !== null) {
      const at = (): Vec3Like | undefined => bot.entity?.position;
      const withRegistry = bot as { registry?: unknown };
      let chat: ReturnType<NonNullable<ReturnType<typeof findChatLoader>>> | null | undefined;
      /** A text component from a packet or from mineflayer, read as the bot's version does. */
      const read = (value: unknown): ServerText => {
        try {
          if (typeof value === 'object' && value !== null && typeof (value as { toMotd?: unknown }).toMotd === 'function') {
            return serverText(styledLegacy((value as { toMotd(): string }).toMotd()));
          }
          if (chat === undefined) {
            const loader = withRegistry.registry === undefined || withRegistry.registry === null ? null : findChatLoader();
            chat = loader === null ? null : loader(withRegistry.registry);
          }
          const motd = chat?.fromNotch(value).toMotd?.();
          if (motd !== undefined) return serverText(styledLegacy(motd));
        } catch {
          // read below without the bot's translations
        }
        if (typeof value === 'string' && !/^\s*[[{"]/.test(value)) return serverText(styledLegacy(value));
        const parts = styledText(value);
        return parts === undefined ? { text: plainText(value) ?? '', parts: undefined } : serverText(parts);
      };

      // The action bar can reach here twice for one packet: from the packet and from an event made of it.
      let turnText: string | undefined;
      const setActionBar = (t: ServerText): void => {
        if (turnText === t.text) return;
        if (turnText === undefined) setImmediate(() => (turnText = undefined));
        turnText = t.text;
        actionBar = { text: t, at: Date.now() };
        if (news('action_bar', t.text)) add(t.text, 'info', 'action_bar', undefined, t.parts);
        barDirty = true;
        if (shown) schedule();
      };

      if (options.chat || options.screen) {
        ctx.on(bot, 'messagestr', (message: unknown, position: unknown, json: unknown) => {
          if (position === 'game_info') {
            if (options.screen) setActionBar(json === undefined || json === null ? serverText(styledLegacy(String(message))) : read(json));
            return;
          }
          if (!options.chat) return;
          const t = json === undefined || json === null ? undefined : read(json);
          add(String(message), 'info', position === 'chat' ? 'chat' : 'system', undefined, t?.parts);
        });
      }

      if (options.screen) {
        const client: EmitterLike | undefined = bot._client;
        if (client !== undefined) {
          // mineflayer has no listener for the action bar's own packet, and gives titles without their style.
          ctx.on(client, 'action_bar', (p: { text?: unknown }) => setActionBar(read(p?.text)));
          ctx.on(client, 'set_title_text', (p: { text?: unknown }) => {
            title = { text: read(p?.text), at: Date.now() };
            titleDirty = titleNew = true;
            schedule();
          });
          ctx.on(client, 'set_title_subtitle', (p: { text?: unknown }) => {
            subtitle = read(p?.text);
            if (titleVisible(Date.now())) {
              titleDirty = titleNew = true;
              schedule();
            }
          });
          ctx.on(client, 'set_title_time', (p: { fadeIn?: unknown; stay?: unknown; fadeOut?: unknown }) => {
            if (typeof p?.fadeIn === 'number' && typeof p.stay === 'number' && typeof p.fadeOut === 'number') {
              times = { fadeIn: Math.max(0, p.fadeIn), stay: Math.max(0, p.stay), fadeOut: Math.max(0, p.fadeOut) };
            }
          });
          ctx.on(client, 'clear_titles', (p: { reset?: unknown }) => {
            const was = titleSent && titleVisible(Date.now());
            title = undefined;
            if (p?.reset === true) {
              subtitle = undefined;
              times = { fadeIn: 10, stay: 70, fadeOut: 20 };
            }
            if (shown && was) {
              titleSent = false;
              try {
                ctx.effects.title('', { subtitle: '', fadeIn: 0, stay: 0, fadeOut: 0 });
              } catch {
                // nothing to clear then
              }
            }
          });
        }

        const bar = (b: { entityUUID?: unknown; title?: unknown; health?: unknown; color?: unknown; dividers?: unknown } | undefined): void => {
          if (b === undefined || b === null || typeof b.entityUUID !== 'string') return;
          const known = bars.get(b.entityUUID);
          if (known === undefined && bars.size >= MAX_BOSS_BARS) return;
          const t = read(b.title);
          const health = typeof b.health === 'number' && Number.isFinite(b.health) ? Math.min(1, Math.max(0, b.health)) : 0;
          bars.set(b.entityUUID, {
            title: t,
            progress: health,
            color: typeof b.color === 'string' && BOSS_COLORS.has(b.color) ? (b.color as BossbarColor) : 'white',
            overlay: (typeof b.dividers === 'number' ? BOSS_OVERLAYS[b.dividers] : undefined) ?? 'progress',
          });
          if (known?.title.text !== t.text && news('boss_bar', t.text)) add(t.text, 'info', 'boss_bar', undefined, t.parts);
          barsDirty = true;
          if (shown) schedule();
        };
        ctx.on(bot, 'bossBarCreated', bar);
        ctx.on(bot, 'bossBarUpdated', bar);
        ctx.on(bot, 'bossBarDeleted', (b: { entityUUID?: unknown } | undefined) => {
          if (typeof b?.entityUUID !== 'string' || !bars.delete(b.entityUUID)) return;
          barsDirty = true;
          if (shown) schedule();
        });
        // A new server starts with an empty screen.
        ctx.on(bot, 'login', () => {
          actionBar = undefined;
          title = undefined;
          subtitle = undefined;
          if (bars.size > 0) {
            bars.clear();
            barsDirty = true;
            if (shown) schedule();
          }
        });
      }

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
        return kept.map(({ time, level, kind, text, pos }) => ({ time, level, kind, text, pos }));
      },
      get screen() {
        const now = Date.now();
        const titled = titleVisible(now);
        return {
          actionBar: barVisible(now) && actionBar!.text.text !== '' ? actionBar!.text.text : undefined,
          title: titled ? title!.text.text : undefined,
          subtitle: titled && subtitle !== undefined && subtitle.text !== '' ? subtitle.text : undefined,
          bossBars: [...bars.values()].map((b) => ({ title: b.title.text, progress: b.progress, color: b.color })),
        };
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
