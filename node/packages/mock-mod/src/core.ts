import { randomBytes, X509Certificate } from 'node:crypto';
import type { WebSocketServer } from 'ws';
import { BridgeError, certificateFingerprint, fromBase64Url, type EnvelopeHeader, type KindName } from '@mineflayer-bridge/protocol';
import { ChatCommands } from './chat-commands.ts';
import { Connections } from './connection.ts';
import { Controls } from './controls.ts';
import { Devices } from './devices.ts';
import { Displays } from './displays.ts';
import { InputQueue } from './inputs.ts';
import { clearSampleTimer, Player } from './player.ts';
import { Queries } from './queries.ts';
import { isLive, Transport, type Conn, type Session } from './session.ts';
import { SharedObjects } from './shared.ts';
import { SurfaceGate } from './surface-gate.ts';
import { ObjectStore, TX_TIMEOUT_MS } from './store.ts';
import { Streams } from './streams.ts';
import type { Clock, DropTarget, MockModOptions, MockSessionInfo } from './types.ts';
import { bareIdentity } from './util.ts';
import { Views } from './views.ts';
import { Screens } from './screens.ts';

/** Options with the defaults the mock needs everywhere filled in. */
export type ResolvedOptions = Required<Pick<MockModOptions, 'resumeWindowMs' | 'heartbeatMs' | 'maxFrameBytes' | 'sweepIntervalMs'>> & MockModOptions;

/**
 * The state of one mock mod and the parts that act on it. Each part holds its own state and reaches
 * the others through this object; {@link MockMod} is the public face over it.
 */
export class ModCore extends Transport {
  readonly opts: ResolvedOptions;
  readonly clock: Clock;
  readonly tokenBytes: Uint8Array;
  readonly tls: boolean;
  readonly fingerprint: string;
  readonly conns = new Map<number, Conn>();
  /** Every session by resume token, including detached ones inside their resume window. */
  readonly sessions = new Map<string, Session>();
  /** Envelope request ids for requests the mock sends (`command.complete`, `obj.edit.propose`). */
  nextRequest = 1;

  readonly devices: Devices;
  readonly store: ObjectStore;
  readonly shared: SharedObjects;
  readonly streams: Streams;
  readonly player: Player;
  readonly inputs: InputQueue;
  readonly gate: SurfaceGate;
  readonly views: Views;
  readonly screens: Screens;
  readonly chat: ChatCommands;
  readonly controls: Controls;
  readonly displays: Displays;
  readonly connections: Connections;
  readonly queries: Queries;

  constructor(
    options: MockModOptions,
    localToken: string,
    readonly port: number,
    wss: WebSocketServer,
  ) {
    super();
    this.opts = {
      ...options,
      resumeWindowMs: options.resumeWindowMs ?? 30_000,
      heartbeatMs: options.heartbeatMs ?? 5000,
      maxFrameBytes: options.maxFrameBytes ?? 1024 * 1024,
      sweepIntervalMs: options.sweepIntervalMs ?? 25,
    };
    this.clock = options.clock ?? Date;
    this.tokenBytes = fromBase64Url(localToken);
    this.tls = options.tls !== undefined;
    const certFingerprint = options.tls === undefined ? undefined : certificateFingerprint(new Uint8Array(new X509Certificate(options.tls.cert).raw));
    this.fingerprint = options.fingerprint ?? certFingerprint ?? `sha256:${randomBytes(32).toString('hex')}`;
    this.devices = new Devices(this);
    this.store = new ObjectStore(this);
    this.shared = new SharedObjects(this);
    this.streams = new Streams(this);
    this.player = new Player(this);
    this.queries = new Queries(this);
    this.gate = new SurfaceGate(options.surfaceCooldownsMs ?? [5000, 15_000, 30_000], options.surfaceResetMs ?? 60_000, options.surfaceAskedWindowMs ?? 5000);
    this.inputs = new InputQueue(this);
    this.views = new Views(this);
    this.screens = new Screens(this);
    this.chat = new ChatCommands(this);
    this.controls = new Controls(this);
    this.displays = new Displays(this);
    this.connections = new Connections(this, wss);
  }

  info(s: Session): MockSessionInfo {
    const { attached, worldMatched } = this.player.attachState(s);
    return {
      id: s.sessionId,
      connectionId: s.conn?.id ?? null,
      kind: s.kind,
      name: s.name,
      identity: s.identity,
      owner: s.owner,
      deviceId: s.deviceId,
      connected: s.conn !== null,
      superseded: s.superseded,
      world: s.world,
      position: s.position,
      attached,
      worldMatched,
      status: s.status,
      extensions: s.extensions,
      lastApplied: s.inApplied,
    };
  }

  /** The newest non-superseded session whose owner, bare identity or name is `match`. */
  /** Extension messages received, oldest first, as raw payload bytes. */
  readonly extensionMessages: { owner: string; type: string; payload: Uint8Array }[] = [];

  onExtensionMessage(s: Session, type: string, payload: Uint8Array, header?: EnvelopeHeader): void {
    // As the console extension of the mod: lines come only from an attached session that may notify.
    if (type === 'bridge:console.lines') {
      if (!this.devices.granted(s, 'notify')) return this.devices.deny(s, 'notify', type, header, header?.type);
      if (!this.player.attachState(s).attached) return this.sendSessionError(s, 'not_attached', 'the player has not attached this bot', header);
    }
    this.extensionMessages.push({ owner: s.owner, type, payload });
    this.emit('extensionMessage', { session: this.info(s), type, payload });
  }

  findSession(match: string): Session | undefined {
    let found: Session | undefined;
    for (const s of this.sessions.values()) {
      if (s.superseded) continue;
      if (s.owner === match || bareIdentity(s.owner) === match || s.name === match) found = s;
    }
    return found;
  }

  /** The live session whose prefix holds `key`, or `undefined`. */
  liveOwnerOrNone(key: string): Session | undefined {
    for (const s of this.sessions.values()) if (isLive(s) && key.startsWith(s.prefix)) return s;
    return undefined;
  }

  /** The live session whose prefix holds `key`. @throws BridgeError `disconnected` when none does. */
  liveOwner(key: string): Session {
    const s = this.liveOwnerOrNone(key);
    if (s === undefined) throw new BridgeError('disconnected', `no session owns ${key}`);
    return s;
  }

  /** The live session owning a declaration of `kind` at `key`. @throws BridgeError `invalid_message` when there is none. */
  ownerSession(key: string, kind: KindName): Session {
    if (this.store.kindAt(key) !== kind) throw new BridgeError('invalid_message', `no ${kind} at ${key}`);
    return this.liveOwner(key);
  }

  /**
   * Refuses an unasked surface while its owner cools down: answers `cooldown` (retryable, with
   * `retryAfterMs` in the details) and returns true. A view or screen open has no request id, so
   * its refusal is a session error that names the message and carries the object's key in the
   * details.
   */
  refuseSurface(s: Session, header: EnvelopeHeader | undefined, relatesTo: number, type: string, key?: string): boolean {
    const left = this.gate.refusedFor(s.owner, this.clock.now());
    if (left === undefined) return false;
    this.emit('inputRejected', { session: this.info(s), type, code: 'cooldown' });
    const details = key === undefined || this.opts.surfaceRefusalKey === false ? { retryAfterMs: left } : { retryAfterMs: left, key };
    this.sendSessionError(s, 'cooldown', `the player dismissed this a moment ago; try again in ${left} ms`, header, relatesTo, details, true);
    return true;
  }

  /** False after refusing a write from a superseded, read-only session. */
  writable(s: Session, header: EnvelopeHeader | undefined, relatesTo: number): boolean {
    if (!s.superseded) return true;
    this.sendSessionError(s, 'superseded', 'session is read-only', header, relatesTo);
    return false;
  }

  resolveConns(target: DropTarget): Conn[] {
    let c: Conn | null | undefined;
    if (typeof target === 'number') c = this.conns.get(target);
    else if (typeof target === 'string') c = this.findSession(target)?.conn;
    else c = target.connectionId === null ? undefined : this.conns.get(target.connectionId);
    return c == null ? [] : [c];
  }

  closeSession(s: Session, reason: string, notify: boolean): void {
    if (s.closed) return;
    if (notify && s.conn !== null) this.sendSession(s, 'session.close', { reason });
    s.closed = true;
    s.conn?.sessions.delete(s.sessionId);
    this.end(s, reason);
  }

  /** Ends a session whose resume window passed. */
  expire(s: Session): void {
    s.closed = true;
    this.end(s, 'expired');
  }

  /** Releases a session's timers, streams and queued inputs and stops tracking it. */
  forgetSession(s: Session): void {
    this.store.discardTx(s, 'session_end');
    clearSampleTimer(s);
    if (s.ackTimer !== undefined) clearTimeout(s.ackTimer);
    s.ackTimer = undefined;
    s.streams.clear();
    this.sessions.delete(s.token);
    this.inputs.drop(s);
  }

  /** Clears the shown view or the active tool when its declaration disappears. */
  forgetDeclaration(key: string): void {
    this.views.forget(key);
    this.controls.forget(key);
  }

  /** Expires TTLs, resume windows and input deadlines. Runs on a timer and before inspection. */
  sweep(): void {
    const now = this.clock.now();
    this.store.sweep(now);
    for (const s of [...this.sessions.values()]) {
      if (s.tx !== undefined && now - s.tx.startedAt > TX_TIMEOUT_MS) this.store.discardTx(s, 'timeout');
      if (s.conn === null && s.droppedAt !== undefined && now - s.droppedAt > this.opts.resumeWindowMs) this.expire(s);
    }
    this.inputs.sweep(now);
  }

  /** Drops every pending request, so no timer outlives {@link MockMod.close}. */
  clearPending(): void {
    for (const s of this.sessions.values()) if (s.ackTimer !== undefined) clearTimeout(s.ackTimer);
    this.chat.clear();
    this.views.clear();
    this.shared.clear();
  }

  private end(s: Session, reason: string): void {
    const info = this.info(s);
    this.forgetSession(s);
    if (!s.superseded) this.store.dropOwned(s);
    this.emit('sessionClosed', { ...info, reason });
  }
}
