import { afterEach, describe, expect, expectTypeOf, it, vi } from 'vitest';
import type { MockInputRequest } from '@mineflayer-bridge/mock-mod';
import {
  BridgeError,
  defineExtension,
  plainKind,
  plainMessage,
  tasks,
  WireReader,
  WireWriter,
  type BridgePlayer,
  type KindType,
  type Logger,
  type SurfaceRefusal,
  type TasksApi,
} from '../src/index.ts';
import { ALICE, ALICE_PREFIX, FakeBot, fixture, load, OVERWORLD, rejection, sleep, thrown, until, type Env } from './helpers.ts';

let e: Env;
const f = fixture((x) => (e = x.e));
const { hub, attached } = f;

afterEach(() => {
  vi.useRealTimers();
});

// Kept from before any test fakes the timers, to let real socket traffic pass while fake time runs.
const realSetTimeout = setTimeout;

/** Runs `ms` of fake time in steps, giving the sockets a moment of real time at each. */
async function pump(ms: number, until: () => boolean = () => false): Promise<void> {
  for (let t = 0; t < ms && !until(); t += 100) {
    await vi.advanceTimersByTimeAsync(100);
    await new Promise((r) => realSetTimeout(r, 3));
  }
}

const nextInput = async (): Promise<MockInputRequest> => (await e.mod.waitFor('inputRequest'))[0];
const FIELDS = [{ id: 'n', type: 'number' }] as const;

/** A logger that keeps what it is given, by level. */
function recorder(): Logger & { errors: string[]; warnings: string[] } {
  const errors: string[] = [];
  const warnings: string[] = [];
  return { errors, warnings, debug() {}, info() {}, warn: (m) => warnings.push(m), error: (m) => errors.push(m) };
}

/** A clock the test moves by hand, for the mock's cooldowns. */
function manualClock(): { now(): number; advance(ms: number): void } {
  let t = 1_000_000;
  return { now: () => t, advance: (ms) => void (t += ms) };
}

/** Opens a form and has the player dismiss it. */
async function dismissForm(b: { form: (d: { title: string; fields: typeof FIELDS }) => Promise<unknown> }): Promise<void> {
  const queued = nextInput();
  const form = b.form({ title: 'Dismiss me', fields: FIELDS });
  e.mod.cancel((await queued).id);
  expect((await rejection(form)).code).toBe('cancelled');
}

describe('refused surfaces', () => {
  it('refuses unasked surfaces after a dismissal for 5, 15 and 30 s, and starts over after an answer or a quiet minute', async () => {
    const clock = manualClock();
    await e.restart({ clock, surfaceCooldownsMs: undefined as never });
    const b = await attached();
    const refusals: SurfaceRefusal[] = [];
    b.on('refused', (r) => refusals.push(r));
    const refusedAfterDismissal = async (): Promise<BridgeError> => {
      await dismissForm(b);
      return rejection(b.form({ title: 'Again', fields: FIELDS }));
    };

    let err = await refusedAfterDismissal();
    expect(err).toMatchObject({ code: 'cooldown', retryable: true, retryAfterMs: 5000 });
    expect(refusals).toHaveLength(1);
    expect(refusals[0]).toMatchObject({ surface: 'form', code: 'cooldown', retryAfterMs: 5000, retries: 0 });
    expect(refusals[0]!.error).toBe(err);
    // Picks, prompts and world menus are held back the same way.
    expect((await rejection(b.prompt.confirm('Sure?'))).code).toBe('cooldown');
    expect((await rejection(b.pick.block())).code).toBe('cooldown');
    expect(refusals.map((r) => r.surface)).toEqual(['form', 'prompt', 'pick']);

    clock.advance(4000);
    expect((await rejection(b.form({ title: 'Again', fields: FIELDS }))).retryAfterMs).toBe(1000);
    clock.advance(1000);
    err = await refusedAfterDismissal();
    expect(err.retryAfterMs).toBe(15_000);
    clock.advance(15_000);
    err = await refusedAfterDismissal();
    expect(err.retryAfterMs).toBe(30_000);
    clock.advance(30_000);
    // The last step repeats.
    err = await refusedAfterDismissal();
    expect(err.retryAfterMs).toBe(30_000);

    // An answered surface starts the count over.
    clock.advance(30_000);
    const queued = nextInput();
    const answered = b.form({ title: 'Answer me', fields: FIELDS });
    e.mod.resolveForm((await queued).id, { n: 3 });
    expect(await answered).toEqual({ n: 3 });
    // What follows an answer within a few seconds counts as asked for.
    clock.advance(6000);
    err = await refusedAfterDismissal();
    expect(err.retryAfterMs).toBe(5000);

    // So does a minute without a dismissal.
    clock.advance(60_000);
    err = await refusedAfterDismissal();
    expect(err.retryAfterMs).toBe(5000);
  });

  it('lets a surface through that follows the player running one of the bot commands or actions', async () => {
    const clock = manualClock();
    await e.restart({ clock, surfaceCooldownsMs: undefined as never });
    const b = await attached();
    await dismissForm(b);
    expect((await rejection(b.form({ title: 'Unasked', fields: FIELDS }))).code).toBe('cooldown');

    b.commands.register('ask', { run: async () => `got ${(await b.form({ title: 'Asked', fields: FIELDS })).n}` });
    await until(() => e.mod.commands().length === 1);
    const queued = nextInput();
    const result = e.mod.invokeCommand(`${ALICE_PREFIX}/~cmd/ask`);
    e.mod.resolveForm((await queued).id, { n: 7 });
    expect(await result).toMatchObject({ ok: true, message: 'got 7' });

    // The window is a few seconds; after it the cooldown of a new dismissal applies again.
    await dismissForm(b);
    clock.advance(5001);
    await dismissForm(b);
    clock.advance(6000);
    expect((await rejection(b.form({ title: 'Unasked', fields: FIELDS }))).code).toBe('cooldown');
  });

  it('refuses the 17th waiting request of a session with quota_exceeded at once', async () => {
    const b = await attached();
    const waiting = Array.from({ length: 16 }, (_, i) => b.prompt.text(`q${i}`, { timeoutMs: 60_000 }));
    await until(() => e.mod.inputQueue().length === 16);
    const err = await rejection(b.prompt.text('one too many'));
    expect(err).toMatchObject({ code: 'quota_exceeded', retryable: true, retryAfterMs: undefined });
    for (const r of e.mod.inputQueue()) e.mod.resolvePrompt(r.id, 'ok');
    expect(await Promise.all(waiting)).toEqual(Array.from({ length: 16 }, () => 'ok'));
  });

  it('waits out a cooldown and opens again when the call says wait', async () => {
    // The mock reads the clock through the global, so it follows the fake time too.
    await e.restart({ heartbeatMs: 3_600_000, surfaceCooldownsMs: undefined as never, clock: { now: () => Date.now() } });
    const b = await attached();
    const refusals: SurfaceRefusal[] = [];
    b.on('refused', (r) => refusals.push(r));
    await dismissForm(b);

    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
    const start = Date.now();
    let opened: MockInputRequest | undefined;
    e.mod.on('inputRequest', (r) => (opened = r));
    let done: unknown;
    b.form({ title: 'Patient', fields: FIELDS, timeoutMs: 60_000, refused: 'wait' }).then(
      (v) => (done = v),
      (x: unknown) => (done = x),
    );
    await pump(4000);
    expect(opened).toBeUndefined();
    expect(refusals).toHaveLength(1);
    await pump(1500);
    expect(opened).toMatchObject({ type: 'form' });
    // The deadline keeps counting from the first try.
    const left = (opened!.request as { timeoutMs: number }).timeoutMs;
    expect(left).toBeLessThanOrEqual(55_100);
    expect(left).toBeGreaterThan(50_000);
    e.mod.resolveForm(opened!.id, { n: 1 });
    await pump(200);
    expect(done).toEqual({ n: 1 });
    expect(Date.now() - start).toBeGreaterThanOrEqual(5000);
  });

  it('asks the bridge-wide handler, lets a call override it, and gives up after 5 retries or when the wait does not fit', async () => {
    await e.restart({ heartbeatMs: 3_600_000 });
    const seen: SurfaceRefusal[] = [];
    const h = hub({
      surfaces: {
        onRefused: (r) => {
          seen.push(r);
          return 'wait';
        },
      },
    });
    const b = await attached('Alice', h);
    const events: SurfaceRefusal[] = [];
    b.on('refused', (r) => events.push(r));
    const waiting = Array.from({ length: 16 }, (_, i) => b.prompt.text(`q${i}`, { timeoutMs: 600_000 }));
    for (const p of waiting) p.catch(() => undefined);
    await until(() => e.mod.inputQueue().length === 16);

    // A call's own `refused` wins over the handler.
    expect((await rejection(b.prompt.confirm('now or never', { refused: 'reject' }))).code).toBe('quota_exceeded');
    expect(seen).toHaveLength(0);
    expect(events).toHaveLength(1);
    // A wait that would pass the call's deadline is not started.
    expect((await rejection(b.prompt.confirm('short', { timeoutMs: 500 }))).code).toBe('quota_exceeded');
    expect(seen).toHaveLength(1);
    expect((await rejection(b.pick.block({ refused: 'later' as never }))).message).toMatch(/refused must be/);

    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
    seen.length = 0;
    events.length = 0;
    let failed: BridgeError | undefined;
    b.prompt.confirm('stubborn', { timeoutMs: 60_000 }).catch((x: BridgeError) => (failed = x));
    await pump(8000, () => failed !== undefined);
    vi.useRealTimers();
    expect(failed).toMatchObject({ code: 'quota_exceeded' });
    // Refused six times: the first try and five retries, the handler asked before each retry.
    expect(events.map((r) => r.retries)).toEqual([0, 1, 2, 3, 4, 5]);
    expect(seen.map((r) => r.retries)).toEqual([0, 1, 2, 3, 4]);
    expect(seen[0]).toMatchObject({ surface: 'prompt', code: 'quota_exceeded', retryAfterMs: undefined });
    expect(seen[0]!.player).toBeUndefined();
    for (const r of e.mod.inputQueue()) e.mod.cancel(r.id);
  });

  it('ends the wait when the signal aborts, and reports a handler that throws', async () => {
    await e.restart({ surfaceCooldownsMs: [60_000] });
    const b = await attached();
    const errors: Error[] = [];
    b.on('error', (x) => errors.push(x));
    await dismissForm(b);
    const ac = new AbortController();
    const patient = b.form({ title: 'Patient', fields: FIELDS, timeoutMs: 600_000, signal: ac.signal, refused: 'wait' });
    await sleep(50);
    ac.abort();
    expect((await rejection(patient)).code).toBe('cancelled');
    const err = await rejection(
      b.form({
        title: 'Broken handler',
        fields: FIELDS,
        refused: () => {
          throw new Error('handler broke');
        },
      }),
    );
    expect(err.code).toBe('cooldown');
    expect(errors.map((x) => x.message)).toEqual(['handler broke']);
  });

  it('reports a refused view or screen open through the refused event and closes the handle', async () => {
    await e.restart({ surfaceCooldownsMs: [60_000] });
    const b = await attached();
    const refusals: SurfaceRefusal[] = [];
    b.on('refused', (r) => refusals.push(r));
    const closes: string[] = [];
    const view = b.containerView('bag', { title: 'Bag', on: { close: (_v, reason) => void closes.push(reason) } });
    view.open();
    await until(() => e.mod.shownView !== undefined);
    e.mod.closeView();
    await until(() => closes.length === 1);
    view.open();
    await until(() => refusals.length === 1);
    expect(refusals[0]).toMatchObject({ surface: 'view', code: 'cooldown', retries: 0 });
    expect(refusals[0]!.retryAfterMs).toBeGreaterThan(59_000);
    await until(() => closes.length === 2);
    expect(closes).toEqual(['player', 'refused']);
    expect(view.isOpen).toBe(false);
    expect(e.mod.shownView).toBeUndefined();

    const screen = b.screen('panel', { title: 'Panel', tabs: [] });
    screen.open();
    await until(() => refusals.length === 2);
    expect(refusals[1]).toMatchObject({ surface: 'screen', code: 'cooldown' });
    await until(() => !screen.isOpen);
  });
  /** Dismisses a view so the cooldown starts, then opens `a` and `b` in one tick; returns the order they closed in. */
  async function refusedPair(): Promise<{ order: string[]; refusals: SurfaceRefusal[] }> {
    const b = await attached();
    const refusals: SurfaceRefusal[] = [];
    b.on('refused', (r) => refusals.push(r));
    const order: string[] = [];
    const view = (id: string) => b.containerView(id, { title: id, on: { close: (_v, reason) => void (reason === 'refused' && order.push(id)) } });
    const first = view('first');
    first.open();
    await until(() => e.mod.shownView !== undefined);
    e.mod.closeView();
    await until(() => !first.isOpen);
    const a = view('a');
    const c = view('b');
    await until(() => e.mod.views().length === 3);
    a.open();
    c.open();
    await until(() => order.length === 2);
    expect(a.isOpen || c.isOpen).toBe(false);
    return { order, refusals };
  }

  it('settles the view the refusal names by its key', async () => {
    await e.restart({ surfaceCooldownsMs: [60_000] });
    const { order, refusals } = await refusedPair();
    expect(order).toEqual(['a', 'b']);
    expect(refusals.map((r) => (r.error.details as { key: string }).key)).toEqual([`${ALICE_PREFIX}/~view/a`, `${ALICE_PREFIX}/~view/b`]);
  });

  it('closes the newest open of that kind when the refusal carries no key', async () => {
    await e.restart({ surfaceCooldownsMs: [60_000], surfaceRefusalKey: false });
    const { order, refusals } = await refusedPair();
    expect(order).toEqual(['b', 'a']);
    expect(refusals.map((r) => (r.error.details as { key?: string }).key)).toEqual([undefined, undefined]);
  });

  it('leaves other handles alone when the named screen is no longer waiting', async () => {
    await e.restart({ surfaceCooldownsMs: [60_000] });
    const b = await attached();
    const closes: string[] = [];
    const kept = b.screen('kept', { title: 'Kept', tabs: [], onClose: (_s, reason) => void closes.push(`kept:${reason}`) });
    kept.open();
    const open = (id: string): boolean => e.mod.screens().some((x) => x.key === `${ALICE_PREFIX}/~screen/${id}` && x.open);
    await until(() => open('kept'));
    const gone = b.screen('gone', { title: 'Gone', tabs: [], onClose: (_s, reason) => void closes.push(`gone:${reason}`) });
    gone.open();
    await until(() => open('gone'));
    e.mod.closeScreen(`${ALICE_PREFIX}/~screen/gone`);
    await until(() => closes.includes('gone:player'));
    const refused = new Promise<SurfaceRefusal>((resolve) => b.on('refused', resolve));
    gone.open();
    gone.close();
    expect((await refused).surface).toBe('screen');
    await sleep(30);
    expect(closes.filter((c) => c.startsWith('kept'))).toEqual([]);
    expect(kept.isOpen).toBe(true);
  });
});

describe('calls that must not hang', () => {
  it('rejects a request that could not be sent because the session never opened', async () => {
    const h = hub({ endpointFile: `${e.dir}/nowhere/endpoint.json` });
    const b = load(h.plugin(), new FakeBot('Alice'));
    const started = Date.now();
    const [form, shared] = await Promise.all([
      rejection(b.form({ title: 'Nobody home', fields: FIELDS, timeoutMs: 200 })),
      rejection(b.shared.create('k', 'core:box', { min: { x: 0, y: 0, z: 0 }, size: { x: 1, y: 1, z: 1 } }, { timeoutMs: 200 })),
    ]);
    expect(form.code).toBe('timeout');
    expect(shared).toMatchObject({ code: 'disconnected', retryable: true });
    expect(Date.now() - started).toBeLessThan(3000);
  });

  it('rejects a waiting form with disconnected when the game restarts', async () => {
    const b = await attached();
    const queued = nextInput();
    const form = b.form({ title: 'Lost', fields: FIELDS });
    await queued;
    await e.restart();
    expect((await rejection(form)).code).toBe('disconnected');
  });
});

describe('frame limit and reconnect backoff', () => {
  it('refuses an object larger than a frame when it is declared, and keeps the connection', async () => {
    await e.restart({ maxFrameBytes: 8192 });
    const b = await attached();
    expect(b.limits.maxFrameBytes).toBe(8192);
    const closed: number[] = [];
    e.mod.on('connectionClosed', (c) => closed.push(c.connectionId));
    const layer = b.layer('big');
    const err = thrown(() => layer.label('huge', [0, 64, 0], 'x'.repeat(9000)));
    expect(err.code).toBe('quota_exceeded');
    expect(err.details).toMatchObject({ limit: 8192 - 512 });
    expect(layer.has('huge')).toBe(false);
    layer.label('fits', [0, 64, 0], 'x'.repeat(4000));
    await until(() => e.mod.object(`${ALICE_PREFIX}/big/fits`));
    expect(closed).toEqual([]);
  });

  it('checks maxObjects and maxInteractive at the call', async () => {
    await e.restart({ quotas: { objects: 5, interactive: 1 } });
    const h = hub();
    const b = await attached('Alice', h);
    // The limit is per device: another session of the hub uses it up too.
    h.controller({ name: 'dash' }).layer('other');
    const layer = b.layer('few');
    layer.text('a', [0, 64, 0], 'a', { interactive: { tooltip: 'click' } });
    expect(thrown(() => layer.text('b', [0, 64, 0], 'b', { interactive: { tooltip: 'click' } })).message).toMatch(/1 interactive objects/);
    // Replacing the interactive object does not count it twice.
    layer.text('a', [0, 64, 0], 'a2', { interactive: { tooltip: 'click' } });
    layer.text('b', [0, 64, 0], 'b');
    layer.text('c', [0, 64, 0], 'c');
    expect(thrown(() => layer.text('d', [0, 64, 0], 'd'))).toMatchObject({ code: 'quota_exceeded', details: { limit: 5 } });
    layer.remove('c');
    layer.text('d', [0, 64, 0], 'd');
    await until(() => e.mod.object(`${ALICE_PREFIX}/few/d`));
  });

  it('backs off when the mod drops the connection right after every welcome', async () => {
    let welcomes = 0;
    e.mod.on('authenticated', ({ connectionId }) => {
      welcomes++;
      setTimeout(() => e.mod.disconnect(connectionId), 5);
    });
    const h = hub({ reconnect: { initialDelayMs: 20, maxDelayMs: 5000 } });
    load(h.plugin(), new FakeBot('Alice'));
    await sleep(1500);
    // Delays double from 20 ms, so about 7 attempts fit; a 20 ms loop would make dozens.
    expect(welcomes).toBeGreaterThanOrEqual(3);
    expect(welcomes).toBeLessThanOrEqual(14);
  });

  it('starts the backoff over once a connection stayed up for stableMs', async () => {
    let welcomes = 0;
    e.mod.on('authenticated', ({ connectionId }) => {
      welcomes++;
      setTimeout(() => e.mod.disconnect(connectionId), 30);
    });
    const h = hub({ reconnect: { initialDelayMs: 20, maxDelayMs: 5000, stableMs: 10 } });
    load(h.plugin(), new FakeBot('Alice'));
    await sleep(1500);
    expect(welcomes).toBeGreaterThan(14);
  });
});

describe('one-off messages while the session is not open', () => {
  const NOTE = plainMessage<number>('acme:queue-note', { from: 'node' });
  const FAST = plainMessage<number>('acme:queue-fast', { from: 'node', delivery: 'unreliable' });
  const notes = defineExtension({
    id: 'acme:queue',
    version: '1.0.0',
    messages: [NOTE, FAST],
    setup: (ctx) => ({ note: (n: number) => ctx.message(NOTE).send(n), fast: (n: number) => ctx.message(FAST).send(n) }),
  });
  const number = (bytes: Uint8Array): unknown => new WireReader(bytes).any();

  it('keeps the newest 256 reliable messages and drops notifications and chat lines', async () => {
    // The link is up but the session cannot open yet.
    let refuse = true;
    await e.restart({ refuseOpen: () => (refuse ? { code: 'rate_limited', retryable: true } : undefined) });
    const h = hub({ extensions: [notes] });
    const b = load(h.plugin(), new FakeBot('Alice'));
    await until(() => h.connected && b.has('acme:queue'));
    await sleep(50);
    expect(b.state.connected).toBe(false);
    for (let i = 0; i < 300; i++) b.get(notes)!.note(i);
    b.notify('stale');
    b.chat.print('stale');
    b.notify('stale with a button', { buttons: [{ label: 'Go', run: () => undefined }] });
    refuse = false;
    await until(() => e.mod.extensionMessages(ALICE).length === 256, 10_000);
    await sleep(100);
    const got = e.mod.extensionMessages(ALICE).map((m) => number(m.payload));
    expect(got).toHaveLength(256);
    expect(got[0]).toBe(44);
    expect(got[255]).toBe(299);
    expect(e.mod.notifies).toHaveLength(0);
    expect(e.mod.printed()).toHaveLength(0);
    expect(f.logger.warnings.filter((w) => w.includes('dropping the oldest'))).toHaveLength(1);
    e.mod.setPlayerContext(OVERWORLD);
    await until(() => b.state.attached);
    b.notify('fresh');
    await until(() => e.mod.notifies.length === 1);
  }, 15_000);

  it('sends unreliable extension messages outside the sequenced stream', async () => {
    const h = hub({ extensions: [notes] });
    const b = await attached('Alice', h);
    const seqs: (number | undefined)[] = [];
    e.mod.on('message', (m) => {
      if (m.type === 'acme:queue-fast' || m.type === 'acme:queue-note') seqs.push(m.header.seq);
    });
    b.get(notes)!.note(1);
    await until(() => seqs.length === 1);
    b.get(notes)!.fast(2);
    await until(() => seqs.length === 2);
    expect(seqs[0]).toBeTypeOf('number');
    expect(seqs[1]).toBeUndefined();
  });
});

describe('extensions loaded after connecting', () => {
  it('loads a first-party extension with wire names without reconnecting', async () => {
    const h = hub();
    const drops: string[] = [];
    h.on('disconnected', (d) => drops.push(d.reason));
    const b = await attached('Alice', h);
    const session = b.session.id;
    await b.use(tasks());
    const api = b.get(tasks);
    expectTypeOf(api).toEqualTypeOf<TasksApi | undefined>();
    api!.task('Dig');
    await until(() => e.mod.objects(ALICE).some((o) => o.kind === 'bridge:task'));
    expect(drops).toEqual([]);
    expect(b.session.id).toBe(session);
    expect(f.logger.warnings.filter((w) => w.includes('does not support kind'))).toEqual([]);
  });

  it('reconnects once for a third-party kind and never warns that the mod lacks it', async () => {
    const RADAR = plainKind<{ range: number; tags: string[] }>('acme:radar');
    const radar = defineExtension({
      id: 'acme:radar',
      version: '1.0.0',
      kinds: [RADAR],
      setup: (ctx) => ({ show: (range: number) => ctx.objects.put('dish', RADAR.name, { range, tags: ['a'] } as never) }),
    });
    const h = hub();
    const drops: string[] = [];
    h.on('disconnected', (d) => drops.push(d.reason));
    const b = await attached('Alice', h);
    await b.use(radar);
    b.get(radar)!.show(12);
    const o = await until(() => e.mod.object(`${ALICE}/acme:radar/dish`));
    expect(o.kind).toBe('acme:radar');
    expect(new WireReader(o.raw).any()).toEqual({ range: 12, tags: ['a'] });
    expect(drops).toEqual(['renegotiating']);
    expect(f.logger.warnings.filter((w) => w.includes('does not support kind'))).toEqual([]);
  });

  it('builds plain kinds and lets third parties write their own codec', () => {
    expect(plainKind('acme:radar')).toMatchObject({ id: 0, name: 'acme:radar' });
    expect(() => plainKind('radar')).toThrow(/namespace:name/);
    const POINT: KindType<{ x: number }> = {
      id: 0,
      name: 'acme:point',
      codec: { write: (w, v) => w.uint(v.x), read: (r) => ({ x: r.uint() }) },
    };
    const w = new WireWriter(16);
    POINT.codec.write(w, { x: 7 });
    expect(POINT.codec.read(new WireReader(w.toBytes()))).toEqual({ x: 7 });
  });
});

describe('errors nobody handles', () => {
  it('logs an error thrown by a handler while nothing listens for error, and emits it otherwise', async () => {
    const logger = recorder();
    const b = await attached('Alice', hub({ logger }));
    b.keybinds.register('boom', {
      label: 'Boom',
      keys: ['g'],
      onPress: () => {
        throw new Error('keybind broke');
      },
    });
    await until(() => e.mod.keybinds().length === 1);
    e.mod.keybindEvent(`${ALICE_PREFIX}/~key/boom`, true);
    await until(() => logger.errors.length === 1);
    expect(logger.errors[0]).toContain('keybind broke');

    const seen: Error[] = [];
    b.on('error', (x) => seen.push(x));
    e.mod.keybindEvent(`${ALICE_PREFIX}/~key/boom`, true);
    await until(() => seen.length === 1);
    expect(seen[0]).toBeInstanceOf(BridgeError);
    expect(logger.errors).toHaveLength(1);
  });

  it('logs a failed extension setup while nothing listens', async () => {
    const logger = recorder();
    const broken = defineExtension({
      id: 'acme:broken',
      version: '1.0.0',
      setup: () => {
        throw new Error('setup broke');
      },
    });
    const b = load(hub({ logger, extensions: [broken] }).plugin(), new FakeBot('Alice'));
    expect((await rejection(b.ready)).message).toBe('setup broke');
    expect(logger.errors.some((m) => m.includes('setup broke'))).toBe(true);
  });
});

describe('a refused session.open', () => {
  it('rejects ready and emits error for a refusal that is not retryable, then tries again on the next connection', async () => {
    let refuse = true;
    await e.restart({ refuseOpen: () => (refuse ? { code: 'identity_taken', message: 'this bot belongs to another device' } : undefined) });
    const conns: number[] = [];
    e.mod.on('connection', (c) => conns.push(c.connectionId));
    const b = load(hub().plugin(), new FakeBot('Alice'));
    const errors: Error[] = [];
    b.on('error', (x) => errors.push(x));
    const err = await rejection(b.ready);
    expect(err).toMatchObject({ code: 'identity_taken', message: 'this bot belongs to another device' });
    expect(errors).toEqual([err]);
    await sleep(100);
    expect(e.mod.sessions()).toEqual([]);
    expect(b.state.connected).toBe(false);
    // The refusal stands for this connection; the next one asks again.
    refuse = false;
    await sleep(600);
    expect(b.state.connected).toBe(false);
    e.mod.disconnect(conns.at(-1)!);
    await until(() => b.state.connected);
    expect(e.mod.sessions().map((s) => s.owner)).toEqual([ALICE]);
  });

  it('retries a retryable refusal with backoff until the session opens', async () => {
    let calls = 0;
    await e.restart({ refuseOpen: () => (++calls <= 2 ? { code: 'rate_limited', retryable: true } : undefined) });
    const b = load(hub().plugin(), new FakeBot('Alice'));
    const errors: Error[] = [];
    b.on('error', (x) => errors.push(x));
    await b.ready;
    expect(calls).toBe(3);
    expect(errors).toEqual([]);
    expect(b.state.connected).toBe(true);
  }, 10_000);
});

describe('mutual proof', () => {
  it('does not treat a peer with a wrong proof as the mod', async () => {
    await e.restart({ wrongProof: true });
    const h = hub();
    const errors: BridgeError[] = [];
    const drops: { reason: string; retry: boolean }[] = [];
    h.on('error', (x) => errors.push(x as BridgeError));
    h.on('disconnected', (d) => drops.push(d));
    const connected = vi.fn();
    h.on('connected', connected);
    const b = load(h.plugin(), new FakeBot('Alice'));
    await until(() => drops.length === 1);
    expect(errors).toHaveLength(1);
    expect(errors[0]).toMatchObject({ code: 'unauthorized' });
    expect(errors[0]!.message).toMatch(/did not prove/);
    expect(drops[0]).toEqual({ reason: 'unauthorized', retry: false });
    expect(connected).not.toHaveBeenCalled();
    expect(h.connected).toBe(false);
    expect(b.state.connected).toBe(false);
    expect(e.mod.sessions()).toEqual([]);
  });
});

describe('hub errors nobody handles', () => {
  it('logs a connection error while nothing listens on the hub or the link', async () => {
    await e.restart({ wrongProof: true });
    const logger = recorder();
    const h = hub({ logger });
    load(h.plugin(), new FakeBot('Alice'));
    await until(() => logger.errors.length === 1);
    expect(logger.errors[0]).toMatch(/did not prove/);

    const quiet = recorder();
    const h2 = hub({ logger: quiet });
    const seen: Error[] = [];
    h2.on('error', (x) => seen.push(x));
    load(h2.plugin(), new FakeBot('Bob'));
    await until(() => seen.length === 1);
    expect(quiet.errors).toEqual([]);
  });
});

describe('the player uuid', () => {
  it('is on the player, in the player event and on the invocation context', async () => {
    const UUID = '069a79f4-44e9-4726-a5be-fca90e38aaf5';
    const b = await attached();
    expect(b.players[0]!.uuid).toBeUndefined();
    e.mod.setPlayerName('Notch');
    e.mod.setPlayerUuid(UUID);
    await until(() => b.players[0]!.uuid === UUID);
    expect(b.players[0]).toMatchObject({ name: 'Notch', uuid: UUID });

    const seen: (BridgePlayer | undefined)[] = [];
    b.commands.register('who', { run: (_a, ctx) => void seen.push(ctx.player) });
    b.on('player', (_change, p) => seen.push(p));
    await until(() => e.mod.commands().length === 1);
    await e.mod.invokeCommand(`${ALICE_PREFIX}/~cmd/who`);
    e.mod.setPlayerState({ away: true });
    await until(() => seen.length === 2);
    expect(seen.map((p) => p?.uuid)).toEqual([UUID, UUID]);
  });
});
