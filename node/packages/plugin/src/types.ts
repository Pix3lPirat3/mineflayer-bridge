import type {
  BotIdentity,
  ClientSample,
  DepthMode,
  Detail,
  ExtensionRef,
  Interest,
  KindName,
  KindPayloads,
  ObjectTraits,
  Permission as KnownPermission,
  Scope,
  SessionKind,
  WorldKey,
} from '@mineflayer-bridge/protocol';
import type { KindType, MessageType } from '@mineflayer-bridge/protocol';
import type { QueryApi } from './queries.ts';
import type { DisplayFactory, EmitterOptions, ParticleEmitter, Patch } from './display-types.ts';
import type {
  FormValues,
  InputAndHud,
  InvocationContext,
  InvocationReturn,
  MineflayerItemLike,
  SurfaceOptions,
  SurfaceRefusal,
} from './input-types.ts';
import type { LayerObjectHandle, TraitOptions } from './traits-types.ts';
import type { RetainedFactory, Volume, VolumeOptions } from './retained-types.ts';
import type { SharedPoints, SharedRoutes, SharedZones, SpatialFactory } from './spatial-types.ts';

/** A position in world space, in blocks. Accepts a mineflayer `Vec3`, `{x, y, z}` or `[x, y, z]`. */
export type Vec3Like = { readonly x: number; readonly y: number; readonly z: number } | readonly [number, number, number];

/**
 * A colour: `'#rrggbb'` (opaque), `'#rrggbbaa'`, or a number. Numbers up to `0xFFFFFF` are opaque
 * RGB; larger numbers are packed ARGB as vanilla uses.
 */
export type ColorLike = string | number;

/** A permission name: one of the known ones, or another one a mod grants. */
export type Permission = KnownPermission | (string & {});

/** Structured logger. `data` is optional context. */
export interface Logger {
  debug(message: string, data?: Record<string, unknown>): void;
  info(message: string, data?: Record<string, unknown>): void;
  warn(message: string, data?: Record<string, unknown>): void;
  error(message: string, data?: Record<string, unknown>): void;
}

/** Anything with Node-style `on`/`off`. Mineflayer bots and Node `EventEmitter`s qualify. */
export interface EmitterLike {
  // Listener arguments are typed `any[]` to match Node's EventEmitter signature, so a mineflayer bot
  // (an EventEmitter) is accepted under strict settings.
  on(event: string, listener: (...args: any[]) => void): unknown;
  off?(event: string, listener: (...args: any[]) => void): unknown;
  removeListener?(event: string, listener: (...args: any[]) => void): unknown;
}

/**
 * The parts of a mineflayer `Bot` the plugin reads. Typed structurally so mineflayer stays an
 * optional peer dependency.
 */
export interface BotLike extends EmitterLike {
  /** In-game name; set by mineflayer at login. */
  username?: string | undefined;
  entity?: { readonly position: Vec3Like; readonly id?: number } | null;
  game?: { readonly dimension?: string } | null;
  player?: { readonly uuid?: string } | null;
  /**
   * The protocol client (mineflayer `bot._client`). Its `login` and `respawn` packets name the world
   * the bot is in, which `game.dimension` does not: a server can run worlds such as `minecraft:creative`
   * on the overworld dimension type.
   */
  _client?: EmitterLike & {
    /** The signed-in account (node-minecraft-protocol `client.session`), for online-mode bots. */
    readonly session?: { readonly selectedProfile?: { readonly id?: string } } | null;
  };
  /** The player inventory window, read by `bridge:window`. Emits `updateSlot(slot, oldItem, newItem)`. */
  inventory?: InventoryLike;
  /** Drops a whole stack (mineflayer `bot.tossStack`). */
  tossStack?(item: MineflayerItemLike): Promise<void>;
  /** Drops `count` items of a type (mineflayer `bot.toss`). */
  toss?(itemType: number, metadata: number | null, count: number | null): Promise<void>;
  /** Moves a stack between inventory window slots (mineflayer `bot.moveSlotItem`). */
  moveSlotItem?(sourceSlot: number, destSlot: number): Promise<void>;
  /** Installed by the plugin. */
  bridge?: BotBridge;
}

/** The parts of a mineflayer inventory `Window` the plugin reads. */
export interface InventoryLike extends EmitterLike {
  /** Window slots by index; `null` is empty. */
  readonly slots: readonly (MineflayerItemLike | null | undefined)[];
}

/**
 * The `createBot` options mineflayer passes to plugins as the second argument. Fields accept
 * `undefined` and `auth` accepts mineflayer's custom auth functions, so mineflayer's own
 * `BotOptions` type fits under `exactOptionalPropertyTypes`.
 */
export interface BotOptionsLike {
  host?: string | undefined;
  port?: number | undefined;
  username?: string | undefined;
  /** `offline`, `microsoft`, ...; a custom auth function is treated like an unknown method. */
  auth?: string | ((...args: never[]) => unknown) | undefined;
}

/** Endpoint given directly instead of through `endpoint.json`. */
export interface EndpointInfo {
  /** WebSocket URL; `/bridge/v1` is appended when the URL has no path. */
  url: string;
  /** base64url local token from `endpoint.json`, for the `local` method. */
  localToken?: string;
  /** Mod fingerprint; selects the stored device credential. */
  fingerprint?: string;
}

/** Reconnect policy. */
export interface ReconnectOptions {
  /** First delay ceiling in ms. Default 250. */
  initialDelayMs?: number;
  /** Largest delay ceiling in ms. Default 30000. */
  maxDelayMs?: number;
  /** Attempts before giving up. Default: forever. */
  maxAttempts?: number;
  /**
   * How long a connection must stay up before the delay starts over at `initialDelayMs`, in ms.
   * Default 10000. A mod that accepts the connection and drops it again sooner is retried with a
   * growing delay, like one that refuses it.
   */
  stableMs?: number;
}

/** A stored device credential for one mod fingerprint. */
export interface DeviceCredential {
  deviceId: string;
  /** base64url secret issued by `pair.result`. */
  secret: string;
  /**
   * TLS endpoint of a remotely paired mod, `wss://<host>:<port>`, so later runs find it from the
   * credential alone. Absent for credentials of local mods.
   */
  endpoint?: string;
  /** When the credential was issued, in ms since the Unix epoch. */
  pairedAt?: number;
}

/** A {@link DeviceCredential} together with the fingerprint it is stored under. */
export interface StoredDevice extends DeviceCredential {
  /** Canonical mod certificate fingerprint, `sha256:<64 lowercase hex digits>`. */
  fingerprint: string;
}

/**
 * Credential persistence. The file implementation is the default; keychains can implement this later.
 * Fingerprints passed in are canonical (`sha256:<hex>`).
 */
export interface CredentialStore {
  /** Returns the stable device id, creating and saving one on first use. */
  deviceId(): Promise<string>;
  /** The device credential for a mod fingerprint, if paired. */
  device(fingerprint: string): Promise<DeviceCredential | undefined>;
  /** Saves a device credential for a mod fingerprint, replacing any earlier one. */
  saveDevice(fingerprint: string, credential: DeviceCredential): Promise<void>;
  /**
   * Every stored credential. Optional: without it the plugin cannot find a remote mod from the
   * endpoint URL alone, and needs `pair` or an endpoint with a `fingerprint`.
   */
  list?(): Promise<StoredDevice[]>;
  /** Deletes the credential of a fingerprint; resolves `false` when there was none. Optional. */
  forget?(fingerprint: string): Promise<boolean>;
}

/** What a successful pairing stored, from {@link HubEvents.paired} and {@link pairDevice}. */
export interface PairedDevice {
  /** Device id the mod registered. */
  readonly deviceId: string;
  /** Full canonical fingerprint now pinned for the endpoint, `sha256:<hex>`. */
  readonly fingerprint: string;
  /** TLS endpoint, `wss://<host>:<port>`. */
  readonly endpoint: string;
}

/**
 * Effective limits the mod advertised in `welcome`. Before the first `welcome` they hold the
 * default quotas.
 *
 * The mod also caps single objects and answers `quota_exceeded` beyond: 4096 characters of text;
 * 16384 path points; 4096 route nodes, zone boxes or corners; 1024 HUD rows and sparkline samples;
 * 1024 screen items and 32 tabs; 2048 container slots; 1024 settings per group and variables per
 * scope; 64 form fields; 256 choices, options, world menu entries and extra form buttons. A
 * notification is cut to 4096 characters and 20 lines, and a session's object data to 16 MiB.
 */
export interface BridgeLimits {
  /** Largest frame, in bytes. */
  readonly maxFrameBytes: number;
  /** Declared objects of this device, counted over every session on the hub. */
  readonly maxObjects: number;
  /** Payload of one blob, in bytes. */
  readonly maxBlobBytes: number;
  /** Blob bytes in flight per session, in bytes. */
  readonly blobInFlightBytes: number;
  /** How long the mod keeps a dropped session, in ms. */
  readonly resumeWindowMs: number;
  /** Idle interval before a `ping`, in ms. */
  readonly heartbeatMs: number;
  /** Displays of any `core:display.*` kind. */
  readonly maxDisplays: number;
  /** Entity displays, counted within {@link maxDisplays}. */
  readonly maxEntityDisplays: number;
  /** Particle emitters. */
  readonly maxEmitters: number;
  /** Sound emitters. */
  readonly maxSoundEmitters: number;
  /** Interactive elements. */
  readonly maxInteractive: number;
  /** Effects per second of one session: sounds, particles, titles and action bars count together, and more are dropped. */
  readonly effectsPerSecond: number;
}

/** A declared object as persisted by a {@link BridgeStore}. */
export interface PersistedObject {
  /** Key relative to the owner: `<extension id>/<local id>`. */
  key: string;
  kind: string;
  layer?: string;
  scope: Scope;
  world?: WorldKey;
  /** base64url of the encoded kind data. */
  data: string;
  /** Traits as declared, with owner-relative action keys. */
  traits?: ObjectTraits;
}

/** Everything persisted for one owner. */
export interface PersistedOwner {
  objects: PersistedObject[];
  /** Extension key-value stores by extension id. */
  values: Record<string, Record<string, unknown>>;
}

/** Bot-side persistence. */
export interface BridgeStore {
  load(owner: string): Promise<PersistedOwner | undefined>;
  save(owner: string, state: PersistedOwner): Promise<void>;
}

/**
 * Where one link of a hub connects: one player's mod. The fields mean what the same fields of
 * {@link HubOptions} mean; `credentials`, `reconnect`, `remoteFallback` and `allowInsecure` default
 * to the hub's.
 */
export interface LinkOptions {
  /** Id of the link and of its {@link BridgePlayer}, unique in the hub. Default: `link-<n>`. */
  id?: string;
  endpoint?: string | EndpointInfo | undefined;
  pair?: string | undefined;
  endpointFile?: string | undefined;
  remoteFallback?: boolean | undefined;
  allowInsecure?: boolean | undefined;
  credentials?: string | CredentialStore | undefined;
  deviceName?: string;
  reconnect?: ReconnectOptions | false;
}

/** One link of a hub, as returned by {@link BridgeHub.link}. */
export interface BridgeLink {
  /** The link id, also the id of the {@link BridgePlayer} each session has for it. */
  readonly id: string;
  /** True while this link is authenticated. */
  readonly connected: boolean;
  /** Events of this link only. Returns an unsubscribe function. */
  on<E extends keyof HubEvents>(event: E, fn: (...args: HubEvents[E]) => void): () => void;
  /**
   * Ends every session on this link (`session.close`, so that mod drops their objects), says goodbye
   * and stops reconnecting. The other links are not affected.
   */
  close(): Promise<void>;
}

/**
 * One linked player as one session sees it: what that player's mod reported for this bot or
 * controller. The singular {@link BridgeState} combines them.
 */
export interface BridgePlayer {
  /** Id of the hub link this player is on. */
  readonly id: string;
  /** This session is open on that link. */
  readonly connected: boolean;
  /** That player has this bot attached. */
  readonly attached: boolean;
  /** That player is in this bot's world. */
  readonly worldMatched: boolean;
  /** That player's world, or `null` on the title screen or when unknown. */
  readonly playerWorld: WorldKey | null;
  /**
   * That player's profile name (their Minecraft username), so a bot knows who gave an order, such as
   * the player to walk to. `undefined` until the mod reported it.
   */
  readonly name: string | undefined;
  /**
   * That player's profile UUID, lowercase with dashes, as mineflayer has it in `bot.players[name].uuid`; it
   * stays the same when the player changes their name. `undefined` until the mod reported it.
   */
  readonly uuid: string | undefined;
  /** This bot is that player's focused bot. */
  readonly focused: boolean;
  /** That player is in a world, not on the title screen. */
  readonly inWorld: boolean;
  /** False while that player's death screen shows. */
  readonly alive: boolean;
  /** That player gave no input for the time set in their mod (5 minutes by default). */
  readonly away: boolean;
  /** That player's game window has focus. */
  readonly windowFocused: boolean;
  /** Level of detail that player's mod asks for. */
  readonly detail: Detail;
  /** Capabilities that player's mod advertised. */
  readonly capabilities: readonly ExtensionRef[];
  /** Permissions that player granted this device. */
  readonly permissions: ReadonlySet<string>;
  /**
   * Runs `fn` acting for this player: notifications, chat lines, prompts, picks, forms, world menus
   * and screen or view opens made in it, including after `await`, go to this player only.
   */
  run<T>(fn: () => T): T;
}

/** Options shared by {@link bridge} and {@link createBridge}. */
export interface HubOptions {
  /**
   * More players' mods to link, each with its own connection. Every bot and controller of the hub
   * then has one session per link: declared state goes to all of them, and input from any of them
   * reaches the same handlers, tagged with its {@link BridgePlayer}.
   *
   * Without `links` the hub has one link, `default`, from the top-level connection options. With
   * `links` the top-level options make a `default` link first only when one of `endpoint`,
   * `endpointFile` or `pair` is given.
   */
  links?: readonly LinkOptions[];
  /**
   * Mod endpoint as a URL or {@link EndpointInfo}. Default: `MINEFLAYER_BRIDGE_ENDPOINT` (URL or file
   * path), then `<minecraft dir>/config/mineflayer_bridge/endpoint.json`. When that file is missing
   * the attempt fails and is retried, unless `remoteFallback` is set. The link logs at info level
   * which endpoint it chose and why.
   *
   * A `wss://` endpoint is pinned instead of CA-validated: to `fingerprint` when given, otherwise
   * to the fingerprint stored when it was paired. Without either the connection fails with
   * `unauthorized`. A plain `ws://` endpoint is accepted only on this machine (`localhost`,
   * `127.0.0.0/8`, `::1`) unless `allowInsecure` is set.
   */
  endpoint?: string | EndpointInfo | undefined;
  /**
   * When no endpoint is configured (`endpoint`, `endpointFile`, `pair` and the environment variables
   * are all unset) and the local `endpoint.json` cannot be read, connect to the most recently paired
   * remote mod in the credential store instead, pinned to its stored fingerprint. Default `false`:
   * the attempt fails with `disconnected` and is retried, so a bot meant for the mod on this machine
   * never ends up in another player's game because that game is not running yet.
   */
  remoteFallback?: boolean | undefined;
  /**
   * Allows a plain `ws://` endpoint on a host other than this machine. Default `false`: such an
   * endpoint is refused with `unauthorized` and not retried, because the handshake and
   * everything the bot and the player exchange would cross the network unencrypted, to a server
   * whose identity nothing checks.
   * Pair with the mod's `mfb://` URI to get `wss://` with a pinned certificate instead; set this only
   * on a network you trust, such as a container network or an SSH tunnel that does not end on loopback.
   */
  allowInsecure?: boolean | undefined;
  /**
   * Pairing URI shown by the mod, `mfb://<host>:<port>?fp=<prefix>&code=<code>`. Default:
   * `MINEFLAYER_BRIDGE_PAIR`. Used only while no credential is stored for that endpoint, so keeping
   * it in a config after the first run is harmless: later runs authenticate with the stored device
   * secret and the pinned full fingerprint.
   */
  pair?: string | undefined;
  /** Path of an `endpoint.json` to read on every connection attempt. */
  endpointFile?: string | undefined;
  /** Credential file path or store. Default: `<OS config dir>/mineflayer-bridge/credentials.json`. */
  credentials?: string | CredentialStore | undefined;
  /** Device name shown in the approval toast. Default: the host name. */
  deviceName?: string;
  /** Extensions loaded into every session of this hub. */
  extensions?: readonly ExtensionUse[];
  /** Logger. Default: warnings and errors to the console. */
  logger?: Logger;
  /** Reconnect policy, or `false` to stay disconnected after the first failure. */
  reconnect?: ReconnectOptions | false;
  /** Persistence for `persistent` objects and extension stores: a store or a JSON file path. Default: none. */
  store?: BridgeStore | string;
  /** What every session of the hub does when the mod refuses a pick, prompt, form or world menu. */
  surfaces?: SurfaceOptions;
}

/** Options for {@link bridge}: hub options plus per-bot options. */
export interface BridgeOptions extends HubOptions {
  /** Display name. Default: `bot.username`. */
  name?: string;
  /** Send `tick_end` after every physics tick, like the exported `tickEnd` plugin. Default: true. */
  tickEnd?: boolean;
  /**
   * The server part of the bot's identity, instead of the address it joined. A bot that follows its
   * player from server to server passes one name for all of them, so the mod knows it as one bot.
   * No `/`. Ignored for a Microsoft account, whose UUID is its identity everywhere.
   */
  identityServer?: string;
}

/** Per-bot options for {@link BridgeHub.plugin}. */
export interface BotPluginOptions {
  /** Display name. Default: `bot.username`. */
  name?: string;
  /** Extensions loaded in addition to the hub's. */
  extensions?: readonly ExtensionUse[];
  /** Send `tick_end` after every physics tick, like the exported `tickEnd` plugin. Default: true. */
  tickEnd?: boolean;
  /**
   * The server part of the bot's identity, instead of the address it joined. A bot that follows its
   * player from server to server passes one name for all of them, so the mod knows it as one bot.
   * No `/`. Ignored for a Microsoft account, whose UUID is its identity everywhere.
   */
  identityServer?: string;
}

/** Options for {@link BridgeHub.controller}. */
export interface ControllerOptions {
  /** Controller name; part of its owner key `ctl:<device>/<name>`. Must not contain `/`. */
  name: string;
  extensions?: readonly ExtensionUse[];
}

/** A mineflayer plugin function, as returned by {@link bridge} and {@link BridgeHub.plugin}. */
export type BridgePlugin = (bot: BotLike, botOptions?: BotOptionsLike) => void;

/** Connection-level hub shared by all sessions of a process. */
export interface BridgeHub {
  /** A mineflayer plugin that opens one bot session on this hub's connection. */
  plugin(options?: BotPluginOptions): BridgePlugin;
  /** Opens a controller session (not a bot). */
  controller(options: ControllerOptions): BridgeController;
  /** True while any link is authenticated. */
  readonly connected: boolean;
  /** The links of this hub, in order; the first is the primary one. */
  readonly links: readonly BridgeLink[];
  /**
   * Links one more player's mod. Every existing and later session gets a session on it.
   * @throws BridgeError `invalid_message` when the id is already used.
   */
  link(options: LinkOptions): BridgeLink;
  /** Connection events of every link. Returns an unsubscribe function. */
  on<E extends keyof HubEvents>(event: E, fn: (...args: HubEvents[E]) => void): () => void;
  /** Closes every session, says goodbye and stops reconnecting. */
  close(): Promise<void>;
}

/** Events of {@link BridgeHub}. */
export interface HubEvents {
  /** Authenticated and welcomed by the mod. */
  connected: [];
  /** The connection closed; `retry` says whether a reconnect is scheduled. */
  disconnected: [{ reason: string; retry: boolean }];
  /**
   * A connection problem, as a `BridgeError`: an unreadable endpoint file, a refused credential
   * (`unauthorized`), a credential that could not be stored. While nobody listens on the hub or the
   * link, these are logged through the logger at error level.
   */
  error: [Error];
  /** Pairing succeeded and the credential was stored. */
  paired: [PairedDevice];
  /** The granted permission set changed, from `welcome` or a `permissions` push. */
  permissions: [ReadonlySet<string>];
}

/** Style fields. Omitted fields take the schema defaults. */
export interface StyleOptions {
  /** Line colour. Default white. */
  color?: ColorLike;
  /** Fill alpha, 0 to 1; 0 means no fill. */
  fill?: number;
  /** Line width in pixels. */
  lineWidth?: number;
  /** How the shape draws against blocks in front of it. Default `test`. */
  depth?: DepthMode;
  /** Alpha multiplier for parts behind blocks in `both` mode, 0 to 1. */
  xrayAlpha?: number;
  /** Pulses the alpha to draw attention. Default false. */
  pulse?: boolean;
}

/** Fields common to every world-space shape, plus the traits every object takes. */
export interface ShapeOptions extends TraitOptions {
  /**
   * A style id declared with `style()`, a namespaced global style such as `core:default`, or inline
   * style fields for a one-off shape.
   */
  style?: string | StyleOptions;
  /**
   * Shorthand for the inline style colour: alone it is `style: { color }`, with inline style fields
   * it fills `style.color` when that is missing. It cannot be combined with a style id.
   */
  color?: ColorLike;
  /** The mod removes the shape this many ms after it receives the latest write. */
  ttlMs?: number;
  /** Persist through the plugin store and re-declare on start. Ignored with `ttlMs`. */
  persistent?: boolean;
  /**
   * World binding. Default: the bot's current world when known (`scope: world`), otherwise global.
   * `'global'` renders in whatever world the player is in.
   */
  world?: WorldKey | 'global';
}

/** Options of `layer.box()`. */
export interface BoxOptions extends ShapeOptions {}

/** Options of `layer.line()`. */
export interface LineOptions extends ShapeOptions {
  /** Draws an arrow head at the `to` end. Default false. */
  arrow?: boolean;
}

/** Options of `layer.path()`. */
export interface PathOptions extends ShapeOptions {
  /** One class per segment (`points.length - 1` entries), mapped to styles by `classStyles`. */
  classes?: readonly number[] | Uint16Array;
  /** Style id per class. */
  classStyles?: Readonly<Record<number, string>>;
}

/** Options of `layer.label()`. */
export interface LabelOptions extends ShapeOptions {
  /** Text scale. Default 1. */
  scale?: number;
  /** Background colour behind the text. Default 25% black. */
  background?: ColorLike;
  /** Hidden beyond this distance in blocks. Default 64. */
  maxDistance?: number;
}

/** Options of `layer.entityTag()`. */
export interface EntityTagOptions extends ShapeOptions {
  /** Text shown above the entity. */
  text?: string;
  /** Outline colour drawn around the entity. */
  outline?: ColorLike;
  /** Draws a line from the player's crosshair to the entity, through walls. Default false. */
  tracer?: boolean;
}

/** Options of `layer.waypoint()`. */
export interface WaypointOptions extends ShapeOptions {
  /** Draws a vertical beam at the waypoint. Default false. */
  beam?: boolean;
  /** Pins the marker to the screen edge while the waypoint is off screen. Default true. */
  edgePin?: boolean;
  /** Draws a line from the player's crosshair to the waypoint, through walls. Default false. */
  tracer?: boolean;
}

/**
 * Handle of a box, line, path, label, entity tag or waypoint. `update` changes some fields: the
 * geometry under its wire names and any option; `null` restores a default. `D` is the kind's wire
 * data, as edit handlers see it.
 */
export interface ShapeHandle<U, D> extends LayerObjectHandle<D> {
  /** @throws BridgeError `invalid_message` for a field that does not fit; nothing changes then. */
  update(patch: U): void;
}

/** Update of a box. */
export type BoxUpdate = Patch<BoxOptions> & { min?: Vec3Like; size?: Vec3Like | number };
/** Update of a line; `from` and `to` are the two ends. */
export type LineUpdate = Patch<LineOptions> & { from?: Vec3Like; to?: Vec3Like };
/** Update of a path. */
export type PathUpdate = Patch<PathOptions> & { points?: readonly Vec3Like[] | Float64Array };
/** Update of a label. */
export type LabelUpdate = Patch<LabelOptions> & { pos?: Vec3Like; text?: string };
/** Update of an entity tag. */
export type EntityTagUpdate = Patch<EntityTagOptions> & { entity?: number | { readonly id: number } };
/** Update of a waypoint. */
export type WaypointUpdate = Patch<WaypointOptions> & { pos?: Vec3Like; name?: string };

export type BoxHandle = ShapeHandle<BoxUpdate, KindPayloads['core:box']>;
export type LineHandle = ShapeHandle<LineUpdate, KindPayloads['core:line']>;
export type PathHandle = ShapeHandle<PathUpdate, KindPayloads['core:path']>;
export type LabelHandle = ShapeHandle<LabelUpdate, KindPayloads['core:label']>;
export type EntityTagHandle = ShapeHandle<EntityTagUpdate, KindPayloads['core:entity-tag']>;
export type WaypointHandle = ShapeHandle<WaypointUpdate, KindPayloads['core:waypoint']>;

/** Layer declaration options. */
export interface LayerOptions {
  /** Category for bulk toggles, such as `debug`, `status` or `planning`. */
  category?: string;
  /** Visibility before the player toggles it. Default true. */
  defaultVisible?: boolean;
}

/**
 * A named group of shapes owned by one extension scope. Writing an id again replaces the shape;
 * when the kind is the same, the same handle is returned with all options replaced. Writes in the
 * same tick go out in one batch. Shape methods throw `BridgeError` `invalid_message` for an option
 * that does not fit.
 */
export interface Layer extends DisplayFactory, RetainedFactory, SpatialFactory {
  readonly name: string;
  /** Axis-aligned box from `min` with `size` (blocks; a number means a cube). */
  box(id: string, min: Vec3Like, size: Vec3Like | number, options?: BoxOptions): BoxHandle;
  /** Line from `a` to `b`. */
  line(id: string, a: Vec3Like, b: Vec3Like, options?: LineOptions): LineHandle;
  /** Polyline through `points` (or flat `x, y, z` triples). */
  path(id: string, points: readonly Vec3Like[] | Float64Array, options?: PathOptions): PathHandle;
  /** Billboard text at `pos`; the text may hold variable templates. */
  label(id: string, pos: Vec3Like, text: string, options?: LabelOptions): LabelHandle;
  /** Outline and tag that follow an entity on the client. Accepts an entity or its numeric id. */
  entityTag(id: string, entity: number | { readonly id: number }, options?: EntityTagOptions): EntityTagHandle;
  /** Named marker with optional beam and screen-edge pin. */
  waypoint(id: string, pos: Vec3Like, name: string, options?: WaypointOptions): WaypointHandle;
  /** True when a shape with this id is declared. */
  has(id: string): boolean;
  /** Removes one shape; a group goes with all its children. Its handle becomes inert. */
  remove(id: string): void;
  /** Removes every shape in the layer with one prefix removal. The layer itself stays declared; handles become inert. */
  clear(): void;
  /** True when connected, attached, world-matched and the player has the layer visible. */
  isVisible(): boolean;
}

/** Options for {@link ObjectsApi.put}. */
export interface PutOptions {
  layer?: string;
  ttlMs?: number;
  persistent?: boolean;
  world?: WorldKey | 'global';
}

/** Low-level object access under the scope's `<owner>/<extension>/` prefix. */
export interface ObjectsApi {
  /** Declares a core-kind object at a local id. */
  put<K extends KindName>(localId: string, kind: K, data: KindPayloads[K], options?: PutOptions): void;
  /** Declares an object of an extension kind with pre-encoded data. */
  put(localId: string, kind: string, data: Uint8Array, options?: PutOptions): void;
  remove(localId: string): void;
  /** Removes every local id starting with `prefix` with one prefix removal. */
  removePrefix(prefix: string): void;
  has(localId: string): boolean;
  /** Local ids currently declared in this scope. */
  keys(): string[];
}

/** A shared object as received from the mod. */
export interface SharedObject {
  readonly key: string;
  readonly kind: string;
  readonly rev: number;
  readonly layer: string | undefined;
  readonly scope: Scope;
  readonly world: WorldKey | undefined;
  /** Decoded data for core kinds, otherwise raw bytes. */
  readonly data: unknown;
  readonly raw: Uint8Array;
}

/** Filter for {@link SharedApi.subscribe}. Empty or omitted lists mean everything. */
export interface SharedFilter {
  kinds?: readonly string[];
  extensions?: readonly string[];
}

/** Options for {@link SharedApi.create}. */
export interface SharedCreateOptions {
  layer?: string;
  /** Bind to a world; omitted means global. */
  world?: WorldKey;
  /** Request timeout in ms, up to the schema's 10000. */
  timeoutMs?: number;
}

/**
 * Shared objects: a live view of what the mod broadcasts to this session, plus revision-checked
 * operations. Ops reject with `BridgeError` code `conflict` (with `details.rev`) when `expectRev`
 * is stale, `timeout`, `disconnected`, or `cancelled` on unload.
 */
export interface SharedApi {
  /** Adds to this session's subscription. The view fills as the mod sends matching objects. */
  subscribe(filter?: SharedFilter): void;
  get(key: string): SharedObject | undefined;
  /** Objects in the view, optionally only those whose key starts with `prefix` or of one kind. */
  list(filter?: { prefix?: string; kind?: string }): SharedObject[];
  /**
   * Creates a shared object. A key starting with `shared/` is used as is; otherwise it becomes
   * `shared/<scope>/<key>`. Resolves with the key and revision (1).
   */
  create<K extends KindName>(key: string, kind: K, data: KindPayloads[K], options?: SharedCreateOptions): Promise<{ key: string; rev: number }>;
  create(key: string, kind: string, data: Uint8Array, options?: SharedCreateOptions): Promise<{ key: string; rev: number }>;
  /** Replaces the data of a shared object if it is still at `expectRev`. Resolves with the new revision. */
  update<K extends KindName>(key: string, kind: K, data: KindPayloads[K], expectRev: number, options?: { timeoutMs?: number }): Promise<{ key: string; rev: number }>;
  update(key: string, kind: string, data: Uint8Array, expectRev: number, options?: { timeoutMs?: number }): Promise<{ key: string; rev: number }>;
  /** Removes a shared object if it is still at `expectRev`. */
  remove(key: string, expectRev: number, options?: { timeoutMs?: number }): Promise<void>;
  /** View change events. Returns an unsubscribe function. */
  on(event: 'change', fn: (object: SharedObject) => void): () => void;
  on(event: 'remove', fn: (key: string) => void): () => void;
  /** Shared zones in the bot's world; the first call subscribes. */
  readonly zones: SharedZones;
  /** Shared points in the bot's world. */
  readonly points: SharedPoints;
  /** Shared routes in the bot's world. */
  readonly routes: SharedRoutes;
}

/** Level of a notification; it picks the colour and icon. A mod may know more levels. */
export type NotifyLevel = 'info' | 'success' | 'warn' | 'error' | (string & {});

/** Options for `notify`. */
export interface NotifyOptions {
  /** Default `info`. */
  level?: NotifyLevel;
  /** Icon id, such as an item id. */
  icon?: string;
  /**
   * Up to 4 buttons after the text, such as *Dump* and *Ignore*. A notification with buttons always
   * shows in chat, where they can be clicked, even when the player has notifications as toasts. A
   * button works while the session lasts, for the last 32 notifications.
   */
  buttons?: readonly NotifyButton[];
  /** Sound event played with it, such as `block.note_block.bell`. */
  sound?: string;
}

/** A button on a notification: it runs code, or puts a line into the chat box. */
export type NotifyButton = NotifyRunButton | NotifySuggestButton;

/** A notification button that runs code when clicked. */
export interface NotifyRunButton {
  /** 1 to 32 characters. */
  label: string;
  /** Runs when the player clicks it; returns like an action, so a string shows as the reply. */
  run(ctx: InvocationContext): InvocationReturn;
}

/** A notification button that puts a line into the chat box, for the player to change or send. */
export interface NotifySuggestButton {
  /** 1 to 32 characters. */
  label: string;
  /** The line, 1 to 256 characters, such as `@Miner mine stone 64`. */
  suggest: string;
}

/** Live session state, read through `bot.bridge.state`. */
export interface BridgeState {
  /** The session is open on a live connection. */
  readonly connected: boolean;
  /** The player has this bot attached. */
  readonly attached: boolean;
  /** The player is in this bot's world. */
  readonly worldMatched: boolean;
  /** The player's world, or `null` on the title screen or when unknown. */
  readonly playerWorld: WorldKey | null;
  /** Mod capabilities from `welcome`. */
  readonly capabilities: readonly ExtensionRef[];
  /** A newer session took over this identity; writes are no longer sent. */
  readonly superseded: boolean;
  /**
   * Level of detail from the latest `interest`; `full` before the first. While it is `reduced`,
   * extensions should drop expensive optional data.
   */
  readonly detail: Detail;
}

/** One whole WebSocket frame (envelope header and payload) as the plugin sent or received it. */
export interface RawFrame {
  /** `in`: from the mod; `out`: from the plugin. */
  readonly direction: 'in' | 'out';
  /** When the frame passed through the plugin, in ms since the Unix epoch. */
  readonly atMs: number;
  /** The frame bytes. Owned by the listener's caller; copy before keeping them past the call. */
  readonly bytes: Uint8Array;
}

/** What {@link SamplesApi.request} asks the player's client to share. */
export interface SampleRequestOptions {
  /**
   * Samples per second, more than 0 and at most 20. Default 4. The mod caps it further at the rate
   * the player allows.
   */
  rateHz?: number;
  /** Player position, yaw and pitch. Needs the `client.sample.position` permission. Default false. */
  position?: boolean;
  /** Look target: the block or entity under the crosshair. Needs `client.sample.target`. Default false. */
  target?: boolean;
  /** The item in the player's main hand. Needs `client.sample.inventory`. Default false. */
  heldItem?: boolean;
}

/**
 * One `client.sample` from the player's client. Only fields that were requested and that the player
 * granted are present. Position is in blocks (world space), yaw and pitch in degrees as vanilla
 * reports them.
 */
export type ClientSampleData = Readonly<ClientSample>;

/**
 * Opt-in samples of the player's client. Each scope (`bot.bridge` and every extension) holds its
 * own request; the session sends the union (each field requested by any scope, the highest rate) as
 * one latest-wins `sample.request`, and sends it again after every reconnect. The mod sends samples
 * only while the bot is attached and its world matches, and leaves out every field whose permission
 * is not granted, so a request never fails for lack of permission. An extension's request and
 * listeners end when it unloads.
 */
export interface SamplesApi {
  /**
   * Replaces this scope's request. A request with no field set is the same as {@link stop}.
   * @throws BridgeError `invalid_message` when `rateHz` is not a finite number in (0, 20].
   */
  request(options: SampleRequestOptions): void;
  /** Withdraws this scope's request. When no scope requests anything, the mod stops sampling. */
  stop(): void;
  /** This scope's current request, or `undefined` when it has none. */
  readonly current: Readonly<Required<SampleRequestOptions>> | undefined;
  /** The newest sample of the session, or `undefined` before the first and after a disconnect or detach. */
  readonly latest: ClientSampleData | undefined;
  /** Calls `fn` for every sample. Returns an unsubscribe function. */
  on(event: 'sample', fn: (sample: ClientSampleData) => void): () => void;
}

/** A variable value. */
export type VarValue = string | number | boolean;

/**
 * The variables of one scope, declared as one `core:vars` object at `<scope>/~vars`. Text in HUD
 * widgets, labels, boss bars and text displays of the same scope may reference them as `{name}` or
 * `{name:format}`; the mod renders the templates, so one variable change updates every text that
 * uses it. Changes made in one tick go out as one patch.
 *
 * Names are 1 to 64 characters of letters, digits, `_`, `.` and `-`. Formats: `int` (rounded),
 * `1f` and `2f` (fixed decimals), `pct` (a fraction as a rounded percentage, `0.42` is `42%`).
 * `{{` and `}}` are literal braces; an unknown name is shown as written.
 */
export interface VarsApi {
  /** @throws BridgeError `invalid_message` for a bad name or a value that is not a string, finite number or boolean. */
  set(name: string, value: VarValue): void;
  /** Sets several variables in one write. Same errors as {@link set}; nothing changes on error. */
  setMany(values: Readonly<Record<string, VarValue>>): void;
  get(name: string): VarValue | undefined;
  /** Removes a variable; returns false when it was not set. */
  delete(name: string): boolean;
  /** Every variable of the scope. */
  all(): Record<string, VarValue>;
}

/** Members shared by sessions and extension contexts: variables, focus and transactions. */
export interface ScopeApi {
  /** Variables of this scope. */
  readonly vars: VarsApi;
  /**
   * This bot is the player's focused bot (with several players: of any of them), from the latest
   * `interest`; false before the first and while disconnected. The mod keeps one focused bot per
   * player; the player changes it with a key, from Bridge home or by clicking a bot.
   */
  readonly focused: boolean;
  /**
   * Asks the player to focus this bot (`focus.request`, latest wins). The player may refuse.
   * @throws BridgeError `invalid_message` when `reason` is not a string.
   */
  requestFocus(reason?: string): void;
  /**
   * Runs `fn` and sends every declared-state write of the session made while it runs (sync or until
   * its promise settles) together, between `obj.begin` and `obj.commit`: the mod shows none of them
   * before all of them. Returns what `fn` returns.
   *
   * - Writes of other code in the session made while the transaction is open join it.
   * - A transaction started inside another joins the outer one; only the outermost sends.
   * - When `fn` throws or rejects, the writes it made are still sent, as one transaction, and the
   *   error is rethrown. Nothing is rolled back. A transaction without writes sends nothing.
   */
  transaction<T>(fn: () => T): T;
}

/** Events of {@link BotBridge} and {@link BridgeController}. */
export interface BridgeEvents {
  connected: [];
  disconnected: [];
  attached: [];
  detached: [];
  worldMatched: [];
  worldUnmatched: [];
  interest: [Interest];
  /** {@link ScopeApi.focused} changed. */
  focus: [focused: boolean];
  superseded: [];
  /** The granted permission set changed; carries the full new set. */
  permissions: [ReadonlySet<string>];
  /**
   * Something failed that no caller could catch: a handler threw or rejected (a keybind, a tool, an
   * observing intercept, a completion function, a chat, channel, view or screen listener, an edit
   * handler, a `whenVisible` callback), an extension failed to set up or needs a capability the mod
   * lacks, or the mod refused to open the session. Always a `BridgeError`. While nobody listens,
   * these are logged through the logger at error level instead of being lost.
   */
  error: [Error];
  /**
   * The mod refused to open a surface: the player dismissed this bot's last one a moment ago
   * (`cooldown`) or too many requests wait (`quota_exceeded`). Fired for every refusal, whatever the
   * call then does about it, so a controller can log it or ask another way. For a view or screen the
   * event is all there is: `open()` returns nothing to reject.
   */
  refused: [refusal: SurfaceRefusal];
  /** A linked player joined or left a world, died, respawned, went away, came back, or their game window gained or lost focus. */
  player: [change: PlayerChange, player: BridgePlayer];
  /** Bots only: the bot arrived in a world (it spawned, changed dimension, or a proxy moved it to another server); see {@link BotBridge.world}. */
  world: [world: WorldKey, previous: WorldKey | undefined];
}

/**
 * What changed about a linked player, for the `player` event: `joined` and `left` a world, `world`
 * (another dimension or server), `died` and `respawned`, `away` and `back` (input after being away),
 * `focus` and `blur` of the game window.
 */
export type PlayerChange = 'joined' | 'left' | 'world' | 'died' | 'respawned' | 'away' | 'back' | 'focus' | 'blur';

/** Session information exposed to extensions. */
export interface SessionInfo {
  /** Current mod session id; 0 while not open. It may change on resume. */
  readonly id: number;
  readonly kind: SessionKind;
  /** Bot identity, `undefined` for controllers and before login. */
  readonly identity: BotIdentity | undefined;
  /** Owner key prefix without the trailing slash, once known. */
  readonly owner: string | undefined;
}

/** Common API of bot and controller sessions. Input and HUD members belong to the `user:default` scope. */
export interface BridgeSession extends InputAndHud, ScopeApi {
  readonly state: BridgeState;
  readonly session: SessionInfo;
  /**
   * Resolves once the initial extensions have loaded and the session has opened for the first time.
   * Rejects with an extension load error, or with the mod's error when it refuses to open the session
   * for good (for example because the bot's identity belongs to another device). While the game is
   * not running it stays pending, so code that must not wait for the game should use the `connected`
   * event instead.
   */
  readonly ready: Promise<void>;
  /** Loads extensions (and their dependencies given in the same call) in dependency order. */
  use(...extensions: ExtensionUse[]): Promise<void>;
  /** Unloads an extension and its dependents, removing their objects. */
  unload(id: string): Promise<void>;
  /**
   * Public API returned by an extension's `setup`, or `undefined` while it is not loaded. Given the
   * extension itself (`bot.bridge.get(tasks)`) the API type is inferred; given an id, pass it as the
   * type argument.
   */
  get<A>(extension: ExtensionRefLike<A>): A | undefined;
  get<A = unknown>(id: string): A | undefined;
  has(id: string): boolean;
  /** A layer in the `user:default` scope. */
  layer(name: string, options?: LayerOptions): Layer;
  /** Declares a style in the `user:default` scope. */
  style(id: string, options: StyleOptions): void;
  /** A new, empty ghost-block volume for `layer.ghostBlocks`. */
  volume(options?: VolumeOptions): Volume;
  /** Objects in the `user:default` scope. */
  readonly objects: ObjectsApi;
  /**
   * A notification in the player's client: a chat line, or a toast when the player chose toasts in
   * the mod's settings. With `buttons` it always goes to chat. It is about now: while the session is
   * not open it is dropped rather than shown late, so wait for `ready` or `connected` first. The
   * mod shows it only while the player has the bot attached, and refuses more than 10 a second.
   */
  notify(text: string, options?: NotifyOptions): void;
  /** Sets the presence status object (latest wins). Must be MessagePack-encodable. */
  status(status: unknown): void;
  readonly shared: SharedApi;
  /** Client samples for the `user:default` scope. */
  readonly samples: SamplesApi;
  /** Client queries: entities and blocks the player's client has loaded. */
  readonly query: QueryApi;
  /** True when the mod advertises a capability, e.g. `supports('core:path@^1')`. False before welcome. */
  supports(spec: string): boolean;
  /**
   * Permissions the player granted this device, as of the latest `welcome` or `permissions` push.
   * Empty before the first `welcome`. A mod that does not report permissions is taken to grant the
   * default permissions of a local device. Requests needing a missing permission reject with
   * `BridgeError` `permission_denied`.
   */
  readonly permissions: ReadonlySet<string>;
  /** True when `permission` is in {@link permissions}. */
  can(permission: Permission): boolean;
  /**
   * Limits advertised by the mod. The plugin checks declarations against them and throws
   * `quota_exceeded` instead of sending what the mod would refuse: an object larger than a frame,
   * more than `maxObjects` objects over all sessions of the hub, more than `maxInteractive`
   * interactive ones, and the display and emitter limits.
   */
  readonly limits: BridgeLimits;
  on<E extends keyof BridgeEvents>(event: E, fn: (...args: BridgeEvents[E]) => void): () => void;
  /**
   * The players linked to this session, one per hub link, in link order. The first is the primary
   * one: shared objects, client samples, capabilities, permissions and limits come from it.
   */
  readonly players: readonly BridgePlayer[];
  /** The player on the link with this id. */
  player(id: string): BridgePlayer | undefined;
  /**
   * The player whose input is being handled: set inside command, action, intercept, screen, view and
   * menu handlers (and code they `await`), and inside {@link BridgePlayer.run}; `undefined` elsewhere.
   */
  readonly origin: BridgePlayer | undefined;
  /** Runs `fn` acting for nobody in particular: what it opens or sends goes to every linked player. */
  everyone<T>(fn: () => T): T;
  /** Closes the session (`session.close`); the mod drops its objects. */
  close(): Promise<void>;
}

/** `bot.bridge`. */
export interface BotBridge extends BridgeSession {
  readonly bot: BotLike;
  /**
   * The world the bot is in: its server (`host:port`, as it connected) and the world's own name,
   * such as `minecraft:overworld`, or `minecraft:creative` on a server behind a proxy that names its
   * worlds. `undefined` before the bot spawns. The `world` event fires when it changes.
   */
  readonly world: WorldKey | undefined;
}

/** A controller session from {@link BridgeHub.controller}. */
export interface BridgeController extends BridgeSession {
  readonly name: string;
}

/** Per-extension key-value storage, persisted through the plugin store when one is configured. */
export interface KeyValueStore {
  get(key: string): Promise<unknown>;
  set(key: string, value: unknown): Promise<void>;
  delete(key: string): Promise<void>;
}

/** A message channel between an extension's Node and mod halves. */
export interface ExtensionChannel<T> {
  readonly name: string;
  /**
   * Sends to the mod, with the message's delivery. `reliable` messages are kept in order and sent
   * again after a resume; while the session is not open up to 256 wait and the oldest are dropped
   * beyond that. `latest` keeps only the newest. `unreliable` ones are dropped while the session is
   * not open or the connection is busy.
   */
  send(payload: T): void;
  /** Calls `fn` for each message from the mod. Returns a remover. */
  on(fn: (payload: T) => void): () => void;
}

/**
 * The scope handed to an extension's `setup`. Input and HUD members declare under
 * `<owner>/<extension id>/`; unloading removes the declarations and rejects pending input requests
 * with `cancelled`.
 */
export interface ExtensionContext extends InputAndHud, ScopeApi {
  /** Extension id. */
  readonly id: string;
  /** The mineflayer bot, or `null` in a controller session. */
  readonly bot: BotLike | null;
  readonly session: SessionInfo;
  readonly state: BridgeState;
  /** A layer under `<owner>/<extension id>/`. */
  layer(name: string, options?: LayerOptions): Layer;
  /** Declares a style under `<owner>/<extension id>/`. */
  style(id: string, options: StyleOptions): void;
  /** A new, empty ghost-block volume for `layer.ghostBlocks`. */
  volume(options?: VolumeOptions): Volume;
  readonly objects: ObjectsApi;
  /** Shared objects; `create` keys default to `shared/<extension id>/`. Pending ops reject with `cancelled` on unload. */
  readonly shared: SharedApi;
  /** Client samples owned by this extension; its request is withdrawn on unload. */
  readonly samples: SamplesApi;
  /** Client queries; pending ones reject with `cancelled` on unload. */
  readonly query: QueryApi;
  notify(text: string, options?: NotifyOptions): void;
  /** Adds a listener that is removed on unload. Returns a remover. */
  on<A extends unknown[] = unknown[]>(emitter: EmitterLike, event: string, fn: (...args: A) => void): () => void;
  onConnected(fn: () => void): () => void;
  onDisconnected(fn: () => void): () => void;
  onAttached(fn: () => void): () => void;
  onDetached(fn: () => void): () => void;
  onWorldMatched(fn: () => void): () => void;
  onWorldUnmatched(fn: () => void): () => void;
  /** Runs `fn` with every `interest` the mod sends (attach, world match, detail, layer visibility, focus). */
  onInterest(fn: (interest: Interest) => void): () => void;
  /** Runs `fn` whenever `focused` changes (the session's `focus` event); removed on unload. */
  onFocus(fn: (focused: boolean) => void): () => void;
  /**
   * Observes every frame of the hub's connection, sent and received, for every session on it. `fn`
   * runs synchronously; it must be cheap and must copy `frame.bytes` to keep them. Removed on
   * unload. Returns a remover.
   */
  onFrame(fn: (frame: RawFrame) => void): () => void;
  /**
   * Runs `fn` whenever the layer becomes visible. The signal aborts and the returned cleanup runs
   * when it is hidden again or the extension unloads.
   */
  whenVisible(layer: string | Layer, fn: (signal: AbortSignal) => void | (() => void)): () => void;
  readonly store: KeyValueStore;
  /** Public API of an extension listed in `requires.extensions`, by id or by the extension itself (which infers the type). */
  require<A>(extension: ExtensionRefLike<A>): A;
  require<A = unknown>(id: string): A;
  /**
   * Public API of an extension listed in `requires.optionalExtensions`, or `undefined` while it is not
   * loaded. Ask again when needed rather than keeping the result: it may load or unload later.
   */
  optional<A>(extension: ExtensionRefLike<A>): A | undefined;
  optional<A = unknown>(id: string): A | undefined;
  /**
   * A channel for a message of the extension's mod half, listed with its codec in `messages`.
   * Sending before the mod registered the message drops it; listeners end on unload.
   * @throws BridgeError `invalid_message` when the message is not listed with a codec.
   */
  message<T>(type: MessageType<T>): ExtensionChannel<T>;
  supports(spec: string): boolean;
  /** Aborts on unload. */
  readonly signal: AbortSignal;
  readonly log: Logger;
}

/** Standard Schema v1 (standardschema.dev), the subset the plugin uses. */
export interface StandardSchemaV1<Input = unknown, Output = Input> {
  readonly '~standard': {
    readonly version: 1;
    readonly vendor: string;
    readonly validate: (value: unknown) => StandardResult<Output> | Promise<StandardResult<Output>>;
    readonly types?: { readonly input: Input; readonly output: Output } | undefined;
  };
}

/** Result of a Standard Schema validation. */
export type StandardResult<Output> =
  | { readonly value: Output; readonly issues?: undefined }
  | { readonly issues: ReadonlyArray<{ readonly message: string; readonly path?: ReadonlyArray<PropertyKey | { readonly key: PropertyKey }> | undefined }> };

/** One field of the built-in mini schema. */
export type MiniField =
  | { readonly type: 'string'; readonly default?: string; readonly enum?: readonly string[]; readonly required?: boolean }
  | { readonly type: 'number' | 'integer'; readonly default?: number; readonly min?: number; readonly max?: number; readonly required?: boolean }
  | { readonly type: 'boolean'; readonly default?: boolean; readonly required?: boolean }
  | { readonly type: 'array'; readonly default?: readonly unknown[]; readonly required?: boolean }
  | { readonly type: 'object'; readonly default?: Readonly<Record<string, unknown>>; readonly required?: boolean };

/** Built-in mini schema: field name to {@link MiniField}. */
export type MiniSchema = { readonly [name: string]: MiniField };

type MiniValue<F extends MiniField> = F extends { type: 'string'; enum: readonly (infer E)[] }
  ? E
  : F extends { type: 'string' }
    ? string
    : F extends { type: 'number' | 'integer' }
      ? number
      : F extends { type: 'boolean' }
        ? boolean
        : F extends { type: 'array' }
          ? unknown[]
          : Record<string, unknown>;

type HasValue<F> = F extends { default: unknown } ? true : F extends { required: true } ? true : false;

/** Validated options for a mini schema: fields with a default or `required` are always present. */
export type InferMini<S extends MiniSchema> = {
  [K in keyof S as HasValue<S[K]> extends true ? K : never]: MiniValue<S[K]>;
} & {
  [K in keyof S as HasValue<S[K]> extends true ? never : K]?: MiniValue<S[K]>;
};

/** Options schema accepted by {@link ExtensionDefinition.options}. */
export type OptionsSchema = StandardSchemaV1 | MiniSchema;

/** Options type produced by a schema. */
export type OptionsOf<S> = S extends StandardSchemaV1<unknown, infer O> ? O : S extends MiniSchema ? InferMini<S> : Record<string, unknown>;

/** Input type accepted by an extension factory for a schema. */
export type OptionsInputOf<S> = S extends StandardSchemaV1<infer I, unknown> ? I : S extends MiniSchema ? { [K in keyof S]?: MiniValue<S[K]> } : Record<string, unknown>;

/** Definition passed to {@link defineExtension}. */
export interface ExtensionDefinition<S extends OptionsSchema | undefined, A> {
  /** `namespace:kebab-name`. */
  id: string;
  /** Semver. */
  version: string;
  requires?: {
    /** Mod capabilities that must exist, e.g. `core:path@^1`. Loading fails with `unsupported` otherwise. */
    mod?: readonly string[];
    /** Capabilities checked at runtime with `ctx.supports()`. */
    optional?: readonly string[];
    /** Node extensions loaded first: ids, or factories that are loaded with default options when missing. */
    extensions?: readonly (string | ExtensionFactory<never, unknown>)[];
    /**
     * Node extensions used when they are loaded: listed in the same `use()` call, they load first;
     * otherwise they are not needed. Read them with `ctx.optional(id)`.
     */
    optionalExtensions?: readonly string[];
  };
  /** Options schema: any Standard Schema (zod, valibot, ...) or the built-in mini schema. */
  options?: S;
  /**
   * Object kinds of the extension's mod half to register in `hello`, e.g. `acme:radar`: names, or
   * `KindType` entries (from {@link plainKind} or the code generator), which also let `objects.put`
   * encode the data.
   */
  kinds?: readonly (string | KindType<any>)[];
  /**
   * Messages of the mod half to register in `hello`: names, or `MessageType` entries (from
   * {@link plainMessage} or the code generator) for `ctx.message`.
   */
  messages?: readonly (string | MessageType<any>)[];
  /** Runs when the extension loads. The return value is its public API. */
  setup(ctx: ExtensionContext, options: OptionsOf<S>): A | Promise<A>;
}

/** An extension with options bound, ready for `use()` or an `extensions` list. */
export interface ExtensionInstance<A = unknown> {
  readonly id: string;
  readonly version: string;
  readonly definition: ExtensionDefinition<OptionsSchema | undefined, A>;
  readonly input: unknown;
}

/** Returned by {@link defineExtension}: call it with options to get an {@link ExtensionInstance}. */
export interface ExtensionFactory<I, A> {
  (options?: I): ExtensionInstance<A>;
  readonly id: string;
  readonly version: string;
  readonly definition: ExtensionDefinition<OptionsSchema | undefined, A>;
}

/**
 * An extension as `get`, `require` and `optional` take it to infer its API type: the factory
 * {@link defineExtension} returned, or an instance of it.
 */
export type ExtensionRefLike<A> = { readonly id: string; readonly definition: ExtensionDefinition<OptionsSchema | undefined, A> };

/** What `use()` and `extensions` lists accept: an instance, or a factory used with default options. */
export type ExtensionUse = ExtensionInstance<unknown> | ExtensionFactory<never, unknown>;
