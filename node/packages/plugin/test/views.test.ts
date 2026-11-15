import { describe, expect, it } from 'vitest';
import { BridgeError, defineExtension, type BotBridge, type ContainerEvent, type ContainerView, type ExtensionContext } from '../src/index.ts';
import { ALICE, ALICE_PREFIX, FakeBot, fixture, load, OVERWORLD, rejection, sleep, until, type Env } from './helpers.ts';

const IDENTITY = 'alice@play.example.net:25565';
const KEY = `${ALICE_PREFIX}/~view/inv`;

let e: Env;
const { hub, attached } = fixture((f) => (e = f.e));

/** Opens a view from the bot and waits until the mock shows it. */
async function shown(v: { open(): void }, key: string): Promise<void> {
  v.open();
  await until(() => e.mod.shownView === key, 5000, `view ${key} shown`);
}

describe('container views', () => {
  it('declares a view at ~view/<id> with items from strings, objects and mineflayer items', async () => {
    const b = await attached();
    const v = b.containerView('inv', {
      title: "Alice's inventory",
      layout: 'player-inventory',
      slots: [null, 'diamond', { id: 'minecraft:stick', count: 3, name: 'Wand', lore: ['Zap'], glint: true, customModel: 'acme:wand' }, undefined, { name: 'iron_ingot', count: 7, customName: 'Shiny', type: 5 }],
      filler: 'minecraft:gray_stained_glass_pane',
      closeOnClick: true,
    });
    expect(v.size).toBe(46);
    await until(() => e.mod.views().length === 1);
    const [d] = e.mod.views();
    expect(d).toMatchObject({ key: KEY, extension: 'user:default', id: 'inv', open: false, scope: 'global' });
    expect(d!.data).toMatchObject({ title: "Alice's inventory", layout: 'player_inventory', closeOnClick: true, filler: { id: 'minecraft:gray_stained_glass_pane' } });
    expect(d!.data.slots.map((s) => [s.slot, s.item])).toEqual([
      [1, expect.objectContaining({ id: 'minecraft:diamond', count: 1 })],
      [2, expect.objectContaining({ id: 'minecraft:stick', count: 3, name: 'Wand', lore: ['Zap'], glint: true, customModel: 'acme:wand' })],
      [4, expect.objectContaining({ id: 'minecraft:iron_ingot', count: 7, name: 'Shiny' })],
    ]);
    expect(v.slot(2)).toEqual({ id: 'minecraft:stick', count: 3, name: 'Wand', lore: ['Zap'], glint: true, customModel: 'acme:wand' });
    expect(v.slot(0)).toBeUndefined();
  });

  it('sends slot changes made in one tick as one field patch', async () => {
    const b = await attached();
    const v = b.containerView('inv', { title: 'Chest', layout: 'chest:1' });
    await until(() => e.mod.views().length === 1);
    const writes: string[] = [];
    e.mod.on('objectChanged', (c) => writes.push(c.write));
    v.setSlot(0, 'minecraft:dirt');
    v.setSlot(8, { id: 'minecraft:stone', count: 64 });
    v.setSlots({ 3: 'minecraft:sand', 8: null }, { merge: true });
    await until(() => e.mod.views()[0]?.data.slots.length === 2);
    expect(writes).toEqual(['patch']);
    expect(e.mod.views()[0]!.data.slots.map((s) => [s.slot, s.item.id])).toEqual([[0, 'minecraft:dirt'], [3, 'minecraft:sand']]);
    v.setSlots(new Map([[5, 'minecraft:gravel']]));
    await until(() => e.mod.views()[0]?.data.slots.length === 1);
    expect(e.mod.views()[0]!.data.slots[0]).toMatchObject({ slot: 5, item: { id: 'minecraft:gravel' } });
  });

  it('opens and closes from the bot and from the player, tracking isOpen and calling handlers', async () => {
    const b = await attached();
    const log: string[] = [];
    const v = b.containerView('inv', { title: 'Chest', on: { open: () => log.push('open'), close: (_v, reason) => log.push(`close:${reason}`) } });
    await shown(v, KEY);
    expect(v.isOpen).toBe(true);
    const [closedByBot] = await Promise.all([e.mod.waitFor('viewClosed'), Promise.resolve(v.close())]);
    expect(closedByBot[0]).toEqual({ key: KEY, by: 'bot' });
    expect(v.isOpen).toBe(false);

    e.mod.openView(KEY);
    await until(() => v.isOpen, 5000, 'opened by the player');
    e.mod.closeView();
    await until(() => !v.isOpen, 5000, 'closed by the player');
    expect(log).toEqual(['open', 'close:bot', 'open', 'close:player']);
    expect(e.mod.shownView).toBeUndefined();
  });

  it('routes gestures to click, drop and hotbarSwap and acks with the handler result', async () => {
    const b = await attached();
    const events: ContainerEvent[] = [];
    const v = b.containerView('inv', {
      title: 'Chest',
      slots: { 0: 'minecraft:diamond', 1: 'minecraft:dirt' },
      on: {
        click: (ev) => {
          events.push(ev);
          if (ev.slot === 1) return false;
          if (ev.slot === 2) throw new BridgeError('busy', 'still mining');
          return ev.shift ? 'moved' : undefined;
        },
        drop: async (ev) => {
          events.push(ev);
          await sleep(5);
          return ev.all ? 'dropped all' : 'dropped one';
        },
        hotbarSwap: (ev) => {
          events.push(ev);
          return Promise.reject(new Error('cannot swap'));
        },
      },
    });
    await shown(v, KEY);
    expect(await e.mod.clickSlot(KEY, 0, 'left')).toMatchObject({ ok: true, message: undefined });
    expect(await e.mod.clickSlot(KEY, 0, 'shift_left')).toMatchObject({ ok: true, message: 'moved' });
    expect(await e.mod.clickSlot(KEY, 1, 'right')).toMatchObject({ ok: false, message: undefined });
    expect(await e.mod.clickSlot(KEY, 2, 'middle')).toMatchObject({ ok: false, message: 'still mining' });
    expect(await e.mod.clickSlot(KEY, 0, 'drop')).toMatchObject({ ok: true, message: 'dropped one' });
    expect(await e.mod.clickSlot(KEY, 0, 'drop_all')).toMatchObject({ ok: true, message: 'dropped all' });
    expect(await e.mod.clickSlot(KEY, 1, 'number', { hotbar: 4 })).toMatchObject({ ok: false, message: 'cannot swap' });
    expect(await e.mod.clickSlot(KEY, -999, 'left')).toMatchObject({ ok: true });
    expect(events.map((x) => [x.slot, x.click, x.shift, x.all, x.hotbar])).toEqual([
      [0, 'left', false, false, undefined],
      [0, 'shift-left', true, false, undefined],
      [1, 'right', false, false, undefined],
      [2, 'middle', false, false, undefined],
      [0, 'drop', false, false, undefined],
      [0, 'drop-all', false, true, undefined],
      [1, 'number', false, false, 4],
      [-999, 'left', false, false, undefined],
    ]);
    expect(events[0]!.item).toEqual({ id: 'minecraft:diamond', count: 1 });
    expect(events[0]!.view).toBe(v);
    expect(events[7]!.item).toBeUndefined();
  });

  it('acks gestures without a handler as ok, and slot updates made by a handler go out before its ack', async () => {
    const b = await attached();
    const v: ContainerView = b.containerView('inv', {
      title: 'Chest',
      on: {
        click: (ev) => {
          ev.view.setSlot(ev.slot, 'minecraft:emerald');
        },
      },
    });
    await shown(v, KEY);
    expect(await e.mod.clickSlot(KEY, 0, 'number', { hotbar: 0 })).toMatchObject({ ok: true });
    const order: string[] = [];
    e.mod.on('message', (m) => {
      if (m.type === 'obj.upsert' || m.type === 'container.ack') order.push(m.type);
    });
    await e.mod.clickSlot(KEY, 4, 'left');
    expect(order).toEqual(['obj.upsert', 'container.ack']);
    expect(e.mod.views()[0]!.data.slots[0]).toMatchObject({ slot: 4, item: { id: 'minecraft:emerald' } });
  });

  it('closes a closeOnClick view after the click', async () => {
    const b = await attached();
    const reasons: string[] = [];
    const v = b.containerView('inv', { title: 'Pick one', closeOnClick: true, on: { click: () => 'chosen', close: (_v, r) => reasons.push(r) } });
    await shown(v, KEY);
    expect(await e.mod.clickSlot(KEY, 3, 'left')).toMatchObject({ ok: true, message: 'chosen' });
    await until(() => !v.isOpen);
    expect(reasons).toEqual(['player']);
  });

  it('is closed again by the mod when the player has not attached the bot', async () => {
    const b = await attached();
    e.mod.setAttached(IDENTITY, false);
    await until(() => !b.state.attached);
    const reasons: string[] = [];
    const v = b.containerView('inv', { title: 'Chest', on: { close: (_v, r) => reasons.push(r) } });
    v.open();
    expect(v.isOpen).toBe(true);
    await until(() => !v.isOpen, 5000, 'refused');
    // Not the player's doing: a bot mirroring its own window must not close it.
    expect(reasons).toEqual(['lost']);
    expect(e.mod.shownView).toBeUndefined();
  });

  it('reports a shown view as lost, not closed by the player, when the bot is detached', async () => {
    const b = await attached();
    const reasons: string[] = [];
    const v = b.containerView('inv', { title: 'Chest', on: { close: (_v, r) => reasons.push(r) } });
    await shown(v, KEY);
    e.mod.setAttached(IDENTITY, false);
    await until(() => !v.isOpen, 5000, 'lapsed');
    expect(reasons).toEqual(['lost']);
    expect(e.mod.shownView).toBeUndefined();
  });

  it('opening another view closes the first one', async () => {
    const b = await attached();
    const a = b.containerView('a', { title: 'A' });
    const c = b.containerView('c', { title: 'C' });
    await shown(a, `${ALICE_PREFIX}/~view/a`);
    await shown(c, `${ALICE_PREFIX}/~view/c`);
    await until(() => !a.isOpen);
    expect(c.isOpen).toBe(true);
    expect(e.mod.views().map((x) => [x.id, x.open])).toEqual([['a', false], ['c', true]]);
  });

  it('updates fields, returns the same handle for the same id, and removes the view', async () => {
    const b = await attached();
    const v = b.containerView('inv', { title: 'Old', layout: 'chest:2', slots: { 0: 'minecraft:dirt', 17: 'minecraft:stone' } });
    expect(b.containerView('inv', { title: 'Same', layout: 'chest:2' })).toBe(v);
    v.update({ title: 'New', layout: 'hopper', filler: 'minecraft:glass_pane' });
    expect(v.size).toBe(5);
    await until(() => e.mod.views()[0]?.data.title === 'New');
    expect(e.mod.views()[0]!.data).toMatchObject({ layout: 'hopper', filler: { id: 'minecraft:glass_pane' } });
    expect(e.mod.views()[0]!.data.slots.map((s) => s.slot)).toEqual([0]);
    v.update({ filler: null });
    await until(() => e.mod.views()[0]?.data.filler === undefined);

    await shown(v, KEY);
    const closed = e.mod.waitFor('viewClosed');
    v.remove();
    expect((await closed)[0]).toEqual({ key: KEY, by: 'removed' });
    expect(e.mod.views()).toEqual([]);
    expect(v.isOpen).toBe(false);
    v.setSlot(0, 'minecraft:dirt');
    v.open();
    await sleep(30);
    expect(e.mod.views()).toEqual([]);
  });

  it('rejects bad ids, layouts, slots and items', async () => {
    const b = await attached();
    expect(() => b.containerView('~mine', { title: 'x' })).toThrow(/does not start with ~/);
    expect(() => b.containerView('a/b', { title: 'x' })).toThrow(BridgeError);
    expect(() => b.containerView('x', { title: 'x', layout: 'chest:7' })).toThrow(/unknown container layout/);
    expect(() => b.containerView('x', { title: 'x', layout: 'grid:10x2' })).toThrow(/unknown container layout/);
    expect(() => b.containerView('x', { title: 'x', slots: { 27: 'minecraft:dirt' } })).toThrow(/outside container view/);
    const v = b.containerView('ok', { title: 'x', layout: 'grid:3x3' });
    expect(v.size).toBe(9);
    expect(() => v.setSlot(9, 'minecraft:dirt')).toThrow(/outside/);
    expect(() => v.setSlot(1.5, 'minecraft:dirt')).toThrow(/outside/);
    expect(() => v.setSlot(0, { id: 'minecraft:dirt', count: -1 })).toThrow(/non-negative integer/);
    expect(() => v.setSlot(0, '')).toThrow(/item id/);
    expect(() => v.setSlots([null, { count: 1 } as unknown as string])).toThrow(/needs an id/);
  });

  it('mock: refuses gestures on views that are not shown and malformed gestures', async () => {
    const b = await attached();
    b.containerView('inv', { title: 'x' });
    await until(() => e.mod.views().length === 1);
    expect(() => e.mod.clickSlot(KEY, 0, 'left')).toThrow(/not open/);
    expect(() => e.mod.openView(`${ALICE_PREFIX}/~view/none`)).toThrow(/no core:container-view/);
    e.mod.openView(KEY);
    expect(e.mod.views()[0]!.open).toBe(true);
    expect(() => e.mod.clickSlot(KEY, 0, 'number')).toThrow(/hotbar slot/);
    expect(() => e.mod.clickSlot(KEY, 0.5, 'left')).toThrow(/integer/);
    expect(() => e.mod.closeView(`${ALICE_PREFIX}/~view/other`)).toThrow(/not open/);
    e.mod.closeView(KEY);
    e.mod.closeView();
    expect(e.mod.shownView).toBeUndefined();
  });

  it('opens a view requested before the session opened once it opens', async () => {
    const bot = new FakeBot('Alice');
    const b = load(hub().plugin(), bot);
    const v = b.containerView('inv', { title: 'Early' });
    v.open();
    e.mod.setPlayerContext(OVERWORLD);
    await until(() => e.mod.shownView === KEY, 5000, 'early open');
    expect(v.isOpen).toBe(true);
  });

  it('replays views after the mod restarts and closes an open view as lost instead of reopening it', async () => {
    const b = await attached();
    const reasons: string[] = [];
    const v = b.containerView('inv', { title: 'Inv', slots: ['minecraft:dirt'], on: { close: (_v, r) => reasons.push(r) } });
    await shown(v, KEY);
    await e.restart({ attachPolicy: 'always' });
    await until(() => e.mod.views().length === 1, 8000, 'view replayed');
    expect(e.mod.views()[0]!.data.slots).toEqual([expect.objectContaining({ slot: 0, item: expect.objectContaining({ id: 'minecraft:dirt' }) })]);
    await until(() => reasons.length === 1, 5000, 'lost close');
    expect(reasons).toEqual(['lost']);
    expect(v.isOpen).toBe(false);
    expect(e.mod.shownView).toBeUndefined();
    v.open();
    await until(() => e.mod.shownView === KEY, 5000, 'reopen on request');
    expect(await e.mod.clickSlot(KEY, 0, 'left')).toMatchObject({ ok: true });
  });

  it('removes an extension view on unload, makes its handle inert and drops its route', async () => {
    let view!: ContainerView;
    let clicks = 0;
    const ext = defineExtension({
      id: 'acme:shop',
      version: '1.0.0',
      setup(ctx) {
        view = ctx.containerView('shop', { title: 'Shop', on: { click: () => void clicks++ } });
      },
    });
    const b = await attached();
    await b.use(ext);
    const key = `${ALICE}/acme:shop/~view/shop`;
    await shown(view, key);
    await e.mod.clickSlot(key, 0, 'left');
    expect(clicks).toBe(1);
    const closed = e.mod.waitFor('viewClosed');
    await b.unload('acme:shop');
    expect((await closed)[0]).toEqual({ key, by: 'removed' });
    expect(e.mod.views()).toEqual([]);
    expect(view.isOpen).toBe(false);
    view.setSlot(0, 'minecraft:dirt');
    view.open();
    await sleep(30);
    expect(e.mod.views()).toEqual([]);
    expect(e.mod.shownView).toBeUndefined();
  });

  it('refuses opens and gestures for views nobody handles', async () => {
    const b = await attached();
    // A raw object declaration has no route behind it.
    b.objects.put('~view/raw', 'core:container-view', { title: 'Raw', slots: [] }, { world: 'global' });
    const key = `${ALICE_PREFIX}/~view/raw`;
    await until(() => e.mod.views().length === 1);
    const refused = e.mod.waitFor('viewClosed');
    e.mod.openView(key);
    const ack = e.mod.clickSlot(key, 0, 'left');
    expect(await ack).toMatchObject({ ok: false, message: 'not available' });
    expect((await refused)[0]).toEqual({ key, by: 'bot' });
  });
});

describe('menus', () => {
  const MENU = `${ALICE_PREFIX}/~view/jobs`;

  it('declares buttons with name, lore and glint and runs their click handlers', async () => {
    const b = await attached();
    const ran: string[] = [];
    const menu = b.menu('jobs', { title: 'Jobs', rows: 3 });
    menu
      .slot(11, { item: 'minecraft:iron_pickaxe', name: 'Mine', lore: ['Strip-mine at y -58'], onClick: () => void ran.push('mine') })
      .slot(15, { item: 'minecraft:wheat', name: 'Farm', glint: true, close: true, onClick: () => 'farming' })
      .slot(13, { item: { id: 'minecraft:paper', name: 'Ignored' }, name: 'Info', count: 2 })
      .fill('minecraft:black_stained_glass_pane');
    expect(b.menu('jobs', { title: 'Jobs!' })).toBe(menu);
    await until(() => e.mod.views()[0]?.data.slots.length === 3 && e.mod.views()[0]?.data.title === 'Jobs!');
    const d = e.mod.views()[0]!.data;
    expect(d).toMatchObject({ layout: 'chest:3', filler: { id: 'minecraft:black_stained_glass_pane' } });
    expect(d.slots).toMatchObject([
      { slot: 11, item: expect.objectContaining({ id: 'minecraft:iron_pickaxe', name: 'Mine', lore: ['Strip-mine at y -58'] }) },
      { slot: 13, item: expect.objectContaining({ id: 'minecraft:paper', name: 'Info', count: 2 }) },
      { slot: 15, item: expect.objectContaining({ id: 'minecraft:wheat', name: 'Farm', glint: true }) },
    ]);

    await shown(menu, MENU);
    expect(await e.mod.clickSlot(MENU, 11, 'left')).toMatchObject({ ok: true });
    expect(await e.mod.clickSlot(MENU, 11, 'drop')).toMatchObject({ ok: true });
    expect(await e.mod.clickSlot(MENU, 11, 'double')).toMatchObject({ ok: true });
    expect(ran).toEqual(['mine']);
    expect(await e.mod.clickSlot(MENU, 13, 'left')).toMatchObject({ ok: true });
    expect(await e.mod.clickSlot(MENU, 15, 'left')).toMatchObject({ ok: true, message: 'farming' });
    await until(() => !menu.isOpen, 5000, 'close after click');

    menu.slot(11, null).fill(null);
    await until(() => e.mod.views()[0]?.data.slots.length === 2 && e.mod.views()[0]?.data.filler === undefined);
    expect(() => menu.slot(27, { item: 'minecraft:dirt' })).toThrow(/outside/);
    expect(() => b.menu('big', { title: 'x', rows: 7 })).toThrow(/rows/);
    expect(() => b.menu('~x', { title: 'x' })).toThrow(/does not start with ~/);
  });

  it('opens another menu from a click', async () => {
    const b = await attached();
    const sub = b.menu('sub', { title: 'Sub' });
    const main = b.menu('jobs', { title: 'Jobs' }).slot(0, { item: 'minecraft:compass', onClick: () => sub.open() });
    await shown(main, MENU);
    await e.mod.clickSlot(MENU, 0, 'left');
    await until(() => e.mod.shownView === `${ALICE_PREFIX}/~view/sub`);
    await until(() => !main.isOpen);
    expect(sub.isOpen).toBe(true);
  });

  it('paginates entries with back and next buttons', async () => {
    const b = await attached();
    const clicked: number[] = [];
    const entries = Array.from({ length: 20 }, (_, i) => ({ item: 'minecraft:emerald', name: `Offer ${i}`, onClick: () => void clicked.push(i) }));
    const menu = b.menu('jobs', { title: 'Shop', rows: 3 });
    const pages = menu.paginated('offers', { area: { x: 0, y: 0, width: 9, height: 2 }, entries, prev: 18, next: 26 });
    expect([pages.page, pages.pages]).toEqual([0, 2]);
    const slots = (): [number, string | undefined][] => e.mod.views()[0]!.data.slots.map((s) => [s.slot, s.item.name]);
    await until(() => e.mod.views()[0]?.data.slots.length === 19);
    expect(slots().at(-1)).toEqual([26, 'Next page']);
    expect(slots().some(([s]) => s === 18)).toBe(false);

    await shown(menu, MENU);
    await e.mod.clickSlot(MENU, 26, 'left');
    expect(pages.page).toBe(1);
    await until(() => e.mod.views()[0]?.data.slots.length === 3);
    expect(slots()).toEqual([[0, 'Offer 18'], [1, 'Offer 19'], [18, 'Previous page']]);
    await e.mod.clickSlot(MENU, 1, 'left');
    expect(clicked).toEqual([19]);
    await e.mod.clickSlot(MENU, 5, 'left');
    expect(clicked).toEqual([19]);

    await e.mod.clickSlot(MENU, 18, 'left');
    expect(pages.page).toBe(0);
    pages.set(entries.slice(0, 3));
    expect(pages.pages).toBe(1);
    await until(() => e.mod.views()[0]?.data.slots.length === 3);

    // The same id replaces the pane.
    const custom = menu.paginated('offers', { area: [10, 11], entries, prev: 9, next: 14, nextButton: { item: 'minecraft:lime_dye', name: 'More' }, page: 9 });
    expect([custom.page, custom.pages]).toEqual([9, 10]);
    await until(() => e.mod.views()[0]?.data.slots[0]?.slot === 9);
    expect(slots()).toEqual([[9, 'Previous page'], [10, 'Offer 18'], [11, 'Offer 19']]);
    custom.show(0);
    await until(() => slots().some(([s, n]) => s === 14 && n === 'More'));
    expect(() => menu.paginated('bad', { area: [1, 2], entries, prev: 27 })).toThrow(expect.objectContaining({ code: 'invalid_message' }));
  });

  it('asks a yes/no question with confirm and removes the menu afterwards', async () => {
    const b = await attached();
    let opened = e.mod.waitFor('viewOpened');
    let answer = b.menu.confirm('Delete waypoint?', { yes: 'Delete', no: { item: 'minecraft:barrier', name: 'Keep' }, item: 'minecraft:compass' });
    let key = (await opened)[0].key;
    expect(key).toMatch(/\/user:default\/~view\/~confirm-\d+$/);
    expect(e.mod.views()[0]!.data.slots.map((s) => [s.slot, s.item.id, s.item.name])).toEqual([
      [11, 'minecraft:lime_stained_glass_pane', 'Delete'],
      [13, 'minecraft:compass', undefined],
      [15, 'minecraft:barrier', 'Keep'],
    ]);
    await e.mod.clickSlot(key, 11, 'left');
    expect(await answer).toBe(true);
    await until(() => e.mod.views().length === 0, 5000, 'confirm removed');

    opened = e.mod.waitFor('viewOpened');
    answer = b.menu.confirm('Again?');
    key = (await opened)[0].key;
    await e.mod.clickSlot(key, 15, 'left');
    expect(await answer).toBe(false);

    opened = e.mod.waitFor('viewOpened');
    answer = b.menu.confirm('Close me');
    await opened;
    e.mod.closeView();
    expect(await answer).toBe(false);

    expect(await b.menu.confirm('Timed', { timeoutMs: 40, default: true })).toBe(true);
    const ac = new AbortController();
    opened = e.mod.waitFor('viewOpened');
    answer = b.menu.confirm('Abort', { signal: ac.signal });
    await opened;
    ac.abort();
    expect((await rejection(answer)).code).toBe('cancelled');
    await until(() => e.mod.views().length === 0 && e.mod.shownView === undefined);
    expect((await rejection(b.menu.confirm('x', { timeoutMs: 0 }))).code).toBe('invalid_message');
  });

  it('rejects a pending confirm with cancelled when its extension unloads', async () => {
    let ctx!: ExtensionContext;
    const b = await attached();
    await b.use(defineExtension({ id: 'acme:ask', version: '1.0.0', setup: (c) => void (ctx = c) }));
    const opened = e.mod.waitFor('viewOpened');
    const answer = ctx.menu.confirm('Sure?');
    await opened;
    await b.unload('acme:ask');
    expect((await rejection(answer)).code).toBe('cancelled');
    await until(() => e.mod.views().length === 0);
  });
});
