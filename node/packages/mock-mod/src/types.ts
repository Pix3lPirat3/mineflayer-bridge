import type {
  ArgType,
  BotIdentity,
  ClientSample,
  Budget,
  EnvelopeHeader,
  ExtensionRef,
  FormOpen,
  BlockHighlightData,
  GhostBlocksData,
  HeatmapData,
  KindName,
  ObjectTraits,
  KindPayloads,
  Lifetime,
  PickKind,
  PickRequest,
  PromptKind,
  PromptRequest,
  ChatSpan,
  SlotEntry,
  SlotItem,
  EffectBody,
  Scope,
  SessionKind,
  Suggestion,
  WorldKey,
  Target,
  Vec3d,
  VolumeSection,
  WorldMenuRequest,
} from '@mineflayer-bridge/protocol';

/** Time source for TTL and resume-window expiry. `now()` returns milliseconds. */
export interface Clock {
  now(): number;
}

/** Facts about a device asking to connect, given to {@link MockModOptions.approve}. */
export interface ApprovalRequest {
  readonly deviceId: string;
  readonly deviceName: string;
  readonly method: 'local' | 'device';
  readonly remoteAddress: string;
}

/** How a device entered the mock's registry. */
export type MockDeviceOrigin = 'local' | 'paired' | 'issued';

/** A device in the mock's registry, as listed in the inspector. */
export interface MockDevice {
  readonly deviceId: string;
  /** Device name from the last `hello`, empty before the first connection. */
  readonly name: string;
  /** `local` after approving a local-token device, `paired` after a pairing code, `issued` by `MockMod.issueDevice`. */
  readonly origin: MockDeviceOrigin;
  /** Granted permissions. */
  readonly permissions: readonly string[];
  /** Clock time of the last successful authentication, in ms, or `undefined` before one. */
  readonly lastSeenAt: number | undefined;
  /** Remote address of the last successful authentication. */
  readonly lastAddress: string | undefined;
}

/** A pairing code shown by `MockMod.beginPairing`. */
export interface MockPairing {
  /** `mfb://<host>:<port>?fp=<base32 prefix>&code=<code>`. */
  readonly uri: string;
  /** The 8-character single-use code. */
  readonly code: string;
  /** Clock time after which the code is refused, in ms. */
  readonly expiresAt: number;
}

/** TLS material for {@link MockModOptions.tls}, PEM or DER as Node's `tls` accepts it. */
export interface MockTlsOptions {
  readonly key: string | Buffer;
  readonly cert: string | Buffer;
}

/** Player attach policy. */
export type AttachPolicy = 'auto' | 'always' | 'manual';

/** Options for {@link createMockMod}. Every field is optional. */
export interface MockModOptions {
  /** TCP port. Default 0, which picks a free port. */
  port?: number;
  /** Bind address. Default `127.0.0.1`. */
  host?: string;
  /** base64url local token. Default: 32 random bytes, as the real mod does on every start. */
  localToken?: string;
  /**
   * Instance fingerprint written to the endpoint file and sent in `pair.result`. Default: the
   * SHA-256 fingerprint of the {@link tls} certificate, otherwise random `sha256:<hex>`.
   */
  fingerprint?: string;
  /**
   * Serves `wss://` with this key and certificate (TLS is required off
   * loopback). `MockMod.beginPairing` needs it, since a pairing code is accepted only over TLS.
   * Default: plain `ws://`.
   */
  tls?: MockTlsOptions;
  /** Lifetime of a pairing code from `MockMod.beginPairing`, in ms. Default 300000 (5 minutes). */
  pairingTtlMs?: number;
  /**
   * Initial permissions of a device the registry learns about. Default: the default grants, the
   * remote set for `paired` devices and the local set otherwise.
   */
  permissions?: (device: { readonly deviceId: string; readonly origin: MockDeviceOrigin }) => readonly string[];
  /**
   * Called the first time a `local` device id appears. Resolve `false` to deny. Default: allow.
   * The handshake waits for it, like the in-game approval toast.
   */
  approve?: (request: ApprovalRequest) => boolean | Promise<boolean>;
  /** Time source for TTL, resume expiry and surface cooldowns. Default `Date`. */
  clock?: Clock;
  /**
   * How long a bot's unasked surfaces (picks, prompts, forms, world menus, views and screens it
   * opens itself) are refused with `cooldown` after the player dismissed one, in ms: the first entry
   * after one dismissal, the next after two in a row, and so on; the last repeats. An answered
   * surface, or {@link surfaceResetMs} without a dismissal, starts the count over. `[]` switches it
   * off. Default `[5000, 15000, 30000]`.
   */
  surfaceCooldownsMs?: readonly number[];
  /** Time without a dismissal after which the dismissal count starts over, in ms. Default 60000. */
  surfaceResetMs?: number;
  /**
   * How long after the player ran a bot's command or action (or clicked in its view or screen) that
   * bot's surfaces count as asked for and skip the cooldown, in ms. Default 5000.
   */
  surfaceAskedWindowMs?: number;
  /** How long a `chat` surface prompt may hold the player's chat before it times out and counts as dismissed, in ms. Default 60000. */
  chatPromptWindowMs?: number;
  /** Sessions one device may hold on its connections; another `session.open` is refused with `quota_exceeded`. Default 256. */
  maxSessionsPerDevice?: number;
  /**
   * Whether a refused view or screen open names the object in `details.key`. `false` leaves it out,
   * as a mod older than the key does. Default true.
   */
  surfaceRefusalKey?: boolean;
  /**
   * Refuses a `session.open` with the returned error instead of opening the session; `undefined`
   * lets it through. For tests of how the plugin handles a refused open.
   */
  refuseOpen?: (open: { readonly kind: string; readonly name: string; readonly deviceId: string }) => { code: string; message?: string; retryable?: boolean } | undefined;
  /** Sends a wrong `welcome.proof`, as something that is not the mod would. Default false. */
  wrongProof?: boolean;
  /** Resume window in ms. Default 30000. */
  resumeWindowMs?: number;
  /** Heartbeat interval in ms, also advertised in `welcome`. Peers are dead after 3x. Default 5000. */
  heartbeatMs?: number;
  /** Maximum frame size in bytes. Default 1 MiB. */
  maxFrameBytes?: number;
  /** Capabilities advertised in `welcome.extensions`. Default: every core kind at `1.0.0`. */
  capabilities?: ExtensionRef[];
  /** Budget advertised in `welcome`. */
  budget?: Budget;
  /** Attach policy. Default `auto`: attach bots whose world matches the player's. */
  attachPolicy?: AttachPolicy;
  /** Filters which extension message and kind names from `hello` get registry ids. Default: all. */
  supports?: (name: string, space: 'message' | 'kind') => boolean;
  /** Interval of the TTL, resume-expiry and input-deadline sweep in ms. Default 25. */
  sweepIntervalMs?: number;
  /** Caps the deadline of forms in ms; a form's own `timeoutMs` applies when it is shorter. Default: no cap. */
  formTimeoutMs?: number;
  /**
   * Prompt and form surfaces the mock can show. A request for any
   * other surface falls back to `hud` (prompts) or `screen` (forms). Default: every surface.
   */
  surfaces?: readonly string[];
  /** Chat prefix that addresses bots. Default `@`. */
  chatPrefix?: string;
  /**
   * Quotas. A full write that would exceed one is refused with `quota_exceeded`, relating to
   * `obj.upsert`. Defaults: 20000 objects per device, and per session 1024 interactive ones, 4096
   * displays, 256 entity displays and 32 particle emitters.
   */
  quotas?: MockQuotas;
  /** Item tags by tag id, for `accepts.tags` of storage slots, e.g. `{ 'minecraft:logs': ['minecraft:oak_log'] }`. Default: none. */
  itemTags?: Readonly<Record<string, readonly string[]>>;
  /**
   * Deadline in ms for `dynamic` completions (300). After it,
   * {@link MockMod.complete} answers an empty list and the bot's late answer is dropped. Default 300.
   */
  completionTimeoutMs?: number;
  /**
   * How long a `dynamic` answer is reused before {@link MockMod.complete} asks the bot again, in
   * ms, as the real mod does. Default 1500.
   */
  completionCacheMs?: number;
  /**
   * Suggestions for completion sources the real mod resolves from the client world, by schema
   * source name (`entities`, `blocks`, `block_ids`, `item_ids`, `sounds`, `particles`,
   * `enchantments`, `players`, `zones`, `points`, `waypoints`). The mock filters them by prefix and
   * applies the source's `limit`; it does not apply `near`, `radius` or `filter`. Default: none.
   */
  completionStubs?: Readonly<Record<string, readonly (string | Suggestion)[]>>;
  /**
   * Resolves target selectors such as `@nearest[type=minecraft:zombie]` typed for `entity`,
   * `entity_list`, `block_pos` and `vec3` arguments, the way the client resolves them from its
   * world. Other arguments take the text as typed. Default: none, and a selector is a parse error.
   */
  resolveSelector?: (selector: MockSelector) => readonly MockSelectorValue[];
  /**
   * Stream window in bytes: granted with `stream.accept`, and granted again with
   * `stream.window` for each chunk received, so at most this many bytes of a stream are unreceived.
   * 0 grants nothing, which stalls every transfer. Default 1 MiB.
   */
  streamWindow?: number;
  /** Capacity of the LRU blob cache in bytes, shared by every session of this mock. Default 256 MiB. */
  blobCacheBytes?: number;
  /**
   * Directory the blob cache is persisted to. A mock created on the same directory knows the blobs and
   * answers `stream.have` for them, as the real mod does across client restarts. Default: memory only.
   */
  blobDir?: string;
}

/** Kind of a target selector. */
export type MockSelectorKind = 'me' | 'self' | 'looked' | 'nearest' | 'all';

/** A target selector the mock asks {@link MockModOptions.resolveSelector} to resolve. */
export interface MockSelector {
  /** As typed, e.g. `@nearest[type=minecraft:zombie,distance=..8]`. */
  readonly text: string;
  readonly kind: MockSelectorKind;
  /** Bracket options: `type`, `tag`, `distance`, `from` (`player` or `bot`) and `limit`. */
  readonly options: Readonly<Record<string, string>>;
  /** Owner key of the bot whose command is parsed, the bot `@self` names. */
  readonly bot: string;
  /** Full key of the `core:command`. */
  readonly command: string;
  /** Name of the argument the selector was typed for. */
  readonly arg: string;
  readonly type: ArgType;
}

/**
 * What a selector resolves to: client entity ids for `entity` and `entity_list`, positions in
 * blocks for `block_pos` (floored) and `vec3`. A single-target argument takes the first value.
 */
export type MockSelectorValue = number | { readonly x: number; readonly y: number; readonly z: number };

/** What {@link MockMod.runCommand} did with a command line. */
export type MockCommandOutcome =
  /** Parsed and invoked; `path` holds canonical subcommand names and `result` the bot's `invocation.result`. */
  | { readonly status: 'invoked'; readonly command: string; readonly path: readonly string[]; readonly args: Record<string, unknown>; readonly result: MockInvocationResult }
  /** Named a node without a handler: the mod lists its subtree as usage lines and sends nothing. */
  | { readonly status: 'usage'; readonly command: string; readonly path: readonly string[]; readonly usage: readonly string[] }
  /**
   * Did not parse, and nothing was sent. `start` and `end` are UTF-16 offsets into the line of the
   * offending text; both are the line length for a missing argument.
   */
  | { readonly status: 'error'; readonly command: string | undefined; readonly path: readonly string[]; readonly error: string; readonly start: number; readonly end: number };

/** Suggestions for the word at the end of a line. */
export interface MockCompletion {
  /** UTF-16 offset where the completed word starts; a suggestion replaces the line from here. */
  readonly start: number;
  readonly suggestions: readonly Suggestion[];
  /** Schema source of the argument suggestions, `bool` for booleans, or `undefined` for node names only. */
  readonly source: string | undefined;
  /**
   * For the `dynamic` source: `answered` by the bot, served from the per-prefix `cached` answer,
   * `timeout` after {@link MockModOptions.completionTimeoutMs}, or `failed` on an error response.
   */
  readonly dynamic: 'answered' | 'cached' | 'timeout' | 'failed' | undefined;
  /** Why an earlier word stops completion, e.g. `count: expected an integer, got x`. */
  readonly error: string | undefined;
}

/** Quotas the mock enforces: objects per device, the rest per session. */
export interface MockQuotas {
  /** Display objects of any `core:display.*` kind. */
  displays?: number;
  /** `core:display.entity` objects. */
  entityDisplays?: number;
  /** `core:particle-emitter` objects. */
  emitters?: number;
  /** `core:sound-emitter` objects. Default 16. */
  soundEmitters?: number;
  /** Objects with the interactive trait. Default 1024. */
  interactive?: number;
  /** `core:keybind`, `core:tool` and `core:action` objects of one session, each. Default 256. */
  declarations?: number;
  /** `core:command` objects of one session, a crew's orders included. Default 1024. */
  commands?: number;
  /** `effect.play` calls per second of one session (sounds, particles, titles and action bars together); more are dropped without an answer. Default 40. */
  effectsPerSecond?: number;
  /** Objects one device may hold over all of its sessions, advertised as `welcome.limits.maxObjects`. Default 20000. */
  objects?: number;
  /** `notify` messages per second of one session; more are refused with `rate_limited`. Default 10. */
  notifiesPerSecond?: number;
  /** Input requests (picks, prompts, forms, world menus) one session may have waiting. Default 16. */
  inputRequests?: number;
  /**
   * Payload bytes of one blob, advertised as `welcome.limits.maxBlobBytes`; larger offers are
   * rejected with `quota_exceeded`. Default 32 MiB. The bytes in flight are capped by
   * `budget.streamBytesInFlight`: offers beyond it wait for earlier transfers before they are accepted.
   */
  blobBytes?: number;
}

/** A blob in the mock's cache. */
export interface MockBlob {
  /** Lowercase hex SHA-256 of the payload. */
  readonly hash: string;
  /** Payload bytes. */
  readonly size: number;
}

/** A `core:ghost-blocks` object with its volume decoded from the blob. */
export interface MockGhostBlocks {
  readonly data: GhostBlocksData;
  readonly sections: readonly VolumeSection[];
  /** Number of set blocks. */
  readonly count: number;
  /** State at a volume position (before `origin` is applied), or `undefined` when unset. */
  get(pos: { readonly x: number; readonly y: number; readonly z: number }): string | undefined;
}

/** A `core:heatmap` object with its cells decoded from the blob. */
export interface MockHeatmap {
  readonly data: HeatmapData;
  /** `x, y, z` triples in blocks. */
  readonly cells: Int32Array;
  readonly values: Float32Array;
}

/** A `core:block-highlight` object with its positions, inline or from the blob. */
export interface MockHighlights {
  readonly data: BlockHighlightData;
  /** `x, y, z` triples in blocks. */
  readonly positions: Int32Array;
  /** True when the positions came from a blob. */
  readonly fromBlob: boolean;
}

/** An object held by the mock, with its data decoded when the kind is a core kind. */
export interface MockObject {
  readonly key: string;
  /** Kind name, e.g. `core:box`, or `#<id>` when the id is not in any registry. */
  readonly kind: string;
  readonly kindId: number;
  readonly rev: number;
  readonly layer: string | undefined;
  readonly lifetime: Lifetime;
  readonly scope: Scope;
  readonly world: WorldKey | undefined;
  readonly ttlMs: number | undefined;
  /** Decoded data for core kinds, otherwise the raw bytes. */
  readonly data: unknown;
  /** Encoded data exactly as stored. */
  readonly raw: Uint8Array;
  /** Object traits as last written; action keys are full keys. */
  readonly traits: ObjectTraits | undefined;
}

/** Kinds of spatial definitions. */
export type MockSpatialKind = 'core:zone' | 'core:point' | 'core:route';

/** A zone, point or route held by the mock, shared or owned by a session. */
export interface MockSpatial<K extends MockSpatialKind> {
  readonly key: string;
  /** `shared`, or the owner key of the session that declared it (`bot:...`). */
  readonly owner: string;
  readonly shared: boolean;
  readonly rev: number;
  readonly layer: string | undefined;
  readonly world: WorldKey | undefined;
  readonly data: KindPayloads[K];
}

/** The bot's answer to {@link MockMod.proposeEdit}, from `obj.edit.result`. */
export interface MockEditAnswer {
  readonly accepted: boolean;
  readonly reason: string | undefined;
}

/** A player edit of a bot-owned object waiting for the bot's answer; the real mod shows it as pending. */
export interface MockPendingEdit {
  readonly key: string;
  readonly owner: string;
  readonly baseRev: number;
  /** The data the edit would produce. */
  readonly proposed: unknown;
}

/** Options for {@link MockMod.proposeEdit}. */
export interface MockProposeOptions {
  /** Revision the edit is based on. Default: the object's current revision. */
  baseRev?: number;
  /** Raw patch fields to send instead of the diff of `patch`, for malformed proposals. */
  fields?: Uint8Array;
  /** How long to wait for the answer in ms. Default: the schema's 10000. */
  timeoutMs?: number;
  /**
   * Skips the editable check, as a misbehaving client would, so the bot's own policy answers.
   * Default false.
   */
  unchecked?: boolean;
}

/** Device credential issued by `MockMod.issueDevice`. */
export interface IssuedDevice {
  readonly deviceId: string;
  readonly secret: Uint8Array;
  /** The secret as base64url, the form stored in credential files. */
  readonly secretBase64Url: string;
  readonly fingerprint: string;
}

/** Target accepted by `MockMod.disconnect`, `MockMod.blackhole` and `MockMod.kick`: a connection id, a session match, or a session info. */
export type DropTarget = number | string | Pick<MockSessionInfo, 'connectionId'>;

/** A session as seen by the mock. */
export interface MockSessionInfo {
  /** Session id on its current connection. Changes when the session resumes on a new connection. */
  readonly id: number;
  /** Connection id of the current connection, or `null` while detached inside the resume window. */
  readonly connectionId: number | null;
  readonly kind: SessionKind;
  readonly name: string;
  readonly identity: BotIdentity | undefined;
  /** Owner, e.g. `bot:steve@localhost:25565` or `ctl:<device>/<name>`. */
  readonly owner: string;
  readonly deviceId: string;
  readonly connected: boolean;
  readonly superseded: boolean;
  readonly world: WorldKey | undefined;
  readonly position: { x: number; y: number; z: number } | undefined;
  readonly attached: boolean;
  readonly worldMatched: boolean;
  readonly status: unknown;
  readonly extensions: readonly ExtensionRef[];
  /** Highest plugin `seq` applied. */
  readonly lastApplied: number;
}

/** A decoded inbound message, emitted as `message` for test assertions. */
export interface MockInbound {
  readonly connectionId: number;
  readonly session: MockSessionInfo | undefined;
  readonly type: string;
  readonly header: EnvelopeHeader;
  readonly payload: unknown;
  /** True when the message was a reliable replay at or below the last applied seq and was ignored. */
  readonly duplicate: boolean;
}

/** Filter for {@link MockMod.setLayerVisible}. Omitted fields match anything. */
export interface LayerToggle {
  owner?: string;
  extension?: string;
  layer?: string;
  category?: string;
}

/** Events emitted by the mock. */
export interface MockModEvents {
  connection: [{ connectionId: number; remoteAddress: string }];
  authenticated: [{ connectionId: number; deviceId: string; method: string }];
  /** A pairing code was redeemed and `pair.result` sent. */
  paired: [{ connectionId: number; deviceId: string; code: string }];
  /** A request or write was refused with `permission_denied`; `key` names the refused object write. */
  permissionDenied: [{ session: MockSessionInfo; permission: string; type: string; key: string | undefined }];
  rejected: [{ connectionId: number; reason: string }];
  connectionClosed: [{ connectionId: number }];
  sessionOpened: [MockSessionInfo];
  sessionResumed: [MockSessionInfo];
  sessionClosed: [MockSessionInfo & { reason: string }];
  snapshotEnd: [{ session: MockSessionInfo; removed: string[] }];
  objectChanged: [{ key: string; object: MockObject; write: 'full' | 'patch' | 'shared' }];
  objectRemoved: [{ key: string; reason: 'remove' | 'ttl' | 'snapshot' | 'expired' | 'shared' }];
  notify: [{ session: MockSessionInfo; text: string; level: string; icon: string | undefined; buttons: MockNotifyButton[]; sound: string | undefined }];
  message: [MockInbound];
  /** An extension message from the plugin, as raw payload bytes. */
  extensionMessage: [{ session: MockSessionInfo; type: string; payload: Uint8Array }];
  protocolError: [{ connectionId: number; code: string; message: string }];
  /** An input request was queued. */
  inputRequest: [MockInputRequest];
  /** An input request left the queue. */
  inputSettled: [{ request: MockInputRequest; outcome: InputOutcome }];
  /** An input request was refused on arrival, with `not_attached` or `world_mismatch`. */
  inputRejected: [{ session: MockSessionInfo; type: string; code: string }];
  /** A container view opened, by the player (`openView`) or by its bot (`container.open`). */
  viewOpened: [{ key: string; by: 'player' | 'bot' }];
  /**
   * A container view closed: by the player (`closeView`, a `closeOnClick` click, or another view
   * opening), by its bot (`container.close`), or because its object or session went away.
   */
  viewClosed: [{ key: string; by: 'player' | 'bot' | 'removed' | 'lost' }];
  /** A declared screen opened, by the player (`openScreen`) or by its bot (`screen.open`). */
  screenOpened: [{ key: string; by: 'player' | 'bot' }];
  /** A declared screen closed, by the player (`closeScreen`) or by its bot (`screen.close`). */
  screenClosed: [{ key: string; by: 'player' | 'bot' }];
  /** A bot printed a chat line with `chat.print`. */
  chatPrinted: [MockChatLine];
  /** A bot played a client-only effect with `effect.play`. */
  effect: [MockEffect];
  /** A stream ended with a verified hash and its blob entered the cache. */
  blobStored: [{ session: MockSessionInfo; hash: string; size: number; codec: string }];
  /** An offered blob was already cached and answered with `stream.have`. */
  blobHave: [{ session: MockSessionInfo; hash: string }];
  /** The mock refused or failed a stream with `stream.reject`, or the plugin cancelled one (`by: 'plugin'`). */
  streamRejected: [{ session: MockSessionInfo; hash: string; reason: string; by: 'mock' | 'plugin' }];
  /** A `client.sample` went to a session, with the fields it was allowed to see. */
  sample: [{ session: MockSessionInfo; sample: ClientSample }];
  /** A bot sent `focus.request`; `granted` follows {@link FocusPolicy}. */
  focusRequested: [{ session: MockSessionInfo; reason: string | undefined; granted: boolean }];
  /**
   * A transaction ended: `committed` applied its `writes` buffered
   * messages at once; `discarded` dropped them because another `obj.begin` replaced it, it stayed
   * open past 10 s of clock time, or its session ended.
   */
  transaction: [{ session: MockSessionInfo; tx: number; outcome: 'committed' | 'discarded'; writes: number; reason: 'replaced' | 'timeout' | 'session_end' | undefined }];
}

/** How the simulated player answers `focus.request`: `grant` focuses the bot, `refuse` keeps the focus. */
export type FocusPolicy = 'grant' | 'refuse';

/** A session's active `sample.request`, with schema defaults applied. */
export interface MockSampleRequest {
  /** Requested rate in Hz, before the player's cap. */
  readonly rateHz: number;
  readonly position: boolean;
  readonly target: boolean;
  readonly heldItem: boolean;
}

/** How an input request left the mock's queue. */
export type InputOutcome =
  /** Answered with a result. */
  | 'resolved'
  /** Rejected with `cancelled`, as when the player presses Esc. */
  | 'cancelled'
  /** The deadline passed: a prompt answered with its default, a pick or form rejected with `timeout`. */
  | 'timeout'
  /** The bot withdrew it with `pick.cancel`, `prompt.cancel`, `form.close` or `world-menu.cancel`. */
  | 'withdrawn'
  /** Its session ended. */
  | 'dropped';

interface MockInputBase {
  /** Mock-wide id, used by the `resolve*`, `cancel` and `timeOut` methods. */
  readonly id: number;
  /** Envelope request id, the one the bot's cancel or close message names. */
  readonly req: number;
  readonly owner: string;
  readonly urgent: boolean;
  /** Clock time of arrival, in ms. */
  readonly receivedAt: number;
  /** Clock time at which the deadline passes, in ms. */
  readonly deadlineAt: number;
}

/** A pick, prompt or form waiting in the mock's input queue. */
export type MockInputRequest = MockInputBase &
  (
    | { readonly type: 'pick'; readonly kind: PickKind; readonly request: PickRequest }
    | { readonly type: 'prompt'; readonly kind: PromptKind; readonly request: PromptRequest; readonly surface: string }
    | { readonly type: 'form'; readonly kind: 'form'; readonly request: FormOpen; readonly surface: string }
    | { readonly type: 'world-menu'; readonly kind: 'world-menu'; readonly request: WorldMenuRequest }
  );

/** A declaration (command, action, keybind, tool, settings group, panel or boss bar) held by the mock. */
export interface MockDeclaration<K extends KindName> {
  /** Full object key, as events carry it. */
  readonly key: string;
  readonly owner: string;
  /** Extension scope, e.g. `user:default` or `acme:radar`. */
  readonly extension: string;
  /** Local id after the reserved directory, e.g. the command name. */
  readonly id: string;
  readonly scope: Scope;
  readonly world: WorldKey | undefined;
  readonly data: KindPayloads[K];
}

/** A widget of a HUD panel. */
export interface MockHudWidget {
  readonly key: string;
  /** Widget id within its panel. */
  readonly id: string;
  /** Kind name, e.g. `core:hud.kv`. */
  readonly kind: string;
  readonly order: number;
  /** Decoded kind data. */
  readonly data: unknown;
}

/** A HUD panel with its widgets in display order (by `order`, then id). */
export interface MockHudPanel extends MockDeclaration<'core:hud.panel'> {
  readonly widgets: readonly MockHudWidget[];
}

/** Options of {@link MockMod.toolEvent}. */
export interface MockToolEventOptions {
  /** Scroll steps for `scroll`. */
  delta?: number;
  /** Vanilla key name for `key`. */
  key?: string;
  /** Resolved target under the crosshair. */
  target?: Target;
}

/** What {@link MockMod.invokeCommand} and {@link MockMod.invokeAction} resolve with. */
export interface MockInvocationResult {
  readonly ok: boolean;
  readonly message: string | undefined;
  /** Set when the player cancelled what the handler waited on: the mod shows nothing. */
  readonly quiet?: true;
}

/** A container view held by the mock. */
export interface MockContainerView extends MockDeclaration<'core:container-view'> {
  /** True while the player's screen shows this view. */
  readonly open: boolean;
}

export interface MockScreen extends MockDeclaration<'core:screen'> {
  /** True while the player has this screen open. */
  readonly open: boolean;
}

/** The bot's `container.ack` for a gesture made with {@link MockMod.clickSlot}. */
export interface MockContainerAck {
  /** Mod-assigned id of the gesture. */
  readonly input: number;
  readonly ok: boolean;
  readonly message: string | undefined;
}

/** Options of {@link MockMod.clickSlot}. */
export interface MockClickOptions {
  /** Target hotbar slot, 0 to 8, for `number`. */
  hotbar?: number;
  /** How long to wait for the ack, in ms. Default 30000. */
  timeoutMs?: number;
}

/** Options of {@link MockMod.closeView}. */
export interface MockCloseOptions {
  /** The player confirmed closing a `requireAnswer` view; without it such a view stays open. */
  confirm?: boolean;
}

/** A move the player makes in the storage slots of the shown view. */
export interface MockStorageMove {
  /** A storage or `source` slot of the view, or `player` for a copy of a stack from the player's inventory. */
  from: number | 'player';
  /** A storage slot of the view, or `player` to put the stack back into the player's inventory. */
  to: number | 'player';
  /** The stack the player brings; required when `from` is `player`. */
  item?: SlotItem;
  /** How many items to move. Default: the whole stack. */
  count?: number;
  /** How long to wait for the ack of an `ask` slot, in ms. Default 30000. */
  timeoutMs?: number;
}

/** What {@link MockMod.storageMove} did. */
export interface MockStorageResult {
  readonly ok: boolean;
  /** Why the move was refused on the client (`locked`, `not accepted`, `full`, ...), or the bot's reason. */
  readonly message: string | undefined;
  /** The bot was asked with `container.input`, because an `ask` slot took part. */
  readonly asked: boolean;
  /** The bot rejected an `ask` move, so it was rolled back. */
  readonly rolledBack: boolean;
  /** What the storage slots hold afterwards, by slot. */
  readonly storage: readonly SlotEntry[];
}

/** Options of {@link MockMod.interact}: the player clicking a real block or entity. */
export interface MockInteraction {
  /** The resolved target, as `intercept.event` carries it. */
  target: Target;
  /** Default `secondary`. */
  input?: 'primary' | 'secondary';
  /** Registry id of the block or entity, matched against `target.ids`. */
  id?: string;
  /** Its tags, matched against `target.tags`. */
  tags?: readonly string[];
  /** Held modifier keys. */
  modifiers?: { shift?: boolean; control?: boolean; alt?: boolean };
  /** How long to wait for the cancelling rule's `invocation.result`, in ms. Default 30000. */
  timeoutMs?: number;
}

/** One `intercept.event` the mock sent. */
export interface MockInterceptDelivery {
  readonly key: string;
  readonly owner: string;
  readonly mode: string;
  /** The `cancelled` flag the event carried: an earlier rule had cancelled the interaction. */
  readonly cancelled: boolean;
}

/** What {@link MockMod.interact} did. */
export interface MockInteractionOutcome {
  /** A rule cancelled the interaction, so vanilla would not run it. */
  readonly cancelled: boolean;
  /** Key of the rule that cancelled it. */
  readonly by: string | undefined;
  /** That rule's `invocation.result`. */
  readonly result: MockInvocationResult | undefined;
  /** Every event sent, in rule order. */
  readonly deliveries: readonly MockInterceptDelivery[];
}

/** Armor stand part rotations in degrees. */
export type MockPose = readonly [number, number, number];

/** The entity properties of a `core:display.entity`, decoded for assertions. */
export interface MockEntityProps {
  readonly key: string;
  readonly type: string;
  readonly invisible: boolean;
  readonly onFire: boolean;
  readonly frozen: boolean;
  readonly charged: boolean;
  readonly saddled: boolean;
  readonly variant: string | undefined;
  readonly color: string | undefined;
  readonly profession: string | undefined;
  readonly effects: readonly string[];
  readonly armorStand:
    | {
        readonly small: boolean;
        readonly showArms: boolean;
        readonly noBasePlate: boolean;
        readonly marker: boolean;
        readonly pose: Readonly<Partial<Record<'head' | 'body' | 'leftArm' | 'rightArm' | 'leftLeg' | 'rightLeg', MockPose>>>;
      }
    | undefined;
  /** The SNBT string as sent. */
  readonly data: string | undefined;
  /** The SNBT parsed: compounds as objects, lists as arrays, numbers without their type suffix. */
  readonly nbt: unknown;
}

/**
 * A notification button: `action` is the full key of the sender's action it runs (run it with
 * `invokeAction(action, { type: 'notification' })`), or `suggest` the line it puts into the chat box.
 */
export interface MockNotifyButton {
  readonly label: string;
  readonly action: string | undefined;
  readonly suggest: string | undefined;
}

/** A chat line a bot printed. */
export interface MockChatLine {
  readonly owner: string;
  readonly spans: readonly ChatSpan[];
}

/** Where a captured chat line went for one bot. */
export type MockChatDelivery =
  /**
   * Parsed as a command and invoked; `result` is its `invocation.result`. For a crew's order,
   * `owner` is the crew's session and `bots` the bots it was for.
   */
  | { readonly owner: string; readonly routed: 'command'; readonly command: string; readonly path?: readonly string[]; readonly args: Record<string, unknown>; readonly bots?: readonly string[]; readonly result: MockInvocationResult }
  /**
   * Stopped at a node that only groups subcommands, or at one with subcommands whose required
   * arguments are missing; the mod showed its help lines and sent nothing.
   */
  | { readonly owner: string; readonly routed: 'usage'; readonly command: string; readonly path: readonly string[]; readonly usage: readonly string[] }
  /** Delivered as free text with `chat.message`. */
  | { readonly owner: string; readonly routed: 'message'; readonly text: string };

/** What {@link MockMod.sendChat} did with a line. */
export type MockChatOutcome =
  /** Not addressed to anything the mod knows: the line would go to the game server as `text`. */
  | { readonly captured: false; readonly text: string }
  /**
   * Cancelled on the client and routed. Every addressed command set whose command parses the line
   * runs it; when none does, a grouping node shows its help, and otherwise every addressed bot (or
   * the controller) gets the text. `broadcast` is set for `all` and crew groups.
   */
  | {
      readonly captured: true;
      readonly broadcast: boolean;
      readonly deliveries: readonly MockChatDelivery[];
      /**
       * Set, with no deliveries, when the name matched nothing but is close to one that would: the
       * line is kept from the server so a typo is not sent as chat.
       */
      readonly mistyped?: MockMistyped;
      /**
       * Set, with no deliveries, when the line addressed something but nothing could be delivered:
       * no text followed the name, or the device may not receive chat. The reason, as shown to the player.
       */
      readonly unknown?: string;
      /**
       * Set on a line delivered as free text whose first word names a command, when its arguments
       * did not parse: `<command>: <reason>`, so the player can tell a typo from a conversation.
       */
      readonly commandError?: string;
      /**
       * Set, with no deliveries, when the line went to the active `chat` surface prompt. `error`
       * says why it did not fit; the prompt then stays open.
       */
      readonly prompt?: { readonly id: number; readonly error?: string };
    };

/** A chat line whose name was most likely a typo of an addressable one. */
export interface MockMistyped {
  /** The name as typed. */
  readonly name: string;
  /** The bot, controller or group name it is closest to. */
  readonly closest: string;
  /** The line with `closest` in place of `name`. */
  readonly corrected: string;
  /** The line with the prefix doubled, which goes to the server unchanged. */
  readonly asChat: string;
}

/** A display, group or particle emitter in a layer. */
export interface MockPlaced {
  /** Full object key, as `element.event` carries it. */
  readonly key: string;
  readonly owner: string;
  /** Extension scope, e.g. `user:default`. */
  readonly extension: string;
  readonly layer: string;
  /** Shape id within the layer; group children are `<group id>/<child id>`. */
  readonly id: string;
  /** Kind name, e.g. `core:display.text`. */
  readonly kind: string;
  readonly scope: Scope;
  readonly world: WorldKey | undefined;
  /** Decoded kind data. */
  readonly data: unknown;
  /** Object traits as last written. */
  readonly traits: ObjectTraits | undefined;
}

/** A display (`core:display.text`, `.item`, `.block` or `.entity`). */
export interface MockDisplay extends MockPlaced {
  /** Shape id of the group it belongs to, if any. */
  readonly group: string | undefined;
}

/** A `core:group` with the displays and groups that name it, sorted by key. */
export interface MockGroup extends MockPlaced {
  /** Displays whose `group` field is this group. */
  readonly children: readonly MockDisplay[];
  /** Groups whose shape id is directly under this one (`<id>/<child>`), the plugin's nesting convention. */
  readonly groups: readonly MockPlaced[];
}

/** A client-only effect a bot played. */
export interface MockEffect {
  readonly owner: string;
  readonly effect: EffectBody;
}

/** Options of {@link MockMod.elementEvent}. */
export interface MockElementEventOptions {
  /** Scroll steps for `scroll`. */
  delta?: number;
  /** Vanilla key name for `key`; it must be one the element declares. */
  key?: string;
  /** Hit point relative to the element position, in blocks. */
  local?: Vec3d;
  /** Held modifiers, or the wire bit set (bit 0 shift, bit 1 control, bit 2 alt). */
  modifiers?: number | { shift?: boolean; ctrl?: boolean; alt?: boolean };
}
