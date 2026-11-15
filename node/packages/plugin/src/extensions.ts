import { BridgeError, type ExtensionRef } from '@mineflayer-bridge/protocol';
import { registerWire } from './ext-wire.ts';
import type {
  ExtensionContext,
  ExtensionDefinition,
  ExtensionFactory,
  ExtensionInstance,
  ExtensionUse,
  MiniField,
  MiniSchema,
  OptionsInputOf,
  OptionsSchema,
  StandardSchemaV1,
} from './types.ts';
import { parseSpec, satisfies } from './util.ts';

const ID_PATTERN = /^[a-z0-9][a-z0-9_.-]*:[a-z0-9][a-z0-9_.-]*$/;

/**
 * Defines an extension. Returns a factory: call it with options to get an instance for `use()` or
 * an `extensions` list, or pass the factory itself for default options.
 *
 * @throws BridgeError `invalid_message` when `id` is not `namespace:name` or `version` is not semver.
 */
export function defineExtension<const S extends OptionsSchema | undefined = undefined, A = void>(definition: ExtensionDefinition<S, A>): ExtensionFactory<OptionsInputOf<S>, A> {
  if (!ID_PATTERN.test(definition.id)) throw new BridgeError('invalid_message', `extension id must be namespace:name, got ${definition.id}`);
  if (definition.id === 'user:default') throw new BridgeError('invalid_message', 'user:default is reserved for direct bot.bridge use');
  if (!/^\d+\.\d+\.\d+/.test(definition.version)) throw new BridgeError('invalid_message', `extension ${definition.id}: version must be semver, got ${definition.version}`);
  const def = definition as unknown as ExtensionDefinition<OptionsSchema | undefined, A>;
  registerWire(def);
  const factory = ((input?: OptionsInputOf<S>): ExtensionInstance<A> => ({ id: def.id, version: def.version, definition: def, input })) as ExtensionFactory<OptionsInputOf<S>, A>;
  Object.defineProperties(factory, {
    id: { value: def.id, enumerable: true },
    version: { value: def.version, enumerable: true },
    definition: { value: def, enumerable: true },
  });
  return factory;
}

function isStandard(schema: OptionsSchema): schema is StandardSchemaV1 {
  return typeof (schema as Partial<StandardSchemaV1>)['~standard'] === 'object';
}

function checkMiniField(name: string, f: MiniField, value: unknown, issues: string[]): unknown {
  if (value === undefined) {
    if (f.default !== undefined) return structuredClone(f.default);
    if (f.required === true) issues.push(`${name}: required`);
    return undefined;
  }
  switch (f.type) {
    case 'string':
      if (typeof value !== 'string') issues.push(`${name}: expected string`);
      else if (f.enum !== undefined && !f.enum.includes(value)) issues.push(`${name}: expected one of ${f.enum.join(', ')}`);
      return value;
    case 'number':
    case 'integer':
      if (typeof value !== 'number' || Number.isNaN(value)) issues.push(`${name}: expected number`);
      else if (f.type === 'integer' && !Number.isInteger(value)) issues.push(`${name}: expected integer`);
      else if (f.min !== undefined && value < f.min) issues.push(`${name}: must be >= ${f.min}`);
      else if (f.max !== undefined && value > f.max) issues.push(`${name}: must be <= ${f.max}`);
      return value;
    case 'boolean':
      if (typeof value !== 'boolean') issues.push(`${name}: expected boolean`);
      return value;
    case 'array':
      if (!Array.isArray(value)) issues.push(`${name}: expected array`);
      return value;
    case 'object':
      if (typeof value !== 'object' || value === null || Array.isArray(value)) issues.push(`${name}: expected object`);
      return value;
  }
}

/**
 * Validates options against the extension's schema and applies defaults.
 *
 * @throws BridgeError `invalid_message` listing every issue.
 */
export async function validateOptions(def: ExtensionDefinition<OptionsSchema | undefined, unknown>, input: unknown): Promise<unknown> {
  const schema = def.options;
  if (schema === undefined) return input ?? {};
  if (isStandard(schema)) {
    const result = await schema['~standard'].validate(input ?? {});
    if (result.issues !== undefined) {
      const text = result.issues
        .map((i) => {
          const path = (i.path ?? []).map((p) => String(typeof p === 'object' ? p.key : p)).join('.');
          return path === '' ? i.message : `${path}: ${i.message}`;
        })
        .join('; ');
      throw new BridgeError('invalid_message', `invalid options for ${def.id}: ${text}`, { details: result.issues });
    }
    return result.value;
  }
  if (input !== undefined && (typeof input !== 'object' || input === null || Array.isArray(input))) {
    throw new BridgeError('invalid_message', `invalid options for ${def.id}: expected an object`);
  }
  const given = (input ?? {}) as Record<string, unknown>;
  const issues: string[] = [];
  const out: Record<string, unknown> = { ...given };
  for (const [name, field] of Object.entries(schema as MiniSchema)) {
    const v = checkMiniField(name, field, given[name], issues);
    if (v !== undefined) out[name] = v;
  }
  if (issues.length > 0) throw new BridgeError('invalid_message', `invalid options for ${def.id}: ${issues.join('; ')}`, { details: issues });
  return out;
}

/** Normalises a use entry to an instance. */
export function toInstance(use: ExtensionUse): ExtensionInstance {
  return typeof use === 'function' ? use() : use;
}

/** Ids of the extensions an extension depends on. */
export function dependencyIds(def: ExtensionDefinition<OptionsSchema | undefined, unknown>): string[] {
  return (def.requires?.extensions ?? []).map((d) => (typeof d === 'string' ? d : d.id));
}

/**
 * Orders new instances so dependencies come first. Factories listed in `requires.extensions` that
 * are neither loaded nor given are added with default options.
 *
 * @throws BridgeError `invalid_message` on a duplicate, a missing dependency, or a cycle (naming it).
 */
export function orderForLoad(uses: readonly ExtensionUse[], loaded: ReadonlySet<string>): ExtensionInstance[] {
  const byId = new Map<string, ExtensionInstance>();
  const add = (inst: ExtensionInstance): void => {
    if (loaded.has(inst.id)) throw new BridgeError('invalid_message', `extension ${inst.id} is already loaded`);
    if (byId.has(inst.id)) throw new BridgeError('invalid_message', `extension ${inst.id} is listed twice`);
    byId.set(inst.id, inst);
  };
  for (const u of uses) add(toInstance(u));
  const queue = [...byId.values()];
  while (queue.length > 0) {
    const inst = queue.shift()!;
    for (const d of inst.definition.requires?.extensions ?? []) {
      if (typeof d === 'string' || loaded.has(d.id) || byId.has(d.id)) continue;
      const dep = d();
      byId.set(dep.id, dep);
      queue.push(dep);
    }
  }
  const order: ExtensionInstance[] = [];
  const state = new Map<string, 'visiting' | 'done'>();
  const visit = (id: string, path: string[]): void => {
    const s = state.get(id);
    if (s === 'done') return;
    if (s === 'visiting') {
      const cycle = [...path.slice(path.indexOf(id)), id];
      throw new BridgeError('invalid_message', `extension dependency cycle: ${cycle.join(' -> ')}`, { details: { cycle } });
    }
    const inst = byId.get(id)!;
    state.set(id, 'visiting');
    for (const dep of dependencyIds(inst.definition)) {
      if (loaded.has(dep)) continue;
      if (!byId.has(dep)) throw new BridgeError('invalid_message', `extension ${id} requires ${dep}, which is not loaded`);
      visit(dep, [...path, id]);
    }
    for (const dep of inst.definition.requires?.optionalExtensions ?? []) {
      if (byId.has(dep) && !loaded.has(dep)) visit(dep, [...path, id]);
    }
    state.set(id, 'done');
    order.push(inst);
  };
  for (const id of byId.keys()) visit(id, []);
  return order;
}

/** Capabilities from `requires.mod` that `capabilities` does not satisfy. */
export function missingCapabilities(required: readonly string[], capabilities: readonly ExtensionRef[]): string[] {
  return required.filter((spec) => !supportsSpec(spec, capabilities));
}

/** True when a capability spec (`id` or `id@range`) is satisfied. */
export function supportsSpec(spec: string, capabilities: readonly ExtensionRef[]): boolean {
  const { id, range } = parseSpec(spec);
  return capabilities.some((c) => c.id === id && satisfies(c.version, range));
}

/** A loaded extension inside one session. */
export interface LoadedExtension {
  readonly instance: ExtensionInstance;
  readonly ctx: ExtensionContext;
  readonly controller: AbortController;
  api: unknown;
}
