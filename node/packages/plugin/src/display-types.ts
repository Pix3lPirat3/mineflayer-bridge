import type { KindPayloads, WorldKey } from '@mineflayer-bridge/protocol';
import type { ItemInput, RefusedDecision, RefusedHandler, Vec3Value } from './input-types.ts';
import type { LayerObjectHandle, TraitOptions } from './traits-types.ts';
import type { ColorLike, Vec3Like } from './types.ts';

/**
 * How a display turns towards the camera: `fixed` never turns, `vertical` turns around the Y axis
 * only, `horizontal` tilts only, `center` always faces the camera.
 */
export type Billboard = 'fixed' | 'vertical' | 'horizontal' | 'center';

/** Client-side animation loops. They run without traffic. */
export type AnimationType = 'bob' | 'spin' | 'pulse' | 'sway';

/** An animation loop with its parameters. */
export interface Animation {
  type: AnimationType;
  /** Size of the motion: blocks for `bob` and `sway`, a scale factor for `pulse`, ignored by `spin`. Default 0.1. */
  amplitude?: number;
  /** Length of one loop in ms, a positive integer. Default 2000. */
  periodMs?: number;
}

/**
 * A rotation as Minecraft angles in degrees: `yaw` 0 faces south (+Z) and grows clockwise seen from
 * above (90 faces west), positive `pitch` tilts the front down, and `roll` turns around the front
 * axis. They apply roll first, then pitch, then yaw. Omitted angles are 0.
 */
export interface EulerAngles {
  yaw?: number;
  pitch?: number;
  roll?: number;
}

/** A rotation quaternion as `[x, y, z, w]`, sent as is. */
export type Quaternion = readonly [number, number, number, number];

/** Transform applied to a display after its position, the layout of vanilla display entities. */
export interface DisplayTransformOptions {
  /** Translation relative to the position, in blocks. */
  offset?: Vec3Like;
  /** A quaternion `[x, y, z, w]`, or angles in degrees converted to one. */
  rotation?: Quaternion | EulerAngles;
  /** Scale per axis; a number scales all three. */
  scale?: number | Vec3Like;
}

/** Look while the player hovers an element. The mod switches to it without a round trip. */
export interface HoverOptions {
  /** Scale multiplier while hovered. */
  scale?: number;
  /** Outline colour while hovered. */
  glow?: ColorLike;
  /** Text shown instead of the display's own text while hovered (text displays). */
  text?: string;
}

/** Modifier keys held during an element interaction. */
export interface ElementModifiers {
  readonly shift: boolean;
  readonly ctrl: boolean;
  readonly alt: boolean;
}

/** Interaction names as handlers see them. */
export type ElementInputName = 'hover' | 'leave' | 'primary' | 'secondary' | 'middle' | 'scroll' | 'key' | 'drag';

/** Phase of a drag: `start` on press, `move` while held, `end` on release. */
export type DragPhase = 'start' | 'move' | 'end';

/** One interaction with an interactive element. */
export interface ElementEvent {
  /** Shape id of the element in its layer (group children: `<group id>/<child id>`). */
  readonly id: string;
  /** Layer name. */
  readonly layer: string;
  readonly input: ElementInputName;
  /** Hit point relative to the element's position, in blocks. Set for clicks and drags. */
  readonly local: Vec3Value | undefined;
  readonly modifiers: ElementModifiers;
  /** Accumulated scroll steps for `scroll`; positive is away from the player. */
  readonly delta: number | undefined;
  /** Vanilla key name for `key`, such as `key.keyboard.q`. */
  readonly key: string | undefined;
  /** Set for `drag`. */
  readonly phase: DragPhase | undefined;
}

/** What element handlers may return; a rejection is reported as the session's `error` event. */
export type ElementHandlerReturn = void | Promise<void>;

/** Handler of one element interaction. */
export type ElementHandler = (event: ElementEvent) => ElementHandlerReturn;

/**
 * Element interaction handlers. The interactions declared to the mod are derived from the handlers
 * present, so the mod only reports what the bot listens to (the hover look switches either way).
 */
export interface ElementHandlers {
  /** The crosshair entered the element. */
  hover?: ElementHandler;
  /** The crosshair left the element. */
  leave?: ElementHandler;
  /** Attack button. Consumes the click, so vanilla never sees it. */
  primary?: ElementHandler;
  /** Use button. */
  secondary?: ElementHandler;
  /** Pick-block button. */
  middle?: ElementHandler;
  /** Scroll wheel while hovering, with the accumulated steps. */
  scroll?: (delta: number, event: ElementEvent) => ElementHandlerReturn;
  /**
   * Keys pressed while hovering: one handler for every key in {@link InteractiveOptions.keys}, or a
   * handler per key. Bare names such as `q` mean `key.keyboard.q`.
   */
  key?: ((key: string, event: ElementEvent) => ElementHandlerReturn) | Readonly<Record<string, ElementHandler>>;
  /** Drag start, moves and end, with `phase` and the hit point in element-local space. */
  drag?: ElementHandler;
}

/** Makes an object interactive. */
export interface InteractiveOptions {
  /** Tooltip shown while hovering. */
  tooltip?: string;
  /** Keys delivered while hovering, for a `key` handler function. Bare names mean `key.keyboard.<name>`. */
  keys?: readonly string[];
  /** Interaction range in blocks. Default: the player's reach. */
  maxDistance?: number;
  /** Size of a custom hit box centred on the element, in blocks; a number means a cube. Default: from the visual. */
  hitbox?: number | Vec3Like;
  /** Tags matched by context actions registered for the `element` target type. */
  tags?: readonly string[];
  on?: ElementHandlers;
}

/** Where a declared object lives and how long, and its traits; shared by displays, groups and emitters. */
export interface PlacementOptions extends TraitOptions {
  /** The mod removes the object this many ms after it receives the latest write. */
  ttlMs?: number;
  /** Persist through the plugin store and re-declare on start. Ignored with `ttlMs`. Handlers are not persisted. */
  persistent?: boolean;
  /** World binding. Default: the bot's current world when known, otherwise global. */
  world?: WorldKey | 'global';
}

/** Fields shared by every display kind. Omitted fields take the schema defaults. */
export interface DisplayOptions extends PlacementOptions {
  /**
   * Shape id of a group in the same layer; the position is then relative to that group. Group
   * methods set it for their children.
   */
  group?: string;
  /** Overall scale factor. Default 1. */
  scale?: number;
  transform?: DisplayTransformOptions;
  /** Default `fixed`. */
  billboard?: Billboard;
  /** Duration in ms over which the mod animates to new positions and transforms. Default 0. */
  interpolate?: number;
  /** Same as {@link interpolate}, in ms. */
  interpolateMs?: number;
  /** A client-side loop, by name (default amplitude and period) or with parameters. */
  animate?: AnimationType | Animation;
  /** Fixed light level, an integer from 0 to 15. Default: world light. */
  brightness?: number;
  /** Outline colour. Default: none. */
  glow?: ColorLike;
  /** Hidden beyond this distance, in blocks. Default 64. */
  maxDistance?: number;
}

/** Text alignment of a text display. */
export type TextAlign = 'left' | 'center' | 'right';

/** Options of `layer.text`. */
export interface TextDisplayOptions extends DisplayOptions {
  /** Wrap width in pixels. Default 200. */
  lineWidth?: number;
  /** Background colour. Default 25% black. */
  background?: ColorLike;
  shadow?: boolean;
  /** Visible through blocks. */
  seeThrough?: boolean;
  /** Default `center`. */
  align?: TextAlign;
}

/** How an item display renders its stack, as vanilla item display contexts. */
export type ItemDisplayMode = 'ground' | 'fixed' | 'head' | 'gui';

/** Options of `layer.item`. */
export interface ItemDisplayOptions extends DisplayOptions {
  /** Default `ground`. */
  mode?: ItemDisplayMode;
}

/** Options of `layer.block`. */
export interface BlockDisplayOptions extends DisplayOptions {}

/** Equipment slots of an entity display. */
export type EquipmentSlot = 'head' | 'chest' | 'legs' | 'feet' | 'mainhand' | 'offhand';

/** Options of `layer.entity`. */
export interface EntityDisplayOptions extends DisplayOptions {
  /** Body yaw in degrees, Minecraft convention (0 south, 90 west). Default 0. */
  yaw?: number;
  /** Pitch in degrees, positive looks down. Default 0. */
  pitch?: number;
  /** Head yaw in degrees. Default: the body yaw. */
  headYaw?: number;
  /** Entity pose, such as `standing`, `crouching`, `sleeping` or `swimming`. */
  pose?: string;
  /** Custom name. */
  name?: string;
  /** Show the custom name without looking at the entity. Default false. */
  nameVisible?: boolean;
  baby?: boolean;
  /** Player name or UUID whose skin a player model uses. */
  skin?: string;
  /** Items worn and held, as item ids, item objects or mineflayer items. */
  equipment?: Readonly<Partial<Record<EquipmentSlot, ItemInput>>>;
  /** Hide the model; equipment and the name still show. Default false. */
  invisible?: boolean;
  /** Burning, with vanilla's fire overlay. Default false. */
  onFire?: boolean;
  /** Shaking as in powder snow. Default false. */
  frozen?: boolean;
  /** Variant of cats, wolves, horses, frogs, axolotls, villager types and similar, e.g. `minecraft:tabby`. */
  variant?: string;
  /** Sheep wool, collar, llama carpet or shulker colour, as a dye colour name such as `red`. */
  color?: string;
  /** Villager profession, e.g. `minecraft:librarian`. */
  profession?: string;
  /** Charged creeper. Default false. */
  charged?: boolean;
  /** Saddled pig, strider or horse. Default false. */
  saddled?: boolean;
  /** Armor stand looks and part poses (`minecraft:armor_stand`). */
  armorStand?: ArmorStandOptions;
  /**
   * Status effects whose particles swirl around the entity; a bare id such as `speed` means
   * `minecraft:speed`.
   */
  effects?: readonly string[];
  /**
   * Entity data applied on the client as `/summon` would, for anything not covered above: an SNBT
   * string, or an object serialised to SNBT (see {@link NbtCompound}).
   */
  data?: string | NbtCompound;
}

/** Rotation of an armor stand part as `[x, y, z]` in degrees, vanilla convention. */
export type PartPose = readonly [number, number, number];

/** Armor stand options of an entity display. */
export interface ArmorStandOptions {
  /** Default false. */
  small?: boolean;
  /** Default false. */
  showArms?: boolean;
  /** Default false. */
  noBasePlate?: boolean;
  /** A marker stand: no hit box and a tiny size. Default false. */
  marker?: boolean;
  /** Part rotations; omitted parts keep vanilla's default pose. */
  pose?: {
    head?: PartPose;
    body?: PartPose;
    leftArm?: PartPose;
    rightArm?: PartPose;
    leftLeg?: PartPose;
    rightLeg?: PartPose;
  };
}

/** A number with an explicit SNBT type, made by the `nbt` helpers (`nbt.float(1.5)` is `1.5f`). */
export interface NbtTyped {
  readonly nbtType: 'byte' | 'short' | 'int' | 'long' | 'float' | 'double' | 'byteArray' | 'intArray' | 'longArray';
  readonly value: number | bigint | readonly (number | bigint)[];
}

/**
 * A value serialised to SNBT: strings are quoted, booleans become `1b` and `0b`, integers stay ints,
 * other numbers become doubles, and arrays become lists. `undefined` properties are left out.
 */
export type NbtValue = string | number | boolean | NbtTyped | readonly NbtValue[] | NbtCompound;

/** An SNBT compound. */
export interface NbtCompound {
  readonly [key: string]: NbtValue | undefined;
}

/**
 * A partial update of an object: given fields change, `null` restores a field's default, and
 * omitted fields keep their value.
 */
export type Patch<O> = { [K in keyof O]?: O[K] | null };

/** Update of a text display; `text` replaces the text. */
export type TextDisplayUpdate = Patch<Omit<TextDisplayOptions, 'group'>> & { text?: string };
/** Update of an item display; `item` replaces the stack. */
export type ItemDisplayUpdate = Patch<Omit<ItemDisplayOptions, 'group'>> & { item?: ItemInput };
/** Update of a block display; `state` replaces the block state. */
export type BlockDisplayUpdate = Patch<Omit<BlockDisplayOptions, 'group'>> & { state?: string };
/** Update of an entity display; `type` replaces the entity type. */
export type EntityDisplayUpdate = Patch<Omit<EntityDisplayOptions, 'group'>> & { type?: string };

/** Options of `move`. */
export interface MoveOptions {
  /** Animate to the new position over this many ms. Default: the object's `interpolate`. */
  interpolate?: number;
  /** Same as {@link interpolate}, in ms. */
  interpolateMs?: number;
}

/**
 * Handle of one declared object in a layer. Changes are field patches; writes made in one tick go
 * out together. After `remove()`, or when the layer is cleared, the object replaced by a different
 * kind, or the owning extension unloaded, the handle does nothing. `D` is the kind's wire data, as
 * edit handlers see it.
 */
export interface PlacedHandle<U, D> extends LayerObjectHandle<D> {
  /**
   * Changes some fields.
   * @throws BridgeError `invalid_message` for a field that does not fit; nothing changes then.
   */
  update(patch: U): void;
  /** Moves to `pos` (blocks; relative to the group for group children). */
  move(pos: Vec3Like, options?: MoveOptions): void;
}

/** Handle of a `core:display.text`. */
export type TextDisplay = PlacedHandle<TextDisplayUpdate, KindPayloads['core:display.text']>;
/** Handle of a `core:display.item`. */
export type ItemDisplay = PlacedHandle<ItemDisplayUpdate, KindPayloads['core:display.item']>;
/** Handle of a `core:display.block`. */
export type BlockDisplay = PlacedHandle<BlockDisplayUpdate, KindPayloads['core:display.block']>;
/** Handle of a `core:display.entity`. */
export type EntityDisplay = PlacedHandle<EntityDisplayUpdate, KindPayloads['core:display.entity']>;

/**
 * Methods that declare displays. Calling one again with the same id replaces the object; when the
 * kind is the same, the same handle is returned with all options replaced. Ids must be non-empty
 * and must not contain `/`.
 *
 * Every method throws a `BridgeError`: `invalid_message` for an option that does not fit, and
 * `quota_exceeded` when the session already declares `limits.maxDisplays` displays or
 * `limits.maxEntityDisplays` entity displays, as the mod advertised them (defaults 4096 and 256).
 */
export interface DisplayFactory {
  /** Rich text at `pos`. Needs no armor stand. */
  text(id: string, pos: Vec3Like, text: string, options?: TextDisplayOptions): TextDisplay;
  /** An item stack: an id string, an item object or a mineflayer `Item`. */
  item(id: string, pos: Vec3Like, item: ItemInput, options?: ItemDisplayOptions): ItemDisplay;
  /** A block state, such as `minecraft:chest[facing=north]`, including block entity visuals. */
  block(id: string, pos: Vec3Like, state: string, options?: BlockDisplayOptions): BlockDisplay;
  /** An entity model of `type`, such as `minecraft:zombie` or `minecraft:player`. */
  entity(id: string, pos: Vec3Like, type: string, options?: EntityDisplayOptions): EntityDisplay;
  /** A transform node other displays parent to. */
  group(id: string, options: GroupOptions): Group;
  /**
   * A long-running particle emitter at `pos`, such as `minecraft:flame`. Quota: 32 per session.
   */
  emitter(id: string, pos: Vec3Like, particle: string, options?: EmitterOptions): ParticleEmitter;
  /**
   * A sound that plays while the object exists: a sound event id such as
   * `minecraft:block.beacon.ambient`, or the bytes of an Ogg Vorbis file. Removing it stops the
   * sound. Quota: 16 per session.
   */
  sound(id: string, pos: Vec3Like, sound: SoundSource, options?: SoundEmitterOptions): SoundEmitter;
}

/** What a group follows: the bot itself, an entity, or an entity id. */
export type GroupFollow = 'self' | number | { readonly id: number };

/** Options of `layer.group`. */
export interface GroupOptions extends PlacementOptions {
  /** Position in blocks. Required unless the group follows something; relative to the parent group for nested groups. */
  pos?: Vec3Like;
  /**
   * Follow the bot (`'self'`) or an entity on the client, interpolated every frame. Only top-level
   * groups follow; nested groups move with their parent.
   */
  follow?: GroupFollow;
  /** Offset from the followed entity's position, in blocks. Default `[0, 0, 0]`. Only with `follow`. */
  offset?: Vec3Like;
  /** Default `fixed`. */
  billboard?: Billboard;
  /** Duration in ms over which the mod animates the group to new positions. Default 0. */
  interpolate?: number;
  /** Same as {@link interpolate}, in ms. */
  interpolateMs?: number;
  transform?: DisplayTransformOptions;
}

/** Update of a group; `follow: null` stops following, which then needs a `pos`. */
export type GroupUpdate = Patch<Omit<GroupOptions, 'pos'>> & { pos?: Vec3Like };

/**
 * A group handle. Its display methods declare children positioned relative to the group, with shape
 * ids `<group id>/<child id>`. Moving the group moves its children in one write; removing it removes
 * every child and nested group with one prefix removal.
 */
export interface Group extends DisplayFactory, PlacedHandle<GroupUpdate, KindPayloads['core:group']> {}

/** Options of `layer.emitter`. */
export interface EmitterOptions extends PlacementOptions {
  /** Shape id of a group in the same layer; `pos` is then relative to it. Group methods set it. */
  group?: string;
  /** Bursts per second. Default 4. */
  rate?: number;
  /** Particles per burst, a non-negative integer. Default 1. */
  count?: number;
  /** Random spread per axis in blocks; a number means the same on every axis. */
  spread?: number | Vec3Like;
  /** Particle speed. Default 0. */
  speed?: number;
  /** Particle options, such as `{ color, scale }` for `minecraft:dust`. Must be MessagePack-encodable. */
  options?: unknown;
}

/** Update of an emitter; `particle` replaces the particle type. */
export type EmitterUpdate = Patch<EmitterOptions> & { particle?: string };

/** Handle of a `core:particle-emitter`. `move` ignores `interpolate`. */
export type ParticleEmitter = PlacedHandle<EmitterUpdate, KindPayloads['core:particle-emitter']>;

/** Vanilla sound category; the player's slider for it applies, then the Bot sounds setting. */
export type SoundCategory = 'master' | 'music' | 'record' | 'weather' | 'block' | 'hostile' | 'neutral' | 'player' | 'ambient' | 'voice' | 'ui';

/** A sound event id such as `minecraft:block.note_block.pling`, or the bytes of an Ogg Vorbis file. */
export type SoundSource = string | Uint8Array;

/** Options of `layer.sound`. */
export interface SoundEmitterOptions extends PlacementOptions {
  /** Shape id of a group in the same layer; `pos` is then relative to it. Group methods set it. */
  group?: string;
  /** Default true. False plays at the listener, wherever the player is. */
  positional?: boolean;
  /** Default 1. Above 1 also carries farther, as in vanilla. */
  volume?: number;
  /** Default 1. */
  pitch?: number;
  /** Default true: loops seamlessly. False plays once, and again on every `play()`. */
  loop?: boolean;
  /** With `loop`: a pause between plays in ms, instead of a seamless loop. */
  interval?: number;
  /** Default `neutral`. */
  category?: SoundCategory;
}

/** Update of a sound emitter; `sound` replaces what plays. */
export type SoundEmitterUpdate = Patch<SoundEmitterOptions> & { sound?: SoundSource };

/** Handle of a `core:sound-emitter`. `remove()` stops it. */
export interface SoundEmitter extends PlacedHandle<SoundEmitterUpdate, KindPayloads['core:sound-emitter']> {
  /** Plays a non-looping sound again; a looping one restarts. */
  play(): void;
}

/** Options of `effects.sound`. */
export interface SoundOptions {
  /** Default `neutral`, or `ui` when not positional. */
  category?: SoundCategory;
  /** Position in blocks. Default: played at the player, not positional. */
  at?: Vec3Like;
  /** Default 1. */
  volume?: number;
  /** Default 1. */
  pitch?: number;
}

/** Options of `effects.particles`. */
export interface ParticleOptions {
  /** Position in blocks. */
  at: Vec3Like;
  /** A non-negative integer. Default 1. */
  count?: number;
  /** Random spread per axis in blocks; a number means the same on every axis. */
  spread?: number | Vec3Like;
  /** Default 0. */
  speed?: number;
  /** Particle options. Must be MessagePack-encodable. */
  options?: unknown;
}

/** Options of `effects.title`. Times are in ticks (1/20 s), as vanilla titles use. */
export interface TitleOptions {
  subtitle?: string;
  /** Default 10. */
  fadeIn?: number;
  /** Default 70. */
  stay?: number;
  /** Default 20. */
  fadeOut?: number;
}

/**
 * Fire-and-forget effects played only in the player's client. They are unreliable: dropped while
 * the session is not open or the connection is backed up, and never replayed. Invalid arguments
 * throw `BridgeError` `invalid_message`.
 */
export interface EffectsApi {
  /** Plays a sound event, such as `minecraft:block.note_block.pling` (a bare name gets `minecraft:`). */
  sound(id: string, options?: SoundOptions): void;
  /** Spawns particles of `type`, such as `minecraft:happy_villager`. */
  particles(type: string, options: ParticleOptions): void;
  /** Shows a title and optional subtitle. */
  title(title: string, options?: TitleOptions): void;
  /** Shows an action-bar message, labelled with the bot's name. */
  actionBar(text: string): void;
}

/** One entry of a world menu. It needs at least one of `label`, `item`, `block`, `entity` or `text`. */
export interface WorldMenuEntry<I extends string = string> {
  /** Unique within the menu; the menu resolves with it. */
  id: I;
  /** Shown while the entry is hovered. */
  label?: string;
  /** Item shown as the entry: an id, an item object or a mineflayer `Item`. */
  item?: ItemInput;
  /** Block state shown as the entry. */
  block?: string;
  /** Entity type shown as the entry. */
  entity?: string;
  /** Text shown as the entry. */
  text?: string;
}

/** How world menu entries are laid out around their point. */
export type WorldMenuLayout = 'row' | 'column' | 'ring' | 'grid';

/** Options of `worldMenu`. */
export interface WorldMenuOptions<I extends string = string> {
  /** Centre of the menu, in blocks. */
  at: Vec3Like;
  /** At least one entry. */
  entries: readonly WorldMenuEntry<I>[];
  /** Default `ring`. */
  layout?: WorldMenuLayout;
  /** Distance of the entries from `at`, in blocks. Default 1.5. */
  radius?: number;
  /** Action-bar hint shown with the bot's name. */
  prompt?: string;
  /** Deadline in ms, counted by the mod from receipt. Default 60000, at most 600000. */
  timeoutMs?: number;
  /** Aborting sends `world-menu.cancel` and rejects with `cancelled`. */
  signal?: AbortSignal;
  /**
   * What to do when the mod refuses to open this: `'reject'`, `'wait'` or a handler. Default: the
   * bridge's `surfaces.onRefused`, otherwise `'reject'`. See {@link RefusedHandler}.
   */
  refused?: RefusedDecision | RefusedHandler;
}
