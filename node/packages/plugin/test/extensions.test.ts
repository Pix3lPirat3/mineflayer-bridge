import { EventEmitter } from 'node:events';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { BridgeError, defineExtension, JsonFileStore, type ExtensionContext, type StandardSchemaV1 } from '../src/index.ts';
import { ALICE, FakeBot, fixture, load, OVERWORLD, sleep, until, type Env } from './helpers.ts';

let e: Env;
const { hub } = fixture((f) => (e = f.e));

describe('defineExtension', () => {
  it('validates ids and versions', () => {
    expect(() => defineExtension({ id: 'nonamespace', version: '1.0.0', setup() {} })).toThrow(/namespace:name/);
    expect(() => defineExtension({ id: 'a:b', version: 'one', setup() {} })).toThrow(/semver/);
    expect(() => defineExtension({ id: 'user:default', version: '1.0.0', setup() {} })).toThrow(/reserved/);
  });
});

describe('extension lifecycle', () => {
  it('loads in dependency order, exposes APIs and inherits hub extensions', async () => {
    const order: string[] = [];
    const base = defineExtension({
      id: 'test:base',
      version: '1.2.0',
      setup(ctx) {
        order.push(`base:${ctx.session.kind}`);
        return { greet: () => 'hi' };
      },
    });
    const top = defineExtension({
      id: 'test:top',
      version: '1.0.0',
      requires: { extensions: ['test:base'] },
      setup(ctx) {
        order.push('top');
        return { base: ctx.require<{ greet(): string }>('test:base').greet() };
      },
    });
    const h = hub({ extensions: [base] });
    const bot = new FakeBot('Alice');
    const b = load(h.plugin({ extensions: [top()] }), bot);
    await b.ready;
    expect(order).toEqual(['base:bot', 'top']);
    expect(b.get<{ base: string }>('test:top')?.base).toBe('hi');
    expect(b.has('test:base')).toBe(true);
    const [info] = await e.mod.waitFor('sessionOpened', () => true).catch(() => [e.mod.session(ALICE)!]);
    expect(info.extensions).toEqual([
      { id: 'test:base', version: '1.2.0' },
      { id: 'test:top', version: '1.0.0' },
    ]);
  });

  it('auto-loads dependencies given as factories', async () => {
    const dep = defineExtension({ id: 'test:dep', version: '1.0.0', setup: () => 42 });
    const user = defineExtension({ id: 'test:user', version: '1.0.0', requires: { extensions: [dep] }, setup: (ctx) => ctx.require<number>('test:dep') + 1 });
    const b = load(hub().plugin(), new FakeBot('Alice'));
    await b.use(user);
    expect(b.get('test:user')).toBe(43);
  });

  it('loads soft dependencies first when present and runs without them otherwise', async () => {
    const order: string[] = [];
    const tasks = defineExtension({ id: 'test:tasks', version: '1.0.0', setup: () => { order.push('tasks'); return { pause: () => 'paused' }; } });
    const seen: (string | undefined)[] = [];
    const trails = defineExtension({
      id: 'test:trails',
      version: '1.0.0',
      requires: { optionalExtensions: ['test:tasks'] },
      setup(ctx) {
        order.push('trails');
        seen.push(ctx.optional<{ pause(): string }>('test:tasks')?.pause());
        expect(() => ctx.optional('test:other')).toThrow(/optionalExtensions/);
      },
    });
    const b = load(hub().plugin(), new FakeBot('Alice'));
    await b.use(trails(), tasks());
    expect(order).toEqual(['tasks', 'trails']);
    await b.unload('test:trails');
    await b.unload('test:tasks');
    expect(b.has('test:tasks')).toBe(false);
    await b.use(trails());
    expect(seen).toEqual(['paused', undefined]);
  });

  it('rejects a dependency cycle and names it', async () => {
    const a = defineExtension({ id: 'test:a', version: '1.0.0', requires: { extensions: ['test:b'] }, setup() {} });
    const b2 = defineExtension({ id: 'test:b', version: '1.0.0', requires: { extensions: ['test:c'] }, setup() {} });
    const c = defineExtension({ id: 'test:c', version: '1.0.0', requires: { extensions: ['test:a'] }, setup() {} });
    const b = load(hub().plugin(), new FakeBot('Alice'));
    const err = await b.use(a, b2, c).catch((x: unknown) => x);
    expect(err).toBeInstanceOf(BridgeError);
    expect((err as BridgeError).message).toBe('extension dependency cycle: test:a -> test:b -> test:c -> test:a');
    expect(b.has('test:a')).toBe(false);
  });

  it('rejects a missing dependency and initial load failures through ready', async () => {
    const lonely = defineExtension({ id: 'test:lonely', version: '1.0.0', requires: { extensions: ['test:nope'] }, setup() {} });
    const bot = new FakeBot('Alice');
    const b = load(hub().plugin({ extensions: [lonely] }), bot);
    const errors: Error[] = [];
    b.on('error', (x) => errors.push(x));
    await expect(b.ready).rejects.toThrow('extension test:lonely requires test:nope, which is not loaded');
    await sleep(0);
    expect(errors).toHaveLength(1);
    await until(() => b.state.connected);
  });

  it('validates options with the mini schema and applies defaults', async () => {
    let seen: unknown;
    const viz = defineExtension({
      id: 'test:viz',
      version: '1.0.0',
      options: {
        colorByMove: { type: 'boolean', default: false },
        maxNodes: { type: 'integer', default: 100, min: 1, max: 1000 },
        mode: { type: 'string', enum: ['fast', 'pretty'], default: 'fast' },
        label: { type: 'string' },
      },
      setup(_ctx, options) {
        seen = options;
        const n: number = options.maxNodes;
        const m: 'fast' | 'pretty' = options.mode;
        return { n, m };
      },
    });
    const b = load(hub().plugin(), new FakeBot('Alice'));
    await b.use(viz({ colorByMove: true }));
    expect(seen).toEqual({ colorByMove: true, maxNodes: 100, mode: 'fast' });
    await b.unload('test:viz');
    const err = (await b.use(viz({ maxNodes: 0, mode: 'slow' as 'fast' })).catch((x: unknown) => x)) as BridgeError;
    expect(err.code).toBe('invalid_message');
    expect(err.message).toBe('invalid options for test:viz: maxNodes: must be >= 1; mode: expected one of fast, pretty');
  });

  it('validates options with a Standard Schema', async () => {
    const schema: StandardSchemaV1<{ radius?: number }, { radius: number }> = {
      '~standard': {
        version: 1,
        vendor: 'test',
        validate(value) {
          const v = value as { radius?: unknown };
          if (v.radius !== undefined && typeof v.radius !== 'number') return { issues: [{ message: 'must be a number', path: ['radius'] }] };
          return { value: { radius: (v.radius as number | undefined) ?? 8 } };
        },
      },
    };
    let radius = 0;
    const radar = defineExtension({
      id: 'test:radar',
      version: '1.0.0',
      options: schema,
      setup(_ctx, options) {
        radius = options.radius;
      },
    });
    const b = load(hub().plugin(), new FakeBot('Alice'));
    await b.use(radar());
    expect(radius).toBe(8);
    await b.unload('test:radar');
    await expect(b.use(radar({ radius: 'big' as unknown as number }))).rejects.toThrow('invalid options for test:radar: radius: must be a number');
  });

  it('rejects mod requirements the mod does not meet', async () => {
    const needsMissing = defineExtension({ id: 'test:needs', version: '1.0.0', requires: { mod: ['test:never-advertised@^1', 'core:path@^1'] }, setup() {} });
    const b = load(hub().plugin(), new FakeBot('Alice'));
    await until(() => b.state.connected);
    const err = (await b.use(needsMissing).catch((x: unknown) => x)) as BridgeError;
    expect(err.code).toBe('unsupported');
    expect(err.details).toEqual({ missing: ['test:never-advertised@^1'] });
  });

  it('unloads dependents first, aborts the signal, detaches listeners and removes objects with one prefix', async () => {
    const events: string[] = [];
    const emitter = new EventEmitter();
    const base = defineExtension({
      id: 'test:base',
      version: '1.0.0',
      setup(ctx) {
        ctx.layer('a').box('x', [0, 0, 0], 1);
        ctx.signal.addEventListener('abort', () => events.push('base aborted'));
      },
    });
    const top = defineExtension({
      id: 'test:top',
      version: '1.0.0',
      requires: { extensions: ['test:base'] },
      setup(ctx) {
        ctx.layer('b').box('y', [0, 0, 0], 1);
        ctx.on<[number]>(emitter, 'tick', (n) => events.push(`tick ${n}`));
        ctx.signal.addEventListener('abort', () => events.push('top aborted'));
      },
    });
    const b = load(hub().plugin({ extensions: [base, top] }), new FakeBot('Alice'));
    await b.ready;
    b.layer('mine').box('z', [0, 0, 0], 1);
    await until(() => e.mod.object(`${ALICE}/test:top/b/y`) && e.mod.object(`${ALICE}/user:default/mine/z`));
    emitter.emit('tick', 1);
    const removes: unknown[] = [];
    e.mod.on('message', (m) => {
      if (m.type === 'obj.remove') removes.push(m.payload);
    });
    await b.unload('test:base');
    emitter.emit('tick', 2);
    expect(events).toEqual(['tick 1', 'top aborted', 'base aborted']);
    expect(b.has('test:top')).toBe(false);
    await until(() => !e.mod.object(`${ALICE}/test:base/a/x`));
    expect(removes).toEqual([{ keys: [], prefixes: [`${ALICE}/test:top/`, `${ALICE}/test:base/`] }]);
    expect(e.mod.object(`${ALICE}/test:top/b/y`)).toBeUndefined();
    expect(e.mod.object(`${ALICE}/user:default/mine/z`)).toBeDefined();
    expect(emitter.listenerCount('tick')).toBe(0);
  });

  it('rejects pending shared ops of an unloaded extension with cancelled', async () => {
    let ctxRef: ExtensionContext | undefined;
    const ext = defineExtension({
      id: 'test:zones',
      version: '1.0.0',
      setup(ctx) {
        ctxRef = ctx;
      },
    });
    const h = hub({ endpoint: { url: 'ws://127.0.0.1:1' }, endpointFile: undefined, reconnect: false });
    const b = load(h.plugin({ extensions: [ext] }), new FakeBot('Alice'));
    // The endpoint is unreachable, so `ready` (which waits for the first open) never resolves here.
    await until(() => ctxRef !== undefined, 5000, 'extension setup');
    const pending = ctxRef!.shared.create('z1', 'core:box', { min: { x: 0, y: 0, z: 0 }, size: { x: 1, y: 1, z: 1 } }).catch((x: unknown) => x);
    await b.unload('test:zones');
    expect(((await pending) as BridgeError).code).toBe('cancelled');
  });

  it('runs whenVisible while the layer is visible and lifecycle hooks with auto-removal', async () => {
    const log: string[] = [];
    const ext = defineExtension({
      id: 'test:heat',
      version: '1.0.0',
      setup(ctx) {
        ctx.layer('nodes', { defaultVisible: false });
        ctx.whenVisible('nodes', (signal) => {
          log.push('start');
          signal.addEventListener('abort', () => log.push('abort'));
          return () => log.push('stop');
        });
        ctx.onAttached(() => log.push('attached'));
        ctx.onConnected(() => log.push('connected'));
      },
    });
    const b = load(hub().plugin({ extensions: [ext] }), new FakeBot('Alice'));
    await until(() => b.state.connected);
    e.mod.setPlayerContext(OVERWORLD);
    await until(() => b.state.worldMatched);
    expect(log).toEqual(['connected', 'attached']);
    e.mod.setLayerVisible({ extension: 'test:heat', layer: 'nodes' }, true);
    await until(() => log.includes('start'));
    e.mod.setLayerVisible({ layer: 'nodes' }, false);
    await until(() => log.includes('stop'));
    expect(log).toEqual(['connected', 'attached', 'start', 'abort', 'stop']);
    e.mod.setLayerVisible({ layer: 'nodes' }, true);
    await until(() => log.filter((x) => x === 'start').length === 2);
    await b.unload('test:heat');
    expect(log.slice(-2)).toEqual(['abort', 'stop']);
    e.mod.setAttached(ALICE, false);
    await until(() => !b.state.attached);
    e.mod.setAttached(ALICE, true);
    await until(() => b.state.attached);
    expect(log.filter((x) => x === 'attached')).toHaveLength(1);
  });

  it('persists persistent objects and extension stores and re-declares them on start', async () => {
    const path = join(e.dir, 'store.json');
    const ext = defineExtension({
      id: 'test:memo',
      version: '1.0.0',
      async setup(ctx) {
        const count = ((await ctx.store.get('count')) as number | undefined) ?? 0;
        await ctx.store.set('count', count + 1);
        if (count === 0) ctx.layer('marks').waypoint('home', [1, 2, 3], 'Home', { persistent: true });
        return count;
      },
    });
    const h1 = hub({ store: path });
    const bot1 = new FakeBot('Alice');
    const b1 = load(h1.plugin({ extensions: [ext] }), bot1);
    await b1.ready;
    await until(() => e.mod.object(`${ALICE}/test:memo/marks/home`));
    await h1.close();
    expect(e.mod.object(`${ALICE}/test:memo/marks/home`)).toBeUndefined();
    const saved = await new JsonFileStore(path).load(ALICE);
    expect(saved?.values).toEqual({ 'test:memo': { count: 1 } });
    expect(saved?.objects.map((o) => o.key)).toEqual(['test:memo/marks/home']);

    const b2 = load(hub({ store: path }).plugin({ extensions: [ext] }), new FakeBot('Alice'));
    await b2.ready;
    expect(b2.get('test:memo')).toBe(1);
    await until(() => e.mod.object(`${ALICE}/test:memo/marks/home`));
    expect(e.mod.object(`${ALICE}/test:memo/marks/home`)?.lifetime).toBe('persistent');
  });
});
