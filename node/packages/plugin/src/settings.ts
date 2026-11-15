import { BridgeError, type KindPayloads, type SettingDef } from '@mineflayer-bridge/protocol';
import type { SettingDefinition, Settings, SettingsSchema, SettingsValuesOf, SettingValue } from './input-types.ts';
import { Emitter, toColor } from './util.ts';

const TYPES: ReadonlySet<string> = new Set(['bool', 'int', 'float', 'string', 'color', 'choice']);

/** Normalises a value against its definition, or returns `undefined` when it does not fit. */
function fit(def: SettingDefinition, v: unknown): unknown {
  switch (def.type) {
    case 'bool':
      return typeof v === 'boolean' ? v : undefined;
    case 'int':
    case 'float': {
      if (typeof v !== 'number' || !Number.isFinite(v)) return undefined;
      if (def.type === 'int' && !Number.isInteger(v)) return undefined;
      if ((def.min !== undefined && v < def.min) || (def.max !== undefined && v > def.max)) return undefined;
      return v;
    }
    case 'string':
      return typeof v === 'string' ? v : undefined;
    case 'color':
      try {
        return typeof v === 'number' || typeof v === 'string' ? toColor(v) : undefined;
      } catch {
        return undefined;
      }
    case 'choice':
      return typeof v === 'string' && def.options.includes(v) ? v : undefined;
  }
}

function fallback(def: SettingDefinition): unknown {
  switch (def.type) {
    case 'bool':
      return false;
    case 'int':
    case 'float':
      return def.min ?? 0;
    case 'string':
      return '';
    case 'color':
      return 0xffffffff;
    case 'choice':
      return def.options[0];
  }
}

/** One settings group of one scope, fed by `settings.values`. */
export class SettingsGroup<S extends SettingsSchema> implements Settings<S> {
  readonly key: string;
  /** Encoded declaration for the `core:settings` object. */
  readonly data: KindPayloads['core:settings'];
  private readonly schema: S;
  private readonly defaults = new Map<string, unknown>();
  private readonly values = new Map<string, unknown>();
  private readonly events = new Emitter<{ change: [keyof S & string, SettingValue<S[keyof S]>] }>();

  constructor(key: string, schema: S, title: string | undefined) {
    this.key = key;
    this.schema = schema;
    const settings: SettingDef[] = [];
    for (const [name, def] of Object.entries(schema)) {
      if (name === '') throw new BridgeError('invalid_message', 'setting keys must be non-empty');
      if (!TYPES.has(def.type)) throw new BridgeError('invalid_message', `setting ${name} has unknown type ${String(def.type)}`);
      if (def.type === 'choice' && def.options.length === 0) throw new BridgeError('invalid_message', `choice setting ${name} needs options`);
      const initial = def.default === undefined ? fallback(def) : fit(def, def.default);
      if (initial === undefined) throw new BridgeError('invalid_message', `default of setting ${name} does not fit its definition`);
      this.defaults.set(name, initial);
      this.values.set(name, initial);
      const w: SettingDef = { key: name, type: def.type, defaultValue: initial };
      if (def.label !== undefined) w.label = def.label;
      if (def.description !== undefined) w.description = def.description;
      if (def.type === 'int' || def.type === 'float') {
        if (def.min !== undefined) w.min = def.min;
        if (def.max !== undefined) w.max = def.max;
      }
      if (def.type === 'choice') w.options = [...def.options];
      settings.push(w);
    }
    this.data = { settings };
    if (title !== undefined) this.data.title = title;
  }

  get<K extends keyof S & string>(key: K): SettingValue<S[K]> {
    if (!this.values.has(key)) throw new BridgeError('invalid_message', `unknown setting ${key}`);
    return this.values.get(key) as SettingValue<S[K]>;
  }

  all(): SettingsValuesOf<S> {
    return Object.fromEntries(this.values) as SettingsValuesOf<S>;
  }

  on(_event: 'change', fn: (key: keyof S & string, value: SettingValue<S[keyof S]>) => void): () => void {
    return this.events.on('change', fn);
  }

  /**
   * Applies the player's values. The map is the whole state of the group: a key that is missing or
   * does not fit its definition takes the default.
   */
  apply(incoming: Readonly<Record<string, unknown>>): void {
    const changed: string[] = [];
    for (const [name, def] of Object.entries(this.schema)) {
      const raw = Object.hasOwn(incoming, name) ? incoming[name] : undefined;
      const next = (raw === undefined || raw === null ? undefined : fit(def, raw)) ?? this.defaults.get(name);
      if (Object.is(this.values.get(name), next)) continue;
      this.values.set(name, next);
      changed.push(name);
    }
    for (const name of changed) this.events.emit('change', name as keyof S & string, this.values.get(name) as SettingValue<S[keyof S]>);
  }

  dispose(): void {
    this.events.removeAll();
  }
}
