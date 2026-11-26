import { randomBytes } from 'node:crypto';
import type { Notification as MockNotification } from './displays.ts';
import type { MockLoadedWorld } from './queries.ts';
import { mkdir, writeFile } from 'node:fs/promises';
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { createServer as createTlsServer, type Server as TlsServer } from 'node:https';
import type { AddressInfo } from 'node:net';
import { dirname } from 'node:path';
import { WebSocketServer } from 'ws';
import {
  BridgeError,
  toBase64Url,
  type Budget,
  type ClientSample,
  type Detail,
  type ElementInput,
  type KindName,
  type KindPayloads,
  type PickResult,
  type Scope,
  type SlotClick,
  type SlotEntry,
  type Target,
  type ToolInput,
  type WorldKey,
} from '@mineflayer-bridge/protocol';
import { ModCore } from './core.ts';
import type {
  AttachPolicy,
  DropTarget,
  IssuedDevice,
  LayerToggle,
  MockBlob,
  MockChatDelivery,
  MockChatLine,
  MockChatOutcome,
  MockClickOptions,
  MockCloseOptions,
  MockCommandOutcome,
  MockCompletion,
  MockContainerAck,
  MockContainerView,
  MockScreen,
  MockDeclaration,
  MockDevice,
  MockDisplay,
  MockEditAnswer,
  MockEffect,
  MockElementEventOptions,
  MockEntityProps,
  MockGhostBlocks,
  MockGroup,
  MockHeatmap,
  MockHighlights,
  MockHudPanel,
  MockInputRequest,
  MockInteraction,
  MockInteractionOutcome,
  MockInvocationResult,
  MockModEvents,
  MockModOptions,
  MockObject,
  MockPairing,
  MockPendingEdit,
  MockPlaced,
  MockProposeOptions,
  MockSampleRequest,
  MockSessionInfo,
  MockSpatial,
  MockSpatialKind,
  MockStorageMove,
  MockStorageResult,
  MockToolEventOptions,
  FocusPolicy,
} from './types.ts';
import { renderedText, renderTemplate, scopeVars } from './templates.ts';

/** Default deadline for a bot's `invocation.result` or `container.ack`, in ms. */
const ANSWER_TIMEOUT_MS = 30_000;

/**
 * A headless mod for tests. It implements the mod side of the wire protocol over a
 * real WebSocket server, keeps the object store in memory and renders
 * nothing. Created with {@link createMockMod}.
 */
export class MockMod {
  /** WebSocket URL of the endpoint, without the `/bridge/v1` path, as the mod writes it to endpoint.json. */
  readonly url: string;
  /** Port the mock listens on. */
  readonly port: number;
  /** Current local token (base64url). */
  readonly localToken: string;
  /** Instance fingerprint. */
  readonly fingerprint: string;
  /** True when the mock serves `wss://` ({@link MockModOptions.tls}). */
  readonly tls: boolean;

  private readonly core: ModCore;
  private readonly server: Server | TlsServer;
  private readonly wss: WebSocketServer;
  private readonly sweepTimer: NodeJS.Timeout;
  private readonly heartbeatTimer: NodeJS.Timeout;
  private closed = false;

  /** @internal Use {@link createMockMod}. */
  constructor(server: Server | TlsServer, wss: WebSocketServer, options: MockModOptions, localToken: string) {
    this.server = server;
    this.wss = wss;
    this.port = (server.address() as AddressInfo).port;
    const core = new ModCore(options, localToken, this.port, wss);
    this.core = core;
    this.localToken = localToken;
    this.tls = core.tls;
    this.fingerprint = core.fingerprint;
    const host = options.host ?? '127.0.0.1';
    this.url = `${this.tls ? 'wss' : 'ws'}://${host.includes(':') ? `[${host}]` : host}:${this.port}`;

    server.on('upgrade', (req, socket, head) => core.connections.onUpgrade(req, socket, head));
    this.sweepTimer = setInterval(() => core.sweep(), core.opts.sweepIntervalMs);
    this.sweepTimer.unref();
    this.heartbeatTimer = setInterval(() => core.connections.heartbeat(), Math.max(10, Math.floor(core.opts.heartbeatMs / 5)));
    this.heartbeatTimer.unref();
  }

  /** Subscribes to an event. Returns a function that removes the listener. */
  on<E extends keyof MockModEvents>(event: E, listener: (...args: MockModEvents[E]) => void): () => void {
    return this.core.on(event, listener);
  }

  /**
   * Resolves with the arguments of the next `event` for which `predicate` returns true.
   * @throws BridgeError `timeout` (rejects) when none arrives within `timeoutMs` (default 5000).
   */
  waitFor<E extends keyof MockModEvents>(event: E, predicate: (...args: MockModEvents[E]) => boolean = () => true, timeoutMs = 5000): Promise<MockModEvents[E]> {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        off();
        reject(new BridgeError('timeout', `mock: no ${event} within ${timeoutMs} ms`));
      }, timeoutMs);
      const off = this.on(event, (...args) => {
        if (!predicate(...args)) return;
        clearTimeout(timer);
        off();
        resolve(args);
      });
    });
  }

  /**
   * Writes `endpoint.json` the way the mod does on start, readable only by the
   * current user. Parent directories are created.
   */
  async writeEndpointFile(path: string): Promise<void> {
    await mkdir(dirname(path), { recursive: true });
    const body = { v: 1, url: this.url, fingerprint: this.fingerprint, localToken: this.localToken };
    await writeFile(path, JSON.stringify(body, null, 2), { mode: 0o600 });
  }

  /** Issues a device credential as `pair.result` would, so a client can use the `device` method. */
  issueDevice(deviceId: string = crypto.randomUUID()): IssuedDevice {
    return this.core.devices.issue(deviceId);
  }

  /**
   * Revokes a device: its connections get `goodbye{reason: 'revoked', retry:
   * false}` and close, and its credential and registry entry are deleted.
   */
  revokeDevice(deviceId: string): void {
    this.core.devices.revoke(deviceId);
  }

  /**
   * Shows a pairing code as *Bridge > Devices > Pair new device* does. The
   * code is single-use and expires after `ttlMs` (default {@link MockModOptions.pairingTtlMs});
   * `auth.challenge` offers `pair` while any code is active.
   *
   * @param options.host Host written into the URI. Default: the bind address, or `127.0.0.1` for a wildcard bind.
   * @throws BridgeError `unsupported` when the mock was created without {@link MockModOptions.tls}.
   */
  beginPairing(options: { host?: string; ttlMs?: number } = {}): MockPairing {
    return this.core.devices.beginPairing(options);
  }

  /** The device registry, sorted by device id. */
  devices(): MockDevice[] {
    return this.core.devices.list();
  }

  /** One registry entry, or `undefined` for an unknown device. */
  device(deviceId: string): MockDevice | undefined {
    return this.core.devices.get(deviceId);
  }

  /**
   * Replaces the granted permissions of a device and pushes the full set to its live connections
   * with the `permissions` message. Later requests needing a permission
   * outside the set are refused with `permission_denied`.
   *
   * @throws BridgeError `invalid_message` for a device the registry does not know.
   */
  setPermissions(deviceId: string, permissions: readonly string[]): void {
    this.core.devices.setPermissions(deviceId, permissions);
  }

  /** All live objects whose key starts with `prefix`, sorted by key, after expiring TTLs. */
  objects(prefix = ''): MockObject[] {
    return this.core.store.list(prefix);
  }

  /** One object by key, or `undefined`. */
  object(key: string): MockObject | undefined {
    return this.core.store.one(key);
  }

  /** The decoded data of a core-kind object, or `undefined` when missing or of another kind. */
  objectData<K extends KindName>(key: string, kind: K): KindPayloads[K] | undefined {
    return this.core.store.data(key, kind);
  }

  /** Blobs in the cache shared by every session, least recently used first. */
  blobs(): MockBlob[] {
    return this.core.streams.list();
  }

  /** A cached blob payload by lowercase hex SHA-256, or `undefined`. */
  blob(hash: string): Uint8Array | undefined {
    return this.core.streams.get(hash);
  }

  /**
   * A `core:ghost-blocks` object with its volume decoded, or `undefined` when the object is missing,
   * of another kind, or its blob has not arrived.
   */
  ghost(key: string): MockGhostBlocks | undefined {
    return this.core.streams.ghost(key);
  }

  /** A `core:heatmap` object with its cells decoded, or `undefined` when missing or its blob has not arrived. */
  heatmap(key: string): MockHeatmap | undefined {
    return this.core.streams.heatmap(key);
  }

  /** A `core:block-highlight` object with its positions, or `undefined` when missing or its blob has not arrived. */
  highlights(key: string): MockHighlights | undefined {
    return this.core.streams.highlights(key);
  }

  /** All sessions, including detached ones still inside their resume window. */
  sessions(): MockSessionInfo[] {
    return [...this.core.sessions.values()].map((s) => this.core.info(s));
  }

  /** The newest live session for an owner (`bot:...`), a bare identity, or a display name. */
  session(match: string): MockSessionInfo | undefined {
    const s = this.core.findSession(match);
    return s === undefined ? undefined : this.core.info(s);
  }

  /** Notifications received so far, oldest first. A button's `action` runs with `invokeAction(action, { type: 'notification' })`. */
  get notifies(): readonly MockNotification[] {
    return this.core.displays.notifications;
  }

  /** Sets what the player is doing (alive, away, window focus), sent in `player.context`. */
  setPlayerState(state: Partial<{ alive: boolean; away: boolean; windowFocused: boolean }>): void {
    this.core.player.setState(state);
  }

  /**
   * Sets whether the player plays their own singleplayer world, sent in `player.context` while they
   * are in a world: `{}` while it is not open to LAN, `{ lanPort }` once it is, `null` on a server.
   * The world key's server, set with {@link setPlayerContext}, is the caller's to match.
   */
  setPlayerSingleplayer(singleplayer: { lanPort?: number } | null): void {
    this.core.player.setSingleplayer(singleplayer);
  }

  /** Sets the player's profile name, sent in `player.context`. */
  setPlayerName(name: string | undefined): void {
    this.core.player.setName(name);
  }

  /** Sets the player's profile UUID, sent to every session in `player.context`; `undefined` leaves it out. */
  setPlayerUuid(uuid: string | undefined): void {
    this.core.player.setUuid(uuid);
  }

  /** Sets the player's world. `null` means the title screen. Sends `player.context` and `interest` where they change. */
  setPlayerContext(world: WorldKey | null): void {
    this.core.player.setWorld(world);
  }

  /** Extension messages the plugin sent, oldest first, as raw payload bytes; `owner` filters. */
  extensionMessages(owner?: string): readonly { owner: string; type: string; payload: Uint8Array }[] {
    const all = this.core.extensionMessages;
    return owner === undefined ? [...all] : all.filter((m) => m.owner === owner);
  }

  /**
   * Sends an extension message from the mod half to a session, as raw payload bytes encoded with the
   * extension's codec. Returns false when the session's connection did not register the name.
   * @throws BridgeError `invalid_message` for an unknown session.
   */
  sendExtension(session: string, type: string, payload: Uint8Array, options: { reliable?: boolean } = {}): boolean {
    const s = this.core.findSession(session);
    if (s === undefined) throw new BridgeError('invalid_message', `no session ${session}`);
    return this.core.sendExtension(s, type, payload, options.reliable ?? true);
  }

  /** Sets what the player's client has loaded, for `query.entities` and `query.blocks`. */
  setLoadedWorld(world: MockLoadedWorld): void {
    this.core.queries.world = world;
  }

  /** Overrides attach for a bot identity (`bot:...` owner or bare identity). `null` clears the override. */
  setAttached(identity: string, attached: boolean | null): void {
    this.core.player.setAttached(identity, attached);
    this.core.views.lapse();
  }

  /** Changes the attach policy for bots without an override. */
  setAttachPolicy(policy: AttachPolicy): void {
    this.core.player.setAttachPolicy(policy);
  }

  /**
   * Records a player layer toggle. Later toggles win over earlier ones; layers
   * without a matching toggle use their `defaultVisible`.
   */
  setLayerVisible(filter: LayerToggle, visible: boolean): void {
    this.core.player.setLayerVisible(filter, visible);
  }

  /**
   * Sets the level of detail sent in every session's `interest`, as the
   * mod does when many bots are attached or a bot is far away.
   */
  setDetail(detail: Detail): void {
    this.core.player.setDetail(detail);
  }

  /**
   * Focuses a bot (owner, bare identity or name) as the player would with the focus key, or clears
   * the focus with `null`. Every session's `interest.focused`
   * follows. Until the first call every attached session counts as focused.
   */
  setFocus(bot: string | null): void {
    this.core.player.setFocus(bot);
  }

  /** How later `focus.request`s are answered. Default `grant`. Each one emits `focusRequested`. */
  setFocusPolicy(policy: FocusPolicy): void {
    this.core.player.setFocusPolicy(policy);
  }

  /**
   * The variables of a scope, `<owner>/<extension>` such as `bot:alice@host:25565/user:default`,
   * or `undefined` when it declares none.
   */
  vars(scope: string): Record<string, unknown> | undefined {
    this.core.sweep();
    return scopeVars(this.core.store, scope);
  }

  /** Renders `template` with the variables of `scope` as the mod's client does; see {@link renderTemplate}. */
  renderTemplate(template: string, scope: string): string {
    return renderTemplate(template, this.vars(scope) ?? {});
  }

  /**
   * The text the player sees for the object at `key`: its `text`, `label` or `name` field rendered
   * with the variables of the scope that declared it, or `undefined` when it has no text field.
   */
  rendered(key: string): string | undefined {
    return renderedText(this.core.store, key);
  }

  /** The open transaction of a session (owner, identity or name): its id and buffered messages. */
  openTransaction(match: string): { tx: number; writes: number } | undefined {
    const t = this.core.findSession(match)?.tx;
    return t === undefined ? undefined : { tx: t.id, writes: t.writes.length };
  }

  /**
   * The player's sample rate cap in Hz: default 4, at most 20.
   * @throws BridgeError `invalid_message` for a rate outside (0, 20].
   */
  setSampleRateLimit(hz: number): void {
    this.core.player.setSampleRateLimit(hz);
  }

  /** The current `sample.request` of a session (owner, identity or name), or `undefined` when none is active. */
  sampleRequest(match: string): MockSampleRequest | undefined {
    const r = this.core.findSession(match)?.sampleRequest;
    return r === undefined ? undefined : { ...r };
  }

  /**
   * Simulates the player's client producing a sample. Every session with
   * an active request that is attached and world-matched gets a `client.sample` holding only the
   * fields it requested and whose permission its device was granted: `position`, `yaw` and `pitch`
   * need `client.sample.position`, `target` needs `client.sample.target`, `heldItem` needs
   * `client.sample.inventory`. Sessions left with no field get nothing. Each session is limited to
   * the lower of its requested rate and {@link setSampleRateLimit}; a sample inside the interval
   * replaces any waiting one and goes out when the interval ends (latest wins).
   *
   * @returns The number of sessions the sample was sent or scheduled to.
   */
  setPlayerSample(sample: ClientSample): number {
    return this.core.player.sample(sample);
  }

  /** Pushes a new budget to every session with `budget.update`. */
  setBudget(budget: Budget): void {
    this.core.player.setBudget(budget);
  }

  /**
   * Creates or replaces a shared object as the player would, bumping its revision and broadcasting it.
   * @returns The new revision.
   * @throws BridgeError `not_owner` when `key` does not start with `shared/`.
   */
  putShared<K extends KindName>(key: string, kind: K, data: KindPayloads[K], options: { scope?: Scope; world?: WorldKey; layer?: string } = {}): number {
    return this.core.shared.put(key, kind, data, options);
  }

  /**
   * Creates a shared zone, point or route as the player would, at `shared/bridge:zones/<id>` (id
   * default: a random UUID), revision 1, and broadcasts it.
   * @throws BridgeError `conflict` (with `details.rev`) when the key exists.
   */
  playerCreateShared<K extends MockSpatialKind>(kind: K, data: KindPayloads[K], options: { id?: string; world?: WorldKey } = {}): { key: string; rev: number } {
    return this.core.shared.playerCreate(kind, data, options);
  }

  /**
   * Edits a shared object as the player would with gizmos: `patch` is merged over its decoded data,
   * the revision goes up by one and the object is broadcast. Scope, world and layer are kept.
   * @returns The new revision.
   * @throws BridgeError `invalid_message` when the key is not a shared core-kind object, `conflict`
   *   (with `details.rev`) when `expectRev` is given and stale.
   */
  playerEditShared<K extends KindName = KindName>(key: string, patch: Partial<KindPayloads[K]>, options: { expectRev?: number } = {}): number {
    return this.core.shared.playerEdit(key, patch, options);
  }

  /**
   * Proposes a player edit of a bot-owned object, as a
   * released gizmo would: `patch` is merged over the decoded data and the changed fields go to the
   * owning session as `obj.edit.propose`. The edit is pending ({@link pendingEdits}) until the bot
   * answers; the bot's own upsert carries an accepted change, never this call.
   * @returns The bot's answer.
   * @throws BridgeError `invalid_message` when `key` is not a core-kind object; `disconnected` when
   *   no live session owns it. Rejects with `timeout` when the bot does not answer in time.
   */
  proposeEdit<K extends KindName = KindName>(key: string, patch: Partial<KindPayloads[K]>, options: MockProposeOptions = {}): Promise<MockEditAnswer> {
    return this.core.shared.proposeEdit(key, patch, options);
  }

  /** Player edits waiting for a bot's answer, oldest first. */
  pendingEdits(): MockPendingEdit[] {
    return this.core.shared.pendingEdits();
  }

  /** Zones held by the mod, shared and bot-owned, by key. */
  zones(): MockSpatial<'core:zone'>[] {
    return this.core.shared.spatial('core:zone');
  }

  /** Points held by the mod, shared and bot-owned, by key. */
  points(): MockSpatial<'core:point'>[] {
    return this.core.shared.spatial('core:point');
  }

  /** Routes held by the mod, shared and bot-owned, by key. */
  routes(): MockSpatial<'core:route'>[] {
    return this.core.shared.spatial('core:route');
  }

  /** Removes a shared object as the player would and broadcasts the removal. */
  removeShared(key: string): void {
    this.core.shared.playerRemove(key);
  }

  /** Abruptly drops a connection (no close handshake), to simulate a network failure. */
  disconnect(target: DropTarget): void {
    for (const c of this.core.resolveConns(target)) c.ws.terminate();
  }

  /**
   * Ends a session as if its resume window had passed: its objects are dropped, its resume token
   * becomes invalid and its connection closes, so the client must open a new session.
   */
  expireSession(target: string): void {
    const s = this.core.findSession(target);
    if (s === undefined) return;
    const conn = s.conn;
    conn?.sessions.delete(s.sessionId);
    s.conn = null;
    this.core.expire(s);
    conn?.ws.terminate();
  }

  /** Silently discards every inbound frame on a connection until it closes, to simulate frames lost in flight. */
  blackhole(target: DropTarget): void {
    for (const c of this.core.resolveConns(target)) c.blackholed = true;
  }

  /** Sends `goodbye` with the given reason to a connection and closes it. */
  kick(target: DropTarget, reason: string, retry = true): void {
    for (const c of this.core.resolveConns(target)) this.core.goodbye(c, reason, reason, retry);
  }

  /** Sends `goodbye{shutdown}` to every connection and stops listening. */
  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.sweepTimer);
    clearInterval(this.heartbeatTimer);
    for (const c of this.core.conns.values()) this.core.goodbye(c, 'shutdown', 'mod stopped', true);
    this.core.clearPending();
    await new Promise<void>((resolve) => this.wss.close(() => resolve()));
    for (const c of this.core.conns.values()) c.ws.terminate();
    this.server.closeAllConnections();
    await new Promise<void>((resolve) => this.server.close(() => resolve()));
  }

  /** The input request the player is answering now: the head of the queue. */
  active(): MockInputRequest | undefined {
    return this.core.inputs.active();
  }

  /** Every queued input request, the active one first. */
  inputQueue(): MockInputRequest[] {
    return this.core.inputs.list();
  }

  /**
   * Answers a pick. `result` is a `PickResult` union member, or just its value, which is then
   * wrapped with the request's kind.
   * @throws BridgeError `invalid_message` when there is no such pick.
   */
  resolvePick(id: number, result: PickResult | PickResult['value']): void {
    this.core.inputs.resolvePick(id, result);
  }

  /**
   * Answers a prompt with `value`, checked as the client would before sending it: a `lines` answer
   * is 1 to 4 strings and a `pages` answer 1 to 100 strings, each within `maxLength`; a text answer
   * is within `maxLength` and a number within `min` and `max`.
   * @throws BridgeError `invalid_message` when there is no such prompt or the answer does not fit.
   */
  resolvePrompt(id: number, value: unknown): void {
    this.core.inputs.resolvePrompt(id, value);
  }

  /**
   * Chooses an entry of a world menu, as the player clicking it.
   * @throws BridgeError `invalid_message` when there is no such world menu or it has no entry `entry`.
   */
  resolveWorldMenu(id: number, entry: string): void {
    this.core.inputs.resolveWorldMenu(id, entry);
  }

  /** Submits a form with `values` by field id. @throws BridgeError `invalid_message` when there is no such form. */
  resolveForm(id: number, values: Record<string, unknown>): void {
    this.core.inputs.resolveForm(id, values);
  }

  /** Presses one of a form's extra `buttons` instead of submitting it. @throws BridgeError `invalid_message` when there is no such form or button. */
  pressFormButton(id: number, button: string): void {
    this.core.inputs.pressFormButton(id, button);
  }

  /** Rejects any input request with `cancelled`, as the player pressing Esc. */
  cancel(id: number): void {
    this.core.inputs.cancel(id);
  }

  /** Expires an input request now, exactly as when its deadline passes. */
  timeOut(id: number): void {
    this.core.inputs.timeOut(id);
  }

  /**
   * Runs a context action as the player would from the radial menu, and resolves with the bot's
   * `invocation.result`.
   * @throws BridgeError `invalid_message` when `key` is not a `core:action`; rejects with `timeout`
   *   when the bot does not answer within `timeoutMs` (default 30000), or `disconnected` when no
   *   session owns the key. `opts` says how it was run and which bots were selected, as from the map.
   */
  invokeAction(key: string, target: Target, timeoutMs = ANSWER_TIMEOUT_MS, opts: { source?: string; bots?: readonly string[] } = {}): Promise<MockInvocationResult> {
    return this.core.chat.invokeAction(key, target, timeoutMs, opts);
  }

  /**
   * The context actions the radial menu offers on an object (its key under the crosshair): the
   * ones its `traits.actions` name, then `element` actions of the focused bot whose tag filter
   * matches its `interactive.tags` (an action with no filter matches every object), sorted by
   * owner, `order` and label.
   * @throws BridgeError `invalid_message` when there is no object at `key`.
   */
  objectActions(key: string): MockDeclaration<'core:action'>[] {
    return this.core.chat.objectActions(key);
  }

  /**
   * Picks an action from an object's radial menu, as the player looking at it would, and resolves
   * with the bot's `invocation.result`. The target is `{ type: 'element', objectKey: key }` and
   * the source `menu`. `action` is the action's full key or its id, as in {@link objectActions}.
   * Rejects with `invalid_message` when the object does not offer it; otherwise as
   * {@link invokeAction}.
   */
  runObjectAction(key: string, action: string, timeoutMs = ANSWER_TIMEOUT_MS): Promise<MockInvocationResult> {
    return this.core.chat.runObjectAction(key, action, timeoutMs);
  }

  /**
   * The context actions the radial menu offers on a shared zone, point or route (its key), as
   * when the player looks at its name label, its surface or marker: actions whose target type is
   * the object's kind (`zone`, `point`, `route`) and whose `ids` (matched against its name) and
   * `tags` filters it passes, of bots the player may see now, sorted by owner, `order` and label.
   * @throws BridgeError `invalid_message` when there is no zone, point or route at `key`.
   */
  sharedActions(key: string): MockDeclaration<'core:action'>[] {
    return this.core.chat.sharedActions(key);
  }

  /**
   * Picks an action from a shared object's radial menu, as the player would, and resolves with
   * the bot's `invocation.result`. The target is `{ type, objectKey: key, pos }` with the object's
   * kind as `type` and the middle of its bounds as `pos`; the source is `menu`. `action` is the
   * action's full key or its id, as in {@link sharedActions}. Rejects with `invalid_message` when
   * the object does not offer it; otherwise as {@link invokeAction}.
   */
  runSharedAction(key: string, action: string, timeoutMs = ANSWER_TIMEOUT_MS): Promise<MockInvocationResult> {
    return this.core.chat.runSharedAction(key, action, timeoutMs);
  }

  /**
   * Runs a command with parsed `args`, as from the palette. `path` names the subcommand below the
   * root, e.g. `['start']`; it is left out of `command.invoked` when empty. An order (declared under
   * `~order/`) needs `bots`, the crew bots it is for; other commands take none.
   * Errors as for {@link invokeAction}.
   */
  invokeCommand(key: string, args: Record<string, unknown> = {}, timeoutMs = ANSWER_TIMEOUT_MS, path: readonly string[] = [], bots: readonly string[] = []): Promise<MockInvocationResult> {
    return this.core.chat.invokeCommand(key, args, timeoutMs, path, bots);
  }

  /**
   * Types a command line for one bot, as after `/bridge <bot>` or in the palette, parsed as the
   * real mod parses it. Words are split at whitespace; double quotes group words into one. The
   * first word names a command of that bot by name or alias (an exact match first, then without
   * case); following words walk subcommands the same way, then fill the node's arguments
   * positionally: optional ones may be left off the end (declared defaults are filled in), `greedy`
   * takes the rest of the line, `block_pos` and `vec3` take three words (each a number, `~` or
   * `~offset` from the player of {@link setLoadedWorld}), `entity_list` takes comma-separated ids,
   * and selectors such as `@nearest` for entities and positions go through
   * {@link MockModOptions.resolveSelector}. A node that only groups subcommands, or one with
   * subcommands whose required arguments are missing, answers its help lines; a line that does
   * not parse answers the mod's error message with the offending offsets. Neither sends anything.
   * Otherwise resolves with the bot's `invocation.result`.
   * @param bot Bot username, session name or owner key, matched without case; a controller session
   *   matches by its name, as in `/bridge <controller> ...`.
   * @throws BridgeError `disconnected` when no connected bot session matches `bot`; rejects with
   *   `timeout` when the bot does not answer within `timeoutMs` (default 30000).
   */
  runCommand(bot: string, line: string, timeoutMs = ANSWER_TIMEOUT_MS): Promise<MockCommandOutcome> {
    return this.core.chat.run(bot, line, timeoutMs);
  }

  /**
   * Completes the last word of a command line for one bot, as the client does while the player types.
   * A line ending in whitespace completes an empty word. Suggestions are the
   * command or subcommand names (and aliases) that fit, then the argument's source:
   * - `choices` (also the default for `choice` arguments), `true`/`false` for `bool`, and `bots`
   *   (also the default for `bot` arguments) are resolved by the mock;
   * - `dynamic` sends `command.complete` with the earlier arguments already parsed, waits at most
   *   {@link MockModOptions.completionTimeoutMs} (default 300) and drops a late answer. Answers are
   *   cached per input prefix and reused for {@link MockModOptions.completionCacheMs} (default
   *   1500), or until the command object changes; an older answer asks the bot again;
   * - every other source returns {@link MockModOptions.completionStubs} for that source.
   * Client sources are filtered by prefix; all sources are cut to their `limit` (default 20). The
   * real mod also debounces typing by 100 ms; each call here is one settled keystroke.
   * @throws BridgeError `disconnected` when no connected bot session matches `bot`.
   */
  complete(bot: string, line: string): Promise<MockCompletion> {
    return this.core.chat.complete(bot, line);
  }

  /** Sends `keybind.event` for a declared keybind. @throws BridgeError `invalid_message` when `key` is not a `core:keybind`. */
  keybindEvent(key: string, pressed: boolean): void {
    this.core.controls.keybindEvent(key, pressed);
  }

  /** Sends `tool.event` for a declared tool. @throws BridgeError `invalid_message` when `key` is not a `core:tool`. */
  toolEvent(key: string, input: ToolInput, options: MockToolEventOptions = {}): void {
    this.core.controls.toolEvent(key, input, options);
  }

  /**
   * Selects a tool in the tool wheel, or none with `null`. The owning session gets `tool.state`
   * naming it, and a session whose tool was active before gets an empty `tool.state`.
   * @throws BridgeError `invalid_message` when `key` is not a `core:tool`.
   */
  setActiveTool(key: string | null): void {
    this.core.controls.setActiveTool(key);
  }

  /** The active tool's key, if any. */
  get activeToolKey(): string | undefined {
    return this.core.controls.activeTool;
  }

  /**
   * Changes one of the player's setting values (`undefined` restores the default) and sends the
   * group's values with `settings.values` to the owning session.
   */
  setSettingValue(settingsKey: string, key: string, value: unknown): void {
    this.core.controls.setSettingValue(settingsKey, key, value);
  }

  /** The player's values of a settings group, as last set with {@link setSettingValue}. */
  settingValues(settingsKey: string): Record<string, unknown> {
    return this.core.controls.settingValues(settingsKey);
  }

  /** Declared commands, sorted by key. */
  commands(): MockDeclaration<'core:command'>[] {
    return this.core.store.declarations('core:command', '~cmd/');
  }

  /** Declared context actions, sorted by key. */
  actions(): MockDeclaration<'core:action'>[] {
    return this.core.store.declarations('core:action', '~action/');
  }

  /** Declared keybinds, sorted by key. */
  keybinds(): MockDeclaration<'core:keybind'>[] {
    return this.core.store.declarations('core:keybind', '~key/');
  }

  /** Declared tools, sorted by key. */
  tools(): MockDeclaration<'core:tool'>[] {
    return this.core.store.declarations('core:tool', '~tool/');
  }

  /** Declared settings groups, sorted by key. */
  settingsGroups(): MockDeclaration<'core:settings'>[] {
    return this.core.store.declarations('core:settings', '~settings/');
  }

  /** Declared boss bars, sorted by key. */
  bossbars(): MockDeclaration<'core:bossbar'>[] {
    return this.core.store.declarations('core:bossbar', '~bossbar/');
  }

  /** Declared HUD panels, sorted by key, each with its widgets in display order. */
  hudPanels(): MockHudPanel[] {
    return this.core.store.hudPanels();
  }

  /** Declared container views and menus, sorted by key, each with whether the player's screen shows it. */
  views(): MockContainerView[] {
    return this.core.views.list();
  }

  /** Declared screens, sorted by key, each with whether the player has it open. */
  screens(): MockScreen[] {
    return this.core.screens.list();
  }

  /**
   * Opens a declared screen as the player would, from Bridge home or the primary menu key; the owner
   * gets `screen.open`.
   * @throws BridgeError `invalid_message` when `key` is not a `core:screen`.
   */
  openScreen(key: string): void {
    this.core.screens.playerOpen(key);
  }

  /** Closes a screen as the player pressing Esc; its bot gets `screen.close`. Returns false when it was not open. */
  closeScreen(key: string): boolean {
    return this.core.screens.playerClose(key);
  }

  /**
   * Clicks a button (`value` left out), flips a toggle (a boolean) or picks a cycle option (its index)
   * on a declared screen, and resolves with the bot's `invocation.result`.
   * @throws BridgeError `invalid_message` when `key` is not a `core:screen` or has no enabled item
   *   `item`; rejects as {@link invokeAction} does.
   */
  screenEvent(key: string, item: string, value?: boolean | number, timeoutMs = ANSWER_TIMEOUT_MS): Promise<MockInvocationResult> {
    return this.core.chat.screenEvent(key, item, value, timeoutMs);
  }

  /** Key of the container view the player's screen shows, if any. */
  get shownView(): string | undefined {
    return this.core.views.shown;
  }

  /**
   * Opens a container view as the player would, for example from the bot's context menu: the view
   * shown before closes first (its bot gets `container.close`), then the owner gets `container.open`.
   * @throws BridgeError `invalid_message` when `key` is not a `core:container-view`.
   */
  openView(key: string): void {
    this.core.views.open(key);
  }

  /**
   * Closes the shown view as the player pressing Esc; its bot gets `container.close`. A
   * `requireAnswer` view only closes with `confirm`, as after the player confirmed. A
   * view with `reopenParent` opens that view next, whose bot gets `container.open`.
   * Returns false when nothing closed.
   * @throws BridgeError `invalid_message` when `key` is given and is not the shown view.
   */
  closeView(key?: string, options: MockCloseOptions = {}): boolean {
    return this.core.views.close(key, options);
  }

  /**
   * Makes a gesture on a slot of the shown view and resolves with the bot's `container.ack`. A
   * `closeOnClick` view closes right after the gesture is sent.
   * @throws BridgeError `invalid_message` when the view is not shown, the slot is locked or a
   *   storage slot, `slot` is not an integer, or `number` has no hotbar slot from 0 to 8; rejects
   *   with `timeout` when no ack arrives within `timeoutMs` (default 30000).
   */
  clickSlot(key: string, slot: number, click: SlotClick, options: MockClickOptions = {}): Promise<MockContainerAck> {
    return this.core.views.click(key, slot, click, options);
  }

  /** What the storage slots of a view hold now, sorted by slot; empty slots are left out. */
  storage(key: string): SlotEntry[] {
    return this.core.views.storage(key);
  }

  /**
   * Moves items in the storage slots of the shown view as the player would, checking
   * policies, `accepts` and `maxStack` on the client. A move that involves an
   * `ask` slot is applied at once and sent as `container.input` with `moved` and `fromSlot`; a
   * rejecting `container.ack` rolls it back. After every applied change the bot gets
   * `container.storage` with every storage slot.
   *
   * Copy semantics: a move from a `source` slot takes a copy of its declared item (a stack of
   * `count`, default the template's count) and leaves the slot as it is; nothing can be moved into a
   * source slot (`locked`) and a copy cannot go into the player's real inventory (`virtual copy`).
   * A move from `player` is a copy as well, since the player's inventory is a copy source of a view
   * with storage slots, so the player keeps the stack.
   * @throws BridgeError `invalid_message` when the view is not shown, a slot is outside the view, or
   *   a move from the player has no item; rejects with `timeout` when an `ask` move gets no ack.
   */
  storageMove(key: string, move: MockStorageMove): Promise<MockStorageResult> {
    return this.core.views.storageMove(key, move);
  }

  /** Declared intercept rules, sorted by key. */
  intercepts(): MockDeclaration<'core:intercept'>[] {
    return this.core.controls.intercepts();
  }

  /**
   * Grants or revokes `input.intercept` cancel mode for a bot identity (`bot:...` owner or bare
   * identity), as the player would per bot. Without an override it is on for bots of local devices
   * and off for remotely paired ones; without it a `cancel` rule only observes.
   */
  setInterceptCancel(identity: string, allowed: boolean): void {
    this.core.controls.setInterceptCancel(identity, allowed);
  }

  /**
   * The player interacts with a real block or entity. Every
   * matching rule of an attached bot (world-bound rules only while the world matches) gets
   * `intercept.event`, in priority order: highest first, `monitor` last, then by key. The first
   * permitted `cancel` rule cancels the interaction and its `invocation.result` is awaited; later
   * rules hear about it flagged as cancelled.
   * @throws BridgeError `timeout` when the cancelling rule does not answer within `timeoutMs`.
   */
  interact(o: MockInteraction): Promise<MockInteractionOutcome> {
    return this.core.controls.interact(o);
  }

  /**
   * The entity properties of an entity display, decoded and with its SNBT parsed.
   * @throws BridgeError `invalid_message` when `key` is not a `core:display.entity` or its data is not valid SNBT.
   */
  entityProps(key: string): MockEntityProps {
    return this.core.displays.entityProps(key);
  }

  /** Displays of every session, sorted by key. */
  displays(): MockDisplay[] {
    return this.core.displays.displays();
  }

  /** Groups of every session, sorted by key, each with the displays and groups under it. */
  groups(): MockGroup[] {
    return this.core.displays.groups();
  }

  /** Particle emitters of every session, sorted by key. */
  emitters(): MockPlaced[] {
    return this.core.displays.emitters();
  }

  /** Every effect bots played with `effect.play`, oldest first. */
  effects(): MockEffect[] {
    return [...this.core.displays.effects];
  }

  /**
   * Interacts with an interactive object of any kind as the player would, sending `element.event`
   * to its owner. The inputs come from the object's `interactive` traits (or, for displays, the
   * `interactive` data field).
   * @throws BridgeError `invalid_message` when `key` is not a core-kind object, the object does not
   *   declare `input` (hover events only reach bots that registered them), or a `key` input names a
   *   key it does not declare; `disconnected` when no session owns it.
   */
  elementEvent(key: string, input: ElementInput, options: MockElementEventOptions = {}): void {
    this.core.displays.elementEvent(key, input, options);
  }

  /** Every line bots printed with `chat.print`, oldest first. */
  printed(): MockChatLine[] {
    return [...this.core.chat.printed];
  }

  /**
   * Types a chat line as the player, routed as the real mod routes it. While the active input
   * request is a text or confirm prompt on the `chat` surface, the line answers it (`prompt` is
   * set; a confirm takes yes, no, y or n). Otherwise the line is captured only when the token after
   * the prefix (default `@`) is one of {@link addresses}, matched without case; `@@` escapes the
   * prefix. A bot reaches its own commands and the orders of every crew it is in, a crew group its
   * crew's orders, `all` every bot's commands and every crew's orders, a controller its own
   * commands. Every command set whose command parses the line runs it (parsed as
   * {@link runCommand} does); when none does, a grouping node reports its help, and otherwise the
   * text goes to the addressed bots (or controller) as `chat.message`, with `commandError` set when
   * the first word named a command whose arguments did not parse. `unknown` is set, and nothing
   * delivered, when no text follows the name or the addressed device lacks the `chat` permission.
   * A name that matches nothing but is one edit from one that would (two from six letters on), or
   * starts with one or is the start of one, is taken for a typo: the line is captured with
   * `mistyped` set and nothing is delivered. Resolves once every command invocation answered.
   */
  sendChat(line: string): Promise<MockChatOutcome> {
    return this.core.chat.sendChat(line);
  }

  /**
   * What the mod shows for an `order` chat click (`run_order`) on a line `owner` printed: the line
   * it runs, with the chat prefix. The value is `<name> <command line>`, addressed as in
   * {@link sendChat}, and reaches only the printing session's own commands and the orders it gives.
   * @param owner Owner key of the session that printed the line, as in {@link printed}.
   * @throws BridgeError `disconnected` when that session is gone, `permission_denied` when its device
   *   lacks the `chat` permission, `invalid_message` for a name nobody answers to or a line that runs
   *   none of its commands or orders (never sent as chat). The mod then shows the span without a click.
   */
  describeOrderClick(owner: string, value: string): string {
    return this.core.chat.describeOrderClick(owner, value);
  }

  /**
   * Runs an `order` chat click as the player's click does: every command set of the printing
   * session that the addressed line parses in runs it, with the source `chat`. Rejects as
   * {@link describeOrderClick} throws, sending nothing.
   */
  runOrderClick(owner: string, value: string): Promise<MockChatDelivery[]> {
    return this.core.chat.runOrderClick(owner, value);
  }

  /**
   * Every name the player may address after the chat prefix or `/bridge`, as the real mod lists
   * them: `all` while a bot is attached, attached bots, live controllers, then crew groups.
   */
  addresses(): { name: string; kind: 'all' | 'bot' | 'controller' | 'group'; bots: string[] }[] {
    return this.core.chat.addressees().map((a) => ({ name: a.name, kind: a.kind, bots: a.kind === 'controller' ? [] : a.chatTo.map((s) => s.identity?.username ?? s.name) }));
  }
}

/**
 * Starts a mock mod listening on `host:port` and resolves once it accepts connections.
 *
 * @example
 * const mod = await createMockMod()
 * await mod.writeEndpointFile(join(dir, 'endpoint.json'))
 */
export async function createMockMod(options: MockModOptions = {}): Promise<MockMod> {
  const notFound = (_req: IncomingMessage, res: ServerResponse): void => {
    res.statusCode = 404;
    res.end();
  };
  const server: Server | TlsServer = options.tls === undefined ? createServer(notFound) : createTlsServer({ key: options.tls.key, cert: options.tls.cert }, notFound);
  // ws closes oversize frames with 1009, as the protocol requires.
  const wss = new WebSocketServer({ noServer: true, maxPayload: options.maxFrameBytes ?? 1024 * 1024 });
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(options.port ?? 0, options.host ?? '127.0.0.1', () => {
      server.off('error', reject);
      resolve();
    });
  });
  const token = options.localToken ?? toBase64Url(new Uint8Array(randomBytes(32)));
  return new MockMod(server, wss, options, token);
}
