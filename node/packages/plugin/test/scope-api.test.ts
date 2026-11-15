import { describe, expect, it } from 'vitest';
import { BridgeError, defineExtension, vitalsHud, type BotBridge, type ExtensionContext } from '../src/index.ts';
import { ALICE, ALICE_PREFIX, FakeBot, fixture, load, OVERWORLD, sleep, until, type Env } from './helpers.ts';

let e: Env;
const { hub, attached } = fixture((f) => (e = f.e));

/** An extension that hands its context out. */
async function withContext(b: BotBridge, id = 'acme:probe'): Promise<ExtensionContext> {
  let ctx!: ExtensionContext;
  await b.use(defineExtension({ id, version: '1.0.0', setup: (c) => void (ctx = c) })());
  return ctx;
}

describe('focus', () => {
  it('follows interest.focused and asks for focus with focus.request', async () => {
    const b = await attached();
    const ctx = await withContext(b);
    await until(() => b.focused, 5000, 'auto focus');
    expect(ctx.focused).toBe(true);
    const seen: boolean[] = [];
    const ctxSeen: boolean[] = [];
    b.on('focus', (f) => seen.push(f));
    ctx.onFocus((f) => ctxSeen.push(f));
    const offSecond = b.on('focus', (f) => seen.push(f));

    e.mod.setFocus(null);
    await until(() => !b.focused);
    offSecond();
    e.mod.setFocusPolicy('refuse');
    const refused = e.mod.waitFor('focusRequested');
    b.requestFocus('need help');
    expect((await refused)[0]).toMatchObject({ reason: 'need help', granted: false });
    await sleep(30);
    expect(b.focused).toBe(false);
    e.mod.setFocusPolicy('grant');
    ctx.requestFocus();
    await until(() => ctx.focused);
    expect(seen).toEqual([false, false, true]);
    expect(ctxSeen).toEqual([false, true]);
    expect(() => b.requestFocus(3 as unknown as string)).toThrow(BridgeError);

    await b.unload('acme:probe');
    e.mod.setFocus(null);
    await until(() => !b.focused);
    expect(ctxSeen).toEqual([false, true]);
  });

  it('sends HUD detail only while focused, unless whenUnfocused is full', async () => {
    const h = hub();
    const bot = Object.assign(new FakeBot('Alice'), { health: 15, food: 18, foodSaturation: 4 });
    const b = load(h.plugin({ extensions: [vitalsHud({ intervalMs: 0 })] }), bot);
    const other = Object.assign(new FakeBot('Bob'), { health: 10, food: 10, foodSaturation: 2 });
    const c = load(h.plugin({ extensions: [vitalsHud({ intervalMs: 0, whenUnfocused: 'full' })] }), other);
    e.mod.setPlayerContext(OVERWORLD);
    await until(() => b.state.attached && c.state.attached);
    const saturation = (owner: string): unknown => e.mod.object(`${owner}/bridge:vitals-hud/~hud/vitals/saturation`);
    const bob = 'bot:bob@play.example.net:25565';
    await until(() => saturation(ALICE) && saturation(bob));
    e.mod.setFocus('Bob');
    await until(() => !b.focused && c.focused);
    await until(() => saturation(ALICE) === undefined);
    expect(e.mod.object(`${ALICE}/bridge:vitals-hud/~hud/vitals/health`)).toBeDefined();
    e.mod.setFocus(null);
    await until(() => !c.focused);
    await sleep(30);
    expect(saturation(bob)).toBeDefined();
    e.mod.setFocus('Alice');
    await until(() => saturation(ALICE));
  });
});

describe('variables and templates', () => {
  it('keeps one core:vars object per scope, written once per tick, and renders templates', async () => {
    const b = await attached();
    const key = `${ALICE_PREFIX}/~vars`;
    b.vars.set('task', 'Mining');
    b.vars.setMany({ done: 3, total: 10, hp: 0.5, ok: true });
    b.layer('ui').label('status', [0, 65, 0], '{task} {done}/{total} at {hp:pct}');
    const panel = b.hud('main', { title: 'Main' });
    panel.text('line', '{task}: {done:1f}');
    b.bossbar('bar', { text: 'Task {task}' });
    await until(() => e.mod.vars(ALICE_PREFIX)?.['total'] === 10);
    expect(e.mod.vars(ALICE_PREFIX)).toEqual({ task: 'Mining', done: 3, total: 10, hp: 0.5, ok: true });
    expect(e.mod.rendered(`${ALICE_PREFIX}/ui/status`)).toBe('Mining 3/10 at 50%');
    expect(e.mod.rendered(`${ALICE_PREFIX}/~hud/main/line`)).toBe('Mining: 3.0');
    expect(e.mod.rendered(`${ALICE_PREFIX}/~bossbar/bar`)).toBe('Task Mining');
    expect(b.vars.get('done')).toBe(3);
    expect(b.vars.all()).toEqual({ task: 'Mining', done: 3, total: 10, hp: 0.5, ok: true });

    const writes: string[] = [];
    e.mod.on('objectChanged', (c) => writes.push(`${c.key.slice(ALICE_PREFIX.length + 1)}:${c.write}`));
    for (let i = 4; i <= 9; i++) b.vars.set('done', i);
    b.vars.set('task', 'Smelting');
    await until(() => e.mod.vars(ALICE_PREFIX)?.['task'] === 'Smelting');
    await sleep(30);
    // One write for the whole tick; the map is one field, so it travels as a full write.
    expect(writes).toEqual(['~vars:full']);
    expect(e.mod.rendered(`${ALICE_PREFIX}/ui/status`)).toBe('Smelting 9/10 at 50%');

    expect(() => b.vars.set('a:b', 1)).toThrow(/variable names/);
    expect(() => b.vars.set('n', Number.NaN)).toThrow(/finite number/);
    expect(() => b.vars.setMany({ good: 1, bad: {} as unknown as string })).toThrow(BridgeError);
    expect(b.vars.get('good')).toBeUndefined();
    expect(b.vars.delete('missing')).toBe(false);
    for (const k of Object.keys(b.vars.all())) expect(b.vars.delete(k)).toBe(true);
    await until(() => e.mod.object(key) === undefined);
  });

  it('gives each extension its own variables, removed on unload', async () => {
    const b = await attached();
    const ctx = await withContext(b);
    ctx.vars.set('x', 1);
    b.vars.set('x', 2);
    ctx.layer('l').text('t', [0, 65, 0], 'x={x}');
    const scope = `${ALICE}/acme:probe`;
    await until(() => e.mod.vars(scope)?.['x'] === 1 && e.mod.vars(ALICE_PREFIX)?.['x'] === 2);
    await until(() => e.mod.rendered(`${scope}/l/t`) === 'x=1');
    await b.unload('acme:probe');
    await until(() => e.mod.vars(scope) === undefined);
    expect(e.mod.vars(ALICE_PREFIX)).toEqual({ x: 2 });
  });
});

describe('transactions', () => {
  it('brackets the writes of a sync or async function in obj.begin and obj.commit', async () => {
    const b = await attached();
    const layer = b.layer('tx');
    await until(() => e.mod.object(`${ALICE_PREFIX}/tx`));
    const txs: { tx: number; writes: number; outcome: string }[] = [];
    e.mod.on('transaction', (t) => txs.push({ tx: t.tx, writes: t.writes, outcome: t.outcome }));

    const r = b.transaction(() => {
      layer.box('a', [0, 64, 0], 1);
      layer.box('b', [1, 64, 0], 1);
      return b.transaction(() => {
        b.vars.set('n', 1);
        return 42;
      });
    });
    expect(r).toBe(42);
    await until(() => txs.length === 1);
    expect(txs[0]).toMatchObject({ tx: 1, outcome: 'committed' });
    expect(e.mod.object(`${ALICE_PREFIX}/tx/a`) && e.mod.object(`${ALICE_PREFIX}/tx/b`) && e.mod.vars(ALICE_PREFIX)).toBeTruthy();

    const pending = b.transaction(async () => {
      layer.remove('a');
      await sleep(40);
      layer.label('c', [0, 66, 0], 'later');
      return 'done';
    });
    await sleep(20);
    expect(e.mod.object(`${ALICE_PREFIX}/tx/a`)).toBeDefined();
    expect(await pending).toBe('done');
    await until(() => txs.length === 2);
    expect(txs[1]).toMatchObject({ tx: 2, outcome: 'committed' });
    expect(e.mod.object(`${ALICE_PREFIX}/tx/a`)).toBeUndefined();
    expect(e.mod.object(`${ALICE_PREFIX}/tx/c`)).toBeDefined();
  });

  it('commits what was written before an error and rethrows; sends nothing without writes', async () => {
    const b = await attached();
    const ctx = await withContext(b);
    const txs: number[] = [];
    e.mod.on('transaction', (t) => txs.push(t.tx));
    expect(() =>
      ctx.transaction(() => {
        ctx.layer('l').box('a', [0, 64, 0], 1);
        throw new Error('half way');
      }),
    ).toThrow('half way');
    await until(() => txs.length === 1);
    expect(e.mod.object(`${ALICE}/acme:probe/l/a`)).toBeDefined();
    await expect(b.transaction(() => Promise.reject(new Error('nothing')))).rejects.toThrow('nothing');
    expect(() => b.transaction(() => undefined)).not.toThrow();
    await sleep(40);
    expect(txs).toEqual([1]);
  });
});

describe('handle contract', () => {
  it('gives boss bars, HUD panels and container views alive and removed events', async () => {
    const b = await attached();
    const removed: string[] = [];
    const bar = b.bossbar('bar', { text: 'x' });
    bar.on('removed', () => removed.push('bar'));
    expect(bar.alive).toBe(true);
    bar.remove();
    expect(bar.alive).toBe(false);
    const panel = b.hud('p', { title: 'P' });
    panel.on('removed', () => removed.push('panel'));
    expect(panel.alive).toBe(true);
    panel.destroy();
    expect(panel.alive).toBe(false);
    const view = b.containerView('v', { title: 'V' });
    view.on('removed', () => removed.push('view'));
    expect(view.alive).toBe(true);
    view.remove();
    expect(view.alive).toBe(false);
    expect(removed).toEqual(['bar', 'panel', 'view']);
    await until(() => e.mod.object(`${ALICE_PREFIX}/~hud/p`) === undefined && e.mod.object(`${ALICE_PREFIX}/~bossbar/bar`) === undefined);
  });
});
