import { randomInt, randomBytes } from 'node:crypto';
import type { IncomingMessage } from 'node:http';
import type { Duplex } from 'node:stream';
import type { RawData, WebSocket, WebSocketServer } from 'ws';
import {
  authProof,
  expectFullyRead,
  Kinds,
  MessagesById,
  PROTOCOL,
  readHeader,
  type BotIdentity,
  type EnvelopeHeader,
  type MessageName,
  type MessagePayloads,
  type SessionKind,
} from '@mineflayer-bridge/protocol';
import type { ModCore } from './core.ts';
import { dispatchSessionMessage } from './dispatch.ts';
import { createSession, trimLog, type Conn } from './session.ts';
import { toBridgeError, toBytes } from './util.ts';

const PATH = '/bridge/v1';
/** Messages a connection may send before `welcome`. */
const BEFORE_WELCOME: ReadonlySet<string> = new Set(['hello', 'auth.response', 'ping', 'pong', 'goodbye']);

/** WebSocket connections: upgrade, framing and sequencing checks, handshake, and session open and resume. */
export class Connections {
  constructor(
    private readonly core: ModCore,
    private readonly wss: WebSocketServer,
  ) {}

  onUpgrade(req: IncomingMessage, socket: Duplex, head: Buffer): void {
    const path = (req.url ?? '').split('?')[0];
    // An Origin header means a browser; refuse before anything else.
    if (req.headers.origin !== undefined) {
      socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');
      return;
    }
    if (path !== PATH) {
      socket.end('HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');
      return;
    }
    this.wss.handleUpgrade(req, socket, head, (ws) => this.onConnection(ws, req));
  }

  /** Terminates connections silent for three heartbeats and pings idle ones. */
  heartbeat(): void {
    const now = Date.now();
    const { heartbeatMs } = this.core.opts;
    for (const c of this.core.conns.values()) {
      if (now - c.lastInbound > heartbeatMs * 3) {
        c.ws.terminate();
        continue;
      }
      if (now - c.lastOutbound >= heartbeatMs) this.core.sendConn(c, 'ping', { time: now });
    }
  }

  private onConnection(ws: WebSocket, req: IncomingMessage): void {
    const remoteAddress = req.socket.remoteAddress ?? '';
    const now = Date.now();
    const conn: Conn = {
      id: randomInt(1, 2 ** 48),
      ws,
      remoteAddress,
      loopback: remoteAddress === '127.0.0.1' || remoteAddress === '::1' || remoteAddress === '::ffff:127.0.0.1',
      tls: (req.socket as { encrypted?: boolean }).encrypted === true,
      state: 'hello',
      nonce: new Uint8Array(randomBytes(32)),
      secret: undefined,
      deviceId: '',
      deviceName: '',
      registry: new Map(),
      namesById: new Map(),
      kindNames: new Set(),
      sessions: new Map(),
      nextSessionId: 1,
      lastInbound: now,
      lastOutbound: now,
      blackholed: false,
    };
    this.core.conns.set(conn.id, conn);
    this.core.emit('connection', { connectionId: conn.id, remoteAddress });
    ws.on('message', (data, isBinary) => this.onFrame(conn, data, isBinary));
    ws.on('close', () => this.onClose(conn));
    ws.on('error', () => ws.terminate());
  }

  private onClose(conn: Conn): void {
    if (conn.state === 'closed') return;
    conn.state = 'closed';
    this.core.conns.delete(conn.id);
    const now = this.core.clock.now();
    for (const s of conn.sessions.values()) {
      if (s.conn !== conn) continue;
      s.conn = null;
      s.droppedAt = now;
      if (s.ackTimer !== undefined) clearTimeout(s.ackTimer);
      s.ackTimer = undefined;
    }
    this.core.emit('connectionClosed', { connectionId: conn.id });
  }

  private onFrame(conn: Conn, data: RawData, isBinary: boolean): void {
    if (conn.blackholed || conn.state === 'closed') return;
    // Text frames close with 1003.
    if (!isBinary) {
      conn.ws.close(1003, 'binary frames only');
      return;
    }
    conn.lastInbound = Date.now();
    const bytes = toBytes(data);
    let header: EnvelopeHeader;
    let typeName: string;
    let payload: unknown;
    try {
      const frame = readHeader(bytes);
      header = frame.header;
      const type = MessagesById.get(header.type);
      // A negotiated extension message stays raw bytes; the mock has no codec for it.
      const ext = type === undefined ? extensionName(conn, header.type) : undefined;
      if (type === undefined && ext === undefined) {
        this.core.sendError(conn, header.session, 'unknown_type', `unknown message type ${header.type}`, header);
        return;
      }
      if (type !== undefined) {
        typeName = type.name;
        payload = type.codec.read(frame.payload);
        expectFullyRead(frame.payload);
      } else {
        typeName = ext!;
        payload = bytes.slice(frame.payload.position);
      }
    } catch (e) {
      let partial: EnvelopeHeader | undefined;
      try {
        partial = readHeader(bytes).header;
      } catch {
        partial = undefined;
      }
      this.core.sendError(conn, partial?.session ?? 0, 'invalid_message', toBridgeError(e).message, partial);
      return;
    }

    if (conn.state !== 'ready' && !BEFORE_WELCOME.has(typeName)) {
      this.protocolError(conn, `${typeName} before welcome`);
      this.core.goodbye(conn, 'invalid_message', `${typeName} before welcome`, false);
      return;
    }

    if (header.session === 0) {
      this.core.emit('message', { connectionId: conn.id, session: undefined, type: typeName, header, payload, duplicate: false });
      this.onConnectionMessage(conn, typeName as MessageName, header, payload);
      return;
    }

    const s = conn.sessions.get(header.session);
    if (s === undefined || s.conn !== conn) {
      this.core.sendError(conn, header.session, 'invalid_message', `unknown session ${header.session}`, header);
      return;
    }
    if (header.ack !== undefined) trimLog(s, header.ack);
    let duplicate = false;
    if (header.seq !== undefined) {
      if (header.seq <= s.inApplied) {
        duplicate = true;
      } else if (header.seq !== s.inApplied + 1) {
        // A gap is a protocol error that ends the session.
        this.core.sendError(conn, header.session, 'invalid_message', `sequence gap: expected ${s.inApplied + 1}, got ${header.seq}`, header);
        this.core.closeSession(s, 'invalid_message', true);
        return;
      } else {
        s.inApplied = header.seq;
        this.core.scheduleAck(s);
      }
    }
    this.core.emit('message', { connectionId: conn.id, session: this.core.info(s), type: typeName, header, payload, duplicate });
    if (duplicate) return;
    if (!MessagesById.has(header.type)) return this.core.onExtensionMessage(s, typeName, payload as Uint8Array, header);
    dispatchSessionMessage(this.core, s, typeName as MessageName, header, payload);
  }

  private onConnectionMessage(conn: Conn, type: MessageName, header: EnvelopeHeader, payload: unknown): void {
    switch (type) {
      case 'hello':
        return this.onHello(conn, payload as MessagePayloads['hello']);
      case 'auth.response':
        if (conn.state !== 'auth') return this.protocolError(conn, 'unexpected auth.response');
        void this.authenticate(conn, payload as MessagePayloads['auth.response']);
        return;
      case 'ping':
        this.core.sendConn(conn, 'pong', { time: (payload as MessagePayloads['ping']).time });
        return;
      case 'pong':
      case 'ack':
      case 'error':
        return;
      case 'goodbye':
        conn.ws.close(1000, 'goodbye');
        return;
      case 'session.open':
        return this.openSession(conn, header, payload as MessagePayloads['session.open']);
      case 'session.resume':
        return this.resumeSession(conn, header, payload as MessagePayloads['session.resume']);
      default:
        this.core.sendError(conn, 0, 'invalid_message', `${type} is not a connection message`, header);
    }
  }

  private onHello(conn: Conn, p: MessagePayloads['hello']): void {
    if (conn.state !== 'hello') return this.protocolError(conn, 'duplicate hello');
    if (p.protocolMajor !== PROTOCOL.major) {
      this.core.goodbye(conn, 'protocol_mismatch', `mod speaks protocol ${PROTOCOL.major}, plugin ${p.protocolMajor}`, false);
      return;
    }
    conn.deviceName = p.deviceName;
    let next = 0x100;
    const register = (name: string, space: 'message' | 'kind'): void => {
      if (this.core.opts.supports?.(name, space) === false || conn.registry.has(name)) return;
      if (space === 'kind') conn.kindNames.add(name);
      conn.registry.set(name, next);
      conn.namesById.set(next++, name);
    };
    for (const name of p.messages) register(name, 'message');
    for (const name of p.kinds) register(name, 'kind');
    conn.state = 'auth';
    const methods: MessagePayloads['auth.challenge']['methods'] = conn.loopback ? ['local', 'device'] : ['device'];
    const pairing = this.core.devices.pairingActive();
    // `pair` only while a code is active; the mock also needs TLS for it.
    if (conn.tls && pairing) methods.push('pair');
    this.core.sendConn(conn, 'auth.challenge', { connectionId: conn.id, nonce: conn.nonce, methods });
  }

  private async authenticate(conn: Conn, p: MessagePayloads['auth.response']): Promise<void> {
    conn.state = 'approving';
    const ok = await this.core.devices.verify(conn, p);
    // The socket may have closed while approval was pending.
    if ((conn.state as Conn['state']) === 'closed') return;
    if (!ok) {
      this.core.emit('rejected', { connectionId: conn.id, reason: 'unauthorized' });
      this.core.goodbye(conn, 'unauthorized', 'authentication failed', false);
      return;
    }
    conn.deviceId = p.deviceId;
    conn.state = 'ready';
    const device = this.core.devices.record(p.deviceId, p.method === 'pair' ? 'paired' : p.method === 'local' ? 'local' : 'issued');
    device.name = conn.deviceName;
    device.lastSeenAt = this.core.clock.now();
    device.lastAddress = conn.remoteAddress;
    this.core.emit('authenticated', { connectionId: conn.id, deviceId: p.deviceId, method: p.method });
    const { opts } = this.core;
    const quotas = opts.quotas;
    const budget = this.core.player.budget;
    // Proves the mod knows the secret too: the plugin's nonce, signed for `mod:<device id>`.
    let proof: Uint8Array | undefined;
    if (p.nonce !== undefined && p.nonce !== null && conn.secret !== undefined) {
      proof = authProof(conn.secret, p.nonce, conn.id, `mod:${p.deviceId}`);
      if (opts.wrongProof === true) proof = proof.map((b) => b ^ 0xff);
    }
    this.core.sendConn(conn, 'welcome', {
      protocolMajor: PROTOCOL.major,
      protocolMinor: PROTOCOL.minor,
      modVersion: '0.0.0-mock',
      gameVersion: 'mock',
      loader: 'mock',
      registry: [...conn.registry].map(([name, id]) => ({ name, id, space: conn.kindNames.has(name) ? ('kind' as const) : ('message' as const) })),
      extensions: opts.capabilities ?? Object.keys(Kinds).map((id) => ({ id, version: '1.0.0' })),
      limits: {
        maxFrameBytes: opts.maxFrameBytes,
        maxObjects: quotas?.objects ?? 20_000,
        maxBlobBytes: this.core.streams.maxBlobBytes,
        resumeWindowMs: opts.resumeWindowMs,
        heartbeatMs: opts.heartbeatMs,
        maxDisplays: quotas?.displays ?? 4096,
        maxEntityDisplays: quotas?.entityDisplays ?? 256,
        maxEmitters: quotas?.emitters ?? 32,
        maxInteractive: quotas?.interactive ?? 1024,
        effectsPerSecond: quotas?.effectsPerSecond ?? 40,
        blobInFlightBytes: budget.streamBytesInFlight,
        maxSoundEmitters: quotas?.soundEmitters ?? 16,
      },
      budget,
      permissions: [...device.permissions],
      proof,
    });
  }

  private openSession(conn: Conn, header: EnvelopeHeader, p: MessagePayloads['session.open']): void {
    if (header.req === undefined) return this.core.sendError(conn, 0, 'invalid_message', 'session.open needs a request id', header);
    if (p.kind === 'bot' && p.identity === undefined) return this.core.sendError(conn, 0, 'invalid_message', 'bot session needs an identity', header);
    if (p.kind === 'controller' && !this.core.devices.allows(conn.deviceId, 'controller')) {
      return this.core.sendError(conn, 0, 'permission_denied', 'session.open of a controller needs the controller permission', header, { permission: 'controller' });
    }
    const refusal = this.core.opts.refuseOpen?.({ kind: p.kind, name: p.name, deviceId: conn.deviceId });
    if (refusal !== undefined) {
      const payload: MessagePayloads['error'] = { error: { code: refusal.code, message: refusal.message ?? `session.open refused: ${refusal.code}`, retryable: refusal.retryable ?? false }, relatesTo: header.type };
      this.core.sendConn(conn, 'error', payload, { req: header.req, response: true, error: true });
      return;
    }
    const max = this.core.opts.maxSessionsPerDevice ?? 256;
    let held = 0;
    for (const x of this.core.sessions.values()) if (x.deviceId === conn.deviceId && x.conn !== null && !x.closed) held++;
    if (held >= max) return this.core.sendError(conn, 0, 'quota_exceeded', `this device already holds ${max} sessions`, header);
    const owner = ownerFor(conn, p.kind, p.name, p.identity);
    // The newest session is the writer.
    for (const old of this.core.sessions.values()) {
      if (old.owner !== owner || old.superseded) continue;
      old.superseded = true;
      if (old.conn !== null) this.core.sendSessionError(old, 'superseded', 'a newer session took over this identity', undefined);
      else this.core.forgetSession(old);
    }
    const s = createSession(conn, p.kind, p.name, p.identity, owner, p.extensions);
    conn.sessions.set(s.sessionId, s);
    this.core.sessions.set(s.token, s);
    this.core.sendConn(conn, 'session.opened', { sessionId: s.sessionId, resumeToken: Buffer.from(s.token, 'hex') }, { req: header.req, response: true });
    this.core.emit('sessionOpened', this.core.info(s));
    this.core.player.refresh(s);
    this.core.controls.refreshTool(s);
  }

  private resumeSession(conn: Conn, header: EnvelopeHeader, p: MessagePayloads['session.resume']): void {
    if (header.req === undefined) return this.core.sendError(conn, 0, 'invalid_message', 'session.resume needs a request id', header);
    const s = this.core.sessions.get(Buffer.from(p.resumeToken).toString('hex'));
    const expired = s?.droppedAt !== undefined && this.core.clock.now() - s.droppedAt > this.core.opts.resumeWindowMs;
    if (s === undefined || expired || s.superseded || !s.resumable || s.deviceId !== conn.deviceId || s.closed) {
      if (s !== undefined && expired) this.core.expire(s);
      this.core.sendError(conn, 0, 'disconnected', 'resume failed: unknown or expired session', header);
      return;
    }
    // The plugin may notice a dead link before the mod does; detach the session from the old socket.
    if (s.conn !== null && s.conn !== conn) {
      s.conn.sessions.delete(s.sessionId);
      s.conn.ws.terminate();
    }
    trimLog(s, p.lastApplied);
    s.conn = conn;
    s.sessionId = conn.nextSessionId++;
    s.droppedAt = undefined;
    s.lastContext = undefined;
    s.lastInterest = undefined;
    s.toolActive = undefined;
    conn.sessions.set(s.sessionId, s);
    this.core.sendConn(conn, 'session.resumed', { sessionId: s.sessionId, lastApplied: s.inApplied }, { req: header.req, response: true });
    s.ackSent = s.inApplied;
    this.core.replay(s, p.lastApplied);
    this.core.emit('sessionResumed', this.core.info(s));
    this.core.player.refresh(s);
    this.core.controls.refreshTool(s);
    // settings.values is latest-wins, so values sent while the session was detached were lost.
    this.core.controls.sendAllSettings(s);
  }

  private protocolError(conn: Conn, message: string): void {
    this.core.emit('protocolError', { connectionId: conn.id, code: 'invalid_message', message });
  }
}

/** The owner key: `ctl:<device>/<name>` for a controller, `bot:<uuid>` or `bot:<name>@<server>` for a bot. */
function ownerFor(conn: Conn, kind: SessionKind, name: string, identity: BotIdentity | undefined): string {
  if (kind === 'controller') return `ctl:${conn.deviceId}/${name}`;
  return `bot:${identity?.uuid ?? `${(identity?.username ?? name).toLowerCase()}@${identity?.server ?? 'unknown'}`}`;
}

/** The extension message name a connection negotiated for a wire id, if any. */
function extensionName(conn: Conn, id: number): string | undefined {
  for (const [name, n] of conn.registry) if (n === id && !conn.kindNames.has(name)) return name;
  return undefined;
}
