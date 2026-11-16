import { describe, expect, it } from 'vitest';
import type { SlotEntry } from '@mineflayer-bridge/protocol';
import { compactCount, toSlotItem } from '../src/items.ts';
import { MemoryStore, type AskEvent, type BotBridge, type StorageContents } from '../src/index.ts';
import { ALICE, ALICE_PREFIX, fixture, OVERWORLD, quietLogger, sleep, thrown, until, type Env } from './helpers.ts';

const key = (id: string): string => `${ALICE_PREFIX}/~view/${id}`;

let e: Env;
const { hub, attached } = fixture((f) => ({ e, logger } = f));
let logger: ReturnType<typeof quietLogger>;

function view(id: string) {
  return e.mod.views().find((v) => v.key === key(id));
}

function entries(id: string): SlotEntry[] {
  return view(id)?.data.slots ?? [];
}

function entry(id: string, slot: number): SlotEntry | undefined {
  return entries(id).find((s) => s.slot === slot);
}

async function shown(v: { open(): void }, id: string): Promise<void> {
  v.open();
  await until(() => e.mod.shownView === key(id), 5000, `view ${id} shown`);
}

describe('item presentation', () => {
  it('compacts counts above 99 and keeps given count text', () => {
    expect([0, 64, 99, 100, 999, 1000, 1250, 9999, 12_345, 999_999, 1_500_000, 2_000_000_000].map(compactCount)).toEqual([
      '0',
      '64',
      '99',
      '100',
      '999',
      '1k',
      '1.2k',
      '9.9k',
      '12k',
      '999k',
      '1.5M',
      '2B',
    ]);
    expect(toSlotItem({ id: 'stone', count: 1500 })).toEqual({ id: 'minecraft:stone', count: 1500, countText: '1.5k' });
    expect(toSlotItem({ id: 'stone', count: 1500, countText: '99+' })).toEqual({ id: 'minecraft:stone', count: 1500, countText: '99+' });
    expect(toSlotItem({ id: 'stone', count: 64 })).toEqual({ id: 'minecraft:stone', count: 64 });
    expect(toSlotItem({ id: 'stone', count: 0 })).toEqual({ id: 'minecraft:stone', count: 0 });
    expect(toSlotItem({ name: 'dirt', count: 200 })).toEqual({ id: 'minecraft:dirt', count: 200, countText: '200' });
  });

  it('sends bars, heads, colours, frames, sounds and styled text in every item input', async () => {
    const b = await attached();
    const other = { username: 'Bob' };
    const v = b.containerView('look', {
      title: 'Looks',
      slots: {
        0: { id: 'diamond_pickaxe', bar: 0.25, barColor: '#00ff00' },
        1: { id: 'player_head', head: 'self', name: [{ text: 'Alice', color: '#ffaa00', bold: true }, ' (bot)'] },
        2: { id: 'player_head', head: other, hideTooltip: true },
        3: { id: 'leather_helmet', color: 0xff0000, clickSound: 'ui.button.click' },
        4: { id: 'clock', frames: ['minecraft:clock', { id: 'compass', glint: true }], frameMs: 250 },
        5: { id: 'paper', lore: ['plain', [{ text: 'Rare', italic: true }, ' item']] },
        6: { id: 'paper', lore: ['one', 'two'] },
      },
    });
    await until(() => entries('look').length === 7);
    const item = (slot: number) => entry('look', slot)!.item;
    expect(item(0)).toMatchObject({ bar: 0.25, barColor: 0xff00ff00 });
    expect(item(1).name ?? undefined).toBeUndefined();
    expect(item(1)).toMatchObject({ head: 'Alice', nameSpans: [{ text: 'Alice', color: 0xffffaa00, bold: true }, { text: ' (bot)' }] });
    expect(item(2)).toMatchObject({ head: 'Bob', hideTooltip: true });
    expect(item(3)).toMatchObject({ color: 0xffff0000, clickSound: 'minecraft:ui.button.click' });
    expect(item(4)).toMatchObject({ frameMs: 250, frames: [expect.objectContaining({ id: 'minecraft:clock' }), expect.objectContaining({ id: 'minecraft:compass', glint: true })] });
    expect(item(5).lore ?? undefined).toBeUndefined();
    expect(item(5)).toMatchObject({ loreLines: [{ spans: [{ text: 'plain' }] }, { spans: [{ text: 'Rare', italic: true }, { text: ' item' }] }] });
    expect(item(6).lore).toEqual(['one', 'two']);
    expect(item(6).loreLines).toBeUndefined();
    expect(v.slot(1)).toMatchObject({ head: 'Alice', name: [{ text: 'Alice', color: 0xffffaa00, bold: true }, ' (bot)'] });
    expect(v.slot(5)?.lore).toEqual(['plain', [{ text: 'Rare', italic: true }, ' item']]);

    const layer = b.layer('shop');
    layer.item('head', [0, 65, 0], { id: 'player_head', head: 'self', count: 3 });
    await until(() => e.mod.displays().length === 1);
    expect(e.mod.displays()[0]!.data).toMatchObject({ item: { id: 'minecraft:player_head', head: 'Alice', count: 3 } });

    const pick = b.worldMenu({ at: [0, 66, 0], entries: [{ id: 'a', item: { id: 'emerald', count: 500, name: [{ text: 'Pay', color: '#00ff00' }] } }] });
    const [req] = await e.mod.waitFor('inputRequest');
    expect(req.type === 'world-menu' && req.request.entries[0]!.item).toMatchObject({ count: 500, countText: '500', nameSpans: [{ text: 'Pay', color: 0xff00ff00 }] });
    e.mod.resolveWorldMenu(req.id, 'a');
    expect(await pick).toBe('a');
  });

  it('rejects presentation that does not fit', async () => {
    const b = await attached();
    const v = b.containerView('bad', { title: 'x' });
    expect(thrown(() => v.setSlot(0, { id: 'stone', bar: 1.5 })).message).toMatch(/bar/);
    expect(thrown(() => v.setSlot(0, { id: 'stone', frames: [{ id: 'dirt', frames: ['stone'] }] })).message).toMatch(/frames/);
    expect(thrown(() => v.setSlot(0, { id: 'stone', frames: [] })).message).toMatch(/non-empty/);
    expect(thrown(() => v.setSlot(0, { id: 'stone', frameMs: 0 })).message).toMatch(/frameMs/);
    expect(thrown(() => v.setSlot(0, { id: 'stone', head: { username: '' } })).message).toMatch(/head/);
    expect(thrown(() => v.setSlot(0, { id: 'stone', name: { text: 'x', click: { action: 'copy', value: 'x' } } })).message).toMatch(/click/);
    expect(thrown(() => v.setSlot(0, { id: 'stone', barColor: 'green' })).message).toMatch(/colour/);
    expect(() => toSlotItem({ id: 'player_head', head: 'self' })).toThrow(/self/);
  });
});

describe('slot policies and storage', () => {
  it('declares policies, reports storage changes and refuses moves on the client', async () => {
    const b = await attached();
    const reports: StorageContents[] = [];
    const heard: StorageContents[] = [];
    const v = b.containerView('drop', {
      title: 'Put items here',
      slots: { 1: { id: 'apple', count: 5 }, 2: 'minecraft:gray_stained_glass_pane' },
      policies: { 0: 'place', 1: { policy: 'take' }, 2: 'locked', 3: { policy: 'free', accepts: { ids: ['diamond'], tags: ['c:gems'] }, maxStack: 16 }, 4: 'button' },
      on: { storage: (slots) => void reports.push(slots) },
    });
    v.on('storage', (slots) => void heard.push(slots));
    await until(() => entries('drop').length === 4);
    expect(entries('drop')).toMatchObject([
      { slot: 0, item: { id: 'minecraft:air', count: 0 }, policy: 'place' },
      { slot: 1, item: { id: 'minecraft:apple', count: 5 }, policy: 'take' },
      { slot: 2, item: { id: 'minecraft:gray_stained_glass_pane' }, policy: 'locked' },
      { slot: 3, policy: 'free', accepts: { type: 'item', ids: ['minecraft:diamond'], tags: ['c:gems'] }, maxStack: 16 },
    ]);
    expect(v.policy(3)).toEqual({ policy: 'free', accepts: { ids: ['minecraft:diamond'], tags: ['c:gems'] }, maxStack: 16 });
    expect(v.policy(4)).toEqual({ policy: 'button' });
    expect([...v.storage()]).toEqual([[1, { id: 'minecraft:apple', count: 5 }]]);

    await shown(v, 'drop');
    expect(await e.mod.storageMove(key('drop'), { from: 'player', to: 0, item: { id: 'minecraft:oak_log', count: 3 } })).toMatchObject({ ok: true, asked: false });
    await until(() => reports.length === 1);
    expect([...v.storage()]).toEqual([
      [0, { id: 'minecraft:oak_log', count: 3 }],
      [1, { id: 'minecraft:apple', count: 5 }],
    ]);
    expect(heard).toEqual(reports);

    expect(await e.mod.storageMove(key('drop'), { from: 1, to: 'player', count: 2 })).toMatchObject({ ok: true });
    await until(() => v.slot(1)?.count === 3);
    expect(await e.mod.storageMove(key('drop'), { from: 'player', to: 2, item: { id: 'minecraft:dirt' } })).toMatchObject({ ok: false, message: 'locked' });
    expect(await e.mod.storageMove(key('drop'), { from: 'player', to: 3, item: { id: 'minecraft:dirt' } })).toMatchObject({ ok: false, message: 'not accepted' });
    expect(await e.mod.storageMove(key('drop'), { from: 'player', to: 3, item: { id: 'minecraft:diamond', count: 20 } })).toMatchObject({ ok: false, message: 'full' });
    expect(await e.mod.storageMove(key('drop'), { from: 0, to: 1 })).toMatchObject({ ok: false, message: 'locked' });
    expect(await e.mod.storageMove(key('drop'), { from: 'player', to: 3, item: { id: 'minecraft:diamond', count: 10 } })).toMatchObject({ ok: true });
    await until(() => v.storage().get(3)?.count === 10);
    expect(reports).toHaveLength(3);
    expect(() => e.mod.clickSlot(key('drop'), 2, 'left')).toThrow(/locked/);
    expect(() => e.mod.clickSlot(key('drop'), 0, 'left')).toThrow(/storage slot/);
    expect(await e.mod.clickSlot(key('drop'), 4, 'left')).toMatchObject({ ok: true });

    v.setPolicy(4, 'place');
    v.setPolicy(0, null);
    await until(() => entry('drop', 4)?.policy === 'place' && entry('drop', 0)?.policy === 'button');
    expect(thrown(() => v.setPolicy(5, 'sticky' as never)).message).toMatch(/unknown slot policy/);
    expect(thrown(() => v.setPolicy(5, { policy: 'place', maxStack: 0 })).message).toMatch(/maxStack/);
  });

  it('accepts tagged items when the mock knows the tag', async () => {
    await e.restart({ itemTags: { 'c:gems': ['minecraft:emerald'] } });
    const b = await attached();
    const v = b.containerView('gems', { title: 'Gems', policies: { 0: { policy: 'place', accepts: { tags: ['c:gems'] } } } });
    await shown(v, 'gems');
    expect(await e.mod.storageMove(key('gems'), { from: 'player', to: 0, item: { id: 'minecraft:emerald' } })).toMatchObject({ ok: true });
    expect(await e.mod.storageMove(key('gems'), { from: 'player', to: 0, item: { id: 'minecraft:stone' } })).toMatchObject({ ok: false, message: 'not accepted' });
  });

  it('asks the bot about moves into ask slots and rolls back rejected ones', async () => {
    const b = await attached();
    const asked: AskEvent[] = [];
    const v = b.containerView('trade', {
      title: 'Offer',
      policies: { 0: 'ask', 1: 'free' },
      on: {
        ask: async (ev) => {
          asked.push(ev);
          if (ev.moved?.id === 'minecraft:dirt') return 'Dirt is worthless';
          if (ev.moved?.id === 'minecraft:stone') return false;
          if (ev.moved?.id === 'minecraft:bedrock') throw new Error('no way');
          return true;
        },
      },
    });
    await shown(v, 'trade');
    expect(await e.mod.storageMove(key('trade'), { from: 'player', to: 0, item: { id: 'minecraft:dirt', count: 4 } })).toMatchObject({
      ok: false,
      asked: true,
      rolledBack: true,
      message: 'Dirt is worthless',
      storage: [],
    });
    expect(await e.mod.storageMove(key('trade'), { from: 'player', to: 0, item: { id: 'minecraft:stone' } })).toMatchObject({ ok: false, rolledBack: true });
    expect(await e.mod.storageMove(key('trade'), { from: 'player', to: 0, item: { id: 'minecraft:bedrock' } })).toMatchObject({ ok: false, message: 'no way' });
    expect(await e.mod.storageMove(key('trade'), { from: 'player', to: 0, item: { id: 'minecraft:gold_ingot', count: 2 } })).toMatchObject({ ok: true, asked: true });
    await until(() => v.storage().get(0)?.count === 2);
    expect(await e.mod.storageMove(key('trade'), { from: 0, to: 1, count: 1 })).toMatchObject({ ok: true, asked: true });
    await until(() => v.storage().get(1)?.count === 1);
    expect(asked.map((a) => [a.slot, a.fromSlot, a.moved?.id, a.moved?.count])).toEqual([
      [0, -1, 'minecraft:dirt', 4],
      [0, -1, 'minecraft:stone', 1],
      [0, -1, 'minecraft:bedrock', 1],
      [0, -1, 'minecraft:gold_ingot', 2],
      [0, 0, 'minecraft:gold_ingot', 1],
    ]);
  });

  it('writes the player storage into the declaration after a fresh session', async () => {
    const b = await attached();
    const v = b.containerView('keep', { title: 'Keep', policies: { 0: 'place' } });
    await shown(v, 'keep');
    await e.mod.storageMove(key('keep'), { from: 'player', to: 0, item: { id: 'minecraft:torch', count: 7 } });
    await until(() => v.storage().size === 1);
    await e.restart();
    e.mod.setPlayerContext(OVERWORLD);
    await until(() => entry('keep', 0)?.item.id === 'minecraft:torch', 8000, 'storage re-declared');
    expect(e.mod.storage(key('keep'))).toMatchObject([{ slot: 0, item: { id: 'minecraft:torch', count: 7 } }]);
  });
});

describe('vanilla progress views', () => {
  it('declares furnace, brewing and lectern layouts with progress, fuel and pages', async () => {
    const b = await attached();
    const f = b.containerView('smelt', { title: 'Smelting', layout: 'furnace', slots: ['iron_ore', 'coal', { id: 'iron_ingot', count: 3 }], progress: 0.5, fuel: 0.75 });
    const brew = b.containerView('brew', { title: 'Brewing', layout: 'brewing', progress: 0.1 });
    const book = b.containerView('book', { title: 'Notes', layout: 'lectern', pages: ['Page one', 'Page two'] });
    expect([f.size, brew.size, book.size]).toEqual([3, 5, 0]);
    await until(() => e.mod.views().length === 3);
    expect(view('smelt')!.data).toMatchObject({ layout: 'furnace', progress: 0.5, fuel: 0.75 });
    expect(view('brew')!.data).toMatchObject({ layout: 'brewing' });
    expect(view('brew')!.data.progress).toBeCloseTo(0.1);
    expect(view('book')!.data).toMatchObject({ layout: 'lectern', pages: ['Page one', 'Page two'] });
    f.update({ progress: 1, fuel: null });
    await until(() => view('smelt')?.data.progress === 1 && view('smelt')?.data.fuel === undefined);
    expect(thrown(() => f.update({ progress: 2 })).message).toMatch(/progress/);
    expect(thrown(() => book.setSlot(0, 'stone')).message).toMatch(/outside/);
  });
});

describe('menu toolkit', () => {
  it('compiles a structure layout with buttons, decoration and storage slots', async () => {
    const b = await attached();
    let pressed = 0;
    const menu = b.menu('build', { title: 'Build', rows: 3 });
    menu.layout(['# # # # # # # # #', '# . . . X . . . #', '# # # # # # # # #'], {
      '#': 'minecraft:black_stained_glass_pane',
      '.': { storage: 'place' },
      X: { item: 'minecraft:emerald', name: 'Go', onClick: () => void pressed++ },
    });
    expect(menu.area('.')).toEqual([10, 11, 12, 14, 15, 16]);
    await until(() => entries('build').length === 27);
    expect(entry('build', 0)).toMatchObject({ policy: 'locked', item: { id: 'minecraft:black_stained_glass_pane' } });
    expect(entry('build', 10)).toMatchObject({ policy: 'place', item: { id: 'minecraft:air' } });
    expect(entry('build', 13)).toMatchObject({ policy: 'button', item: { id: 'minecraft:emerald', name: 'Go' } });
    await shown(menu, 'build');
    await e.mod.clickSlot(key('build'), 13, 'left');
    expect(pressed).toBe(1);
    await e.mod.storageMove(key('build'), { from: 'player', to: 11, item: { id: 'minecraft:cobblestone', count: 32 } });
    await until(() => menu.view.storage().size === 1);
    menu.slot(13, { item: 'minecraft:redstone', name: 'Stop' });
    await until(() => entry('build', 13)?.item.id === 'minecraft:redstone');
    expect(entry('build', 11)?.item).toMatchObject({ id: 'minecraft:cobblestone', count: 32 });
    expect(thrown(() => menu.layout(['#?'], { '#': null })).message).toMatch(/character \?/);
    expect(thrown(() => menu.layout(['##########'], { '#': null })).message).toMatch(/more than 9/);
    expect(thrown(() => menu.area('Z')).message).toMatch(/no character Z/);
    expect(thrown(() => menu.storage(0, 'locked')).message).toMatch(/storage slots/);
  });

  it('fills source slots from a palette, copies from them without using them up and never reports them', async () => {
    const b = await attached();
    const reports: StorageContents[] = [];
    const menu = b.menu('kit', { title: 'Kit', rows: 2, onStorage: (slots) => void reports.push(slots) });
    menu.palette(['oak_log', { id: 'cobblestone', count: 64 }, 'iron_ingot', 'bread']);
    menu.storage({ x: 0, y: 1, width: 9, height: 1 }, 'place');
    await until(() => entries('kit').length === 13);
    expect(entries('kit').slice(0, 4)).toMatchObject([
      { slot: 0, item: { id: 'minecraft:oak_log' }, policy: 'source' },
      { slot: 1, item: { id: 'minecraft:cobblestone', count: 64 }, policy: 'source' },
      { slot: 2, item: { id: 'minecraft:iron_ingot' }, policy: 'source' },
      { slot: 3, item: { id: 'minecraft:bread' }, policy: 'source' },
    ]);
    expect(entry('kit', 0)?.accepts).toBeUndefined();
    expect(menu.view.policy(0)).toEqual({ policy: 'source' });
    expect(menu.view.storage().size).toBe(0);

    await shown(menu, 'kit');
    expect(await e.mod.storageMove(key('kit'), { from: 1, to: 9, count: 16 })).toMatchObject({ ok: true, storage: [{ slot: 9, item: { id: 'minecraft:cobblestone', count: 16 } }] });
    expect(await e.mod.storageMove(key('kit'), { from: 1, to: 10 })).toMatchObject({ ok: true });
    await until(() => reports.length === 2);
    expect([...reports[1]!]).toEqual([
      [9, { id: 'minecraft:cobblestone', count: 16 }],
      [10, { id: 'minecraft:cobblestone', count: 64 }],
    ]);
    expect(entry('kit', 1)?.item).toMatchObject({ id: 'minecraft:cobblestone', count: 64 });
    expect(menu.view.slot(1)).toMatchObject({ id: 'minecraft:cobblestone', count: 64 });
    // The player's inventory is a copy source of a view with storage slots.
    expect(await e.mod.storageMove(key('kit'), { from: 'player', to: 11, item: { id: 'minecraft:torch', count: 8 } })).toMatchObject({ ok: true });
    expect(await e.mod.storageMove(key('kit'), { from: 'player', to: 2, item: { id: 'minecraft:dirt' } })).toMatchObject({ ok: false, message: 'locked' });
    expect(await e.mod.storageMove(key('kit'), { from: 9, to: 0 })).toMatchObject({ ok: false, message: 'locked' });
    expect(await e.mod.storageMove(key('kit'), { from: 3, to: 'player' })).toMatchObject({ ok: false, message: 'virtual copy' });
    expect(() => e.mod.clickSlot(key('kit'), 0, 'left')).toThrow(/source slot/);
    await until(() => menu.view.storage().size === 3);

    menu.palette(['diamond'], { slots: [0, 1, 2, 3] });
    await until(() => entries('kit').filter((x) => x.policy === 'source').length === 1);
    expect(entry('kit', 0)?.item.id).toBe('minecraft:diamond');
    expect(menu.view.storage().size).toBe(3);

    menu.layout(['S'], { S: { storage: 'source', item: 'emerald' } });
    await until(() => entry('kit', 0)?.item.id === 'minecraft:emerald');
    expect(thrown(() => menu.layout(['S'], { S: { storage: 'source' } })).message).toMatch(/source cell needs the item/);
    expect(thrown(() => menu.storage(0, 'source')).message).toMatch(/use menu.palette/);
    expect(thrown(() => menu.palette(['a', 'b'], { slots: [0] })).message).toMatch(/does not fit into 1 slots/);
    expect(thrown(() => menu.palette(['a'], { slot: [0] } as never)).message).toMatch(/unknown palette option "slot"; did you mean slots\?/);
    expect(thrown(() => menu.view.setPolicy(5, { policy: 'source', maxStack: 4 })).message).toMatch(/source slot takes no accepts or maxStack/);
  });

  it('layers panes by priority and shows lower ones through hidden or empty cells', async () => {
    const b = await attached();
    const menu = b.menu('layers', { title: 'Layers', rows: 3 });
    menu.slot(13, { item: 'minecraft:stone', name: 'base' });
    const border = menu.pane('border', { type: 'outline', cell: 'minecraft:gray_stained_glass_pane' });
    const top = menu.pane('top', { area: [13, 14], cells: [{ item: 'minecraft:gold_block', name: 'top' }], priority: 5 });
    await until(() => entry('layers', 13)?.item.name === 'top');
    expect(entries('layers').filter((s) => s.item.id === 'minecraft:gray_stained_glass_pane').map((s) => s.slot)).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26]);
    expect(entry('layers', 14)).toBeUndefined();
    top.setVisible(false);
    await until(() => entry('layers', 13)?.item.name === 'base');
    border.set(['minecraft:white_stained_glass_pane']);
    await until(() => entry('layers', 0)?.item.id === 'minecraft:white_stained_glass_pane');
    top.setVisible(true);
    border.remove();
    await until(() => entry('layers', 0) === undefined && entry('layers', 13)?.item.name === 'top');
    expect(menu.get('top')).toBe(top);
    expect(menu.get('border')).toBeUndefined();
    const fill = menu.pane('fill', { area: { x: 0, y: 2, width: 9, height: 1 }, fill: 'minecraft:glass_pane', cells: [null, 'minecraft:dirt'] });
    await until(() => entry('layers', 19)?.item.id === 'minecraft:dirt' && entry('layers', 18)?.item.id === 'minecraft:glass_pane');
    expect(fill.priority).toBe(0);
    expect(thrown(() => menu.pane('x', { area: { x: 5, y: 0, width: 5, height: 1 } })).message).toMatch(/does not fit/);
  });

  it('pages, scrolls and switches tabs', async () => {
    const b = await attached();
    const menu = b.menu('browse', { title: 'Browse', rows: 6 });
    const items = Array.from({ length: 12 }, (_, i) => ({ item: 'minecraft:paper', name: `Item ${i}` }));
    const pages = menu.paginated('pages', { area: [0, 1, 2, 3, 4], entries: items, prev: 7, next: 8 });
    const scroll = menu.scrolling('scroll', { area: { x: 0, y: 1, width: 3, height: 2 }, entries: items, back: 16, forward: 25 });
    const selected: string[] = [];
    const tabs = menu.tabs('tabs', {
      tabs: [
        { id: 'ores', icon: 'minecraft:iron_ore', cells: ['minecraft:coal', 'minecraft:iron_ingot'] },
        { id: 'food', icon: 'minecraft:bread', cells: () => ['minecraft:apple'] },
      ],
      tabSlots: [45, 46],
      area: { x: 0, y: 4, width: 9, height: 1 },
      onSelect: (id) => void selected.push(id),
    });
    expect([pages.pages, scroll.maxOffset, tabs.selected]).toEqual([3, 2, 'ores']);
    const name = (slot: number): string | undefined => entry('browse', slot)?.item.name;
    await until(() => name(4) === 'Item 4' && name(9) === 'Item 0' && name(20) === 'Item 5' && entry('browse', 37)?.item.id === 'minecraft:iron_ingot');
    expect(entry('browse', 7)).toBeUndefined();
    expect(entry('browse', 16)).toBeUndefined();
    expect(entry('browse', 45)?.item.glint).toBe(true);
    await shown(menu, 'browse');
    await e.mod.clickSlot(key('browse'), 8, 'left');
    await until(() => name(0) === 'Item 5' && name(7) === 'Previous page');
    expect(pages.page).toBe(1);
    await e.mod.clickSlot(key('browse'), 25, 'left');
    await until(() => name(9) === 'Item 3' && name(16) === 'Scroll back');
    expect(scroll.offset).toBe(1);
    scroll.scrollTo(99);
    await until(() => name(9) === 'Item 6' && entry('browse', 25) === undefined);
    await e.mod.clickSlot(key('browse'), 46, 'left');
    await until(() => entry('browse', 36)?.item.id === 'minecraft:apple' && entry('browse', 37) === undefined);
    expect([tabs.selected, selected]).toEqual(['food', ['food']]);
    expect(entry('browse', 46)?.item.glint).toBe(true);
    pages.set(items.slice(0, 2));
    await until(() => entry('browse', 2) === undefined && entry('browse', 7) === undefined);
    expect(pages.page).toBe(0);
    expect(thrown(() => tabs.select('nope')).message).toMatch(/no tab/);
    expect(thrown(() => menu.tabs('t2', { tabs: [{ id: 'a', icon: 'stone', cells: [] }], tabSlots: [], area: [0] })).message).toMatch(/one tab slot per tab/);
  });

  it('runs toggles, cycles, counters, sliders, bars and labels', async () => {
    const b = await attached();
    const menu = b.menu('settings', { title: 'Settings', rows: 4 });
    const changes: unknown[] = [];
    const toggle = menu.toggle(0, {
      on: { item: 'minecraft:lime_dye', name: 'On' },
      off: { item: 'minecraft:gray_dye', name: 'Off' },
      onChange: (v) => {
        changes.push(['toggle', v]);
        return v ? 'enabled' : 'disabled';
      },
    });
    const cycle = menu.cycle(1, { values: ['north', 'east', 'south'] as const, look: (v) => ({ item: 'minecraft:compass', name: v }), onChange: (v) => void changes.push(['cycle', v]) });
    const counter = menu.counter(2, { look: 'minecraft:arrow', min: 1, max: 64, value: 5, onChange: (v) => void changes.push(['counter', v]) });
    const slider = menu.slider({ x: 0, y: 1, width: 5, height: 1 }, { min: 0, max: 100, value: 50, onChange: (v) => void changes.push(['slider', v]) });
    const bar = menu.bar({ x: 0, y: 2, width: 4, height: 1 }, { value: 0.6 });
    const label = menu.label({ x: 0, y: 3, width: 9, height: 1 }, 'HI YOU');
    await until(() => entry('settings', 0)?.item.name === 'Off' && entry('settings', 32) !== undefined);
    expect(entry('settings', 2)?.item.count).toBe(5);
    expect(entries('settings').filter((s) => s.slot >= 9 && s.slot < 14).map((s) => [s.item.id, s.item.name, s.item.glint])).toEqual([
      ['minecraft:lime_stained_glass_pane', '0', false],
      ['minecraft:lime_stained_glass_pane', '25', false],
      ['minecraft:lime_stained_glass_pane', '50', true],
      ['minecraft:gray_stained_glass_pane', '75', false],
      ['minecraft:gray_stained_glass_pane', '100', false],
    ]);
    expect(entries('settings').filter((s) => s.slot >= 18 && s.slot < 22).map((s) => [s.item.id, s.item.bar, s.item.name, s.policy])).toEqual([
      ['minecraft:lime_stained_glass_pane', undefined, '60%', 'locked'],
      ['minecraft:lime_stained_glass_pane', undefined, '60%', 'locked'],
      ['minecraft:lime_stained_glass_pane', expect.closeTo(0.4, 5), '60%', 'locked'],
      ['minecraft:gray_stained_glass_pane', undefined, '60%', 'locked'],
    ]);
    expect(entries('settings').filter((s) => s.slot >= 27).map((s) => [s.slot, s.item.countText])).toEqual([
      [27, 'H'],
      [28, 'I'],
      [30, 'Y'],
      [31, 'O'],
      [32, 'U'],
    ]);

    await shown(menu, 'settings');
    expect(await e.mod.clickSlot(key('settings'), 0, 'left')).toMatchObject({ ok: true, message: 'enabled' });
    expect(toggle.value).toBe(true);
    await e.mod.clickSlot(key('settings'), 1, 'right');
    expect(cycle.value).toBe('south');
    await e.mod.clickSlot(key('settings'), 2, 'left');
    await e.mod.clickSlot(key('settings'), 2, 'shift_left');
    await e.mod.clickSlot(key('settings'), 2, 'right');
    expect(counter.value).toBe(15);
    await e.mod.clickSlot(key('settings'), 12, 'left');
    expect(slider.value).toBe(75);
    await until(() => entry('settings', 2)?.item.count === 15 && entry('settings', 12)?.item.glint === true);
    expect(changes).toEqual([
      ['toggle', true],
      ['cycle', 'south'],
      ['counter', 6],
      ['counter', 16],
      ['counter', 15],
      ['slider', 75],
    ]);
    counter.set(500);
    bar.set(1);
    label.set('OK');
    toggle.set(false);
    await until(() => entry('settings', 2)?.item.count === 64 && entry('settings', 21)?.item.name === '100%' && entry('settings', 29) === undefined && entry('settings', 0)?.item.name === 'Off');
    expect(changes).toHaveLength(6);
    expect(thrown(() => menu.slider([0], {})).message).toMatch(/two slots/);
    expect(thrown(() => menu.counter(3, { look: 'stone', min: 5, max: 2 })).message).toMatch(/max/);
    expect(thrown(() => menu.cycle(3, { values: [], look: () => 'stone' })).message).toMatch(/at least one/);
  });

  it('opens children, goes back with state kept and reopens the parent on close', async () => {
    const b = await attached();
    const parent = b.menu('main', { title: 'Main', rows: 2 });
    const pages = parent.paginated('pages', { area: [0, 1], entries: ['stone', 'dirt', 'sand', 'gravel'], next: 8 });
    const child = b.menu('child', { title: 'Child', rows: 1, back: 8, reopenParent: true });
    parent.slot(9, { item: 'minecraft:chest', onClick: () => parent.open(child) });
    await shown(parent, 'main');
    await e.mod.clickSlot(key('main'), 8, 'left');
    expect(pages.page).toBe(1);
    await e.mod.clickSlot(key('main'), 9, 'left');
    await until(() => e.mod.shownView === key('child'));
    expect(child.parent).toBe(parent);
    await until(() => entry('child', 8)?.item.name === 'Back' && view('child')?.data.reopenParent === key('main'));
    await e.mod.clickSlot(key('child'), 8, 'left');
    await until(() => e.mod.shownView === key('main'));
    expect(parent.isOpen).toBe(true);
    expect(pages.page).toBe(1);
    expect(entry('main', 0)?.item.id).toBe('minecraft:sand');

    await e.mod.clickSlot(key('main'), 9, 'left');
    await until(() => child.isOpen);
    expect(e.mod.closeView()).toBe(true);
    await until(() => e.mod.shownView === key('main') && parent.isOpen && !child.isOpen, 5000, 'parent reopened');

    child.open();
    await until(() => child.isOpen);
    expect(child.parent).toBeUndefined();
    await until(() => entry('child', 8) === undefined && view('child')?.data.reopenParent === undefined);
    child.back();
    await until(() => !child.isOpen && e.mod.shownView === undefined);
    expect(() => parent.open(parent)).toThrow(/another menu/);
  });

  it('keeps menus open until answered with requireAnswer and closes after clicks with closeOnClick', async () => {
    const b = await attached();
    const ask = b.menu('ask', { title: 'Answer me', requireAnswer: true });
    await shown(ask, 'ask');
    expect(view('ask')!.data.requireAnswer).toBe(true);
    expect(e.mod.closeView()).toBe(false);
    expect(ask.isOpen).toBe(true);
    expect(e.mod.closeView(undefined, { confirm: true })).toBe(true);
    await until(() => !ask.isOpen);

    const quick = b.menu('quick', { title: 'Quick', closeOnClick: true }).slot(0, { item: 'minecraft:stone' });
    await shown(quick, 'quick');
    await e.mod.clickSlot(key('quick'), 0, 'left');
    await until(() => !quick.isOpen);
  });

  it('persists page, scroll, tab and component state through the plugin store', async () => {
    const store = new MemoryStore();
    const build = (b: BotBridge) => {
      const menu = b.menu('saved', { title: 'Saved', rows: 2, persist: true });
      return {
        menu,
        pages: menu.paginated('pages', { area: [0, 1], entries: ['stone', 'dirt', 'sand'], next: 8 }),
        toggle: menu.toggle(9, { on: 'lime_dye', off: 'gray_dye' }),
        counter: menu.counter(10, { look: 'arrow', value: 3 }),
      };
    };
    const h1 = hub({ store });
    const b1 = await attached('Alice', h1);
    const first = build(b1);
    await first.menu.restored;
    await shown(first.menu, 'saved');
    await e.mod.clickSlot(key('saved'), 8, 'left');
    await e.mod.clickSlot(key('saved'), 9, 'left');
    await e.mod.clickSlot(key('saved'), 10, 'left');
    expect([first.pages.page, first.toggle.value, first.counter.value]).toEqual([1, true, 4]);
    await sleep(60);
    await h1.close();

    const b2 = await attached('Alice', hub({ store }));
    const second = build(b2);
    await second.menu.restored;
    expect([second.pages.page, second.toggle.value, second.counter.value]).toEqual([1, true, 4]);
    await until(() => entry('saved', 0)?.item.id === 'minecraft:sand' && entry('saved', 10)?.item.count === 4);
  });
});
