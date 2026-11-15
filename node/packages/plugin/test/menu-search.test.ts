import { describe, expect, it } from 'vitest';
import type { SlotEntry } from '@mineflayer-bridge/protocol';
import type { MockInputRequest } from '@mineflayer-bridge/mock-mod';
import { MemoryStore, type BotBridge, type MenuCell } from '../src/index.ts';
import { ALICE_PREFIX, fixture, sleep, thrown, until, type Env } from './helpers.ts';

const key = (id: string): string => `${ALICE_PREFIX}/~view/${id}`;

let e: Env;
const { hub, attached } = fixture((f) => (e = f.e));

function entries(id: string): SlotEntry[] {
  return e.mod.views().find((v) => v.key === key(id))?.data.slots ?? [];
}

function entry(id: string, slot: number): SlotEntry | undefined {
  return entries(id).find((s) => s.slot === slot);
}

/** Item ids shown in slots 0 to 3, the pane's area in these tests. */
function shownIds(id: string): (string | undefined)[] {
  return [0, 1, 2, 3].map((s) => entry(id, s)?.item.id);
}

const BLOCKS = ['stone', 'iron_ore', 'dirt', 'gold_ore', 'sand', 'oak_log', 'coal_ore', 'gravel', 'clay', 'glass'];

async function nextPrompt(): Promise<MockInputRequest> {
  const [r] = await e.mod.waitFor('inputRequest');
  return r;
}

async function shown(menu: { open(): void }, id: string): Promise<void> {
  menu.open();
  await until(() => e.mod.shownView === key(id), 5000, `view ${id} shown`);
}

/** Clicks the search button and waits for the anvil prompt, with the menu set aside. */
async function clickSearch(id: string, slot = 7): Promise<MockInputRequest> {
  const queued = nextPrompt();
  await e.mod.clickSlot(key(id), slot, 'left');
  const r = await queued;
  await until(() => e.mod.shownView === undefined, 5000, 'menu set aside');
  return r;
}

describe('menu search', () => {
  it('filters through the anvil prompt, resets to page 1 and clears with the clear button or an empty answer', async () => {
    const b = await attached();
    const heard: string[] = [];
    let closes = 0;
    let opens = 0;
    const menu = b.menu('find', { title: 'Find', rows: 1, onClose: () => void closes++ });
    menu.on('open', () => void opens++);
    const pane = menu.paginated('blocks', { area: [0, 1, 2, 3], entries: BLOCKS, next: 8, search: { slot: 7, clear: 6, onSearch: (f) => void heard.push(f) } });
    await shown(menu, 'find');
    expect(opens).toBe(1);
    await until(() => entry('find', 7)?.item.name === 'Search');
    expect(entry('find', 7)?.item).toMatchObject({ id: 'minecraft:compass', lore: ['Click to search'] });
    expect(entry('find', 6)).toBeUndefined();
    await e.mod.clickSlot(key('find'), 8, 'left');
    expect(pane.page).toBe(1);

    const r = await clickSearch('find');
    expect(r).toMatchObject({ type: 'prompt', kind: 'text', surface: 'anvil' });
    expect(r.request).toMatchObject({ surface: 'anvil', item: 'minecraft:name_tag', text: 'Search', maxLength: 50, defaultValue: '' });
    expect(menu.isOpen).toBe(false);
    e.mod.resolvePrompt(r.id, ' ORE ');
    await until(() => e.mod.shownView === key('find') && menu.isOpen, 5000, 'menu back');
    expect(pane.filter).toBe('ORE');
    expect(pane.matches).toBe(3);
    expect(pane.page).toBe(0);
    expect(pane.pages).toBe(1);
    await until(() => entry('find', 3) === undefined);
    expect(shownIds('find')).toEqual(['minecraft:iron_ore', 'minecraft:gold_ore', 'minecraft:coal_ore', undefined]);
    expect(entry('find', 8)).toBeUndefined();
    expect(entry('find', 7)?.item).toMatchObject({ lore: ['Filter: ORE', '3 matches', 'Right click to clear'], glint: true });
    expect(entry('find', 6)?.item).toMatchObject({ id: 'minecraft:barrier', name: 'Clear search' });
    // Setting the menu aside for the prompt is not a close for the menu's handlers.
    expect([closes, opens]).toEqual([0, 1]);

    await e.mod.clickSlot(key('find'), 6, 'left');
    expect(pane.filter).toBe('');
    await until(() => entry('find', 6) === undefined && entry('find', 0)?.item.id === 'minecraft:stone' && entry('find', 8) !== undefined);
    expect(pane.pages).toBe(3);

    // The default answer is the filter in force; an empty answer clears it.
    pane.filterBy('log');
    let again = await clickSearch('find');
    expect(again.request).toMatchObject({ defaultValue: 'log' });
    e.mod.resolvePrompt(again.id, '');
    await until(() => menu.isOpen && pane.filter === '');

    // A right click on the search button clears as well.
    pane.filterBy('sand');
    await until(() => entry('find', 1) === undefined);
    await e.mod.clickSlot(key('find'), 7, 'right');
    expect(pane.filter).toBe('');
    expect(e.mod.inputQueue()).toEqual([]);

    again = await clickSearch('find');
    e.mod.resolvePrompt(again.id, 'nothing');
    await until(() => menu.isOpen && pane.matches === 0);
    await until(() => entries('find').every((s) => s.slot === 6 || s.slot === 7));
    expect(entry('find', 7)?.item.lore).toEqual(['Filter: nothing', '0 matches', 'Right click to clear']);
    expect(heard).toEqual(['ORE', '', 'log', '', 'sand', '', 'nothing']);
    expect([closes, opens]).toEqual([0, 1]);
  });

  it('returns to the menu unchanged when the prompt is cancelled or times out, with the navigation stack intact', async () => {
    const b = await attached();
    const parent = b.menu('root', { title: 'Root', rows: 1 });
    const child = b.menu('list', { title: 'List', rows: 1, back: 5, reopenParent: true });
    const pane = child.paginated('blocks', { area: [0, 1, 2, 3], entries: BLOCKS, next: 8, search: { slot: 7 } });
    parent.slot(0, { item: 'minecraft:chest', onClick: () => parent.open(child) });
    await shown(parent, 'root');
    await e.mod.clickSlot(key('root'), 0, 'left');
    await until(() => e.mod.shownView === key('list'));
    pane.filterBy('o');
    pane.next();
    expect([pane.filter, pane.page]).toEqual(['o', 1]);
    await until(() => entry('list', 0)?.item.id === 'minecraft:coal_ore');

    const r = await clickSearch('list');
    e.mod.cancel(r.id);
    await until(() => e.mod.shownView === key('list') && child.isOpen, 5000, 'child back');
    expect([pane.filter, pane.page]).toEqual(['o', 1]);
    expect(child.parent).toBe(parent);
    expect(entry('list', 5)?.item.name).toBe('Back');

    const t = await clickSearch('list');
    e.mod.timeOut(t.id);
    await until(() => e.mod.shownView === key('list') && child.isOpen, 5000, 'child back after timeout');
    expect([pane.filter, pane.page]).toEqual(['o', 1]);

    await e.mod.clickSlot(key('list'), 5, 'left');
    await until(() => e.mod.shownView === key('root'));
  });

  it('keeps the filter through set, update and live entries, and matches searchText', async () => {
    const b = await attached();
    const menu = b.menu('jobs', { title: 'Jobs', rows: 1 });
    const jobs: MenuCell[] = [
      { item: 'minecraft:iron_pickaxe', name: 'Mine', lore: ['strip mine'] },
      { item: 'minecraft:wheat', name: 'Farm' },
      { item: 'minecraft:oak_sapling', name: 'Plant trees', lore: ['forest'] },
    ];
    const pane = menu.paginated('jobs', {
      area: [0, 1, 2, 3],
      entries: jobs,
      search: { slot: 7, button: { item: 'minecraft:name_tag', name: 'Find', lore: ['Jobs only'] }, searchText: (j) => (typeof j === 'object' && j !== null && 'lore' in j ? String(j.lore) : '') },
    });
    pane.filterBy('MINE');
    await until(() => entry('jobs', 0)?.item.name === 'Mine' && entry('jobs', 1) === undefined);
    expect(entry('jobs', 7)?.item).toMatchObject({ id: 'minecraft:name_tag', name: 'Find', lore: ['Jobs only', 'Filter: MINE', '1 match', 'Right click to clear'] });

    pane.set([...jobs, { item: 'minecraft:stone', name: 'Quarry', lore: ['mine stone'] }]);
    await until(() => entry('jobs', 1)?.item.name === 'Quarry');
    expect(pane.matches).toBe(2);
    menu.update({ title: 'All jobs' });
    await sleep(30);
    expect(pane.filter).toBe('MINE');
    expect(entry('jobs', 1)?.item.name).toBe('Quarry');

    // The default search text is the shown name, else the item id and its path.
    const plain = menu.paginated('plain', { area: [0, 1, 2, 3], entries: ['oak_log', { item: 'stone', name: 'Rock' }, 'stone'], priority: 1 });
    plain.filterBy('oak log');
    expect(plain.matches).toBe(1);
    plain.filterBy('stone');
    expect(plain.matches).toBe(1);
    plain.filterBy('rock');
    expect(plain.matches).toBe(1);

    expect(thrown(() => menu.paginated('bad', { area: [0], entries: [], search: { slot: 12 } })).message).toMatch(/slot 12/);
    expect(thrown(() => menu.paginated('bad', { area: [0], entries: [], search: { slot: 1, maxLength: 0 } })).message).toMatch(/maxLength/);
  });

  it('searches with a custom search text and asks from the handle', async () => {
    const b = await attached();
    const menu = b.menu('shop', { title: 'Shop', rows: 2 });
    const pages = menu.paginated('blocks', {
      area: { x: 0, y: 0, width: 9, height: 1 },
      entries: BLOCKS.map((id) => ({ item: id, name: id.replace('_', ' ') })),
      prev: 9,
      next: 17,
      search: { slot: 13, prompt: 'Find a block', item: 'minecraft:spyglass', searchText: (cell) => (typeof cell === 'object' && cell !== null && 'name' in cell ? String(cell.name) : '') },
    });
    await shown(menu, 'shop');
    const queued = nextPrompt();
    const answer = pages.search();
    const r = await queued;
    expect(r.request).toMatchObject({ text: 'Find a block', item: 'minecraft:spyglass', surface: 'anvil' });
    // One prompt at a time.
    expect(pages.search()).toBe(answer);
    e.mod.resolvePrompt(r.id, 'gold');
    expect(await answer).toBe('gold');
    await until(() => menu.isOpen && entry('shop', 0)?.item.name === 'gold ore' && entry('shop', 1) === undefined);
  });

  it('persists the filter with the page and drops the prompt when the menu goes away', async () => {
    const store = new MemoryStore();
    const build = (b: BotBridge) => {
      const menu = b.menu('saved', { title: 'Saved', rows: 1, persist: true });
      return { menu, pane: menu.paginated('blocks', { area: [0, 1], entries: BLOCKS, next: 8, search: { slot: 7 } }) };
    };
    const h1 = hub({ store });
    const b1 = await attached('Alice', h1);
    const first = build(b1);
    await first.menu.restored;
    await shown(first.menu, 'saved');
    const r = await clickSearch('saved');
    e.mod.resolvePrompt(r.id, 'o');
    await until(() => first.menu.isOpen && first.pane.filter === 'o');
    await e.mod.clickSlot(key('saved'), 8, 'left');
    expect([first.pane.page, first.pane.matches]).toEqual([1, 5]);
    await sleep(60);
    await h1.close();

    const b2 = await attached('Alice', hub({ store }));
    const second = build(b2);
    await second.menu.restored;
    expect([second.pane.filter, second.pane.page, second.pane.matches]).toEqual(['o', 1, 5]);
    await until(() => entry('saved', 0)?.item.id === 'minecraft:gold_ore' && entry('saved', 1)?.item.id === 'minecraft:oak_log');
    await shown(second.menu, 'saved');
    const p = await clickSearch('saved');
    second.menu.remove();
    await until(() => e.mod.inputQueue().every((x) => x.id !== p.id), 5000, 'prompt withdrawn');
    expect(e.mod.shownView).toBeUndefined();
  });
});
