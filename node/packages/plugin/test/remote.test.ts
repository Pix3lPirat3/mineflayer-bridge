import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createMockMod, generateCertificate, type MockCertificate, type MockMod, type MockModOptions } from '@mineflayer-bridge/mock-mod';
import { formatPairingUri, fromBase32, normalizeFingerprint, parsePairingUri, toBase32 } from '@mineflayer-bridge/protocol';
import {
  BridgeError,
  createBridge,
  FileCredentialStore,
  LOCAL_DEFAULT_PERMISSIONS,
  pairDevice,
  REMOTE_DEFAULT_PERMISSIONS,
  runCli,
  type BridgeHub,
  type HubOptions,
  type PairedDevice,
} from '../src/index.ts';
import { env, FakeBot, load, quietLogger, sleep, teardown, until, type Env } from './helpers.ts';

const CERT = generateCertificate({ commonName: 'mock A' });
const OTHER = generateCertificate({ commonName: 'mock B' });

let dir: string;
let credentials: string;
let mods: MockMod[];
let hubs: BridgeHub[];

async function tlsMod(options: MockModOptions = {}, cert: MockCertificate = CERT): Promise<MockMod> {
  const mod = await createMockMod({ heartbeatMs: 1000, tls: { key: cert.key, cert: cert.cert }, ...options });
  mods.push(mod);
  return mod;
}

function hub(options: HubOptions = {}): BridgeHub & { errors: Error[]; gone: Promise<{ reason: string; retry: boolean }> } {
  const h = createBridge({ credentials, logger: quietLogger(), reconnect: { initialDelayMs: 20, maxDelayMs: 100 }, ...options });
  hubs.push(h);
  const errors: Error[] = [];
  h.on('error', (e) => errors.push(e));
  const gone = new Promise<{ reason: string; retry: boolean }>((resolve) => h.on('disconnected', (d) => d.retry || resolve(d)));
  return Object.assign(h, { errors, gone });
}

function paired(h: BridgeHub): Promise<PairedDevice> {
  return new Promise((resolve) => h.on('paired', resolve));
}

/** Pairs a bot hub with `mod` through its URI and waits until the session is open. */
async function pairBot(mod: MockMod, name = 'Alice'): Promise<{ h: ReturnType<typeof hub>; bot: FakeBot; device: PairedDevice; uri: string; logger: ReturnType<typeof quietLogger> }> {
  const { uri } = mod.beginPairing();
  const logger = quietLogger();
  const h = hub({ pair: uri, logger });
  const done = paired(h);
  const bot = new FakeBot(name);
  const b = load(h.plugin(), bot);
  const device = await done;
  await until(() => b.state.connected);
  return { h, bot, device, uri, logger };
}

beforeEach(async () => {
  dir = await mkdtemp(join(tmpdir(), 'mfb-remote-'));
  credentials = join(dir, 'credentials.json');
  mods = [];
  hubs = [];
  // Keep a developer's real endpoint.json and environment out of discovery.
  vi.stubEnv('APPDATA', dir);
  vi.stubEnv('HOME', dir);
  vi.stubEnv('USERPROFILE', dir);
  vi.stubEnv('MINEFLAYER_BRIDGE_ENDPOINT', '');
  vi.stubEnv('MINEFLAYER_BRIDGE_PAIR', '');
});

afterEach(async () => {
  for (const h of hubs) await h.close();
  for (const m of mods) await m.close();
  vi.unstubAllEnvs();
  await rm(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 20 });
});

describe('pairing URIs and fingerprints', () => {
  it('round-trips base32 and parses mfb URIs in either case', () => {
    const bytes = new Uint8Array([0, 1, 2, 250, 255, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21]);
    expect(fromBase32(toBase32(bytes))).toEqual(bytes);
    expect(toBase32(bytes)).toHaveLength(32);
    const uri = formatPairingUri('192.168.1.20', 25590, CERT.fingerprint, 'AB12CD34');
    const p = parsePairingUri(uri.replace(/fp=([A-Z2-7]+)/, (_m, fp: string) => `fp=${fp.toLowerCase()}`));
    expect(p).toMatchObject({ host: '192.168.1.20', port: 25590, code: 'AB12CD34', endpoint: 'wss://192.168.1.20:25590' });
    expect(Buffer.from(p.fingerprintPrefix).toString('hex')).toBe(CERT.fingerprint.slice(7, 47));
    expect(parsePairingUri(formatPairingUri('::1', 1, CERT.fingerprint, 'abcdefgh')).endpoint).toBe('wss://[::1]:1');
    expect(() => parsePairingUri('https://h:1?fp=AAAA&code=12345678')).toThrow(BridgeError);
    expect(() => parsePairingUri('mfb://h:1?fp=AAAA&code=12345678')).toThrow(/fp must encode/);
    expect(() => parsePairingUri(uri.replace(/code=\w+/, 'code=short'))).toThrow(/code/);
    expect(normalizeFingerprint(CERT.fingerprint.slice(7).toUpperCase().replace(/(..)(?!$)/g, '$1:'))).toBe(CERT.fingerprint);
  });
});

describe('remote pairing', () => {
  it('pairs over TLS, stores the full fingerprint with the endpoint, and opens the session', async () => {
    const mod = await tlsMod();
    const redeemed = mod.waitFor('paired');
    const { h, bot, device } = await pairBot(mod);
    const [r] = await redeemed;
    expect(device).toEqual({ deviceId: r.deviceId, fingerprint: CERT.fingerprint, endpoint: mod.url });
    const stored = await new FileCredentialStore(credentials).device(CERT.fingerprint);
    expect(stored).toMatchObject({ deviceId: r.deviceId, endpoint: mod.url });
    expect(mod.devices()).toEqual([expect.objectContaining({ deviceId: r.deviceId, origin: 'paired', lastAddress: expect.any(String) })]);
    expect(mod.session('bot:alice@play.example.net:25565')?.deviceId).toBe(r.deviceId);
    expect([...bot.b.permissions].sort()).toEqual([...REMOTE_DEFAULT_PERMISSIONS].sort());
    expect(bot.b.can('render.world')).toBe(true);
    expect(bot.b.can('chat')).toBe(false);
    expect(h.errors).toEqual([]);
  });

  it('refuses a certificate whose fingerprint does not start with the URI prefix and sends nothing', async () => {
    const mod = await tlsMod();
    const { code } = mod.beginPairing();
    const connections: unknown[] = [];
    mod.on('connection', (c) => connections.push(c));
    const h = hub({ pair: formatPairingUri('127.0.0.1', mod.port, OTHER.fingerprint, code) });
    load(h.plugin(), new FakeBot('Alice'));
    expect(await h.gone).toEqual({ reason: 'unauthorized', retry: false });
    expect(h.errors.some((e) => e instanceof BridgeError && e.code === 'unauthorized' && /does not match the pairing URI/.test(e.message))).toBe(true);
    expect(connections).toEqual([]);
    expect(mod.devices()).toEqual([]);
    expect(await new FileCredentialStore(credentials).list()).toEqual([]);
  });

  it('refuses expired and already used codes without retrying', async () => {
    const mod = await tlsMod();
    const expired = mod.beginPairing({ ttlMs: 1 });
    await sleep(10);
    const late = hub({ pair: expired.uri, credentials: join(dir, 'late.json') });
    load(late.plugin(), new FakeBot('Late'));
    expect(await late.gone).toEqual({ reason: 'unauthorized', retry: false });
    expect(late.errors.map((e) => e.message).join('\n')).toMatch(/no active pairing code/);

    const first = mod.beginPairing();
    mod.beginPairing();
    await pairDevice(first.uri, { credentials: join(dir, 'first.json'), logger: quietLogger() });
    const again = hub({ pair: first.uri, credentials: join(dir, 'again.json') });
    const rejected = mod.waitFor('rejected');
    load(again.plugin(), new FakeBot('Again'));
    await rejected;
    expect(await again.gone).toEqual({ reason: 'unauthorized', retry: false });
    expect(again.errors.map((e) => e.message).join('\n')).toMatch(/refused the pairing code/);
    expect(mod.devices()).toHaveLength(1);
  });

  it('authenticates later runs with the device secret, pinned, from the endpoint, the old URI or nothing', async () => {
    const mod = await tlsMod();
    const first = await pairBot(mod);
    await first.h.close();

    for (const options of [{ endpoint: mod.url }, { pair: first.uri }, { remoteFallback: true }] satisfies HubOptions[]) {
      const auth = mod.waitFor('authenticated');
      const h = hub(options);
      const b = load(h.plugin(), new FakeBot('Alice'));
      const [a] = await auth;
      expect(a).toMatchObject({ method: 'device', deviceId: first.device.deviceId });
      await until(() => b.state.connected);
      await h.close();
    }

    const h = hub({ endpoint: mod.url });
    const b = load(h.plugin(), new FakeBot('Alice'));
    await until(() => b.state.connected);
    const reauth = mod.waitFor('authenticated');
    mod.disconnect(mod.session('bot:alice@play.example.net:25565')!);
    expect((await reauth)[0].method).toBe('device');
    await until(() => b.state.connected);
    expect(mod.devices()).toHaveLength(1);
  });

  it('refuses to connect when the pinned certificate changes, before sending anything', async () => {
    const mod = await tlsMod();
    const first = await pairBot(mod);
    await first.h.close();
    const port = mod.port;
    await mod.close();
    const impostor = await tlsMod({ port }, OTHER);
    const connections: unknown[] = [];
    impostor.on('connection', (c) => connections.push(c));
    const h = hub({ endpoint: `wss://127.0.0.1:${port}` });
    load(h.plugin(), new FakeBot('Alice'));
    expect(await h.gone).toEqual({ reason: 'unauthorized', retry: false });
    expect(h.errors.some((e) => e instanceof BridgeError && e.code === 'unauthorized' && e.message.includes(`pinned ${CERT.fingerprint}`))).toBe(true);
    expect(connections).toEqual([]);
  });

  it('fails without retry for a wss endpoint that was never paired', async () => {
    const mod = await tlsMod();
    const h = hub({ endpoint: mod.url });
    load(h.plugin(), new FakeBot('Alice'));
    expect(await h.gone).toEqual({ reason: 'unauthorized', retry: false });
    expect(h.errors[0]?.message).toMatch(/pair this device first/);
  });

  it('stops reconnecting and forgets the credential when the device is revoked', async () => {
    const mod = await tlsMod();
    const { h, device } = await pairBot(mod);
    const connections: unknown[] = [];
    mod.on('connection', (c) => connections.push(c));
    mod.revokeDevice(device.deviceId);
    expect(await h.gone).toEqual({ reason: 'revoked', retry: false });
    expect(h.errors.some((e) => e instanceof BridgeError && e.code === 'unauthorized' && /revoked/.test(e.message))).toBe(true);
    await sleep(300);
    expect(connections).toEqual([]);
    expect(h.connected).toBe(false);
    await until(async () => (await new FileCredentialStore(credentials).list()).length === 0);
    expect(JSON.parse(await readFile(credentials, 'utf8')).devices).toEqual({});
  });
});

describe('permissions', () => {
  let e: Env;

  beforeEach(async () => {
    e = await env();
  });

  afterEach(async () => {
    await teardown(e);
  });

  it('reports the granted set, follows pushes and names the missing permission on refusals', async () => {
    const logger = quietLogger();
    const h = hub({ endpointFile: e.endpointFile, credentials: e.credentials, logger });
    const b = load(h.plugin(), new FakeBot('Alice'));
    await until(() => b.state.connected);
    expect([...b.permissions].sort()).toEqual([...LOCAL_DEFAULT_PERMISSIONS].sort());
    expect(b.can('chat')).toBe(true);
    const deviceId = e.mod.sessions()[0]!.deviceId;

    const pushed = new Promise<ReadonlySet<string>>((resolve) => b.on('permissions', resolve));
    const hubPushed = new Promise<ReadonlySet<string>>((resolve) => h.on('permissions', resolve));
    const without = new Set(['render.world', 'input.pick', 'chat']);
    e.mod.setPermissions(deviceId, LOCAL_DEFAULT_PERMISSIONS.filter((p) => !without.has(p)));
    const set = await pushed;
    expect(await hubPushed).toBe(set);
    expect(set.has('render.world')).toBe(false);
    expect(b.can('render.world')).toBe(false);
    expect(b.can('render.hud')).toBe(true);

    const denied = e.mod.waitFor('permissionDenied', (d) => d.permission === 'render.world');
    b.layer('debug').box('a', [0, 64, 0], 1);
    b.layer('debug').label('l', [0, 64, 0], 'still allowed?');
    expect((await denied)[0]).toMatchObject({ type: 'obj.upsert', key: expect.stringContaining('/debug/a') });
    // Refused writes arrive as unsolicited session errors, which the plugin reports through the logger.
    await until(() => logger.warnings.length >= 2);
    expect(logger.warnings[0]).toMatch(/permission_denied: .*render\.world.*permission "render\.world" is not granted to this device/);
    expect(e.mod.object(`${e.mod.sessions()[0]!.owner}/user:default/debug`)).toBeDefined();

    await expect(b.prompt.confirm('ok?')).rejects.toMatchObject({ code: 'permission_denied', message: expect.stringContaining('"input.pick"') });
    const chatDenied = e.mod.waitFor('permissionDenied', (d) => d.permission === 'chat');
    b.chat.print('hello');
    await chatDenied;
    expect(e.mod.printed()).toEqual([]);

    e.mod.setPermissions(deviceId, LOCAL_DEFAULT_PERMISSIONS);
    await until(() => b.can('render.world'));
    b.layer('debug').box('b', [0, 64, 0], 1);
    await until(() => e.mod.object(`${e.mod.sessions()[0]!.owner}/user:default/debug/b`));
  });

  it('refuses controller sessions and chat for remote devices by default', async () => {
    const mod = await tlsMod();
    const { h, bot, logger } = await pairBot(mod);
    const denied = mod.waitFor('protocolError', (p) => p.code === 'permission_denied');
    const ctl = h.controller({ name: 'dash' });
    expect((await denied)[0].message).toMatch(/controller permission/);
    await until(() => logger.warnings.some((w) => w.includes('session.open refused')));
    expect(ctl.state.connected).toBe(false);
    expect(mod.sessions().map((s) => s.kind)).toEqual(['bot']);
    const chatDenied = mod.waitFor('permissionDenied', (d) => d.permission === 'chat');
    bot.b.chat.print('hi');
    await chatDenied;
  });
});

describe('cli', () => {
  function io(): { out: string[]; err: string[]; io: { out(l: string): void; err(l: string): void } } {
    const out: string[] = [];
    const err: string[] = [];
    return { out, err, io: { out: (l) => out.push(l), err: (l) => err.push(l) } };
  }

  it('pairs, lists and forgets credentials against the mock', async () => {
    const mod = await tlsMod();
    const { uri } = mod.beginPairing();
    const pair = io();
    expect(await runCli(['pair', uri, '--credentials', credentials, '--name', 'cli-test'], pair.io)).toBe(0);
    const deviceId = mod.devices()[0]!.deviceId;
    expect(mod.devices()[0]!.name).toBe('cli-test');
    expect(pair.out.join('\n')).toContain(`device id:   ${deviceId}`);
    expect(pair.out.join('\n')).toContain(`endpoint:    ${mod.url}`);
    expect(pair.out.join('\n')).toContain(CERT.fingerprint);

    const twice = io();
    expect(await runCli(['pair', uri, '--credentials', credentials], twice.io)).toBe(0);
    expect(twice.out[0]).toBe(`Already paired with ${mod.url}`);

    const list = io();
    expect(await runCli(['devices', '--credentials', credentials], list.io)).toBe(0);
    expect(list.out).toEqual(expect.arrayContaining([CERT.fingerprint, `  device id: ${deviceId}`, `  endpoint:  ${mod.url}`]));
    const json = io();
    expect(await runCli(['devices', '--json', '--credentials', credentials], json.io)).toBe(0);
    const parsed = JSON.parse(json.out.join('\n')) as Record<string, unknown>[];
    expect(parsed).toEqual([expect.objectContaining({ fingerprint: CERT.fingerprint, deviceId, endpoint: mod.url })]);
    expect(parsed[0]).not.toHaveProperty('secret');

    const forget = io();
    expect(await runCli(['forget', mod.url, '--credentials', credentials], forget.io)).toBe(0);
    expect(forget.out[0]).toContain(CERT.fingerprint);
    const none = io();
    expect(await runCli(['forget', CERT.fingerprint, '--credentials', credentials], none.io)).toBe(1);
    const empty = io();
    expect(await runCli(['devices', '--credentials', credentials], empty.io)).toBe(0);
    expect(empty.out[0]).toMatch(/No stored credentials/);
  });

  it('reports refused pairings and usage errors with exit codes', async () => {
    const mod = await tlsMod();
    const { code } = mod.beginPairing();
    const bad = io();
    expect(await runCli(['pair', formatPairingUri('127.0.0.1', mod.port, OTHER.fingerprint, code), '--credentials', credentials], bad.io)).toBe(1);
    expect(bad.err.join('\n')).toMatch(/does not match the pairing URI.*\[unauthorized\]/);
    const usage = io();
    expect(await runCli(['frobnicate'], usage.io)).toBe(2);
    expect(await runCli(['pair'], usage.io)).toBe(2);
    expect(await runCli([], usage.io)).toBe(2);
    expect(await runCli(['--help'], usage.io)).toBe(0);
    const malformed = io();
    expect(await runCli(['pair', 'mfb://nope', '--credentials', credentials], malformed.io)).toBe(1);
    expect(malformed.err.join('\n')).toMatch(/invalid pairing URI/);
  });
});
