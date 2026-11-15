import {
  BridgeError,
  Messages,
  type FieldType,
  type KindName,
  type KindPayloads,
  type MerchantOffer,
  type MessagePayloads,
  type PickKind,
  type PickResult,
  type PromptKind,
  type Suggestion,
  type Target as WireTarget,
  type TargetSpec,
  type Vec3d,
  type WorldMenuEntry as WireWorldMenuEntry,
} from '@mineflayer-bridge/protocol';
import type { WorldMenuOptions } from './display-types.ts';
import type {
  ActionDefinition,
  ActionsApi,
  ChoiceOption,
  ChoicePromptOptions,
  CommandDefinition,
  CommandsApi,
  CrewApi,
  CrewDefinition,
  FormDefinition,
  FormFieldDef,
  FormButtonDef,
  FormResult,
  FormValues,
  InterceptContext,
  InterceptDefinition,
  InterceptsApi,
  InvocationContext,
  InvocationReturn,
  KeybindDefinition,
  KeybindsApi,
  PickApi,
  PickedBlock,
  PickedDirection,
  PickedEntity,
  PickedItem,
  PickedPolygon,
  PickedRegion,
  PickOptions,
  PromptApi,
  PromptOptions,
  PromptSurface,
  RefusedDecision,
  RefusedHandler,
  SurfaceKind,
  SurfaceRefusal,
  RecipePattern,
  RecipePromptOptions,
  Target,
  TargetFilter,
  ToolDefinition,
  ToolInputName,
  ToolsApi,
  ToolUse,
  TradeOffer,
  TradePromptOptions,
  Vec3Value,
} from './input-types.ts';
import { nonEmpty, segment, word } from './check.ts';
import { childList, compileCommand, completeArgument, resolvePath, withDefaults } from './commands.ts';
import { toSlotItem, type ItemContext } from './items.ts';
import type { Link } from './link.ts';
import type { ScopeObjects } from './scope.ts';
import type { CoreSet } from './players.ts';
import type { InboundInput } from './session.ts';
import type { BridgePlayer, Logger } from './types.ts';
import { asBridgeError, checkKeys, toVec3, waitFor, type Emitter } from './util.ts';

/**
 * Extra time the plugin waits past an input request's own deadline before timing out locally. The
 * mod enforces the deadline and answers first; the local timer only covers a mod that went silent.
 */
const INPUT_GRACE_MS = 5000;
const PICK_DEFAULT_TIMEOUT_MS = 60_000;
const WORLD_MENU_DEFAULT_TIMEOUT_MS = 60_000;
const WORLD_MENU_LAYOUTS: ReadonlySet<string> = new Set(['row', 'column', 'ring', 'grid']);
const PROMPT_DEFAULT_TIMEOUT_MS = 30_000;
/** A refused call opens again at most this often, whatever its handler says. */
export const MAX_REFUSED_RETRIES = 5;
/** Wait before opening again when the refusal names none, in ms. */
const REFUSED_DEFAULT_WAIT_MS = 1000;

/** Input requests, with the answer and the withdrawal message of each. */
interface InputRequests {
  'pick.request': { reply: MessagePayloads['pick.result']; cancel: 'pick.cancel' };
  'prompt.request': { reply: MessagePayloads['prompt.result']; cancel: 'prompt.cancel' };
  'world-menu.request': { reply: MessagePayloads['world-menu.result']; cancel: 'world-menu.cancel' };
  'form.open': { reply: MessagePayloads['form.result']; cancel: 'form.close' };
}
type InputRequest = keyof InputRequests;
const CANCEL: { readonly [N in InputRequest]: InputRequests[N]['cancel'] } = {
  'pick.request': 'pick.cancel',
  'prompt.request': 'prompt.cancel',
  'world-menu.request': 'world-menu.cancel',
  'form.open': 'form.close',
};
const SURFACE: { readonly [N in InputRequest]: SurfaceKind } = {
  'pick.request': 'pick',
  'prompt.request': 'prompt',
  'world-menu.request': 'world-menu',
  'form.open': 'form',
};

/** The refusal an error stands for, or `undefined` for any other error. */
export function refusalOf(error: BridgeError, surface: SurfaceKind, retries: number, player: BridgePlayer | undefined): SurfaceRefusal | undefined {
  const code = error.code as string;
  if (code !== 'cooldown' && code !== 'quota_exceeded') return undefined;
  return { surface, code, retryAfterMs: error.retryAfterMs, retries, error, player };
}

/**
 * Views and screens the bot asked the mod to open. An open carries no request id, so a refusal
 * comes back as a plain error naming the message and, in `details.key`, the view or screen. A mod
 * that sends no key is taken to mean the newest open of that kind the player has not touched yet.
 */
export class OpenTracker {
  private seq = 0;
  private readonly waiting = new Map<object, { surface: 'view' | 'screen'; seq: number; key: () => string | undefined; refuse: () => void }>();

  /** `handle` sent its open; `key` is its object key once the owner is known, `refuse` closes it again. */
  sent(handle: object, surface: 'view' | 'screen', key: () => string | undefined, refuse: () => void): void {
    this.waiting.set(handle, { surface, seq: ++this.seq, key, refuse });
  }

  /** The handle closed, or the player used it, so the open went through. */
  settled(handle: object): void {
    this.waiting.delete(handle);
  }

  /**
   * Closes the waiting handle of `surface` at `key`, or without a key the newest one. Returns false
   * when nothing matched.
   */
  refuse(surface: 'view' | 'screen', key: string | undefined): boolean {
    let found: [object, { seq: number; refuse: () => void }] | undefined;
    for (const [h, w] of this.waiting) {
      if (w.surface !== surface) continue;
      if (key !== undefined) {
        if (w.key() !== key) continue;
        found = [h, w];
        break;
      }
      if (found === undefined || w.seq > found[1].seq) found = [h, w];
    }
    if (found === undefined) return false;
    this.waiting.delete(found[0]);
    found[1].refuse();
    return true;
  }
}

/** Checks a call's `refused` option. @throws BridgeError `invalid_message`. */
function refusedOption(what: string, v: unknown): RefusedDecision | RefusedHandler | undefined {
  if (v === undefined || v === 'reject' || v === 'wait' || typeof v === 'function') return v as RefusedDecision | RefusedHandler | undefined;
  throw new BridgeError('invalid_message', `${what} refused must be 'reject', 'wait' or a function`);
}

/**
 * Target type of a global action that applies to nothing under the crosshair; used when a global
 * action is registered without `target`.
 */
export const NO_TARGET = 'none';

/** Session-wide events input features follow. */
export interface SessionInputEvents {
  /** The session opened; `fresh` is false after a resume. Fired after the snapshot was queued. */
  opened: [fresh: boolean];
  chat: [MessagePayloads['chat.message']];
}

/** What input features need from their session; {@link ItemContext.self} resolves `head: 'self'`. */
export interface InputHost extends ItemContext {
  /** The session on every hub link; see {@link CoreSet} for where each message goes. */
  readonly core: CoreSet;
  /** The primary link, for encoding and limits. */
  readonly link: Link;
  /** The player whose input is being handled, inside a handler. */
  origin(): BridgePlayer | undefined;
  readonly router: InputRouter;
  readonly log: Logger;
  readonly events: Emitter<SessionInputEvents>;
  /** Reports an error thrown by a user handler (the session's `error` event). */
  reportError(error: BridgeError): void;
  /** Views and screens whose open the mod may still refuse. */
  readonly opens: OpenTracker;
  /** The bridge-wide answer to a refused surface, when one was configured. */
  readonly onRefused: RefusedHandler | undefined;
  /** Tells the session the mod refused a surface (its `refused` event). */
  refused(refusal: SurfaceRefusal): void;
  /**
   * Resolves once the session is open. Rejects with `cancelled` when `signal` aborts and with `timeout`
   * after `timeoutMs` milliseconds.
   */
  whenOpen(signal: AbortSignal, timeoutMs: number): Promise<void>;
}

/** A handler registered under one declaration key. */
export interface Route {
  /**
   * The message this route accepts; `container` accepts `container.open`, `.close`, `.input` and
   * `.storage`, and `screen` accepts `screen.event`, `.open` and `.close`.
   */
  readonly accepts: 'action.invoked' | 'command.invoked' | 'keybind.event' | 'tool.event' | 'settings.values' | 'container' | 'screen' | 'element.event' | 'intercept.event';
  handle(payload: unknown, type: InboundInput): void;
  /** Answers `command.complete` for a `command.invoked` route; never rejects. */
  complete?(payload: MessagePayloads['command.complete']): Promise<Suggestion[]>;
  activate?(): void;
  deactivate?(): void;
}

const REFUSABLE: ReadonlySet<string> = new Set<Refusable>(['action.invoked', 'command.invoked', 'container.input', 'container.open', 'intercept.event', 'screen.event']);

/** Inbound messages without a route that still need an answer, because the player waits on them. */
export type Refusable = 'action.invoked' | 'command.invoked' | 'container.input' | 'container.open' | 'intercept.event' | 'screen.event';

function eventKey(type: InboundInput, p: MessagePayloads[InboundInput]): string | undefined {
  switch (type) {
    case 'container.open':
    case 'container.close':
    case 'container.input':
    case 'container.storage':
      return (p as MessagePayloads['container.input']).view;
    case 'intercept.event':
      return (p as MessagePayloads['intercept.event']).intercept;
    case 'screen.event':
    case 'screen.open':
    case 'screen.close':
      return (p as MessagePayloads['screen.event']).screen;
    case 'action.invoked':
      return (p as MessagePayloads['action.invoked']).action;
    case 'command.invoked':
      return (p as MessagePayloads['command.invoked']).command;
    case 'keybind.event':
      return (p as MessagePayloads['keybind.event']).keybind;
    case 'tool.event':
      return (p as MessagePayloads['tool.event']).tool;
    case 'settings.values':
      return (p as MessagePayloads['settings.values']).settings;
    case 'tool.state':
      return (p as MessagePayloads['tool.state']).active;
    case 'element.event':
      return (p as MessagePayloads['element.event']).element;
  }
}

/**
 * Routes input events of one session to the handler registered under the declaration's key
 * (events carry the full object key). Keys are stored relative to the owner.
 */
export class InputRouter {
  private readonly routes = new Map<string, Route>();
  private activeTool: string | undefined;

  constructor(
    private readonly owner: () => string | undefined,
    private readonly refuse: <N extends Refusable>(type: N, payload: MessagePayloads[N]) => void,
    private readonly log: Logger,
  ) {}

  set(key: string, route: Route): void {
    const prev = this.routes.get(key);
    if (prev !== undefined && key === this.activeTool) {
      this.activeTool = undefined;
      prev.deactivate?.();
    }
    this.routes.set(key, route);
  }

  delete(key: string, route?: Route): void {
    const prev = this.routes.get(key);
    if (prev === undefined || (route !== undefined && prev !== route)) return;
    this.routes.delete(key);
    if (key === this.activeTool) {
      this.activeTool = undefined;
      prev.deactivate?.();
    }
  }

  /** Relative key of the active tool, when it belongs to this session. */
  get active(): string | undefined {
    return this.activeTool;
  }

  private relative(key: string | undefined): string | undefined {
    const owner = this.owner();
    if (key === undefined || owner === undefined || !key.startsWith(`${owner}/`)) return undefined;
    return key.slice(owner.length + 1);
  }

  dispatch<N extends InboundInput>(type: N, payload: MessagePayloads[N]): void {
    const key = this.relative(eventKey(type, payload));
    if (type === 'tool.state') {
      this.setActiveTool(key);
      return;
    }
    const route = key === undefined ? undefined : this.routes.get(key);
    const accepts = type.startsWith('container.') ? 'container' : type.startsWith('screen.') ? 'screen' : type;
    if (route === undefined || route.accepts !== accepts) {
      this.log.debug(`no handler for ${type}`, { key: eventKey(type, payload) });
      // The player is waiting on invocations and clicks, so they always get an answer.
      if (REFUSABLE.has(type)) {
        this.refuse(type as Refusable, payload as MessagePayloads[Refusable]);
      }
      return;
    }
    route.handle(payload, type);
  }

  /**
   * Answers `command.complete` through `reply`. A command this session does not route answers an
   * empty list, so the mod's field never waits for its 300 ms deadline.
   */
  complete(payload: MessagePayloads['command.complete'], reply: (suggestions: Suggestion[]) => void): void {
    const key = this.relative(payload.command);
    const route = key === undefined ? undefined : this.routes.get(key);
    if (route?.complete === undefined) {
      this.log.debug('no completion handler', { key: payload.command });
      reply([]);
      return;
    }
    void route.complete(payload).then(reply);
  }

  private setActiveTool(key: string | undefined): void {
    const next = key !== undefined && this.routes.get(key)?.accepts === 'tool.event' ? key : undefined;
    if (next === this.activeTool) return;
    const prev = this.activeTool === undefined ? undefined : this.routes.get(this.activeTool);
    this.activeTool = next;
    prev?.deactivate?.();
    if (next !== undefined) this.routes.get(next)?.activate?.();
  }

  /** The session was lost: the mod re-sends `tool.state` after it opens again. */
  reset(): void {
    this.setActiveTool(undefined);
  }
}

/** Converts a wire vector (`Vec3d` or `Vec3i`) to a fresh plain object. */
function vec(v: { x: number; y: number; z: number }): Vec3Value {
  return { x: v.x, y: v.y, z: v.z };
}

function targetSpec(filter: TargetFilter | undefined, type: string): TargetSpec | undefined {
  if (filter === undefined) return undefined;
  checkKeys('target filter field', filter, FILTER_KEYS, { id: 'use ids', tag: 'use tags', types: 'use type' });
  const spec: TargetSpec = { type: filter.type ?? type };
  if (filter.ids !== undefined) spec.ids = [...filter.ids];
  if (filter.tags !== undefined) spec.tags = [...filter.tags];
  return spec;
}

/** Converts a wire target to the public shape, leaving out absent fields. */
function toTarget(t: WireTarget): Target {
  const out: Target = { type: t.type };
  if (t.block !== undefined) out.block = vec(t.block);
  if (t.face !== undefined) out.face = t.face;
  if (t.entity !== undefined) out.entity = t.entity;
  if (t.pos !== undefined) out.pos = vec(t.pos);
  if (t.item !== undefined) out.item = t.item;
  if (t.objectKey !== undefined) out.objectKey = t.objectKey;
  return out;
}

interface PickResults {
  point: Vec3Value;
  block: PickedBlock;
  region: PickedRegion;
  polygon: PickedPolygon;
  entity: PickedEntity;
  path: Vec3Value[];
  direction: PickedDirection;
  item: PickedItem;
}

/** Converts a `PickResult` union member to the friendly result of `kind`. */
function convertPick<K extends PickKind>(kind: K, r: PickResult): PickResults[K] {
  if (r.type !== kind) throw new BridgeError('invalid_message', `expected a ${kind} pick result, got ${r.type}`);
  let out: PickResults[PickKind];
  switch (r.type) {
    case 'point':
      out = vec(r.value.pos);
      break;
    case 'block':
      out = { pos: vec(r.value.pos), face: r.value.face, hit: vec(r.value.hit), state: r.value.state };
      break;
    case 'region': {
      const a = r.value.min;
      const b = r.value.max;
      out = { min: { x: Math.min(a.x, b.x), y: Math.min(a.y, b.y), z: Math.min(a.z, b.z) }, max: { x: Math.max(a.x, b.x), y: Math.max(a.y, b.y), z: Math.max(a.z, b.z) } };
      break;
    }
    case 'polygon':
      out = { vertices: r.value.vertices.map(vec), minY: r.value.minY, maxY: r.value.maxY };
      break;
    case 'entity': {
      const e: PickedEntity = { id: r.value.id, type: r.value.type, pos: vec(r.value.pos) };
      if (r.value.uuid !== undefined) e.uuid = r.value.uuid;
      out = e;
      break;
    }
    case 'path':
      out = r.value.points.map(vec);
      break;
    case 'direction':
      out = { yaw: r.value.yaw, pitch: r.value.pitch, cardinal: r.value.cardinal };
      break;
    case 'item': {
      const i: PickedItem = { id: r.value.id, count: r.value.count };
      if (r.value.components !== undefined) i.components = r.value.components;
      out = i;
      break;
    }
  }
  return out as PickResults[K];
}

/** Timeouts of one input request, in ms. */
interface Deadline {
  /** What the mod enforces. */
  readonly timeoutMs: number;
  /** When the plugin gives up locally, {@link INPUT_GRACE_MS} later. */
  readonly localMs: number;
}

/**
 * Resolves and checks the `timeoutMs` of an input request; `fallback` applies when none is given,
 * and `undefined` means the schema maximum.
 * @throws BridgeError `invalid_message` outside 1 to the schema maximum.
 */
function deadline(what: string, type: InputRequest, given: number | undefined, fallback: number | undefined): Deadline {
  const max = Messages[type].timeoutMs ?? (type === 'form.open' ? 3_600_000 : 600_000);
  const timeoutMs = given ?? fallback ?? max;
  if (!Number.isInteger(timeoutMs) || timeoutMs <= 0 || timeoutMs > max) throw new BridgeError('invalid_message', `${what} timeoutMs must be an integer from 1 to ${max}`);
  // A form defaults to the full maximum, so only its local wait may pass the schema limit.
  return { timeoutMs, localMs: type === 'form.open' ? timeoutMs + INPUT_GRACE_MS : Math.min(timeoutMs + INPUT_GRACE_MS, max) };
}

/** Public names use hyphens; the schema enums use underscores. */
function wireName<T extends string>(name: string): T {
  return name.replaceAll('-', '_') as T;
}

const FIELD_TYPES: ReadonlySet<string> = new Set(['text', 'number', 'slider', 'toggle', 'select', 'multi-select', 'item', 'block-pos', 'zone']);

/** Which surfaces each prompt kind may use. */
const SURFACES: Readonly<Record<PromptKind, readonly PromptSurface[]>> = {
  confirm: ['hud', 'dialog', 'chat', 'world'],
  // Trades and recipes travel as choice prompts on their own surfaces.
  choice: ['hud', 'dialog', 'world', 'stonecutter', 'enchanting', 'merchant', 'crafting'],
  text: ['hud', 'dialog', 'anvil', 'sign', 'book', 'chat'],
  number: ['hud', 'dialog'],
  lines: ['sign', 'hud', 'dialog'],
  pages: ['book', 'hud', 'dialog'],
};

const DEFAULT_SURFACE: Readonly<Record<PromptKind, PromptSurface>> = { confirm: 'hud', choice: 'hud', text: 'hud', number: 'hud', lines: 'sign', pages: 'book' };

/** The union of every caller option, as `ask` reads them; `ask` copies only these fields to the wire. */
type AnyPromptOptions<T> = PromptOptions<T> & {
  surface?: PromptSurface;
  item?: string;
  wood?: string;
  min?: number;
  max?: number;
  maxLength?: number;
};

/** What a prompt method built itself from validated input, never from the caller's options. */
interface PromptWire {
  /** The surface after the method's own default. */
  surface?: PromptSurface;
  options?: WireWorldMenuEntry[];
  offers?: MerchantOffer[];
}

function keys(...names: string[]): ReadonlySet<string> {
  return new Set(names);
}

const PROMPT_KEYS = ['default', 'timeoutMs', 'placement', 'at', 'signal', 'onTimeout', 'onSurface', 'surface', 'refused'];
/**
 * The options each prompt method accepts. Unknown keys reject, so a mistyped option (such as
 * `options` for a recipe's `items`) cannot leak onto the wire and confuse the mod.
 */
const PROMPT_OPTION_KEYS = {
  confirm: keys(...PROMPT_KEYS),
  choice: keys(...PROMPT_KEYS),
  trade: keys(...PROMPT_KEYS),
  recipe: keys(...PROMPT_KEYS, 'items'),
  text: keys(...PROMPT_KEYS, 'item', 'wood', 'maxLength'),
  number: keys(...PROMPT_KEYS, 'min', 'max'),
  lines: keys(...PROMPT_KEYS, 'wood', 'maxLength'),
  pages: keys(...PROMPT_KEYS, 'maxLength'),
} as const;
type PromptMethod = keyof typeof PROMPT_OPTION_KEYS;
const DEFAULT_HINT = 'use default';
const PROMPT_HINTS: Readonly<Record<PromptMethod, Readonly<Record<string, string>>>> = {
  confirm: { defaultValue: DEFAULT_HINT },
  choice: {
    defaultValue: DEFAULT_HINT,
    options: 'pass the choices (ids or { id, label, item, cost } objects) as the second argument',
    choices: 'pass the choices as the second argument',
  },
  trade: { defaultValue: DEFAULT_HINT, offers: 'pass the offers as the second argument', options: 'pass the offers as the second argument' },
  recipe: { defaultValue: DEFAULT_HINT, options: 'use items for the virtual items the player arranges', choices: 'use items for the virtual items the player arranges' },
  text: { defaultValue: DEFAULT_HINT, placeholder: 'use default for the initial text' },
  number: { defaultValue: DEFAULT_HINT, minimum: 'use min', maximum: 'use max' },
  lines: { defaultValue: DEFAULT_HINT },
  pages: { defaultValue: DEFAULT_HINT },
};
const PICK_KEYS = keys('prompt', 'timeoutMs', 'maxDistance', 'maxVolume', 'filter', 'urgent', 'signal', 'refused');
const PICK_HINTS: Readonly<Record<string, string>> = { text: 'use prompt', range: 'use maxDistance', distance: 'use maxDistance' };
const FILTER_KEYS = keys('type', 'ids', 'tags');
const CHOICE_KEYS = keys('id', 'label', 'item', 'cost');
const OFFER_KEYS = keys('id', 'cost', 'cost2', 'result', 'disabled', 'uses', 'maxUses');
const WORLD_MENU_KEYS = keys('at', 'entries', 'layout', 'radius', 'prompt', 'timeoutMs', 'signal', 'refused');
const WORLD_MENU_HINTS: Readonly<Record<string, string>> = { options: 'use entries', choices: 'use entries', items: 'use entries' };
const WORLD_MENU_ENTRY_KEYS = keys('id', 'label', 'item', 'block', 'entity', 'text');
const FORM_KEYS = keys('title', 'fields', 'submitLabel', 'surface', 'timeoutMs', 'signal', 'buttons', 'refused');
const FORM_BUTTON_KEYS = keys('id', 'label', 'icon', 'danger');
const FORM_FIELD_KEYS = keys('id', 'type', 'label', 'default', 'min', 'max', 'step', 'options', 'required');
const FORM_FIELD_HINTS: Readonly<Record<string, string>> = { choices: 'use options', defaultValue: 'use default', name: 'use id' };

/** An enchanting table shows three options. */
const ENCHANTING_OPTIONS = 3;
const MODIFIERS: Readonly<Record<string, string>> = { shift: 'shift', control: 'control', ctrl: 'control', alt: 'alt' };
const INTERCEPT_PRIORITIES: ReadonlySet<string> = new Set(['lowest', 'low', 'normal', 'high', 'highest', 'monitor']);

/** A 3x3 pattern of item ids or nulls, from three rows or nine cells. */
function pattern(v: unknown): RecipePattern | undefined {
  if (!Array.isArray(v)) return undefined;
  const cells: unknown[] = v.length === 3 && v.every((r) => Array.isArray(r) && r.length === 3) ? (v as unknown[][]).flat() : v.length === 9 ? v : [];
  if (cells.length !== 9) return undefined;
  const out: (string | null)[] = [];
  for (const c of cells) {
    if (c === null || c === undefined || c === '') out.push(null);
    else if (typeof c === 'string' && !/\s/.test(c)) out.push(c.includes(':') ? c : `minecraft:${c}`);
    else return undefined;
  }
  return [out.slice(0, 3), out.slice(3, 6), out.slice(6, 9)];
}

/** A list of at most `max` strings, or `undefined` for anything else. */
function strings(v: unknown, max: number): string[] | undefined {
  if (!Array.isArray(v) || v.length > max || !v.every((x) => typeof x === 'string')) return undefined;
  return [...(v as string[])];
}

const FORM_SURFACES: ReadonlySet<string> = new Set(['screen', 'dialog']);

/** Runs a command, action or screen handler and answers with `invocation.result`. */
export function invoke(host: InputHost, invocation: number, run: () => InvocationReturn): void {
  const reply = (ok: boolean, message: string | undefined, quiet = false): void => {
    const p: MessagePayloads['invocation.result'] = { invocation, ok };
    if (message !== undefined) p.message = message;
    if (quiet) p.quiet = true;
    host.core.send('invocation.result', host.link.encode('invocation.result', p));
  };
  let result: InvocationReturn;
  try {
    result = run();
  } catch (e) {
    reply(false, asBridgeError(e).message);
    return;
  }
  Promise.resolve(result).then(
    (v) => reply(true, typeof v === 'string' ? v : undefined),
    (e: unknown) => {
      const err = asBridgeError(e);
      // The player closed a form or prompt the handler waited on: they know, so no error toast.
      if (err.code === 'cancelled') reply(true, undefined, true);
      else reply(false, err.message);
    },
  );
}

/** Turns a synchronous throw of `fn` into a rejected promise, so validation errors reject like the request would. */
function settle<T>(fn: () => Promise<T>): Promise<T> {
  try {
    return fn();
  } catch (e) {
    return Promise.reject(asBridgeError(e));
  }
}

/** Calls a user callback, reporting what it throws instead of letting it escape into the protocol core. */
export function guard(host: Pick<InputHost, 'reportError'>, fn: () => unknown): void {
  try {
    const r = fn();
    if (r instanceof Promise) r.catch((e: unknown) => host.reportError(asBridgeError(e)));
  } catch (e) {
    host.reportError(asBridgeError(e));
  }
}

const CREW_KEYS: ReadonlySet<string> = new Set(['bots', 'groups']);

/** Checks a crew definition and turns it into its `core:crew` payload. */
function crewData(c: CrewDefinition): KindPayloads['core:crew'] {
  checkKeys('crew field', c, CREW_KEYS, { members: 'use bots', names: 'use bots' });
  if (!Array.isArray(c.bots)) throw new BridgeError('invalid_message', 'crew bots must be a list of bot names');
  const bots: string[] = [];
  for (const b of c.bots) {
    if (typeof b !== 'string' || b === '' || /\s/.test(b)) throw new BridgeError('invalid_message', `crew bot names are non-empty without spaces: ${JSON.stringify(b)}`);
    if (!bots.some((x) => x.toLowerCase() === b.toLowerCase())) bots.push(b);
  }
  const groups: { name: string; members: string[] }[] = [];
  for (const [name, members] of Object.entries(c.groups ?? {})) {
    word('crew group name', name);
    if (name.toLowerCase() === 'all') throw new BridgeError('invalid_message', 'a crew group cannot be named all');
    if (!Array.isArray(members)) throw new BridgeError('invalid_message', `crew group ${name} must list bot names`);
    for (const m of members) {
      if (typeof m !== 'string' || !bots.some((b) => b.toLowerCase() === m.toLowerCase())) {
        throw new BridgeError('invalid_message', `crew group ${name}: ${JSON.stringify(m)} is not one of the crew's bots`);
      }
    }
    groups.push({ name, members: [...members] });
  }
  return groups.length === 0 ? { bots } : { bots, groups };
}

/** Declarations of one kind under one directory of a scope (`~cmd/`, `~action/`, ...). */
class Registry {
  private readonly routes = new Map<string, Route>();

  constructor(
    private readonly host: InputHost,
    private readonly scope: ScopeObjects,
    private readonly dir: string,
  ) {}

  private rel(id: string): string {
    return `${this.scope.scope}/${this.dir}${id}`;
  }

  put<K extends KindName>(id: string, kind: K, data: KindPayloads[K], world: 'global' | undefined, route: Route): () => void {
    this.scope.put(`${this.dir}${id}`, kind, data, world === undefined ? {} : { world });
    this.routes.set(id, route);
    this.host.router.set(this.rel(id), route);
    return () => {
      if (this.routes.get(id) === route) this.remove(id);
    };
  }

  remove(id: string): void {
    const route = this.routes.get(id);
    this.scope.remove(`${this.dir}${id}`);
    this.routes.delete(id);
    if (route !== undefined) this.host.router.delete(this.rel(id), route);
  }

  has(id: string): boolean {
    return this.routes.has(id);
  }

  /** The public `register`, `unregister` and `has` of this registry. */
  api<D>(register: (id: string, def: D) => () => void): { register(id: string, def: D): () => void; unregister(id: string): void; has(id: string): boolean } {
    return { register, unregister: (id) => this.remove(id), has: (id) => this.has(id) };
  }

  /** Forgets the routes; the objects themselves go with the scope's prefix removal. */
  dispose(): void {
    for (const [id, route] of this.routes) this.host.router.delete(this.rel(id), route);
    this.routes.clear();
  }
}

/** Picks, prompts, forms, commands, actions, keybinds and tools of one scope. */
export class ScopeInput {
  readonly pick: PickApi;
  readonly prompt: PromptApi;
  readonly commands: CommandsApi;
  readonly orders: CommandsApi;
  readonly crew: CrewApi;
  readonly actions: ActionsApi;
  readonly intercepts: InterceptsApi;
  readonly keybinds: KeybindsApi;
  readonly tools: ToolsApi;
  private readonly registries: Registry[];

  constructor(
    private readonly host: InputHost,
    private readonly scope: ScopeObjects,
    private readonly signal: AbortSignal,
  ) {
    const pick = <K extends PickKind>(kind: K) => (o?: PickOptions) => this.requestPick(kind, o ?? {});
    this.pick = {
      point: pick('point'),
      block: pick('block'),
      region: pick('region'),
      polygon: pick('polygon'),
      entity: pick('entity'),
      path: pick('path'),
      direction: pick('direction'),
      item: pick('item'),
    };
    // Every prompt method checks its option keys first, before any option is read.
    const prompt = <R>(method: PromptMethod, o: unknown, run: () => Promise<R>): Promise<R> =>
      settle(() => {
        checkKeys(`${method} prompt option`, o, PROMPT_OPTION_KEYS[method], PROMPT_HINTS[method]);
        return run();
      });
    this.prompt = {
      confirm: (text, o = {}) => prompt('confirm', o, () => this.ask('confirm', text, undefined, o.default ?? false, o, {}, (v) => (typeof v === 'boolean' ? v : undefined))),
      choice: <C extends string>(text: string, choices: readonly (C | ChoiceOption<C>)[], o: ChoicePromptOptions<C> = {}) => prompt('choice', o, () => this.choose(text, choices, o)),
      trade: <I extends string>(text: string, offers: readonly TradeOffer<I>[], o: TradePromptOptions<I> = {}) => prompt('trade', o, () => this.trade(text, offers, o)),
      recipe: (text: string, o: RecipePromptOptions = {}) => prompt('recipe', o, () => this.recipe(text, o)),
      text: (text, o = {}) => prompt('text', o, () => this.ask('text', text, undefined, o.default ?? '', o, {}, (v) => (typeof v === 'string' ? v : undefined))),
      number: (text, o = {}) => prompt('number', o, () => this.ask('number', text, undefined, o.default ?? 0, o, {}, (v) => (typeof v === 'number' && Number.isFinite(v) ? v : undefined))),
      lines: (text, o = {}) => prompt('lines', o, () => this.ask('lines', text, undefined, o.default ?? [], o, {}, (v) => strings(v, 4))),
      pages: (text, o = {}) => prompt('pages', o, () => this.ask('pages', text, undefined, o.default ?? [], o, {}, (v) => strings(v, Infinity))),
    };
    const commands = new Registry(host, scope, '~cmd/');
    const orders = new Registry(host, scope, '~order/');
    const actions = new Registry(host, scope, '~action/');
    const intercepts = new Registry(host, scope, '~intercept/');
    const keybinds = new Registry(host, scope, '~key/');
    const tools = new Registry(host, scope, '~tool/');
    this.registries = [commands, orders, actions, intercepts, keybinds, tools];
    this.commands = commands.api((name, def: CommandDefinition) => this.registerCommand(commands, name, def, false));
    this.orders = orders.api((name, def: CommandDefinition) => this.registerCommand(orders, name, def, true));
    let crew: CrewDefinition | undefined;
    this.crew = {
      set: (c) => {
        const data = crewData(c);
        scope.put('~crew/crew', 'core:crew', data, { world: 'global' });
        crew = { bots: [...data.bots], groups: Object.fromEntries((data.groups ?? []).map((g) => [g.name, [...g.members]])) };
      },
      clear: () => {
        scope.remove('~crew/crew');
        crew = undefined;
      },
      get: () => crew,
    };
    this.actions = actions.api((id, def: ActionDefinition) => this.registerAction(actions, id, def));
    this.intercepts = intercepts.api((id, def: InterceptDefinition) => this.registerIntercept(intercepts, id, def));
    this.keybinds = keybinds.api((id, def: KeybindDefinition) => this.registerKeybind(keybinds, id, def));
    this.tools = {
      ...tools.api((id, def: ToolDefinition) => this.registerTool(tools, id, def)),
      active: () => {
        const prefix = `${scope.scope}/~tool/`;
        const a = host.router.active;
        return a !== undefined && a.startsWith(prefix) ? a.slice(prefix.length) : undefined;
      },
    };
  }

  /**
   * Sends an input request. While the session is still connecting it waits for it to open, within the
   * request's own timeout and signal, so `await bot.bridge.ready` followed by a pick works. A request
   * the mod refuses is opened again when `refused` (or the bridge-wide handler) says to wait, at
   * most {@link MAX_REFUSED_RETRIES} times and never past the request's own deadline.
   */
  private async send<N extends InputRequest>(
    type: N,
    payload: MessagePayloads[N],
    d: Deadline,
    user: AbortSignal | undefined,
    refused: RefusedDecision | RefusedHandler | undefined,
  ): Promise<InputRequests[N]['reply']> {
    const signal = user === undefined ? this.signal : AbortSignal.any([user, this.signal]);
    const endsAt = Date.now() + d.timeoutMs;
    let p = payload;
    let localMs = d.localMs;
    for (let retries = 0; ; retries++) {
      if (!this.host.core.connected) await this.host.whenOpen(signal, Math.max(1, endsAt - Date.now()));
      let error: BridgeError;
      try {
        return (await this.host.core.request(type, p, localMs, signal, CANCEL[type])) as InputRequests[N]['reply'];
      } catch (e) {
        error = asBridgeError(e);
      }
      const refusal = refusalOf(error, SURFACE[type], retries, this.host.origin());
      if (refusal === undefined) throw error;
      this.host.refused(refusal);
      if (retries >= MAX_REFUSED_RETRIES || (await this.decide(refused, refusal)) !== 'wait') throw error;
      const wait = refusal.retryAfterMs ?? REFUSED_DEFAULT_WAIT_MS;
      const left = Math.floor(endsAt - Date.now() - wait);
      // Waiting would use up the call's own deadline, so the refusal is the answer.
      if (left < 1) throw error;
      await waitFor<void>(signal, wait, 'cancelled while waiting to open again', (s) => s.ok(), () => undefined);
      p = { ...p, timeoutMs: left };
      localMs = Math.min(d.localMs, left + INPUT_GRACE_MS);
    }
  }

  private async decide(option: RefusedDecision | RefusedHandler | undefined, refusal: SurfaceRefusal): Promise<RefusedDecision> {
    const o = option ?? this.host.onRefused;
    if (o === undefined) return 'reject';
    if (typeof o !== 'function') return o;
    try {
      return (await o(refusal)) === 'wait' ? 'wait' : 'reject';
    } catch (e) {
      this.host.reportError(asBridgeError(e));
      return 'reject';
    }
  }

  private async requestPick<K extends PickKind>(kind: K, o: PickOptions): Promise<PickResults[K]> {
    checkKeys(`${kind} pick option`, o, PICK_KEYS, PICK_HINTS);
    const d = deadline('pick', 'pick.request', o.timeoutMs, PICK_DEFAULT_TIMEOUT_MS);
    const refused = refusedOption('pick', o.refused);
    const p: MessagePayloads['pick.request'] = { kind, timeoutMs: d.timeoutMs };
    if (o.prompt !== undefined) p.prompt = o.prompt;
    if (o.maxDistance !== undefined) p.maxDistance = o.maxDistance;
    if (o.maxVolume !== undefined) p.maxVolume = o.maxVolume;
    const filter = targetSpec(o.filter, kind);
    if (filter !== undefined) p.filter = filter;
    if (o.urgent === true) p.urgent = true;
    const r = await this.send('pick.request', p, d, o.signal, refused);
    return convertPick(kind, r.result);
  }

  /**
   * Sends one `prompt.request`, built field by field from `o` and `wire` so nothing the caller did
   * not mean as a prompt option reaches the mod. The caller checked the keys of `o`.
   * @param wire Entries, offers and surface the method built itself from validated input.
   * @param strict The answer only makes sense on the requested surface, so a fallback rejects with
   * `unsupported`.
   */
  private async ask<T>(
    kind: PromptKind,
    text: string,
    choices: readonly string[] | undefined,
    def: T,
    o: AnyPromptOptions<T>,
    wire: PromptWire,
    coerce: (v: unknown) => T | undefined,
    strict = false,
  ): Promise<T> {
    const d = deadline('prompt', 'prompt.request', o.timeoutMs, PROMPT_DEFAULT_TIMEOUT_MS);
    const refused = refusedOption('prompt', o.refused);
    if (typeof text !== 'string') throw new BridgeError('invalid_message', 'prompt text must be a string');
    const surface = wire.surface ?? o.surface ?? (o.placement === 'world' ? 'world' : DEFAULT_SURFACE[kind]);
    if (!SURFACES[kind].includes(surface)) throw new BridgeError('invalid_message', `a ${kind} prompt cannot use the ${String(surface)} surface; use one of ${SURFACES[kind].join(', ')}`);
    if (o.maxLength !== undefined && (!Number.isInteger(o.maxLength) || o.maxLength <= 0)) throw new BridgeError('invalid_message', 'prompt maxLength must be a positive integer');
    if (o.min !== undefined && !Number.isFinite(o.min)) throw new BridgeError('invalid_message', 'prompt min must be a finite number');
    if (o.max !== undefined && !Number.isFinite(o.max)) throw new BridgeError('invalid_message', 'prompt max must be a finite number');
    if (o.min !== undefined && o.max !== undefined && o.min > o.max) throw new BridgeError('invalid_message', 'prompt min must not exceed max');
    // `placement` predates surfaces; both name a world menu so older and newer mods agree.
    const p: MessagePayloads['prompt.request'] = { kind, text, defaultValue: def, timeoutMs: d.timeoutMs, placement: surface === 'world' ? 'world' : 'hud', surface };
    if (choices !== undefined) p.choices = [...choices];
    if (o.at !== undefined) p.at = toVec3(o.at, 'prompt position') satisfies Vec3d;
    if (o.item !== undefined) p.item = o.item;
    if (o.wood !== undefined) p.wood = o.wood;
    if (o.min !== undefined) p.min = o.min;
    if (o.max !== undefined) p.max = o.max;
    if (o.maxLength !== undefined) p.maxLength = o.maxLength;
    if (wire.options !== undefined) p.options = wire.options;
    if (wire.offers !== undefined) p.offers = wire.offers;
    const r = await this.send('prompt.request', p, d, o.signal, refused);
    const value = r.value === undefined || r.value === null ? def : coerce(r.value);
    if (value === undefined) throw new BridgeError('invalid_message', `the mod answered a ${kind} prompt with ${JSON.stringify(r.value)}`);
    // An older mod does not report the surface; it can only have used the one requested.
    const used = (r.surface ?? surface) as PromptSurface;
    if (strict && used !== surface) throw new BridgeError('unsupported', `the mod showed this prompt on ${used} instead of ${surface}`);
    if (o.onSurface !== undefined) guard(this.host, () => o.onSurface!(used));
    if (r.timedOut === true && o.onTimeout !== undefined) guard(this.host, () => o.onTimeout!(value));
    return value;
  }

  /** `prompt.choice`: plain choices, or options with items and cost lines for the item surfaces. */
  private choose<C extends string>(text: string, choices: readonly (C | ChoiceOption<C>)[], o: ChoicePromptOptions<C>): Promise<C> {
    if (!Array.isArray(choices) || choices.length === 0) throw new BridgeError('invalid_message', 'a choice prompt needs at least one choice');
    const ids: C[] = [];
    const entries: WireWorldMenuEntry[] = [];
    let rich = false;
    for (const c of choices) {
      if (typeof c !== 'string') checkKeys('choice option field', c, CHOICE_KEYS, { name: 'use label', text: 'use label or cost' });
      const id: C = typeof c === 'string' ? c : c.id;
      if (typeof (id as unknown) !== 'string' || id === '' || ids.includes(id)) throw new BridgeError('invalid_message', `choice ids must be unique non-empty strings: ${String(id)}`);
      ids.push(id);
      const w: WireWorldMenuEntry = { id, label: id };
      if (typeof c !== 'string') {
        rich = true;
        if (c.label !== undefined) w.label = String(c.label);
        if (c.item !== undefined) w.item = toSlotItem(c.item, this.host);
        // The enchanting cost line travels as the entry text.
        if (c.cost !== undefined) w.text = String(c.cost);
      }
      entries.push(w);
    }
    const surface = o.surface ?? (o.placement === 'world' ? 'world' : 'hud');
    if (surface === 'enchanting' && ids.length > ENCHANTING_OPTIONS) throw new BridgeError('invalid_message', `an enchanting prompt shows at most ${ENCHANTING_OPTIONS} options`);
    // The mod may answer with the choice itself or with its index.
    const coerce = (v: unknown): C | undefined => (typeof v === 'string' ? ids.find((c) => c === v) : typeof v === 'number' ? ids[v] : undefined);
    const wire: PromptWire = { surface };
    if (rich || surface === 'stonecutter' || surface === 'enchanting') wire.options = entries;
    return this.ask('choice', text, ids, o.default ?? ids[0]!, o, wire, coerce);
  }

  /** `prompt.trade`: a choice among offers on a merchant screen. */
  private trade<I extends string>(text: string, offers: readonly TradeOffer<I>[], o: TradePromptOptions<I>): Promise<I> {
    if (!Array.isArray(offers) || offers.length === 0) throw new BridgeError('invalid_message', 'a trade prompt needs at least one offer');
    const surface = o.surface ?? 'merchant';
    if (surface !== 'merchant' && surface !== 'hud' && surface !== 'dialog') throw new BridgeError('invalid_message', `a trade prompt cannot use the ${String(surface)} surface; use merchant, hud or dialog`);
    const ids: I[] = [];
    const open = new Set<string>();
    const wire = offers.map((x): MerchantOffer => {
      checkKeys('trade offer field', x, OFFER_KEYS, { price: 'use cost', buy: 'use cost', sell: 'use result', item: 'use result' });
      if (typeof x.id !== 'string' || x.id === '' || ids.includes(x.id)) throw new BridgeError('invalid_message', `offer ids must be unique non-empty strings: ${String(x.id)}`);
      ids.push(x.id);
      const w: MerchantOffer = { id: x.id, cost: toSlotItem(x.cost, this.host), result: toSlotItem(x.result, this.host) };
      if (x.cost2 !== undefined) w.cost2 = toSlotItem(x.cost2, this.host);
      if (x.disabled === true) w.disabled = true;
      else open.add(x.id);
      for (const k of ['uses', 'maxUses'] as const) {
        const n = x[k];
        if (n === undefined) continue;
        if (!Number.isInteger(n) || n < 0 || n > 0xffffffff) throw new BridgeError('invalid_message', `offer ${k} must be a non-negative integer`);
        w[k] = n;
      }
      return w;
    });
    const coerce = (v: unknown): I | undefined => {
      const id = typeof v === 'number' ? ids[v] : typeof v === 'string' ? ids.find((i) => i === v) : undefined;
      return id !== undefined && open.has(id) ? id : undefined;
    };
    const def = o.default ?? ids.find((i) => open.has(i)) ?? ids[0]!;
    return this.ask('choice', text, ids, def, o, { surface, offers: wire }, coerce);
  }

  /** `prompt.recipe`: a 3x3 crafting grid. */
  private recipe(text: string, o: RecipePromptOptions): Promise<RecipePattern> {
    const surface = o.surface ?? 'crafting';
    if (surface !== 'crafting') throw new BridgeError('invalid_message', 'a recipe prompt can only use the crafting surface');
    const def = o.default === undefined ? pattern(Array.from({ length: 9 }, () => null)) : pattern(o.default);
    if (def === undefined) throw new BridgeError('invalid_message', 'a recipe default must be three rows of three item ids or nulls');
    const wire: PromptWire = { surface };
    if (o.items !== undefined) {
      if (!Array.isArray(o.items)) throw new BridgeError('invalid_message', 'recipe items must be a list of items');
      const seen = new Set<string>();
      wire.options = o.items.map((i): WireWorldMenuEntry => {
        const item = toSlotItem(i, this.host);
        if (seen.has(item.id)) throw new BridgeError('invalid_message', `recipe items must differ: ${item.id}`);
        seen.add(item.id);
        return { id: item.id, item };
      });
    }
    return this.ask('choice', text, undefined, def, o, wire, pattern, true);
  }

  /** An input request that resolves with the chosen entry id. */
  async worldMenu<const I extends string>(o: WorldMenuOptions<I>): Promise<I> {
    checkKeys('world menu option', o, WORLD_MENU_KEYS, WORLD_MENU_HINTS);
    const d = deadline('world menu', 'world-menu.request', o.timeoutMs, WORLD_MENU_DEFAULT_TIMEOUT_MS);
    const refused = refusedOption('world menu', o.refused);
    if (!Array.isArray(o.entries) || o.entries.length === 0) throw new BridgeError('invalid_message', 'a world menu needs at least one entry');
    const ids = new Set<string>();
    const entries = o.entries.map((x): WireWorldMenuEntry => {
      checkKeys('world menu entry field', x, WORLD_MENU_ENTRY_KEYS, { name: 'use label' });
      if (typeof x.id !== 'string' || x.id === '' || ids.has(x.id)) throw new BridgeError('invalid_message', `world menu entry ids must be unique non-empty strings: ${String(x.id)}`);
      ids.add(x.id);
      const w: WireWorldMenuEntry = { id: x.id };
      if (x.label !== undefined) w.label = String(x.label);
      if (x.item !== undefined) w.item = toSlotItem(x.item, this.host);
      if (x.block !== undefined) w.block = nonEmpty('world menu block', x.block);
      if (x.entity !== undefined) w.entity = nonEmpty('world menu entity', x.entity);
      if (x.text !== undefined) w.text = String(x.text);
      if (w.label === undefined && w.item === undefined && w.block === undefined && w.entity === undefined && w.text === undefined) {
        throw new BridgeError('invalid_message', `world menu entry ${x.id} needs a label, item, block, entity or text`);
      }
      return w;
    });
    if (o.layout !== undefined && !WORLD_MENU_LAYOUTS.has(o.layout)) throw new BridgeError('invalid_message', `unknown world menu layout ${String(o.layout)}; use row, column, ring or grid`);
    if (o.radius !== undefined && (!Number.isFinite(o.radius) || o.radius <= 0)) throw new BridgeError('invalid_message', 'world menu radius must be a positive number');
    const p: MessagePayloads['world-menu.request'] = { at: toVec3(o.at, 'world menu position'), entries, timeoutMs: d.timeoutMs };
    if (o.layout !== undefined) p.layout = o.layout;
    if (o.radius !== undefined) p.radius = o.radius;
    if (o.prompt !== undefined) p.prompt = o.prompt;
    const r = await this.send('world-menu.request', p, d, o.signal, refused);
    if (!ids.has(r.entry)) throw new BridgeError('invalid_message', `the mod answered a world menu with unknown entry ${JSON.stringify(r.entry)}`);
    return r.entry as I;
  }

  /** Opens a form and resolves with its values, defaults filled in. */
  async form<const F extends readonly FormFieldDef[], const B extends readonly FormButtonDef[] = readonly []>(def: FormDefinition<F, B>): Promise<FormResult<F, B>> {
    checkKeys('form option', def, FORM_KEYS, { submit: 'use submitLabel', name: 'use title' });
    if (!Array.isArray(def.fields)) throw new BridgeError('invalid_message', 'form fields must be a list');
    const d = deadline('form', 'form.open', def.timeoutMs, undefined);
    const refused = refusedOption('form', def.refused);
    const surface = def.surface ?? 'screen';
    if (!FORM_SURFACES.has(surface)) throw new BridgeError('invalid_message', `a form cannot use the ${String(surface)} surface; use screen or dialog`);
    const seen = new Set<string>();
    const fields = def.fields.map((f): MessagePayloads['form.open']['fields'][number] => {
      checkKeys('form field property', f, FORM_FIELD_KEYS, FORM_FIELD_HINTS);
      if (typeof f.id !== 'string' || f.id === '' || seen.has(f.id)) throw new BridgeError('invalid_message', `form field ids must be unique non-empty strings: ${String(f.id)}`);
      if (!FIELD_TYPES.has(f.type)) throw new BridgeError('invalid_message', `unknown form field type ${String(f.type)}`);
      seen.add(f.id);
      const w: MessagePayloads['form.open']['fields'][number] = { id: f.id, type: wireName<FieldType>(f.type) };
      if (f.label !== undefined) w.label = f.label;
      if (f.default !== undefined) w.defaultValue = f.default;
      if (f.min !== undefined) w.min = f.min;
      if (f.max !== undefined) w.max = f.max;
      if (f.step !== undefined) w.step = f.step;
      if (f.options !== undefined) w.options = [...f.options];
      if (f.required === true) w.required = true;
      return w;
    });
    const p: MessagePayloads['form.open'] = { title: def.title, fields, surface, timeoutMs: d.timeoutMs };
    if (def.submitLabel !== undefined) p.submitLabel = def.submitLabel;
    if (def.buttons !== undefined && def.buttons.length > 0) {
      const ids = new Set<string>();
      p.buttons = def.buttons.map((b) => {
        checkKeys('form button property', b, FORM_BUTTON_KEYS, {});
        if (typeof b.id !== 'string' || b.id === '' || ids.has(b.id)) throw new BridgeError('invalid_message', `form button ids must be unique non-empty strings: ${String(b.id)}`);
        if (typeof b.label !== 'string' || b.label === '') throw new BridgeError('invalid_message', `form button ${b.id} needs a label`);
        ids.add(b.id);
        return { id: b.id, label: b.label, ...(b.icon === undefined ? {} : { icon: b.icon }), danger: b.danger === true };
      });
      if (seen.has('button')) throw new BridgeError('invalid_message', 'a form with buttons cannot have a field named button');
    }
    const r = await this.send('form.open', p, d, def.signal, refused);
    const values: Record<string, unknown> = {};
    for (const f of def.fields) {
      const v = r.values[f.id];
      if (v !== undefined && v !== null) values[f.id] = v;
      else if (f.default !== undefined) values[f.id] = f.default;
    }
    if (typeof r.button === 'string') values['button'] = r.button;
    return values as FormResult<F, B>;
  }

  private registerCommand(reg: Registry, name: string, def: CommandDefinition, orders: boolean): () => void {
    word(orders ? 'order name' : 'command name', name);
    const cmd = compileCommand(name, def);
    if (orders) cmd.data.orders = true;
    const route: Route = {
      accepts: 'command.invoked',
      handle: (payload) => {
        const p = payload as MessagePayloads['command.invoked'];
        const found = resolvePath(cmd.root, p.path ?? []);
        invoke(this.host, p.invocation, () => {
          if (typeof found === 'string') throw new BridgeError('invalid_message', found);
          const { node, path } = found;
          // The mod lists children itself; this only covers a stale tree.
          if (node.run === undefined) throw new BridgeError('invalid_message', childList(node));
          const ctx: InvocationContext = { invocation: p.invocation, key: p.command, path, player: this.host.origin(), source: p.source ?? undefined };
          if (orders) {
            if (p.bots === undefined || p.bots === null || p.bots.length === 0) throw new BridgeError('invalid_message', `order ${name} came without bots`);
            (ctx as { bots?: readonly string[] }).bots = p.bots;
          }
          return node.run(withDefaults(node, p.args), ctx);
        });
      },
      complete: (p) => completeArgument(cmd.root, p.command, p, (e) => this.host.reportError(e)),
    };
    return reg.put(name, 'core:command', cmd.data, 'global', route);
  }

  private registerAction(reg: Registry, id: string, def: ActionDefinition): () => void {
    segment('action id', id);
    if (typeof def.run !== 'function') throw new BridgeError('invalid_message', `action ${id} needs a run function`);
    if (def.scope !== undefined && def.scope !== 'world' && def.scope !== 'global') throw new BridgeError('invalid_message', `action scope must be world or global: ${String(def.scope)}`);
    const target = def.target ?? (def.scope === 'global' ? { type: NO_TARGET } : undefined);
    if (typeof target?.type !== 'string' || target.type === '') {
      throw new BridgeError('invalid_message', `action ${id} needs a target type${def.scope === 'global' ? '' : " (only global actions may leave the target out)"}`);
    }
    const data: KindPayloads['core:action'] = { label: def.label, target: targetSpec(target, target.type)! };
    if (def.icon !== undefined) data.icon = def.icon;
    if (def.order !== undefined) data.order = def.order;
    const route: Route = {
      accepts: 'action.invoked',
      handle: (payload) => {
        const p = payload as MessagePayloads['action.invoked'];
        const ctx: InvocationContext = { invocation: p.invocation, key: p.action, path: [], player: this.host.origin(), source: p.source ?? undefined, bots: p.bots ?? undefined };
        invoke(this.host, p.invocation, () => def.run(toTarget(p.target), ctx));
      },
    };
    // A world action binds to the bot's current world like a shape; a global one renders anywhere.
    return reg.put(id, 'core:action', data, def.scope === 'global' ? 'global' : undefined, route);
  }

  /** A `core:intercept` routed by `intercept.event`. */
  private registerIntercept(reg: Registry, id: string, def: InterceptDefinition): () => void {
    segment('intercept id', id);
    if (typeof def.run !== 'function') throw new BridgeError('invalid_message', `intercept ${id} needs a run function`);
    if (typeof def.target?.type !== 'string' || def.target.type === '') throw new BridgeError('invalid_message', `intercept ${id} needs a target type`);
    const input = def.input ?? 'secondary';
    if (input !== 'primary' && input !== 'secondary') throw new BridgeError('invalid_message', `intercept input must be primary or secondary: ${String(input)}`);
    const mode = def.mode ?? 'observe';
    if (mode !== 'observe' && mode !== 'cancel') throw new BridgeError('invalid_message', `intercept mode must be observe or cancel: ${String(mode)}`);
    const priority = def.priority ?? 'normal';
    if (!INTERCEPT_PRIORITIES.has(priority)) throw new BridgeError('invalid_message', `unknown intercept priority ${String(priority)}; use ${[...INTERCEPT_PRIORITIES].join(', ')}`);
    const data: KindPayloads['core:intercept'] = { target: targetSpec(def.target, def.target.type)!, input, mode, priority };
    if (def.modifier !== undefined) {
      const m = MODIFIERS[def.modifier];
      if (m === undefined) throw new BridgeError('invalid_message', `intercept modifier must be shift, control or alt: ${String(def.modifier)}`);
      data.modifier = m;
    }
    const route: Route = {
      accepts: 'intercept.event',
      handle: (payload) => {
        const p = payload as MessagePayloads['intercept.event'];
        const ctx: InterceptContext = { invocation: p.invocation, key: p.intercept, path: [], cancelled: p.cancelled, player: this.host.origin() };
        const target = toTarget(p.target);
        // Only a cancelling rule is answered: the player sees its result like an action's.
        if (mode === 'cancel') invoke(this.host, p.invocation, () => def.run(target, ctx));
        else guard(this.host, () => def.run(target, ctx));
      },
    };
    return reg.put(id, 'core:intercept', data, def.scope === 'global' ? 'global' : undefined, route);
  }

  private registerKeybind(reg: Registry, id: string, def: KeybindDefinition): () => void {
    segment('keybind id', id);
    if (def.keys.length === 0) throw new BridgeError('invalid_message', `keybind ${id} needs at least one key`);
    const keys = def.keys.map((k) => {
      if (typeof k !== 'string' || k === '') throw new BridgeError('invalid_message', `keybind ${id} has an empty key`);
      return k.includes('.') ? k : `key.keyboard.${k.toLowerCase()}`;
    });
    const activation = def.activation ?? (def.onRelease !== undefined ? (def.onPress !== undefined ? 'both' : 'release') : 'press');
    const data: KindPayloads['core:keybind'] = { label: def.label, keys, activation };
    if (def.context !== undefined) data.context = def.context;
    if (def.priority !== undefined) data.priority = def.priority;
    if (def.consumes !== undefined) data.consumes = def.consumes;
    const route: Route = {
      accepts: 'keybind.event',
      handle: (payload) => {
        const fn = (payload as MessagePayloads['keybind.event']).pressed ? def.onPress : def.onRelease;
        if (fn !== undefined) guard(this.host, fn);
      },
    };
    return reg.put(id, 'core:keybind', data, 'global', route);
  }

  private registerTool(reg: Registry, id: string, def: ToolDefinition): () => void {
    segment('tool id', id);
    const on = def.on ?? {};
    const handled = (['primary', 'secondary', 'middle', 'scroll', 'key'] as const).filter((i) => on[i] !== undefined);
    const inputs: ToolInputName[] = def.inputs !== undefined ? [...def.inputs] : handled;
    const data: KindPayloads['core:tool'] = { label: def.label, targets: [...(def.targets ?? ['block'])], inputs };
    if (def.icon !== undefined) data.icon = def.icon;
    const route: Route = {
      accepts: 'tool.event',
      handle: (payload) => {
        const p = payload as MessagePayloads['tool.event'];
        const use: ToolUse = { input: p.input, target: p.target === undefined ? undefined : toTarget(p.target), delta: p.delta, key: p.key };
        guard(this.host, () => {
          switch (p.input) {
            case 'primary':
            case 'secondary':
            case 'middle':
              return on[p.input]?.(use.target, use);
            case 'scroll':
              return on.scroll?.(p.delta ?? 0, use);
            case 'key':
              return p.key === undefined ? undefined : on.key?.(p.key, use);
          }
        });
      },
      activate: () => {
        if (def.onActivate !== undefined) guard(this.host, def.onActivate);
      },
      deactivate: () => {
        if (def.onDeactivate !== undefined) guard(this.host, def.onDeactivate);
      },
    };
    return reg.put(id, 'core:tool', data, 'global', route);
  }

  /** Forgets every route of this scope. */
  dispose(): void {
    for (const r of this.registries) r.dispose();
  }
}

