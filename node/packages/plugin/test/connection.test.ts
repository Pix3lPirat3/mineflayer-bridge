import { EventEmitter } from 'node:events';
import { describe, expect, it } from 'vitest';
import { BridgeError, FileCredentialStore } from '../src/index.ts';
import { ALICE, ALICE_PREFIX, BOT_OPTIONS, FakeBot, fixture, load, OVERWORLD, sleep, until, within, type Env } from './helpers.ts';

let e: Env;
const { hub } = fixture((f) => (e = f.e));

describe('handshake and session', () => {
  it('connects through the endpoint file and opens a bot session with its owner prefix', async () => {
    const opened = e.mod.waitFor('sessionOpened');
    const bot = new FakeBot('Alice');
    const b = load(hub().plugin(), bot);
    const [info] = await opened;
    expect(info.owner).toBe(ALICE);
    expect(info.identity).toEqual({ username: 'Alice', uuid: undefined, server: 'play.example.net:25565' });
    await until(() => b.state.connected);
    expect(b.session.owner).toBe(ALICE);
    expect(b.supports('core:box@^1')).toBe(true);
    expect(b.supports('test:never-advertised')).toBe(false);
  });

  it('keeps a stable device id in the credentials file', async () => {
    const auth = e.mod.waitFor('authenticated');
    load(hub().plugin(), new FakeBot('Alice'));
    const [{ deviceId, method }] = await auth;
    expect(method).toBe('local');
    expect(await new FileCredentialStore(e.credentials).deviceId()).toBe(deviceId);
  });

  it('rejects a bad token with goodbye unauthorized and stops retrying', async () => {
    const h = hub({ endpoint: { url: e.mod.url, localToken: Buffer.alloc(32, 1).toString('base64url') }, endpointFile: undefined });
    const rejected = e.mod.waitFor('rejected');
    const errors: Error[] = [];
    h.on('error', (err) => errors.push(err));
    const gone = new Promise<{ reason: string; retry: boolean }>((resolve) => h.on('disconnected', resolve));
    load(h.plugin(), new FakeBot('Alice'));
    await rejected;
    expect(await gone).toEqual({ reason: 'unauthorized', retry: false });
    expect(errors.some((x) => x instanceof BridgeError && x.code === 'unauthorized')).toBe(true);
    expect(h.connected).toBe(false);
  });

  it('asks for approval of new local devices and honours a denial', async () => {
    await e.restart({ approve: () => false });
    const h = hub({ reconnect: false });
    const gone = new Promise<{ reason: string }>((resolve) => h.on('disconnected', resolve));
    load(h.plugin(), new FakeBot('Alice'));
    expect((await gone).reason).toBe('unauthorized');
  });

  it('authenticates with a stored device credential', async () => {
    const issued = e.mod.issueDevice();
    await new FileCredentialStore(e.credentials).saveDevice(issued.fingerprint, { deviceId: issued.deviceId, secret: issued.secretBase64Url });
    const auth = e.mod.waitFor('authenticated');
    load(hub({ endpoint: { url: e.mod.url, fingerprint: issued.fingerprint }, endpointFile: undefined }).plugin(), new FakeBot('Alice'));
    const [a] = await auth;
    expect(a).toMatchObject({ method: 'device', deviceId: issued.deviceId });
  });

  it('waits for login before opening when the username is not known yet', async () => {
    const bot = new FakeBot(undefined);
    load(hub().plugin(), bot);
    await sleep(100);
    expect(e.mod.sessions()).toHaveLength(0);
    bot.username = 'Alice';
    bot.emit('login');
    await until(() => e.mod.session(ALICE));
  });
});

describe('declared state', () => {
  it('sends declarations made before connecting as one snapshot', async () => {
    const bot = new FakeBot('Alice');
    const h = hub();
    const b = load(h.plugin(), bot);
    const layer = b.layer('route');
    layer.box('a', [1, 2, 3], 1);
    layer.label('l', { x: 0, y: 70, z: 0 }, 'hello', { scale: 2 });
    const [{ removed }] = await e.mod.waitFor('snapshotEnd');
    expect(removed).toEqual([]);
    expect(e.mod.objectData(`${ALICE_PREFIX}/route/a`, 'core:box')).toEqual({ min: { x: 1, y: 2, z: 3 }, size: { x: 1, y: 1, z: 1 }, style: undefined });
    expect(e.mod.objectData(`${ALICE_PREFIX}/route/l`, 'core:label')).toMatchObject({ text: 'hello', scale: 2 });
    expect(e.mod.object(`${ALICE_PREFIX}/route`)).toMatchObject({ kind: 'core:layer', layer: 'route' });
  });

  it('writes shapes with decoded data, world binding, styles and colours', async () => {
    const bot = new FakeBot('Alice');
    const b = load(hub().plugin(), bot);
    await until(() => b.state.connected);
    b.style('danger', { color: '#ff3b30', fill: 0.25, depth: 'both' });
    const layer = b.layer('debug', { category: 'debug', defaultVisible: false });
    layer.box('target', { x: 10, y: 64, z: -5 }, [1, 2, 1], { style: 'danger' });
    layer.line('ln', [0, 0, 0], [1, 1, 1], { arrow: true, style: { color: 0x00ff00 } });
    layer.path('p', [[0, 0, 0], [1, 0, 0], [2, 1, 0]], { classes: [0, 1], classStyles: { 0: 'walk', 1: 'jump' } });
    layer.entityTag('e', { id: 42 }, { text: 'cow', outline: '#ffffff80', tracer: true });
    layer.waypoint('w', [5, 70, 5], 'home', { beam: true, tracer: true });
    await until(() => e.mod.object(`${ALICE_PREFIX}/debug/w`));
    const box = e.mod.object(`${ALICE_PREFIX}/debug/target`)!;
    expect(box).toMatchObject({ kind: 'core:box', scope: 'world', world: OVERWORLD, layer: 'debug', lifetime: 'session' });
    expect(box.data).toMatchObject({ min: { x: 10, y: 64, z: -5 }, size: { x: 1, y: 2, z: 1 }, style: 'danger' });
    expect(e.mod.objectData(`${ALICE_PREFIX}/~style/danger`, 'core:style')).toMatchObject({ color: 0xffff3b30, depth: 'both' });
    expect(e.mod.objectData(`${ALICE_PREFIX}/debug/ln`, 'core:line')).toMatchObject({ arrow: true, style: '~inline/debug/ln' });
    expect(e.mod.objectData(`${ALICE_PREFIX}/~style/~inline/debug/ln`, 'core:style')?.color).toBe(0xff00ff00);
    const path = e.mod.objectData(`${ALICE_PREFIX}/debug/p`, 'core:path')!;
    expect(Array.from(path.points)).toEqual([0, 0, 0, 1, 0, 0, 2, 1, 0]);
    expect(Array.from(path.classes!)).toEqual([0, 1]);
    expect(path.classStyles).toEqual({ 0: 'walk', 1: 'jump' });
    expect(e.mod.objectData(`${ALICE_PREFIX}/debug/e`, 'core:entity-tag')).toMatchObject({ entity: 42, text: 'cow', outline: 0x80ffffff, tracer: true });
    expect(e.mod.objectData(`${ALICE_PREFIX}/debug/w`, 'core:waypoint')).toMatchObject({ name: 'home', beam: true, tracer: true });
    expect(e.mod.objectData(`${ALICE_PREFIX}/debug`, 'core:layer')).toEqual({ category: 'debug', defaultVisible: false });
  });

  it('sends changes to an existing shape as a field patch and batches a tick into one upsert', async () => {
    const bot = new FakeBot('Alice');
    const b = load(hub().plugin(), bot);
    const layer = b.layer('route');
    layer.box('a', [0, 0, 0], 1);
    await until(() => e.mod.object(`${ALICE_PREFIX}/route/a`));
    const upserts: { full: number; patch: number }[] = [];
    e.mod.on('message', (m) => {
      if (m.type !== 'obj.upsert') return;
      const writes = (m.payload as { writes: { type: string }[] }).writes;
      upserts.push({ full: writes.filter((w) => w.type === 'full').length, patch: writes.filter((w) => w.type === 'patch').length });
    });
    const rev = e.mod.object(`${ALICE_PREFIX}/route/a`)!.rev;
    for (let i = 1; i <= 10; i++) layer.box('a', [i, 0, 0], 1);
    layer.box('b', [0, 0, 0], 1);
    await until(() => e.mod.objectData(`${ALICE_PREFIX}/route/a`, 'core:box')?.min.x === 10);
    await until(() => e.mod.object(`${ALICE_PREFIX}/route/b`));
    expect(upserts).toEqual([{ full: 1, patch: 1 }]);
    expect(e.mod.object(`${ALICE_PREFIX}/route/a`)!.rev).toBeGreaterThan(rev);
  });

  it('removes shapes, clears a layer with one prefix removal, and leaves other layers alone', async () => {
    const bot = new FakeBot('Alice');
    const b = load(hub().plugin(), bot);
    const route = b.layer('route');
    const other = b.layer('other');
    route.box('a', [0, 0, 0], 1);
    route.box('b', [0, 0, 0], 1, { style: { color: '#ffffff' } });
    other.box('c', [0, 0, 0], 1);
    await until(() => e.mod.object(`${ALICE_PREFIX}/other/c`));
    const removes: unknown[] = [];
    e.mod.on('message', (m) => {
      if (m.type === 'obj.remove') removes.push(m.payload);
    });
    route.remove('a');
    await until(() => !e.mod.object(`${ALICE_PREFIX}/route/a`));
    route.clear();
    await until(() => !e.mod.object(`${ALICE_PREFIX}/route/b`));
    expect(removes).toEqual([
      { keys: [`${ALICE_PREFIX}/route/a`], prefixes: [] },
      { keys: [], prefixes: [`${ALICE_PREFIX}/route/`, `${ALICE_PREFIX}/~style/~inline/route/`] },
    ]);
    expect(e.mod.object(`${ALICE_PREFIX}/route`)).toBeDefined();
    expect(e.mod.object(`${ALICE_PREFIX}/other/c`)).toBeDefined();
    expect(e.mod.object(`${ALICE_PREFIX}/~style/~inline/route/b`)).toBeUndefined();
  });

  it('expires shapes with a TTL and refreshes the TTL on rewrite', async () => {
    const bot = new FakeBot('Alice');
    const b = load(hub().plugin(), bot);
    const layer = b.layer('fx');
    layer.box('short', [0, 0, 0], 1, { ttlMs: 150 });
    layer.box('long', [0, 0, 0], 1, { ttlMs: 400 });
    await until(() => e.mod.object(`${ALICE_PREFIX}/fx/long`));
    expect(e.mod.object(`${ALICE_PREFIX}/fx/short`)?.ttlMs).toBe(150);
    await sleep(250);
    expect(e.mod.object(`${ALICE_PREFIX}/fx/short`)).toBeUndefined();
    layer.box('long', [0, 0, 0], 1, { ttlMs: 400 });
    await sleep(250);
    expect(e.mod.object(`${ALICE_PREFIX}/fx/long`)).toBeDefined();
    await until(() => !e.mod.object(`${ALICE_PREFIX}/fx/long`), 2000);
  });

  it('sends notify and coalesces latest-wins status updates', async () => {
    const bot = new FakeBot('Alice');
    const b = load(hub().plugin(), bot);
    e.mod.setPlayerContext(OVERWORLD);
    await until(() => b.state.attached);
    const statuses: unknown[] = [];
    e.mod.on('message', (m) => {
      if (m.type === 'presence.status') statuses.push((m.payload as { status: unknown }).status);
    });
    for (let i = 0; i < 100; i++) b.status({ task: 'mining', n: i });
    b.notify('done', { level: 'success', icon: 'minecraft:diamond' });
    await until(() => e.mod.notifies.length === 1);
    await sleep(50);
    expect(statuses).toEqual([{ task: 'mining', n: 99 }]);
    expect(e.mod.notifies[0]).toEqual({ owner: ALICE, text: 'done', level: 'success', icon: 'minecraft:diamond', buttons: [], sound: undefined });
    expect(e.mod.session(ALICE)?.status).toEqual({ task: 'mining', n: 99 });
  });

  it('paces to the advertised budget and coalesces latest-wins values while throttled', async () => {
    await e.restart({ budget: { messagesPerSecond: 10, bytesPerSecond: 1 << 20, streamBytesInFlight: 1 << 20 } });
    const bot = new FakeBot('Alice');
    const b = load(hub().plugin(), bot);
    await until(() => b.state.connected);
    await sleep(300);
    const statuses: number[] = [];
    e.mod.on('message', (m) => {
      if (m.type === 'presence.status') statuses.push((m.payload as { status: number }).status);
    });
    for (let i = 0; i < 60; i++) {
      b.status(i);
      await new Promise((r) => setImmediate(r));
    }
    await until(() => statuses.at(-1) === 59, 3000);
    expect(statuses.length).toBeLessThan(30);
  });

  it('paces all bots of one hub against one budget, as the mod counts the device', async () => {
    await e.restart({ budget: { messagesPerSecond: 10, bytesPerSecond: 1 << 20, streamBytesInFlight: 1 << 20 } });
    const h = hub();
    const bots = [load(h.plugin(), new FakeBot('Alice')), load(h.plugin(), new FakeBot('Bob'))];
    await until(() => bots.every((b) => b.state.connected));
    await sleep(1100);
    let sent = 0;
    e.mod.on('message', (m) => {
      if (m.type === 'presence.status') sent++;
    });
    const end = Date.now() + 1000;
    for (let i = 0; Date.now() < end; i++) {
      for (const b of bots) b.status(i);
      await new Promise((r) => setImmediate(r));
    }
    // A full bucket of 10 plus 10 a second; two buckets would let about 40 through.
    expect(sent).toBeLessThanOrEqual(24);
    expect(sent).toBeGreaterThan(5);
  });

  it('removes stale objects when a restarted bot process re-declares within the resume window', async () => {
    const h1 = hub();
    const first = load(h1.plugin(), new FakeBot('Alice'));
    first.layer('route').box('keep', [0, 0, 0], 1);
    first.layer('route').box('stale', [0, 0, 0], 1);
    await until(() => e.mod.object(`${ALICE_PREFIX}/route/stale`));
    e.mod.disconnect(ALICE);
    await h1.close();
    expect(e.mod.object(`${ALICE_PREFIX}/route/stale`)).toBeDefined();
    const ended = e.mod.waitFor('snapshotEnd');
    const second = load(hub().plugin(), new FakeBot('Alice'));
    second.layer('route').box('keep', [1, 1, 1], 1);
    const [{ removed }] = await ended;
    expect(removed).toEqual([`${ALICE_PREFIX}/route/stale`]);
    expect(e.mod.objectData(`${ALICE_PREFIX}/route/keep`, 'core:box')?.min).toEqual({ x: 1, y: 1, z: 1 });
  });

  it('marks the older session superseded when the same identity opens again', async () => {
    const first = load(hub().plugin(), new FakeBot('Alice'));
    await until(() => first.state.connected);
    const superseded = new Promise<void>((resolve) => first.on('superseded', resolve));
    load(hub().plugin(), new FakeBot('Alice'));
    await superseded;
    expect(first.state.superseded).toBe(true);
  });

  it('closes the session when the bot ends and the mod drops its objects', async () => {
    const bot = new FakeBot('Alice');
    const b = load(hub().plugin(), bot);
    b.layer('route').box('a', [0, 0, 0], 1);
    await until(() => e.mod.object(`${ALICE_PREFIX}/route/a`));
    const closed = e.mod.waitFor('sessionClosed');
    bot.emit('end');
    expect((await closed)[0].reason).toBe('closed');
    expect(e.mod.objects(ALICE)).toEqual([]);
    // The closed bridge stays on the bot and ignores calls.
    expect(bot.bridge).toBe(b);
    expect(b.state.connected).toBe(false);
    b.notify('after the end');
    b.layer('route').box('b', [0, 0, 0], 1);
  });
});

describe('world, attach and interest', () => {
  it('reports the world by its own name, which servers give worlds on the overworld type', async () => {
    const bot = new FakeBot('Alice');
    const client = new EventEmitter();
    Object.assign(bot, { _client: client });
    load(hub().plugin(), bot);
    await until(() => e.mod.session(ALICE)?.world);
    expect(e.mod.session(ALICE)?.world).toEqual(OVERWORLD);
    // A proxy moved the bot to its creative server: mineflayer still says overworld.
    client.emit('respawn', { worldState: { name: 'minecraft:creative', dimension: 0 } });
    bot.emit('respawn');
    await until(() => e.mod.session(ALICE)?.world?.dimension === 'minecraft:creative');
    // Older protocols name it worldName.
    client.emit('login', { worldName: 'skyblock' });
    await until(() => e.mod.session(ALICE)?.world?.dimension === 'minecraft:skyblock');
  });

  it('reports the world on spawn and dimension change, and positions at most 2 Hz only while attached', async () => {
    const bot = new FakeBot('Alice');
    const b = load(hub().plugin(), bot);
    const reports: { world?: unknown; position?: { x: number } }[] = [];
    e.mod.on('message', (m) => {
      if (m.type === 'world.report') reports.push(m.payload as { position?: { x: number } });
    });
    await until(() => e.mod.session(ALICE)?.world);
    expect(e.mod.session(ALICE)?.world).toEqual(OVERWORLD);
    expect(reports[0]?.position).toEqual({ x: 0, y: 64, z: 0 });
    reports.length = 0;
    expect(b.state.attached).toBe(false);
    bot.move(1, 64, 0);
    await sleep(50);
    expect(reports).toHaveLength(0);

    const events: string[] = [];
    for (const ev of ['attached', 'detached', 'worldMatched', 'worldUnmatched'] as const) b.on(ev, () => events.push(ev));
    e.mod.setPlayerContext(OVERWORLD);
    await until(() => b.state.attached && b.state.worldMatched);
    expect(b.state.playerWorld).toEqual(OVERWORLD);
    expect(events).toEqual(['attached', 'worldMatched']);

    reports.length = 0;
    await sleep(520);
    for (let i = 0; i < 20; i++) bot.move(i, 64, 0);
    await sleep(100);
    expect(reports).toHaveLength(1);
    await sleep(500);
    expect(reports).toHaveLength(2);
    expect(reports[1]?.position?.x).toBe(19);

    bot.game.dimension = 'the_nether';
    bot.emit('respawn');
    await until(() => !b.state.worldMatched);
    expect(e.mod.session(ALICE)?.world).toEqual({ server: OVERWORLD.server, dimension: 'minecraft:the_nether' });
    expect(events).toEqual(['attached', 'worldMatched', 'detached', 'worldUnmatched']);
  });

  it('honours manual attach overrides', async () => {
    const bot = new FakeBot('Alice');
    const b = load(hub().plugin(), bot);
    await until(() => b.state.connected);
    e.mod.setAttached(ALICE, true);
    await until(() => b.state.attached);
    expect(b.state.worldMatched).toBe(false);
    e.mod.setAttached(ALICE, false);
    await until(() => !b.state.attached);
  });

  it('gates layers on interest: isVisible follows attach, world and player toggles', async () => {
    const bot = new FakeBot('Alice');
    const b = load(hub().plugin(), bot);
    const route = b.layer('route');
    const nodes = b.layer('nodes', { category: 'debug', defaultVisible: false });
    await until(() => b.state.connected);
    expect(route.isVisible()).toBe(false);
    e.mod.setPlayerContext(OVERWORLD);
    await until(() => route.isVisible());
    expect(nodes.isVisible()).toBe(false);
    e.mod.setLayerVisible({ category: 'debug' }, true);
    await until(() => nodes.isVisible());
    e.mod.setLayerVisible({ owner: ALICE, layer: 'route' }, false);
    await until(() => !route.isVisible());
    e.mod.setPlayerContext(null);
    await until(() => !nodes.isVisible());
  });
});

describe('reconnect and resume', () => {
  it('reconnects after a mod restart with the new token and re-declares everything', async () => {
    const bot = new FakeBot('Alice');
    const h = hub();
    const b = load(h.plugin(), bot);
    b.layer('route').box('a', [0, 0, 0], 1);
    b.style('s', { color: '#123456' });
    await until(() => e.mod.object(`${ALICE_PREFIX}/route/a`));
    const oldToken = e.mod.localToken;
    const reconnected = new Promise<void>((resolve) => b.on('connected', resolve));
    const mod = await e.restart();
    expect(mod.localToken).not.toBe(oldToken);
    b.layer('route').box('b', [0, 0, 0], 1);
    await reconnected;
    await until(() => mod.object(`${ALICE_PREFIX}/route/b`));
    expect(mod.objects(ALICE).map((o) => o.key)).toEqual([`${ALICE_PREFIX}/route`, `${ALICE_PREFIX}/route/a`, `${ALICE_PREFIX}/route/b`, `${ALICE_PREFIX}/~style/s`]);
  });

  it('resumes after a dropped socket and replays unacknowledged messages in both directions', async () => {
    await e.restart({ heartbeatMs: 60_000 });
    const bot = new FakeBot('Alice');
    const b = load(hub().plugin(), bot);
    b.shared.subscribe();
    b.layer('route').box('a', [0, 0, 0], 1);
    await until(() => e.mod.object(`${ALICE_PREFIX}/route/a`), 5000, 'first box');
    await sleep(300);
    const before = e.mod.session(ALICE)!;

    // Plugin to mod: frames lost in flight are replayed from the plugin's log.
    e.mod.blackhole(ALICE);
    b.layer('route').box('lost', [0, 0, 0], 1);
    await sleep(50);
    expect(e.mod.object(`${ALICE_PREFIX}/route/lost`)).toBeUndefined();
    // Listen first: on a fast machine the plugin can reconnect and resume within one poll.
    const closed = e.mod.waitFor('connectionClosed');
    const resuming = e.mod.waitFor('sessionResumed');
    e.mod.disconnect(ALICE);
    await within(closed, 5000, 'disconnect');

    // Mod to plugin: a shared broadcast while detached is logged by the mod and replayed. It runs in
    // the same tick as the close, before any reconnect timer can fire.
    e.mod.putShared('shared/test/zone', 'core:box', { min: { x: 1, y: 1, z: 1 }, size: { x: 2, y: 2, z: 2 } });

    const [resumed] = await within(resuming, 5000, 'resume');
    expect(resumed.owner).toBe(ALICE);
    expect(resumed.connectionId).not.toBe(before.connectionId);
    await until(() => e.mod.object(`${ALICE_PREFIX}/route/lost`), 5000, 'replayed box');
    await until(() => b.shared.get('shared/test/zone'), 5000, 'replayed shared zone');
    expect(b.shared.get('shared/test/zone')?.data).toMatchObject({ size: { x: 2, y: 2, z: 2 } });
    expect(e.mod.sessions()).toHaveLength(1);
  });

  it('acks with an empty ack after 250 ms of silence so the peer trims its log', async () => {
    const bot = new FakeBot('Alice');
    const b = load(hub().plugin(), bot);
    b.shared.subscribe();
    await until(() => b.state.connected);
    const acks: number[] = [];
    e.mod.on('message', (m) => {
      if (m.type === 'ack' && m.header.ack !== undefined) acks.push(m.header.ack);
    });
    e.mod.putShared('shared/test/a', 'core:box', { min: { x: 0, y: 0, z: 0 }, size: { x: 1, y: 1, z: 1 } });
    await until(() => acks.length > 0, 2000);
    expect(acks[0]).toBeGreaterThanOrEqual(1);
  });

  it('falls back to a fresh session and full resync when resume fails', async () => {
    const bot = new FakeBot('Alice');
    const b = load(hub().plugin(), bot);
    b.layer('route').box('a', [0, 0, 0], 1);
    await until(() => e.mod.object(`${ALICE_PREFIX}/route/a`));
    const resumes: string[] = [];
    e.mod.on('message', (m) => {
      if (m.type === 'session.resume' || m.type === 'session.open') resumes.push(m.type);
    });
    const resynced = e.mod.waitFor('snapshotEnd');
    e.mod.expireSession(ALICE);
    expect(e.mod.object(`${ALICE_PREFIX}/route/a`)).toBeUndefined();
    await resynced;
    expect(resumes).toEqual(['session.resume', 'session.open']);
    expect(e.mod.object(`${ALICE_PREFIX}/route/a`)).toBeDefined();
    expect(b.state.connected).toBe(true);
  });
});

describe('swarm and controller', () => {
  it('multiplexes 50 bots over one hub connection', async () => {
    const h = hub();
    const connections = new Set<number>();
    e.mod.on('connection', ({ connectionId }) => connections.add(connectionId));
    const bots = Array.from({ length: 50 }, (_, i) => new FakeBot(`Bot${i}`));
    const plugin = h.plugin();
    for (const bot of bots) load(plugin, bot).layer('pos').box('me', [0, 0, 0], 1);
    await until(() => e.mod.objects().filter((o) => o.kind === 'core:box').length === 50, 10_000);
    expect(connections.size).toBe(1);
    const sessions = e.mod.sessions();
    expect(sessions).toHaveLength(50);
    expect(new Set(sessions.map((s) => s.connectionId)).size).toBe(1);
    expect(new Set(sessions.map((s) => s.id)).size).toBe(50);
  });

  it('opens a controller session owning ctl:<device>/<name>', async () => {
    const h = hub();
    const ctl = h.controller({ name: 'swarm-manager' });
    ctl.layer('overview').label('t', [0, 0, 0], 'swarm');
    const [info] = await e.mod.waitFor('sessionOpened');
    expect(info.kind).toBe('controller');
    const device = await new FileCredentialStore(e.credentials).deviceId();
    expect(info.owner).toBe(`ctl:${device}/swarm-manager`);
    await until(() => e.mod.object(`ctl:${device}/swarm-manager/user:default/overview/t`));
    expect(ctl.state.attached).toBe(true);
    expect(() => h.controller({ name: 'a/b' })).toThrow(BridgeError);
  });
});

describe('bot options', () => {
  it('uses the default port and lowercases the host', async () => {
    const bot = new FakeBot('Bob');
    load(hub().plugin(), bot, { host: 'Example.ORG', port: 25570 });
    await until(() => e.mod.session('bot:bob@example.org:25570'));
    const bot2 = new FakeBot('Carl');
    load(hub().plugin(), bot2, {});
    await until(() => e.mod.session('bot:carl@localhost:25565'));
    expect(BOT_OPTIONS.host).toBe('play.example.net');
  });
});
