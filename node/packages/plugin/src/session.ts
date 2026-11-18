import {
  BridgeError,
  Flags,
  Messages,
  type BotIdentity,
  type EnvelopeHeader,
  type ExtensionRef,
  type MessageName,
  type MessagePayloads,
  type ObjectTraits,
  type ObjectWrite,
  type SessionKind,
} from '@mineflayer-bridge/protocol';
import { DEFAULT_STREAM_BYTES_IN_FLIGHT, type BlobLimits } from './blobs.ts';
import { errorFromPayload, type Link } from './link.ts';
import { OBJECT_FRAME_OVERHEAD, type DeclaredStore, type StoreChanges, type StoreCursor, type StoreWrite } from './store.ts';
import { StreamSender } from './streams.ts';

/** Outbound log cap per session; beyond it resume is impossible and a resync follows. */
const LOG_CAP_BYTES = 4 * 1024 * 1024;
const EMPTY_ACK_DELAY_MS = 250;
const MAX_WRITES_PER_UPSERT = 256;
const MAX_TX_ID = 0xffffffff;
/** Room kept in a frame for the envelope header, in bytes. */
const FRAME_HEADER_ROOM = 64;
/** One-off messages kept while the session is not open; the oldest are dropped beyond it. */
export const MAX_QUEUED_MESSAGES = 256;
const OPEN_RETRY_INITIAL_MS = 500;
const OPEN_RETRY_MAX_MS = 30_000;
/** Codes of a failed `session.open` that are worth another try even when not marked retryable. */
const OPEN_RETRY_CODES: ReadonlySet<string> = new Set(['timeout', 'disconnected', 'rate_limited']);

interface LogEntry {
  seq: number;
  type: number;
  flags: number;
  req: number | undefined;
  payload: Uint8Array;
}

interface QueuedRequest {
  type: MessageName;
  payload: Uint8Array;
  timeoutMs: number;
  resolve(p: unknown): void;
  reject(e: BridgeError): void;
  req: number | undefined;
  /** Set once logged; the request then waits on the link's pending table. */
  sent: boolean;
  /** Message that withdraws the request at the mod, sent on abort and on local timeout. */
  cancel: CancelMessage | undefined;
  /** Runs out while the request still waits for the session to open. */
  waitTimer: NodeJS.Timeout | undefined;
}

/** Traits as sent: action keys are owner-relative in the store and absolute on the wire. */
function wireTraits(t: ObjectTraits | undefined, owner: string): ObjectTraits | undefined {
  if (t?.actions === undefined) return t;
  return { ...t, actions: t.actions.map((a) => `${owner}/${a}`) };
}

/** Messages that withdraw an input request; each carries the request id as `request`. */
export type CancelMessage = 'pick.cancel' | 'prompt.cancel' | 'form.close' | 'world-menu.cancel';

/** Mod-to-Node input messages routed to declarations by object key. */
export type InboundInput =
  | 'action.invoked'
  | 'command.invoked'
  | 'keybind.event'
  | 'tool.event'
  | 'tool.state'
  | 'settings.values'
  | 'container.open'
  | 'container.close'
  | 'container.input'
  | 'container.storage'
  | 'element.event'
  | 'intercept.event'
  | 'screen.event'
  | 'screen.open'
  | 'screen.close';

/** Callbacks from the protocol core to the public session object. */
export interface SessionHandlers {
  /** A message of a mod extension arrived. */
  extension?(type: string, payload: unknown): void;
  /** The session is open. `fresh` is false after a successful resume. */
  opened(fresh: boolean): void;
  /** The connection dropped or the session ended; it may resume later. */
  lost(): void;
  context(p: MessagePayloads['player.context']): void;
  interest(p: MessagePayloads['interest']): void;
  sharedUpsert(writes: ObjectWrite[]): void;
  sharedRemove(p: MessagePayloads['obj.remove']): void;
  /** A fresh session starts; the shared view must be refilled by the mod. */
  sharedReset(): void;
  superseded(): void;
  /**
   * The mod rejected something that was not a request (`not_owner`, `unknown_kind`, a refused
   * `container.open`, ...). `relatesTo` names the message it answers, when the mod said.
   */
  writeError(error: BridgeError, relatesTo: string | undefined): void;
  /** `session.open` was refused for good; the session stays closed until the link reconnects. */
  openFailed(error: BridgeError): void;
  /** An input event addressed to one of this session's declarations. */
  input<N extends InboundInput>(type: N, payload: MessagePayloads[N]): void;
  /**
   * The player edited one of this session's objects. Call `reply` once; declared-state changes made
   * before it go out ahead of the answer. An answer given after the session left `open` is dropped.
   */
  editPropose(p: MessagePayloads['obj.edit.propose'], reply: (p: MessagePayloads['obj.edit.result']) => void): void;
  /** A chat line addressed to this bot. */
  chat(p: MessagePayloads['chat.message']): void;
  /** A sample of the player's client. */
  sample(p: MessagePayloads['client.sample']): void;
  /**
   * The mod asks for dynamic completions. Call `reply` once; an answer given after the session left
   * `open` is dropped, as the mod has given up on it by then.
   */
  complete(p: MessagePayloads['command.complete'], reply: (p: MessagePayloads['command.suggestions']) => void): void;
}

/**
 * Protocol state of one session: open and resume, per-direction sequence numbers, the outbound log
 * and replay, acks, latest-wins slots, and turning declared-state changes into
 * `obj.*` messages.
 */
export class SessionCore {
  readonly kind: SessionKind;
  readonly name: string;
  identity: BotIdentity | undefined;
  state: 'idle' | 'opening' | 'open' | 'detached' | 'closed' = 'idle';
  sessionId = 0;
  superseded = false;

  private readonly link: Link;
  /** What this session has sent of the declared store; each link has its own. */
  private readonly store: StoreCursor;
  private readonly offRelease: () => void;
  private readonly handlers: SessionHandlers;
  private readonly extensionRefs: () => ExtensionRef[];
  private holds = 0;
  private resumeToken: Uint8Array | undefined;
  private outSeq = 0;
  private sentUpTo = 0;
  private log: LogEntry[] = [];
  private logBytes = 0;
  private resumable = true;
  /** Extension messages sent before the connection registered their names; sent once it has. */
  private awaiting: { type: string; payload: Uint8Array; reliable: boolean; req: number | undefined }[] = [];
  private inApplied = 0;
  private ackSent = 0;
  private ackTimer: NodeJS.Timeout | undefined;
  private flushScheduled = false;
  private retryTimer: NodeJS.Timeout | undefined;
  private openTimer: NodeJS.Timeout | undefined;
  private openAttempt = 0;
  /** The mod refused the open for good on this connection. */
  private openRefused = false;
  private droppedWarned = false;
  private waitingWritable = false;
  private readonly latest = new Map<string, Uint8Array>();
  private readonly lastLatest = new Map<string, Uint8Array>();
  private queued: QueuedRequest[] = [];
  private queuedMessages: { type: string; payload: Uint8Array }[] = [];
  private unreliable: { type: string; payload: Uint8Array }[] = [];
  private subscription: { kinds: Set<string>; extensions: Set<string>; all: boolean } | undefined;
  private subscriptionDirty = false;
  private warnedKinds = new Set<string>();
  private readonly streams: StreamSender;
  /** Open {@link beginTx} calls; while above 0 the store is not drained. */
  private txDepth = 0;
  /** A transaction ended and its writes still wait for the next drain. */
  private txPending = false;
  private txSeq = 0;
  /** Edit answers held until the transaction that holds their accepted data is sent. */
  private afterTx: (() => void)[] = [];

  constructor(link: Link, kind: SessionKind, name: string, store: DeclaredStore, handlers: SessionHandlers, extensionRefs: () => ExtensionRef[]) {
    this.link = link;
    this.kind = kind;
    this.name = name;
    this.store = store.cursor();
    this.handlers = handlers;
    this.extensionRefs = extensionRefs;
    this.store.onDirty = () => this.scheduleFlush();
    this.streams = new StreamSender({
      blobs: store.blobs,
      limits: () => this.blobLimits(),
      maxFrameBytes: () => link.maxFrameBytes,
      reliable: (type, payload) => this.reliable(type, link.encode(type, payload)),
      failed: (error) => handlers.writeError(error, undefined),
    });
    this.offRelease = store.blobs.onReleased((blob) => {
      this.streams.release(blob);
      this.scheduleFlush();
    });
  }

  /** Blob limits advertised by the mod, in bytes. */
  blobLimits(): BlobLimits {
    const paced = this.link.budget?.streamBytesInFlight ?? 0;
    const quota = this.link.limits.blobInFlightBytes > 0 ? this.link.limits.blobInFlightBytes : DEFAULT_STREAM_BYTES_IN_FLIGHT;
    return { maxBlobBytes: this.link.maxBlobBytes, streamBytesInFlight: paced > 0 ? Math.min(paced, quota) : quota };
  }

  /** Owner key without the trailing slash, or `undefined` before it is known. */
  get owner(): string | undefined {
    if (this.kind === 'controller') return this.link.authDeviceId === '' ? undefined : `ctl:${this.link.authDeviceId}/${this.name}`;
    const id = this.identity;
    if (id === undefined) return undefined;
    return `bot:${id.uuid ?? `${id.username.toLowerCase()}@${id.server ?? 'unknown'}`}`;
  }

  get connected(): boolean {
    return this.state === 'open';
  }

  /** Delays opening (for example while persisted state loads). Pair with {@link release}. */
  hold(): void {
    this.holds++;
  }

  release(): void {
    this.holds--;
    this.maybeOpen();
  }

  setIdentity(identity: BotIdentity): void {
    this.identity = identity;
    this.maybeOpen();
  }

  private get canOpen(): boolean {
    return this.holds === 0 && (this.kind === 'controller' || this.identity !== undefined);
  }

  linkReady(): void {
    this.openRefused = false;
    this.openAttempt = 0;
    this.maybeOpen();
  }

  private maybeOpen(): void {
    if (!this.canOpen || this.link.state !== 'ready' || this.openRefused || this.openTimer !== undefined) return;
    if (this.state !== 'idle' && this.state !== 'detached') return;
    this.openOrResume();
  }

  /** The link reconnects with new names: open a new session then, since ids may move. */
  forgetResume(): void {
    this.resumable = false;
  }

  /** Sends what waited for its names, or drops it when the mod still did not register them. */
  private sendAwaiting(): void {
    const waiting = this.awaiting;
    this.awaiting = [];
    for (const m of waiting) {
      if (this.link.messageId(m.type) === undefined) {
        this.link.log.warn('dropping a message the mod did not register', { type: m.type });
        continue;
      }
      if (m.reliable) this.reliable(m.type, m.payload, m.req);
      else this.transmitNamed(m.type, m.payload);
    }
    if (waiting.length > 0) this.pump();
  }

  linkLost(): void {
    if (this.state === 'closed') return;
    clearTimeout(this.ackTimer);
    this.ackTimer = undefined;
    clearTimeout(this.openTimer);
    this.openTimer = undefined;
    const wasOpen = this.state === 'open';
    this.state = this.resumeToken !== undefined ? 'detached' : 'idle';
    this.sessionId = 0;
    if (wasOpen) this.handlers.lost();
  }

  private openOrResume(): void {
    if (this.resumeToken === undefined || !this.resumable) {
      this.openFresh();
      return;
    }
    this.state = 'opening';
    this.link.requestConn('session.resume', { resumeToken: this.resumeToken, lastApplied: this.inApplied }, (error, response) => {
      if (this.state === 'closed' && error === undefined) return this.closeLate((response as MessagePayloads['session.resumed']).sessionId);
      if (this.state !== 'opening') return;
      if (error === undefined) {
        this.onResumed(response as MessagePayloads['session.resumed']);
        return;
      }
      if (this.link.state !== 'ready') {
        this.state = 'detached';
        return;
      }
      this.link.log.debug('resume failed, opening a new session', { error: error.message });
      this.openFresh();
    });
  }

  private onResumed(r: MessagePayloads['session.resumed']): void {
    this.sessionId = r.sessionId;
    this.link.bind(r.sessionId, this);
    this.trimLog(r.lastApplied);
    // Everything above the mod's lastApplied is re-sent by pump().
    this.sentUpTo = r.lastApplied;
    this.ackSent = 0;
    this.state = 'open';
    for (const [t, p] of this.lastLatest) if (!this.latest.has(t)) this.latest.set(t, p);
    this.handlers.opened(false);
    this.scheduleFlush();
    this.sendAwaiting();
    this.pump();
  }

  private openFresh(): void {
    this.state = 'opening';
    this.resetSequencing();
    const payload: MessagePayloads['session.open'] = { kind: this.kind, name: this.name, identity: this.identity, extensions: this.extensionRefs() };
    this.link.requestConn('session.open', payload, (error, response) => {
      if (this.state === 'closed' && error === undefined) return this.closeLate((response as MessagePayloads['session.opened']).sessionId);
      if (this.state !== 'opening') return;
      if (error !== undefined) {
        this.state = 'idle';
        this.onOpenFailed(error);
        return;
      }
      this.openAttempt = 0;
      const r = response as MessagePayloads['session.opened'];
      this.sessionId = r.sessionId;
      this.resumeToken = r.resumeToken;
      this.link.bind(r.sessionId, this);
      this.state = 'open';
      this.superseded = false;
      this.handlers.sharedReset();
      this.sendSnapshot();
      if (this.subscription !== undefined) this.subscriptionDirty = true;
      for (const [t, p] of this.lastLatest) if (!this.latest.has(t)) this.latest.set(t, p);
      this.handlers.opened(true);
      this.flush();
      this.sendAwaiting();
    });
  }

  /**
   * A refused `session.open`: retryable refusals are tried again with backoff while the link stays
   * up; any other one is reported and waits for the next connection.
   */
  private onOpenFailed(error: BridgeError): void {
    if (this.link.state !== 'ready') return;
    if (!error.retryable && !OPEN_RETRY_CODES.has(error.code)) {
      this.openRefused = true;
      this.link.log.warn('session.open refused', { code: error.code, error: error.message, name: this.name });
      this.handlers.openFailed(error);
      return;
    }
    const delay = Math.min(OPEN_RETRY_MAX_MS, error.retryAfterMs ?? OPEN_RETRY_INITIAL_MS * 2 ** this.openAttempt);
    this.openAttempt++;
    this.link.log.debug('session.open failed, retrying', { code: error.code, error: error.message, name: this.name, delayMs: delay });
    this.openTimer = setTimeout(() => {
      this.openTimer = undefined;
      this.maybeOpen();
    }, delay);
    this.openTimer.unref?.();
  }

  /** Forgets sequencing state for a new session; requests already sent cannot complete. */
  private resetSequencing(): void {
    this.outSeq = 0;
    this.sentUpTo = 0;
    this.log = [];
    this.logBytes = 0;
    this.resumable = true;
    this.inApplied = 0;
    this.ackSent = 0;
    this.resumeToken = undefined;
    this.streams.reset();
    const sent = this.queued.filter((q) => q.sent);
    this.queued = this.queued.filter((q) => !q.sent);
    for (const q of sent) {
      // If the session is lost and resume fails, pending requests reject.
      if (q.req !== undefined) this.link.cancel(q.req, new BridgeError('disconnected', 'session lost before the response arrived', { retryable: true }));
    }
  }

  /** Sends the declared state after open: begin, batches of at most 256 writes, end. */
  private sendSnapshot(): void {
    const all = this.store.snapshot();
    // Offers go first, so each object arrives with its stream in flight
    // and the mod answers `have` for what it cached in an earlier session.
    for (const o of all) if (o.blob !== undefined) this.streams.want(o.blob);
    this.streams.offerQueued();
    this.reliable('obj.snapshot.begin', this.link.encode('obj.snapshot.begin', {}));
    this.sendWrites(all.map((o) => ({ type: 'full' as const, object: o })));
    this.reliable('obj.snapshot.end', this.link.encode('obj.snapshot.end', {}));
  }

  private toWire(w: StoreWrite, owner: string): ObjectWrite | undefined {
    const o = w.object;
    const key = `${owner}/${o.key}`;
    const traits = wireTraits(w.type === 'patch' ? w.traits : o.traits, owner);
    if (w.type === 'patch') return { type: 'patch', value: { key, rev: o.rev, fields: w.fields, ttlMs: o.ttlMs, traits } };
    const kind = this.link.kindId(o.kind);
    if (kind === undefined) {
      // Not offered in this connection's hello yet: the link reconnects and a snapshot follows.
      if (this.link.awaitingName(o.kind)) return undefined;
      if (!this.warnedKinds.has(o.kind)) {
        this.warnedKinds.add(o.kind);
        this.link.log.warn(`mod does not support kind ${o.kind}; objects of it are not sent`);
      }
      return undefined;
    }
    return { type: 'full', value: { key, kind, rev: o.rev, layer: o.layer, lifetime: o.lifetime, scope: o.scope, world: o.world, ttlMs: o.ttlMs, data: o.data, traits } };
  }

  private sendWrites(writes: StoreChanges['writes']): void {
    const owner = this.owner;
    if (owner === undefined || writes.length === 0) return;
    const byteBudget = Math.floor(this.link.maxFrameBytes / 2);
    let batch: ObjectWrite[] = [];
    let bytes = 0;
    const emit = (): void => {
      if (batch.length === 0) return;
      this.reliable('obj.upsert', this.link.encode('obj.upsert', { writes: batch }));
      batch = [];
      bytes = 0;
    };
    for (const w of writes) {
      const wire = this.toWire(w, owner);
      if (wire === undefined) continue;
      const size = (wire.type === 'full' ? wire.value.data.length : wire.value.fields.length) + wire.value.key.length + 32;
      // Declared before the mod advertised a smaller frame limit; sending it would end the connection.
      if (size > this.link.maxFrameBytes - OBJECT_FRAME_OVERHEAD) {
        this.handlers.writeError(new BridgeError('quota_exceeded', `${wire.value.key} is too large for one frame (${size} bytes, limit ${this.link.maxFrameBytes}); it was not sent`), 'obj.upsert');
        continue;
      }
      if (batch.length >= MAX_WRITES_PER_UPSERT || (bytes + size > byteBudget && batch.length > 0)) emit();
      batch.push(wire);
      bytes += size;
    }
    emit();
  }

  /**
   * Queues a reliable message. It is sent in order when the session is open and writable. While the
   * session is not open at most {@link MAX_QUEUED_MESSAGES} wait; the oldest are dropped beyond that.
   */
  send(type: MessageName | (string & {}), payload: Uint8Array): void {
    this.queuedMessages.push({ type, payload });
    if (this.state !== 'open' && this.queuedMessages.length > MAX_QUEUED_MESSAGES) {
      const dropped = this.queuedMessages.splice(0, this.queuedMessages.length - MAX_QUEUED_MESSAGES);
      // One warning per closed spell, so a bot that keeps sending while offline does not flood the log.
      if (!this.droppedWarned) {
        this.droppedWarned = true;
        this.link.log.warn(`the session is not open and ${MAX_QUEUED_MESSAGES} messages already wait; dropping the oldest`, { name: this.name, dropped: dropped.length });
      }
    }
    this.scheduleFlush();
  }

  /** Sends a reliable message only while the session is open; otherwise it is dropped. Returns whether it was queued. */
  sendIfOpen(type: MessageName | (string & {}), payload: Uint8Array): boolean {
    if (this.state !== 'open') return false;
    this.send(type, payload);
    return true;
  }

  /**
   * Queues an unreliable message for the next flush (dropped under backpressure). It is dropped
   * when the session is not open, when reliable messages are still backed up, or when the socket or
   * budget has no room; it is never logged or replayed.
   */
  sendUnreliable(type: MessageName | (string & {}), payload: Uint8Array): void {
    if (this.state !== 'open') return;
    this.unreliable.push({ type, payload });
    this.scheduleFlush();
  }

  /** Sets a latest-wins value; only the newest per message type is sent. */
  sendLatest(type: MessageName | (string & {}), payload: Uint8Array): void {
    this.latest.set(type, payload);
    this.scheduleFlush();
  }

  /** Adds to the shared subscription; the union is sent as one `shared.subscribe`. */
  subscribe(kinds: readonly string[], extensions: readonly string[]): void {
    const sub = (this.subscription ??= { kinds: new Set(), extensions: new Set(), all: false });
    if (kinds.length === 0 && extensions.length === 0) sub.all = true;
    for (const k of kinds) sub.kinds.add(k);
    for (const e of extensions) sub.extensions.add(e);
    this.subscriptionDirty = true;
    this.scheduleFlush();
  }

  /**
   * A reliable in-session request. Queued until the session is open; the timeout runs from when it
   * is logged. Requests survive a resume because they are replayed. With `cancel`, aborting or
   * timing out after the request went out also sends that message, so the mod withdraws it.
   */
  request<N extends MessageName>(type: N, payload: MessagePayloads[N], timeoutMs: number, signal?: AbortSignal, cancel?: CancelMessage): Promise<unknown> {
    return new Promise((resolve, reject) => {
      if (signal?.aborted === true) {
        reject(new BridgeError('cancelled', 'request cancelled'));
        return;
      }
      const onAbort = (): void => {
        if (q.req === undefined) {
          this.queued = this.queued.filter((x) => x !== q);
          q.reject(new BridgeError('cancelled', 'request cancelled'));
          return;
        }
        const req = q.req;
        if (this.link.cancel(req, new BridgeError('cancelled', 'request cancelled')) && q.cancel !== undefined) this.sendCancel(q.cancel, req);
      };
      const q: QueuedRequest = {
        type,
        payload: this.link.encode(type, payload),
        timeoutMs,
        resolve: (p) => {
          signal?.removeEventListener('abort', onAbort);
          clearTimeout(q.waitTimer);
          resolve(p);
        },
        reject: (e) => {
          signal?.removeEventListener('abort', onAbort);
          clearTimeout(q.waitTimer);
          reject(e);
        },
        req: undefined,
        sent: false,
        cancel,
        // A request that never gets sent must not wait for ever on a session that stays closed.
        waitTimer: setTimeout(() => {
          if (q.sent) return;
          this.queued = this.queued.filter((x) => x !== q);
          q.reject(new BridgeError('disconnected', `the session did not open within ${timeoutMs} ms, so the request was not sent`, { retryable: true }));
        }, timeoutMs),
      };
      signal?.addEventListener('abort', onAbort, { once: true });
      this.queued.push(q);
      this.scheduleFlush();
    });
  }

  private sendCancel(type: CancelMessage, request: number): void {
    if (this.state === 'closed') return;
    this.send(type, this.link.encode(type, { request }));
  }

  scheduleFlush(): void {
    if (this.flushScheduled) return;
    this.flushScheduled = true;
    // Writes made in the same tick go out together.
    setImmediate(() => {
      this.flushScheduled = false;
      this.flush();
    });
  }

  private flush(): void {
    this.flushReliable();
    const pending = this.unreliable;
    this.unreliable = [];
    if (this.state !== 'open') return;
    for (const m of pending) {
      if (this.sentUpTo < this.outSeq || !this.link.writable || this.link.pacer.take(m.payload.length) !== 0) continue;
      this.transmitNamed(m.type, m.payload);
    }
  }

  private flushReliable(): void {
    if (this.state !== 'open') return;
    this.droppedWarned = false;
    // Materialise new reliable messages only once the backlog is out, so changes made while the
    // socket or budget is saturated keep coalescing in the store.
    if (this.sentUpTo < this.outSeq) {
      this.pump();
      if (this.sentUpTo < this.outSeq) return;
    }
    if (this.subscriptionDirty && this.subscription !== undefined) {
      this.subscriptionDirty = false;
      const sub = this.subscription;
      this.reliable('shared.subscribe', this.link.encode('shared.subscribe', sub.all ? { kinds: [], extensions: [] } : { kinds: [...sub.kinds], extensions: [...sub.extensions] }));
    }
    if (!this.superseded && !this.drainStore()) this.streams.offerQueued();
    for (const m of this.queuedMessages) this.reliable(m.type, m.payload);
    this.queuedMessages = [];
    for (const q of this.queued) {
      if (q.sent) continue;
      q.sent = true;
      clearTimeout(q.waitTimer);
      q.req = this.link.allocReq();
      this.link.expect(q.req, this, q.timeoutMs, (p) => {
        this.queued = this.queued.filter((x) => x !== q);
        q.resolve(p);
      }, (e) => {
        this.queued = this.queued.filter((x) => x !== q);
        if (e.code === 'timeout' && q.cancel !== undefined && q.req !== undefined) this.sendCancel(q.cancel, q.req);
        q.reject(e);
      });
      this.reliable(q.type, q.payload, q.req);
    }
    // One chunk per flush, after everything else that was waiting. Chunks also
    // wait while half the outbound log is unacknowledged, so a transfer never costs the ability to resume.
    if (!this.superseded && this.logBytes <= LOG_CAP_BYTES / 2 && this.streams.chunk()) this.scheduleFlush();
    this.pump();
  }

  /**
   * Opens a transaction: declared-state changes are held until the matching {@link endTx}, then
   * sent between `obj.begin` and `obj.commit`. Calls nest; only the outermost end sends.
   */
  beginTx(): void {
    this.txDepth++;
  }

  endTx(): void {
    if (this.txDepth === 0) return;
    if (--this.txDepth > 0) return;
    this.txPending = true;
    this.scheduleFlush();
  }

  /**
   * Logs pending declared-state changes as `obj.remove` and `obj.upsert`, bracketed by
   * `obj.begin`/`obj.commit` when a transaction ended since the last drain. Returns false when there
   * were none; a transaction without changes sends nothing.
   */
  private drainStore(): boolean {
    const owner = this.owner;
    if (this.txDepth > 0 || this.superseded || owner === undefined) return false;
    const tx = this.txPending && this.store.hasChanges ? (this.txSeq = (this.txSeq % MAX_TX_ID) + 1) : undefined;
    this.txPending = false;
    if (!this.store.hasChanges) {
      this.releaseAfterTx();
      return false;
    }
    if (tx !== undefined) this.reliable('obj.begin', this.link.encode('obj.begin', { tx }));
    const changes = this.store.drain();
    if (changes.keys.length > 0 || changes.prefixes.length > 0) {
      this.reliable('obj.remove', this.link.encode('obj.remove', { keys: changes.keys.map((k) => `${owner}/${k}`), prefixes: changes.prefixes.map((p) => `${owner}/${p}`) }));
    }
    for (const w of changes.writes) if (w.object.blob !== undefined) this.streams.want(w.object.blob);
    this.streams.offerQueued();
    this.sendWrites(changes.writes);
    if (tx !== undefined) this.reliable('obj.commit', this.link.encode('obj.commit', { tx }));
    this.releaseAfterTx();
    return true;
  }

  private releaseAfterTx(): void {
    const waiting = this.afterTx;
    this.afterTx = [];
    for (const fn of waiting) fn();
  }

  /** A payload that cannot fit in one frame; the mod would close the connection over it. */
  private tooLarge(type: string, payload: Uint8Array): BridgeError | undefined {
    const room = this.link.maxFrameBytes - FRAME_HEADER_ROOM;
    if (payload.length <= room) return undefined;
    return new BridgeError('quota_exceeded', `${type} of ${payload.length} bytes does not fit in one frame (limit ${this.link.maxFrameBytes}); it was not sent`, {
      details: { bytes: payload.length, limit: room },
    });
  }

  private reliable(type: string, payload: Uint8Array, req?: number): void {
    const big = this.tooLarge(type, payload);
    if (big !== undefined) {
      // Dropped before it takes a sequence number, so the stream stays whole.
      if (req === undefined || !this.link.cancel(req, big)) this.handlers.writeError(big, type);
      return;
    }
    const id = this.link.messageId(type);
    if (id === undefined) {
      if (this.link.awaitingName(type)) this.keepAwaiting({ type, payload, reliable: true, req });
      else this.link.log.warn('dropping a message the mod did not register', { type });
      return;
    }
    const seq = ++this.outSeq;
    this.log.push({ seq, type: id, flags: 0, req, payload });
    this.logBytes += payload.length;
    if (this.logBytes > LOG_CAP_BYTES) this.resumable = false;
  }

  /** Sends an unsequenced message by name; extension names the mod did not register are dropped. */
  private transmitNamed(type: string, payload: Uint8Array): void {
    const big = this.tooLarge(type, payload);
    if (big !== undefined) {
      this.handlers.writeError(big, type);
      return;
    }
    const id = this.link.messageId(type);
    if (id !== undefined) this.transmit(id, 0, payload, undefined, undefined);
    else if (this.link.awaitingName(type)) this.keepAwaiting({ type, payload, reliable: false, req: undefined });
  }

  /** Keeps a message until its name is registered, within the same cap as other waiting messages. */
  private keepAwaiting(m: { type: string; payload: Uint8Array; reliable: boolean; req: number | undefined }): void {
    this.awaiting.push(m);
    if (this.awaiting.length > MAX_QUEUED_MESSAGES) this.awaiting.shift();
  }

  /** Can another frame go out now? If not, arranges a retry and returns false. */
  private gate(bytes: number): boolean {
    if (!this.link.writable) {
      if (!this.waitingWritable) {
        this.waitingWritable = true;
        this.link.whenWritable(() => {
          this.waitingWritable = false;
          this.flush();
        });
      }
      return false;
    }
    const wait = this.link.pacer.take(bytes);
    if (wait === 0) return true;
    if (this.retryTimer === undefined) {
      this.retryTimer = setTimeout(() => {
        this.retryTimer = undefined;
        this.flush();
      }, wait);
    }
    return false;
  }

  /** Sends logged messages not yet on this connection, then the latest-wins slots. */
  private pump(): void {
    if (this.state !== 'open') return;
    while (this.sentUpTo < this.outSeq) {
      const first = this.log[0];
      const entry = first === undefined ? undefined : this.log[this.sentUpTo + 1 - first.seq];
      if (entry === undefined) {
        this.sentUpTo = this.outSeq;
        break;
      }
      if (!this.gate(entry.payload.length)) return;
      this.transmit(entry.type, entry.flags, entry.payload, entry.seq, entry.req);
      this.sentUpTo = entry.seq;
    }
    for (const [type, payload] of [...this.latest]) {
      if (!this.gate(payload.length)) return;
      this.latest.delete(type);
      this.lastLatest.set(type, payload);
      this.transmitNamed(type, payload);
    }
  }

  private transmit(type: number, flags: number, payload: Uint8Array, seq: number | undefined, req: number | undefined): void {
    const header: EnvelopeHeader = { flags, type, session: this.sessionId };
    if (seq !== undefined) header.seq = seq;
    if (this.inApplied > 0) header.ack = this.inApplied;
    if (req !== undefined) header.req = req;
    if (this.link.sendFrame(header, payload)) this.ackSent = this.inApplied;
  }

  /** Answers a mod request: a reliable, sequenced response carrying the request's id. */
  private respond(type: MessageName, req: number, body: Uint8Array): void {
    const seq = ++this.outSeq;
    this.log.push({ seq, type: Messages[type].id, flags: Flags.RESPONSE, req, payload: body });
    this.logBytes += body.length;
    this.pump();
  }

  private trimLog(ack: number): void {
    let drop = 0;
    while (drop < this.log.length && this.log[drop]!.seq <= ack) {
      this.logBytes -= this.log[drop]!.payload.length;
      drop++;
    }
    if (drop > 0) this.log.splice(0, drop);
  }

  private scheduleAck(): void {
    if (this.ackTimer !== undefined) return;
    this.ackTimer = setTimeout(() => {
      this.ackTimer = undefined;
      // An empty ack after 250 ms when nothing else carried it.
      if (this.state === 'open' && this.inApplied > this.ackSent) this.transmit(Messages.ack.id, 0, this.link.encode('ack', {}), undefined, undefined);
    }, EMPTY_ACK_DELAY_MS);
  }

  /** Handles one frame addressed to this session. */
  onFrame(header: EnvelopeHeader, type: string, payload: unknown): void {
    if (this.state !== 'open') return;
    if (header.ack !== undefined) {
      this.trimLog(header.ack);
      if (this.streams.sendable) this.scheduleFlush();
    }
    if (header.seq !== undefined) {
      if (header.seq <= this.inApplied) return;
      if (header.seq !== this.inApplied + 1) {
        // A gap is a protocol error; report it and start over.
        this.transmit(Messages.error.id, Flags.ERROR, this.link.encode('error', { error: { code: 'invalid_message', message: `sequence gap at ${header.seq}`, retryable: false }, relatesTo: header.type }), undefined, undefined);
        this.transmit(Messages['session.close'].id, 0, this.link.encode('session.close', { reason: 'invalid_message' }), undefined, undefined);
        this.restartFresh();
        return;
      }
      this.inApplied = header.seq;
      this.scheduleAck();
    }
    if ((header.flags & Flags.RESPONSE) !== 0) {
      this.link.settle(header, type, payload);
      return;
    }
    switch (type as MessageName) {
      case 'player.context':
        this.handlers.context(payload as MessagePayloads['player.context']);
        return;
      case 'interest':
        this.handlers.interest(payload as MessagePayloads['interest']);
        return;
      case 'obj.upsert':
        this.handlers.sharedUpsert((payload as MessagePayloads['obj.upsert']).writes);
        return;
      case 'obj.remove':
        this.handlers.sharedRemove(payload as MessagePayloads['obj.remove']);
        return;
      case 'budget.update':
        this.link.budget = (payload as MessagePayloads['budget.update']).budget;
        return;
      case 'action.invoked':
      case 'command.invoked':
      case 'keybind.event':
      case 'tool.event':
      case 'tool.state':
      case 'settings.values':
      case 'container.open':
      case 'container.close':
      case 'container.input':
      case 'container.storage':
      case 'element.event':
      case 'intercept.event':
      case 'screen.event':
      case 'screen.open':
      case 'screen.close':
        this.handlers.input(type as InboundInput, payload as MessagePayloads[InboundInput]);
        return;
      case 'chat.message':
        this.handlers.chat(payload as MessagePayloads['chat.message']);
        return;
      case 'client.sample':
        this.handlers.sample(payload as MessagePayloads['client.sample']);
        return;
      case 'error': {
        const p = payload as MessagePayloads['error'];
        const err = errorFromPayload(p, this.link.remote);
        if (err.code === 'superseded') {
          if (!this.superseded) {
            this.superseded = true;
            this.handlers.superseded();
          }
        } else {
          this.handlers.writeError(err, p.relatesTo === undefined ? undefined : this.link.messageName(p.relatesTo));
        }
        return;
      }
      case 'session.close': {
        const reason = (payload as MessagePayloads['session.close']).reason;
        if (reason === 'superseded') {
          this.superseded = true;
          this.handlers.superseded();
          return;
        }
        // A newer session of this bot's name from this device took over: opening again would take it back.
        if (reason === 'replaced') {
          this.link.unbind(this.sessionId);
          this.sessionId = 0;
          this.resumeToken = undefined;
          this.state = 'idle';
          this.openRefused = true;
          this.link.log.warn('session replaced by a newer one of the same bot name', { name: this.name });
          this.handlers.lost();
          return;
        }
        this.restartFresh();
        return;
      }
      case 'obj.edit.propose': {
        const req = header.req;
        if (req === undefined) return;
        const session = this.sessionId;
        this.handlers.editPropose(payload as MessagePayloads['obj.edit.propose'], (answer) => {
          if (this.state !== 'open' || this.sessionId !== session) return;
          const send = (): void => {
            if (this.state === 'open' && this.sessionId === session) this.respond('obj.edit.result', req, this.link.encode('obj.edit.result', answer));
          };
          // The mod clears the pending edit on the answer, so the accepted data must already be logged;
          // inside a transaction it is logged with the commit.
          if (answer.accepted && this.txDepth > 0) {
            this.afterTx.push(send);
            return;
          }
          if (answer.accepted) this.drainStore();
          send();
        });

        return;
      }
      case 'command.complete': {
        const req = header.req;
        if (req === undefined) return;
        const session = this.sessionId;
        this.handlers.complete(payload as MessagePayloads['command.complete'], (answer) => {
          if (this.state !== 'open' || this.sessionId !== session) return;
          this.respond('command.suggestions', req, this.link.encode('command.suggestions', answer));
        });
        return;
      }
      case 'stream.accept':
        this.streams.onAccept(payload as MessagePayloads['stream.accept']);
        this.scheduleFlush();
        return;
      case 'stream.window':
        this.streams.onWindow(payload as MessagePayloads['stream.window']);
        this.scheduleFlush();
        return;
      case 'stream.have':
        this.streams.onHave(payload as MessagePayloads['stream.have']);
        this.scheduleFlush();
        return;
      case 'stream.reject':
        this.streams.onReject(payload as MessagePayloads['stream.reject']);
        this.scheduleFlush();
        return;
      case 'ping':
        this.transmit(Messages.pong.id, 0, this.link.encode('pong', { time: (payload as MessagePayloads['ping']).time }), undefined, undefined);
        return;
      default:
        // A message of a mod extension, decoded with the codec its Node half listed.
        if (type.includes(':')) this.handlers.extension?.(type, payload);
        return;
    }
  }

  private restartFresh(): void {
    this.link.unbind(this.sessionId);
    this.sessionId = 0;
    this.resumeToken = undefined;
    this.state = 'idle';
    this.handlers.lost();
    this.maybeOpen();
  }

  /** The mod opened the session after it was closed here, as when a bot ends during the open: close it there too. */
  private closeLate(sessionId: number): void {
    if (this.link.state !== 'ready') return;
    this.link.sendFrame({ flags: 0, type: Messages['session.close'].id, session: sessionId }, this.link.encode('session.close', { reason: 'closed' }));
  }

  /** Ends the session with `session.close`; the mod drops its objects. */
  async close(reason = 'closed'): Promise<void> {
    if (this.state === 'closed') return;
    if (this.state === 'open') {
      this.flush();
      this.transmit(Messages['session.close'].id, 0, this.link.encode('session.close', { reason }), undefined, undefined);
    }
    this.state = 'closed';
    clearTimeout(this.ackTimer);
    clearTimeout(this.retryTimer);
    clearTimeout(this.openTimer);
    this.link.rejectAll(this, new BridgeError('cancelled', 'session closed'));
    for (const q of [...this.queued]) if (!q.sent) q.reject(new BridgeError('cancelled', 'session closed'));
    this.queued = [];
    this.store.dispose();
    this.offRelease();
    this.link.delete(this);
    await Promise.resolve();
  }
}
