import {
  BridgeError,
  decodeKind,
  fromBase64Url,
  Kinds,
  toBase64Url,
  type BotIdentity,
  type Detail,
  type ExtensionRef,
  type Interest,
  type MessagePayloads,
  type SessionKind,
  type WorldKey,
  type MessageType,
} from '@mineflayer-bridge/protocol';
import { encodeExtension, extensionMessage } from './ext-wire.ts';
import type { EffectsApi, WorldMenuOptions } from './display-types.ts';
import { ScopeFeatures } from './features.ts';
import type {
  ActionsApi,
  BossbarHandle,
  BossbarOptions,
  ChatApi,
  CommandsApi,
  CrewApi,
  ContainerView,
  ContainerViewOptions,
  Screen,
  ScreenOptions,
  FormDefinition,
  FormFieldDef,
  FormButtonDef,
  FormResult,
  FormValues,
  HudPanel,
  HudPanelOptions,
  InterceptsApi,
  KeybindsApi,
  MenuFactory,
  PickApi,
  PromptApi,
  SettingsApi,
  SurfaceOptions,
  SurfaceRefusal,
  ToolsApi,
} from './input-types.ts';
import { guard, InputRouter, OpenTracker, refusalOf, type InputHost, type Refusable, type SessionInputEvents } from './input.ts';
import { dependencyIds, missingCapabilities, orderForLoad, supportsSpec, validateOptions, type LoadedExtension } from './extensions.ts';
import type { Link } from './link.ts';
import { CoreSet, currentOrigin, PlayerImpl, playerChanges, runForEveryone, type HubLink } from './players.ts';
import type { BlobLimits } from './blobs.ts';
import type { Volume, VolumeOptions } from './retained-types.ts';
import { createQueries, type QueryApi } from './queries.ts';
import { SampleHub } from './samples.ts';
import { ScopeObjects, type ScopeHost } from './scope.ts';
import { SessionCore, type SessionHandlers } from './session.ts';
import { decideEdit, EditRegistry } from './edits.ts';
import { SharedApiImpl, SharedState } from './shared.ts';
import { DeclaredStore } from './store.ts';
import type {
  BotBridge,
  BotLike,
  BotOptionsLike,
  BridgeController,
  BridgeLimits,
  BridgeEvents,
  BridgePlayer,
  BridgeSession,
  BridgeState,
  BridgeStore,
  EmitterLike,
  ExtensionChannel,
  ExtensionContext,
  ExtensionDefinition,
  ExtensionInstance,
  ExtensionRefLike,
  ExtensionUse,
  KeyValueStore,
  Layer,
  LayerOptions,
  Logger,
  NotifyOptions,
  ObjectsApi,
  OptionsSchema,
  Permission,
  PersistedOwner,
  RawFrame,
  SamplesApi,
  SessionInfo,
  SharedApi,
  StyleOptions,
  VarsApi,
} from './types.ts';
import { asBridgeError, Emitter, tagLogger, toVec3, waitFor } from './util.ts';
import { ScopeVars } from './vars.ts';
import { VolumeImpl } from './volume.ts';

/** Scope used by code that calls `bot.bridge` directly. */
const DEFAULT_SCOPE = 'user:default';
const POSITION_INTERVAL_MS = 500;
const PERSIST_DEBOUNCE_MS = 20;
const MAX_NOTIFY_BUTTONS = 4;
/** Notifications whose buttons still work. */
const MAX_NOTIFY_ACTIONS = 32;

/** What a session needs from its hub. */
export interface HubContext {
  /** The hub's links, in order; sessions get one protocol session per link. */
  links(): readonly HubLink[];
  readonly logger: Logger;
  readonly store: BridgeStore | undefined;
  /** The hub's `surfaces` option. */
  readonly surfaces: SurfaceOptions | undefined;
  /** Objects every session of the hub declares together; the mod's object limit is per device. */
  objectCount(): number;
  /** Records definitions so their kind and message names go into the next `hello`. */
  register(defs: readonly ExtensionDefinition<OptionsSchema | undefined, unknown>[]): void;
  forget(session: SessionImpl): void;
}

interface Watcher {
  check(): boolean;
  fn(signal: AbortSignal): void | (() => void);
  active: AbortController | undefined;
  cleanup: (() => void) | undefined;
}

interface Loaded extends LoadedExtension {
  readonly scope: ScopeObjects;
  readonly features: ScopeFeatures;
  readonly deps: readonly string[];
}

/** Shared implementation of bot and controller sessions. */
export abstract class SessionImpl implements BridgeSession, ScopeHost {
  readonly store = new DeclaredStore();
  readonly ready: Promise<void>;
  readonly objects: ObjectsApi;
  readonly shared: SharedApi;
  readonly pick: PickApi;
  readonly prompt: PromptApi;
  readonly commands: CommandsApi;
  readonly orders: CommandsApi;
  readonly crew: CrewApi;
  readonly actions: ActionsApi;
  readonly intercepts: InterceptsApi;
  readonly keybinds: KeybindsApi;
  readonly tools: ToolsApi;
  readonly settings: SettingsApi;
  readonly menu: MenuFactory;
  readonly chat: ChatApi;
  readonly effects: EffectsApi;
  /** Routes input events of this session; also read by layers for element events. */
  readonly router: InputRouter;
  readonly edits = new EditRegistry();
  readonly samples: SamplesApi;
  readonly query: QueryApi;
  readonly vars: VarsApi;

  protected readonly hub: HubContext;
  protected readonly core: CoreSet;
  protected readonly events = new Emitter<BridgeEvents>();
  protected readonly log: Logger;
  private readonly sharedState: SharedState;
  private readonly defaultScope: ScopeObjects;
  private readonly defaultFeatures: ScopeFeatures;
  private readonly inputHost: InputHost;
  private readonly inputEvents = new Emitter<SessionInputEvents>();
  /** Aborts on close; cancels pending input requests of the default scope. */
  private readonly lifetime = new AbortController();
  private readonly loaded = new Map<string, Loaded>();
  private loadOrder: string[] = [];
  private readonly watchers = new Set<Watcher>();
  private readonly sampleHub = new SampleHub({
    sendRequest: (p) => this.core.sendLatest('sample.request', this.core.link.encode('sample.request', p)),
    reportError: (e) => this.reportError(e),
  });
  private detail: Detail = 'full';
  private focusedNow = false;
  private attached = false;
  private worldMatched = false;
  private playerWorld: WorldKey | null = null;
  /** What each linked player's mod reported, by hub link in link order. */
  private readonly playerMap = new Map<HubLink, PlayerImpl>();
  private readonly linkOffs = new Map<HubLink, () => void>();
  private persistKey: string | undefined;
  private persistValues: Record<string, Record<string, unknown>> = {};
  private persistLoaded: Promise<void> = Promise.resolve();
  private persistTimer: NodeJS.Timeout | undefined;
  private closed = false;

  protected constructor(hub: HubContext, kind: SessionKind, name: string, uses: readonly ExtensionUse[], log: Logger) {
    this.hub = hub;
    this.log = log;
    this.core = new CoreSet(kind, (l) => new SessionCore(l.link, kind, name, this.store, this.handlers(l), () => this.extensionRefs()));
    this.sharedState = new SharedState({ kindName: (id) => this.core.link.kindName(id) });
    this.defaultScope = new ScopeObjects(this, DEFAULT_SCOPE);
    this.objects = this.defaultScope;
    this.samples = this.sampleHub.scope(undefined);
    this.query = createQueries(this.core);
    this.shared = new SharedApiImpl(this.sharedState, this.core, this.registry(), DEFAULT_SCOPE, undefined, () => this.currentWorld());
    this.router = new InputRouter(() => this.core.owner, (type, payload) => this.refuse(type, payload), log);
    const self = this;
    this.inputHost = {
      core: this.core,
      get link() {
        return self.core.link;
      },
      origin: () => this.origin,
      router: this.router,
      log,
      events: this.inputEvents,
      reportError: (e) => this.reportError(e),
      opens: new OpenTracker(),
      onRefused: hub.surfaces?.onRefused,
      refused: (r) => this.events.emit('refused', r),
      whenOpen: (signal, timeoutMs) => this.whenOpen(signal, timeoutMs),
      self: () => this.self(),
    };
    this.defaultFeatures = new ScopeFeatures(this.inputHost, this.defaultScope, this.lifetime.signal, this.kv(DEFAULT_SCOPE));
    ({
      pick: this.pick,
      prompt: this.prompt,
      commands: this.commands,
      orders: this.orders,
      crew: this.crew,
      actions: this.actions,
      intercepts: this.intercepts,
      keybinds: this.keybinds,
      tools: this.tools,
      settings: this.settings,
      menu: this.menu,
      chat: this.chat,
      effects: this.effects,
    } = this.defaultFeatures);
    this.vars = new ScopeVars(this.defaultScope);
    this.store.onPersistentChange = () => this.schedulePersist();
    // session.open lists the loaded extensions, so the first open waits for them.
    this.core.hold();
    const loaded = this.use(...uses);
    loaded.catch((e: unknown) => this.reportError(asBridgeError(e))).finally(() => this.core.release());
    // `ready` means usable: extensions loaded and the session open, so fire-and-forget calls such as
    // effects are not dropped while the first connection is still being made.
    this.ready = loaded.then(() => this.firstOpen());
    this.ready.catch(() => undefined);
    for (const l of hub.links()) this.addLink(l);
  }

  private registry(): Pick<Link, 'kindId'> {
    return { kindId: (name) => this.core.link.kindId(name) };
  }

  /** Opens this session on one more hub link. */
  addLink(l: HubLink): void {
    if (this.closed || this.playerMap.has(l)) return;
    this.playerMap.set(l, new PlayerImpl(l));
    // The primary link's permissions are the session's.
    this.linkOffs.set(
      l,
      l.link.events.on('permissions', (set) => {
        if (this.core.primaryLink === l) this.events.emit('permissions', set);
      }),
    );
    this.core.add(l);
  }

  /** Ends this session on one hub link (the mod drops its objects); the other links carry on. */
  async removeLink(l: HubLink): Promise<void> {
    const p = this.playerMap.get(l);
    if (p === undefined) return;
    const wasPrimary = this.core.primaryLink === l;
    this.linkOffs.get(l)?.();
    this.linkOffs.delete(l);
    await this.core.remove(l);
    this.playerMap.delete(l);
    if (p.connected) {
      p.reset();
      this.playerLost();
    }
    // The next primary mod sends its own shared objects once the subscription moves to it.
    if (wasPrimary) this.sharedState.reset();
  }

  get players(): readonly BridgePlayer[] {
    return [...this.playerMap.values()];
  }

  player(id: string): BridgePlayer | undefined {
    for (const p of this.playerMap.values()) if (p.id === id) return p;
    return undefined;
  }

  get origin(): BridgePlayer | undefined {
    const o = currentOrigin();
    return o === undefined ? undefined : this.playerMap.get(o);
  }

  everyone<T>(fn: () => T): T {
    return runForEveryone(fn);
  }

  private isPrimary(l: HubLink): boolean {
    return this.core.primaryLink === l;
  }

  /** The linked players whose session is open. */
  private openPlayers(): PlayerImpl[] {
    return [...this.playerMap.values()].filter((p) => p.connected);
  }

  /**
   * Recomputes the singular state from the players: attached, matched and focused when any open
   * player is; reduced detail only when every open player asks for it; the player world of the first
   * attached open player, else of the first open one, else the primary player's last known world.
   */
  private combine(): void {
    const open = this.openPlayers();
    const world = (open.find((p) => p.attached && p.playerWorld !== null) ?? open.find((p) => p.playerWorld !== null))?.playerWorld;
    const primary: PlayerImpl | undefined = this.playerMap.values().next().value;
    this.playerWorld = world ?? primary?.playerWorld ?? null;
    if (open.length > 0) this.detail = open.some((p) => p.detail === 'full') ? 'full' : 'reduced';
    this.setAttached(open.some((p) => p.attached));
    this.setMatched(open.some((p) => p.worldMatched));
    this.setFocused(open.some((p) => p.focused));
  }

  /** A player's session closed; when it was the last open one the session counts as disconnected. */
  private playerLost(): void {
    const any = this.openPlayers().length > 0;
    if (!any) {
      this.sampleHub.reset();
      this.router.reset();
    }
    this.combine();
    if (!any) this.events.emit('disconnected');
    this.refreshVisibility();
  }

  get state(): BridgeState {
    return {
      connected: this.core.connected,
      attached: this.attached,
      worldMatched: this.worldMatched,
      playerWorld: this.playerWorld,
      capabilities: this.core.link.capabilities,
      superseded: this.core.superseded,
      detail: this.detail,
    };
  }

  get focused(): boolean {
    return this.focusedNow;
  }

  /** @throws BridgeError `invalid_message` when `reason` is not a string. */
  requestFocus(reason?: string): void {
    if (reason !== undefined && typeof reason !== 'string') throw new BridgeError('invalid_message', 'focus request reason must be a string');
    this.core.sendLatest('focus.request', this.core.link.encode('focus.request', reason === undefined ? {} : { reason }));
  }

  transaction<T>(fn: () => T): T {
    this.core.beginTx();
    let r: T;
    try {
      r = fn();
    } catch (e) {
      this.core.endTx();
      throw e;
    }
    if (r instanceof Promise) return r.finally(() => this.core.endTx()) as T;
    this.core.endTx();
    return r;
  }

  private setFocused(v: boolean): void {
    if (v === this.focusedNow) return;
    this.focusedNow = v;
    this.events.emit('focus', v);
  }

  get session(): SessionInfo {
    return { id: this.core.sessionId, kind: this.core.kind, identity: this.core.identity, owner: this.core.owner };
  }

  /** The bot's world for world-scoped shapes; controllers have none. */
  currentWorld(): WorldKey | undefined {
    return undefined;
  }

  /** The bot's username for `head: 'self'`; controllers have none. */
  self(): string | undefined {
    const name = this.contextBot()?.username;
    return name === undefined || name === null || name === '' ? undefined : name;
  }

  /** Reports an error nobody could catch as the `error` event, or in the log while nobody listens. */
  reportError(error: BridgeError): void {
    if (this.events.listenerCount('error') > 0) this.events.emit('error', error);
    else this.log.error(`${error.code}: ${error.message}`, error.cause instanceof Error && error.cause.stack !== undefined ? { stack: error.cause.stack } : undefined);
  }

  blobLimits(): BlobLimits {
    return this.core.blobLimits();
  }

  objectCount(): number {
    return this.hub.objectCount();
  }

  volume(options?: VolumeOptions): Volume {
    return new VolumeImpl(options);
  }

  /** Visible to at least one open player who has the bot attached and is in its world. */
  layerVisible(scope: string, layer: string, defaultVisible: boolean): boolean {
    const key = `${scope}\n${layer}`;
    for (const p of this.playerMap.values()) {
      if (p.connected && p.attached && p.worldMatched && (p.layers.get(key) ?? defaultVisible)) return true;
    }
    return false;
  }

  private declaredDefaultVisible(scope: string, layer: string): boolean {
    const o = this.store.get(`${scope}/${layer}`);
    if (o === undefined || o.kind !== 'core:layer') return true;
    return decodeKind(Kinds['core:layer'], o.data).defaultVisible ?? true;
  }

  /** Answers an inbound message nobody handles, so the player always gets an answer. */
  private refuse<N extends Refusable>(type: N, payload: MessagePayloads[N]): void {
    const link = this.core.link;
    if (type === 'container.input') {
      const p = payload as MessagePayloads['container.input'];
      this.core.send('container.ack', link.encode('container.ack', { view: p.view, input: p.input, ok: false, message: 'not available' }));
    } else if (type === 'container.open') {
      this.core.send('container.close', link.encode('container.close', { view: (payload as MessagePayloads['container.open']).view }));
    } else {
      const invocation = (payload as MessagePayloads['command.invoked' | 'action.invoked' | 'intercept.event' | 'screen.event']).invocation;
      this.core.send('invocation.result', link.encode('invocation.result', { invocation, ok: false, message: 'not available' }));
    }
  }

  /**
   * Callbacks of the protocol session on hub link `l`. Input from that link is handled inside it
   * (see {@link currentOrigin}), so answers and what handlers open go back to the same player.
   * Shared objects and client samples are taken from the primary link only.
   */
  /** Listeners of extension messages from the mod, by message name. */
  private readonly channelListeners = new Map<string, Set<(payload: unknown) => void>>();

  private handlers(l: HubLink): SessionHandlers {
    const player = (): PlayerImpl | undefined => this.playerMap.get(l);
    const as = <T>(fn: () => T): T => player()!.run(fn);
    return {
      extension: (type, payload) => {
        const fns = this.channelListeners.get(type);
        if (fns === undefined) return;
        as(() => {
          for (const fn of [...fns]) guard(this.inputHost, () => fn(payload));
        });
      },
      opened: (fresh) => {
        const p = player();
        if (p === undefined) return;
        const first = this.openPlayers().length === 0;
        p.connected = true;
        if (first) {
          this.inputEvents.emit('opened', fresh);
          this.events.emit('connected');
          this.settleFirstOpen(undefined);
        }
        this.checkRequirements();
        this.refreshVisibility();
      },
      lost: () => {
        const p = player();
        if (p === undefined) return;
        p.reset();
        this.playerLost();
      },
      context: (c) => {
        const p = player();
        if (p === undefined) return;
        const changes = playerChanges(p, c);
        p.playerWorld = c.world ?? null;
        p.name = c.name;
        p.uuid = c.uuid ?? undefined;
        p.attached = c.attached;
        p.inWorld = c.inWorld;
        p.alive = c.alive !== false;
        p.away = c.away === true;
        p.windowFocused = c.windowFocused !== false;
        this.combine();
        this.refreshVisibility();
        for (const change of changes) as(() => this.events.emit('player', change, p));
      },
      interest: (i) => this.onInterest(l, i),
      sharedUpsert: (w) => {
        if (this.isPrimary(l)) this.sharedState.upsert(w);
      },
      sharedRemove: (p) => {
        if (this.isPrimary(l)) this.sharedState.remove(p);
      },
      sharedReset: () => {
        if (this.isPrimary(l)) this.sharedState.reset();
      },
      superseded: () => this.events.emit('superseded'),
      writeError: (e, relatesTo) => {
        // A view or screen open returns nothing to reject, so its refusal is only an event.
        const surface = relatesTo === 'container.open' ? 'view' : relatesTo === 'screen.open' ? 'screen' : undefined;
        const refusal = surface === undefined ? undefined : refusalOf(e, surface, 0, player());
        if (refusal !== undefined && surface !== undefined) {
          as(() => {
            this.events.emit('refused', refusal);
            // The handle counted itself open when it asked; it closes again with reason `refused`.
            const key = (e.details as { key?: unknown } | null | undefined)?.key;
            this.inputHost.opens.refuse(surface, typeof key === 'string' ? key : undefined);
          });
        } else this.log.warn(`mod rejected ${relatesTo ?? 'a write'}: ${e.code}: ${e.message}`);
      },
      openFailed: (e) => {
        this.reportError(e);
        if (this.openPlayers().length === 0) this.settleFirstOpen(e);
      },
      input: (type, p) => as(() => this.router.dispatch(type, p)),
      editPropose: (p, reply) =>
        as(() => {
          decideEdit(p, { owner: this.core.owner, store: this.store, edits: this.edits, reportError: (e) => this.reportError(e) }).then(reply, (e: unknown) => {
            const error = asBridgeError(e);
            this.reportError(error);
            reply({ accepted: false, reason: error.message });
          });
        }),
      chat: (p) => as(() => this.inputEvents.emit('chat', p)),
      sample: (p) => {
        if (this.isPrimary(l)) this.sampleHub.receive(p);
      },
      complete: (p, reply) => as(() => this.router.complete(p, (suggestions) => reply({ suggestions }))),
    };
  }

  private onInterest(l: HubLink, i: MessagePayloads['interest']): void {
    const p = this.playerMap.get(l);
    if (p === undefined) return;
    const vis = new Map<string, boolean>();
    for (const x of i.layers) vis.set(`${x.extension}\n${x.layer}`, x.visible);
    p.layers = vis;
    p.detail = i.detail;
    p.attached = i.attached;
    p.worldMatched = i.worldMatched;
    p.focused = i.focused === true;
    this.combine();
    this.events.emit('interest', i);
    this.refreshVisibility();
  }

  protected setAttached(v: boolean): void {
    if (v === this.attached) return;
    this.attached = v;
    if (!v) this.sampleHub.reset();
    this.events.emit(v ? 'attached' : 'detached');
    if (v) this.onAttach();
  }

  private setMatched(v: boolean): void {
    if (v === this.worldMatched) return;
    this.worldMatched = v;
    this.events.emit(v ? 'worldMatched' : 'worldUnmatched');
  }

  /** Hook for subclasses when the player attaches. */
  protected onAttach(): void {}

  private refreshVisibility(): void {
    for (const w of this.watchers) this.evaluate(w);
  }

  private evaluate(w: Watcher): void {
    const visible = w.check();
    if (visible && w.active === undefined) {
      w.active = new AbortController();
      try {
        const cleanup = w.fn(w.active.signal);
        w.cleanup = typeof cleanup === 'function' ? cleanup : undefined;
      } catch (e) {
        this.reportError(asBridgeError(e));
      }
    } else if (!visible && w.active !== undefined) {
      this.stopWatcher(w);
    }
  }

  private stopWatcher(w: Watcher): void {
    w.active?.abort();
    w.active = undefined;
    const c = w.cleanup;
    w.cleanup = undefined;
    try {
      c?.();
    } catch (e) {
      this.reportError(asBridgeError(e));
    }
  }

  private extensionRefs(): ExtensionRef[] {
    return this.loadOrder.map((id) => {
      const l = this.loaded.get(id)!;
      return { id: l.instance.id, version: l.instance.version };
    });
  }

  /** Sets the owner key used for persistence and loads what was saved under it. */
  protected attachPersistence(key: string): void {
    const store = this.hub.store;
    this.persistKey = key;
    if (store === undefined) return;
    this.core.hold();
    this.persistLoaded = (async () => {
      try {
        const saved = await store.load(key);
        if (saved === undefined) return;
        this.persistValues = saved.values;
        for (const o of saved.objects) {
          this.store.restore({ key: o.key, kind: o.kind, layer: o.layer, lifetime: 'persistent', scope: o.scope, world: o.world, ttlMs: undefined, data: fromBase64Url(o.data), traits: o.traits });
        }
      } catch (e) {
        this.log.error('could not load persisted bridge state', { error: (e as Error).message });
      } finally {
        this.core.release();
      }
    })();
  }

  private schedulePersist(): void {
    if (this.hub.store === undefined || this.persistKey === undefined || this.persistTimer !== undefined) return;
    this.persistTimer = setTimeout(() => {
      this.persistTimer = undefined;
      void this.persistNow();
    }, PERSIST_DEBOUNCE_MS);
  }

  private async persistNow(): Promise<void> {
    const store = this.hub.store;
    const key = this.persistKey;
    if (store === undefined || key === undefined) return;
    await this.persistLoaded;
    const state: PersistedOwner = {
      objects: this.store.persistent().map((o) => {
        const p: PersistedOwner['objects'][number] = { key: o.key, kind: o.kind, scope: o.scope, data: toBase64Url(o.data) };
        if (o.layer !== undefined) p.layer = o.layer;
        if (o.world !== undefined) p.world = o.world;
        if (o.traits !== undefined) p.traits = o.traits;
        return p;
      }),
      values: this.persistValues,
    };
    try {
      await store.save(key, state);
    } catch (e) {
      this.log.error('could not save bridge state', { error: (e as Error).message });
    }
  }

  private kv(ext: string): KeyValueStore {
    return {
      get: async (key) => {
        await this.persistLoaded;
        return structuredClone(this.persistValues[ext]?.[key]);
      },
      set: async (key, value) => {
        await this.persistLoaded;
        (this.persistValues[ext] ??= {})[key] = structuredClone(value);
        this.schedulePersist();
      },
      delete: async (key) => {
        await this.persistLoaded;
        if (this.persistValues[ext] !== undefined) delete this.persistValues[ext][key];
        this.schedulePersist();
      },
    };
  }

  layer(name: string, options?: LayerOptions): Layer {
    return this.defaultScope.layer(name, options);
  }

  style(id: string, options: StyleOptions): void {
    this.defaultScope.style(id, options);
  }

  form<const F extends readonly FormFieldDef[], const B extends readonly FormButtonDef[] = readonly []>(definition: FormDefinition<F, B>): Promise<FormResult<F, B>> {
    return this.defaultFeatures.form(definition);
  }

  hud(panelId: string, options?: HudPanelOptions): HudPanel {
    return this.defaultFeatures.hud(panelId, options);
  }

  worldMenu<const I extends string>(options: WorldMenuOptions<I>): Promise<I> {
    return this.defaultFeatures.worldMenu(options);
  }

  bossbar(id: string, options: BossbarOptions): BossbarHandle {
    return this.defaultFeatures.bossbar(id, options);
  }

  containerView(id: string, options: ContainerViewOptions): ContainerView {
    return this.defaultFeatures.containerView(id, options);
  }

  screen(id: string, options: ScreenOptions): Screen {
    return this.defaultFeatures.screen(id, options);
  }

  /** Actions behind notification buttons, oldest first; the oldest go once there are too many. */
  private readonly notifyActions: string[] = [];
  private notifySeq = 0;

  notify(text: string, options: NotifyOptions = {}): void {
    const p: MessagePayloads['notify'] = { text, level: options.level ?? 'info' };
    if (options.icon !== undefined) p.icon = options.icon;
    if (options.sound !== undefined) p.sound = options.sound;
    const buttons = options.buttons ?? [];
    if (buttons.length > MAX_NOTIFY_BUTTONS) throw new BridgeError('invalid_message', `a notification takes at most ${MAX_NOTIFY_BUTTONS} buttons`);
    for (const b of buttons) {
      if (typeof b.label !== 'string' || b.label.trim() === '' || b.label.length > 32) throw new BridgeError('invalid_message', 'a notification button needs a label of 1 to 32 characters');
      const run = 'run' in b ? b.run : undefined;
      const suggest = 'suggest' in b ? b.suggest : undefined;
      if ((run === undefined) === (suggest === undefined)) throw new BridgeError('invalid_message', 'a notification button needs a run function or a line to suggest');
      if (run !== undefined && typeof run !== 'function') throw new BridgeError('invalid_message', 'a notification button needs a run function');
      if (suggest !== undefined && (typeof suggest !== 'string' || suggest.trim() === '' || suggest.length > 256)) throw new BridgeError('invalid_message', 'a notification button suggests a line of 1 to 256 characters');
    }
    const owner = this.core.owner;
    // A notification is about now, so nothing is kept for a session that is not open.
    if (owner === undefined || !this.core.connected) {
      this.log.debug('notification dropped: the session is not open', { text });
      return;
    }
    if (buttons.length > 0) {
      p.buttons = buttons.map((b) => {
        if ('suggest' in b) return { label: b.label, suggest: b.suggest };
        const id = `bridge-notify-${++this.notifySeq}`;
        this.defaultFeatures.actions.register(id, { label: b.label, target: { type: 'notification' }, scope: 'global', run: (_t, ctx) => b.run(ctx) });
        this.notifyActions.push(id);
        return { label: b.label, action: `${owner}/user:default/~action/${id}` };
      });
      while (this.notifyActions.length > MAX_NOTIFY_ACTIONS) this.defaultFeatures.actions.unregister(this.notifyActions.shift()!);
    }
    this.core.sendIfOpen('notify', this.core.link.encode('notify', p));
  }

  status(status: unknown): void {
    this.core.sendLatest('presence.status', this.core.link.encode('presence.status', { status }));
  }

  supports(spec: string): boolean {
    return supportsSpec(spec, this.core.link.capabilities);
  }

  get permissions(): ReadonlySet<string> {
    return this.core.link.permissions;
  }

  can(permission: Permission): boolean {
    return this.core.link.permissions.has(permission);
  }

  get limits(): BridgeLimits {
    return this.core.link.limits;
  }

  on<E extends keyof BridgeEvents>(event: E, fn: (...args: BridgeEvents[E]) => void): () => void {
    return this.events.on(event, fn);
  }

  get<A>(extension: ExtensionRefLike<A>): A | undefined;
  get<A = unknown>(id: string): A | undefined;
  get(extension: string | ExtensionRefLike<unknown>): unknown {
    return this.loaded.get(typeof extension === 'string' ? extension : extension.id)?.api;
  }

  has(id: string): boolean {
    return this.loaded.has(id);
  }

  async use(...uses: ExtensionUse[]): Promise<void> {
    if (uses.length === 0) return;
    const order = orderForLoad(uses, new Set(this.loaded.keys()));
    this.hub.register(order.map((i) => i.definition));
    const options = await Promise.all(order.map((i) => validateOptions(i.definition, i.input)));
    if (this.core.link.state === 'ready') {
      for (const i of order) {
        const missing = missingCapabilities(i.definition.requires?.mod ?? [], this.core.link.capabilities);
        if (missing.length > 0) throw new BridgeError('unsupported', `extension ${i.id} needs mod capabilities ${missing.join(', ')}`, { details: { missing } });
      }
    }
    for (let n = 0; n < order.length; n++) {
      const inst = order[n]!;
      const loaded = this.createLoaded(inst);
      this.loaded.set(inst.id, loaded);
      this.loadOrder.push(inst.id);
      try {
        loaded.api = await (inst.definition.setup as (ctx: ExtensionContext, o: unknown) => unknown)(loaded.ctx, options[n]);
      } catch (e) {
        this.unloadOne(inst.id);
        throw asBridgeError(e);
      }
    }
  }

  async unload(id: string): Promise<void> {
    if (!this.loaded.has(id)) return;
    const victims = new Set([id]);
    let grew = true;
    while (grew) {
      grew = false;
      for (const l of this.loaded.values()) {
        if (victims.has(l.instance.id)) continue;
        if (l.deps.some((d) => victims.has(d))) {
          victims.add(l.instance.id);
          grew = true;
        }
      }
    }
    // Unload dependents first, i.e. reverse load order.
    for (const v of [...this.loadOrder].reverse()) if (victims.has(v)) this.unloadOne(v);
    await Promise.resolve();
  }

  private unloadOne(id: string): void {
    const l = this.loaded.get(id);
    if (l === undefined) return;
    l.controller.abort(new BridgeError('cancelled', `extension ${id} unloaded`));
    l.scope.removeAll();
    l.features.dispose();
    this.loaded.delete(id);
    this.loadOrder = this.loadOrder.filter((x) => x !== id);
  }

  private checkRequirements(): void {
    for (const id of [...this.loadOrder]) {
      const l = this.loaded.get(id);
      if (l === undefined) continue;
      const missing = missingCapabilities(l.instance.definition.requires?.mod ?? [], this.core.link.capabilities);
      if (missing.length === 0) continue;
      this.reportError(new BridgeError('unsupported', `extension ${id} needs mod capabilities ${missing.join(', ')}; unloading it`, { details: { missing } }));
      void this.unload(id);
    }
  }

  /** Callers of {@link firstOpen} still waiting. */
  private readonly firstOpenWaiters = new Set<(error: BridgeError | undefined) => void>();

  /** Resolves when the session first opens; rejects when the mod refuses the open for good or the session closes. */
  private firstOpen(): Promise<void> {
    if (this.core.connected) return Promise.resolve();
    return new Promise<void>((resolve, reject) => {
      this.firstOpenWaiters.add((error) => (error === undefined ? resolve() : reject(error)));
    });
  }

  private settleFirstOpen(error: BridgeError | undefined): void {
    const waiters = [...this.firstOpenWaiters];
    this.firstOpenWaiters.clear();
    for (const w of waiters) w(error);
  }

  private whenOpen(signal: AbortSignal, timeoutMs: number): Promise<void> {
    if (this.core.connected) return Promise.resolve();
    return waitFor<void>(
      signal,
      timeoutMs,
      'cancelled while waiting for the bridge session',
      (s) => s.fail(new BridgeError('timeout', `the bridge session did not open within ${timeoutMs} ms`, { retryable: true })),
      (s) => this.events.on('connected', () => s.ok()),
    );
  }

  private hook(event: 'connected' | 'disconnected' | 'attached' | 'detached' | 'worldMatched' | 'worldUnmatched', fn: () => void, signal: AbortSignal): () => void {
    const off = this.events.on(event, fn);
    signal.addEventListener('abort', off, { once: true });
    return off;
  }

  private watch(check: () => boolean, fn: (signal: AbortSignal) => void | (() => void), signal: AbortSignal): () => void {
    const w: Watcher = { check, fn, active: undefined, cleanup: undefined };
    this.watchers.add(w);
    const stop = (): void => {
      if (!this.watchers.delete(w)) return;
      this.stopWatcher(w);
    };
    signal.addEventListener('abort', stop, { once: true });
    this.evaluate(w);
    return stop;
  }

  /** The bot for extension contexts; controllers return `null`. */
  protected abstract contextBot(): BotLike | null;

  private createLoaded(inst: ExtensionInstance): Loaded {
    const controller = new AbortController();
    const signal = controller.signal;
    const scope = new ScopeObjects(this, inst.id);
    const features = new ScopeFeatures(this.inputHost, scope, signal, this.kv(inst.id));
    const shared = new SharedApiImpl(this.sharedState, this.core, this.registry(), inst.id, signal, () => this.currentWorld());
    const deps = dependencyIds(inst.definition);
    const self = this;
    const ctx: ExtensionContext = {
      id: inst.id,
      bot: this.contextBot(),
      get session() {
        return self.session;
      },
      get state() {
        return self.state;
      },
      get focused() {
        return self.focusedNow;
      },
      vars: new ScopeVars(scope),
      requestFocus: (reason) => this.requestFocus(reason),
      transaction: (fn) => this.transaction(fn),
      onFocus: (fn) => {
        const off = this.events.on('focus', fn);
        signal.addEventListener('abort', off, { once: true });
        return off;
      },
      layer: (name, o) => scope.layer(name, o),
      style: (id, o) => scope.style(id, o),
      volume: (o) => new VolumeImpl(o),
      objects: scope,
      shared,
      samples: this.sampleHub.scope(signal),
      query: createQueries(this.core, signal),
      pick: features.pick,
      prompt: features.prompt,
      form: (definition) => features.form(definition),
      commands: features.commands,
      orders: features.orders,
      crew: features.crew,
      actions: features.actions,
      intercepts: features.intercepts,
      keybinds: features.keybinds,
      tools: features.tools,
      settings: features.settings,
      hud: (panelId, o) => features.hud(panelId, o),
      bossbar: (id, o) => features.bossbar(id, o),
      containerView: (id, o) => features.containerView(id, o),
      screen: (id, o) => features.screen(id, o),
      menu: features.menu,
      chat: features.chat,
      effects: features.effects,
      worldMenu: (o) => features.worldMenu(o),
      notify: (text, o) => this.notify(text, o),
      on: <A extends unknown[]>(emitter: EmitterLike, event: string, fn: (...args: A) => void) => {
        const listener = fn as unknown as (...args: any[]) => void;
        emitter.on(event, listener);
        const off = (): void => {
          if (emitter.off !== undefined) emitter.off(event, listener);
          else emitter.removeListener?.(event, listener);
        };
        signal.addEventListener('abort', off, { once: true });
        return off;
      },
      onConnected: (fn) => this.hook('connected', fn, signal),
      onDisconnected: (fn) => this.hook('disconnected', fn, signal),
      onAttached: (fn) => this.hook('attached', fn, signal),
      onDetached: (fn) => this.hook('detached', fn, signal),
      onWorldMatched: (fn) => this.hook('worldMatched', fn, signal),
      onWorldUnmatched: (fn) => this.hook('worldUnmatched', fn, signal),
      onInterest: (fn: (interest: Interest) => void) => {
        const off = this.events.on('interest', fn);
        signal.addEventListener('abort', off, { once: true });
        return off;
      },
      onFrame: (fn: (frame: RawFrame) => void) => {
        // Frames carry no link tag, so a recording follows one connection: the primary link.
        const off = this.core.link.tap(fn);
        signal.addEventListener('abort', off, { once: true });
        return off;
      },
      whenVisible: (layer, fn) => {
        const check = typeof layer === 'string' ? () => this.layerVisible(inst.id, layer, this.declaredDefaultVisible(inst.id, layer)) : () => layer.isVisible();
        return this.watch(check, fn, signal);
      },
      store: this.kv(inst.id),
      require: <A>(extension: string | ExtensionRefLike<A>): A => {
        const id = typeof extension === 'string' ? extension : extension.id;
        if (!deps.includes(id)) throw new BridgeError('invalid_message', `${inst.id} must list ${id} in requires.extensions to require it`);
        const dep = this.loaded.get(id);
        if (dep === undefined) throw new BridgeError('invalid_message', `extension ${id} is not loaded`);
        return dep.api as A;
      },
      optional: <A>(extension: string | ExtensionRefLike<A>): A | undefined => {
        const id = typeof extension === 'string' ? extension : extension.id;
        if (!(inst.definition.requires?.optionalExtensions ?? []).includes(id)) {
          throw new BridgeError('invalid_message', `${inst.id} must list ${id} in requires.optionalExtensions to use it`);
        }
        return this.loaded.get(id)?.api as A | undefined;
      },
      message: <T>(type: MessageType<T>): ExtensionChannel<T> => {
        const listed = (inst.definition.messages ?? []).some((m) => typeof m !== 'string' && m.name === type.name);
        if (!listed || extensionMessage(type.name) === undefined) {
          throw new BridgeError('invalid_message', `${inst.id} must list ${type.name} with its codec in messages to use it`);
        }
        return {
          name: type.name,
          send: (payload) => {
            if (signal.aborted) return;
            const bytes = encodeExtension(type, payload);
            if (type.delivery === 'latest') this.core.sendLatest(type.name, bytes);
            else if (type.delivery === 'unreliable') this.core.sendUnreliable(type.name, bytes);
            else this.core.send(type.name, bytes);
          },
          on: (fn) => {
            let set = this.channelListeners.get(type.name);
            if (set === undefined) this.channelListeners.set(type.name, (set = new Set()));
            const listener = fn as (payload: unknown) => void;
            set.add(listener);
            const off = (): void => {
              set.delete(listener);
            };
            signal.addEventListener('abort', off, { once: true });
            return off;
          },
        };
      },
      supports: (spec) => this.supports(spec),
      signal,
      log: tagLogger(this.log, `[${inst.id}]`),
    };
    return { instance: inst, ctx, controller, api: undefined, scope, features, deps };
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    for (const id of [...this.loadOrder].reverse()) {
      const l = this.loaded.get(id);
      l?.controller.abort(new BridgeError('cancelled', 'session closed'));
    }
    this.lifetime.abort(new BridgeError('cancelled', 'session closed'));
    for (const w of [...this.watchers]) this.stopWatcher(w);
    this.watchers.clear();
    if (this.persistTimer !== undefined) {
      clearTimeout(this.persistTimer);
      this.persistTimer = undefined;
      await this.persistNow();
    }
    for (const off of this.linkOffs.values()) off();
    this.linkOffs.clear();
    this.settleFirstOpen(new BridgeError('cancelled', 'session closed'));
    await this.core.close();
    this.hub.forget(this);
    this.events.removeAll();
  }
}

/** Normalises a mineflayer dimension name to a resource key (`overworld` becomes `minecraft:overworld`). */
export function dimensionKey(dimension: string | undefined): string | undefined {
  if (dimension === undefined || dimension === '') return undefined;
  return dimension.includes(':') ? dimension : `minecraft:${dimension}`;
}

/** Server part of a world key and bot identity: lowercase host plus port, default 25565. */
export function serverKey(options: BotOptionsLike | undefined): string {
  const host = (options?.host ?? 'localhost').toLowerCase();
  return `${host}:${options?.port ?? 25565}`;
}

/** `bot.bridge`: a bot session plus world reporting. */
export class BotBridgeImpl extends SessionImpl implements BotBridge {
  readonly bot: BotLike;
  private readonly botOptions: BotOptionsLike | undefined;
  private readonly server: string;
  private botWorld: WorldKey | undefined;
  /** The world's name from the last login or respawn packet, such as `minecraft:creative`. */
  private levelName: string | undefined;
  private lastPositionAt = 0;
  private positionTimer: NodeJS.Timeout | undefined;
  private readonly offs: (() => void)[] = [];

  constructor(hub: HubContext, bot: BotLike, botOptions: BotOptionsLike | undefined, name: string | undefined, uses: readonly ExtensionUse[]) {
    super(hub, 'bot', name ?? bot.username ?? botOptions?.username ?? 'bot', uses, tagLogger(hub.logger, `[${name ?? bot.username ?? botOptions?.username ?? 'bot'}]`));
    this.bot = bot;
    this.botOptions = botOptions;
    this.server = serverKey(botOptions);
    const listen = (event: string, fn: (...args: any[]) => void): void => {
      bot.on(event, fn);
      this.offs.push(() => {
        if (bot.off !== undefined) bot.off(event, fn);
        else bot.removeListener?.(event, fn);
      });
    };
    // The world's own name, as the player's game has it: mineflayer keeps only the dimension type.
    const client = bot._client;
    if (typeof client?.on === 'function') {
      const named = (packet: { worldState?: { name?: unknown }; worldName?: unknown } | undefined): void => {
        const name = packet?.worldState?.name ?? packet?.worldName;
        if (typeof name !== 'string' || name === '') return;
        this.levelName = dimensionKey(name);
        // mineflayer's own handler ran first and the bot's events already fired: report the new name.
        if (this.bot.game?.dimension !== undefined) this.reportWorld();
      };
      client.on('login', named);
      client.on('respawn', named);
      this.offs.push(() => {
        client.removeListener?.('login', named);
        client.removeListener?.('respawn', named);
      });
    }
    if (bot.username !== undefined && bot.username !== '') this.resolveIdentity();
    else listen('login', () => this.resolveIdentity());
    listen('spawn', () => this.reportWorld());
    listen('respawn', () => this.reportWorld());
    listen('game', () => this.reportWorld());
    listen('move', () => this.reportPosition());
    listen('end', () => void this.close());
    if (bot.game?.dimension !== undefined) this.reportWorld();
  }

  protected contextBot(): BotLike | null {
    return this.bot;
  }

  override currentWorld(): WorldKey | undefined {
    return this.botWorld;
  }

  get world(): WorldKey | undefined {
    return this.botWorld;
  }

  private resolveIdentity(): void {
    if (this.core.identity !== undefined) return;
    const username = this.bot.username ?? this.botOptions?.username;
    if (username === undefined || username === '') return;
    const identity: BotIdentity = { username, server: this.server };
    // The UUID identifies online-mode bots; offline UUIDs are derived from the name and add nothing.
    const uuid = this.bot.player?.uuid;
    if (this.botOptions?.auth === 'microsoft' && uuid !== undefined) identity.uuid = uuid;
    const owner = `bot:${identity.uuid ?? `${username.toLowerCase()}@${this.server}`}`;
    this.attachPersistence(owner);
    this.core.setIdentity(identity);
  }

  private position(): { x: number; y: number; z: number } | undefined {
    const p = this.bot.entity?.position;
    return p === undefined ? undefined : toVec3(p, 'bot position');
  }

  private reportWorld(): void {
    const dimension = this.levelName ?? dimensionKey(this.bot.game?.dimension ?? undefined);
    if (dimension === undefined) return;
    const world: WorldKey = { server: this.server, dimension };
    if (this.botWorld !== undefined && this.botWorld.dimension === world.dimension && this.botWorld.server === world.server) return;
    const previous = this.botWorld;
    this.botWorld = world;
    this.sendReport();
    this.events.emit('world', world, previous);
  }

  private sendReport(): void {
    if (this.botWorld === undefined) return;
    const p: MessagePayloads['world.report'] = { world: this.botWorld, position: this.position() };
    this.lastPositionAt = Date.now();
    this.core.sendLatest('world.report', this.core.link.encode('world.report', p));
  }

  /** At most 2 Hz and only while attached; the last move in a burst is sent late. */
  private reportPosition(): void {
    if (!this.state.attached || this.botWorld === undefined) return;
    const wait = this.lastPositionAt + POSITION_INTERVAL_MS - Date.now();
    if (wait <= 0) {
      this.sendReport();
      return;
    }
    this.positionTimer ??= setTimeout(() => {
      this.positionTimer = undefined;
      if (this.state.attached) this.sendReport();
    }, wait);
  }

  protected override onAttach(): void {
    this.reportPosition();
  }

  override async close(): Promise<void> {
    clearTimeout(this.positionTimer);
    for (const off of this.offs.splice(0)) off();
    // `bot.bridge` stays in place: a closed bridge ignores calls, which is kinder than `undefined`.
    await super.close();
  }
}

/** A controller session. */
export class ControllerImpl extends SessionImpl implements BridgeController {
  readonly name: string;

  constructor(hub: HubContext, name: string, uses: readonly ExtensionUse[]) {
    if (name === '' || name.includes('/')) throw new BridgeError('invalid_message', `controller name must be non-empty without /: ${name}`);
    super(hub, 'controller', name, uses, tagLogger(hub.logger, `[ctl:${name}]`));
    this.name = name;
    // The owner includes the authenticated device id, known only per connection; persist by name.
    this.attachPersistence(`ctl:${name}`);
  }

  protected contextBot(): BotLike | null {
    return null;
  }
}
