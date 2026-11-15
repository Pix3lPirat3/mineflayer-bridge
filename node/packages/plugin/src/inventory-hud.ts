import { BridgeError } from '@mineflayer-bridge/protocol';
import { defineExtension } from './extensions.ts';
import { batch, detailGate, itemId, WHEN_UNFOCUSED } from './ext-util.ts';
import type { HudAnchorName, HudPanel, MineflayerItemLike } from './input-types.ts';
import type { BotLike } from './types.ts';

/** The parts of a mineflayer bot `bridge:inventory-hud` reads. */
export interface InventoryBotLike extends BotLike {
  /** The item in the selected hotbar slot. */
  readonly heldItem?: MineflayerItemLike | null;
  /** Selected hotbar slot, 0 to 8. */
  readonly quickBarSlot?: number;
}

/** Player inventory window slots (mineflayer numbering). */
const ARMOR_SLOTS = [
  { id: 'armor-head', slot: 5, label: 'Head' },
  { id: 'armor-chest', slot: 6, label: 'Chest' },
  { id: 'armor-legs', slot: 7, label: 'Legs' },
  { id: 'armor-feet', slot: 8, label: 'Feet' },
  { id: 'offhand', slot: 45, label: 'Offhand' },
] as const;
const HOTBAR_START = 36;
const EMPTY = 'minecraft:air';

/** Public API of `bridge:inventory-hud`. */
export interface InventoryHudApi {
  /** The HUD panel, `~hud/inventory`. */
  readonly panel: HudPanel;
  /** Redraws now from the bot's inventory, when the player is attached. */
  refresh(): void;
}

function describe(item: MineflayerItemLike): string {
  const name = item.customName ?? item.name;
  return item.count > 1 ? `${name} x${item.count}` : name;
}

/**
 * `bridge:inventory-hud`: a HUD panel with the held item, the nine hotbar slots (the selected one
 * marked `>`) and the armor and offhand slots, from `bot.heldItem`, `bot.quickBarSlot` and
 * `bot.inventory.slots`. It follows the inventory's `updateSlot` and the bot's `heldItemChanged`
 * events, coalesced to at most one redraw per `intervalMs`, and waits while the player is not
 * attached. Empty slots show `minecraft:air` with count 0, so the grid keeps its shape. While
 * detail is reduced or the bot is not focused only the held item is kept.
 *
 * Options: `title` (default `Inventory`), `anchor` (default `bottom-right`), `intervalMs`
 * (default 250 ms), `hotbar` and `armor` (default true), `whenUnfocused`: `summary` (default) or
 * `full`. Setup throws `unsupported` in a controller session or for a bot without an inventory.
 */
export const inventoryHud = defineExtension({
  id: 'bridge:inventory-hud',
  version: '0.1.0',
  options: {
    title: { type: 'string', default: 'Inventory' },
    anchor: { type: 'string', default: 'bottom-right', enum: ['top-left', 'top-center', 'top-right', 'middle-left', 'middle-right', 'bottom-left', 'bottom-right'] },
    intervalMs: { type: 'integer', default: 250, min: 0, max: 60_000 },
    hotbar: { type: 'boolean', default: true },
    armor: { type: 'boolean', default: true },
    whenUnfocused: WHEN_UNFOCUSED,
  },
  setup(ctx, options): InventoryHudApi {
    const bot = ctx.bot as InventoryBotLike | null;
    if (bot === null) throw new BridgeError('unsupported', 'bridge:inventory-hud needs a bot session');
    const inventory = bot.inventory;
    if (inventory === undefined) throw new BridgeError('unsupported', 'bridge:inventory-hud needs bot.inventory');
    const panel = ctx.hud('inventory', { title: options.title, anchor: options.anchor as HudAnchorName });

    const slot = (id: string, order: number, label: string, item: MineflayerItemLike | null | undefined): void => {
      if (item === null || item === undefined || item.count <= 0) panel.item(id, EMPTY, { count: 0, label, order });
      else panel.item(id, itemId(item.name), { count: item.count, label: `${label} ${describe(item)}`, order });
    };
    const removeAll = (ids: readonly string[]): void => {
      for (const id of ids) if (panel.has(id)) panel.remove(id);
    };
    const hotbarIds = Array.from({ length: 9 }, (_, i) => `hotbar-${i}`);
    const armorIds = ARMOR_SLOTS.map((a) => a.id);

    const draw = (): void => {
      const selected = bot.quickBarSlot;
      const held = bot.heldItem ?? (selected === undefined ? undefined : inventory.slots[HOTBAR_START + selected]);
      slot('held', 0, 'Held', held);
      const full = rich();
      if (options.hotbar && full) {
        for (let i = 0; i < 9; i++) slot(hotbarIds[i]!, 1 + i, i === selected ? `>${i + 1}` : `${i + 1}`, inventory.slots[HOTBAR_START + i]);
      } else {
        removeAll(hotbarIds);
      }
      if (options.armor && full) {
        ARMOR_SLOTS.forEach((a, i) => slot(a.id, 10 + i, a.label, inventory.slots[a.slot]));
      } else {
        removeAll(armorIds);
      }
    };

    const redraw = batch(ctx, options.intervalMs, draw);
    ctx.on(inventory, 'updateSlot', () => redraw.request());
    ctx.on(bot, 'heldItemChanged', () => redraw.request());
    const rich = detailGate(ctx, options.whenUnfocused, () => redraw.request());
    redraw.request();
    return {
      panel,
      refresh: () => {
        redraw.request();
        redraw.flush();
      },
    };
  },
});
