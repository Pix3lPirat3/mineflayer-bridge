import type { BridgeError } from '@mineflayer-bridge/protocol';
import type { EffectsApi, WorldMenuOptions } from './display-types.ts';
import type { BridgePlayer, ColorLike, Vec3Like } from './types.ts';

/** Something the bot puts in front of the player. */
export type SurfaceKind = 'pick' | 'prompt' | 'form' | 'world-menu' | 'view' | 'screen';

/**
 * A surface the mod refused to open at once, without showing it:
 * - `cooldown`: the player dismissed one of this bot's surfaces a moment ago, so the mod holds back
 *   what the player did not ask for (for about 5 s, then 15 s, then 30 s when they keep dismissing).
 *   A surface that follows the player running one of the bot's commands or actions is never held back.
 * - `quota_exceeded`: more than 16 input requests of this session already wait.
 */
export interface SurfaceRefusal {
  readonly surface: SurfaceKind;
  readonly code: 'cooldown' | 'quota_exceeded';
  /** How long the mod asks to wait before trying again, in ms; `undefined` when it did not say. */
  readonly retryAfterMs: number | undefined;
  /** How many times this call was opened again already; 0 at the first refusal. */
  readonly retries: number;
  /** The error the call rejects with unless it waits. */
  readonly error: BridgeError;
  /** The linked player whose mod refused, when the call was made for one. */
  readonly player: BridgePlayer | undefined;
}

/** `reject`: the call rejects with the refusal. `wait`: it waits and opens again. */
export type RefusedDecision = 'reject' | 'wait';

/**
 * Decides what a refused pick, prompt, form or world menu does. With `'wait'` the call waits
 * `retryAfterMs` (1 s when the mod gave none) and opens again, at most 5 times, and only while that
 * still fits in the call's own `timeoutMs`, which keeps counting from the first try; otherwise, and
 * with `'reject'`, the call rejects with the refusal (`BridgeError` `cooldown` or `quota_exceeded`,
 * `retryAfterMs` on the error). Aborting the call's signal ends the wait with `cancelled`. A handler
 * that throws counts as `'reject'` and is reported through the `error` event.
 */
export type RefusedHandler = (refusal: SurfaceRefusal) => RefusedDecision | Promise<RefusedDecision>;

/** Bridge-wide handling of surfaces, as `surfaces` in the bridge options. */
export interface SurfaceOptions {
  /** Default for every pick, prompt, form and world menu without its own `refused`. Default: reject. */
  onRefused?: RefusedHandler | undefined;
}

/**
 * What a target is: `block`, `entity`, `item`, `bot` (a connected bot's player entity), `zone`,
 * `point`, `waypoint`, `element` (an interactive display), `notification`, `air` (nothing under the
 * crosshair), `none` (no target at all, see `NO_TARGET`), or a type an extension defines.
 */
export type TargetType = 'block' | 'entity' | 'item' | 'bot' | 'zone' | 'point' | 'waypoint' | 'element' | 'notification' | 'air' | 'none' | (string & {});

/** A plain position or vector in blocks, as returned by picks and targets. */
export interface Vec3Value {
  x: number;
  y: number;
  z: number;
}

/**
 * Narrows what a pick, action or tool accepts. Omitted lists mean any.
 */
export interface TargetFilter {
  /** Target type. Picks default it to their own kind. */
  type?: TargetType;
  /** Registry ids or entity types, e.g. `minecraft:chest` or `minecraft:cow`; for `bot` targets, bot usernames. */
  ids?: readonly string[];
  /**
   * Block, item or entity tags, e.g. `c:chests`; any one matches. Entities also have kind tags the
   * game lacks: `mineflayer_bridge:player` (not a bot), `mineflayer_bridge:bot`,
   * `mineflayer_bridge:hostile`, `mineflayer_bridge:animal` (other living mobs) and
   * `mineflayer_bridge:item` (items on the ground).
   */
  tags?: readonly string[];
}

/** Options shared by every pick. */
export interface PickOptions {
  /** Action-bar hint shown with the bot's name. */
  prompt?: string;
  /**
   * Deadline in ms, counted by the mod from when it receives the request (queue time included).
   * Default 60000, at most 600000. On expiry the pick rejects with `timeout`.
   */
  timeoutMs?: number;
  /** Maximum distance from the player, in blocks. */
  maxDistance?: number;
  /** Maximum region volume, in blocks (region picks). */
  maxVolume?: number;
  filter?: TargetFilter;
  /** Jump the queue without interrupting the active request. Needs a permission on the mod. */
  urgent?: boolean;
  /** Aborting sends `pick.cancel` and rejects with `cancelled`. */
  signal?: AbortSignal;
  /**
   * What to do when the mod refuses to open this: `'reject'`, `'wait'` or a handler. Default: the
   * bridge's `surfaces.onRefused`, otherwise `'reject'`. See {@link RefusedHandler}.
   */
  refused?: RefusedDecision | RefusedHandler;
}

/** Result of `pick.block`. */
export interface PickedBlock {
  /** Block position (integer coordinates). */
  pos: Vec3Value;
  /** Clicked face: `down`, `up`, `north`, `south`, `west` or `east`. */
  face: string;
  /** Exact hit position in world space. */
  hit: Vec3Value;
  /** Block state, e.g. `minecraft:chest[facing=north]`. */
  state: string;
}

/** Result of `pick.region`: inclusive integer corners. */
export interface PickedRegion {
  min: Vec3Value;
  max: Vec3Value;
}

/** Result of `pick.polygon`: integer vertices plus an inclusive Y range. */
export interface PickedPolygon {
  vertices: Vec3Value[];
  minY: number;
  maxY: number;
}

/** Result of `pick.entity`. */
export interface PickedEntity {
  /** Entity id as the client knows it, which equals the server entity id. */
  id: number;
  uuid?: string;
  /** Entity type, e.g. `minecraft:cow`. */
  type: string;
  pos: Vec3Value;
}

/** Result of `pick.direction`. Angles are in degrees, vanilla convention. */
export interface PickedDirection {
  yaw: number;
  pitch: number;
  /** `north`, `south`, `east`, `west`, `up` or `down`. */
  cardinal: string;
}

/** Result of `pick.item`. */
export interface PickedItem {
  /** Item id, e.g. `minecraft:diamond`. */
  id: string;
  count: number;
  /** Data components as sent by the client, when present. */
  components?: unknown;
}

/**
 * Ask the player to identify something. One input request is active at a time and the others queue.
 * Every method rejects with a `BridgeError`:
 * - `cancelled`: the player pressed Esc, the signal aborted, or the owning extension unloaded;
 * - `cooldown` / `quota_exceeded`: the mod refused to show it, see {@link SurfaceRefusal};
 * - `timeout`: the deadline passed;
 * - `not_attached` / `world_mismatch`: the player has not attached the bot, or is in another world
 *   (every kind except `item` is world-scoped);
 * - `disconnected`: the session did not open in time, or it was lost and could not resume.
 *
 * A call always settles: its own deadline bounds the wait for the session, the wait for the player
 * and any wait after a refusal.
 */
export interface PickApi {
  /** An exact point (crosshair raycast; scroll moves it along the ray). */
  point(options?: PickOptions): Promise<Vec3Value>;
  block(options?: PickOptions): Promise<PickedBlock>;
  region(options?: PickOptions): Promise<PickedRegion>;
  polygon(options?: PickOptions): Promise<PickedPolygon>;
  entity(options?: PickOptions): Promise<PickedEntity>;
  /** Ordered positions in world space. */
  path(options?: PickOptions): Promise<Vec3Value[]>;
  direction(options?: PickOptions): Promise<PickedDirection>;
  item(options?: PickOptions): Promise<PickedItem>;
}

/** Options shared by prompts. */
export interface PromptOptions<T> {
  /**
   * Answer used when the deadline passes. Defaults: `false` for confirm, the first choice for
   * choice, `''` for text, `0` for number and `[]` for lines and pages.
   */
  default?: T;
  /** Deadline in ms, counted by the mod from receipt. Default 30000, at most 600000. */
  timeoutMs?: number;
  /** Position of a `world` prompt, in blocks. */
  at?: Vec3Like;
  /** Aborting sends `prompt.cancel` and rejects with `cancelled`. */
  signal?: AbortSignal;
  /**
   * What to do when the mod refuses to open this: `'reject'`, `'wait'` or a handler. Default: the
   * bridge's `surfaces.onRefused`, otherwise `'reject'`. See {@link RefusedHandler}.
   */
  refused?: RefusedDecision | RefusedHandler;
  /** Called with the default when the deadline passed; the promise still resolves with it. */
  onTimeout?: (value: T) => void;
  /**
   * Called with the surface that answered, after any fallback, just before the promise resolves.
   * Not called when the prompt rejects.
   */
  onSurface?: (surface: PromptSurface) => void;
}

/**
 * Screen a prompt is shown on. Every surface is a bridge screen that never sends the vanilla packet
 * (anvil rename, sign update, book edit, dialog action or chat message). When the mod cannot open
 * the requested surface it falls back to `hud`.
 * - `hud`: the bridge prompt card;
 * - `dialog`: a vanilla dialog screen whose inputs go to the bot;
 * - `anvil`: an anvil-styled rename screen showing {@link TextPromptOptions.item};
 * - `sign`: a sign edit screen with {@link TextPromptOptions.wood};
 * - `book`: a book edit screen;
 * - `chat`: the next chat line the player sends, captured on the client;
 * - `world`: a world menu at {@link PromptOptions.at};
 * - `stonecutter`: a scrollable list of item choices;
 * - `enchanting`: up to three options with a cost line each;
 * - `merchant`: trade offers, "pay these, get this";
 * - `crafting`: a 3x3 pattern arranged from virtual items.
 */
export type PromptSurface = 'hud' | 'dialog' | 'anvil' | 'sign' | 'book' | 'chat' | 'world' | 'stonecutter' | 'enchanting' | 'merchant' | 'crafting';

/** Options of `prompt.confirm`. */
export interface ConfirmPromptOptions extends PromptOptions<boolean> {
  /** Default `hud`. With `chat`, the next chat line answers. */
  surface?: 'hud' | 'dialog' | 'chat' | 'world';
}

/** Options of `prompt.choice`. */
export interface ChoicePromptOptions<C extends string> extends PromptOptions<C> {
  /** Default `hud`. `stonecutter` and `enchanting` show the options' items and cost lines. */
  surface?: 'hud' | 'dialog' | 'world' | 'stonecutter' | 'enchanting';
}

/** A choice with an item and, for the `enchanting` surface, a cost line. */
export interface ChoiceOption<C extends string = string> {
  /** The prompt resolves with it; unique within the prompt. */
  id: C;
  /** Shown as the entry's name. Default: the id. */
  label?: string;
  /** Item shown for the entry: an id, an item object or a mineflayer `Item`. */
  item?: ItemInput;
  /** Cost line of an `enchanting` option, such as `3 Lapis Lazuli`. */
  cost?: string;
}

/** One offer of a `merchant` prompt: pay `cost` (and `cost2`), get `result`. */
export interface TradeOffer<I extends string = string> {
  /** The prompt resolves with it; unique within the prompt. */
  id: I;
  cost: ItemInput;
  cost2?: ItemInput;
  result: ItemInput;
  /** Shown crossed out and cannot be chosen. Default false. */
  disabled?: boolean;
  /** Uses so far, shown on the trade's bar. */
  uses?: number;
  maxUses?: number;
}

/** Options of `prompt.trade`. */
export interface TradePromptOptions<I extends string> extends PromptOptions<I> {
  /** Default `merchant`; on `hud` and `dialog` the offers are plain choices. */
  surface?: 'merchant' | 'hud' | 'dialog';
}

/** A crafting pattern: three rows of three item ids, `null` for empty cells. */
export type RecipePattern = (string | null)[][];

/** Options of `prompt.recipe`. */
export interface RecipePromptOptions extends PromptOptions<RecipePattern> {
  /** The only surface; a mod that cannot show it makes the prompt reject with `unsupported`. */
  surface?: 'crafting';
  /** Virtual items the player arranges. Default: any item. */
  items?: readonly ItemInput[];
}

/** Options of `prompt.text`. */
export interface TextPromptOptions extends PromptOptions<string> {
  /** Default `hud`. */
  surface?: 'hud' | 'dialog' | 'anvil' | 'sign' | 'book' | 'chat';
  /** Item shown in the anvil's input slot, e.g. `minecraft:chest` (`anvil` only). */
  item?: string;
  /** Wood type of the sign, e.g. `spruce` (`sign` only). */
  wood?: string;
  /** Longest accepted answer, in characters. A positive integer. */
  maxLength?: number;
}

/** Options of `prompt.number`. */
export interface NumberPromptOptions extends PromptOptions<number> {
  /** Default `hud`. */
  surface?: 'hud' | 'dialog';
  /** Smallest accepted value; finite and not above `max`. */
  min?: number;
  /** Largest accepted value; finite. */
  max?: number;
}

/** Options of `prompt.lines`. */
export interface LinesPromptOptions extends PromptOptions<string[]> {
  /** Default `sign`. */
  surface?: 'sign' | 'hud' | 'dialog';
  /** Wood type of the sign, e.g. `spruce`. Default: the mod's choice. */
  wood?: string;
  /** Longest accepted line, in characters. A positive integer. */
  maxLength?: number;
}

/** Options of `prompt.pages`. */
export interface PagesPromptOptions extends PromptOptions<string[]> {
  /** Default `book`. */
  surface?: 'book' | 'hud' | 'dialog';
  /** Longest accepted page, in characters. A positive integer. */
  maxLength?: number;
}

/**
 * Small questions answered with the keyboard or a vanilla-styled screen. Each resolves with the
 * answer, or with the default when the deadline passes. Rejections are as for {@link PickApi};
 * `world_mismatch` only applies to world menus. A surface the prompt kind does not support, or an
 * option that does not fit (a non-integer `maxLength`, `min` above `max`), rejects with
 * `invalid_message` before anything is sent. An answer of the wrong shape rejects with
 * `invalid_message`.
 */
export interface PromptApi {
  confirm(text: string, options?: ConfirmPromptOptions): Promise<boolean>;
  /** Resolves with one of `choices`, which must not be empty. */
  /**
   * Resolves with one of `choices` (or its id), which must not be empty. Choices may carry items and
   * cost lines for the `stonecutter` and `enchanting` surfaces; `enchanting` takes at most three.
   */
  choice<const C extends string>(text: string, choices: readonly (C | ChoiceOption<C>)[], options?: ChoicePromptOptions<C>): Promise<C>;
  /** Trade offers on a merchant screen; resolves with the chosen offer's id. Disabled offers cannot be chosen. */
  trade<const I extends string>(text: string, offers: readonly TradeOffer<I>[], options?: TradePromptOptions<I>): Promise<I>;
  /**
   * A 3x3 crafting grid the player fills from virtual items; resolves with the pattern, row by row.
   * The default answer is an empty grid.
   */
  recipe(text: string, options?: RecipePromptOptions): Promise<RecipePattern>;
  text(text: string, options?: TextPromptOptions): Promise<string>;
  /** Resolves with a finite number. */
  number(text: string, options?: NumberPromptOptions): Promise<number>;
  /** Up to four lines of text, on a sign by default. Resolves with the lines in order. */
  lines(text: string, options?: LinesPromptOptions): Promise<string[]>;
  /** Pages of text, in a book by default. Resolves with the pages in order. */
  pages(text: string, options?: PagesPromptOptions): Promise<string[]>;
}

/** Form field types. */
export type FormFieldType = 'text' | 'number' | 'slider' | 'toggle' | 'select' | 'multi-select' | 'item' | 'block-pos' | 'zone';

/** One form field. The client validates values against the constraints before submitting. */
export interface FormFieldDef {
  /** Key of the value in the result. */
  readonly id: string;
  readonly type: FormFieldType;
  readonly label?: string;
  /** Initial value, of the field's value type. */
  readonly default?: unknown;
  /** Bounds for `number` and `slider`, or length bounds for `text`. */
  readonly min?: number;
  readonly max?: number;
  /** Step for `slider` and `number`. */
  readonly step?: number;
  /** Choices for `select` and `multi-select`. */
  readonly options?: readonly string[];
  /** The form cannot be submitted while this field is empty. */
  readonly required?: boolean;
}

/**
 * Value type of a form field: `toggle` is a boolean, `number` and `slider` numbers, `multi-select` a
 * string list, `block-pos` a position, and the rest strings (`zone` is the zone's object key).
 */
export type FormFieldValue<T extends FormFieldType> = T extends 'toggle'
  ? boolean
  : T extends 'number' | 'slider'
    ? number
    : T extends 'multi-select'
      ? string[]
      : T extends 'block-pos'
        ? Vec3Value
        : string;

/** The values a form resolves with, keyed by field id. */
export type FormValues<F extends readonly FormFieldDef[]> = { [D in F[number] as D['id']]: FormFieldValue<D['type']> };

/**
 * A button of a form besides submit and cancel, in a row of its own, such as "Remove container".
 * Pressing it closes the form, which resolves with `button` set to its `id`.
 */
export interface FormButtonDef {
  readonly id: string;
  readonly label: string;
  /** An item id drawn on the button, or a Bridge icon: `mineflayer_bridge:trash`. */
  readonly icon?: string;
  /** Removes or resets something: drawn red. */
  readonly danger?: boolean;
}

/**
 * What a form resolves with: its values, and with `buttons`, the `button` the player pressed
 * instead of submitting (the values are then the defaults).
 */
export type FormResult<F extends readonly FormFieldDef[], B extends readonly FormButtonDef[] = readonly []> = FormValues<F> &
  (B extends readonly [] ? unknown : { readonly button?: B[number]['id'] });

/** A form screen definition. */
export interface FormDefinition<F extends readonly FormFieldDef[] = readonly FormFieldDef[], B extends readonly FormButtonDef[] = readonly FormButtonDef[]> {
  title: string;
  fields: F;
  submitLabel?: string;
  /** Actions besides submit, such as removing what the form edits; no field may then be named `button`. */
  buttons?: B;
  /** `screen` (default): the bridge form screen. `dialog`: a vanilla dialog screen. */
  surface?: 'screen' | 'dialog';
  /**
   * Deadline in ms, at most 3600000 (the default), counted by the mod from receipt. On expiry the
   * form closes and the call rejects with `timeout`.
   */
  timeoutMs?: number;
  /** Aborting closes the form (`form.close`) and rejects with `cancelled`. */
  signal?: AbortSignal;
  /**
   * What to do when the mod refuses to open this: `'reject'`, `'wait'` or a handler. Default: the
   * bridge's `surfaces.onRefused`, otherwise `'reject'`. See {@link RefusedHandler}.
   */
  refused?: RefusedDecision | RefusedHandler;
}

/** Passed to command and action handlers. */
export interface InvocationContext {
  /** Mod-assigned id of this invocation. */
  readonly invocation: number;
  /** Full object key of the declaration that was invoked. */
  readonly key: string;
  /**
   * Subcommand names below the root that led to the handler, e.g. `['start']` for `mine start`.
   * Canonical names, never aliases. Empty for the root and for actions.
   */
  readonly path: readonly string[];
  /**
   * The linked player the input came from (a hub may link several players' mods). The invocation
   * result, and what the handler opens or sends, goes to this player only.
   */
  readonly player?: BridgePlayer | undefined;
  /**
   * How the player ran it, when the mod says: `chat` (an `@name` line or a chat click), `command`
   * (`/bridge`), `palette`, `menu` (the radial or context menu), `screen` (a button or form) or
   * `map`. Answer later messages the same way: in chat for `chat` and `command`, as a toast
   * otherwise.
   */
  readonly source?: InvocationSource | undefined;
  /**
   * The bots this is meant for. For an order ({@link InputAndHud.orders}), the crew bots the player
   * addressed: one bot for `@eco1`, a group's members for `@farmers`, the whole crew for `@all`,
   * never empty. For an action, the bots the player had selected, such as on the map, when there
   * were any.
   */
  readonly bots?: readonly string[] | undefined;
}

/** How the player ran a command or action; see {@link InvocationContext.source}. */
export type InvocationSource = 'chat' | 'command' | 'palette' | 'menu' | 'screen' | 'map' | (string & {});

/**
 * Return value of a command or action handler. A string becomes the result message shown to the
 * player; a thrown error or a rejection becomes a failure carrying the error's message, except a
 * `cancelled` error (the player closed a form or prompt the handler awaited), which ends with
 * nothing shown.
 */
export type InvocationReturn = string | void | Promise<string | void>;

/** Command argument types. `enum` takes its values from `choices`. */
export type CommandArgType =
  | 'string'
  | 'greedy'
  | 'int'
  | 'float'
  | 'bool'
  | 'enum'
  | 'block-pos'
  | 'vec3'
  | 'block-id'
  | 'item-id'
  | 'entity'
  | 'entity-list'
  | 'player'
  | 'bot'
  | 'zone'
  | 'point';

/**
 * Completion sources the mod resolves on the client:
 * - `choices`: the argument's `choices`;
 * - `entities`: entities near the player or the bot, shown as "Zombie (12 m)", inserting the entity id;
 * - `blocks`: the block under the crosshair, `~` positions and nearby blocks of a type;
 * - `block-ids`, `item-ids`, `sounds`, `particles`, `enchantments`: registry ids by prefix;
 * - `players`: the tab list; `bots`: connected bot names;
 * - `zones`, `points`, `waypoints`: shared and this bot's own definitions.
 */
export type CompletionSourceName =
  | 'choices'
  | 'entities'
  | 'blocks'
  | 'block-ids'
  | 'item-ids'
  | 'sounds'
  | 'particles'
  | 'enchantments'
  | 'players'
  | 'bots'
  | 'zones'
  | 'points'
  | 'waypoints';

/** Narrows what a client completion source offers. Omitted lists mean any. */
export interface CompletionFilter {
  /** Entity types for `entities`, e.g. `minecraft:zombie`. Same as `ids`; give one of the two. */
  types?: readonly string[];
  /** Registry ids, e.g. `minecraft:chest` for `blocks`. */
  ids?: readonly string[];
  /** Block, item or entity tags, e.g. `c:ores`. */
  tags?: readonly string[];
}

/** A client completion source with options. */
export interface CompletionSourceOptions {
  source: CompletionSourceName;
  /** Where `entities` and `blocks` measure from. Default `player`; `bot` uses the bot's reported position. */
  near?: 'player' | 'bot';
  /** Search radius in blocks for `entities` and `blocks`, a positive number. Default: the mod's. */
  radius?: number;
  filter?: CompletionFilter;
  /** Most suggestions shown, an integer from 1 to 50. Default 20. */
  limit?: number;
}

/** One completion suggestion. */
export interface CommandSuggestion {
  /** Text inserted for the argument. Non-empty. */
  text: string;
  /** Label shown instead of `text`. */
  display?: string;
  /** Tooltip shown next to the suggestion. */
  tooltip?: string;
}

/** A suggestion as returned by a completion function: a bare string is the text to insert. */
export type SuggestionLike = string | CommandSuggestion;

/** Passed to a dynamic completion function. */
export interface CompletionContext {
  /** Earlier arguments of the same node, already parsed and selector-resolved by the mod. */
  readonly args: Readonly<Record<string, unknown>>;
  /** Subcommand names below the root, e.g. `['give']`. */
  readonly path: readonly string[];
  /** Name of the argument being completed. */
  readonly arg: string;
  /** Full object key of the command. */
  readonly key: string;
  /**
   * For an order (`@<bot>`, `@<group>`, `@all`): the crew bots it is addressed to, so completion can
   * offer what they have, such as the items in their inventories. Unset for other commands.
   */
  readonly bots?: readonly string[];
}

/**
 * Answers `command.complete` for one argument (the `dynamic` source). `input` is what the player
 * typed for the argument so far. At most 50 suggestions are sent; the rest are dropped. A throw, a
 * rejection or a malformed suggestion answers an empty list and is reported through the session's
 * `error` event. The mod gives up after 300 ms and drops later answers, so this should be fast.
 */
export type CompletionFunction = (input: string, ctx: CompletionContext) => readonly SuggestionLike[] | Promise<readonly SuggestionLike[]>;

/** One command argument. The mod parses it and completes it as the player types. */
export interface CommandArgDef {
  name: string;
  type: CommandArgType;
  /** Default: true when `default` is given. Required arguments cannot follow optional ones. */
  optional?: boolean;
  /** Used when the argument is omitted. */
  default?: unknown;
  min?: number;
  max?: number;
  /** Values of an `enum` argument, also used by the `choices` completion source. */
  choices?: readonly string[];
  description?: string;
  /**
   * Completion source: a client source by name or with options, or a function the mod calls through
   * `command.complete` while the player types.
   */
  complete?: CompletionSourceName | CompletionSourceOptions | CompletionFunction;
}

/**
 * Value the handler receives for an argument type: numbers for `int` and `float`, `boolean` for
 * `bool`, the declared choices for `enum`, `{ x, y, z }` for `block-pos` and `vec3`, a client entity
 * id for `entity` and a list of them for `entity-list`, otherwise a string (ids are namespaced).
 */
export type CommandArgValue<D extends CommandArgDef> = D['type'] extends 'int' | 'float'
  ? number
  : D['type'] extends 'bool'
    ? boolean
    : D['type'] extends 'enum'
      ? D['choices'] extends readonly (infer C extends string)[]
        ? C
        : string
      : D['type'] extends 'block-pos' | 'vec3'
        ? Vec3Value
        : D['type'] extends 'entity'
          ? number
          : D['type'] extends 'entity-list'
            ? number[]
            : string;

/**
 * Parsed arguments of a node, by name. An optional argument without a default may be `undefined`.
 * Falls back to `Record<string, unknown>` when the argument names are not literal types.
 */
export type CommandArgs<A extends readonly CommandArgDef[]> = string extends A[number]['name']
  ? Record<string, unknown>
  : {
      [D in A[number] as D['name']]: D extends { default: unknown }
        ? CommandArgValue<D>
        : D extends { optional: true }
          ? CommandArgValue<D> | undefined
          : CommandArgValue<D>;
    };

/**
 * A subcommand node. A node needs `run`, `children` or both; a node without `run` only lists its
 * children when run.
 */
export interface CommandNodeDefinition {
  description?: string;
  /** Other names the player may type for this node. Unique among siblings, without spaces or `/`. */
  aliases?: readonly string[];
  args?: readonly CommandArgDef[];
  /** Receives the parsed arguments by name, with defaults filled in for omitted ones. */
  run?(args: Record<string, unknown>, ctx: InvocationContext): InvocationReturn;
  /** Subcommands by name. Names are non-empty, without spaces or `/`. At most 8 levels deep. */
  children?: Readonly<Record<string, CommandNodeDefinition>>;
}

/**
 * A command for the palette, `/bridge <bot> <command>` and `@bot` chat lines: either flat (`args`
 * and `run`) or a tree with `children`, e.g. `mine start <block> [count]`, `mine stop`. `run`
 * receives typed arguments when `args` is written inline; children receive `Record<string, unknown>`.
 */
export interface CommandDefinition<A extends readonly CommandArgDef[] = readonly CommandArgDef[]> {
  description?: string;
  /** Other names for the command. Unique, without spaces or `/`. */
  aliases?: readonly string[];
  args?: A;
  /** Receives the parsed arguments by name, with defaults filled in for omitted ones. */
  run?(args: CommandArgs<A>, ctx: InvocationContext): InvocationReturn;
  /** Subcommands by name. Names are non-empty, without spaces or `/`. At most 8 levels deep. */
  children?: Readonly<Record<string, CommandNodeDefinition>>;
}

/**
 * Commands owned by this scope, declared at `~cmd/<name>`. The same interface declares orders at
 * `~order/<name>`; see {@link InputAndHud.orders}.
 */
export interface CommandsApi {
  /**
   * Declares or replaces a command. `name` must be non-empty, without spaces or `/`. Throws
   * `invalid_message` for a malformed definition: a node with neither `run` nor `children`, an
   * unknown argument type or completion source, duplicate argument names or sibling names and
   * aliases, a required argument after an optional one, a `greedy` argument that is not last, or a
   * tree deeper than 8 levels. Returns an unregister function that leaves a newer registration
   * under the same name alone.
   */
  register<const A extends readonly CommandArgDef[] = readonly CommandArgDef[]>(name: string, definition: CommandDefinition<A>): () => void;
  unregister(name: string): void;
  has(name: string): boolean;
}

/** The bots a session gives orders to, and named groups of them. */
export interface CrewDefinition {
  /** Display names (usernames) of the bots. Only attached ones are addressed. */
  bots: readonly string[];
  /**
   * Groups by name, each addressed as `@<name>`. Members must be listed in `bots`. A name is a
   * word without spaces or `/`, not `all`; a group named like a bot or another session is ignored.
   */
  groups?: Readonly<Record<string, readonly string[]>>;
}

/** Declares the session's crew at `~crew/crew`; see {@link InputAndHud.orders}. */
export interface CrewApi {
  /**
   * Declares or replaces the crew. Throws `invalid_message` for an empty or spaced bot name, a
   * group named `all` or with a bad name, or a member not in `bots`.
   */
  set(crew: CrewDefinition): void;
  /** Removes the crew, so orders reach nobody. */
  clear(): void;
  /** The crew last set, or `undefined`. */
  get(): CrewDefinition | undefined;
}

/**
 * A resolved target. Which fields are set depends on `type`. A connected bot's own player entity
 * resolves as `bot` for rules and actions that ask for `bot`, and as `entity` for those that ask
 * for `entity`; compare `objectKey` with `bot.bridge.session.owner` to tell whether the target is
 * this bot.
 */
export interface Target {
  type: TargetType;
  /** Block position for `block`. */
  block?: Vec3Value;
  /** Face for `block`. */
  face?: string;
  /** Entity id for `entity` and `bot`. */
  entity?: number;
  /** World position, in blocks. */
  pos?: Vec3Value;
  /** Item id for `item`. */
  item?: string;
  /** Object key for `bot` (its owner prefix), `zone`, `point`, `waypoint` and `element`. */
  objectKey?: string;
}

/** A context action for the radial menu and, when global, the palette. */
export interface ActionDefinition {
  label: string;
  /** Item or texture id. */
  icon?: string;
  /**
   * What the action applies to. A global action may leave it out: it then applies to no target
   * (type `none`, exported as `NO_TARGET`), never matches in the radial menu, and is offered in the
   * palette and on the title screen, where `run` receives `{ type: 'none' }`. That suits bot
   * managers' actions such as *Add bot*. A world action needs a target.
   */
  target?: TargetFilter & { type: TargetType };
  /**
   * `world` (default): bound to the bot's current world at declaration time, like shapes.
   * `global`: offered anywhere, including the title screen.
   */
  scope?: 'world' | 'global';
  /** Sort order within the bot's group; lower first. Default 0. */
  order?: number;
  run(target: Target, ctx: InvocationContext): InvocationReturn;
}

/** Context actions owned by this scope, declared at `~action/<id>`. */
export interface ActionsApi {
  /** Declares or replaces an action. Returns an unregister function. */
  register(id: string, definition: ActionDefinition): () => void;
  unregister(id: string): void;
  has(id: string): boolean;
}

/** Order of intercept rules across bots: higher runs first, `monitor` always last. */
export type InterceptPriority = 'lowest' | 'low' | 'normal' | 'high' | 'highest' | 'monitor';

/** Passed to intercept handlers. */
export interface InterceptContext extends InvocationContext {
  /** True when a rule that ran earlier cancelled the interaction, so vanilla does not run it. */
  readonly cancelled: boolean;
}

/**
 * A rule on the player's interactions with real blocks and entities, the client-side counterpart of
 * a server plugin cancelling an interact event.
 */
export interface InterceptDefinition {
  /** What the rule applies to, e.g. `{ type: 'block', tags: ['c:chests'] }`. */
  target: TargetFilter & { type: TargetType };
  /** Default `secondary` (use). */
  input?: 'primary' | 'secondary';
  /**
   * `observe` (default): vanilla proceeds and the bot hears about it. `cancel`: the client stops the
   * vanilla interaction, so no packet is built, and the handler's result is shown like an action's.
   * `cancel` needs the `input.intercept` permission, which the player grants per bot; without it the
   * rule observes.
   */
  mode?: 'observe' | 'cancel';
  /** Only while this key is held. `ctrl` is accepted for `control`. */
  modifier?: 'shift' | 'control' | 'ctrl' | 'alt';
  /** Default `normal`. */
  priority?: InterceptPriority;
  /** `world` (default): bound to the bot's current world. `global`: in any world. */
  scope?: 'world' | 'global';
  /** Return values as for actions; they only reach the player in `cancel` mode. */
  run(target: Target, ctx: InterceptContext): InvocationReturn;
}

/** Intercept rules owned by this scope, declared at `~intercept/<id>`. */
export interface InterceptsApi {
  /** Declares or replaces a rule. Returns an unregister function. */
  register(id: string, definition: InterceptDefinition): () => void;
  unregister(id: string): void;
  has(id: string): boolean;
}

/**
 * Where a keybind is active: `ingame` (no screen open), `gui` (a screen open), `any`,
 * `bot-selected` (while this bot is focused), `tool:<tool id>` (while that tool is selected) or
 * `target:<type>` (while the crosshair is on a target of that {@link TargetType}).
 */
export type KeybindContext = 'ingame' | 'gui' | 'any' | 'bot-selected' | `tool:${string}` | `target:${string}` | (string & {});

/** A bot keybind. These are defaults; the player can rebind them. */
export interface KeybindDefinition {
  label: string;
  /**
   * Vanilla key names in chord order, e.g. `key.keyboard.left.control` then `key.keyboard.g`. A
   * name without a dot is short for `key.keyboard.<name>`.
   */
  keys: readonly string[];
  /** Default `ingame`. */
  context?: KeybindContext;
  /** Default: `both` when both callbacks are given, `release` with only `onRelease`, otherwise `press`. */
  activation?: 'press' | 'release' | 'both';
  /** Higher wins on conflicts. Default 0. */
  priority?: number;
  /** Hide the key from vanilla while it triggers this bind. Default true. */
  consumes?: boolean;
  onPress?: () => void;
  onRelease?: () => void;
}

/** Keybinds owned by this scope, declared at `~key/<id>`. */
export interface KeybindsApi {
  /** Declares or replaces a keybind. Returns an unregister function. */
  register(id: string, definition: KeybindDefinition): () => void;
  unregister(id: string): void;
  has(id: string): boolean;
}

/** Inputs a tool can capture. */
export type ToolInputName = 'primary' | 'secondary' | 'middle' | 'scroll' | 'key';

/** One use of a tool, as delivered by `tool.event`. */
export interface ToolUse {
  input: ToolInputName;
  /** What was under the crosshair, resolved to one of the tool's target types. */
  target: Target | undefined;
  /** Whole scroll steps for `scroll`. */
  delta: number | undefined;
  /** Vanilla key name for `key`. */
  key: string | undefined;
}

/** A persistent input mode. */
export interface ToolDefinition {
  label: string;
  icon?: string;
  /** Target types resolved under the crosshair. Default `['block']`. */
  targets?: readonly string[];
  /** Captured inputs. Default: the inputs that have a handler in `on`. */
  inputs?: readonly ToolInputName[];
  on?: {
    primary?: (target: Target | undefined, use: ToolUse) => void;
    secondary?: (target: Target | undefined, use: ToolUse) => void;
    middle?: (target: Target | undefined, use: ToolUse) => void;
    scroll?: (delta: number, use: ToolUse) => void;
    key?: (key: string, use: ToolUse) => void;
  };
  /** The player selected this tool. */
  onActivate?: () => void;
  /** The player switched this tool off, selected another one, or the session was lost. */
  onDeactivate?: () => void;
}

/** Tools owned by this scope, declared at `~tool/<id>`. */
export interface ToolsApi {
  /** Declares or replaces a tool. Returns an unregister function. */
  register(id: string, definition: ToolDefinition): () => void;
  unregister(id: string): void;
  has(id: string): boolean;
  /** Local id of this scope's active tool, when the player has one of them selected. */
  active(): string | undefined;
}

/** One bot-declared setting. */
export type SettingDefinition =
  | { readonly type: 'bool'; readonly default?: boolean; readonly label?: string; readonly description?: string }
  | { readonly type: 'int' | 'float'; readonly default?: number; readonly min?: number; readonly max?: number; readonly label?: string; readonly description?: string }
  | { readonly type: 'string'; readonly default?: string; readonly label?: string; readonly description?: string }
  | { readonly type: 'color'; readonly default?: ColorLike; readonly label?: string; readonly description?: string }
  | { readonly type: 'choice'; readonly options: readonly string[]; readonly default?: string; readonly label?: string; readonly description?: string };

/** Setting key to definition. */
export type SettingsSchema = { readonly [key: string]: SettingDefinition };

/** Value type of a setting: colours are packed ARGB numbers, choices one of their options. */
export type SettingValue<D extends SettingDefinition> = D extends { type: 'bool' }
  ? boolean
  : D extends { type: 'int' | 'float' | 'color' }
    ? number
    : D extends { type: 'choice'; options: readonly (infer O)[] }
      ? O
      : string;

/** Every value of a settings group. */
export type SettingsValuesOf<S extends SettingsSchema> = { -readonly [K in keyof S]: SettingValue<S[K]> };

/** Options for {@link SettingsApi.define}. */
export interface SettingsGroupOptions {
  /** Group id; part of the key `~settings/<group>`. Default `default`. */
  group?: string;
  /** Heading in the settings screen. */
  title?: string;
}

/**
 * A settings group. Values are the declared defaults until the player's values arrive with
 * `settings.values`, which the mod sends on every connect and on every change. Values that do not
 * fit the schema (wrong type, out of range, unknown choice) are ignored.
 */
export interface Settings<S extends SettingsSchema> {
  /** Object key relative to the owner: `<scope>/~settings/<group>`. */
  readonly key: string;
  get<K extends keyof S & string>(key: K): SettingValue<S[K]>;
  /** A copy of every current value. */
  all(): SettingsValuesOf<S>;
  /** Called once per key whose value changed. Returns an unsubscribe function. */
  on(event: 'change', fn: (key: keyof S & string, value: SettingValue<S[keyof S]>) => void): () => void;
}

/** Bot-declared settings, shown under *Bots > name* in the settings screen. */
export interface SettingsApi {
  /** Declares or replaces a group. The returned object stays valid until the group is redeclared or the scope unloads. */
  define<const S extends SettingsSchema>(schema: S, options?: SettingsGroupOptions): Settings<S>;
}

/** HUD anchor slot. */
export type HudAnchorName = 'top-left' | 'top-center' | 'top-right' | 'middle-left' | 'middle-right' | 'bottom-left' | 'bottom-right';

/** Options of a HUD panel. */
export interface HudPanelOptions {
  title?: string;
  /** Default `top-right`. The player can move the panel in the HUD editor. */
  anchor?: HudAnchorName;
  /** Order among panels in the same slot; lower first. Default 0. */
  order?: number;
  collapsed?: boolean;
}

/** Fields every widget accepts. */
export interface HudWidgetOptions {
  /** Position within the panel; lower first. Default: creation order. */
  order?: number;
}

/** Options of a text widget. */
export interface HudTextOptions extends HudWidgetOptions {
  maxLines?: number;
  color?: ColorLike;
}

/** Key-value rows: an object (in insertion order) or a list with per-row colours. */
export type HudKvEntries =
  | Readonly<Record<string, string | number | boolean>>
  | readonly { readonly key: string; readonly value: string | number | boolean; readonly color?: ColorLike }[];

/** Content of a bar widget. */
export interface HudBarOptions extends HudWidgetOptions {
  value: number;
  /** Default 0. */
  min?: number;
  /** Default 1. */
  max?: number;
  label?: string;
  color?: ColorLike;
}

/** Options of a list widget. */
export interface HudListOptions extends HudWidgetOptions {
  /** Index of the selected row. */
  selected?: number;
  maxRows?: number;
}

/** Options of a sparkline widget; `min` and `max` fix the vertical range. */
export interface HudSparklineOptions extends HudWidgetOptions {
  /** Samples kept; the oldest is dropped first. Default 60, at least 1. */
  capacity?: number;
  min?: number;
  max?: number;
  color?: ColorLike;
}

/** Options of an item widget. */
export interface HudItemOptions extends HudWidgetOptions {
  /** Default 1. */
  count?: number;
  label?: string;
}

/** Handle of a text widget. */
export interface HudTextHandle {
  set(text: string): void;
}

/** Handle of a key-value widget. Changes go out as field patches, batched per tick. */
export interface HudKvHandle {
  /** Sets one row, appending it when new. */
  set(key: string, value: string | number | boolean, color?: ColorLike): void;
  remove(key: string): void;
  /** Replaces every row. */
  replace(entries: HudKvEntries): void;
  get(key: string): string | undefined;
}

/** Handle of a bar widget. Values must be finite numbers. */
export interface HudBarHandle {
  set(value: number): void;
  update(options: Partial<HudBarOptions>): void;
}

/** Handle of a list widget. */
export interface HudListHandle {
  set(items: readonly string[]): void;
  /** Selects a row, or clears the selection with `undefined`. */
  select(index: number | undefined): void;
}

/** Handle of a badge widget; the colour is kept when omitted. */
export interface HudBadgeHandle {
  set(text: string, color?: ColorLike): void;
}

/** A ring buffer of `capacity` samples, sent at most once per tick and only when changed. */
export interface HudSparklineHandle {
  push(...values: number[]): void;
  clear(): void;
  /** The buffered samples, oldest first. */
  values(): number[];
}

/** Handle of an item widget. */
export interface HudItemHandle {
  set(itemId: string, options?: Omit<HudItemOptions, 'order'>): void;
}

/**
 * A HUD panel owned by one scope, declared at `~hud/<panel id>`; its widgets live at
 * `~hud/<panel id>/<widget id>`. Calling a widget method again with the same id updates that widget
 * and returns the same handle; the content arguments may then be omitted to only get the handle.
 * Ids must be non-empty and must not contain `/`. `text`, `bar`, `badge` and `item` throw
 * `invalid_message` when called without content for a widget that does not exist yet. Handles of
 * removed widgets do nothing.
 */
export interface HudPanel {
  readonly id: string;
  text(id: string, text?: string, options?: HudTextOptions): HudTextHandle;
  kv(id: string, entries?: HudKvEntries, options?: HudWidgetOptions): HudKvHandle;
  bar(id: string, options?: HudBarOptions): HudBarHandle;
  list(id: string, items?: readonly string[], options?: HudListOptions): HudListHandle;
  badge(id: string, text?: string, color?: ColorLike, options?: HudWidgetOptions): HudBadgeHandle;
  sparkline(id: string, options?: HudSparklineOptions): HudSparklineHandle;
  item(id: string, itemId?: string, options?: HudItemOptions): HudItemHandle;
  has(id: string): boolean;
  /** Removes one widget. {@link destroy} removes the panel. */
  remove(id: string): void;
  /** Removes every widget with one prefix removal; the panel stays. */
  clear(): void;
  /** Changes panel options. */
  update(options: HudPanelOptions): void;
  /** Removes the panel and its widgets. Later calls on this object declare it again. */
  destroy(): void;
  /** False after {@link destroy} until the panel is declared again, and after its extension unloads. */
  readonly alive: boolean;
  /** The panel was destroyed or its extension unloaded. Returns a remover. */
  on(event: 'removed', fn: () => void): () => void;
}

/** Vanilla boss bar colours. */
export type BossbarColor = 'pink' | 'blue' | 'red' | 'green' | 'yellow' | 'purple' | 'white';
/** Vanilla boss bar overlays (segment styles). */
export type BossbarOverlay = 'progress' | 'notched_6' | 'notched_10' | 'notched_12' | 'notched_20';

/** A client-only boss bar. */
export interface BossbarOptions {
  text: string;
  /** 0 to 1. Default 0. */
  progress?: number;
  /** Default `white`. */
  color?: BossbarColor;
  /** Default `progress`. */
  overlay?: BossbarOverlay;
}

/** Handle of a boss bar. After `remove()` the handle does nothing. Its text may hold variable templates. */
export interface BossbarHandle {
  readonly id: string;
  /** False after `remove()` or once the owning extension unloads. */
  readonly alive: boolean;
  /** Changes some fields; sent as a field patch. */
  update(options: Partial<BossbarOptions>): void;
  remove(): void;
  /** The bar was removed or its extension unloaded. Returns a remover. */
  on(event: 'removed', fn: () => void): () => void;
}

/**
 * Whose skin a player head shows: a player name or UUID, `'self'` for this bot, or a bot (anything
 * with a `username`, such as a mineflayer bot).
 */
export type HeadInput = string | { readonly username?: string | null | undefined };

/**
 * How an item looks in a slot, a world menu or a display. The model is client-only, so it may go
 * beyond what a real item allows. Every field is optional.
 */
export interface ItemPresentation {
  /**
   * Stack size, an integer from 0 to 4294967295. Default 1. Counts above 99 get a compact
   * {@link countText} (`250`, `1.2k`, `12k`, `3.4M`) unless one is given.
   */
  readonly count?: number;
  /** Text drawn instead of the count, such as `99+`. */
  readonly countText?: string;
  /** Display name: plain text, or styled spans as `chat.print` takes them (clicks are not allowed). */
  readonly name?: ChatContent;
  /** Tooltip lines below the name; each line is plain text or styled spans. */
  readonly lore?: readonly ChatContent[];
  /** Enchantment glint. Default false. */
  readonly glint?: boolean;
  /** Custom item model id. */
  readonly customModel?: string;
  /** Data components passed through to the client as is. Must be MessagePack-encodable. */
  readonly components?: unknown;
  /** A durability-style bar from 0 (empty) to 1 (full), used as a progress indicator. */
  readonly bar?: number;
  /** Colour of {@link bar}. Default: vanilla's durability colour. */
  readonly barColor?: ColorLike;
  /** Player head skin (for `minecraft:player_head`): a name, UUID, `'self'` or a bot. */
  readonly head?: HeadInput;
  /**
   * Player head skin as server menus carry it: the base64 value of the profile's `textures`
   * property. It wins over {@link head}; the item shows as a player head either way.
   */
  readonly headTexture?: string;
  /** Leather dye, potion or map colour. */
  readonly color?: ColorLike;
  /** An animated item: these items are cycled on the client every {@link frameMs}. Frames cannot nest. */
  readonly frames?: readonly ItemInput[];
  /** Time each frame shows, in ms; a positive integer. Default 500. */
  readonly frameMs?: number;
  /** Show no tooltip on hover. Default false. */
  readonly hideTooltip?: boolean;
  /** Sound event played on the client when the slot is clicked, e.g. `ui.button.click`. */
  readonly clickSound?: string;
}

/** An item stack shown in a container view slot, a world menu or a display. */
export interface ContainerItem extends ItemPresentation {
  /** Item id; a bare name such as `diamond` means `minecraft:diamond`. */
  readonly id: string;
}

/** The parts of a mineflayer (prismarine-item) `Item` that container views read. */
export interface MineflayerItemLike {
  /** Registry name without namespace, e.g. `diamond_pickaxe`. */
  readonly name: string;
  readonly count: number;
  /** Numeric item id, used by `bot.toss`. */
  readonly type?: number;
  readonly metadata?: number | null;
  /** Custom name as mineflayer reports it. */
  readonly customName?: string | null;
}

/**
 * What a slot accepts: an item id string (`'minecraft:diamond'`), a {@link ContainerItem}, or a
 * mineflayer `Item` (its `name` becomes `minecraft:<name>`, its `count` and `customName` carry over).
 */
export type ItemInput = string | ContainerItem | MineflayerItemLike;

/**
 * Slot contents by index: an array such as `bot.inventory.slots` (holes, `null` and `undefined` are
 * empty slots), a map, or an object keyed by slot number.
 */
export type ContainerSlots =
  | readonly (ItemInput | null | undefined)[]
  | ReadonlyMap<number, ItemInput | null | undefined>
  | Readonly<Record<number, ItemInput | null | undefined>>;

/**
 * Container view layouts:
 * - `chest:<rows>`: 1 to 6 rows of 9 slots;
 * - `hopper`: 5 slots; `dispenser`: 9 slots;
 * - `player_inventory`: the 46 slots of a mineflayer inventory window (0 crafting output, 1 to 4
 *   crafting grid, 5 to 8 armour, 9 to 35 main, 36 to 44 hotbar, 45 offhand). `player-inventory` is
 *   accepted as an alias;
 * - `grid:<cols>x<rows>`: a free grid of 1 to 9 columns and 1 to 6 rows;
 * - `furnace`: a read-only progress view with 3 slots (0 input,
 *   1 fuel, 2 output), drawn with {@link ContainerViewOptions.progress} and `fuel`;
 * - `brewing`: a read-only progress view with 5 slots (0 to 2 bottles, 3 ingredient, 4 fuel);
 * - `lectern`: read-only {@link ContainerViewOptions.pages}, without slots.
 */
export type ContainerLayout =
  | `chest:${number}`
  | 'hopper'
  | 'dispenser'
  | 'player_inventory'
  | 'player-inventory'
  | `grid:${number}x${number}`
  | 'furnace'
  | 'brewing'
  | 'lectern';

/** Player gestures on a slot. `number` is a number-key press that targets a hotbar slot. */
export type ContainerClick = 'left' | 'right' | 'middle' | 'shift-left' | 'shift-right' | 'double' | 'drop' | 'drop-all' | 'number';

/**
 * What a click on a slot does, resolved by the mod without a round trip:
 * - `button` (default): the click is cancelled and the bot's click handler runs;
 * - `locked`: the click is cancelled and nothing is sent (decoration, filler);
 * - `take`: the player may take the item; the bot hears about it afterwards (`storage`);
 * - `place`: the player may place items, filtered by `accepts` and capped by `maxStack`;
 * - `free`: `take` and `place`;
 * - `ask`: the mod applies the move at once and the bot's `ask` handler confirms or rolls it back;
 * - `source`: an infinite template. Clicking puts a virtual copy of the slot's item onto the view's
 *   cursor and the slot keeps its item, so the player can take it again and again. Nothing can be
 *   placed into it. Takes no `accepts` or `maxStack`.
 *
 * Slots with `take`, `place`, `free` or `ask` are storage slots: client-only storage owned by the
 * view, which never touches the player's real inventory, reported by `storage`. `source` slots are
 * not storage: their item is what the bot declared, and they are never reported. While a view has
 * storage slots, the player's own inventory below it is a copy source too: taking from it puts a
 * virtual copy on the cursor and leaves the real stack where it is.
 */
export type SlotPolicy = 'button' | 'locked' | 'take' | 'place' | 'free' | 'ask' | 'source';

/** A slot policy with its placement filter. */
export interface SlotRule {
  readonly policy: SlotPolicy;
  /** Items the player may place here; omitted lists mean any. Tags are item tags, e.g. `c:ores`. */
  readonly accepts?: { readonly ids?: readonly string[]; readonly tags?: readonly string[] };
  /** Largest stack the player may place here, a positive integer. Default: the item's own limit. */
  readonly maxStack?: number;
}

/** Slot policies by slot index: a policy name or a full rule. Slots not listed are buttons. */
export type SlotPolicies = ReadonlyMap<number, SlotPolicy | SlotRule> | Readonly<Record<number, SlotPolicy | SlotRule>>;

/** What the storage slots of a view hold, by slot index; empty slots are absent. */
export type StorageContents = ReadonlyMap<number, ContainerItem>;

/** One gesture in a container view, as passed to its handlers. */
export interface ContainerEvent {
  /** The view that was clicked. */
  readonly view: ContainerView;
  /** Slot index, or -999 for a click outside the grid. */
  readonly slot: number;
  readonly click: ContainerClick;
  /** Target hotbar slot, 0 to 8, for `number`. */
  readonly hotbar: number | undefined;
  /** What the bot last declared in the slot, if anything. */
  readonly item: ContainerItem | undefined;
  /** True for `shift-left` and `shift-right`. */
  readonly shift: boolean;
  /** True for `drop-all` (the drop key with Ctrl). */
  readonly all: boolean;
  /** The linked player who made the gesture; the `container.ack` goes to this player only. */
  readonly player?: BridgePlayer | undefined;
}

/** A move into or out of an `ask` slot, already applied by the mod and waiting for the bot's answer. */
export interface AskEvent extends ContainerEvent {
  /** Where the stack came from: a slot index of this view, or -1 for the cursor. */
  readonly fromSlot: number;
  /** The stack being moved. */
  readonly moved: ContainerItem | undefined;
}

/**
 * Answer to an {@link AskEvent}: `true` or nothing accepts the move; `false` rejects it, and a
 * string rejects it with that reason, which the player sees as a tooltip on the red flash. A thrown
 * error or a rejection rejects it with the error's message.
 */
export type AskReturn = boolean | string | void | Promise<boolean | string | void>;

/**
 * Return value of a container handler, which decides the `container.ack` the slot's pending state
 * waits for: `false` rejects the gesture, a string confirms it with that message, anything else
 * confirms it. A thrown error or a rejection rejects it with the error's message.
 */
export type ContainerHandlerReturn = boolean | string | void | Promise<boolean | string | void>;

/**
 * Why a view, menu or screen closed. `player`: the player closed it; `bot`: the bot's own `close()`;
 * `lost`: the view went away without the player: the bot was detached or left the player's world,
 * its object was removed, the mod could not show it in time, or the session was lost (the game
 * restarted, or resume failed). A lost view is not reopened unasked; `refused`: the mod never showed it, because the player dismissed one of
 * the bot's surfaces a moment ago (see the `refused` event, which carries `retryAfterMs`). `open()`
 * returns nothing, so this close is how an open that was refused settles.
 */
export type ContainerCloseReason = 'player' | 'bot' | 'lost' | 'refused';

/** Handlers of a container view. Errors thrown by `open`, `close` and `storage` are reported as `error` events. */
export interface ContainerViewHandlers {
  /** Left, right, middle, shift and double clicks on `button` slots. */
  click?: (event: ContainerEvent) => ContainerHandlerReturn;
  /** The drop key; `event.all` with Ctrl. */
  drop?: (event: ContainerEvent) => ContainerHandlerReturn;
  /** A number key pressed over a slot; `event.hotbar` is the target. */
  hotbarSwap?: (event: ContainerEvent) => ContainerHandlerReturn;
  /** A move into or out of an `ask` slot. Without a handler every move is accepted. */
  ask?: (event: AskEvent) => AskReturn;
  /** The player changed the storage slots; `slots` is what every storage slot holds after the change. */
  storage?: (slots: StorageContents, view: ContainerView) => void;
  /** The screen opened, by the bot or by the player (for example from the bot's context menu). */
  open?: (view: ContainerView) => void;
  /** The screen closed. */
  close?: (view: ContainerView, reason: ContainerCloseReason) => void;
}

/** Declaration of a container view. */
export interface ContainerViewOptions {
  title: string;
  /** Default `chest:3`. */
  layout?: ContainerLayout;
  /** Initial contents. Indexes must be inside the layout. Items in storage slots are their initial contents. */
  slots?: ContainerSlots;
  /** Slot policies. Slots not listed are buttons. */
  policies?: SlotPolicies;
  /** The mod closes the screen after every click. Default false. */
  closeOnClick?: boolean;
  /** Shown in every empty slot that is not a storage slot, such as a glass pane. */
  filler?: ItemInput;
  /** Arrow progress of `furnace` and `brewing` views, from 0 to 1. */
  progress?: number;
  /** Flame of `furnace` views, from 0 to 1. */
  fuel?: number;
  /** Pages of a `lectern` view. */
  pages?: readonly string[];
  /** A view of the same scope, or its id, that the mod opens when the player closes this one. */
  reopenParent?: string | ContainerView;
  /**
   * Esc asks the player to confirm before the view closes, so it is not dismissed by accident; it
   * still closes after the confirmation. Default false.
   */
  requireAnswer?: boolean;
  on?: ContainerViewHandlers;
}

/** Fields {@link ContainerView.update} changes. Omitted fields stay; `null` removes a field. */
export interface ContainerViewUpdate {
  title?: string;
  /** Slots outside the new layout are dropped. */
  layout?: ContainerLayout;
  closeOnClick?: boolean;
  filler?: ItemInput | null;
  progress?: number | null;
  fuel?: number | null;
  pages?: readonly string[] | null;
  reopenParent?: string | ContainerView | null;
  requireAnswer?: boolean;
  /** Replaces every policy. */
  policies?: SlotPolicies;
  /** Replaces the handlers. */
  on?: ContainerViewHandlers;
}

/**
 * A vanilla-styled inventory screen with bot-provided contents, declared as `core:container-view`
 * at `~view/<id>`. It is never a real container: no container packet is ever sent. Slot changes
 * made in one tick go out as one field patch. Methods throw `invalid_message` for slots outside the
 * layout or malformed items; after {@link remove} every method is a no-op.
 */
export interface ContainerView {
  readonly id: string;
  /** True between an open (by the bot or the player) and the next close. */
  readonly isOpen: boolean;
  /** Number of slots of the layout. */
  readonly size: number;
  /**
   * Opens the screen for the player, replacing any other bridge screen. Sent once the session is
   * open; the mod closes it again (`close` handler with `player`) when the player has not attached
   * the bot. A view that is open when a fresh session starts is opened again.
   */
  open(): void;
  /** Closes the screen if it is open. */
  close(): void;
  /** Sets one slot, or empties it with `null`. For a storage slot this replaces what the player left there. */
  setSlot(slot: number, item: ItemInput | null | undefined): void;
  /** Replaces every slot, or with `merge` only the given ones (`null` empties a slot). */
  setSlots(slots: ContainerSlots, options?: { merge?: boolean }): void;
  /** What a slot holds: the bot's declaration, or for a storage slot what the player last left there. */
  slot(slot: number): ContainerItem | undefined;
  /** Sets one slot's policy, or makes it a plain button again with `null`. */
  setPolicy(slot: number, rule: SlotPolicy | SlotRule | null): void;
  /** The policy of a slot; `button` for slots without one. */
  policy(slot: number): SlotRule;
  /** What the storage slots hold, kept current from the mod's `container.storage` reports. */
  storage(): StorageContents;
  /** False after {@link remove} or once the owning extension unloads. */
  readonly alive: boolean;
  /** Listens for storage changes made by the player. Returns a remover; listeners go away with the view. */
  on(event: 'storage', fn: (slots: StorageContents) => void): () => void;
  /** The view was removed or its extension unloaded. */
  on(event: 'removed', fn: () => void): () => void;
  update(options: ContainerViewUpdate): void;
  /** Removes the declaration; the mod closes the screen if it is open. */
  remove(): void;
}

/**
 * A menu button: an item plus a click handler. The presentation fields override the item's own.
 */
export interface MenuButton extends ItemPresentation {
  item: ItemInput;
  /** Close the menu after a successful click. Default false; open another menu from `onClick` to switch. */
  close?: boolean;
  /** Return values as for container handlers. */
  onClick?: (event: ContainerEvent) => ContainerHandlerReturn;
}

/** How a menu cell looks, without behaviour: an item, or an item with presentation overrides. */
export type MenuLook = ItemInput | Omit<MenuButton, 'onClick' | 'close'>;

/**
 * A storage slot in a menu layout or pane, optionally with initial contents. With the `source`
 * policy it is a template slot and `item` is required.
 */
export interface MenuStorageCell {
  readonly storage: SlotPolicy | SlotRule;
  readonly item?: ItemInput;
}

/** Options of {@link Menu.palette}. */
export interface PaletteOptions {
  /**
   * Slots that become `source` slots, in order; the n-th item goes into the n-th slot and slots
   * past the last item are cleared. Default: one slot per item from slot 0, row by row.
   */
  readonly slots?: MenuArea;
}

/**
 * What a layout legend entry or a pane cell holds: a plain item is a `locked` decoration, an object
 * with `item` is a button (with `onClick` when it does something), and `{ storage }` is a storage
 * slot. `null` leaves the cell empty, so lower panes show through.
 */
export type MenuCell = ItemInput | MenuButton | MenuStorageCell | null;

/** A rectangle of a menu in columns and rows, 0-based from the top-left slot. */
export interface MenuRect {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

/**
 * Slots of a menu: indexes in order, a rectangle (row by row), or one character of the menu's last
 * {@link Menu.layout} (its slots in reading order).
 */
export type MenuArea = readonly number[] | MenuRect | string;

/** Options of a menu. */
export interface MenuOptions {
  title: string;
  /** Chest rows, 1 to 6. Default 3. */
  rows?: number;
  /** Shown in every empty slot. */
  filler?: ItemInput;
  /** Called when the menu closes. */
  onClose?: (reason: ContainerCloseReason) => void;
  /** The mod closes the menu after every click. Default false. */
  closeOnClick?: boolean;
  /** Esc asks the player to confirm before the menu closes. Default false. */
  requireAnswer?: boolean;
  /** When opened from another menu, the mod opens that parent again when the player closes this one. Default false. */
  reopenParent?: boolean;
  /** Slot of a Back button, shown while the menu was opened from another menu; the button look defaults to a named arrow. */
  back?: number | { readonly slot: number; readonly button?: MenuLook };
  /**
   * Keep the page, scroll offset, tab and component values of every pane per view key in the
   * extension's store (`ctx.store`, the plugin store when one is configured), and restore them when
   * the menu is declared again. Default false.
   */
  persist?: boolean;
  /** The player changed the menu's storage slots. */
  onStorage?: (slots: StorageContents, view: ContainerView) => void;
  /** A move into or out of an `ask` storage slot; answered as {@link AskReturn}. */
  onAsk?: (event: AskEvent) => AskReturn;
}

/** Options shared by panes and components. */
export interface PaneOptions {
  /**
   * Layering among the menu's panes: a higher priority covers lower ones where it has a cell, and
   * on a tie the pane added later wins. Slots set with {@link Menu.slot} and {@link Menu.layout}
   * form the base at priority 0, below every pane of the same priority. Default 0.
   */
  priority?: number;
}

/** Handle shared by every pane and component. After {@link remove} it does nothing. */
export interface MenuPane {
  /** Pane id within the menu; also the key its state persists under. */
  readonly id: string;
  readonly priority: number;
  /** False while hidden; a hidden pane shows nothing, and the panes below show through. */
  readonly visible: boolean;
  setVisible(visible: boolean): void;
  remove(): void;
}

/** A static pane: fixed cells over an area, or one cell repeated over it with `fill`. */
export interface StaticPaneOptions extends PaneOptions {
  readonly type?: 'static';
  readonly area: MenuArea;
  /** Cells in area order; missing ones are empty. */
  readonly cells?: readonly MenuCell[];
  /** A cell for every slot of the area that `cells` leaves empty. */
  readonly fill?: MenuCell;
}

/** An outline pane: one cell along the border of a rectangle. */
export interface OutlinePaneOptions extends PaneOptions {
  readonly type: 'outline';
  /** Default: the whole menu. */
  readonly area?: MenuRect;
  readonly cell: MenuCell;
}

/** Handle of a static or outline pane. */
export interface StaticPane extends MenuPane {
  /** Replaces the cells of a static pane, or the border cell of an outline pane (its first entry). */
  set(cells: readonly MenuCell[]): void;
}

/**
 * Search of a paginated pane: a button that asks the player for text on an anvil prompt and filters
 * the entries by it. The filter is per-view state like the page: it survives `set`, `menu.update`
 * and the navigation stack, and persists with {@link MenuOptions.persist}.
 */
export interface MenuSearchOptions {
  /** Slot of the search button. */
  readonly slot: number;
  /** Default a compass named `Search`; while a filter is active its lore names the filter and the match count. */
  readonly button?: MenuLook;
  /**
   * Slot of a clear button, shown only while a filter is active. An empty answer or a right click
   * on the search button clears the filter too.
   */
  readonly clear?: number;
  /** Default a barrier named `Clear search`. */
  readonly clearButton?: MenuLook;
  /** Question on the prompt. Default `Search`. */
  readonly prompt?: string;
  /** Item shown in the anvil's input slot. Default `minecraft:name_tag`. */
  readonly item?: string;
  /** Longest accepted answer, in characters. Default 50. */
  readonly maxLength?: number;
  /** Prompt deadline in ms; a timed-out prompt leaves the filter as it was. Default 60000. */
  readonly timeoutMs?: number;
  /**
   * The text a filter is matched against, case-insensitively as a substring. Default: the entry's
   * name as shown (plain text of styled names), else its item id and the id's path with spaces,
   * such as `minecraft:oak_log oak log`.
   */
  readonly searchText?: (entry: MenuCell, index: number) => string;
  /** Called after the filter changed through the button or {@link PagedPane.filterBy}; `''` when cleared. */
  readonly onSearch?: (filter: string) => void;
}

/** A paginated pane: entries over pages, with back and next buttons that only show when useful. */
export interface PagedPaneOptions extends PaneOptions {
  readonly area: MenuArea;
  readonly entries: readonly MenuCell[];
  /** Slot of the back button; omit to page only through the handle. */
  readonly prev?: number;
  /** Slot of the next button. */
  readonly next?: number;
  /** Default an arrow named `Previous page`. */
  readonly prevButton?: MenuLook;
  /** Default an arrow named `Next page`. */
  readonly nextButton?: MenuLook;
  /** First page shown, 0-based. Default 0. */
  readonly page?: number;
  /** Adds a search button that filters the entries through an anvil prompt. */
  readonly search?: MenuSearchOptions;
}

/** Handle of a paginated pane. */
export interface PagedPane extends MenuPane {
  /** Current page, 0-based. */
  readonly page: number;
  /** Number of pages, at least 1. */
  readonly pages: number;
  /** Shows a page; clamped into range. */
  show(page: number): void;
  next(): void;
  prev(): void;
  /** Replaces the entries and shows the clamped current page. */
  set(entries: readonly MenuCell[]): void;
  /** Active search filter, `''` when none. Pages count only the matching entries. */
  readonly filter: string;
  /** Number of entries that match the filter (all of them without one). */
  readonly matches: number;
  /**
   * Filters the entries by `text` (trimmed; case-insensitive substring of each entry's search
   * text) and shows the first page; `''` clears the filter. Works without the search option too.
   */
  filterBy(text: string): void;
  /**
   * Asks the player for a filter on the anvil prompt, as the search button does. The menu closes
   * while the anvil shows and reopens after, with its parent, pages and values intact. Resolves
   * with the filter in force afterwards; cancelling or a timeout leaves it as it was.
   */
  search(): Promise<string>;
}

/** A scrolling pane: a window over rows (or columns) of entries, moved one line at a time. */
export interface ScrollPaneOptions extends PaneOptions {
  readonly area: MenuRect;
  readonly entries: readonly MenuCell[];
  /** `vertical` (default) scrolls rows, `horizontal` columns. Entries fill lines in order. */
  readonly direction?: 'vertical' | 'horizontal';
  /** Slot of the scroll-back button (up or left). */
  readonly back?: number;
  /** Slot of the scroll-forward button (down or right). */
  readonly forward?: number;
  readonly backButton?: MenuLook;
  readonly forwardButton?: MenuLook;
  /** First line shown, 0-based. Default 0. */
  readonly offset?: number;
}

/** Handle of a scrolling pane. */
export interface ScrollPane extends MenuPane {
  /** First line shown, 0-based. */
  readonly offset: number;
  /** Largest offset, at least 0. */
  readonly maxOffset: number;
  /** Shows the window starting at `offset` lines; clamped into range. */
  scrollTo(offset: number): void;
  /** Moves the window by `lines`, negative towards the start. */
  scroll(lines: number): void;
  set(entries: readonly MenuCell[]): void;
}

/** One tab of a tabs pane. */
export interface MenuTab {
  readonly id: string;
  /** The tab button; the selected tab shows with a glint. */
  readonly icon: MenuLook;
  /** Cells of the content area while this tab is selected, or a function that builds them on every render. */
  readonly cells: readonly MenuCell[] | (() => readonly MenuCell[]);
}

/** A tabs pane: one button per tab, and a content area showing the selected tab. */
export interface TabsPaneOptions extends PaneOptions {
  readonly tabs: readonly MenuTab[];
  /** Slots of the tab buttons, in tab order. */
  readonly tabSlots: readonly number[];
  readonly area: MenuArea;
  /** Id of the selected tab. Default: the first. */
  readonly selected?: string;
  /** Called when the player selects another tab. */
  readonly onSelect?: (id: string) => void;
}

/** Handle of a tabs pane. */
export interface TabsPane extends MenuPane {
  readonly selected: string;
  select(id: string): void;
  /** Builds the selected tab's cells again, for tabs whose cells are a function. */
  refresh(): void;
}

/** Options of {@link Menu.toggle}: a button that flips between two looks. */
export interface ToggleOptions extends PaneOptions {
  /** Pane id; also the persistence key. Default `toggle@<slot>`. */
  readonly id?: string;
  readonly on: MenuLook;
  readonly off: MenuLook;
  /** Default false. */
  readonly value?: boolean;
  /** The player flipped it; the return value answers the click like a container handler. */
  readonly onChange?: (value: boolean, event: ContainerEvent) => ContainerHandlerReturn;
}

/** Handle of a toggle. */
export interface MenuToggle extends MenuPane {
  readonly value: boolean;
  /** Changes the value without calling `onChange`. */
  set(value: boolean): void;
}

/** Options of {@link Menu.cycle}: a button that steps through values; left click forward, right click back. */
export interface CycleOptions<V> extends PaneOptions {
  /** Pane id; also the persistence key. Default `cycle@<slot>`. */
  readonly id?: string;
  readonly values: readonly V[];
  /** Look of each value. */
  readonly look: (value: V, index: number) => MenuLook;
  /** Index of the first value shown. Default 0. */
  readonly index?: number;
  readonly onChange?: (value: V, event: ContainerEvent) => ContainerHandlerReturn;
}

/** Handle of a cycle button. */
export interface MenuCycle<V> extends MenuPane {
  readonly index: number;
  readonly value: V;
  /** Shows the value at `index` (wrapped into range) without calling `onChange`. */
  set(index: number): void;
}

/**
 * Options of {@link Menu.counter}: the item count is the value. Left click adds `step`, right click
 * subtracts it, and with Shift both use `shiftStep`.
 */
export interface CounterOptions extends PaneOptions {
  /** Pane id; also the persistence key. Default `counter@<slot>`. */
  readonly id?: string;
  readonly look: MenuLook;
  /** Default 1. */
  readonly min?: number;
  /** Default 64. */
  readonly max?: number;
  /** Default `min`. */
  readonly value?: number;
  /** Default 1. */
  readonly step?: number;
  /** Default 10. */
  readonly shiftStep?: number;
  readonly onChange?: (value: number, event: ContainerEvent) => ContainerHandlerReturn;
}

/** Handle of a counter. */
export interface MenuCounter extends MenuPane {
  readonly value: number;
  /** Sets the value, clamped into range, without calling `onChange`. */
  set(value: number): void;
}

/**
 * Options of {@link Menu.slider}: a row of slots where the knob marks the value. Clicking a slot moves
 * the knob there; slot `i` of `n` stands for `min + (max - min) * i / (n - 1)`.
 */
export interface SliderOptions extends PaneOptions {
  /** Pane id; also the persistence key. Default `slider@<first slot>`. */
  readonly id?: string;
  /** Default 0. */
  readonly min?: number;
  /** Default 1. */
  readonly max?: number;
  /** Default `min`. */
  readonly value?: number;
  /** Slots up to the knob. Default a lime pane. */
  readonly filled?: MenuLook;
  /** Slots after the knob. Default a gray pane. */
  readonly empty?: MenuLook;
  /** The knob. Default: the filled look with a glint. */
  readonly knob?: MenuLook;
  readonly onChange?: (value: number, event: ContainerEvent) => ContainerHandlerReturn;
}

/** Handle of a slider. */
export interface MenuSlider extends MenuPane {
  readonly value: number;
  /** Sets the value, clamped into range, without calling `onChange`. */
  set(value: number): void;
}

/**
 * Options of {@link Menu.bar}: a read-only percentage bar across slots. Whole slots show `filled`,
 * the partly filled slot shows its fraction with the item bar, and every slot is named with the
 * percentage.
 */
export interface BarOptions extends PaneOptions {
  /** Pane id. Default `bar@<first slot>`. */
  readonly id?: string;
  /** From 0 to 1. Default 0. */
  readonly value?: number;
  /** Default a lime pane. */
  readonly filled?: MenuLook;
  /** Default a gray pane. */
  readonly empty?: MenuLook;
}

/** Handle of a percentage bar. */
export interface MenuBar extends MenuPane {
  readonly value: number;
  /** Sets the value, clamped to 0 to 1. */
  set(value: number): void;
}

/** Options of {@link Menu.label}: text spelled with one item per character. */
export interface MenuLabelOptions extends PaneOptions {
  /** Pane id. Default `label@<first slot>`. */
  readonly id?: string;
  /**
   * The item of one character. Default: a white pane named with the character that shows it as its
   * count text. Spaces are always empty slots.
   */
  readonly font?: (char: string) => MenuLook;
}

/** Handle of a label. */
export interface MenuLabel extends MenuPane {
  readonly text: string;
  /** Spells new text; characters beyond the area are dropped. */
  set(text: string): void;
}

/**
 * A GUI-plugin style menu: a chest container view whose slots act as buttons, built from a base of
 * slots plus layered panes and components. Everything compiles to the view's slots and policies,
 * and changes update the open screen in place. Methods throw `invalid_message` for slots outside
 * the menu, unknown layout characters and options that do not fit. Adding a pane or component with
 * the id of an existing one replaces it, and it takes over that id's persisted state.
 */
export interface Menu {
  /** False once removed. */
  readonly alive: boolean;
  /** Merges `patch` into the options given to `menu(id, options)`; rows and `persist` cannot change. */
  update(patch: MenuUpdate): void;
  on<E extends keyof OpenCloseEvents>(event: E, fn: (...args: OpenCloseEvents[E]) => void): () => void;
  readonly id: string;
  readonly view: ContainerView;
  readonly isOpen: boolean;
  /** The menu this one was opened from with {@link open}, until it is opened on its own again. */
  readonly parent: Menu | undefined;
  /** Settles once persisted pane state was read and applied (at once without `persist`). */
  readonly restored: Promise<void>;
  /** Sets or, with `null`, clears a base button. */
  slot(index: number, button: MenuButton | null): Menu;
  /**
   * Applies a structure layout to the base: one string per row, one character per column (spaces
   * between characters are ignored), each character looked up in `legend`. Every character must be
   * in the legend; `null` entries leave their slots empty.
   */
  layout(rows: readonly string[], legend: Readonly<Record<string, MenuCell>>): Menu;
  /** Slots of one character of the last layout, in reading order. */
  area(char: string): number[];
  /**
   * Makes slots storage slots in the base. `source` slots need an item each, so they are made with
   * {@link palette} or a `{ storage: 'source', item }` layout cell.
   */
  storage(area: MenuArea | number, rule: SlotPolicy | SlotRule): Menu;
  /**
   * Fills base slots with `source` slots, one per item: the player takes copies of them without
   * using them up, to put into the menu's storage slots. Calling it again replaces the items.
   * @throws BridgeError `invalid_message` for more items than slots, or an item that does not convert.
   */
  palette(items: readonly ItemInput[], options?: PaletteOptions): Menu;
  /** Sets the filler shown in empty slots, or removes it with `null`. */
  fill(item: ItemInput | null): Menu;
  /** Adds a static or outline pane. */
  pane(id: string, options: StaticPaneOptions | OutlinePaneOptions): StaticPane;
  /** Adds a paginated pane: entries over pages, with optional back, next and search buttons. */
  paginated(id: string, options: PagedPaneOptions): PagedPane;
  scrolling(id: string, options: ScrollPaneOptions): ScrollPane;
  tabs(id: string, options: TabsPaneOptions): TabsPane;
  toggle(slot: number, options: ToggleOptions): MenuToggle;
  cycle<V>(slot: number, options: CycleOptions<V>): MenuCycle<V>;
  counter(slot: number, options: CounterOptions): MenuCounter;
  slider(area: MenuArea, options?: SliderOptions): MenuSlider;
  bar(area: MenuArea, options?: BarOptions): MenuBar;
  label(area: MenuArea, text: string, options?: MenuLabelOptions): MenuLabel;
  /** A pane or component by id. */
  get(id: string): MenuPane | undefined;
  /** Changes the title. */
  setTitle(title: string): Menu;
  /**
   * Opens this menu, or with `child` opens the child and records this menu as its parent. The
   * parent keeps its pages, scroll offsets and component values, so going back shows it as it was.
   */
  open(child?: Menu): void;
  /** Opens the parent again; closes the menu when it has none. */
  back(): void;
  close(): void;
  /** Removes the menu's view. */
  remove(): void;
}

/** A change to a menu's options. */
export type MenuUpdate = Partial<Omit<MenuOptions, 'rows' | 'persist'>>;

/** Options of `menu.confirm`. */
export interface ConfirmMenuOptions {
  /** Yes button: a label (shown on a lime pane) or a button. Default `Yes`. */
  yes?: string | Omit<MenuButton, 'onClick' | 'close'>;
  /** No button: a label (shown on a red pane) or a button. Default `No`. */
  no?: string | Omit<MenuButton, 'onClick' | 'close'>;
  /** Item shown in the middle, such as what is being confirmed. */
  item?: ItemInput;
  /** Answer when the deadline passes. Default false. */
  default?: boolean;
  /** Local deadline in ms. Default 30000. */
  timeoutMs?: number;
  /** Aborting closes the menu and rejects with `cancelled`. */
  signal?: AbortSignal;
}

/** Creates menus of one scope; menus live at `~view/<id>`. */
export interface MenuFactory {
  /**
   * A menu at `~view/<id>`. Calling it again with the same id returns the same menu, with its title
   * updated. Ids must be non-empty, without `/`, and not start with `~`.
   */
  (id: string, options: MenuOptions): Menu;
  /**
   * Opens a yes/no menu and resolves with the answer: `true` for yes, `false` for no, for closing the
   * menu, or the default at the deadline. Rejects with `cancelled` on abort or unload. The menu is
   * removed once answered.
   */
  confirm(title: string, options?: ConfirmMenuOptions): Promise<boolean>;
}

/** What a chat span's click does. */
export interface ChatClickAction {
  /**
   * - `action`: runs a context action of this scope; `value` is its id;
   * - `command`: runs a command line of this bot, such as `mine iron 32`;
   * - `suggest`: puts `value` into the chat box;
   * - `copy`: copies `value` to the clipboard;
   * - `url`: opens `value`, an http or https link, in the browser after the game asks the player;
   * - `bridge`: acts on the mod itself, by the player's click: `attach` or `detach` (this bot, so
   *   its windows, HUD and messages show in every world, or stop showing), `attach:<bot>` or
   *   `detach:<bot>` (a bot of this controller's crew), `menu` (opens Bridge home) or `settings`
   *   (opens the mod's settings). The mod adds its own line to the tooltip saying what the click
   *   does. An older mod shows the text without a click.
   */
  action: 'action' | 'command' | 'suggest' | 'copy' | 'url' | 'bridge';
  value: string;
}

/** A styled piece of a chat line. */
export interface ChatSpanInput {
  text: string;
  color?: ColorLike;
  bold?: boolean;
  italic?: boolean;
  underlined?: boolean;
  strikethrough?: boolean;
  /** Scrambled, as vanilla's obfuscated (`§k`) text. */
  obfuscated?: boolean;
  /** Tooltip text. */
  hover?: string;
  click?: ChatClickAction;
}

/** What `chat.print` accepts: plain text, one span, or a list of strings and spans. */
export type ChatContent = string | ChatSpanInput | readonly (string | ChatSpanInput)[];

/** Details of a chat line addressed to this bot. */
export interface ChatMessageInfo {
  /** The full line the player typed, prefix included. */
  readonly raw: string;
  /** Addressed to `all` rather than to this bot by name. */
  readonly broadcast: boolean;
}

/** Listener of chat lines addressed to the bot. */
export type ChatListener = (text: string, info: ChatMessageInfo) => void;

/**
 * The client-only chat channel. Nothing printed or received ever reaches the game server. The mod
 * gates it with the `chat` permission. After the owning extension unloads, `print` and `on` do
 * nothing.
 */
export interface ChatApi {
  /**
   * Adds a line to the player's chat, prefixed with the bot's name tag. A line is about now: while
   * the session is not open it is dropped rather than shown late. Throws `invalid_message` for an
   * empty line, a bad colour or an unknown click action.
   */
  print(content: ChatContent): void;
  /**
   * Listens for chat lines addressed to the bot (`@<bot> text` or `@all text`) that did not parse as
   * one of its commands. `text` is the line after the bot name. Removed on unload; returns a remover.
   */
  on(event: 'message', fn: ChatListener): () => void;
}

/** Input and HUD members of `bot.bridge`, controllers and extension contexts, each owned by one scope. */
export interface InputAndHud {
  readonly pick: PickApi;
  readonly prompt: PromptApi;
  /** Opens a form and resolves with its values; rejects like {@link PickApi}. */
  form<const F extends readonly FormFieldDef[], const B extends readonly FormButtonDef[] = readonly []>(definition: FormDefinition<F, B>): Promise<FormResult<F, B>>;
  /** Commands typed after this session's own name: `@<name> <command>`, `/bridge <name> <command>`. */
  readonly commands: CommandsApi;
  /**
   * Orders for the session's crew: typed after a crew bot's name, a crew group or `all`
   * (`@eco1 goto home`, `@farmers farm`, `@all stop`) rather than after the session's own name, and
   * run once per line with {@link InvocationContext.bots} naming the bots addressed. A controller
   * that drives several bots declares its orders here and its own commands under `commands`.
   * Orders reach nobody until {@link CrewApi.set} names the bots.
   */
  readonly orders: CommandsApi;
  /** Who this session's {@link orders} are for. */
  readonly crew: CrewApi;
  readonly actions: ActionsApi;
  /** Rules on the player's interactions with real blocks and entities. */
  readonly intercepts: InterceptsApi;
  readonly keybinds: KeybindsApi;
  readonly tools: ToolsApi;
  readonly settings: SettingsApi;
  /** A HUD panel at `~hud/<panel id>`. Calling it again with the same id returns the same panel and applies `options`. */
  hud(panelId: string, options?: HudPanelOptions): HudPanel;
  /** Declares or replaces a boss bar at `~bossbar/<id>`. */
  bossbar(id: string, options: BossbarOptions): BossbarHandle;
  /**
   * Declares a container view at `~view/<id>`. Calling it again with the same id updates that view
   * and returns the same handle. Ids must be non-empty, without `/`, and not start with `~`.
   */
  containerView(id: string, options: ContainerViewOptions): ContainerView;
  /** Menus built on container views; `menu.confirm` asks a yes/no question. */
  readonly menu: MenuFactory;
  /**
   * Declares a screen at `~screen/<id>`: tabs of rows of vanilla widgets, drawn like Bridge home.
   * Calling it again with the same id updates that screen and returns the same handle. Ids must be
   * non-empty, without `/`, and not start with `~`.
   */
  screen(id: string, options: ScreenOptions): Screen;
  readonly chat: ChatApi;
  /** Client-only sounds, particles, titles and action-bar messages. */
  readonly effects: EffectsApi;
  /**
   * Asks the player to choose one of `entries`, shown as interactive displays around `at`. It
   * queues with picks and prompts. Resolves with the chosen entry id; rejects like {@link PickApi}
   * (`world_mismatch` when the player is in another world), and with `invalid_message` before
   * anything is sent for bad options (no entries, duplicate ids, an entry with nothing to show, an
   * unknown layout, a timeout outside 1 to 600000 ms).
   */
  worldMenu<const I extends string>(options: WorldMenuOptions<I>): Promise<I>;
}

/** Passed to the handlers of a screen's buttons, toggles and cycles. */
export interface ScreenEventContext extends InvocationContext {
  readonly screen: Screen;
  /** Id of the item the player used. */
  readonly item: string;
}

/** Options every interactive screen item takes. */
export interface ScreenControlOptions {
  /** Names the item in events; defaults to its position (`<tab>.<row>.<column>`). Give one when rows move. */
  id?: string;
  tooltip?: string;
  /** Width in GUI pixels; items without one share the row evenly. */
  width?: number;
  /** Default true. A disabled control is drawn greyed out and sends nothing. */
  enabled?: boolean;
  /** Sound event the mod plays when the player uses the control, with no round trip; e.g. `block.note_block.pling`. */
  sound?: string;
}

export interface ScreenButton extends ScreenControlOptions {
  /** The label. */
  button: string;
  /** Item id drawn before the label, e.g. `minecraft:compass`. */
  icon?: string;
  /** Runs on click; returns like an action, so a string or error shows as a toast. */
  onClick?: (ctx: ScreenEventContext) => InvocationReturn;
  /** A screen of this scope, or its id, opened as a child on click without a round trip. */
  open?: Screen | string;
}

export interface ScreenToggle extends ScreenControlOptions {
  /** The label. */
  toggle: string;
  value: boolean;
  /** Runs when the player flips it; throw or reject to refuse, and the toggle shows `value` again. */
  onChange?: (value: boolean, ctx: ScreenEventContext) => InvocationReturn;
}

export interface ScreenCycle extends ScreenControlOptions {
  /** The label. */
  cycle: string;
  options: readonly string[];
  /** The chosen option, or its index. */
  value: string | number;
  /** Runs when the player picks another option; throw or reject to refuse. */
  onChange?: (value: string, ctx: ScreenEventContext) => InvocationReturn;
}

/** One item of a screen row; the key that names its kind (`heading`, `text`, `button`, ...) carries its text. */
export type ScreenItemInput =
  | { heading: string }
  | { text: string; color?: ColorLike; width?: number }
  | ScreenButton
  | ScreenToggle
  | ScreenCycle
  | { item: string; text?: string; tooltip?: string; width?: number }
  | { spacer: true; width?: number };

/** A row: one item, or several laid out side by side. */
export type ScreenRowInput = ScreenItemInput | readonly ScreenItemInput[];

export interface ScreenTabInput {
  /** Default: the tab's position. */
  id?: string;
  label: string;
  rows: readonly ScreenRowInput[];
}

export interface ScreenOptions {
  title: string;
  /**
   * The tabs, or a function that builds them. A function runs on {@link Screen.refresh}, each time the
   * player opens the screen and after every accepted toggle or cycle change, so it can read live state.
   * With one tab the screen has no tab bar; `rows` is shorthand for that.
   */
  tabs?: readonly ScreenTabInput[] | (() => readonly ScreenTabInput[]);
  /** Rows of a screen with a single tab, or a function that builds them. */
  rows?: readonly ScreenRowInput[] | (() => readonly ScreenRowInput[]);
  /**
   * `tab`: the tabs also appear on Bridge home. `menu`: the screen is offered as the player's primary
   * menu (the B key) and becomes it once the player picks it under Tools > Primary menu.
   */
  home?: 'tab' | 'menu';
  /** Sort order among contributed home tabs; lower first. Default 0. */
  order?: number;
  onOpen?: (screen: Screen) => void;
  onClose?: (screen: Screen, reason: ContainerCloseReason) => void;
}

/** Lifecycle events of screens and menus. */
export interface OpenCloseEvents {
  open: [];
  close: [reason: ContainerCloseReason];
  /** The handle was removed and is inert. */
  remove: [];
}

/** A declared screen. */
export interface Screen {
  readonly id: string;
  /** False once removed. */
  readonly alive: boolean;
  /** The player has it open. */
  readonly isOpen: boolean;
  /** Merges `patch` into the options; the same as `set` with the merged options. */
  update(patch: Partial<ScreenOptions>): void;
  on<E extends keyof OpenCloseEvents>(event: E, fn: (...args: OpenCloseEvents[E]) => void): () => void;
  /** Asks the mod to open it; needs `ui.container` and an attached session. */
  open(): void;
  close(): void;
  /** Builds the tabs again from their functions and sends what changed. */
  refresh(): void;
  /** Replaces the options; the same as calling `screen(id, options)` again. */
  set(options: ScreenOptions): void;
  remove(): void;
}
