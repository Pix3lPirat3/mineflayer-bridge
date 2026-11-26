import { BridgeError, type ChatSpan, type LoreLine, type SlotItem } from '@mineflayer-bridge/protocol';
import { contentParts, styledSpan } from './chat.ts';
import { resourceId } from './check.ts';
import type { ChatContent, ChatSpanInput, ContainerItem, HeadInput, ItemInput, MineflayerItemLike } from './input-types.ts';
import { toColor } from './util.ts';

type Mutable<T> = { -readonly [K in keyof T]: T[K] };

/** What item conversion needs from its session: the bot's own name, for `head: 'self'`. */
export interface ItemContext {
  /** The bot's username, or `undefined` for controllers and before login. */
  self(): string | undefined;
}

/** For callers that have no session, such as pure helpers; `head: 'self'` then throws. */
export const NO_SELF: ItemContext = { self: () => undefined };

/** Counts above this get a compact `countText` unless one is given. */
const COMPACT_ABOVE = 99;
const U32_MAX = 0xffffffff;

function stackCount(n: number): number {
  if (!Number.isInteger(n) || n < 0 || n > U32_MAX) throw new BridgeError('invalid_message', `item count must be a non-negative integer: ${String(n)}`);
  return n;
}

/**
 * The compact form of a count as the slot overlay shows it: the number itself below 1000, then one
 * decimal below 10k (`1.2k`), whole thousands below a million (`12k`), and the same steps for `M` and
 * `B`. Values are rounded down, so a count never looks larger than it is.
 */
export function compactCount(n: number): string {
  if (n < 1000) return String(n);
  const units: readonly [number, string][] = [
    [1e9, 'B'],
    [1e6, 'M'],
    [1e3, 'k'],
  ];
  for (const [size, unit] of units) {
    if (n < size) continue;
    const v = n / size;
    return v < 10 ? `${Math.floor(v * 10) / 10}${unit}` : `${Math.floor(v)}${unit}`;
  }
  return String(n);
}

function spans(what: string, content: ChatContent): ChatSpan[] {
  const out = contentParts(content).map((p) => {
    if (typeof p === 'object' && p !== null && (p as ChatSpanInput).click !== undefined) throw new BridgeError('invalid_message', `${what} spans cannot have a click`);
    return styledSpan(p);
  });
  if (out.length === 0) throw new BridgeError('invalid_message', `${what} needs at least one span`);
  return out;
}


function headName(h: HeadInput, ctx: ItemContext): string {
  if (h === 'self') {
    const name = ctx.self();
    if (name === undefined || name === '') throw new BridgeError('invalid_message', "head 'self' needs a bot session whose username is known");
    return name;
  }
  if (typeof h === 'string') {
    if (h === '' || /\s/.test(h)) throw new BridgeError('invalid_message', `head must be a player name, UUID or bot: ${h}`);
    return h;
  }
  if (typeof h === 'object' && h !== null && typeof h.username === 'string' && h.username !== '') return h.username;
  throw new BridgeError('invalid_message', 'head must be a player name, UUID, a bot with a username, or self');
}

function presentation(out: SlotItem, it: ContainerItem, ctx: ItemContext, nested: boolean): void {
  if (it.count !== undefined && stackCount(it.count) !== 1) out.count = it.count;
  if (it.countText !== undefined) out.countText = String(it.countText);
  else if (it.count !== undefined && it.count > COMPACT_ABOVE) out.countText = compactCount(it.count);
  if (it.name !== undefined) {
    if (typeof it.name === 'string') out.name = it.name;
    else out.nameSpans = spans('item name', it.name);
  }
  if (it.lore !== undefined) {
    if (!Array.isArray(it.lore)) throw new BridgeError('invalid_message', 'lore must be a list of lines');
    const lines = it.lore as readonly ChatContent[];
    if (lines.every((l) => typeof l === 'string')) out.lore = lines.map(String);
    else out.loreLines = lines.map((l): LoreLine => ({ spans: spans('lore', l) }));
  }
  if (it.glint === true) out.glint = true;
  if (it.customModel !== undefined) out.customModel = it.customModel;
  if (it.components !== undefined) out.components = it.components;
  if (it.bar !== undefined) {
    if (typeof it.bar !== 'number' || !(it.bar >= 0 && it.bar <= 1)) throw new BridgeError('invalid_message', `item bar must be a number from 0 to 1: ${String(it.bar)}`);
    out.bar = it.bar;
  }
  if (it.barColor !== undefined) out.barColor = toColor(it.barColor);
  if (it.head !== undefined) out.head = headName(it.head, ctx);
  if (it.headTexture !== undefined) {
    if (typeof it.headTexture !== 'string' || !/^[A-Za-z0-9+/=_-]{1,32768}$/.test(it.headTexture)) throw new BridgeError('invalid_message', 'headTexture must be the base64 textures value, at most 32768 characters');
    out.headTexture = it.headTexture;
  }
  if (it.color !== undefined) out.color = toColor(it.color);
  if (it.frames !== undefined) {
    if (nested) throw new BridgeError('invalid_message', 'animation frames cannot have frames of their own');
    if (!Array.isArray(it.frames) || it.frames.length === 0) throw new BridgeError('invalid_message', 'item frames must be a non-empty list of items');
    out.frames = (it.frames as readonly ItemInput[]).map((f) => convert(f, ctx, true));
  }
  if (it.frameMs !== undefined) {
    if (!Number.isInteger(it.frameMs) || it.frameMs <= 0 || it.frameMs > U32_MAX) throw new BridgeError('invalid_message', 'item frameMs must be a positive integer');
    out.frameMs = it.frameMs;
  }
  if (it.hideTooltip === true) out.hideTooltip = true;
  if (it.clickSound !== undefined) out.clickSound = resourceId('click sound', it.clickSound);
}

function convert(input: ItemInput, ctx: ItemContext, nested: boolean): SlotItem {
  if (typeof input === 'string') return { id: resourceId('item id', input) };
  if (typeof input !== 'object' || input === null) throw new BridgeError('invalid_message', `not an item: ${String(input)}`);
  if (typeof (input as ContainerItem).id === 'string') {
    const it = input as ContainerItem;
    const out: SlotItem = { id: resourceId('item id', it.id) };
    presentation(out, it, ctx, nested);
    return out;
  }
  const m = input as MineflayerItemLike;
  if (typeof m.name !== 'string') throw new BridgeError('invalid_message', 'an item needs an id string, or a name as mineflayer items have');
  const out: SlotItem = { id: resourceId('item id', m.name) };
  if (m.count !== undefined && stackCount(m.count) !== 1) out.count = m.count;
  if (m.count !== undefined && m.count > COMPACT_ABOVE) out.countText = compactCount(m.count);
  if (typeof m.customName === 'string' && m.customName !== '') out.name = m.customName;
  return out;
}

/**
 * Converts any accepted item form to the wire `SlotItem`. Objects with a string `id` are {@link
 * ContainerItem}s; other objects with a string `name` are mineflayer items. Plain names and lore
 * stay plain; styled ones become `nameSpans` and `loreLines`.
 * `head: 'self'` resolves through `ctx`.
 * @throws BridgeError `invalid_message` for anything that does not fit.
 */
export function toSlotItem(input: ItemInput, ctx: ItemContext = NO_SELF): SlotItem {
  return convert(input, ctx, false);
}

function fromSpan(s: ChatSpan): string | ChatSpanInput {
  if (s.color === undefined && s.bold !== true && s.italic !== true && s.underlined !== true && s.strikethrough !== true && s.obfuscated !== true && s.hover === undefined) return s.text;
  const out: Mutable<ChatSpanInput> = { text: s.text };
  if (s.color !== undefined) out.color = s.color;
  if (s.bold === true) out.bold = true;
  if (s.italic === true) out.italic = true;
  if (s.underlined === true) out.underlined = true;
  if (s.strikethrough === true) out.strikethrough = true;
  if (s.obfuscated === true) out.obfuscated = true;
  if (s.hover !== undefined) out.hover = s.hover;
  return out;
}

/** Chat content of spans: a single span stands alone, several form a list. */
function fromSpans(spans: readonly ChatSpan[]): ChatContent {
  const parts = spans.map(fromSpan);
  return parts.length === 1 ? parts[0]! : parts;
}

/** The public form of a wire item, leaving out defaults. Styled names and lore come back as spans. */
export function fromSlotItem(s: SlotItem): ContainerItem {
  const out: Mutable<ContainerItem> = { id: s.id, count: s.count ?? 1 };
  if (s.nameSpans !== undefined) out.name = fromSpans(s.nameSpans);
  else if (s.name !== undefined) out.name = s.name;
  if (s.loreLines !== undefined) out.lore = s.loreLines.map((l) => fromSpans(l.spans));
  else if (s.lore !== undefined) out.lore = [...s.lore];
  if (s.glint === true) out.glint = true;
  if (s.customModel !== undefined) out.customModel = s.customModel;
  if (s.components !== undefined) out.components = s.components as Record<string, unknown>;
  if (s.countText !== undefined) out.countText = s.countText;
  if (s.bar !== undefined) out.bar = s.bar;
  if (s.barColor !== undefined) out.barColor = s.barColor;
  if (s.head !== undefined) out.head = s.head;
  if (s.headTexture !== undefined) out.headTexture = s.headTexture;
  if (s.color !== undefined) out.color = s.color;
  if (s.frames !== undefined) {
    out.frames = s.frames.map(fromSlotItem);
    out.frameMs = s.frameMs ?? 500;
  }
  if (s.hideTooltip === true) out.hideTooltip = true;
  if (s.clickSound !== undefined) out.clickSound = s.clickSound;
  return out;
}
