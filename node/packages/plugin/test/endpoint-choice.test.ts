import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createMockMod, generateCertificate, type MockMod } from '@mineflayer-bridge/mock-mod';
import { BridgeError, createBridge, FileCredentialStore, pairDevice, type BridgeHub, type CredentialStore, type HubOptions, type Logger } from '../src/index.ts';
import { resolveTarget } from '../src/remote.ts';
import { FakeBot, load, quietLogger, rejection, sleep, until } from './helpers.ts';

const CERT = generateCertificate({ commonName: 'mock A' });

let dir: string;
let credentials: string;
let mods: MockMod[];
let hubs: BridgeHub[];

async function tlsMod(): Promise<MockMod> {
  const mod = await createMockMod({ heartbeatMs: 1000, tls: { key: CERT.key, cert: CERT.cert } });
  mods.push(mod);
  return mod;
}

/** A quiet logger that keeps the info lines. */
function infoLogger(): Logger & { infos: string[] } {
  const infos: string[] = [];
  return { ...quietLogger(), infos, info: (m) => infos.push(m) };
}

interface Seen {
  errors: Error[];
  disconnects: { reason: string; retry: boolean }[];
}

function hub(options: HubOptions = {}): BridgeHub & Seen {
  const h = createBridge({ credentials, logger: quietLogger(), reconnect: { initialDelayMs: 20, maxDelayMs: 100 }, ...options });
  hubs.push(h);
  const seen: Seen = { errors: [], disconnects: [] };
  h.on('error', (e) => seen.errors.push(e));
  h.on('disconnected', (d) => seen.disconnects.push(d));
  return Object.assign(h, seen);
}

beforeEach(async () => {
  dir = await mkdtemp(join(tmpdir(), 'mfb-choice-'));
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

describe('FileCredentialStore', () => {
  it('retries a failed load on the next call', async () => {
    await writeFile(credentials, '{ not json');
    const store = new FileCredentialStore(credentials);
    await expect(store.deviceId()).rejects.toThrow();
    await expect(store.list()).rejects.toThrow();
    await writeFile(credentials, JSON.stringify({ v: 1, deviceId: 'dev-1', devices: {} }));
    expect(await store.deviceId()).toBe('dev-1');
    expect(await store.list()).toEqual([]);
  });

  it('keeps the entries of two stores that save to one file', async () => {
    const fpA = `sha256:${'a'.repeat(64)}`;
    const fpB = `sha256:${'b'.repeat(64)}`;
    const a = new FileCredentialStore(credentials);
    const b = new FileCredentialStore(credentials);
    // Both read the file before either saves, as two processes started together would.
    const id = await a.deviceId();
    expect(await b.deviceId()).toBe(id);
    await a.saveDevice(fpA, { deviceId: 'a', secret: 'sa' });
    await b.saveDevice(fpB, { deviceId: 'b', secret: 'sb' });
    const onDisk = JSON.parse(await readFile(credentials, 'utf8')) as { deviceId: string; devices: Record<string, unknown> };
    expect(onDisk.deviceId).toBe(id);
    expect(Object.keys(onDisk.devices).sort()).toEqual([fpA, fpB]);
    expect((await b.list()).map((d) => d.fingerprint).sort()).toEqual([fpA, fpB]);

    // A forget in one store does not bring back or drop what the other wrote since.
    await a.saveDevice(fpA, { deviceId: 'a2', secret: 'sa2' });
    expect(await b.forget(fpB)).toBe(true);
    expect(await a.forget(fpB)).toBe(false);
    expect(await new FileCredentialStore(credentials).list()).toEqual([expect.objectContaining({ fingerprint: fpA, deviceId: 'a2' })]);
  });
});

describe('pairDevice', () => {
  it('reports a credential that could not be stored instead of timing out', async () => {
    const mod = await tlsMod();
    const { uri } = mod.beginPairing();
    const inner = new FileCredentialStore(credentials);
    const store: CredentialStore = {
      deviceId: () => inner.deviceId(),
      device: (f) => inner.device(f),
      list: () => inner.list(),
      saveDevice: () => Promise.reject(new Error('disk full')),
    };
    const unhandled: unknown[] = [];
    const onUnhandled = (e: unknown): void => void unhandled.push(e);
    process.on('unhandledRejection', onUnhandled);
    try {
      const started = Date.now();
      const error = await rejection(pairDevice(uri, { credentials: store, timeoutMs: 20_000, logger: quietLogger() }));
      expect(error.code).toBe('internal');
      expect(error.message).toMatch(/^paired as .* could not be stored: disk full/);
      expect(Date.now() - started).toBeLessThan(10_000);
      expect(mod.devices()).toHaveLength(1);
      expect(await inner.list()).toEqual([]);
      await sleep(20);
      expect(unhandled).toEqual([]);
    } finally {
      process.off('unhandledRejection', onUnhandled);
    }
  });
});

describe('endpoint choice', () => {
  it('does not fall back to a paired remote mod unless remoteFallback is set', async () => {
    const mod = await tlsMod();
    await pairDevice(mod.beginPairing().uri, { credentials, logger: quietLogger() });
    const connections: unknown[] = [];
    mod.on('connection', (c) => connections.push(c));

    const plain = hub();
    load(plain.plugin(), new FakeBot('Alice'));
    await until(() => plain.disconnects.length > 0);
    expect(plain.disconnects[0]).toEqual({ reason: 'endpoint unavailable', retry: true });
    const error = plain.errors[0] as BridgeError;
    expect(error).toBeInstanceOf(BridgeError);
    expect(error).toMatchObject({ code: 'disconnected', retryable: true });
    expect(error.message).toMatch(/cannot read endpoint file/);
    expect(error.message).toContain('remoteFallback: true');
    expect(error.message).toContain(mod.url);
    expect(connections).toEqual([]);
    await plain.close();

    const logger = infoLogger();
    const auth = mod.waitFor('authenticated');
    const opted = hub({ remoteFallback: true, logger });
    const b = load(opted.plugin(), new FakeBot('Alice'));
    expect((await auth)[0].method).toBe('device');
    await until(() => b.state.connected);
    expect(logger.infos.filter((m) => m.startsWith('connecting to'))).toEqual([`connecting to ${mod.url}, chosen by remoteFallback: no local endpoint file, using the most recently paired remote mod`]);
  });

  it('names the option when nothing is paired either, and keeps retrying', async () => {
    const h = hub({ remoteFallback: false });
    load(h.plugin(), new FakeBot('Alice'));
    await until(() => h.disconnects.length > 1);
    expect(h.disconnects.every((d) => d.reason === 'endpoint unavailable' && d.retry)).toBe(true);
    expect(h.errors[0]?.message).toMatch(/set the endpoint option, or remoteFallback: true/);
  });

  it('says where the endpoint came from', async () => {
    const store = new FileCredentialStore(credentials);
    const file = join(dir, 'endpoint.json');
    await writeFile(file, JSON.stringify({ url: 'ws://127.0.0.1:25590', localToken: 't' }));
    expect((await resolveTarget({ endpoint: 'ws://localhost:1' }, store, undefined, {})).source).toBe('the endpoint option');
    expect((await resolveTarget({ endpointFile: file }, store, undefined, {})).source).toBe(`the endpointFile option (${file})`);
    expect((await resolveTarget({}, store, undefined, { MINEFLAYER_BRIDGE_ENDPOINT: 'ws://127.0.0.1:2' })).source).toBe('MINEFLAYER_BRIDGE_ENDPOINT');
    expect((await resolveTarget({}, store, undefined, { MINEFLAYER_BRIDGE_ENDPOINT: file })).source).toBe(`MINEFLAYER_BRIDGE_ENDPOINT (file ${file})`);
    const local = join(dir, '.minecraft', 'config', 'mineflayer_bridge');
    const found = await rejection(resolveTarget({}, store, undefined, {}));
    expect(found.message).toContain('remoteFallback: true');
    // An env variable or endpointFile that points nowhere never falls back.
    const missing = await rejection(resolveTarget({ endpointFile: join(dir, 'nope.json'), remoteFallback: true }, store, undefined, {}));
    expect(missing).toMatchObject({ code: 'disconnected', retryable: true });
    expect(missing.message).not.toContain('remoteFallback');
    if (process.platform === 'win32') {
      await mkdir(local, { recursive: true });
      await writeFile(join(local, 'endpoint.json'), JSON.stringify({ url: 'ws://127.0.0.1:3' }));
      const t = await resolveTarget({}, store, undefined, {});
      expect(t.source).toBe(`the endpoint file ${join(local, 'endpoint.json')}`);
      expect(t.url).toBe('ws://127.0.0.1:3/bridge/v1');
    }
  });
});

describe('plain ws endpoints', () => {
  it('accepts loopback hosts and refuses others unless allowInsecure is set', async () => {
    const store = new FileCredentialStore(credentials);
    for (const url of ['ws://localhost:25590', 'ws://LOCALHOST:25590', 'ws://127.0.0.1:25590', 'ws://127.8.9.10:25590', 'ws://[::1]:25590']) {
      expect((await resolveTarget({ endpoint: url }, store, undefined, {})).pin).toBeUndefined();
    }
    for (const url of ['ws://10.0.0.2:25590', 'ws://example.net:25590', 'ws://127.0.0.1.example.net:25590', 'ws://[::2]:25590', 'ws://0.0.0.0:25590']) {
      const error = await rejection(resolveTarget({ endpoint: url }, store, undefined, {}));
      expect(error).toMatchObject({ code: 'unauthorized', retryable: false });
      expect(error.message).toContain('allowInsecure: true');
      const allowed = await resolveTarget({ endpoint: url, allowInsecure: true }, store, undefined, {});
      expect(allowed).toMatchObject({ pin: undefined, source: 'the endpoint option' });
    }
    const viaEnv = await rejection(resolveTarget({}, store, undefined, { MINEFLAYER_BRIDGE_ENDPOINT: 'ws://10.0.0.2:25590' }));
    expect(viaEnv.message).toMatch(/refusing the unencrypted endpoint ws:\/\/10\.0\.0\.2:25590 \(from MINEFLAYER_BRIDGE_ENDPOINT\)/);
  });

  it('fails the link without retrying or connecting', async () => {
    const h = hub({ endpoint: 'ws://192.0.2.1:25590' });
    load(h.plugin(), new FakeBot('Alice'));
    await until(() => h.disconnects.length > 0);
    expect(h.disconnects).toEqual([{ reason: 'unauthorized', retry: false }]);
    expect(h.errors).toHaveLength(1);
    expect(h.errors[0]).toMatchObject({ code: 'unauthorized' });
    await sleep(150);
    expect(h.disconnects).toHaveLength(1);
  });

  it('connects to a loopback mod as before, and a link can opt in on its own', async () => {
    const mod = await createMockMod({ heartbeatMs: 1000 });
    mods.push(mod);
    const file = join(dir, 'mc', 'endpoint.json');
    await mod.writeEndpointFile(file);
    const logger = infoLogger();
    const h = hub({ endpointFile: file, logger, links: [{ id: 'lan', endpoint: 'ws://192.0.2.1:25590', allowInsecure: true, reconnect: false }] });
    const b = load(h.plugin(), new FakeBot('Alice'));
    await until(() => b.state.connected);
    expect(logger.infos).toContain(`connecting to ${mod.url}, chosen by the endpointFile option (${file})`);
    // The opted-in link got past the check and tried the network.
    expect(logger.infos).toContain('connecting to ws://192.0.2.1:25590, chosen by the endpoint option');
    expect(h.errors.every((e) => !(e instanceof BridgeError && e.code === 'unauthorized'))).toBe(true);
  });
});
