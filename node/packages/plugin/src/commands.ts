import { BridgeError, type ArgType, type CommandArg, type CommandNode, type CompletionSpec, type KindPayloads, type MessagePayloads, type Suggestion, type TargetSpec } from '@mineflayer-bridge/protocol';
import type {
  ChatContent,
  ChatSpanInput,
  CommandArgDef,
  CommandDefinition,
  CommandNodeDefinition,
  CompletionFilter,
  CompletionFunction,
  CompletionSourceName,
  CompletionSourceOptions,
  InvocationContext,
  InvocationReturn,
} from './input-types.ts';
import { contentParts, styledSpan } from './chat.ts';
import { word } from './check.ts';
import { asBridgeError } from './util.ts';

/** Deepest tree a command may declare, counting the root as level 1. */
const MAX_DEPTH = 8;
/** Most suggestions one `command.suggestions` answer carries; longer lists are cut. */
const MAX_SUGGESTIONS = 50;
const MAX_LIMIT = 50;

const ARG_TYPES: Readonly<Record<CommandArgDef['type'], ArgType>> = {
  string: 'string',
  greedy: 'greedy',
  int: 'int',
  float: 'float',
  bool: 'bool',
  enum: 'choice',
  'block-pos': 'block_pos',
  vec3: 'vec3',
  'block-id': 'block_id',
  'item-id': 'item_id',
  entity: 'entity',
  'entity-list': 'entity_list',
  player: 'player',
  bot: 'bot',
  zone: 'zone',
  point: 'point',
};

/** Public source names use hyphens; the schema strings use underscores. */
const SOURCES: Readonly<Record<CompletionSourceName, string>> = {
  choices: 'choices',
  entities: 'entities',
  blocks: 'blocks',
  'block-ids': 'block_ids',
  'item-ids': 'item_ids',
  sounds: 'sounds',
  particles: 'particles',
  enchantments: 'enchantments',
  players: 'players',
  bots: 'bots',
  zones: 'zones',
  points: 'points',
  waypoints: 'waypoints',
};

/** `TargetSpec.type` of a completion filter, by source. */
const FILTER_TYPES: Readonly<Partial<Record<CompletionSourceName, string>>> = {
  entities: 'entity',
  blocks: 'block',
  'block-ids': 'block',
  'item-ids': 'item',
};

type NodeRun = (args: Record<string, unknown>, ctx: InvocationContext) => InvocationReturn;

/** One node of a registered command, as the bot side routes invocations and completions. */
export interface CompiledNode {
  /** Space-separated path from the root, for messages, e.g. `mine start`. */
  readonly label: string;
  /** Canonical name of this node. */
  readonly name: string;
  readonly args: readonly CommandArgDef[];
  readonly run: NodeRun | undefined;
  /** Children by canonical name. */
  readonly children: ReadonlyMap<string, CompiledNode>;
  /** Children by alias. */
  readonly aliases: ReadonlyMap<string, CompiledNode>;
  /** Dynamic completion functions by argument name. */
  readonly completers: ReadonlyMap<string, CompletionFunction>;
}

/** A command definition checked and converted to its `core:command` data and routing tree. */
export interface CompiledCommand {
  readonly data: KindPayloads['core:command'];
  readonly root: CompiledNode;
}

function invalid(message: string): never {
  throw new BridgeError('invalid_message', message);
}

function strings(what: string, v: unknown): string[] {
  if (!Array.isArray(v) || v.some((s) => typeof s !== 'string' || s === '')) invalid(`${what} must be a list of non-empty strings`);
  return [...(v as string[])];
}

function filterSpec(label: string, source: CompletionSourceName, f: CompletionFilter): TargetSpec {
  if (typeof f !== 'object' || f === null) invalid(`${label}: completion filter must be an object`);
  if (f.types !== undefined && f.ids !== undefined) invalid(`${label}: completion filter takes types or ids, not both`);
  const spec: TargetSpec = { type: FILTER_TYPES[source] ?? SOURCES[source] };
  const ids = f.types ?? f.ids;
  if (ids !== undefined) spec.ids = strings(`${label}: completion filter ids`, ids);
  if (f.tags !== undefined) spec.tags = strings(`${label}: completion filter tags`, f.tags);
  return spec;
}

function sourceName(label: string, v: unknown): CompletionSourceName {
  if (typeof v !== 'string' || !Object.hasOwn(SOURCES, v)) invalid(`${label}: unknown completion source ${String(v)}`);
  return v as CompletionSourceName;
}

/** Converts `complete` to the wire spec; a function becomes the `dynamic` source. */
function completion(label: string, a: CommandArgDef): CompletionSpec | undefined {
  const c = a.complete;
  if (c === undefined) return undefined;
  if (typeof c === 'function') return { source: 'dynamic', limit: MAX_SUGGESTIONS };
  const o: CompletionSourceOptions = typeof c === 'string' ? { source: c } : c;
  if (typeof o !== 'object' || o === null) invalid(`${label}: complete must be a source name, source options or a function`);
  const source = sourceName(label, o.source);
  if (source === 'choices' && (a.choices === undefined || a.choices.length === 0)) invalid(`${label}: the choices completion source needs choices`);
  const spec: CompletionSpec = { source: SOURCES[source] };
  if (o.near !== undefined) {
    if (o.near !== 'player' && o.near !== 'bot') invalid(`${label}: completion near must be player or bot`);
    spec.near = o.near;
  }
  if (o.radius !== undefined) {
    if (typeof o.radius !== 'number' || !Number.isFinite(o.radius) || o.radius <= 0) invalid(`${label}: completion radius must be a positive number of blocks`);
    spec.radius = o.radius;
  }
  if (o.filter !== undefined) spec.filter = filterSpec(label, source, o.filter);
  if (o.limit !== undefined) {
    if (!Number.isInteger(o.limit) || o.limit < 1 || o.limit > MAX_LIMIT) invalid(`${label}: completion limit must be an integer from 1 to ${MAX_LIMIT}`);
    spec.limit = o.limit;
  }
  return spec;
}

function compileArgs(label: string, defs: unknown, completers: Map<string, CompletionFunction>): { wire: CommandArg[]; defs: CommandArgDef[] } {
  if (defs === undefined) return { wire: [], defs: [] };
  if (!Array.isArray(defs)) invalid(`command ${label}: args must be a list`);
  const seen = new Set<string>();
  const wire: CommandArg[] = [];
  let sawOptional = false;
  const list = defs as CommandArgDef[];
  list.forEach((a, i) => {
    if (typeof a !== 'object' || a === null) invalid(`command ${label}: argument ${i} must be an object`);
    if (typeof a.name !== 'string' || a.name === '') invalid(`command ${label}: argument ${i} needs a name`);
    if (seen.has(a.name)) invalid(`command ${label}: duplicate argument ${a.name}`);
    seen.add(a.name);
    const type = Object.hasOwn(ARG_TYPES, a.type) ? ARG_TYPES[a.type] : undefined;
    if (type === undefined) invalid(`unknown argument type ${String(a.type)} in command ${label}`);
    const optional = a.optional ?? a.default !== undefined;
    // Arguments are positional, so only a suffix may be left out.
    if (!optional && sawOptional) invalid(`command ${label}: required argument ${a.name} follows an optional one`);
    if (type === 'greedy' && i !== list.length - 1) invalid(`command ${label}: greedy argument ${a.name} must be last`);
    sawOptional ||= optional;
    const w: CommandArg = { name: a.name, type, optional };
    if (a.default !== undefined) w.defaultValue = a.default;
    if (a.min !== undefined) w.min = a.min;
    if (a.max !== undefined) w.max = a.max;
    if (a.choices !== undefined) w.choices = strings(`command ${label}: choices of ${a.name}`, a.choices);
    if (a.description !== undefined) w.description = a.description;
    const spec = completion(`command ${label}: argument ${a.name}`, a);
    if (spec !== undefined) w.completion = spec;
    if (typeof a.complete === 'function') completers.set(a.name, a.complete);
    wire.push(w);
  });
  return { wire, defs: list };
}

interface Compiled {
  wire: CommandNode;
  node: CompiledNode;
}

function compileNode(label: string, name: string, def: CommandNodeDefinition, depth: number): Compiled {
  if (typeof def !== 'object' || def === null) invalid(`command ${label} must be an object`);
  if (depth > MAX_DEPTH) invalid(`command ${label} is nested deeper than ${MAX_DEPTH} levels`);
  if (def.run !== undefined && typeof def.run !== 'function') invalid(`command ${label}: run must be a function`);
  if (def.children !== undefined && (typeof def.children !== 'object' || def.children === null)) invalid(`command ${label}: children must be an object of nodes`);
  const entries = Object.entries(def.children ?? {});
  if (def.run === undefined && entries.length === 0) invalid(`command ${label} needs a run function or children`);
  const completers = new Map<string, CompletionFunction>();
  const args = compileArgs(label, def.args, completers);
  const children = new Map<string, CompiledNode>();
  const aliases = new Map<string, CompiledNode>();
  const wireChildren: CommandNode[] = [];
  const taken = new Set<string>();
  for (const [childName, childDef] of entries) {
    word(`subcommand name of ${label}`, childName);
    const child = compileNode(`${label} ${childName}`, childName, childDef, depth + 1);
    for (const n of [childName, ...(child.wire.aliases ?? [])]) {
      // A typed word must pick exactly one sibling.
      if (taken.has(n)) invalid(`command ${label}: ${n} names two subcommands`);
      taken.add(n);
    }
    children.set(childName, child.node);
    for (const alias of child.wire.aliases ?? []) aliases.set(alias, child.node);
    wireChildren.push(child.wire);
  }
  const wire: CommandNode = { name, args: args.wire, runnable: def.run !== undefined };
  if (def.aliases !== undefined) {
    const list = strings(`command ${label}: aliases`, def.aliases).map((a) => word(`alias of ${label}`, a));
    if (list.includes(name) || new Set(list).size !== list.length) invalid(`command ${label}: aliases must be unique and differ from the name`);
    if (list.length > 0) wire.aliases = list;
  }
  if (def.description !== undefined) wire.description = def.description;
  if (wireChildren.length > 0) wire.children = wireChildren;
  const run = def.run === undefined ? undefined : (def.run.bind(def) as NodeRun);
  return { wire, node: { label, name, args: args.defs, run, children, aliases, completers } };
}

/**
 * Checks a command definition and builds its `core:command` data. Throws `invalid_message` for a
 * malformed definition.
 */
export function compileCommand(name: string, def: CommandDefinition): CompiledCommand {
  const { wire, node } = compileNode(name, name, def as CommandNodeDefinition, 1);
  const data: KindPayloads['core:command'] = { name, args: wire.args, runnable: wire.runnable ?? true };
  if (wire.description !== undefined) data.description = wire.description;
  if (wire.aliases !== undefined) data.aliases = wire.aliases;
  if (wire.children !== undefined) data.children = wire.children;
  return { data, root: node };
}

/**
 * Finds the node a `path` names below `root`, accepting aliases as well as canonical names, and the
 * canonical path to it. Returns an error message when a name matches no child.
 */
export function resolvePath(root: CompiledNode, path: readonly string[]): { node: CompiledNode; path: string[] } | string {
  let node = root;
  const canonical: string[] = [];
  for (const step of path) {
    const next = node.children.get(step) ?? node.aliases.get(step);
    if (next === undefined) return `${node.label} has no subcommand ${step}`;
    node = next;
    canonical.push(next.name);
  }
  return { node, path: canonical };
}

/** The parsed arguments with each omitted argument's default filled in. */
export function withDefaults(node: CompiledNode, args: Readonly<Record<string, unknown>>): Record<string, unknown> {
  const out: Record<string, unknown> = { ...args };
  for (const a of node.args) if (a.default !== undefined && (out[a.name] === undefined || out[a.name] === null)) out[a.name] = a.default;
  return out;
}

/** Message for running a node that only groups children: it lists them. */
export function childList(node: CompiledNode): string {
  return `${node.label}: choose one of ${[...node.children.keys()].join(', ')}`;
}

function toSuggestion(label: string, v: unknown): Suggestion {
  if (typeof v === 'string' && v !== '') return { text: v };
  if (typeof v !== 'object' || v === null) invalid(`${label}: a suggestion must be a non-empty string or { text, display, tooltip }`);
  const o = v as Record<string, unknown>;
  if (typeof o['text'] !== 'string' || o['text'] === '') invalid(`${label}: suggestion text must be a non-empty string`);
  const s: Suggestion = { text: o['text'] };
  for (const k of ['display', 'tooltip'] as const) {
    const f = o[k];
    if (f === undefined) continue;
    if (typeof f !== 'string') invalid(`${label}: suggestion ${k} must be a string`);
    s[k] = f;
  }
  const kind = o['kind'];
  if (kind !== undefined) {
    if (kind !== 'value' && kind !== 'option' && kind !== 'keyword') invalid(`${label}: suggestion kind must be value, option or keyword`);
    s.kind = kind;
  }
  return s;
}

/**
 * Answers one `command.complete` for completion source `dynamic`. Never rejects: an unknown node or
 * argument answers an empty list, and an error of the completion function answers an empty list
 * after `report` received it. At most {@link MAX_SUGGESTIONS} suggestions are returned.
 */
export async function completeArgument(root: CompiledNode, key: string, p: MessagePayloads['command.complete'], report: (e: BridgeError) => void): Promise<MessagePayloads['command.suggestions']> {
  const found = resolvePath(root, p.path);
  if (typeof found === 'string') return { suggestions: [] };
  const { node, path } = found;
  const fn = node.completers.get(p.arg);
  if (fn === undefined) return { suggestions: [] };
  const label = `completion of ${node.label} ${p.arg}`;
  try {
    const out: unknown = await fn(p.input, { args: { ...p.args }, path, arg: p.arg, key, ...(p.bots === undefined || p.bots === null ? {} : { bots: [...p.bots] }) });
    const result = Array.isArray(out) ? { suggestions: out } : (out as { suggestions?: unknown; usage?: unknown } | null);
    if (typeof result !== 'object' || result === null || !Array.isArray(result.suggestions)) invalid(`${label} must return a list of suggestions or { suggestions, usage }`);
    const suggestions = (result.suggestions as unknown[]).slice(0, MAX_SUGGESTIONS).map((v: unknown) => toSuggestion(label, v));
    if (result.usage === undefined) return { suggestions };
    const usage = contentParts(result.usage as ChatContent).map((s) => {
      if (typeof s === 'object' && s !== null && (s as ChatSpanInput).click !== undefined) invalid(`${label}: usage spans cannot have a click`);
      return styledSpan(s);
    });
    return usage.length === 0 ? { suggestions } : { suggestions, usage };
  } catch (e) {
    report(asBridgeError(e));
    return { suggestions: [] };
  }
}
