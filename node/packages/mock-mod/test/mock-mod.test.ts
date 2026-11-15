import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { zstdCompressSync } from 'node:zlib';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { WebSocket } from 'ws';
import {
  authProof,
  encodeKind,
  encodeMessage,
  encodePatchFields,
  Flags,
  fromBase64Url,
  Kinds,
  Messages,
  MessagesById,
  PROTOCOL,
  readHeader,
  WireWriter,
  type EnvelopeHeader,
  type MessageName,
  type MessagePayloads,
} from '@mineflayer-bridge/protocol';
import { createMockMod, renderTemplate, type MockMod } from '../src/index.ts';

interface Frame {
  header: EnvelopeHeader;
  type: string;
  payload: unknown;
}

/** A hand-driven protocol client, to check the mock against the wire protocol without the plugin. */
class RawClient {
  readonly frames: Frame[] = [];
  private readonly waiters: (() => void)[] = [];
  private readonly w = new WireWriter();
  closeCode: number | undefined;

  constructor(readonly ws: WebSocket) {
    ws.on('message', (data: Buffer) => {
      const { header, payload } = readHeader(new Uint8Array(data));
      const t = MessagesById.get(header.type)!;
      this.frames.push({ header, type: t.name, payload: t.codec.read(payload) });
      for (const fn of this.waiters.splice(0)) fn();
    });
    ws.on('close', (code) => {
      this.closeCode = code;
      for (const fn of this.waiters.splice(0)) fn();
    });
  }

  static async connect(url: string, headers: Record<string, string> = {}): Promise<RawClient> {
    const ws = new WebSocket(`${url}/bridge/v1`, { headers });
    await new Promise<void>((resolve, reject) => {
      ws.once('open', () => resolve());
      ws.once('error', reject);
    });
    return new RawClient(ws);
  }

  send<N extends MessageName>(type: N, payload: MessagePayloads[N], h: Partial<EnvelopeHeader> = {}): void {
    this.ws.send(encodeMessage(this.w, { flags: 0, type: Messages[type].id, session: 0, ...h }, Messages[type].codec, payload));
  }

  async next(type: string, timeoutMs = 2000): Promise<Frame> {
    const start = Date.now();
    for (;;) {
      const i = this.frames.findIndex((f) => f.type === type);
      if (i >= 0) return this.frames.splice(i, 1)[0]!;
      if (Date.now() - start > timeoutMs || this.closeCode !== undefined) throw new Error(`no ${type}`);
      await new Promise<void>((r) => {
        this.waiters.push(r);
        setTimeout(r, 50);
      });
    }
  }

  async handshake(mod: MockMod, deviceId = 'dev-1'): Promise<MessagePayloads['welcome']> {
    this.send('hello', { protocolMajor: PROTOCOL.major, protocolMinor: 0, pluginVersion: 't', deviceName: 'raw', messages: ['acme:ping'], kinds: ['acme:radar'] });
    const c = (await this.next('auth.challenge')).payload as MessagePayloads['auth.challenge'];
    expect(c.methods).toEqual(['local', 'device']);
    expect(c.nonce).toHaveLength(32);
    this.send('auth.response', { method: 'local', deviceId, proof: authProof(fromBase64Url(mod.localToken), c.nonce, c.connectionId, deviceId) });
    return (await this.next('welcome')).payload as MessagePayloads['welcome'];
  }

  async open(name: string): Promise<number> {
    this.send('session.open', { kind: 'bot', name, identity: { username: name, server: 's:1' }, extensions: [] }, { req: 1 });
    const f = await this.next('session.opened');
    expect(f.header.flags & Flags.RESPONSE).toBe(Flags.RESPONSE);
    expect(f.header.req).toBe(1);
    return (f.payload as MessagePayloads['session.opened']).sessionId;
  }
}

let mod: MockMod;
let clients: RawClient[];

beforeEach(async () => {
  mod = await createMockMod();
  clients = [];
});

afterEach(async () => {
  for (const c of clients) c.ws.terminate();
  await mod.close();
});

async function client(): Promise<RawClient> {
  const c = await RawClient.connect(mod.url);
  clients.push(c);
  return c;
}

async function status(path: string, headers: Record<string, string>): Promise<number> {
  const ws = new WebSocket(`${mod.url}${path}`, { headers });
  return new Promise((resolve) => {
    ws.on('unexpected-response', (_req, res) => {
      resolve(res.statusCode ?? 0);
      ws.terminate();
    });
    ws.on('open', () => {
      resolve(101);
      ws.terminate();
    });
    ws.on('error', () => undefined);
  });
}

describe('upgrade', () => {
  it('refuses Origin with 403 and other paths with 404', async () => {
    expect(await status('/bridge/v1', { Origin: 'http://evil.example' })).toBe(403);
    expect(await status('/other', {})).toBe(404);
    expect(await status('/bridge/v1', {})).toBe(101);
  });

  it('closes text frames with 1003 and oversize frames with 1009', async () => {
    const c = await client();
    c.ws.send('hello');
    await c.next('never').catch(() => undefined);
    expect(c.closeCode).toBe(1003);
    await mod.close();
    mod = await createMockMod({ maxFrameBytes: 64 });
    const d = await client();
    d.ws.send(new Uint8Array(200));
    await d.next('never').catch(() => undefined);
    expect(d.closeCode).toBe(1009);
  });

  it('writes endpoint.json with url, fingerprint and token', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'mfb-mock-'));
    const path = join(dir, 'a', 'endpoint.json');
    await mod.writeEndpointFile(path);
    expect(JSON.parse(await readFile(path, 'utf8'))).toEqual({ v: 1, url: mod.url, fingerprint: mod.fingerprint, localToken: mod.localToken });
    expect(fromBase64Url(mod.localToken)).toHaveLength(32);
    await rm(dir, { recursive: true, force: true });
  });
});

describe('handshake', () => {
  it('assigns registry ids from 0x100 for names in hello', async () => {
    const c = await client();
    const w = await c.handshake(mod);
    expect(w.registry).toEqual([
      { name: 'acme:ping', id: 0x100, space: 'message' },
      { name: 'acme:radar', id: 0x101, space: 'kind' },
    ]);
    expect(w.limits.resumeWindowMs).toBe(30_000);
  });

  it('answers a bad proof with goodbye unauthorized', async () => {
    const c = await client();
    c.send('hello', { protocolMajor: PROTOCOL.major, protocolMinor: 0, pluginVersion: 't', deviceName: 'raw', messages: [], kinds: [] });
    await c.next('auth.challenge');
    c.send('auth.response', { method: 'local', deviceId: 'x', proof: new Uint8Array(32) });
    expect((await c.next('goodbye')).payload).toEqual({ reason: 'unauthorized', message: 'authentication failed', retry: false });
  });

  it('refuses session messages before welcome', async () => {
    const c = await client();
    c.send('session.open', { kind: 'bot', name: 'a', identity: { username: 'a' }, extensions: [] }, { req: 1 });
    expect(((await c.next('goodbye')).payload as MessagePayloads['goodbye']).reason).toBe('invalid_message');
  });
});

describe('sessions and objects', () => {
  const box = encodeKind(Kinds['core:box'], { min: { x: 0, y: 0, z: 0 }, size: { x: 1, y: 1, z: 1 } });

  it('sends player.context and interest after open, and rejects writes outside the owned prefix', async () => {
    const c = await client();
    await c.handshake(mod);
    const sid = await c.open('Steve');
    expect((await c.next('player.context')).payload).toEqual({ inWorld: false, world: undefined, attached: false, alive: true, away: false, windowFocused: true });
    expect((await c.next('interest')).payload).toEqual({ attached: false, worldMatched: false, detail: 'full', layers: [], focused: false });
    c.send('obj.upsert', { writes: [{ type: 'full', value: { key: 'bot:other@s:1/x/y', kind: 16, rev: 1, data: box } }] }, { session: sid, seq: 1 });
    const err = await c.next('error');
    expect(err.header.flags & Flags.ERROR).toBe(Flags.ERROR);
    expect(err.payload).toMatchObject({ error: { code: 'not_owner' }, relatesTo: Messages['obj.upsert'].id });
    c.send('obj.remove', { keys: [], prefixes: ['bot:'] }, { session: sid, seq: 2 });
    expect(((await c.next('error')).payload as MessagePayloads['error']).error.code).toBe('not_owner');
  });

  it('applies full writes by revision, patches by field, and reports patches to missing keys', async () => {
    const c = await client();
    await c.handshake(mod);
    const sid = await c.open('Steve');
    const key = 'bot:steve@s:1/user:default/l/a';
    c.send('obj.upsert', { writes: [{ type: 'full', value: { key, kind: 16, rev: 5, data: box } }] }, { session: sid, seq: 1 });
    c.send('obj.upsert', { writes: [{ type: 'full', value: { key, kind: 16, rev: 4, data: encodeKind(Kinds['core:box'], { min: { x: 9, y: 9, z: 9 }, size: { x: 1, y: 1, z: 1 } }) } }] }, { session: sid, seq: 2 });
    const w = new WireWriter();
    w.arrayHeader(3);
    w.f64(7);
    w.f64(0);
    w.f64(0);
    c.send('obj.upsert', { writes: [{ type: 'patch', value: { key, rev: 6, fields: encodePatchFields(new Map([[0, w.toBytes()]])) } }] }, { session: sid, seq: 3 });
    c.send('obj.upsert', { writes: [{ type: 'patch', value: { key: 'bot:steve@s:1/user:default/l/missing', rev: 1, fields: encodePatchFields(new Map()) } }] }, { session: sid, seq: 4 });
    expect((await c.next('error')).payload).toMatchObject({ error: { code: 'invalid_message' }, relatesTo: Messages['obj.upsert'].id });
    expect(mod.objectData(key, 'core:box')?.min).toEqual({ x: 7, y: 0, z: 0 });
    expect(mod.object(key)?.rev).toBe(6);
  });

  it('closes the session on a sequence gap', async () => {
    const c = await client();
    await c.handshake(mod);
    const sid = await c.open('Steve');
    c.send('obj.snapshot.begin', {}, { session: sid, seq: 2 });
    expect(((await c.next('error')).payload as MessagePayloads['error']).error.code).toBe('invalid_message');
    expect((await c.next('session.close')).payload).toEqual({ reason: 'invalid_message' });
  });

  it('answers requests with error responses carrying RESPONSE, ERROR, req and relatesTo', async () => {
    const c = await client();
    await c.handshake(mod);
    const sid = await c.open('Steve');
    c.send('shared.op', { op: { type: 'remove', value: { key: 'shared/x/y', expectRev: 3 } } }, { session: sid, seq: 1, req: 9 });
    const f = await c.next('error');
    expect(f.header.flags & (Flags.RESPONSE | Flags.ERROR)).toBe(Flags.RESPONSE | Flags.ERROR);
    expect(f.header.req).toBe(9);
    expect(f.payload).toMatchObject({ error: { code: 'conflict', details: { rev: 0 } }, relatesTo: Messages['shared.op'].id });
  });

  it('sends an empty ack 250 ms after unacknowledged input', async () => {
    const c = await client();
    await c.handshake(mod);
    const sid = await c.open('Steve');
    c.send('obj.snapshot.begin', {}, { session: sid, seq: 1 });
    const t0 = Date.now();
    const ack = await c.next('ack');
    expect(ack.header.ack).toBe(1);
    expect(Date.now() - t0).toBeGreaterThanOrEqual(200);
  });

  it('resumes with a new session id and replays its log above lastApplied with the original seq', async () => {
    const c = await client();
    await c.handshake(mod, 'dev-r');
    c.send('session.open', { kind: 'bot', name: 'Steve', identity: { username: 'Steve', server: 's:1' }, extensions: [] }, { req: 1 });
    const opened = (await c.next('session.opened')).payload as MessagePayloads['session.opened'];
    c.send('shared.subscribe', { kinds: [], extensions: [] }, { session: opened.sessionId, seq: 1 });
    await mod.waitFor('message', (m) => m.type === 'shared.subscribe');
    mod.putShared('shared/x/a', 'core:box', { min: { x: 0, y: 0, z: 0 }, size: { x: 1, y: 1, z: 1 } });
    expect((await c.next('obj.upsert')).header.seq).toBe(1);
    c.ws.terminate();
    await mod.waitFor('connectionClosed');
    expect(mod.sessions()[0]?.connected).toBe(false);
    mod.putShared('shared/x/b', 'core:box', { min: { x: 0, y: 0, z: 0 }, size: { x: 1, y: 1, z: 1 } });

    const d = await client();
    await d.handshake(mod, 'dev-r');
    d.send('session.resume', { resumeToken: opened.resumeToken, lastApplied: 0 }, { req: 2 });
    const resumed = (await d.next('session.resumed')).payload as MessagePayloads['session.resumed'];
    expect(resumed).toEqual({ sessionId: 1, lastApplied: 1 });
    const replay = [await d.next('obj.upsert'), await d.next('obj.upsert')];
    expect(replay.map((f) => f.header.seq)).toEqual([1, 2]);
    expect(replay.map((f) => (f.payload as MessagePayloads['obj.upsert']).writes[0]!.value.key)).toEqual(['shared/x/a', 'shared/x/b']);
    d.send('obj.snapshot.begin', {}, { session: resumed.sessionId, seq: 1, ack: 2 });
    const dup = await mod.waitFor('message', (m) => m.type === 'obj.snapshot.begin');
    expect(dup[0].duplicate).toBe(true);
  });

  it('refuses resume from another device, and expires detached sessions after the window', async () => {
    let now = 1000;
    await mod.close();
    mod = await createMockMod({ clock: { now: () => now }, resumeWindowMs: 5000 });
    const c = await client();
    await c.handshake(mod, 'dev-a');
    c.send('session.open', { kind: 'bot', name: 'Steve', identity: { username: 'Steve', server: 's:1' }, extensions: [] }, { req: 1 });
    const opened = (await c.next('session.opened')).payload as MessagePayloads['session.opened'];
    c.ws.terminate();
    await mod.waitFor('connectionClosed');
    const d = await client();
    await d.handshake(mod, 'dev-b');
    d.send('session.resume', { resumeToken: opened.resumeToken, lastApplied: 0 }, { req: 2 });
    const err = await d.next('error');
    expect(err.header.req).toBe(2);
    expect(err.payload).toMatchObject({ error: { code: 'disconnected' }, relatesTo: Messages['session.resume'].id });
    now += 6000;
    await mod.waitFor('sessionClosed', (s) => s.reason === 'expired');
    expect(mod.sessions()).toEqual([]);
  });
});

describe('blob streams', () => {
  const sha = (b: Uint8Array): Uint8Array => new Uint8Array(createHash('sha256').update(b).digest());
  const payload = new Uint8Array(10_000).map((_, i) => (i * 7) & 0xff);

  async function session(): Promise<{ c: RawClient; sid: number; seq: () => number }> {
    const c = await client();
    await c.handshake(mod);
    const sid = await c.open('Steve');
    let n = 0;
    return { c, sid, seq: () => ++n };
  }

  it('accepts, grants window per chunk, verifies the hash and answers have afterwards', async () => {
    await mod.close();
    mod = await createMockMod({ streamWindow: 4000 });
    const { c, sid, seq } = await session();
    c.send('stream.offer', { streamId: 1, hash: sha(payload), size: payload.length, codec: 'raw' }, { session: sid, seq: seq() });
    expect((await c.next('stream.accept')).payload).toEqual({ streamId: 1, window: 4000 });
    c.send('stream.chunk', { streamId: 1, offset: 0, data: payload.subarray(0, 4000) }, { session: sid, seq: seq() });
    expect((await c.next('stream.window')).payload).toEqual({ streamId: 1, window: 4000 });
    c.send('stream.chunk', { streamId: 1, offset: 4000, data: payload.subarray(4000, 8000) }, { session: sid, seq: seq() });
    c.send('stream.chunk', { streamId: 1, offset: 8000, data: payload.subarray(8000) }, { session: sid, seq: seq() });
    const stored = mod.waitFor('blobStored');
    c.send('stream.end', { streamId: 1 }, { session: sid, seq: seq() });
    expect((await stored)[0]).toMatchObject({ size: payload.length, codec: 'raw' });
    expect(mod.blobs()).toEqual([{ hash: Buffer.from(sha(payload)).toString('hex'), size: payload.length }]);
    c.send('stream.offer', { streamId: 2, hash: sha(payload), size: payload.length, codec: 'raw' }, { session: sid, seq: seq() });
    expect((await c.next('stream.have')).payload).toEqual({ streamId: 2 });
  });

  it('decodes zstd and rejects a hash mismatch, an overrun window and oversize offers', async () => {
    await mod.close();
    mod = await createMockMod({ streamWindow: 2000, quotas: { blobBytes: 50_000 } });
    const { c, sid, seq } = await session();
    const packed = new Uint8Array(zstdCompressSync(payload));
    c.send('stream.offer', { streamId: 1, hash: sha(payload), size: payload.length, codec: 'zstd' }, { session: sid, seq: seq() });
    await c.next('stream.accept');
    c.send('stream.chunk', { streamId: 1, offset: 0, data: packed }, { session: sid, seq: seq() });
    c.send('stream.end', { streamId: 1 }, { session: sid, seq: seq() });
    await mod.waitFor('blobStored');

    const bad = new Uint8Array(1000).fill(3);
    c.send('stream.offer', { streamId: 2, hash: sha(new Uint8Array(1000)), size: 1000, codec: 'raw' }, { session: sid, seq: seq() });
    await c.next('stream.accept');
    c.send('stream.chunk', { streamId: 2, offset: 0, data: bad }, { session: sid, seq: seq() });
    c.send('stream.end', { streamId: 2 }, { session: sid, seq: seq() });
    expect((await c.next('stream.reject')).payload).toEqual({ streamId: 2, reason: 'hash_mismatch' });

    c.send('stream.offer', { streamId: 3, hash: sha(payload), size: 5000, codec: 'raw' }, { session: sid, seq: seq() });
    c.send('stream.offer', { streamId: 4, hash: sha(bad), size: bad.length + 2500, codec: 'raw' }, { session: sid, seq: seq() });
    await c.next('stream.have');
    await c.next('stream.accept');
    c.send('stream.chunk', { streamId: 4, offset: 0, data: new Uint8Array(2500) }, { session: sid, seq: seq() });
    expect((await c.next('stream.reject')).payload).toEqual({ streamId: 4, reason: 'window_exceeded' });

    c.send('stream.offer', { streamId: 5, hash: sha(bad), size: 60_000, codec: 'raw' }, { session: sid, seq: seq() });
    expect((await c.next('stream.reject')).payload).toEqual({ streamId: 5, reason: 'quota_exceeded' });
    c.send('stream.offer', { streamId: 6, hash: sha(bad), size: 10, codec: 'lz4' }, { session: sid, seq: seq() });
    expect((await c.next('stream.reject')).payload).toEqual({ streamId: 6, reason: 'unsupported_codec' });
    expect(mod.blobs()).toHaveLength(1);
  });

  it('holds offers beyond the in-flight budget until earlier transfers finish', async () => {
    await mod.close();
    mod = await createMockMod({ budget: { messagesPerSecond: 2000, bytesPerSecond: 8 << 20, streamBytesInFlight: 15_000 } });
    const { c, sid, seq } = await session();
    const other = payload.map((v) => v ^ 0xff);
    c.send('stream.offer', { streamId: 1, hash: sha(payload), size: payload.length, codec: 'raw' }, { session: sid, seq: seq() });
    c.send('stream.offer', { streamId: 2, hash: sha(other), size: other.length, codec: 'raw' }, { session: sid, seq: seq() });
    expect(((await c.next('stream.accept')).payload as MessagePayloads['stream.accept']).streamId).toBe(1);
    await expect(c.next('stream.accept', 150)).rejects.toThrow();
    c.send('stream.chunk', { streamId: 1, offset: 0, data: payload }, { session: sid, seq: seq() });
    c.send('stream.end', { streamId: 1 }, { session: sid, seq: seq() });
    expect(((await c.next('stream.accept')).payload as MessagePayloads['stream.accept']).streamId).toBe(2);
  });

  it('evicts least recently used blobs beyond its capacity', async () => {
    await mod.close();
    mod = await createMockMod({ blobCacheBytes: 25_000 });
    const { c, sid, seq } = await session();
    const blobs = [0, 1, 2].map((k) => payload.map((v) => v ^ k));
    for (const [i, b] of blobs.entries()) {
      const id = i + 1;
      c.send('stream.offer', { streamId: id, hash: sha(b), size: b.length, codec: 'raw' }, { session: sid, seq: seq() });
      await c.next('stream.accept');
      c.send('stream.chunk', { streamId: id, offset: 0, data: b }, { session: sid, seq: seq() });
      const stored = mod.waitFor('blobStored');
      c.send('stream.end', { streamId: id }, { session: sid, seq: seq() });
      await stored;
    }
    expect(mod.blobs().map((x) => x.hash)).toEqual([1, 2].map((k) => Buffer.from(sha(blobs[k]!)).toString('hex')));
  });
});

describe('0.2: transactions, focus, traits and variables', () => {
  const box = encodeKind(Kinds['core:box'], { min: { x: 0, y: 0, z: 0 }, size: { x: 1, y: 1, z: 1 } });
  const owner = 'bot:steve@s:1';

  async function steve(): Promise<{ c: RawClient; sid: number; seq: () => number }> {
    const c = await client();
    await c.handshake(mod);
    const sid = await c.open('Steve');
    let n = 0;
    return { c, sid, seq: () => ++n };
  }

  const full = (key: string, rev: number): MessagePayloads['obj.upsert'] => ({ writes: [{ type: 'full', value: { key, kind: 16, rev, data: box } }] });

  it('applies the writes between obj.begin and obj.commit together, in order', async () => {
    const { c, sid, seq } = await steve();
    c.send('obj.begin', { tx: 7 }, { session: sid, seq: seq() });
    c.send('obj.upsert', full(`${owner}/user:default/l/a`, 1), { session: sid, seq: seq() });
    c.send('obj.upsert', full(`${owner}/user:default/l/b`, 1), { session: sid, seq: seq() });
    c.send('obj.remove', { keys: [`${owner}/user:default/l/a`], prefixes: [] }, { session: sid, seq: seq() });
    await until(() => mod.openTransaction('Steve')?.writes === 3);
    expect(mod.objects(owner)).toEqual([]);
    const done = mod.waitFor('transaction');
    c.send('obj.commit', { tx: 7 }, { session: sid, seq: seq() });
    const [t] = await done;
    expect(t).toMatchObject({ tx: 7, outcome: 'committed', writes: 3, reason: undefined });
    expect(mod.objects(owner).map((o) => o.key)).toEqual([`${owner}/user:default/l/b`]);
    expect(mod.openTransaction('Steve')).toBeUndefined();
    c.send('obj.commit', { tx: 8 }, { session: sid, seq: seq() });
    expect(((await c.next('error')).payload as MessagePayloads['error']).error.code).toBe('invalid_message');
  });

  it('discards an open transaction after 10 s of clock time, on a new obj.begin and when the session ends', async () => {
    let now = 1000;
    await mod.close();
    mod = await createMockMod({ clock: { now: () => now } });
    const { c, sid, seq } = await steve();
    c.send('obj.begin', { tx: 1 }, { session: sid, seq: seq() });
    c.send('obj.upsert', full(`${owner}/user:default/l/a`, 1), { session: sid, seq: seq() });
    await until(() => mod.openTransaction('Steve')?.writes === 1);
    const replaced = mod.waitFor('transaction');
    c.send('obj.begin', { tx: 2 }, { session: sid, seq: seq() });
    expect((await replaced)[0]).toMatchObject({ tx: 1, outcome: 'discarded', reason: 'replaced' });
    const timedOut = mod.waitFor('transaction');
    now += 10_001;
    expect((await timedOut)[0]).toMatchObject({ tx: 2, outcome: 'discarded', reason: 'timeout' });
    c.send('obj.begin', { tx: 3 }, { session: sid, seq: seq() });
    await until(() => mod.openTransaction('Steve')?.tx === 3);
    const ended = mod.waitFor('transaction');
    c.send('session.close', { reason: 'bye' }, { session: sid, seq: seq() });
    expect((await ended)[0]).toMatchObject({ tx: 3, outcome: 'discarded', reason: 'session_end' });
    expect(mod.objects(owner)).toEqual([]);
  });

  it('reports focus in interest, follows setFocus and grants or refuses focus.request', async () => {
    const { c, sid } = await steve();
    expect((await c.next('interest')).payload).toMatchObject({ attached: false, focused: false });
    mod.setAttached('steve@s:1', true);
    expect((await c.next('interest')).payload).toMatchObject({ attached: true, focused: true });
    mod.setFocus(null);
    expect((await c.next('interest')).payload).toMatchObject({ focused: false });
    mod.setFocusPolicy('refuse');
    const refused = mod.waitFor('focusRequested');
    c.send('focus.request', { reason: 'stuck' }, { session: sid });
    expect((await refused)[0]).toMatchObject({ reason: 'stuck', granted: false });
    mod.setFocusPolicy('grant');
    const granted = mod.waitFor('focusRequested');
    c.send('focus.request', {}, { session: sid });
    expect((await granted)[0]).toMatchObject({ reason: undefined, granted: true });
    expect((await c.next('interest')).payload).toMatchObject({ focused: true });
    mod.setFocus('Steve');
    await expect(c.next('interest', 150)).rejects.toThrow();
  });

  it('stores traits, replaces them on patches, routes element events by them and checks editable fields', async () => {
    const { c, sid, seq } = await steve();
    const key = `${owner}/user:default/l/a`;
    const traits = { interactive: { inputs: ['primary' as const] }, editable: [{ field: 'min', editor: 'gizmo' }], actions: [`${owner}/user:default/~action/inspect`] };
    c.send('obj.upsert', { writes: [{ type: 'full', value: { key, kind: 16, rev: 1, data: box, traits } }] }, { session: sid, seq: seq() });
    await until(() => mod.object(key));
    expect(mod.object(key)!.traits).toMatchObject(traits);
    mod.elementEvent(key, 'primary');
    expect(((await c.next('element.event')).payload as MessagePayloads['element.event']).element).toBe(key);
    expect(() => mod.proposeEdit<'core:box'>(key, { size: { x: 2, y: 2, z: 2 } })).toThrow(/size .* is not editable/);
    const patch = { key, rev: 2, fields: encodePatchFields(new Map()), traits: { editable: [{ field: 'size' }] } };
    c.send('obj.upsert', { writes: [{ type: 'patch', value: patch }] }, { session: sid, seq: seq() });
    await until(() => mod.object(key)?.rev === 2);
    expect(mod.object(key)!.traits?.editable).toEqual([{ field: 'size', editor: undefined, label: undefined }]);
    expect(() => mod.elementEvent(key, 'primary')).toThrow(/does not declare primary/);
    const pending = mod.proposeEdit<'core:box'>(key, { size: { x: 2, y: 2, z: 2 } });
    const propose = await c.next('obj.edit.propose');
    c.send('obj.edit.result', { accepted: false, reason: 'no' }, { session: sid, seq: seq(), flags: Flags.RESPONSE, req: propose.header.req! });
    expect(await pending).toEqual({ accepted: false, reason: 'no' });
  });

  it('exposes variables and renders templates as the client does', async () => {
    const { c, sid, seq } = await steve();
    const scope = `${owner}/user:default`;
    const vars = encodeKind(Kinds['core:vars'], { values: { hp: 0.428, n: 7.25, who: 'Steve' } });
    c.send('obj.upsert', { writes: [{ type: 'full', value: { key: `${scope}/~vars`, kind: 3, rev: 1, data: vars } }] }, { session: sid, seq: seq() });
    const label = `${scope}/l/name`;
    const data = encodeKind(Kinds['core:label'], { pos: { x: 0, y: 0, z: 0 }, text: '{who}: {hp:pct}' });
    c.send('obj.upsert', { writes: [{ type: 'full', value: { key: label, kind: Kinds['core:label'].id, rev: 1, data } }] }, { session: sid, seq: seq() });
    await until(() => mod.object(label));
    expect(mod.vars(scope)).toEqual({ hp: 0.428, n: 7.25, who: 'Steve' });
    expect(mod.vars(`${owner}/other:ext`)).toBeUndefined();
    expect(mod.rendered(label)).toBe('Steve: 43%');
    expect(mod.renderTemplate('{n} {n:int} {n:1f} {n:2f} {who:int} {{x}} {missing}', scope)).toBe('7.25 7 7.3 7.25 Steve {x} {missing}');
    expect(renderTemplate('{a}/{b}', { a: true, b: 3 })).toBe('true/3');
  });
});

async function until<T>(cond: () => T | undefined | null | false, timeoutMs = 2000): Promise<T> {
  const start = Date.now();
  for (;;) {
    const v = cond();
    if (v !== undefined && v !== null && v !== false) return v;
    if (Date.now() - start > timeoutMs) throw new Error('timed out');
    await new Promise((r) => setTimeout(r, 5));
  }
}

describe('mutual proof and enforced limits', () => {
  it('proves it knows the secret when the plugin sends a nonce, and sends no proof without one', async () => {
    const c = await client();
    expect((await c.handshake(mod)).proof).toBeUndefined();

    const d = await client();
    d.send('hello', { protocolMajor: PROTOCOL.major, protocolMinor: 0, pluginVersion: 't', deviceName: 'raw', messages: [], kinds: [] });
    const ch = (await d.next('auth.challenge')).payload as MessagePayloads['auth.challenge'];
    const key = fromBase64Url(mod.localToken);
    const nonce = new Uint8Array(32).fill(7);
    d.send('auth.response', { method: 'local', deviceId: 'dev-2', proof: authProof(key, ch.nonce, ch.connectionId, 'dev-2'), nonce });
    const w = (await d.next('welcome')).payload as MessagePayloads['welcome'];
    expect(w.proof).toEqual(authProof(key, nonce, ch.connectionId, 'mod:dev-2'));
    expect(w.proof).not.toEqual(authProof(key, nonce, ch.connectionId, 'dev-2'));
  });

  it('refuses objects past the object and interactive quotas and drops effects past the rate', async () => {
    await mod.close();
    mod = await createMockMod({ quotas: { objects: 2, interactive: 1, effectsPerSecond: 2 } });
    const c = await client();
    const w = await c.handshake(mod);
    expect(w.limits).toMatchObject({ maxObjects: 2, maxInteractive: 1, effectsPerSecond: 2 });
    const sid = await c.open('Steve');
    const box = encodeKind(Kinds['core:box'], { min: { x: 0, y: 0, z: 0 }, size: { x: 1, y: 1, z: 1 } });
    const prefix = 'bot:steve@s:1/user:default/l';
    const full = (id: string, rev: number, interactive: boolean): MessagePayloads['obj.upsert'] => ({
      writes: [{ type: 'full', value: { key: `${prefix}/${id}`, kind: Kinds['core:box'].id, rev, data: box, traits: interactive ? { interactive: { inputs: ['primary'] } } : undefined } }],
    });
    c.send('obj.upsert', full('a', 1, true), { session: sid, seq: 1 });
    c.send('obj.upsert', full('b', 1, true), { session: sid, seq: 2 });
    expect((await c.next('error')).payload).toMatchObject({ error: { code: 'quota_exceeded', message: expect.stringContaining('1 interactive objects') } });
    c.send('obj.upsert', full('a', 2, true), { session: sid, seq: 3 });
    c.send('obj.upsert', full('b', 2, false), { session: sid, seq: 4 });
    c.send('obj.upsert', full('c', 1, false), { session: sid, seq: 5 });
    expect((await c.next('error')).payload).toMatchObject({ error: { code: 'quota_exceeded', message: expect.stringContaining('object quota of 2') } });
    expect(mod.objects(prefix).map((o) => [o.key.slice(prefix.length + 1), o.rev])).toEqual([['a', 2], ['b', 2]]);

    const sound = (id: string): MessagePayloads['effect.play'] => ({ effect: { type: 'sound', value: { id } } });
    for (const id of ['minecraft:a', 'minecraft:b', 'minecraft:c']) c.send('effect.play', sound(id), { session: sid });
    c.send('effect.play', { effect: { type: 'actionbar', value: { text: 'counted too' } } }, { session: sid });
    c.send('obj.remove', { keys: [`${prefix}/b`], prefixes: [] }, { session: sid, seq: 6 });
    const start = Date.now();
    while (mod.object(`${prefix}/b`) !== undefined && Date.now() - start < 2000) await new Promise((r) => setTimeout(r, 5));
    expect(mod.effects().map((x) => x.effect.type)).toEqual(['sound', 'sound']);
  });
});
