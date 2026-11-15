import { BridgeError } from '@mineflayer-bridge/protocol';
import { defineExtension } from './extensions.ts';
import { batch, detailGate, isFiniteNumber, WHEN_UNFOCUSED } from './ext-util.ts';
import type { HudAnchorName, HudPanel } from './input-types.ts';
import type { BotLike, Vec3Like } from './types.ts';

/** A status effect as mineflayer keeps it in `bot.entity.effects`. `duration` is in ticks. */
export interface EffectLike {
  readonly id: number;
  /** 0 for level I. */
  readonly amplifier?: number;
  /** Remaining ticks when the server last reported the effect. */
  readonly duration?: number;
}

/** The parts of a mineflayer bot `bridge:vitals-hud` reads. Health, food and saturation are 0 to 20, oxygen 0 to 20. */
export interface VitalsBotLike extends BotLike {
  readonly health?: number;
  readonly food?: number;
  readonly foodSaturation?: number;
  readonly oxygenLevel?: number;
  entity?: { readonly position: Vec3Like; readonly id?: number; readonly effects?: Readonly<Record<string, EffectLike>> } | null;
  /** Effect names by id, from minecraft-data. */
  readonly registry?: { readonly effects?: Readonly<Record<number, { readonly name?: string; readonly displayName?: string } | undefined>> } | null;
}

/** Bar colours of `bridge:vitals-hud`. */
export const VITAL_COLORS = {
  health: '#ff4b4b',
  food: '#d98a2b',
  saturation: '#ffd447',
  oxygen: '#4fa3ff',
} as const;

/** Public API of `bridge:vitals-hud`. */
export interface VitalsHudApi {
  /** The HUD panel, `~hud/vitals`. */
  readonly panel: HudPanel;
  /** Redraws now with the bot's current values, when the player is attached. */
  refresh(): void;
}

const ROMAN = ['I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X'];

/** `m:ss` for a duration in ticks (20 per second); effects longer than an hour show `**:**` as vanilla does. */
export function formatTicks(ticks: number): string {
  if (!isFiniteNumber(ticks) || ticks < 0) return '';
  const total = Math.floor(ticks / 20);
  if (total >= 3600) return '**:**';
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
}

function effectName(bot: VitalsBotLike, e: EffectLike): string {
  const info = bot.registry?.effects?.[e.id];
  const base = info?.displayName ?? info?.name ?? `Effect ${e.id}`;
  const level = (e.amplifier ?? 0) + 1;
  return level > 1 ? `${base} ${ROMAN[level - 1] ?? level}` : base;
}

/**
 * `bridge:vitals-hud`: a HUD panel with health, food, saturation and oxygen bars and the active
 * status effects, read from `bot.health`, `bot.food`, `bot.foodSaturation`, `bot.oxygenLevel` and
 * `bot.entity.effects`. Updates follow mineflayer's `health`, `breath`, `spawn`, `entityEffect` and
 * `entityEffectEnd` events, coalesced to at most one redraw per `intervalMs`, and wait while the
 * player is not attached. While detail is reduced or the bot is not focused only the health, food
 * and oxygen bars are kept; saturation and effects are removed.
 *
 * Options: `title` (default `Vitals`), `anchor` (default `top-left`), `intervalMs` (default 250 ms),
 * `effects` (default true), `oxygen`: `auto` (default, only while below full), `always` or `never`,
 * `whenUnfocused`: `summary` (default) or `full` to keep the detail while another bot is focused.
 * Setup throws `unsupported` in a controller session.
 */
export const vitalsHud = defineExtension({
  id: 'bridge:vitals-hud',
  version: '0.1.0',
  options: {
    title: { type: 'string', default: 'Vitals' },
    anchor: { type: 'string', default: 'top-left', enum: ['top-left', 'top-center', 'top-right', 'middle-left', 'middle-right', 'bottom-left', 'bottom-right'] },
    intervalMs: { type: 'integer', default: 250, min: 0, max: 60_000 },
    effects: { type: 'boolean', default: true },
    oxygen: { type: 'string', default: 'auto', enum: ['auto', 'always', 'never'] },
    whenUnfocused: WHEN_UNFOCUSED,
  },
  setup(ctx, options): VitalsHudApi {
    const bot = ctx.bot as VitalsBotLike | null;
    if (bot === null) throw new BridgeError('unsupported', 'bridge:vitals-hud needs a bot session');
    const panel = ctx.hud('vitals', { title: options.title, anchor: options.anchor as HudAnchorName });

    const bar = (id: string, order: number, label: string, value: number | undefined, color: string): void => {
      if (!isFiniteNumber(value)) {
        if (panel.has(id)) panel.remove(id);
        return;
      }
      const v = Math.min(20, Math.max(0, value));
      panel.bar(id, { value: v, min: 0, max: 20, label: `${label} ${Math.round(v * 10) / 10}/20`, color, order });
    };

    const draw = (): void => {
      const full = rich();
      bar('health', 0, 'Health', bot.health, VITAL_COLORS.health);
      bar('food', 1, 'Food', bot.food, VITAL_COLORS.food);
      bar('saturation', 2, 'Saturation', full ? bot.foodSaturation : undefined, VITAL_COLORS.saturation);
      const oxygen = bot.oxygenLevel;
      const showOxygen = options.oxygen === 'always' || (options.oxygen === 'auto' && isFiniteNumber(oxygen) && oxygen < 20);
      bar('oxygen', 3, 'Oxygen', showOxygen ? oxygen : undefined, VITAL_COLORS.oxygen);
      const effects = Object.values(bot.entity?.effects ?? {}).filter((e): e is EffectLike => typeof e === 'object' && e !== null && isFiniteNumber(e.id));
      if (!options.effects || !full || effects.length === 0) {
        if (panel.has('effects')) panel.remove('effects');
        return;
      }
      effects.sort((a, b) => a.id - b.id);
      panel.kv(
        'effects',
        effects.map((e) => ({ key: effectName(bot, e), value: formatTicks(e.duration ?? 0) })),
        { order: 4 },
      );
    };

    const redraw = batch(ctx, options.intervalMs, draw);
    const ownEntity = (entity: unknown): boolean => entity === bot.entity || (typeof entity === 'object' && entity !== null && (entity as { id?: unknown }).id === bot.entity?.id && bot.entity?.id !== undefined);
    ctx.on(bot, 'health', () => redraw.request());
    ctx.on(bot, 'breath', () => redraw.request());
    ctx.on(bot, 'spawn', () => redraw.request());
    ctx.on(bot, 'entityEffect', (entity: unknown) => {
      if (ownEntity(entity)) redraw.request();
    });
    ctx.on(bot, 'entityEffectEnd', (entity: unknown) => {
      if (ownEntity(entity)) redraw.request();
    });
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
