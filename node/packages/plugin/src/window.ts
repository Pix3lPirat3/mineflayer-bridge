import { createRequire } from 'node:module';
import { BridgeError } from '@mineflayer-bridge/protocol';
import { defineExtension } from './extensions.ts';
import { itemId } from './ext-util.ts';
import type { ChatContent, ChatSpanInput, ContainerClick, ContainerEvent, ContainerItem, ContainerLayout, ContainerView, MineflayerItemLike } from './input-types.ts';
import type { EmitterLike, InventoryLike } from './types.ts';

/** First slot of the hotbar in a mineflayer inventory window. */
const HOTBAR_START = 36;
/** Slots of a mineflayer inventory window, all shown by the `player_inventory` layout. */
const INVENTORY_SLOTS = 46;
/** Slots of the player inventory at the end of every other window. */
const PLAYER_SLOTS = 36;
/** The largest chest layout. */
const MAX_SLOTS = 54;

/** What {@link WindowApi.open} shows: the bot's open window, or its inventory. */
export type WindowTarget = 'window' | 'inventory';

/** The parts of a mineflayer (prismarine-windows) `Window` that `bridge:window` reads. */
export interface WindowLike extends InventoryLike {
  /** The title as the server sent it: plain text or a text component. */
  readonly title?: unknown;
  /** The window type, such as `minecraft:generic_9x3` or `minecraft:hopper`. */
  readonly type?: unknown;
  /** Index of the first player-inventory slot; the slots before it are the container's own. */
  readonly inventoryStart?: number;
}

/** A mineflayer item with the display fields `bridge:window` shows. */
export interface WindowItemLike extends Omit<MineflayerItemLike, 'customName'> {
  /** Custom name: plain text or a text component. */
  readonly customName?: unknown;
  /** Lore lines: plain text or text components. */
  readonly customLore?: unknown;
  /** Enchantments; an item with any gets the glint. */
  readonly enchants?: unknown;
  /** Data components (prismarine-item on 1.20.5+): a `profile` one gives a head its skin. */
  readonly components?: unknown;
  /** Item NBT on older versions: `SkullOwner` gives a head its skin. */
  readonly nbt?: unknown;
}

/** The parts of a bot `bridge:window` uses besides `bot.inventory`. */
export interface WindowBotLike {
  /** The window the bot has open, or `null`. Read at every `open('window')`. */
  readonly currentWindow?: WindowLike | null;
  /** Clicks a slot of the open window, or of the inventory when none is open (mineflayer `bot.clickWindow`). */
  clickWindow?(slot: number, mouseButton: number, mode: number): Promise<void>;
  /** Closes a window (mineflayer `bot.closeWindow`). */
  closeWindow?(window: never): void;
  /** The bot's minecraft-data registry, used to read text components when prismarine-chat is installed. */
  readonly registry?: unknown;
}

/** Public API of `bridge:window`, from `bot.bridge.get(windowMirror)`. */
export interface WindowApi {
  /** The container view at `bridge:window/~view/window`. */
  readonly view: ContainerView;
  /** What the view mirrors now, or `undefined` while it follows nothing. */
  readonly showing: WindowTarget | undefined;
  /**
   * Shows the bot's open window (the default), or its inventory, and opens the view for the player.
   * With `window` and no window open it shows the inventory. Returns what it shows.
   */
  open(what?: WindowTarget): WindowTarget;
  /** Closes the view and stops following the window. The bot's window stays open. */
  close(): void;
  /** Copies every slot of the mirrored window into the view again. */
  refresh(): void;
}

/**
 * The `bot.clickWindow` mouse button and mode for a gesture in a container view, as the vanilla
 * client sends them: mode 0 plain clicks, 1 shift-clicks, 2 number keys (the button is the hotbar
 * slot), 3 middle click, 4 drop (button 1 for the whole stack), 6 double click.
 */
export function clickArgs(click: ContainerClick, hotbar?: number): { button: number; mode: number } {
  switch (click) {
    case 'left':
      return { button: 0, mode: 0 };
    case 'right':
      return { button: 1, mode: 0 };
    case 'shift-left':
      return { button: 0, mode: 1 };
    case 'shift-right':
      return { button: 1, mode: 1 };
    case 'middle':
      return { button: 2, mode: 3 };
    case 'number':
      return { button: hotbar ?? 0, mode: 2 };
    case 'drop':
      return { button: 0, mode: 4 };
    case 'drop-all':
      return { button: 1, mode: 4 };
    case 'double':
      return { button: 0, mode: 6 };
  }
}

/**
 * The container view layout for a window with `size` slots of its own and the given window type:
 * `hopper` for a 5-slot hopper, `dispenser` for a 3x3 window, otherwise the chest with enough rows
 * (at most 6; slots beyond 54 are not shown).
 */
export function layoutFor(size: number, type = ''): ContainerLayout {
  if (/hopper/.test(type) && size === 5) return 'hopper';
  if (/3x3|dispenser|dropper/.test(type) && size === 9) return 'dispenser';
  return `chest:${Math.min(6, Math.max(1, Math.ceil(size / 9)))}`;
}

/** NBT tags as prismarine-nbt holds them (`{ type, value }`) reduced to their values. */
function untagged(v: unknown, depth = 0): unknown {
  if (depth > 32 || typeof v !== 'object' || v === null) return v;
  if (Array.isArray(v)) return v.map((x) => untagged(x, depth + 1));
  const o = v as Record<string, unknown>;
  const keys = Object.keys(o);
  if (typeof o['type'] === 'string' && 'value' in o && keys.every((k) => k === 'type' || k === 'value' || k === 'name')) {
    // A list tag nests its element type: { type: 'list', value: { type, value: [...] } }.
    return untagged(o['value'], depth + 1);
  }
  return Object.fromEntries(keys.map((k) => [k, untagged(o[k], depth + 1)]));
}

function flatten(v: unknown, depth = 0): string {
  if (depth > 32) return '';
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  if (Array.isArray(v)) return v.map((x) => flatten(x, depth + 1)).join('');
  if (typeof v !== 'object' || v === null) return '';
  const o = v as Record<string, unknown>;
  const own = typeof o['text'] === 'string' ? o['text'] : typeof o[''] === 'string' ? o[''] : typeof o['fallback'] === 'string' ? o['fallback'] : typeof o['translate'] === 'string' ? o['translate'] : '';
  return own + (o['extra'] === undefined ? '' : flatten(o['extra'], depth + 1));
}

/**
 * The text of a chat component without its formatting, for bots whose text cannot go through
 * prismarine-chat: a plain string stays as it is, a JSON or NBT component gives its `text` and
 * `extra` parts joined, and a translation gives its fallback or key. `null` and `undefined` give
 * `undefined`.
 */
export function plainText(value: unknown): string | undefined {
  if (value === null || value === undefined) return undefined;
  if (typeof value === 'string') {
    if (!/^\s*[[{"]/.test(value)) return value;
    try {
      return flatten(JSON.parse(value));
    } catch {
      return value;
    }
  }
  return flatten(untagged(value));
}

/** prismarine-chat's loader: called with a registry, it gives the `ChatMessage` class. */
export type ChatLoader = (registry: unknown) => { fromNotch(message: unknown): { toString(): string; toMotd?(): string } };

let foundLoader: ChatLoader | null | undefined;

/**
 * prismarine-chat as the bot's mineflayer has it, or `null` when neither it nor mineflayer can be
 * found from here. The plugin does not depend on either.
 */
export function findChatLoader(): ChatLoader | null {
  if (foundLoader !== undefined) return foundLoader;
  const require = createRequire(import.meta.url);
  for (const load of [() => require('prismarine-chat'), () => createRequire(require.resolve('mineflayer'))('prismarine-chat')]) {
    try {
      const loader: unknown = load();
      if (typeof loader === 'function') return (foundLoader = loader as ChatLoader);
    } catch {
      // not installed where this can see it
    }
  }
  return (foundLoader = null);
}

/**
 * A function that turns item names, lore lines and window titles into strings. With a registry and
 * prismarine-chat it reads them as the bot's version does (translations included); without, or when
 * that fails, it falls back to {@link plainText}.
 */
export function textReader(registry: unknown, loader: ChatLoader | null = findChatLoader()): (value: unknown) => string | undefined {
  let chat: ReturnType<ChatLoader> | undefined;
  if (registry !== undefined && registry !== null && loader !== null) {
    try {
      chat = loader(registry);
    } catch {
      chat = undefined;
    }
  }
  return (value) => {
    if (value === null || value === undefined) return undefined;
    if (chat === undefined || (typeof value === 'string' && !/^\s*[[{"]/.test(value))) return plainText(value);
    try {
      return chat.fromNotch(value).toString();
    } catch {
      return plainText(value);
    }
  };
}

/** The 16 named chat colours, as `§0` to `§f` give them. */
const NAMED_COLORS: Readonly<Record<string, number>> = {
  black: 0x000000,
  dark_blue: 0x0000aa,
  dark_green: 0x00aa00,
  dark_aqua: 0x00aaaa,
  dark_red: 0xaa0000,
  dark_purple: 0xaa00aa,
  gold: 0xffaa00,
  gray: 0xaaaaaa,
  dark_gray: 0x555555,
  blue: 0x5555ff,
  green: 0x55ff55,
  aqua: 0x55ffff,
  red: 0xff5555,
  light_purple: 0xff55ff,
  yellow: 0xffff55,
  white: 0xffffff,
};
const CODE_COLORS = Object.values(NAMED_COLORS);

/** A text style as chat components carry it, for {@link styledText}; a field left out is inherited. */
export interface TextStyle {
  color?: number;
  bold?: boolean;
  italic?: boolean;
  underlined?: boolean;
  strikethrough?: boolean;
  obfuscated?: boolean;
}

const FLAGS = ['bold', 'italic', 'underlined', 'strikethrough', 'obfuscated'] as const;
const FORMAT_CODES: Readonly<Record<string, (typeof FLAGS)[number]>> = { k: 'obfuscated', l: 'bold', m: 'strikethrough', n: 'underlined', o: 'italic' };
const SECTION = '§';

function colorOf(v: unknown): number | undefined {
  if (typeof v !== 'string') return undefined;
  if (/^#[0-9a-fA-F]{6}$/.test(v)) return parseInt(v.slice(1), 16);
  return NAMED_COLORS[v];
}

/** NBT booleans are bytes: 0 or 1. */
function flagOf(v: unknown): boolean | undefined {
  if (v === undefined || v === null) return undefined;
  return v === true || v === 1 || v === '1' || v === 'true';
}

type Run = { text: string; style: TextStyle };

/** Text with legacy `§` codes in it, split into styled runs. */
function legacyRuns(text: string, base: TextStyle, out: Run[]): void {
  let style: TextStyle = { ...base };
  let run = '';
  for (let i = 0; i < text.length; i++) {
    const c = text[i]!;
    if (c === SECTION && text[i + 1] === '#' && /^[0-9a-fA-F]{6}$/.test(text.slice(i + 2, i + 8))) {
      // A colour by its hex value, as prismarine-chat writes the ones that have no code.
      if (run !== '') out.push({ text: run, style });
      run = '';
      style = { ...base, color: parseInt(text.slice(i + 2, i + 8), 16), bold: false, italic: false, underlined: false, strikethrough: false, obfuscated: false };
      i += 7;
      continue;
    }
    if (c === SECTION && i + 1 < text.length) {
      const k = text[i + 1]!.toLowerCase();
      const hex = '0123456789abcdef'.indexOf(k);
      if (hex >= 0 || k === 'r' || FORMAT_CODES[k] !== undefined) {
        if (run !== '') out.push({ text: run, style });
        run = '';
        i++;
        // A colour code resets the formatting after it, as in vanilla.
        if (hex >= 0) style = { ...base, color: CODE_COLORS[hex]!, bold: false, italic: false, underlined: false, strikethrough: false, obfuscated: false };
        else if (k === 'r') style = { ...base };
        else style = { ...style, [FORMAT_CODES[k]!]: true };
        continue;
      }
    }
    run += c;
  }
  if (run !== '') out.push({ text: run, style });
}

function styleOf(o: Record<string, unknown>, parent: TextStyle): TextStyle {
  const s: TextStyle = { ...parent };
  const c = colorOf(o['color']);
  if (c !== undefined) s.color = c;
  for (const f of FLAGS) {
    const b = flagOf(o[f]);
    if (b !== undefined) s[f] = b;
  }
  return s;
}

function walk(v: unknown, parent: TextStyle, out: Run[], depth: number): void {
  if (depth > 32) return;
  if (typeof v === 'string') return legacyRuns(v, parent, out);
  if (typeof v === 'number' || typeof v === 'boolean') {
    out.push({ text: String(v), style: parent });
    return;
  }
  if (Array.isArray(v)) {
    // A list is a component whose first entry styles the rest.
    if (v.length === 0) return;
    const first = v[0];
    const own = typeof first === 'object' && first !== null && !Array.isArray(first) ? styleOf(first as Record<string, unknown>, parent) : parent;
    walk(first, parent, out, depth + 1);
    for (const x of v.slice(1)) walk(x, own, out, depth + 1);
    return;
  }
  if (typeof v !== 'object' || v === null) return;
  const o = v as Record<string, unknown>;
  const style = styleOf(o, parent);
  const own = ['text', '', 'fallback', 'translate', 'keybind'].map((k) => o[k]).find((x): x is string => typeof x === 'string' && x !== '') ?? '';
  if (own !== '') legacyRuns(own, style, out);
  if (Array.isArray(o['extra'])) for (const x of o['extra']) walk(x, style, out, depth + 1);
}

/**
 * A chat component as the styled parts container views and chat take: colours, bold, italic,
 * underline, strikethrough and obfuscated text from a JSON or NBT component, or from legacy `§`
 * codes in plain text. `base` is the style vanilla starts from, such as italics for item names.
 * Parts without any style are plain strings; an empty component gives one empty string.
 * `undefined` for `null` and `undefined`.
 */
export function styledText(value: unknown, base: Readonly<TextStyle> = {}): (string | ChatSpanInput)[] | undefined {
  if (value === null || value === undefined) return undefined;
  let v: unknown = value;
  if (typeof v === 'string' && /^\s*[[{"]/.test(v)) {
    try {
      v = JSON.parse(v);
    } catch {
      // plain text after all
    }
  } else if (typeof v === 'object') v = untagged(v);
  const runs: Run[] = [];
  walk(v, { ...base }, runs, 0);
  return toParts(runs);
}

function toParts(runs: Run[]): (string | ChatSpanInput)[] {
  if (runs.length === 0) return [''];
  return runs.map(({ text, style }) => {
    const span: ChatSpanInput = { text };
    if (style.color !== undefined) span.color = style.color;
    for (const f of FLAGS) if (style[f] === true) span[f] = true;
    return Object.keys(span).length === 1 ? text : span;
  });
}

/** Text with legacy `§` codes (and `§#rrggbb` colours) as styled parts; never read as JSON. */
export function styledLegacy(text: string): (string | ChatSpanInput)[] {
  const runs: Run[] = [];
  legacyRuns(text, {}, runs);
  return toParts(runs);
}

/** The code of the named colour closest to an RGB value. */
function nearestCode(rgb: number): string {
  let best = 15;
  let bestD = Infinity;
  for (let i = 0; i < CODE_COLORS.length; i++) {
    const c = CODE_COLORS[i]!;
    const d = ((c >> 16) - (rgb >> 16 & 255)) ** 2 + ((c >> 8 & 255) - (rgb >> 8 & 255)) ** 2 + ((c & 255) - (rgb & 255)) ** 2;
    if (d < bestD) {
      bestD = d;
      best = i;
    }
  }
  return '0123456789abcdef'[best]!;
}

/**
 * Styled parts as one string with legacy `§` codes, for text the game draws from a plain string
 * (HUD text, boss bars, titles). Colours without a code become the closest named colour.
 */
export function legacyText(parts: readonly (string | ChatSpanInput)[]): string {
  let out = '';
  let styled = false;
  for (const p of parts) {
    if (typeof p === 'string') {
      out += (styled ? `${SECTION}r` : '') + p;
      styled = false;
      continue;
    }
    let codes = styled ? `${SECTION}r` : '';
    if (typeof p.color === 'number') codes += SECTION + nearestCode(p.color);
    for (const [code, flag] of Object.entries(FORMAT_CODES)) if (p[flag] === true) codes += SECTION + code;
    out += codes + p.text;
    styled = true;
  }
  return out;
}

/** Vanilla shows custom names in italics, and lore lines in purple italics, unless they say otherwise. */
const NAME_BASE: TextStyle = { italic: true };
const LORE_BASE: TextStyle = { italic: true, color: NAMED_COLORS['dark_purple']! };

/** Styled parts as one ChatContent: a lone plain string stays a string. */
function content(parts: (string | ChatSpanInput)[]): ChatContent {
  return parts.length === 1 && typeof parts[0] === 'string' ? parts[0] : parts;
}

/** The base64 `textures` value a head carries: from its `profile` component, or old `SkullOwner` NBT. */
export function headTexture(item: WindowItemLike): string | undefined {
  const find = (v: unknown, depth = 0): string | undefined => {
    if (depth > 8 || typeof v !== 'object' || v === null) return undefined;
    const o = v as Record<string, unknown>;
    if (Array.isArray(o['properties'])) {
      for (const p of o['properties'] as unknown[]) {
        const q = untagged(p) as Record<string, unknown> | null;
        if (typeof q === 'object' && q !== null && q['name'] === 'textures' && typeof q['value'] === 'string' && q['value'] !== '') return q['value'];
      }
    }
    const tex = (o['Properties'] as Record<string, unknown> | undefined)?.['textures'];
    const first = Array.isArray(tex) ? (tex[0] as Record<string, unknown> | undefined) : undefined;
    if (typeof first?.['Value'] === 'string' && first['Value'] !== '') return first['Value'];
    for (const x of Object.values(o)) {
      const r = find(x, depth + 1);
      if (r !== undefined) return r;
    }
    return undefined;
  };
  if (Array.isArray(item.components)) {
    for (const c of item.components as { type?: unknown; data?: unknown }[]) if (c?.type === 'profile') return find(untagged(c.data));
  }
  if (item.nbt === undefined || item.nbt === null) return undefined;
  return find((untagged(item.nbt) as Record<string, unknown> | null)?.['SkullOwner']);
}

/**
 * A mineflayer item as a container view shows it: id, count, the custom name and lore with their
 * colours and styles (empty lore lines are kept: servers use them as spacers), a head's skin, and
 * the glint for an enchanted item. `null` for an empty slot.
 */
export function windowItem(item: WindowItemLike | null | undefined): ContainerItem | null {
  if (item === null || item === undefined) return null;
  const name = styledText(item.customName, NAME_BASE);
  const lore = Array.isArray(item.customLore) ? item.customLore.map((l) => styledText(l, LORE_BASE) ?? ['']) : [];
  const texture = item.name === 'player_head' ? headTexture(item) : undefined;
  const blank = name === undefined || name.every((p) => (typeof p === 'string' ? p : p.text) === '');
  return {
    id: itemId(item.name),
    count: item.count,
    ...(blank ? {} : { name: content(name) }),
    ...(lore.length === 0 ? {} : { lore: lore.map(content) }),
    ...(texture === undefined ? {} : { headTexture: texture }),
    ...(Array.isArray(item.enchants) && item.enchants.length > 0 ? { glint: true } : {}),
  };
}

/**
 * `bridge:window`: shows a window of the bot in the player's game as a container view. `open()`
 * mirrors the window the bot has open (a chest, a hopper, a server's selector menu), or with
 * `'inventory'`, or when nothing is open, `bot.inventory`. The view is kept live through the
 * window's `updateSlot` events and shows item names and lore as text.
 *
 * - The player's clicks are the bot's clicks: each gesture becomes `bot.clickWindow` with the
 *   button and mode vanilla sends for it (see {@link clickArgs}), so a selector clicked here is
 *   clicked by the bot. A click outside the grid is rejected, and so is a click on the inventory
 *   while the bot has another window open.
 * - When the server opens another window while the view is open (the next page of a selector), the
 *   view follows it. When the bot's window closes, the view closes.
 * - When the player closes the view, the bot closes its window too, as a player would.
 * - Two context actions on the bot, *Open inventory* and *Open window*, open the view.
 *
 * Options:
 * - `title`: title of the inventory view. Default `<username>'s inventory`. Other windows show
 *   their own title.
 * - `action` (default true): register the two context actions.
 * - `gestures`: what gestures do on the inventory. `vanilla` (default) plays them as clicks.
 *   `quick` manages the inventory without a cursor: shift-click drops the whole stack
 *   (`bot.tossStack`), the drop key drops one item (`bot.toss`, or the stack without it; Ctrl the
 *   whole stack), a number key moves the stack to that hotbar slot (`bot.moveSlotItem`), and other
 *   clicks do nothing. Other windows are always played as clicks.
 * - `closeWindow` (default true): closing the view closes the bot's window.
 * - `follow` (default true): follow the bot to the next window the server opens.
 *
 * A gesture whose bot method is missing rejects with `unsupported`; the reason is shown to the
 * player. Setup throws `unsupported` in a controller session or for a bot without an inventory.
 */
export const windowMirror = defineExtension({
  id: 'bridge:window',
  version: '1.0.0',
  options: {
    title: { type: 'string' },
    action: { type: 'boolean', default: true },
    gestures: { type: 'string', default: 'vanilla', enum: ['vanilla', 'quick'] },
    closeWindow: { type: 'boolean', default: true },
    follow: { type: 'boolean', default: true },
  },
  setup(ctx, options): WindowApi {
    const base = ctx.bot;
    if (base === null) throw new BridgeError('unsupported', 'bridge:window needs a bot session');
    const bot = base as typeof base & WindowBotLike;
    const inventory = bot.inventory as WindowLike | undefined;
    if (inventory === undefined) throw new BridgeError('unsupported', 'bridge:window needs bot.inventory');
    const text = textReader(bot.registry);

    /** The window the view mirrors, with the remover of its slot listener. */
    let attached: { window: WindowLike; off: () => void } | undefined;

    const isInventory = (w: WindowLike): boolean => w === inventory;
    const openWindow = (): WindowLike | undefined => {
      const w = bot.currentWindow;
      return w === null || w === undefined || isInventory(w) ? undefined : w;
    };
    const titleOf = (w: WindowLike): string => (isInventory(w) ? (options.title ?? `${bot.username ?? 'Bot'}'s inventory`) : text(w.title) || 'Window');
    /** How many of the window's slots the view shows: the whole inventory, or a container's own slots. */
    const shown = (w: WindowLike): number => (isInventory(w) ? INVENTORY_SLOTS : Math.min(MAX_SLOTS, Math.max(0, w.inventoryStart ?? w.slots.length - PLAYER_SLOTS)));
    const layoutOf = (w: WindowLike): ContainerLayout => (isInventory(w) ? 'player_inventory' : layoutFor(shown(w), String(w.type ?? '')));
    const slotsOf = (w: WindowLike): (ContainerItem | null)[] => Array.from({ length: shown(w) }, (_, i) => windowItem(w.slots[i]));

    const stackAt = (slot: number): MineflayerItemLike | undefined => inventory.slots[slot] ?? undefined;
    const tossStack = async (slot: number): Promise<boolean> => {
      const item = stackAt(slot);
      if (item === undefined) return false;
      if (bot.tossStack === undefined) throw new BridgeError('unsupported', 'this bot cannot toss items');
      await bot.tossStack(item);
      return true;
    };
    /** A gesture on the inventory with `gestures: 'quick'`. */
    const quick = async (e: ContainerEvent): Promise<boolean | void> => {
      if (e.click === 'drop' || e.click === 'drop-all') {
        const item = stackAt(e.slot);
        if (item === undefined) return false;
        if (e.all || bot.toss === undefined || item.type === undefined) return tossStack(e.slot);
        await bot.toss(item.type, item.metadata ?? null, 1);
        return true;
      }
      if (e.click === 'number') {
        if (e.hotbar === undefined || stackAt(e.slot) === undefined) return false;
        if (bot.moveSlotItem === undefined) throw new BridgeError('unsupported', 'this bot cannot move inventory slots');
        await bot.moveSlotItem(e.slot, HOTBAR_START + e.hotbar);
        return true;
      }
      return e.shift ? tossStack(e.slot) : undefined;
    };
    /** A gesture the bot makes in its window; a thrown error tells the player why it did not. */
    const gesture = async (e: ContainerEvent): Promise<boolean | void> => {
      const w = attached?.window;
      if (w === undefined) throw new BridgeError('unsupported', 'the window is gone');
      if (isInventory(w)) {
        if (options.gestures === 'quick') return quick(e);
        if (openWindow() !== undefined) throw new BridgeError('unsupported', 'the bot has another window open');
      } else if (bot.currentWindow !== w) throw new BridgeError('unsupported', 'the bot no longer has this window open');
      if (e.slot < 0) return false;
      if (bot.clickWindow === undefined) throw new BridgeError('unsupported', 'this bot cannot click windows');
      const { button, mode } = clickArgs(e.click, e.hotbar);
      await bot.clickWindow(e.slot, button, mode);
      return true;
    };

    const detach = (): void => {
      attached?.off();
      attached = undefined;
    };
    const view = ctx.containerView('window', {
      title: titleOf(inventory),
      layout: 'player_inventory',
      slots: slotsOf(inventory),
      on: {
        click: gesture,
        drop: gesture,
        hotbarSwap: gesture,
        // The player opened it from the mod: show the inventory unless something is mirrored.
        open: () => {
          if (attached === undefined) attach(inventory);
        },
        close: (_v, reason) => {
          const w = attached?.window;
          detach();
          // The player closed it: the bot closes its window too, as a player would.
          if (reason === 'player' && options.closeWindow && w !== undefined && !isInventory(w) && bot.currentWindow === w) bot.closeWindow?.(w as never);
        },
      },
    });
    const attach = (w: WindowLike): void => {
      detach();
      const off = ctx.on(w, 'updateSlot', (slot: number, _old: unknown, item: WindowItemLike | null | undefined) => {
        if (Number.isInteger(slot) && slot >= 0 && slot < Math.min(shown(w), view.size)) view.setSlot(slot, windowItem(item));
      });
      attached = { window: w, off };
      view.update({ title: titleOf(w), layout: layoutOf(w) });
      view.setSlots(slotsOf(w).slice(0, view.size));
    };

    // The server opened another window (a selector's next page): follow it.
    ctx.on(bot, 'windowOpen', (next: WindowLike) => {
      if (!options.follow || !view.isOpen || attached === undefined || next === attached.window) return;
      attach(next);
      view.open();
    });
    ctx.on(bot, 'windowClose', (closed: WindowLike) => {
      if (closed !== attached?.window) return;
      detach();
      view.close();
    });
    ctx.signal.addEventListener('abort', detach, { once: true });

    const open = (what: WindowTarget = 'window'): WindowTarget => {
      const w = what === 'window' ? openWindow() : undefined;
      attach(w ?? inventory);
      view.open();
      return w === undefined ? 'inventory' : 'window';
    };
    if (options.action) {
      ctx.actions.register('inventory', { label: 'Open inventory', icon: 'minecraft:chest', target: { type: 'bot' }, run: () => void open('inventory') });
      ctx.actions.register('window', { label: 'Open window', icon: 'minecraft:ender_chest', target: { type: 'bot' }, order: 1, run: () => (open('window') === 'window' ? undefined : 'No window open; showing the inventory') });
    }
    return {
      view,
      get showing() {
        return attached === undefined ? undefined : isInventory(attached.window) ? 'inventory' : 'window';
      },
      open,
      close: () => {
        detach();
        view.close();
      },
      refresh: () => {
        const w = attached?.window ?? inventory;
        view.setSlots(slotsOf(w).slice(0, view.size));
      },
    };
  },
});
