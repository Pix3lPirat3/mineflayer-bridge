import type { CommandArg, CommandNode, KindPayloads, Suggestion } from '@mineflayer-bridge/protocol';
import type { MockSelectorKind, MockSelectorValue } from './types.ts';

/**
 * The command engine for command trees, completion and target selectors, as the client
 * runs it for `/bridge`, the palette and `@bot` chat lines: tokenising with quotes, walking the
 * tree by names and aliases, positional argument parsing with token ranges, help lines and
 * completion planning. Lines parse to the same values and fail with the same messages as in the
 * real mod. Pure functions; the mock supplies selector resolution and completion sources.
 */

/** A word of a command line with its quotes removed, and its UTF-16 offsets into the line. */
export interface Token {
  readonly text: string;
  readonly start: number;
  readonly end: number;
}

/** The words of a line; `trailingSpace` says the player is starting a new word, `openQuote` that a quote was left open. */
export interface Tokens {
  readonly tokens: Token[];
  readonly trailingSpace: boolean;
  readonly openQuote: boolean;
}

/** A parsed selector; `resolve` turns it into values or an error message. */
export interface SelectorRequest {
  readonly text: string;
  readonly kind: MockSelectorKind;
  readonly options: Readonly<Record<string, string>>;
  readonly arg: CommandArg;
}

/** Resolves a selector for an argument: the values, or an error message. */
export type SelectorHook = (request: SelectorRequest) => readonly MockSelectorValue[] | string;

/** A position in blocks. */
export interface Origin {
  readonly x: number;
  readonly y: number;
  readonly z: number;
}

/** Outcome of parsing one command line against one command. */
export type ParsedLine =
  | { readonly kind: 'invoke'; readonly path: string[]; readonly args: Record<string, unknown> }
  | { readonly kind: 'usage'; readonly path: string[]; readonly usage: string[] }
  | { readonly kind: 'error'; readonly path: string[]; readonly error: string; readonly start: number; readonly end: number };

/** What the word at the end of a line completes. */
export type CompletionPlan =
  /** Node names only (the first word, or subcommands of a grouping node). */
  | { readonly kind: 'words'; readonly start: number; readonly words: Suggestion[] }
  /** An argument, plus subcommand names when the word could also name a child. */
  | {
      readonly kind: 'arg';
      readonly start: number;
      readonly root: CommandNode;
      readonly path: string[];
      readonly arg: CommandArg;
      readonly input: string;
      readonly args: Record<string, unknown>;
      readonly words: Suggestion[];
    }
  | { readonly kind: 'none'; readonly start: number; readonly error: string | undefined };

const SELECTOR_KINDS: ReadonlySet<string> = new Set(['me', 'self', 'looked', 'nearest', 'all']);
const SELECTOR_OPTIONS: readonly string[] = ['type', 'tag', 'distance', 'from', 'limit'];
const RESOURCE_ID = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/;
const WHOLE = /^[+-]?\d+$/;
const DECIMAL = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?[fFdD]?$/;
const NO_ORIGIN: Origin = { x: 0, y: 0, z: 0 };

/** Whether a character separates words: whitespace, but not the non-breaking spaces. */
export function isSpace(ch: string): boolean {
  if (ch === '\u00a0' || ch === '\u2007' || ch === '\u202f' || ch === '\ufeff') return false;
  return /\s/.test(ch) || (ch >= '\u001c' && ch <= '\u001f');
}

/** Removes the whitespace at both ends. */
export function strip(text: string): string {
  let from = 0;
  let to = text.length;
  while (from < to && isSpace(text[from]!)) from++;
  while (to > from && isSpace(text[to - 1]!)) to--;
  return text.slice(from, to);
}

/**
 * Splits a line into words: whitespace separates them, double quotes group words, and a backslash
 * escapes the next character inside quotes. A quote left open runs to the end of the line.
 */
export function split(line: string): Tokens {
  const tokens: Token[] = [];
  let current = '';
  let inToken = false;
  let quoted = false;
  let start = 0;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i]!;
    if (quoted) {
      if (ch === '\\' && i + 1 < line.length) current += line[++i]!;
      else if (ch === '"') quoted = false;
      else current += ch;
      continue;
    }
    if (isSpace(ch)) {
      if (inToken) {
        tokens.push({ text: current, start, end: i });
        current = '';
        inToken = false;
      }
      continue;
    }
    if (!inToken) {
      inToken = true;
      start = i;
    }
    if (ch === '"') quoted = true;
    else current += ch;
  }
  if (inToken) tokens.push({ text: current, start, end: line.length });
  return { tokens, trailingSpace: line !== '' && !quoted && isSpace(line[line.length - 1]!), openQuote: quoted };
}

/** The words of a line, keeping each word's offsets. */
export function tokenize(line: string): Token[] {
  return split(line).tokens;
}

/** The root of a `core:command` viewed as a tree node. */
export function rootNode(data: KindPayloads['core:command']): CommandNode {
  return { name: data.name, aliases: data.aliases, description: data.description, args: data.args, children: data.children, runnable: data.runnable };
}

/** The node a typed word names: an exact name first, then an exact alias, then either ignoring case. */
export function matchNode<N extends Pick<CommandNode, 'name' | 'aliases'>>(nodes: readonly N[], word: string): N | undefined {
  const lower = word.toLowerCase();
  return (
    nodes.find((n) => n.name === word) ??
    nodes.find((n) => n.aliases?.includes(word) === true) ??
    nodes.find((n) => n.name.toLowerCase() === lower || n.aliases?.some((a) => a.toLowerCase() === lower) === true)
  );
}

function runnable(node: CommandNode): boolean {
  return node.runnable !== false;
}

/** Whether an argument must be given: it is not optional and has no default. */
function required(a: CommandArg): boolean {
  return a.optional !== true && (a.defaultValue === undefined || a.defaultValue === null);
}

/** Descends from `root` while words `from` to `limit` name children. */
function walk(root: CommandNode, tokens: readonly Token[], from: number, limit: number): { node: CommandNode; path: string[]; next: number } {
  let node = root;
  const path: string[] = [];
  let i = from;
  while (i < limit) {
    const child = matchNode(node.children ?? [], tokens[i]!.text);
    if (child === undefined) break;
    node = child;
    path.push(child.name);
    i++;
  }
  return { node, path, next: i };
}

/** The argument signature of a node, such as `<block:block_id> [count:int=64]`; choices are joined by `|`. */
export function argSignature(node: CommandNode): string {
  return node.args
    .map((a) => {
      const type = a.choices !== undefined && a.choices.length > 0 ? a.choices.join('|') : a.type;
      const body = `${a.name}:${type}${a.defaultValue === undefined || a.defaultValue === null ? '' : `=${String(a.defaultValue)}`}`;
      return required(a) ? `<${body}>` : `[${body}]`;
    })
    .join(' ');
}

/** The usage of one node: its words, `<a|b>` for its subcommands when it takes no arguments, then the argument signature. */
function usage(words: readonly string[], node: CommandNode): string {
  const args = argSignature(node);
  const kids = node.children ?? [];
  let out = words.join(' ');
  if (kids.length > 0 && (args === '' || !runnable(node))) out += ` <${kids.map((c) => c.name).join('|')}>`;
  if (args !== '' && runnable(node)) out += ` ${args}`;
  return node.description === undefined || node.description === '' ? out : `${out} - ${node.description}`;
}

/** The help lines of a node: its usage and description, then one indented line per child. */
export function helpLines(words: readonly string[], node: CommandNode): string[] {
  return [usage(words, node), ...(node.children ?? []).map((c) => `  ${usage([...words, c.name], c)}`)];
}

/**
 * Parses `@kind[k=v,...]`; returns an error message for a malformed selector. Option values are kept
 * as typed, without the whitespace around them.
 */
export function parseSelector(text: string): { kind: MockSelectorKind; options: Record<string, string> } | string {
  const bracket = text.indexOf('[');
  const name = (bracket < 0 ? text.slice(1) : text.slice(1, bracket)).toLowerCase();
  if (!text.startsWith('@')) return 'A selector starts with @';
  if (!SELECTOR_KINDS.has(name)) return `Unknown selector '@${name}'; use @me, @self, @looked, @nearest or @all`;
  const kind = name as MockSelectorKind;
  if (bracket < 0) return { kind, options: {} };
  if (kind !== 'nearest' && kind !== 'all') return `@${name} takes no options`;
  if (!text.endsWith(']')) return "Expected ']' to close the selector options";
  const options: Record<string, string> = {};
  const inner = text.slice(bracket + 1, -1);
  const pairs = inner === '' ? [] : inner.split(',');
  if (pairs.length > 1 && pairs[pairs.length - 1] === '') pairs.pop();
  for (const pair of pairs) {
    if (strip(pair) === '') return 'Empty selector option';
    const eq = pair.indexOf('=');
    if (eq < 0) return 'Expected key=value in selector options';
    const key = strip(pair.slice(0, eq)).toLowerCase();
    const value = strip(pair.slice(eq + 1));
    if (value === '') return `'${key}' needs a value`;
    switch (key) {
      case 'type':
      case 'tag':
        break;
      case 'distance': {
        const problem = distanceProblem(value);
        if (problem !== undefined) return problem;
        break;
      }
      case 'from':
        if (value.toLowerCase() !== 'bot' && value.toLowerCase() !== 'player') return "'from' must be player or bot";
        break;
      case 'limit':
        if (!WHOLE.test(value)) return "'limit' must be a whole number";
        if (Number(value) < 1) return "'limit' must be at least 1";
        break;
      default:
        return `Unknown selector option '${key}'; use ${SELECTOR_OPTIONS.join(', ')}`;
    }
    options[key] = value;
  }
  return { kind, options };
}

/** Checks `N`, `..N`, `N..` or `M..N`. */
function distanceProblem(value: string): string | undefined {
  const dots = value.indexOf('..');
  const ends = dots < 0 ? [value] : [value.slice(0, dots), value.slice(dots + 2)];
  const open = ends.map((e) => e === '');
  if ((dots < 0 && open[0]) || open.every((o) => o) || ends.some((e) => e !== '' && !DECIMAL.test(e))) {
    return "'distance' must be a range such as ..16, 4.. or 2..8";
  }
  const [lo, hi] = ends.map((e) => (e === '' ? NaN : parseFloat(e)));
  const high = dots < 0 ? lo! : hi!;
  if (lo! < 0 || high < 0 || lo === Infinity || high === Infinity || lo! > high) return "'distance' must be a range of non-negative numbers, lowest first";
  return undefined;
}

/** Why an argument did not parse, with the offsets of the offending words. */
class ArgError {
  constructor(
    readonly message: string,
    readonly start: number,
    readonly end: number,
  ) {}
}

function isVec(v: MockSelectorValue): v is Origin {
  return typeof v === 'object' && v !== null && Number.isFinite(v.x) && Number.isFinite(v.y) && Number.isFinite(v.z);
}

/** Formats a number as the messages do. */
function plain(value: number): string {
  return String(value);
}

function range(value: number, a: CommandArg, what: string, start: number, end: number): ArgError | undefined {
  if (a.min !== undefined && value < a.min) return new ArgError(`${what} must be at least ${plain(a.min)}`, start, end);
  if (a.max !== undefined && value > a.max) return new ArgError(`${what} must be at most ${plain(a.max)}`, start, end);
  return undefined;
}

function decimal(text: string, name: string, start: number, end: number): number | ArgError {
  const t = strip(text);
  const v = DECIMAL.test(t) ? parseFloat(t) : NaN;
  return Number.isFinite(v) ? v : new ArgError(`${name} must be a number, not '${text}'`, start, end);
}

/** One coordinate: absolute, or `~` and `~offset` relative to `origin`. */
function coordinate(t: Token, origin: number, name: string): number | ArgError {
  const text = strip(t.text);
  if (!text.startsWith('~')) return decimal(text, name, t.start, t.end);
  const rest = text.slice(1);
  if (rest === '') return origin;
  const offset = decimal(rest, name, t.start, t.end);
  return offset instanceof ArgError ? offset : origin + offset;
}

/** Adds the `minecraft` namespace to a bare id and lower-cases it. */
function normalizeId(id: string): string {
  const lower = strip(id).toLowerCase();
  return lower.includes(':') ? lower : `minecraft:${lower}`;
}

/** Whether a position word stands for the whole position: `here` or a selector. */
function single(text: string): boolean {
  return text.toLowerCase() === 'here' || text.startsWith('@');
}

/** How many words an argument takes from `start`: three for a typed position, the rest of the line for greedy text. */
function widthOf(a: CommandArg, tokens: readonly Token[], start: number): number {
  if (a.type === 'greedy') return Math.max(tokens.length - start, 1);
  if (a.type === 'block_pos' || a.type === 'vec3') return start < tokens.length && single(tokens[start]!.text) ? 1 : 3;
  return 1;
}

/** Resolves one selector word through the hook. */
function select(a: CommandArg, t: Token, name: string, selectors: SelectorHook): { kind: MockSelectorKind; values: readonly MockSelectorValue[] } | ArgError {
  const sel = parseSelector(t.text);
  if (typeof sel === 'string') return new ArgError(sel, t.start, t.end);
  const resolved = selectors({ text: t.text, kind: sel.kind, options: sel.options, arg: a });
  if (typeof resolved === 'string') return new ArgError(`${name}: ${resolved}`, t.start, t.end);
  return { kind: sel.kind, values: resolved };
}

function entity(a: CommandArg, t: Token, name: string, selectors: SelectorHook): number | ArgError {
  if (t.text.startsWith('@')) {
    const sel = select(a, t, name, selectors);
    if (sel instanceof ArgError) return sel;
    if (sel.kind === 'all') return new ArgError(`${name} takes one entity; use @nearest instead of @all`, t.start, t.end);
    const v = sel.values[0];
    if (v === undefined) return new ArgError(`${name}: no entity matches ${t.text}`, t.start, t.end);
    return typeof v === 'number' ? v : new ArgError(`${name}: ${t.text} is not an entity`, t.start, t.end);
  }
  return WHOLE.test(t.text) ? Number(t.text) : new ArgError(`${name}: no loaded entity or player '${t.text}'`, t.start, t.end);
}

/** Splits an `entity_list` word at commas outside selector brackets. */
function listEntries(t: Token): Token[] {
  const out: Token[] = [];
  let depth = 0;
  let from = 0;
  for (let i = 0; i <= t.text.length; i++) {
    const ch = i < t.text.length ? t.text[i]! : ',';
    if (ch === '[') depth++;
    else if (ch === ']') depth = Math.max(0, depth - 1);
    else if (ch === ',' && (depth === 0 || i === t.text.length)) {
      out.push({ text: t.text.slice(from, i), start: t.start + from, end: t.start + i });
      from = i + 1;
    }
  }
  return out;
}

function entities(a: CommandArg, t: Token, name: string, selectors: SelectorHook): number[] | ArgError {
  const ids: number[] = [];
  for (const part of listEntries(t)) {
    if (part.text === '') return new ArgError(`${name}: empty entry in the list`, part.start, part.end);
    if (part.text.startsWith('@')) {
      const sel = select(a, part, name, selectors);
      if (sel instanceof ArgError) return sel;
      if (!sel.values.every((v) => typeof v === 'number')) return new ArgError(`${name}: ${part.text} did not resolve to entities`, part.start, part.end);
      if (sel.values.length === 0 && sel.kind !== 'all') return new ArgError(`${name}: no entity matches ${part.text}`, part.start, part.end);
      for (const id of sel.values as readonly number[]) if (!ids.includes(id)) ids.push(id);
      continue;
    }
    if (!WHOLE.test(part.text)) return new ArgError(`${name}: no loaded entity or player '${part.text}'`, part.start, part.end);
    if (!ids.includes(Number(part.text))) ids.push(Number(part.text));
  }
  return ids;
}

/** A position: three coordinates, `here`, or one selector. Block positions are floored. */
function position(a: CommandArg, parts: readonly Token[], name: string, selectors: SelectorHook, origin: Origin): Origin | ArgError {
  const block = a.type === 'block_pos';
  const out = (x: number, y: number, z: number): Origin => (block ? { x: Math.floor(x), y: Math.floor(y), z: Math.floor(z) } : { x, y, z });
  if (parts.length === 1) {
    const t = parts[0]!;
    // The mock's player looks at nothing.
    if (t.text.toLowerCase() === 'here') return new ArgError(`${name}: the crosshair is not on ${block ? 'a block' : 'anything'}`, t.start, t.end);
    const sel = select(a, t, name, selectors);
    if (sel instanceof ArgError) return sel;
    if (sel.kind === 'all') return new ArgError(`${name} takes one position; use @nearest instead of @all`, t.start, t.end);
    const v = sel.values[0];
    if (v === undefined) return new ArgError(`${name}: no entity matches ${t.text}`, t.start, t.end);
    return isVec(v) ? out(v.x, v.y, v.z) : new ArgError(`${name}: ${t.text} is not a position`, t.start, t.end);
  }
  const from = block ? { x: Math.floor(origin.x), y: Math.floor(origin.y), z: Math.floor(origin.z) } : origin;
  const x = coordinate(parts[0]!, from.x, name);
  if (x instanceof ArgError) return x;
  const y = coordinate(parts[1]!, from.y, name);
  if (y instanceof ArgError) return y;
  const z = coordinate(parts[2]!, from.z, name);
  if (z instanceof ArgError) return z;
  return out(x, y, z);
}

/** Converts the words of one argument into its value; `raw` is the line text they span, for greedy text. */
function parseValue(a: CommandArg, parts: readonly Token[], raw: string, selectors: SelectorHook, origin: Origin): unknown {
  const name = `'${a.name}'`;
  const first = parts[0]!;
  const start = first.start;
  const end = parts[parts.length - 1]!.end;
  const text = first.text;
  switch (a.type) {
    case 'greedy':
      return range(raw.length, a, `${name} length`, start, end) ?? raw;
    case 'int': {
      if (!WHOLE.test(strip(text))) return new ArgError(`${name} must be a whole number, not '${text}'`, start, end);
      const v = Number(strip(text));
      return range(v, a, name, start, end) ?? v;
    }
    case 'float': {
      const v = decimal(text, name, start, end);
      return v instanceof ArgError ? v : (range(v, a, name, start, end) ?? v);
    }
    case 'bool':
      switch (strip(text).toLowerCase()) {
        case 'true':
        case 'yes':
        case 'on':
        case '1':
          return true;
        case 'false':
        case 'no':
        case 'off':
        case '0':
          return false;
        default:
          return new ArgError(`${name} must be true or false, not '${text}'`, start, end);
      }
    case 'choice': {
      if (a.choices === undefined || a.choices.length === 0) return text;
      const typed = strip(text).toLowerCase();
      return a.choices.find((c) => c.toLowerCase() === typed) ?? new ArgError(`${name} must be one of ${a.choices.join(', ')}`, start, end);
    }
    case 'block_id':
    case 'item_id': {
      const id = normalizeId(text);
      return RESOURCE_ID.test(id) ? id : new ArgError(`${name}: unknown ${a.type === 'block_id' ? 'block' : 'item'} '${text}'`, start, end);
    }
    case 'entity':
      return entity(a, first, name, selectors);
    case 'entity_list': {
      const ids = entities(a, first, name, selectors);
      return ids instanceof ArgError ? ids : (range(ids.length, a, `${name} count`, start, end) ?? ids);
    }
    case 'block_pos':
    case 'vec3':
      return position(a, parts, name, selectors, origin);
    default:
      return range(text.length, a, `${name} length`, start, end) ?? text;
  }
}

/** The line text the words of one argument span; one word is taken without its quotes. */
function rawOf(line: string, parts: readonly Token[]): string {
  return parts.length === 1 ? parts[0]!.text : line.slice(parts[0]!.start, parts[parts.length - 1]!.end);
}

/** ` ; expected a, b` for a node with children, to end a message with. */
function expected(node: CommandNode): string {
  const names = (node.children ?? []).map((c) => c.name);
  return names.length === 0 ? '' : `; expected ${names.join(', ')}`;
}

/**
 * Parses a whole line whose first word already named `root` (by name or alias), as the real mod
 * does: subcommands by name or alias, then the node's arguments in order. Omitted optional
 * arguments take their declared defaults. A line that stops at a grouping node, or at a node with
 * subcommands whose required arguments are missing, yields the node's help lines. `origin` is where
 * the player stands, for `~` coordinates.
 */
export function parseLine(root: CommandNode, line: string, selectors: SelectorHook, origin: Origin = NO_ORIGIN): ParsedLine {
  const tokens = tokenize(line);
  const { node, path, next } = walk(root, tokens, 1, tokens.length);
  const kids = node.children ?? [];
  const words = [root.name, ...path];
  const fail = (error: string, start: number, end: number): ParsedLine => ({ kind: 'error', path, error, start, end });
  let i = next;
  if (i === tokens.length && kids.length > 0 && (!runnable(node) || node.args.some(required))) return { kind: 'usage', path, usage: helpLines(words, node) };
  if (!runnable(node)) {
    const word = tokens[i];
    if (word === undefined) return { kind: 'usage', path, usage: helpLines(words, node) };
    return fail(`Unknown subcommand '${word.text}' of '${words.join(' ')}'${expected(node)}`, word.start, word.end);
  }
  const args: Record<string, unknown> = {};
  for (const a of node.args) {
    if (i >= tokens.length) {
      if (required(a)) {
        const also = Object.keys(args).length === 0 && kids.length > 0 ? ` or a subcommand${expected(node)}` : '';
        return fail(`Missing argument '${a.name}'${also}`, line.length, line.length);
      }
      if (a.defaultValue !== undefined && a.defaultValue !== null) args[a.name] = a.defaultValue;
      continue;
    }
    const width = widthOf(a, tokens, i);
    if (i + width > tokens.length) return fail(`'${a.name}' needs x, y and z`, tokens[i]!.start, line.length);
    const parts = tokens.slice(i, i + width);
    const v = parseValue(a, parts, rawOf(line, parts), selectors, origin);
    if (v instanceof ArgError) return fail(v.message, v.start, v.end);
    args[a.name] = v;
    i += width;
  }
  const extra = tokens[i];
  if (extra !== undefined) {
    const end = tokens[tokens.length - 1]!.end;
    const also = node.args.length === 0 && kids.length > 0 ? `; '${extra.text}' is not a subcommand${expected(node)}` : '';
    return fail(`Too many arguments: ${line.slice(extra.start, end)}${also}`, extra.start, end);
  }
  return { kind: 'invoke', path, args };
}

/** Node names and aliases that start with `prefix`, with the description as tooltip. */
export function nameSuggestions(nodes: readonly CommandNode[], prefix: string): Suggestion[] {
  const out: Suggestion[] = [];
  for (const n of nodes) {
    if (n.name.startsWith(prefix)) {
      out.push(n.description === undefined ? { text: n.name } : { text: n.name, tooltip: n.description });
      continue;
    }
    for (const alias of n.aliases ?? []) if (alias.startsWith(prefix)) out.push({ text: alias, tooltip: `alias of ${n.name}` });
  }
  return out;
}

/**
 * Works out what the last word of `line` completes: a word after trailing whitespace is empty and
 * starts at the end. Earlier arguments are parsed (selectors resolved) so a dynamic source receives
 * them through the hook `selectorsFor` returns for the matched root; an argument that does not
 * parse stops completion with its error.
 */
export function planCompletion(roots: readonly CommandNode[], line: string, selectorsFor: (root: CommandNode) => SelectorHook, origin: Origin = NO_ORIGIN): CompletionPlan {
  const words = split(line);
  const tokens = words.tokens;
  const trailing = tokens.length === 0 || words.trailingSpace;
  const current: Token = trailing ? { text: '', start: line.length, end: line.length } : tokens.pop()!;
  const start = current.start;
  if (tokens.length === 0) return { kind: 'words', start, words: nameSuggestions(roots, current.text) };
  const root = matchNode(roots, tokens[0]!.text);
  if (root === undefined) return { kind: 'none', start, error: `unknown command ${tokens[0]!.text}` };
  const { node, path, next } = walk(root, tokens, 1, tokens.length);
  const rest = tokens.slice(next);
  const names = rest.length === 0 ? nameSuggestions(node.children ?? [], current.text) : [];
  if (!runnable(node)) return rest.length === 0 ? { kind: 'words', start, words: names } : { kind: 'none', start, error: `unknown subcommand ${rest[0]!.text}` };
  const args: Record<string, unknown> = {};
  let i = 0;
  for (const a of node.args) {
    if (a.type === 'greedy') {
      const from = rest[i]?.start ?? start;
      return { kind: 'arg', start: from, root, path, arg: a, input: line.slice(from), args, words: names };
    }
    const width = i < rest.length ? widthOf(a, rest, i) : 1;
    if (i + width > rest.length) return { kind: 'arg', start, root, path, arg: a, input: current.text, args, words: names };
    const parts = rest.slice(i, i + width);
    const v = parseValue(a, parts, rawOf(line, parts), selectorsFor(root), origin);
    if (v instanceof ArgError) return { kind: 'none', start, error: v.message };
    args[a.name] = v;
    i += width;
  }
  return names.length > 0 ? { kind: 'words', start, words: names } : { kind: 'none', start, error: undefined };
}

/** Client-side filtering of a suggestion list: by prefix, ignoring case and the `minecraft:` namespace. */
export function filterSuggestions(list: readonly (string | Suggestion)[], input: string, limit: number): Suggestion[] {
  const p = input.toLowerCase();
  const out: Suggestion[] = [];
  for (const v of list) {
    const s = typeof v === 'string' ? { text: v } : v;
    const t = s.text.toLowerCase();
    if (t.startsWith(p) || (t.startsWith('minecraft:') && t.slice('minecraft:'.length).startsWith(p))) out.push({ ...s });
    if (out.length >= limit) break;
  }
  return out;
}
