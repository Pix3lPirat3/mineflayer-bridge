import { BridgeError } from '@mineflayer-bridge/protocol';
import { wireName } from './ext-wire.ts';
import { BotBridgeImpl, ControllerImpl, type HubContext, type SessionImpl } from './bridge.ts';
import { tickEnd, type TickEndBotLike } from './compat.ts';
import { toInstance } from './extensions.ts';
import { FIRST_PARTY_WIRE } from './first-party-wire.ts';
import { FileCredentialStore, JsonFileStore } from './files.ts';
import { Link } from './link.ts';
import type { HubLink } from './players.ts';
import type {
  BotLike,
  BotOptionsLike,
  BotPluginOptions,
  BridgeController,
  BridgeHub,
  BridgeLink,
  BridgeOptions,
  BridgePlugin,
  ControllerOptions,
  CredentialStore,
  ExtensionDefinition,
  HubEvents,
  HubOptions,
  LinkOptions,
  Logger,
  OptionsSchema,
} from './types.ts';
import { defaultLogger, Emitter } from './util.ts';

const HUB_EVENTS: readonly (keyof HubEvents)[] = ['connected', 'disconnected', 'error', 'paired', 'permissions'];

/** One link of a hub: the connection to one player's mod. */
class LinkHandle implements BridgeLink, HubLink {
  constructor(
    readonly id: string,
    readonly link: Link,
    private readonly hub: Hub,
  ) {}

  get connected(): boolean {
    return this.link.state === 'ready';
  }

  on<E extends keyof HubEvents>(event: E, fn: (...args: HubEvents[E]) => void): () => void {
    return this.link.events.on(event, fn);
  }

  close(): Promise<void> {
    return this.hub.unlink(this);
  }
}

class Hub implements BridgeHub {
  private readonly ctx: HubContext;
  private readonly options: HubOptions;
  private readonly logger: Logger;
  private readonly credentials: CredentialStore;
  private readonly hubLinks: LinkHandle[] = [];
  private readonly events = new Emitter<HubEvents>();
  private readonly sessions = new Set<SessionImpl>();
  private readonly definitions = new Map<string, ExtensionDefinition<OptionsSchema | undefined, unknown>>();
  private closed = false;

  constructor(options: HubOptions) {
    this.options = options;
    this.logger = options.logger ?? defaultLogger;
    this.credentials = typeof options.credentials === 'object' ? options.credentials : new FileCredentialStore(options.credentials);
    for (const e of options.extensions ?? []) this.register([toInstance(e).definition]);
    this.ctx = {
      links: () => this.hubLinks,
      logger: this.logger,
      store: typeof options.store === 'string' ? new JsonFileStore(options.store) : options.store,
      surfaces: options.surfaces,
      objectCount: () => {
        let n = 0;
        for (const s of this.sessions) n += s.store.size;
        return n;
      },
      register: (defs) => this.register(defs),
      forget: (s) => this.sessions.delete(s),
    };
    // The top-level connection options are the `default` link, unless only `links` are given.
    const top = options.endpoint !== undefined || options.endpointFile !== undefined || options.pair !== undefined;
    if (options.links === undefined || top) {
      const first: LinkOptions = { id: 'default' };
      if (options.endpoint !== undefined) first.endpoint = options.endpoint;
      if (options.endpointFile !== undefined) first.endpointFile = options.endpointFile;
      if (options.pair !== undefined) first.pair = options.pair;
      this.link(first);
    }
    for (const l of options.links ?? []) this.link(l);
  }

  private register(defs: readonly ExtensionDefinition<OptionsSchema | undefined, unknown>[]): void {
    let added = false;
    for (const d of defs) {
      if (!this.definitions.has(d.id)) added = (d.messages?.length ?? 0) + (d.kinds?.length ?? 0) > 0 || added;
      this.definitions.set(d.id, d);
    }
    // The link reconnects only for names its hello did not offer; first-party ones always are.
    if (added) for (const l of this.hubLinks) l.link.namesChanged();
  }

  get connected(): boolean {
    return this.hubLinks.some((l) => l.connected);
  }

  get links(): readonly BridgeLink[] {
    return [...this.hubLinks];
  }

  link(options: LinkOptions): BridgeLink {
    if (this.closed) throw new BridgeError('invalid_message', 'the hub is closed');
    const id = options.id ?? this.freeId();
    if (typeof id !== 'string' || id === '') throw new BridgeError('invalid_message', 'link id must be a non-empty string');
    if (this.hubLinks.some((l) => l.id === id)) throw new BridgeError('invalid_message', `the hub already has a link ${id}`);
    const credentials = options.credentials === undefined ? this.credentials : typeof options.credentials === 'object' ? options.credentials : new FileCredentialStore(options.credentials);
    const linkOptions: ConstructorParameters<typeof Link>[0] = {
      credentials,
      reconnect: options.reconnect ?? this.options.reconnect ?? {},
      logger: this.logger,
      names: () => {
        const kinds = new Set<string>(FIRST_PARTY_WIRE.kinds);
        const messages = new Set<string>(FIRST_PARTY_WIRE.messages);
        for (const d of this.definitions.values()) {
          for (const k of d.kinds ?? []) kinds.add(wireName(k));
          for (const m of d.messages ?? []) messages.add(wireName(m));
        }
        return { kinds: [...kinds], messages: [...messages] };
      },
    };
    if (options.endpoint !== undefined) linkOptions.endpoint = options.endpoint;
    if (options.endpointFile !== undefined) linkOptions.endpointFile = options.endpointFile;
    if (options.pair !== undefined) linkOptions.pair = options.pair;
    const remoteFallback = options.remoteFallback ?? this.options.remoteFallback;
    if (remoteFallback !== undefined) linkOptions.remoteFallback = remoteFallback;
    const allowInsecure = options.allowInsecure ?? this.options.allowInsecure;
    if (allowInsecure !== undefined) linkOptions.allowInsecure = allowInsecure;
    const deviceName = options.deviceName ?? this.options.deviceName;
    if (deviceName !== undefined) linkOptions.deviceName = deviceName;
    const handle = new LinkHandle(id, new Link(linkOptions), this);
    for (const event of HUB_EVENTS) {
      handle.link.events.on(event, (...args: HubEvents[typeof event]) => (this.events.emit as (e: keyof HubEvents, ...a: unknown[]) => void)(event, ...args));
    }
    // This forwarder is the link's first `error` listener; with no other one, on the link or the
    // hub, the error would be lost, so it is logged.
    handle.link.events.on('error', (error) => {
      if (this.events.listenerCount('error') === 0 && handle.link.events.listenerCount('error') <= 2) this.logger.error(error.message);
    });
    this.hubLinks.push(handle);
    for (const s of this.sessions) s.addLink(handle);
    return handle;
  }

  private freeId(): string {
    for (let n = this.hubLinks.length; ; n++) {
      const id = `link-${n}`;
      if (!this.hubLinks.some((l) => l.id === id)) return id;
    }
  }

  /** Ends every session on one link and closes it; called by {@link LinkHandle.close}. */
  async unlink(handle: LinkHandle): Promise<void> {
    const i = this.hubLinks.indexOf(handle);
    if (i < 0) return;
    this.hubLinks.splice(i, 1);
    for (const s of [...this.sessions]) await s.removeLink(handle);
    await handle.link.close();
  }

  plugin(options: BotPluginOptions = {}): BridgePlugin {
    return (bot: BotLike, botOptions?: BotOptionsLike) => {
      const uses = [...(this.options.extensions ?? []), ...(options.extensions ?? [])];
      for (const e of options.extensions ?? []) this.register([toInstance(e).definition]);
      if (options.tickEnd !== false) tickEnd(bot as unknown as TickEndBotLike);
      const session = new BotBridgeImpl(this.ctx, bot, botOptions, options.name, uses);
      this.sessions.add(session);
      bot.bridge = session;
    };
  }

  controller(options: ControllerOptions): BridgeController {
    for (const e of options.extensions ?? []) this.register([toInstance(e).definition]);
    const session = new ControllerImpl(this.ctx, options.name, [...(this.options.extensions ?? []), ...(options.extensions ?? [])]);
    this.sessions.add(session);
    return session;
  }

  on<E extends keyof HubEvents>(event: E, fn: (...args: HubEvents[E]) => void): () => void {
    return this.events.on(event, fn);
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    for (const s of [...this.sessions]) await s.close();
    for (const l of this.hubLinks.splice(0)) await l.link.close();
    this.events.removeAll();
  }
}

/**
 * Creates a hub: one connection per linked mod, shared by every bot and controller session of this
 * process. Extensions given here load into every session.
 *
 * By default the hub has one link, to the mod the top-level options name. `links`, or
 * {@link BridgeHub.link} later, link more players' mods: each session then has one protocol session
 * per link, with the same declared state on all of them.
 *
 * Connections start with the first session and reconnect forever with backoff unless `reconnect`
 * says otherwise.
 */
export function createBridge(options: HubOptions = {}): BridgeHub {
  return new Hub(options);
}

/**
 * A mineflayer plugin for a single bot: `bot.loadPlugin(bridge({ ... }))`. It owns its own hub,
 * which closes when the bot ends.
 */
export function bridge(options: BridgeOptions = {}): BridgePlugin {
  const { name, tickEnd: sendTickEnd, ...hubOptions } = options;
  const hub = createBridge(hubOptions);
  const plugin = hub.plugin({ ...(name === undefined ? {} : { name }), ...(sendTickEnd === undefined ? {} : { tickEnd: sendTickEnd }) });
  return (bot, botOptions) => {
    plugin(bot, botOptions);
    bot.on('end', () => void hub.close());
  };
}
