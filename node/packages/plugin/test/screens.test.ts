import { describe, expect, it } from 'vitest';
import { BridgeError, type Screen } from '../src/index.ts';
import { ALICE_PREFIX, fixture, until, type Env } from './helpers.ts';

const KEY = `${ALICE_PREFIX}/~screen/main`;

let e: Env;
const { attached } = fixture((f) => (e = f.e));

function declared(key = KEY) {
  return e.mod.screens().find((s) => s.key === key);
}

describe('screens', () => {
  it('declares tabs of rows at ~screen/<id> with positional ids, global scope and links', async () => {
    const b = await attached();
    const jobs = b.screen('jobs', { title: 'Jobs', rows: [{ text: 'none yet', color: '#ff0000' }] });
    b.screen('main', {
      title: 'Alice',
      home: 'tab',
      order: 3,
      tabs: [{
        id: 'work',
        label: 'Work',
        rows: [
          { heading: 'Jobs' },
          [{ button: 'Mine', icon: 'iron_pickaxe', tooltip: 'Strip-mine', width: 80 }, { button: 'Jobs', open: jobs }, { spacer: true }],
          { toggle: 'Auto-deposit', id: 'deposit', value: true },
          { cycle: 'Speed', options: ['slow', 'fast'], value: 'fast', enabled: false },
          { item: 'wheat', text: '32 wheat' },
        ],
      }],
    });
    const d = await until(() => declared()?.data.tabs[0]?.rows[1]?.items[1]?.open !== undefined && declared(), 5000, 'screen with link');
    expect(d.scope).toBe('global');
    expect(d.data).toMatchObject({ title: 'Alice', home: 'tab', order: 3 });
    const [heading, buttons, toggle, cycle, item] = d.data.tabs[0]!.rows.map((r) => r.items);
    expect(heading).toMatchObject([{ kind: 'heading', text: 'Jobs' }]);
    expect(buttons).toMatchObject([
      { kind: 'button', id: 'work.1.0', text: 'Mine', icon: 'minecraft:iron_pickaxe', tooltip: 'Strip-mine', width: 80 },
      { kind: 'button', id: 'work.1.1', text: 'Jobs', open: `${ALICE_PREFIX}/~screen/jobs` },
      { kind: 'spacer' },
    ]);
    expect(buttons![0]!.enabled).toBe(true);
    expect(toggle).toMatchObject([{ kind: 'toggle', id: 'deposit', text: 'Auto-deposit', value: true }]);
    expect(cycle).toMatchObject([{ kind: 'cycle', id: 'work.3.0', text: 'Speed', options: ['slow', 'fast'], value: 1, enabled: false }]);
    expect(item).toMatchObject([{ kind: 'item', icon: 'minecraft:wheat', text: '32 wheat' }]);
    expect(declared(`${ALICE_PREFIX}/~screen/jobs`)?.data.tabs).toMatchObject([{ id: 'main', label: 'Jobs', rows: [{ items: [{ kind: 'text', text: 'none yet', color: 0xffff0000 }] }] }]);
  });

  it('rejects bad declarations without changing the screen', async () => {
    const b = await attached();
    const s = b.screen('main', { title: 'A', rows: [{ heading: 'ok' }] });
    const bad = (rows: unknown): BridgeError => {
      try {
        s.set({ title: 'B', rows: rows as never });
      } catch (err) {
        return err as BridgeError;
      }
      throw new Error('expected a throw');
    };
    expect(bad([{ nothing: 1 }]).message).toMatch(/needs one of/);
    expect(bad([[{ button: 'a', id: 'x' }, { button: 'b', id: 'x' }]]).message).toMatch(/duplicate item id x/);
    expect(bad([{ toggle: 'a', value: 'yes' }]).message).toMatch(/must be a boolean/);
    expect(bad([{ cycle: 'a', options: ['x'], value: 'y' }]).message).toMatch(/one of its options/);
    expect(bad([{ button: 'a', colour: 1 }]).message).toMatch(/colour/);
    expect(() => b.screen('x', { title: 'X', rows: [], tabs: [] })).toThrow(/tabs or rows, not both/);
    expect(() => b.screen('a/b', { title: 'X', rows: [] })).toThrow(/screen id/);
    await until(() => declared()?.data.title === 'A', 5000, 'unchanged');
  });

  it('runs handlers for clicks, toggles and cycles and answers like actions', async () => {
    const b = await attached();
    let deposit = false;
    const seen: string[] = [];
    b.screen('main', {
      title: 'Alice',
      rows: () => [
        [{ button: 'Mine', id: 'mine', onClick: (ctx) => { seen.push(`click ${ctx.item}`); return 'mining'; } },
          { button: 'Fail', id: 'fail', onClick: () => { throw new Error('no pickaxe'); } }],
        { toggle: 'Deposit', id: 'deposit', value: deposit, onChange: (v) => { deposit = v; } },
        { cycle: 'Speed', id: 'speed', options: ['slow', 'fast'], value: 0, onChange: async (v) => { seen.push(`speed ${v}`); } },
      ],
    });
    await until(() => declared(), 5000, 'declared');
    expect(await e.mod.screenEvent(KEY, 'mine')).toEqual({ ok: true, message: 'mining' });
    expect(await e.mod.screenEvent(KEY, 'fail')).toEqual({ ok: false, message: 'no pickaxe' });
    expect(await e.mod.screenEvent(KEY, 'deposit', true)).toEqual({ ok: true });
    expect(deposit).toBe(true);
    await until(() => declared()?.data.tabs[0]?.rows[1]?.items[0]?.value === true, 5000, 'toggle rebuilt from state');
    expect(await e.mod.screenEvent(KEY, 'speed', 1)).toEqual({ ok: true });
    expect(seen).toEqual(['click mine', 'speed fast']);
    await until(() => declared()?.data.tabs[0]?.rows[2]?.items[0]?.value === 1, 5000, 'cycle keeps the accepted value');
  });

  it('keeps an accepted value on a static screen and drops a refused one', async () => {
    const b = await attached();
    b.screen('main', {
      title: 'Alice',
      rows: [{ toggle: 'Deposit', id: 'deposit', value: false, onChange: (v) => { if (!v) throw new Error('stays on'); } }],
    });
    await until(() => declared(), 5000, 'declared');
    expect(await e.mod.screenEvent(KEY, 'deposit', true)).toEqual({ ok: true });
    await until(() => declared()?.data.tabs[0]?.rows[0]?.items[0]?.value === true, 5000, 'accepted value kept');
    expect(await e.mod.screenEvent(KEY, 'deposit', false)).toEqual({ ok: false, message: 'stays on' });
    expect(declared()?.data.tabs[0]?.rows[0]?.items[0]?.value).toBe(true);
  });

  it('shares open and close with the player and builds function tabs on open', async () => {
    const b = await attached();
    let builds = 0;
    const events: string[] = [];
    const s: Screen = b.screen('main', {
      title: 'Alice',
      rows: () => [{ text: `build ${++builds}` }],
      onOpen: () => events.push('open'),
      onClose: (_s, reason) => events.push(`close ${reason}`),
    });
    await until(() => declared(), 5000, 'declared');
    e.mod.openScreen(KEY);
    await until(() => s.isOpen, 5000, 'opened by the player');
    await until(() => declared()?.data.tabs[0]?.rows[0]?.items[0]?.text === 'build 2', 5000, 'rebuilt on open');
    e.mod.closeScreen(KEY);
    await until(() => !s.isOpen, 5000, 'closed by the player');
    s.open();
    await until(() => e.mod.screens().find((x) => x.key === KEY)?.open === true, 5000, 'opened by the bot');
    s.close();
    await until(() => e.mod.screens().find((x) => x.key === KEY)?.open === false, 5000, 'closed by the bot');
    expect(events).toEqual(['open', 'close player', 'open', 'close bot']);
    s.remove();
    await until(() => declared() === undefined, 5000, 'removed');
  });

  it('follows the handle contract: alive, update and lifecycle events, for screens and menus alike', async () => {
    const b = await attached();
    const events: string[] = [];
    const s = b.screen('main', { title: 'A', rows: [{ text: 'one' }] });
    s.on('open', () => events.push('screen open'));
    s.on('close', (reason) => events.push(`screen close ${reason}`));
    s.on('remove', () => events.push('screen remove'));
    s.update({ title: 'B' });
    await until(() => declared()?.data.title === 'B', 5000, 'update merged the title');
    expect(declared()?.data.tabs[0]?.rows[0]?.items[0]).toMatchObject({ text: 'one' });
    e.mod.openScreen(KEY);
    await until(() => events.includes('screen open'));
    e.mod.closeScreen(KEY);
    await until(() => events.includes('screen close player'));
    expect(s.alive).toBe(true);
    s.remove();
    expect(s.alive).toBe(false);
    expect(events).toEqual(['screen open', 'screen close player', 'screen remove']);

    const m = b.menu('jobs', { title: 'Jobs', rows: 1 });
    m.on('open', () => events.push('menu open'));
    m.on('close', (reason) => events.push(`menu close ${reason}`));
    m.on('remove', () => events.push('menu remove'));
    m.update({ title: 'All jobs' });
    const view = `${ALICE_PREFIX}/~view/jobs`;
    await until(() => e.mod.views().find((v) => v.key === view)?.data.title === 'All jobs', 5000, 'menu title');
    m.open();
    await until(() => events.includes('menu open'));
    m.close();
    await until(() => events.includes('menu close bot'));
    m.remove();
    expect(m.alive).toBe(false);
    expect(events.slice(3)).toEqual(['menu open', 'menu close bot', 'menu remove']);
  });
});
