import { randomBytes } from 'node:crypto';
import { EventEmitter } from 'node:events';
import type { WebSocket } from 'ws';
import {
  Flags,
  Messages,
  WireWriter,
  writeHeader,
  type BotIdentity,
  type ClientSample,
  type EnvelopeHeader,
  type ExtensionRef,
  type MessageName,
  type MessagePayloads,
  type SessionKind,
  type WorldKey,
} from '@mineflayer-bridge/protocol';
import type { InStream } from './streams.ts';
import type { MockModEvents, MockSampleRequest } from './types.ts';

/** Past this many logged bytes a session can no longer resume. */
const LOG_CAP_BYTES = 4 * 1024 * 1024;
/** An empty ack goes out after 250 ms when nothing else carried it. */
const EMPTY_ACK_DELAY_MS = 250;

/** A sequenced message kept for re-sending on resume. */
interface LogEntry {
  seq: number;
  type: number;
  flags: number;
  req: number | undefined;
  payload: Uint8Array;
}

/** One WebSocket connection and its handshake state. */
export interface Conn {
  id: number;
  ws: WebSocket;
  remoteAddress: string;
  loopback: boolean;
  /** The socket is TLS; a pairing code is accepted only then. */
  tls: boolean;
  state: 'hello' | 'auth' | 'approving' | 'ready' | 'closed';
  nonce: Uint8Array;
  /** The secret the device authenticated with; `welcome.proof` is made with it. */
  secret: Uint8Array | undefined;
  deviceId: string;
  deviceName: string;
  /** Registry ids the mock assigned to the extension names from `hello`. */
  registry: Map<string, number>;
  namesById: Map<number, string>;
  kindNames: Set<string>;
  sessions: Map<number, Session>;
  nextSessionId: number;
  /** Wall-clock ms of the last frame in each direction, for the heartbeat. */
  lastInbound: number;
  lastOutbound: number;
  blackholed: boolean;
}

/** A buffered write of an open transaction. */
export type TxWrite = { type: 'upsert'; payload: MessagePayloads['obj.upsert'] } | { type: 'remove'; payload: MessagePayloads['obj.remove'] };

/** A transaction between `obj.begin` and `obj.commit`. */
export interface OpenTransaction {
  readonly id: number;
  /** Clock ms of `obj.begin`. */
  readonly startedAt: number;
  readonly writes: TxWrite[];
}

/** A bot or controller session. It outlives its connection for the resume window. */
export interface Session {
  token: string;
  conn: Conn | null;
  sessionId: number;
  kind: SessionKind;
  name: string;
  identity: BotIdentity | undefined;
  owner: string;
  /** `<owner>/`, the key prefix the session may write. */
  prefix: string;
  deviceId: string;
  extensions: ExtensionRef[];
  outSeq: number;
  outLog: LogEntry[];
  outLogBytes: number;
  resumable: boolean;
  inApplied: number;
  ackSent: number;
  ackTimer: NodeJS.Timeout | undefined;
  /** Clock ms when the connection dropped; `undefined` while connected. */
  droppedAt: number | undefined;
  world: WorldKey | undefined;
  position: { x: number; y: number; z: number } | undefined;
  status: unknown;
  subscription: { kinds: string[]; extensions: string[] } | undefined;
  /** Keys written since `obj.snapshot.begin`, or `undefined` outside a snapshot. */
  snapshot: Set<string> | undefined;
  /** Writes buffered since `obj.begin`, applied on the matching `obj.commit`. */
  tx: OpenTransaction | undefined;
  superseded: boolean;
  closed: boolean;
  /** Last `player.context` and `interest` sent, as JSON, so only changes go out. */
  lastContext: string | undefined;
  lastInterest: string | undefined;
  /** Active tool key last sent with `tool.state`; `undefined` when none of this session's tools is active. */
  toolActive: string | undefined;
  /** Incoming blob streams by the plugin's stream id; they survive a resume. */
  streams: Map<number, InStream>;
  /** Latest `sample.request`, or `undefined` when nothing is requested. */
  sampleRequest: MockSampleRequest | undefined;
  /** Wall-clock ms of the last `client.sample` sent, for the rate limit. */
  sampleSentAt: number;
  /** Trailing sample waiting for the rate limit, and its timer. */
  samplePending: ClientSample | undefined;
  sampleTimer: NodeJS.Timeout | undefined;
}

/** A new session on `conn`, with nothing sent or received yet. */
export function createSession(conn: Conn, kind: SessionKind, name: string, identity: BotIdentity | undefined, owner: string, extensions: ExtensionRef[]): Session {
  return {
    token: randomBytes(16).toString('hex'),
    conn,
    sessionId: conn.nextSessionId++,
    kind,
    name,
    identity,
    owner,
    prefix: `${owner}/`,
    deviceId: conn.deviceId,
    extensions,
    outSeq: 0,
    outLog: [],
    outLogBytes: 0,
    resumable: true,
    inApplied: 0,
    ackSent: 0,
    ackTimer: undefined,
    droppedAt: undefined,
    world: undefined,
    position: undefined,
    status: undefined,
    subscription: undefined,
    snapshot: undefined,
    tx: undefined,
    superseded: false,
    closed: false,
    lastContext: undefined,
    lastInterest: undefined,
    toolActive: undefined,
    streams: new Map(),
    sampleRequest: undefined,
    sampleSentAt: 0,
    samplePending: undefined,
    sampleTimer: undefined,
  };
}

/** True for a session that is neither closed nor superseded. */
export function isLive(s: Session): boolean {
  return !s.closed && !s.superseded;
}

/** Drops logged messages the peer acknowledged up to `ack`. */
export function trimLog(s: Session, ack: number): void {
  let drop = 0;
  while (drop < s.outLog.length && s.outLog[drop]!.seq <= ack) {
    s.outLogBytes -= s.outLog[drop]!.payload.length;
    drop++;
  }
  if (drop > 0) s.outLog.splice(0, drop);
}

/** Envelope options of an outbound message. */
export interface SendOptions {
  req?: number;
  response?: boolean;
  error?: boolean;
}

/** Events, framing, sequencing and error replies shared by every part of the mock. */
export class Transport {
  private readonly emitter = new EventEmitter();
  private readonly writer = new WireWriter(4096);
  private readonly payloadWriter = new WireWriter(4096);

  /** Subscribes to an event. Returns a function that removes the listener. */
  on<E extends keyof MockModEvents>(event: E, listener: (...args: MockModEvents[E]) => void): () => void {
    this.emitter.on(event, listener as (...a: unknown[]) => void);
    return () => this.emitter.off(event, listener as (...a: unknown[]) => void);
  }

  emit<E extends keyof MockModEvents>(event: E, ...args: MockModEvents[E]): void {
    this.emitter.emit(event, ...args);
  }

  sendConn<N extends MessageName>(conn: Conn, type: N, payload: MessagePayloads[N], o: SendOptions = {}): void {
    const header: EnvelopeHeader = { flags: flagsOf(o), type: Messages[type].id, session: 0 };
    if (o.req !== undefined) header.req = o.req;
    this.rawSend(conn, header, this.encodePayload(type, payload));
  }

  sendSession<N extends MessageName>(s: Session, type: N, payload: MessagePayloads[N], o: SendOptions = {}): void {
    const t = Messages[type];
    const flags = flagsOf(o);
    const bytes = this.encodePayload(type, payload);
    let seq: number | undefined;
    // Errors inside a session are sequenced so they survive a resume.
    if (t.delivery === 'reliable' || type === 'error') {
      seq = ++s.outSeq;
      s.outLog.push({ seq, type: t.id, flags, req: o.req, payload: bytes });
      s.outLogBytes += bytes.length;
      // Overflowing the cap is not an error, it only makes resume impossible.
      if (s.outLogBytes > LOG_CAP_BYTES) s.resumable = false;
    }
    this.transmit(s, t.id, flags, bytes, seq, o.req);
  }

  /**
   * Sends an extension message as raw bytes with the id the connection negotiated for its
   * name; reliable ones are sequenced and logged. Returns false when the connection did not register it.
   */
  sendExtension(s: Session, name: string, bytes: Uint8Array, reliable: boolean): boolean {
    const id = s.conn?.registry.get(name);
    if (id === undefined || s.conn?.kindNames.has(name) === true) return false;
    let seq: number | undefined;
    if (reliable) {
      seq = ++s.outSeq;
      s.outLog.push({ seq, type: id, flags: 0, req: undefined, payload: bytes });
      s.outLogBytes += bytes.length;
      if (s.outLogBytes > LOG_CAP_BYTES) s.resumable = false;
    }
    this.transmit(s, id, 0, bytes, seq, undefined);
    return true;
  }

  /** Re-sends logged messages above `lastApplied` with their original seq. */
  replay(s: Session, lastApplied: number): void {
    for (const e of s.outLog) {
      if (e.seq > lastApplied) this.transmit(s, e.type, e.flags, e.payload, e.seq, e.req);
    }
  }

  /** Sends an error on the connection, or inside `session` when it is non-zero and known. */
  sendError(conn: Conn, session: number, code: string, message: string, header: EnvelopeHeader | undefined, details?: unknown): void {
    this.emit('protocolError', { connectionId: conn.id, code, message });
    const s = session === 0 ? undefined : conn.sessions.get(session);
    const payload: MessagePayloads['error'] = { error: { code, message, retryable: false, details }, relatesTo: header?.type };
    if (s !== undefined) this.sendSession(s, 'error', payload, errorOptions(header));
    else this.sendConn(conn, 'error', payload, errorOptions(header));
  }

  /** Sends an error inside a session; `relatesTo` defaults to the type in `header`. */
  sendSessionError(s: Session, code: string, message: string, header: EnvelopeHeader | undefined, relatesTo?: number, details?: unknown, retryable = false): void {
    if (s.conn !== null) this.emit('protocolError', { connectionId: s.conn.id, code, message });
    const payload: MessagePayloads['error'] = { error: { code, message, retryable, details }, relatesTo: relatesTo ?? header?.type };
    this.sendSession(s, 'error', payload, errorOptions(header));
  }

  /** Sends `goodbye` and closes the socket. */
  goodbye(conn: Conn, reason: string, message: string, retry: boolean): void {
    if (conn.state === 'closed') return;
    this.sendConn(conn, 'goodbye', { reason, message, retry });
    conn.ws.close(1000, reason);
  }

  /** Schedules an empty ack unless one is pending. */
  scheduleAck(s: Session): void {
    if (s.ackTimer !== undefined) return;
    s.ackTimer = setTimeout(() => {
      s.ackTimer = undefined;
      if (s.conn !== null && s.inApplied > s.ackSent) this.sendSession(s, 'ack', {});
    }, EMPTY_ACK_DELAY_MS);
  }

  private encodePayload<N extends MessageName>(type: N, payload: MessagePayloads[N]): Uint8Array {
    this.payloadWriter.reset();
    Messages[type].codec.write(this.payloadWriter, payload);
    return this.payloadWriter.toBytes();
  }

  private transmit(s: Session, type: number, flags: number, payload: Uint8Array, seq: number | undefined, req: number | undefined): void {
    const conn = s.conn;
    if (conn === null) return;
    const header: EnvelopeHeader = { flags, type, session: s.sessionId };
    if (seq !== undefined) header.seq = seq;
    if (s.inApplied > 0) {
      header.ack = s.inApplied;
      s.ackSent = s.inApplied;
    }
    if (req !== undefined) header.req = req;
    this.rawSend(conn, header, payload);
  }

  private rawSend(conn: Conn, header: EnvelopeHeader, payload: Uint8Array): void {
    if (conn.state === 'closed') return;
    this.writer.reset();
    writeHeader(this.writer, header);
    this.writer.raw(payload);
    conn.lastOutbound = Date.now();
    conn.ws.send(this.writer.toBytes(), { binary: true });
  }
}

function flagsOf(o: SendOptions): number {
  return (o.response === true ? Flags.RESPONSE : 0) | (o.error === true ? Flags.ERROR : 0);
}

/** An error answers `header` when it was a request, otherwise it stands alone. */
function errorOptions(header: EnvelopeHeader | undefined): SendOptions {
  const isRequest = header?.req !== undefined && (header.flags & Flags.RESPONSE) === 0;
  return isRequest ? { req: header.req!, response: true, error: true } : { error: true };
}
