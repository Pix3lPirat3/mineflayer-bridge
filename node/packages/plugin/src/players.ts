import { AsyncLocalStorage } from 'node:async_hooks';
import { BridgeError, type BotIdentity, type Detail, type ExtensionRef, type MessageName, type MessagePayloads, type SessionKind, type WorldKey } from '@mineflayer-bridge/protocol';
import type { BlobLimits } from './blobs.ts';
import type { Link } from './link.ts';
import type { CancelMessage, SessionCore } from './session.ts';
import type { BridgePlayer, PlayerChange } from './types.ts';

/**
 * One link of a hub: the connection to one player's mod (a process may connect to several mods).
 * Every bot and controller session of the hub has one protocol session per link.
 */
export interface HubLink {
  /** Stable id within the hub: the id given in the link options, otherwise `default` or `link-<n>`. */
  readonly id: string;
  readonly link: Link;
}

/**
 * The player a piece of code acts for. Input handlers run inside the link their input came from, so
 * what they send (the invocation result, a prompt, a screen open, a notification) goes to that
 * player only; {@link BridgePlayer.run} enters a link explicitly and `everyone` leaves it.
 */
const origin = new AsyncLocalStorage<HubLink>();

/** The link the current code acts for, or `undefined` outside handlers and `run` calls. */
export function currentOrigin(): HubLink | undefined {
  return origin.getStore();
}

/** Runs `fn` acting for `link`: one-off messages and requests it makes go to that player only. */
export function runAs<T>(link: HubLink, fn: () => T): T {
  return origin.run(link, fn);
}

/** Runs `fn` acting for nobody in particular: one-off messages and requests go to every linked player. */
export function runForEveryone<T>(fn: () => T): T {
  return origin.exit(fn);
}

/** Per-link player state of one session: what that player's mod last reported. */
export class PlayerImpl implements BridgePlayer {
  connected = false;
  attached = false;
  worldMatched = false;
  playerWorld: WorldKey | null = null;
  name: string | undefined = undefined;
  uuid: string | undefined = undefined;
  focused = false;
  inWorld = false;
  alive = true;
  away = false;
  windowFocused = true;
  detail: Detail = 'full';
  layers = new Map<string, boolean>();

  constructor(readonly hubLink: HubLink) {}

  get id(): string {
    return this.hubLink.id;
  }

  get capabilities(): readonly ExtensionRef[] {
    return this.hubLink.link.capabilities;
  }

  get permissions(): ReadonlySet<string> {
    return this.hubLink.link.permissions;
  }

  run<T>(fn: () => T): T {
    return runAs(this.hubLink, fn);
  }

  /** Forgets what the mod reported; the session on this link is not open. */
  reset(): void {
    this.connected = false;
    this.attached = false;
    this.worldMatched = false;
    this.focused = false;
    this.inWorld = false;
    this.alive = true;
    this.away = false;
    this.windowFocused = true;
  }
}

/**
 * The protocol sessions of one bot or controller, one per hub link, behind the interface features
 * use. Declared state reaches every link through each session's own store cursor; this class decides
 * where everything else goes:
 *
 * - session setup (holds, identity, transactions) and latest-wins values go to every link;
 * - one-off messages and input requests go to the current origin's link (see {@link currentOrigin}),
 *   or to every link outside one. A request sent to several links settles with the first answer and
 *   withdraws the others;
 * - shared-object subscriptions and ops use the primary link, the first one of the hub.
 */
export class CoreSet {
  private readonly entries = new Map<HubLink, SessionCore>();
  private holds = 0;
  private txDepth = 0;
  private identityValue: BotIdentity | undefined;
  private readonly subscriptions: [kinds: readonly string[], extensions: readonly string[]][] = [];
  private readonly latest = new Map<string, Uint8Array>();
  /** The last primary link, kept so encoding and limits work while no link is present. */
  private lastLink: Link | undefined;
  private closed = false;

  constructor(
    readonly kind: SessionKind,
    private readonly make: (link: HubLink) => SessionCore,
  ) {}

  /** Adds the session for a new hub link, bringing it to the state the others are in. */
  add(l: HubLink): SessionCore {
    const core = this.make(l);
    const primary = this.entries.size === 0;
    this.entries.set(l, core);
    this.lastLink ??= l.link;
    for (let i = 0; i < this.holds; i++) core.hold();
    for (let i = 0; i < this.txDepth; i++) core.beginTx();
    if (this.identityValue !== undefined) core.identity = this.identityValue;
    if (primary) for (const [k, e] of this.subscriptions) core.subscribe(k, e);
    for (const [t, p] of this.latest) core.sendLatest(t, p);
    if (this.closed) void core.close();
    else l.link.add(core);
    return core;
  }

  /** Ends the session on one link (`session.close`, so that mod drops its objects). */
  async remove(l: HubLink): Promise<void> {
    const core = this.entries.get(l);
    if (core === undefined) return;
    const wasPrimary = this.primaryLink === l;
    this.entries.delete(l);
    await core.close();
    const next = this.primaryEntry;
    if (wasPrimary && next !== undefined) {
      this.lastLink = next[0].link;
      for (const [k, e] of this.subscriptions) next[1].subscribe(k, e);
    }
  }

  get(l: HubLink): SessionCore | undefined {
    return this.entries.get(l);
  }

  cores(): SessionCore[] {
    return [...this.entries.values()];
  }

  private get primaryEntry(): [HubLink, SessionCore] | undefined {
    for (const e of this.entries) return e;
    return undefined;
  }

  get primaryLink(): HubLink | undefined {
    return this.primaryEntry?.[0];
  }

  get primary(): SessionCore | undefined {
    return this.primaryEntry?.[1];
  }

  /** The primary link's connection, for encoding, registry lookups and limits. */
  get link(): Link {
    const l = this.primaryEntry?.[0].link ?? this.lastLink;
    if (l === undefined) throw new BridgeError('internal', 'the session has no link');
    return l;
  }

  /** Where one-off messages go: the origin's session when it is one of ours, otherwise every session. */
  private targets(): SessionCore[] {
    const o = currentOrigin();
    const core = o === undefined ? undefined : this.entries.get(o);
    return core !== undefined ? [core] : this.cores();
  }

  /** The session of the current origin, or the primary one. */
  private focus(): SessionCore | undefined {
    const o = currentOrigin();
    return (o === undefined ? undefined : this.entries.get(o)) ?? this.primary;
  }

  get connected(): boolean {
    for (const c of this.entries.values()) if (c.connected) return true;
    return false;
  }

  /** Owner key of the origin's session (or the primary's); owners differ per link only for controllers on differently paired mods. */
  get owner(): string | undefined {
    return this.focus()?.owner;
  }

  get identity(): BotIdentity | undefined {
    return this.identityValue;
  }

  get sessionId(): number {
    return this.primary?.sessionId ?? 0;
  }

  get superseded(): boolean {
    return this.primary?.superseded ?? false;
  }

  blobLimits(): BlobLimits {
    const p = this.primary;
    if (p !== undefined) return p.blobLimits();
    return { maxBlobBytes: this.link.maxBlobBytes, streamBytesInFlight: this.link.limits.blobInFlightBytes };
  }

  hold(): void {
    this.holds++;
    for (const c of this.entries.values()) c.hold();
  }

  release(): void {
    this.holds--;
    for (const c of this.cores()) c.release();
  }

  setIdentity(identity: BotIdentity): void {
    this.identityValue = identity;
    for (const c of this.cores()) c.setIdentity(identity);
  }

  beginTx(): void {
    this.txDepth++;
    for (const c of this.entries.values()) c.beginTx();
  }

  endTx(): void {
    if (this.txDepth === 0) return;
    this.txDepth--;
    for (const c of this.entries.values()) c.endTx();
  }

  /** A reliable one-off message to the origin's player, or to every player outside an origin. */
  send(type: MessageName | (string & {}), payload: Uint8Array): void {
    for (const c of this.targets()) c.send(type, payload);
  }

  /**
   * A one-off message that is only worth showing now, such as a notification: players whose session
   * is not open do not get it later. Returns whether anybody will get it.
   */
  sendIfOpen(type: MessageName | (string & {}), payload: Uint8Array): boolean {
    let sent = false;
    for (const c of this.targets()) sent = c.sendIfOpen(type, payload) || sent;
    return sent;
  }

  sendUnreliable(type: MessageName | (string & {}), payload: Uint8Array): void {
    for (const c of this.targets()) c.sendUnreliable(type, payload);
  }

  /** A latest-wins value; it describes the bot, so every link gets it, including links added later. */
  sendLatest(type: MessageName | (string & {}), payload: Uint8Array): void {
    this.latest.set(type, payload);
    for (const c of this.entries.values()) c.sendLatest(type, payload);
  }

  /** Shared objects live in each mod, so the subscription is made on the primary link only. */
  subscribe(kinds: readonly string[], extensions: readonly string[]): void {
    this.subscriptions.push([[...kinds], [...extensions]]);
    this.primary?.subscribe(kinds, extensions);
  }

  /** A request to the primary link only (shared-object ops). */
  requestPrimary<N extends MessageName>(type: N, payload: MessagePayloads[N], timeoutMs: number, signal?: AbortSignal): Promise<unknown> {
    const p = this.primary;
    if (p === undefined) return Promise.reject(new BridgeError('disconnected', 'the session has no link', { retryable: true }));
    return p.request(type, payload, timeoutMs, signal);
  }

  /**
   * An input request to the origin's player, or to every player outside an origin. Sent to several
   * players, the first answer wins and the others are withdrawn with `cancel`; it rejects only when
   * every player's request failed, with the first failure.
   */
  request<N extends MessageName>(type: N, payload: MessagePayloads[N], timeoutMs: number, signal?: AbortSignal, cancel?: CancelMessage): Promise<unknown> {
    const targets = this.targets();
    const open = targets.filter((c) => c.connected);
    const use = open.length > 0 ? open : targets;
    if (use.length === 0) return Promise.reject(new BridgeError('disconnected', 'the session has no link', { retryable: true }));
    if (use.length === 1) return use[0]!.request(type, payload, timeoutMs, signal, cancel);
    const losers = new AbortController();
    const combined = signal === undefined ? losers.signal : AbortSignal.any([signal, losers.signal]);
    return new Promise((resolve, reject) => {
      let left = use.length;
      let first: unknown;
      let done = false;
      for (const c of use) {
        c.request(type, payload, timeoutMs, combined, cancel).then(
          (v) => {
            if (done) return;
            done = true;
            losers.abort();
            resolve(v);
          },
          (e: unknown) => {
            if (done) return;
            first ??= e;
            if (--left > 0) return;
            done = true;
            reject(first as Error);
          },
        );
      }
    });
  }

  async close(reason?: string): Promise<void> {
    this.closed = true;
    for (const c of this.cores()) await c.close(reason);
  }
}

/** What a new `player.context` changes about a player, in the order the `player` event reports it. */
export function playerChanges(p: PlayerImpl, c: MessagePayloads['player.context']): PlayerChange[] {
  const out: PlayerChange[] = [];
  const alive = c.alive !== false;
  const away = c.away === true;
  const focused = c.windowFocused !== false;
  if (!p.inWorld && c.inWorld) out.push('joined');
  else if (p.inWorld && !c.inWorld) out.push('left');
  else if (c.inWorld && !sameWorld(p.playerWorld, c.world ?? null)) out.push('world');
  if (c.inWorld && p.alive !== alive) out.push(alive ? 'respawned' : 'died');
  if (p.away !== away) out.push(away ? 'away' : 'back');
  if (p.windowFocused !== focused) out.push(focused ? 'focus' : 'blur');
  return out;
}

function sameWorld(a: WorldKey | null, b: WorldKey | null): boolean {
  return a === b || (a !== null && b !== null && a.server === b.server && a.dimension === b.dimension && a.backend === b.backend);
}
