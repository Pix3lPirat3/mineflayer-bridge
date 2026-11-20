import { randomBytes, randomUUID } from 'node:crypto';
import { readFileSync, watch, type FSWatcher } from 'node:fs';
import { basename, dirname } from 'node:path';
import { endpointFileOf, readEndpointFile } from './files.ts';
import { extensionMessage } from './ext-wire.ts';
import { hostname } from 'node:os';
import { WebSocket, type ClientOptions, type RawData } from 'ws';
import {
  authProof,
  BridgeError,
  expectFullyRead,
  Flags,
  fromBase64Url,
  Kinds,
  LOCAL_DEFAULT_PERMISSIONS,
  Messages,
  MessagesById,
  type MessageType,
  normalizeFingerprint,
  PROTOCOL,
  readHeader,
  REMOTE_DEFAULT_PERMISSIONS,
  toBase64Url,
  verifyAuthProof,
  WireWriter,
  writeHeader,
  type Budget,
  type EnvelopeHeader,
  type ExtensionRef,
  type MessageName,
  type MessagePayloads,
} from '@mineflayer-bridge/protocol';
import { Pacer } from './pacer.ts';
import { DEFAULT_MAX_BLOB_BYTES, DEFAULT_STREAM_BYTES_IN_FLIGHT } from './blobs.ts';
import { pinnedConnection, resolveTarget, type Target } from './remote.ts';
import type { SessionCore } from './session.ts';
import type { BridgeLimits, CredentialStore, EndpointInfo, HubEvents, Logger, RawFrame, ReconnectOptions, StoredDevice } from './types.ts';
import { asBridgeError, Emitter } from './util.ts';

export { bridgeUrl } from './remote.ts';

/** Plugin version sent in `hello`, read from package.json (one level above both src/ and dist/). */
export const pluginVersion: string = (JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')) as { version: string }).version;

/** Core kind names by wire id. */
const CORE_KIND_NAMES: ReadonlyMap<number, string> = new Map(Object.values(Kinds).map((k) => [k.id, k.name]));
/** Above this many buffered bytes the socket counts as not writable. */
const HIGH_WATER_BYTES = 1024 * 1024;
const DEFAULT_HEARTBEAT_MS = 5000;
const HANDSHAKE_TIMEOUT_MS = 10_000;
/** A connection that stayed up this long starts the reconnect backoff over. */
const DEFAULT_STABLE_MS = 10_000;

/** Default quotas, in force until the first `welcome`. */
const DEFAULT_LIMITS: BridgeLimits = {
  maxFrameBytes: 1024 * 1024,
  maxObjects: 20_000,
  maxBlobBytes: DEFAULT_MAX_BLOB_BYTES,
  blobInFlightBytes: DEFAULT_STREAM_BYTES_IN_FLIGHT,
  resumeWindowMs: 30_000,
  heartbeatMs: DEFAULT_HEARTBEAT_MS,
  maxDisplays: 4096,
  maxEntityDisplays: 256,
  maxEmitters: 32,
  maxSoundEmitters: 16,
  maxInteractive: 1024,
  effectsPerSecond: 40,
};

interface PendingRequest {
  resolve(payload: unknown): void;
  reject(error: BridgeError): void;
  timer: NodeJS.Timeout | undefined;
  core: SessionCore | null;
}

export interface LinkOptions {
  endpoint?: string | EndpointInfo;
  endpointFile?: string;
  /** Pairing URI; see `HubOptions.pair`. */
  pair?: string;
  /** See `HubOptions.remoteFallback`. */
  remoteFallback?: boolean;
  /** See `HubOptions.allowInsecure`. */
  allowInsecure?: boolean;
  credentials: CredentialStore;
  deviceName?: string;
  reconnect: ReconnectOptions | false;
  logger: Logger;
  /** Extension kind and message names to register in `hello`. */
  names(): { kinds: string[]; messages: string[] };
}

/** How long the endpoint file must stay unchanged before a waiting link connects. */
const ENDPOINT_SETTLE_MS = 250;

/** Permissions a local device has by default and a remotely paired one does not. */
const REMOTE_WITHHELD: ReadonlySet<string> = new Set(LOCAL_DEFAULT_PERMISSIONS.filter((p) => !REMOTE_DEFAULT_PERMISSIONS.includes(p)));

/**
 * Converts a received `core:Error` payload to a {@link BridgeError}. A `permission_denied` message
 * is extended to name the missing permission (from `details.permission`) and where the player
 * grants it; code and details stay as received. The mod never asks the player at request time, so
 * the message says what to change in the device settings rather than suggesting a retry.
 *
 * @param remote The connection authenticated as a paired device, whose defaults leave out some
 *   permissions that a local device has; the message says so.
 */
export function errorFromPayload(p: MessagePayloads['error'], remote = false): BridgeError {
  let message = p.error.message;
  if (p.error.code === 'permission_denied') {
    const d = p.error.details;
    const permission = typeof d === 'object' && d !== null && typeof (d as { permission?: unknown }).permission === 'string' ? (d as { permission: string }).permission : undefined;
    const what = permission === undefined ? 'a permission is' : `permission "${permission}" is`;
    const why = remote && (permission === undefined || REMOTE_WITHHELD.has(permission)) ? `; remotely paired devices start without ${[...REMOTE_WITHHELD].join(', ')}` : '';
    message += ` (${what} not granted to this device${why}; the player can grant it in game under Bridge > Devices)`;
  }
  return new BridgeError(p.error.code, message, { retryable: p.error.retryable ?? false, details: p.error.details });
}

function sameSet(a: ReadonlySet<string>, b: ReadonlySet<string>): boolean {
  if (a.size !== b.size) return false;
  for (const x of a) if (!b.has(x)) return false;
  return true;
}

/**
 * One authenticated connection to one mod, shared by every session of a hub. Owns the socket,
 * handshake, heartbeat, reconnect with backoff and request ids; sessions own sequencing and
 * declared state.
 */
export class Link {
  readonly events = new Emitter<HubEvents>();
  state: 'idle' | 'connecting' | 'ready' | 'closed' = 'idle';
  capabilities: ExtensionRef[] = [];
  /** Paces every session on this connection against the mod's budget for the device. */
  readonly pacer = new Pacer();
  private currentBudget: Budget | undefined;
  /** The mod's budget for this device; setting it refills the pacer. */
  get budget(): Budget | undefined {
    return this.currentBudget;
  }
  set budget(b: Budget | undefined) {
    this.currentBudget = b;
    this.pacer.reset(b);
  }
  heartbeatMs = DEFAULT_HEARTBEAT_MS;
  maxFrameBytes = 1024 * 1024;
  /** Largest blob payload the mod accepts, in bytes. */
  maxBlobBytes = DEFAULT_MAX_BLOB_BYTES;
  /** Device id that authenticated this connection; part of controller owner keys. */
  authDeviceId = '';
  /** This connection authenticated as a paired device (pairing code or device secret), not with the local token. */
  remote = false;
  /** Limits from the latest `welcome`. */
  limits: BridgeLimits = DEFAULT_LIMITS;
  /** Granted permissions from the latest `welcome` or `permissions` push; empty before the first `welcome`. */
  permissions: ReadonlySet<string> = new Set();

  private readonly opts: LinkOptions;
  private ws: WebSocket | undefined;
  private target: Target | undefined;
  /** Canonical fingerprint of the certificate the current TLS socket was accepted with. */
  private serverFingerprint: string | undefined;
  /** Credential issued by `pair.result` on this link, used before the store write completes. */
  private paired: StoredDevice | undefined;
  /** The current connection sent a `pair` response and waits for `pair.result`. */
  private pairing = false;
  /** A local refusal (pin mismatch, unusable pairing) that must not be retried. */
  private refusal: BridgeError | undefined;
  private readonly cores = new Set<SessionCore>();
  private readonly bySession = new Map<number, SessionCore>();
  private readonly registry = new Map<string, number>();
  private readonly namesById = new Map<number, string>();
  private readonly pending = new Map<number, PendingRequest>();
  // Request ids are unique for the hub's lifetime, so a request replayed after resume on a new
  // connection can never collide with a fresh one.
  private nextReq = 1;
  private attempt = 0;
  private stableTimer: NodeJS.Timeout | undefined;
  /** What the mod must prove in `welcome`: it knows the secret this connection authenticated with. */
  private auth: { secret: Uint8Array | undefined; nonce: Uint8Array; connectionId: number } | undefined;
  private reconnectTimer: NodeJS.Timeout | undefined;
  /** Watches the endpoint file while waiting to reconnect, so a game that starts is reached at once. */
  private endpointWatch: FSWatcher | undefined;
  /** Between a lost or failed connection and the next attempt. */
  private waiting = false;
  /** Runs once the endpoint file has been still for a moment; the backoff timer runs too. */
  private settleTimer: NodeJS.Timeout | undefined;
  private heartbeatTimer: NodeJS.Timeout | undefined;
  private writableTimer: NodeJS.Timeout | undefined;
  private readonly writableWaiters = new Set<() => void>();
  private lastInbound = 0;
  private lastOutbound = 0;
  private goodbye: { reason: string; retry: boolean } | undefined;
  /** Extension message and kind names offered in this connection's `hello`. */
  private offered = new Set<string>();
  private renegotiateTimer: NodeJS.Timeout | undefined;
  private renegotiating = false;
  private readonly writer = new WireWriter(4096);
  private readonly taps = new Set<(frame: RawFrame) => void>();
  private readonly payloadWriter = new WireWriter(4096);
  private started = false;
  private connecting: Promise<void> | undefined;

  constructor(opts: LinkOptions) {
    this.opts = opts;
  }

  get log(): Logger {
    return this.opts.logger;
  }

  /** Starts connecting; idempotent. */
  start(): void {
    if (this.started || this.state === 'closed') return;
    this.started = true;
    this.beginConnect();
  }

  private beginConnect(): void {
    // Whatever started it, the wait is over: its timers and the file watch go.
    this.waiting = false;
    clearTimeout(this.reconnectTimer);
    clearTimeout(this.settleTimer);
    this.unwatchEndpoint();
    const attempt = this.connect().finally(() => {
      if (this.connecting === attempt) this.connecting = undefined;
    });
    this.connecting = attempt;
  }

  add(core: SessionCore): void {
    this.cores.add(core);
    this.start();
    if (this.state === 'ready') core.linkReady();
  }

  delete(core: SessionCore): void {
    this.cores.delete(core);
    for (const [id, c] of this.bySession) if (c === core) this.bySession.delete(id);
  }

  bind(sessionId: number, core: SessionCore): void {
    this.bySession.set(sessionId, core);
  }

  unbind(sessionId: number): void {
    this.bySession.delete(sessionId);
  }

  /** The extension message a non-core wire id stands for on this connection, if its codec is known. */
  private extensionType(id: number): MessageType<unknown> | undefined {
    const name = this.namesById.get(id);
    return name === undefined ? undefined : extensionMessage(name);
  }

  /** Wire id of a message name for this connection: core ids, or the id `welcome` gave an extension message. */
  messageId(name: string): number | undefined {
    return (Messages as Record<string, { id: number } | undefined>)[name]?.id ?? this.registry.get(name);
  }

  /** Wire id of a kind name for this connection, or `undefined` when the mod did not register it. */
  kindId(name: string): number | undefined {
    return (Kinds as Record<string, { id: number } | undefined>)[name]?.id ?? this.registry.get(name);
  }

  /** Message name for a wire id on this connection, or `undefined` when unknown. */
  messageName(id: number): string | undefined {
    return MessagesById.get(id)?.name ?? this.namesById.get(id);
  }

  /** Kind name for a wire id, or `#<id>` when unknown. */
  kindName(id: number): string {
    return CORE_KIND_NAMES.get(id) ?? this.namesById.get(id) ?? `#${id}`;
  }

  get writable(): boolean {
    return this.state === 'ready' && this.ws !== undefined && this.ws.readyState === WebSocket.OPEN && this.ws.bufferedAmount < HIGH_WATER_BYTES;
  }

  /** Calls `fn` once the socket drains below the high-water mark. */
  whenWritable(fn: () => void): void {
    this.writableWaiters.add(fn);
    // ws has no drain event, so poll bufferedAmount while anyone waits.
    this.writableTimer ??= setInterval(() => {
      if (!this.writable && this.state === 'ready') return;
      clearInterval(this.writableTimer);
      this.writableTimer = undefined;
      const waiters = [...this.writableWaiters];
      this.writableWaiters.clear();
      for (const w of waiters) w();
    }, 10);
  }

  allocReq(): number {
    return this.nextReq++;
  }

  /** Registers a pending request; the timer starts now. */
  expect(req: number, core: SessionCore | null, timeoutMs: number, resolve: (p: unknown) => void, reject: (e: BridgeError) => void): void {
    const timer = setTimeout(() => {
      this.pending.delete(req);
      reject(new BridgeError('timeout', `request ${req} timed out after ${timeoutMs} ms`, { retryable: true }));
    }, timeoutMs);
    this.pending.set(req, { resolve, reject, timer, core });
  }

  /** Rejects and forgets a pending request. Returns false when it already settled. */
  cancel(req: number, error: BridgeError): boolean {
    const p = this.pending.get(req);
    if (p === undefined) return false;
    this.pending.delete(req);
    clearTimeout(p.timer);
    p.reject(error);
    return true;
  }

  /** Rejects every pending request of `core`. */
  rejectAll(core: SessionCore, error: BridgeError): void {
    for (const [req, p] of [...this.pending]) if (p.core === core) this.cancel(req, error);
  }

  /** Settles a response frame against its pending request. */
  settle(header: EnvelopeHeader, type: string, payload: unknown): void {
    if (header.req === undefined) return;
    const p = this.pending.get(header.req);
    if (p === undefined) return;
    this.pending.delete(header.req);
    clearTimeout(p.timer);
    if (type === 'error' || (header.flags & Flags.ERROR) !== 0) p.reject(errorFromPayload(payload as MessagePayloads['error'], this.remote));
    else p.resolve(payload);
  }

  encode<N extends MessageName>(type: N, payload: MessagePayloads[N]): Uint8Array {
    this.payloadWriter.reset();
    Messages[type].codec.write(this.payloadWriter, payload);
    return this.payloadWriter.toBytes();
  }

  /** Sends one frame. Returns false when there is no open socket. */
  sendFrame(header: EnvelopeHeader, payload: Uint8Array): boolean {
    const ws = this.ws;
    if (ws === undefined || ws.readyState !== WebSocket.OPEN) return false;
    this.writer.reset();
    writeHeader(this.writer, header);
    this.writer.raw(payload);
    const bytes = this.writer.toBytes();
    ws.send(bytes, { binary: true });
    this.lastOutbound = Date.now();
    if (this.taps.size > 0) this.emitFrame('out', bytes);
    return true;
  }

  /**
   * Observes every binary frame this link sends or receives, whole (envelope header and payload).
   * Listeners run synchronously on the socket path and must not keep a reference they mutate; an
   * error they throw is logged and swallowed. Returns a remover.
   */
  tap(fn: (frame: RawFrame) => void): () => void {
    this.taps.add(fn);
    return () => {
      this.taps.delete(fn);
    };
  }

  private emitFrame(direction: RawFrame['direction'], bytes: Uint8Array): void {
    const frame: RawFrame = { direction, atMs: Date.now(), bytes };
    for (const fn of [...this.taps]) {
      try {
        fn(frame);
      } catch (e) {
        this.log.warn('frame tap failed', { error: (e as Error).message });
      }
    }
  }

  private sendConn<N extends MessageName>(type: N, payload: MessagePayloads[N], req?: number): void {
    const header: EnvelopeHeader = { flags: 0, type: Messages[type].id, session: 0 };
    if (req !== undefined) header.req = req;
    this.sendFrame(header, this.encode(type, payload));
  }

  /**
   * A request on session 0 (`session.open`, `session.resume`) with the schema timeout. The callback
   * runs synchronously when the response frame is handled, so frames that follow it in the same read
   * already find the session bound.
   */
  requestConn<N extends 'session.open' | 'session.resume'>(type: N, payload: MessagePayloads[N], done: (error: BridgeError | undefined, response: unknown) => void): void {
    if (this.state !== 'ready') {
      done(new BridgeError('disconnected', 'not connected', { retryable: true }), undefined);
      return;
    }
    const req = this.allocReq();
    this.expect(req, null, Messages[type].timeoutMs ?? 15000, (p) => done(undefined, p), (e) => done(e, undefined));
    this.sendConn(type, payload, req);
  }

  /** Closes every session, says goodbye and stops reconnecting. */
  async close(): Promise<void> {
    if (this.state === 'closed') return;
    for (const core of [...this.cores]) await core.close('shutdown');
    this.state = 'closed';
    clearTimeout(this.reconnectTimer);
    clearTimeout(this.settleTimer);
    this.unwatchEndpoint();
    clearTimeout(this.renegotiateTimer);
    clearTimeout(this.stableTimer);
    // An attempt may be reading the endpoint or credentials; let it observe the closed state.
    await this.connecting;
    clearInterval(this.heartbeatTimer);
    clearInterval(this.writableTimer);
    const ws = this.ws;
    if (ws !== undefined && ws.readyState === WebSocket.OPEN) {
      this.sendConn('goodbye', { reason: 'shutdown', message: 'plugin closed', retry: false });
      await new Promise<void>((resolve) => {
        const t = setTimeout(() => {
          ws.terminate();
          resolve();
        }, 1000);
        ws.once('close', () => {
          clearTimeout(t);
          resolve();
        });
        ws.close(1000, 'shutdown');
      });
    } else {
      ws?.terminate();
    }
    for (const [req] of [...this.pending]) this.cancel(req, new BridgeError('cancelled', 'bridge closed'));
    this.events.removeAll();
  }

  private async connect(): Promise<void> {
    if (this.state === 'closed') return;
    this.state = 'connecting';
    this.goodbye = undefined;
    this.refusal = undefined;
    this.pairing = false;
    this.auth = undefined;
    this.serverFingerprint = undefined;
    let ws: WebSocket;
    try {
      const target = await resolveTarget(this.opts, this.opts.credentials, this.paired);
      // Logged when the choice changes, not on every reconnect attempt.
      if (this.target?.origin !== target.origin || this.target.source !== target.source) this.log.info(`connecting to ${target.origin}, chosen by ${target.source}`);
      this.target = target;
      // Resolve the device id before connecting so the handshake never waits on disk.
      await this.opts.credentials.deviceId();
      if ((this.state as Link['state']) === 'closed') return;
      const options: ClientOptions = { perMessageDeflate: false, handshakeTimeout: HANDSHAKE_TIMEOUT_MS };
      if (target.pin !== undefined) {
        options.createConnection = pinnedConnection(
          target.pin,
          HANDSHAKE_TIMEOUT_MS,
          (fingerprint) => {
            this.serverFingerprint = fingerprint;
          },
          (error) => this.refuse(error),
        );
      }
      ws = new WebSocket(target.url, options);
    } catch (e) {
      const error = asBridgeError(e);
      this.events.emit('error', error);
      this.scheduleReconnect(error.code === 'unauthorized' ? 'unauthorized' : 'endpoint unavailable', error.code !== 'unauthorized' && error.code !== 'invalid_message');
      return;
    }
    this.ws = ws;
    this.lastInbound = Date.now();
    ws.on('open', () => {
      const { kinds, messages } = this.opts.names();
      this.offered = new Set([...kinds, ...messages]);
      this.sendConn('hello', {
        protocolMajor: PROTOCOL.major,
        protocolMinor: PROTOCOL.minor,
        pluginVersion,
        deviceName: this.opts.deviceName ?? hostname(),
        messages,
        kinds,
      });
    });
    ws.on('message', (data, isBinary) => {
      if (this.ws !== ws) return;
      if (!isBinary) {
        ws.close(1003, 'binary frames only');
        return;
      }
      this.onFrame(toBytes(data));
    });
    ws.on('error', (e) => this.log.debug('socket error', { error: e.message }));
    ws.on('close', () => {
      if (this.ws === ws) this.onClose();
    });
    this.startHeartbeat();
  }

  /**
   * An extension added message or kind names after this connection's `hello` (a bot that joined
   * later loaded it). Ids are negotiated only in `hello`, so the link reconnects once, at once, and
   * sessions resume with the new names registered.
   */
  namesChanged(): void {
    if (this.ws === undefined || this.state === 'closed') return;
    const { kinds, messages } = this.opts.names();
    if ([...kinds, ...messages].every((n) => this.offered.has(n))) return;
    for (const core of this.cores) core.forgetResume();
    clearTimeout(this.renegotiateTimer);
    // Coalesce several extensions loading together into one reconnect.
    this.renegotiateTimer = setTimeout(() => {
      const ws = this.ws;
      if (ws === undefined || this.state === 'closed') return;
      this.log.info('extensions added names; reconnecting to register them');
      this.renegotiating = true;
      ws.close(1000, 'renegotiate');
    }, 50);
    this.renegotiateTimer.unref?.();
  }

  /** Whether an extension of this hub declares `name` but this connection's `hello` did not offer it yet. */
  awaitingName(name: string): boolean {
    if (this.offered.has(name)) return false;
    const { kinds, messages } = this.opts.names();
    return messages.includes(name) || kinds.includes(name);
  }

  private startHeartbeat(): void {
    clearInterval(this.heartbeatTimer);
    this.heartbeatTimer = setInterval(() => {
      const ws = this.ws;
      // Before welcome the mod takes only a few frames, so nothing but hello and auth is sent.
      if (ws === undefined || ws.readyState !== WebSocket.OPEN || this.state !== 'ready') return;
      const now = Date.now();
      // Ping after one interval idle, dead after three silent.
      if (now - this.lastInbound > this.heartbeatMs * 3) {
        this.log.warn('mod silent, dropping connection');
        ws.terminate();
        return;
      }
      if (now - this.lastOutbound >= this.heartbeatMs) this.sendConn('ping', { time: now });
    }, Math.max(10, Math.floor(this.heartbeatMs / 5)));
    this.heartbeatTimer.unref();
  }

  private onClose(): void {
    clearInterval(this.heartbeatTimer);
    clearTimeout(this.stableTimer);
    this.ws = undefined;
    this.bySession.clear();
    for (const [req, p] of [...this.pending]) if (p.core === null) this.cancel(req, new BridgeError('disconnected', 'connection lost', { retryable: true }));
    if (this.state === 'closed') return;
    const wasReady = this.state === 'ready';
    this.state = 'idle';
    for (const core of this.cores) core.linkLost();
    if (this.renegotiating) {
      this.renegotiating = false;
      this.events.emit('disconnected', { reason: 'renegotiating', retry: true });
      this.beginConnect();
      return;
    }
    const retry = this.refusal === undefined && (this.goodbye?.retry ?? true);
    const reason = this.refusal !== undefined ? 'unauthorized' : (this.goodbye?.reason ?? (wasReady ? 'connection lost' : 'connect failed'));
    this.scheduleReconnect(reason, retry);
  }

  /** Records a refusal that ends reconnecting and reports it as the hub's `error` event. */
  private refuse(error: BridgeError): void {
    this.refusal = error;
    this.events.emit('error', error);
  }

  private scheduleReconnect(reason: string, retry: boolean): void {
    const policy = this.opts.reconnect;
    const allowed = retry && policy !== false && (policy.maxAttempts === undefined || this.attempt < policy.maxAttempts);
    // A token read from the endpoint file can be refused when the game restarted and has not
    // written its new file yet: wait for the file to change rather than give up.
    const t = this.target;
    const fromFile = t !== undefined && t.info.localToken !== undefined && t.pairCode === undefined && t.source !== 'the endpoint option';
    const modRefused = this.goodbye?.reason === 'unauthorized' && this.refusal === undefined && !this.pairing;
    const afterFile = !allowed && modRefused && fromFile && policy !== false && this.state !== 'closed' && endpointFileOf(this.opts) !== undefined;
    this.events.emit('disconnected', { reason, retry: allowed || afterFile });
    if (afterFile) {
      this.waiting = true;
      this.watchEndpoint();
      // The new file may be there already, written before the watch began.
      const refused = t.info.localToken;
      const file = endpointFileOf(this.opts)!;
      void readEndpointFile(file).then(
        (now) => {
          if (this.waiting && now.localToken !== refused) this.reconnectNow();
        },
        () => undefined,
      );
      if (this.endpointWatch !== undefined) return;
    }
    if (!allowed || this.state === 'closed') {
      if (this.state !== 'closed') this.state = 'idle';
      this.started = false;
      return;
    }
    const p: ReconnectOptions = policy;
    const initial = p.initialDelayMs ?? 250;
    const max = p.maxDelayMs ?? 30_000;
    // Exponential backoff with full jitter.
    const ceiling = Math.min(max, initial * 2 ** this.attempt);
    this.attempt++;
    this.waiting = true;
    this.reconnectTimer = setTimeout(() => this.reconnectNow(), Math.random() * ceiling);
    this.watchEndpoint();
  }

  private reconnectNow(): void {
    clearTimeout(this.reconnectTimer);
    clearTimeout(this.settleTimer);
    this.unwatchEndpoint();
    // Only from a wait: a late timer or file event must not start a second connection.
    if (!this.waiting || this.state === 'closed') return;
    this.beginConnect();
  }

  /** The mod writes a new endpoint file on every start; a change to it ends the wait. */
  private watchEndpoint(): void {
    if (this.endpointWatch !== undefined) return;
    const file = endpointFileOf(this.opts);
    if (file === undefined) return;
    try {
      const name = basename(file);
      // A file is written in steps: connect once it has been still for a moment, not mid-write.
      const w = watch(dirname(file), { persistent: false }, (_event, changed) => {
        if (this.endpointWatch !== w || (changed !== null && changed !== name)) return;
        clearTimeout(this.settleTimer);
        this.settleTimer = setTimeout(() => this.reconnectNow(), ENDPOINT_SETTLE_MS);
      });
      w.on('error', () => this.unwatchEndpoint());
      this.endpointWatch = w;
    } catch {
      // No folder yet: the timer still retries.
    }
  }

  private unwatchEndpoint(): void {
    this.endpointWatch?.close();
    this.endpointWatch = undefined;
  }

  private onFrame(bytes: Uint8Array): void {
    this.lastInbound = Date.now();
    if (this.taps.size > 0) this.emitFrame('in', bytes);
    let header: EnvelopeHeader;
    let type: string;
    let payload: unknown;
    try {
      const frame = readHeader(bytes);
      header = frame.header;
      const t = MessagesById.get(header.type) ?? this.extensionType(header.type);
      if (t === undefined) {
        this.sendError(header, 'unknown_type', `unknown message type ${header.type}`);
        return;
      }
      type = t.name;
      payload = t.codec.read(frame.payload);
      expectFullyRead(frame.payload);
    } catch (e) {
      this.log.warn('malformed frame from mod', { error: asBridgeError(e).message });
      return;
    }
    if (header.session !== 0) {
      const core = this.bySession.get(header.session);
      if (core !== undefined) core.onFrame(header, type, payload);
      return;
    }
    this.onConnFrame(header, type as MessageName, payload);
  }

  private sendError(header: EnvelopeHeader, code: string, message: string): void {
    const h: EnvelopeHeader = { flags: Flags.ERROR, type: Messages.error.id, session: header.session };
    if (header.req !== undefined && (header.flags & Flags.RESPONSE) === 0) {
      h.flags |= Flags.RESPONSE;
      h.req = header.req;
    }
    this.sendFrame(h, this.encode('error', { error: { code, message, retryable: false }, relatesTo: header.type }));
  }

  private onConnFrame(header: EnvelopeHeader, type: MessageName, payload: unknown): void {
    switch (type) {
      case 'auth.challenge':
        this.answerChallenge(payload as MessagePayloads['auth.challenge']).catch((e: unknown) => {
          // Reading the credential failed; this attempt cannot authenticate.
          this.events.emit('error', new BridgeError('internal', `could not answer the mod's challenge: ${asBridgeError(e).message}`, { cause: e }));
          this.ws?.close(1000, 'no credential');
        });
        return;
      case 'pair.result':
        this.onPairResult(payload as MessagePayloads['pair.result']);
        return;
      case 'welcome':
        this.onWelcome(payload as MessagePayloads['welcome']);
        return;
      case 'permissions':
        this.setPermissions((payload as MessagePayloads['permissions']).granted);
        return;
      case 'ping':
        if (this.state === 'ready') this.sendConn('pong', { time: (payload as MessagePayloads['ping']).time });
        return;
      case 'goodbye': {
        const p = payload as MessagePayloads['goodbye'];
        this.goodbye = { reason: p.reason, retry: p.retry };
        if (p.reason === 'unauthorized') {
          const message = this.pairing ? `the mod refused the pairing code (wrong, already used or expired): ${p.message}` : p.message;
          this.events.emit('error', new BridgeError('unauthorized', message));
        } else if (p.reason === 'revoked') {
          this.onRevoked(p.message);
        }
        return;
      }
      case 'budget.update':
        this.budget = (payload as MessagePayloads['budget.update']).budget;
        return;
      case 'error':
        if ((header.flags & Flags.RESPONSE) !== 0) this.settle(header, type, payload);
        else this.log.warn('mod error', { code: (payload as MessagePayloads['error']).error.code, message: (payload as MessagePayloads['error']).error.message });
        return;
      case 'session.opened':
      case 'session.resumed':
        this.settle(header, type, payload);
        return;
      default:
        return;
    }
  }

  /**
   * Picks the handshake method: the device secret when a credential exists for the server's
   * fingerprint, then the pairing code when this target pairs, then the local token.
   */
  private async answerChallenge(c: MessagePayloads['auth.challenge']): Promise<void> {
    const t = this.target;
    const ep = t?.info;
    const creds = this.opts.credentials;
    // Over TLS only the fingerprint of the certificate actually seen selects a credential.
    const fingerprint = t?.pin !== undefined ? this.serverFingerprint : ep?.fingerprint === undefined ? undefined : (normalizeFingerprint(ep.fingerprint) ?? ep.fingerprint);
    const device = fingerprint === undefined ? undefined : this.paired?.fingerprint === fingerprint ? this.paired : await creds.device(fingerprint);
    // The mod answers this nonce in `welcome`, proving it holds the same secret.
    const nonce = new Uint8Array(randomBytes(32));
    if (device !== undefined && c.methods.includes('device')) {
      this.authDeviceId = device.deviceId;
      this.remote = true;
      const secret = fromBase64Url(device.secret);
      this.auth = { secret, nonce, connectionId: c.connectionId };
      const proof = authProof(secret, c.nonce, c.connectionId, device.deviceId);
      this.sendConn('auth.response', { method: 'device', deviceId: device.deviceId, proof, nonce });
      return;
    }
    if (t?.pairCode !== undefined && t.pin !== undefined && this.serverFingerprint !== undefined) {
      if (!c.methods.includes('pair')) {
        this.refuse(new BridgeError('unauthorized', 'the mod has no active pairing code (codes are single-use and expire after 5 minutes); show a new one under Bridge > Devices > Pair new device'));
        this.ws?.close(1000, 'no pairing code');
        return;
      }
      // A fresh id per pairing, so pairing again never collides with a device the mod still lists.
      const deviceId = randomUUID();
      this.authDeviceId = deviceId;
      this.remote = true;
      this.pairing = true;
      // The secret arrives with `pair.result`, before `welcome`.
      this.auth = { secret: undefined, nonce, connectionId: c.connectionId };
      this.sendConn('auth.response', { method: 'pair', deviceId, proof: new Uint8Array(Buffer.from(t.pairCode, 'utf8')), nonce });
      return;
    }
    if (ep?.localToken !== undefined && c.methods.includes('local')) {
      const deviceId = await creds.deviceId();
      this.authDeviceId = deviceId;
      this.remote = false;
      const secret = fromBase64Url(ep.localToken);
      this.auth = { secret, nonce, connectionId: c.connectionId };
      const proof = authProof(secret, c.nonce, c.connectionId, deviceId);
      this.sendConn('auth.response', { method: 'local', deviceId, proof, nonce });
      return;
    }
    this.events.emit('error', new BridgeError('unauthorized', `no usable credential for methods ${c.methods.join(', ')}`));
    this.ws?.close(1000, 'no credential');
  }

  private onWelcome(w: MessagePayloads['welcome']): void {
    if (w.protocolMajor !== PROTOCOL.major) {
      this.goodbye = { reason: 'protocol_mismatch', retry: false };
      this.sendConn('goodbye', { reason: 'protocol_mismatch', message: `plugin speaks ${PROTOCOL.major}, mod ${w.protocolMajor}`, retry: false });
      this.ws?.close(1000, 'protocol mismatch');
      return;
    }
    if (!this.modProved(w)) {
      this.refuse(new BridgeError('unauthorized', 'the peer did not prove it knows the credential, so it is not treated as the mod (a mod older than this plugin sends no proof: update the mod)'));
      this.sendConn('goodbye', { reason: 'unauthorized', message: 'missing or wrong proof in welcome', retry: false });
      this.ws?.close(1000, 'unauthorized');
      return;
    }
    this.registry.clear();
    this.namesById.clear();
    for (const e of w.registry) {
      this.registry.set(e.name, e.id);
      this.namesById.set(e.id, e.name);
    }
    this.capabilities = w.extensions;
    this.budget = w.budget;
    this.heartbeatMs = w.limits.heartbeatMs > 0 ? w.limits.heartbeatMs : DEFAULT_HEARTBEAT_MS;
    this.maxFrameBytes = w.limits.maxFrameBytes;
    this.maxBlobBytes = w.limits.maxBlobBytes > 0 ? w.limits.maxBlobBytes : DEFAULT_MAX_BLOB_BYTES;
    this.limits = {
      maxFrameBytes: this.maxFrameBytes,
      maxObjects: w.limits.maxObjects,
      maxBlobBytes: this.maxBlobBytes,
      blobInFlightBytes: w.limits.blobInFlightBytes ?? DEFAULT_LIMITS.blobInFlightBytes,
      resumeWindowMs: w.limits.resumeWindowMs,
      heartbeatMs: this.heartbeatMs,
      maxDisplays: w.limits.maxDisplays ?? DEFAULT_LIMITS.maxDisplays,
      maxEntityDisplays: w.limits.maxEntityDisplays ?? DEFAULT_LIMITS.maxEntityDisplays,
      maxEmitters: w.limits.maxEmitters ?? DEFAULT_LIMITS.maxEmitters,
      maxSoundEmitters: w.limits.maxSoundEmitters ?? DEFAULT_LIMITS.maxSoundEmitters,
      maxInteractive: w.limits.maxInteractive ?? DEFAULT_LIMITS.maxInteractive,
      effectsPerSecond: w.limits.effectsPerSecond ?? DEFAULT_LIMITS.effectsPerSecond,
    };
    this.setPermissions(w.permissions);
    this.pairing = false;
    this.lastInbound = Date.now();
    this.startHeartbeat();
    this.state = 'ready';
    // The backoff starts over only once the connection has lasted, so a mod that closes right
    // after every welcome is retried ever more slowly.
    const policy = this.opts.reconnect;
    clearTimeout(this.stableTimer);
    this.stableTimer = setTimeout(() => {
      this.attempt = 0;
    }, (policy === false ? undefined : policy.stableMs) ?? DEFAULT_STABLE_MS);
    this.stableTimer.unref?.();
    this.events.emit('connected');
    for (const core of [...this.cores]) core.linkReady();
  }

  /**
   * Checks `welcome.proof`: the HMAC of this connection's nonce, made with the secret used to
   * authenticate and `mod:<device id>`. Compared in constant time.
   */
  private modProved(w: MessagePayloads['welcome']): boolean {
    const a = this.auth;
    if (a === undefined || a.secret === undefined || w.proof === undefined || w.proof === null) return false;
    return verifyAuthProof(a.secret, a.nonce, a.connectionId, `mod:${this.authDeviceId}`, w.proof);
  }

  private setPermissions(granted: readonly string[]): void {
    const next: ReadonlySet<string> = new Set(granted);
    if (sameSet(next, this.permissions)) return;
    this.permissions = next;
    this.events.emit('permissions', next);
  }

  /**
   * Stores the credential from `pair.result` under the fingerprint of the certificate the socket was
   * accepted with; later connections pin that full fingerprint.
   */
  private onPairResult(p: MessagePayloads['pair.result']): void {
    const observed = this.serverFingerprint;
    const t = this.target;
    if (!this.pairing || observed === undefined || t === undefined) {
      this.log.warn('ignoring pair.result outside pairing');
      return;
    }
    const claimed = normalizeFingerprint(p.fingerprint);
    if (claimed !== undefined && claimed !== observed) {
      this.refuse(new BridgeError('unauthorized', `pair.result names fingerprint ${claimed}, but the connection presented ${observed}; the credential was not stored`));
      this.sendConn('goodbye', { reason: 'unauthorized', message: 'fingerprint mismatch', retry: false });
      this.ws?.close(1000, 'fingerprint mismatch');
      return;
    }
    if (claimed === undefined) this.log.warn('pair.result fingerprint is not SHA-256 hex; pinning the certificate the connection presented', { fingerprint: p.fingerprint });
    const credential: StoredDevice = { fingerprint: observed, deviceId: p.deviceId, secret: toBase64Url(p.secret), endpoint: t.origin, pairedAt: Date.now() };
    this.paired = credential;
    this.authDeviceId = p.deviceId;
    if (this.auth !== undefined) this.auth.secret = p.secret;
    const { fingerprint, ...stored } = credential;
    this.opts.credentials.saveDevice(fingerprint, stored).then(
      () => this.events.emit('paired', { deviceId: p.deviceId, fingerprint, endpoint: t.origin }),
      (e: unknown) => this.events.emit('error', new BridgeError('internal', `paired as ${p.deviceId}, but the credential could not be stored: ${asBridgeError(e).message}`, { cause: e })),
    );
  }

  /** The mod deleted this device, so the local credential is useless too. */
  private onRevoked(message: string): void {
    const fingerprint = this.serverFingerprint ?? (this.target?.info.fingerprint === undefined ? undefined : normalizeFingerprint(this.target.info.fingerprint));
    this.paired = undefined;
    this.events.emit('error', new BridgeError('unauthorized', `the player revoked this device: ${message}`));
    if (fingerprint !== undefined && this.opts.credentials.forget !== undefined) {
      this.opts.credentials.forget(fingerprint).catch((e: unknown) => this.log.warn('could not delete the revoked credential', { error: asBridgeError(e).message }));
    }
  }
}

function toBytes(data: RawData): Uint8Array {
  if (Array.isArray(data)) return new Uint8Array(Buffer.concat(data));
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
}
