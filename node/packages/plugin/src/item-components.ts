/**
 * Data components of mineflayer items (prismarine-item on 1.20.5+) in the form the game saves them,
 * keyed by id (`{ 'minecraft:damage': 10 }`), so a container view draws the item as the game would.
 * prismarine-item holds them as the protocol sends them: registry entries by number, field names
 * of the packet. Numbers become names here, from the bot's own registry or the registries the
 * server sent it, so they mean the same in a player's game of another version.
 */

/** A prismarine-item component: `{ type: 'damage', data: 12 }`. */
interface NetworkComponent {
  readonly type?: unknown;
  readonly data?: unknown;
}

/** The parts of a minecraft-data registry the conversion reads. */
export interface ComponentRegistryLike {
  readonly items?: Readonly<Record<number, { readonly name?: string } | undefined>>;
  readonly enchantments?: Readonly<Record<number, { readonly name?: string } | undefined>>;
  readonly effects?: Readonly<Record<number, { readonly name?: string } | undefined>>;
  readonly attributesArray?: readonly ({ readonly resource?: string } | undefined)[];
  readonly protocol?: { readonly types?: Record<string, unknown> };
}

/** Potions by protocol id: a built-in registry minecraft-data does not list. Unchanged in 26.1 to 26.3. */
const POTIONS = [
  'water', 'mundane', 'thick', 'awkward', 'night_vision', 'long_night_vision', 'invisibility', 'long_invisibility',
  'leaping', 'long_leaping', 'strong_leaping', 'fire_resistance', 'long_fire_resistance', 'swiftness', 'long_swiftness',
  'strong_swiftness', 'slowness', 'long_slowness', 'strong_slowness', 'turtle_master', 'long_turtle_master',
  'strong_turtle_master', 'water_breathing', 'long_water_breathing', 'healing', 'strong_healing', 'harming',
  'strong_harming', 'poison', 'long_poison', 'strong_poison', 'regeneration', 'long_regeneration', 'strong_regeneration',
  'strength', 'long_strength', 'strong_strength', 'weakness', 'long_weakness', 'luck', 'slow_falling', 'long_slow_falling',
  'wind_charged', 'weaving', 'oozing', 'infested',
] as const;

/**
 * Vanilla entries of registries the server sends, used until it has sent them: data-driven
 * registries are numbered in id order. Enchantments come from minecraft-data.
 */
const VANILLA: Readonly<Record<string, readonly string[]>> = {
  'minecraft:trim_material': ['amethyst', 'copper', 'diamond', 'emerald', 'gold', 'iron', 'lapis', 'netherite', 'quartz', 'redstone', 'resin'],
  'minecraft:trim_pattern': ['bolt', 'coast', 'dune', 'eye', 'flow', 'host', 'raiser', 'rib', 'sentry', 'shaper', 'silence', 'snout', 'spire', 'tide', 'vex', 'ward', 'wayfinder', 'wild'],
};

/** The server's registries a component can name an entry of. */
const SENT = new Set(['minecraft:enchantment', 'minecraft:trim_material', 'minecraft:trim_pattern', 'minecraft:banner_pattern']);

const DYES = ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black'] as const;

/** Components shown by other fields of the item (name, lore, head skin), or never drawn. */
const LEFT_OUT = new Set(['custom_name', 'lore', 'profile', 'custom_data', 'entity_data', 'bucket_entity_data', 'block_entity_data', 'container_loot', 'recipes', 'debug_stick_state', 'creative_slot_lock']);

const ns = (name: string): string => (name.includes(':') ? name : `minecraft:${name}`);
const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const int = (v: unknown): number | undefined => (typeof v === 'number' && Number.isInteger(v) ? v : undefined);
const num = (v: unknown): number | undefined => (typeof v === 'number' && Number.isFinite(v) ? v : undefined);

/** NBT tags as prismarine-nbt holds them (`{ type, value }`) reduced to their values. */
export function untagged(v: unknown, depth = 0): unknown {
  if (depth > 32 || typeof v !== 'object' || v === null) return v;
  if (Array.isArray(v)) return v.map((x) => untagged(x, depth + 1));
  const o = v as Record<string, unknown>;
  const keys = Object.keys(o);
  if (typeof o['type'] === 'string' && 'value' in o && keys.every((k) => k === 'type' || k === 'value' || k === 'name')) return untagged(o['value'], depth + 1);
  return Object.fromEntries(keys.map((k) => [k, untagged(o[k], depth + 1)]));
}

/** mob effect names in minecraft-data are display-cased (`InstantHealth`); one differs from its id. */
function effectId(name: string): string {
  if (name === 'BadLuck') return 'minecraft:unluck';
  return ns(name.replace(/(?<=[a-z0-9])([A-Z])/g, '_$1').toLowerCase());
}

/**
 * Turns prismarine-item components into the game's saved form. Keeps track of the registries the
 * server sends (`registry_data`), whose entries components name by number.
 */
export class ItemComponents {
  private readonly registry: ComponentRegistryLike | undefined;
  private readonly sent = new Map<string, string[]>();

  constructor(registry: unknown) {
    this.registry = isRecord(registry) ? (registry as ComponentRegistryLike) : undefined;
  }

  /** Records a `registry_data` packet of the configuration phase: `{ id, entries: [{ key }] }`. */
  record(packet: unknown): void {
    if (!isRecord(packet) || typeof packet['id'] !== 'string' || !Array.isArray(packet['entries'])) return;
    const id = ns(packet['id']);
    if (!SENT.has(id)) return;
    this.sent.set(id, (packet['entries'] as unknown[]).map((e) => (isRecord(e) && typeof e['key'] === 'string' ? ns(e['key']) : '')));
  }

  private sentName(registry: string, id: unknown): string | undefined {
    const n = int(id);
    if (n === undefined) return undefined;
    const list = this.sent.get(registry);
    if (list !== undefined) return list[n] || undefined;
    if (registry === 'minecraft:enchantment') {
      const name = this.registry?.enchantments?.[n]?.name;
      return name === undefined ? undefined : ns(name);
    }
    const vanilla = VANILLA[registry]?.[n];
    return vanilla === undefined ? undefined : ns(vanilla);
  }

  private itemName(id: unknown): string | undefined {
    const n = int(id);
    const name = n === undefined ? undefined : this.registry?.items?.[n]?.name;
    return name === undefined ? undefined : ns(name);
  }

  private componentName(id: unknown): string | undefined {
    const n = int(id);
    const type = this.registry?.protocol?.types?.['SlotComponentType'];
    const mappings = Array.isArray(type) && isRecord(type[1]) ? (type[1]['mappings'] as Record<string, unknown> | undefined) : undefined;
    const name = n === undefined ? undefined : mappings?.[String(n)];
    return typeof name === 'string' ? ns(name) : undefined;
  }

  /**
   * The components of an item in saved form, or `undefined` when there are none to show.
   * Components that cannot be named (an entry the bot's data does not know) are left out.
   */
  of(item: { readonly components?: unknown; readonly removedComponents?: unknown }): Record<string, unknown> | undefined {
    return this.collect(item.components, item.removedComponents, 0);
  }

  private collect(components: unknown, removed: unknown, depth: number): Record<string, unknown> | undefined {
    const out: Record<string, unknown> = {};
    if (Array.isArray(components)) {
      for (const c of components as NetworkComponent[]) {
        if (!isRecord(c) || typeof c.type !== 'string' || LEFT_OUT.has(c.type)) continue;
        const value = this.value(c.type, c.data, depth);
        if (value !== undefined) out[ns(c.type)] = value;
      }
    }
    if (Array.isArray(removed)) {
      for (const r of removed as unknown[]) {
        const type = isRecord(r) ? r['type'] : r;
        if (typeof type === 'string') out[`!${ns(type)}`] = {};
      }
    }
    return Object.keys(out).length === 0 ? undefined : out;
  }

  private enchantments(d: unknown): Record<string, number> | undefined {
    const list = isRecord(d) ? d['enchantments'] : undefined;
    if (!Array.isArray(list)) return undefined;
    const out: Record<string, number> = {};
    for (const e of list as unknown[]) {
      if (!isRecord(e)) continue;
      const name = this.sentName('minecraft:enchantment', e['id']);
      const level = int(e['level']);
      if (name === undefined || level === undefined) return undefined;
      out[name] = level;
    }
    return out;
  }

  private stack(s: unknown, depth: number): Record<string, unknown> | undefined {
    if (!isRecord(s) || depth > 4) return undefined;
    const id = this.itemName(s['itemId']);
    if (id === undefined) return undefined;
    const out: Record<string, unknown> = { id, count: int(s['itemCount']) ?? 1 };
    const components = this.collect(s['components'], s['removeComponents'], depth + 1);
    if (components !== undefined) out['components'] = components;
    return out;
  }

  private stacks(list: unknown, depth: number): Record<string, unknown>[] | undefined {
    if (!Array.isArray(list)) return undefined;
    const out = list.map((s) => this.stack(s, depth));
    return out.every((s) => s !== undefined) ? (out as Record<string, unknown>[]) : undefined;
  }

  private text(v: unknown): unknown {
    const t = untagged(v);
    return typeof t === 'string' || isRecord(t) || Array.isArray(t) ? t : undefined;
  }

  private value(type: string, d: unknown, depth: number): unknown {
    switch (type) {
      case 'damage':
      case 'max_damage':
      case 'max_stack_size':
      case 'dyed_color':
      case 'map_id':
      case 'ominous_bottle_amplifier':
        return int(d);
      case 'potion_duration_scale':
        return num(d);
      case 'unbreakable':
      case 'glider':
        return {};
      case 'enchantment_glint_override':
        return typeof d === 'boolean' ? d : undefined;
      case 'item_model':
      case 'tooltip_style':
      case 'rarity':
      case 'base_color':
        return typeof d === 'string' && d !== '' ? d : undefined;
      case 'item_name':
        return this.text(d);
      case 'enchantments':
      case 'stored_enchantments':
        return this.enchantments(d);
      case 'custom_model_data': {
        if (!isRecord(d)) return undefined;
        const out: Record<string, unknown> = {};
        for (const k of ['floats', 'flags', 'strings', 'colors']) if (Array.isArray(d[k]) && (d[k] as unknown[]).length > 0) out[k] = d[k];
        return out;
      }
      case 'tooltip_display': {
        if (!isRecord(d)) return undefined;
        const hidden = Array.isArray(d['hiddenComponents']) ? (d['hiddenComponents'] as unknown[]).map((n) => this.componentName(n)).filter((n) => n !== undefined) : [];
        return { hide_tooltip: d['hideTooltip'] === true, hidden_components: hidden };
      }
      case 'trim': {
        if (!isRecord(d) || !isRecord(d['material']) || !isRecord(d['pattern'])) return undefined;
        const material = this.sentName('minecraft:trim_material', d['material']['materialId']);
        const pattern = this.sentName('minecraft:trim_pattern', d['pattern']['patternId']);
        return material === undefined || pattern === undefined ? undefined : { material, pattern };
      }
      case 'potion_contents': {
        if (!isRecord(d)) return undefined;
        const out: Record<string, unknown> = {};
        const potionId = int(d['potionId']);
        if (potionId !== undefined) {
          const potion = POTIONS[potionId];
          if (potion !== undefined) out['potion'] = ns(potion);
        }
        if (int(d['customColor']) !== undefined) out['custom_color'] = d['customColor'];
        if (typeof d['customName'] === 'string') out['custom_name'] = d['customName'];
        if (Array.isArray(d['customEffects'])) {
          const effects: Record<string, unknown>[] = [];
          for (const e of d['customEffects'] as unknown[]) {
            if (!isRecord(e) || !isRecord(e['details'])) continue;
            const n = int(e['id']);
            const name = n === undefined ? undefined : this.registry?.effects?.[n]?.name;
            if (name === undefined) continue;
            const x = e['details'];
            effects.push({ id: effectId(name), amplifier: int(x['amplifier']) ?? 0, duration: int(x['duration']) ?? 0, ambient: x['ambient'] === true, show_particles: x['showParticles'] !== false, show_icon: x['showIcon'] !== false });
          }
          if (effects.length > 0) out['custom_effects'] = effects;
        }
        return out;
      }
      case 'bundle_contents':
        return this.stacks(isRecord(d) ? d['contents'] : undefined, depth);
      case 'charged_projectiles':
        return this.stacks(isRecord(d) ? d['projectiles'] : undefined, depth);
      case 'container': {
        const list = isRecord(d) ? d['contents'] : undefined;
        if (!Array.isArray(list)) return undefined;
        const out: Record<string, unknown>[] = [];
        list.forEach((s, slot) => {
          const item = s === undefined || s === null ? undefined : this.stack(s, depth);
          if (item !== undefined) out.push({ slot, item });
        });
        return out;
      }
      case 'attribute_modifiers': {
        if (!Array.isArray(d)) return undefined;
        const out: Record<string, unknown>[] = [];
        for (const m of d as unknown[]) {
          if (!isRecord(m)) continue;
          const n = int(m['typeId']);
          const attribute = n === undefined ? undefined : this.registry?.attributesArray?.[n]?.resource;
          if (attribute === undefined || typeof m['name'] !== 'string' || num(m['value']) === undefined) return undefined;
          const entry: Record<string, unknown> = {
            type: attribute,
            id: m['name'],
            amount: m['value'],
            operation: ({ add: 'add_value', multiply_base: 'add_multiplied_base', multiply_total: 'add_multiplied_total' } as Record<string, string>)[String(m['operation'])] ?? 'add_value',
            slot: ({ main_hand: 'mainhand', off_hand: 'offhand' } as Record<string, string>)[String(m['slot'])] ?? m['slot'] ?? 'any',
          };
          const display = isRecord(m['display']) ? m['display'] : undefined;
          if (display?.['type'] === 'hidden') entry['display'] = { type: 'hidden' };
          else if (display?.['type'] === 'override' && this.text(display['component']) !== undefined) entry['display'] = { type: 'override', value: this.text(display['component']) };
          out.push(entry);
        }
        return out;
      }
      case 'firework_explosion':
        return explosion(d);
      case 'fireworks': {
        if (!isRecord(d)) return undefined;
        const explosions = Array.isArray(d['explosions']) ? (d['explosions'] as unknown[]).map(explosion) : [];
        return explosions.every((e) => e !== undefined) ? { flight_duration: int(d['flightDuration']) ?? 0, explosions } : undefined;
      }
      case 'banner_patterns': {
        const layers = isRecord(d) ? d['layers'] : undefined;
        if (!Array.isArray(layers)) return undefined;
        const out: Record<string, unknown>[] = [];
        for (const l of layers as unknown[]) {
          if (!isRecord(l) || !isRecord(l['pattern'])) return undefined;
          const pattern = this.sentName('minecraft:banner_pattern', l['pattern']['patternId']);
          const color = DYES[int(l['colorId']) ?? -1];
          if (pattern === undefined || color === undefined) return undefined;
          out.push({ pattern, color });
        }
        return out;
      }
      default:
        return undefined;
    }
  }
}

function explosion(d: unknown): Record<string, unknown> | undefined {
  if (!isRecord(d) || typeof d['shape'] !== 'string') return undefined;
  return {
    shape: d['shape'],
    colors: Array.isArray(d['colors']) ? d['colors'] : [],
    fade_colors: Array.isArray(d['fadeColors']) ? d['fadeColors'] : [],
    has_trail: d['hasTrail'] === true,
    has_twinkle: d['hasTwinkle'] === true,
  };
}
