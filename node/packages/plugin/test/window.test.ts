import { EventEmitter } from 'node:events';
import { describe, expect, it } from 'vitest';
import { clickArgs, headTexture, layoutFor, plainText, styledText, windowItem, windowMirror, type BotBridge, type ContainerClick, type WindowApi } from '../src/index.ts';
import { textReader, type ChatLoader } from '../src/window.ts';
import { ALICE, FakeBot, fixture, load, rejection, sleep, until, type Env } from './helpers.ts';

let e: Env;
const { hub, attached } = fixture((f) => (e = f.e));

const VIEW = `${ALICE}/bridge:window/~view/window`;

interface FakeItem {
  name: string;
  count: number;
  type: number;
  metadata: number;
  customName: unknown;
  customLore?: unknown;
  enchants?: unknown[];
}

/** A stand-in for a prismarine-windows window: slots that report changes. */
class FakeWindow extends EventEmitter {
  slots: (FakeItem | null)[];
  inventoryStart: number;

  constructor(
    size: number,
    readonly type: string = 'minecraft:inventory',
    readonly title: unknown = 'Inventory',
  ) {
    super();
    // An inventory window is all player slots; any other window ends with the 36 player slots.
    this.inventoryStart = type === 'minecraft:inventory' ? 9 : size;
    this.slots = Array.from({ length: type === 'minecraft:inventory' ? size : size + 36 }, () => null);
  }

  set(slot: number, item: FakeItem | null): void {
    const old = this.slots[slot] ?? null;
    this.slots[slot] = item;
    this.emit('updateSlot', slot, old, item);
  }
}

function item(name: string, count: number, type = 1, extra: Partial<FakeItem> = {}): FakeItem {
  return { name, count, type, metadata: 0, customName: null, ...extra };
}

/** A bot with an inventory and the window methods of mineflayer, recording what it is asked to do. */
function windowBot(name = 'Alice') {
  const inventory = new FakeWindow(46);
  inventory.slots[36] = item('diamond_pickaxe', 1, 800);
  inventory.slots[9] = item('cobblestone', 64, 35);
  const calls: unknown[][] = [];
  const bot = Object.assign(new FakeBot(name), {
    inventory,
    currentWindow: null as FakeWindow | null,
    clickWindow: async (slot: number, button: number, mode: number) => void calls.push(['clickWindow', slot, button, mode]),
    closeWindow(w: FakeWindow) {
      calls.push(['closeWindow', w.type]);
      if (bot.currentWindow === w) bot.currentWindow = null;
      bot.emit('windowClose', w);
    },
    tossStack: async (i: FakeItem) => void calls.push(['tossStack', i.name]),
    toss: async (type: number, metadata: number | null, count: number | null) => void calls.push(['toss', type, metadata, count]),
    moveSlotItem: async (from: number, to: number) => void calls.push(['moveSlotItem', from, to]),
  });
  /** The server opens a window for the bot. */
  const serverOpens = (w: FakeWindow): void => {
    bot.currentWindow = w;
    bot.emit('windowOpen', w);
  };
  return { bot, inventory, calls, serverOpens };
}

async function mirror(options: Parameters<typeof windowMirror>[0] = {}): Promise<ReturnType<typeof windowBot> & { b: BotBridge; api: WindowApi }> {
  const w = windowBot();
  const b = await attached(w.bot);
  await b.use(windowMirror(options));
  await until(() => e.mod.views().length === 1);
  return { ...w, b, api: b.get(windowMirror)! };
}

const shownSlots = (): [number, string, number | undefined][] => e.mod.views()[0]!.data.slots.map((s) => [s.slot, s.item.id, s.item.count]);

describe('bridge:window helpers', () => {
  it('maps every gesture to the button and mode vanilla sends', () => {
    const all: ContainerClick[] = ['left', 'right', 'shift-left', 'shift-right', 'middle', 'number', 'drop', 'drop-all', 'double'];
    expect(all.map((c) => [c, clickArgs(c, 7)])).toEqual([
      ['left', { button: 0, mode: 0 }],
      ['right', { button: 1, mode: 0 }],
      ['shift-left', { button: 0, mode: 1 }],
      ['shift-right', { button: 1, mode: 1 }],
      ['middle', { button: 2, mode: 3 }],
      ['number', { button: 7, mode: 2 }],
      ['drop', { button: 0, mode: 4 }],
      ['drop-all', { button: 1, mode: 4 }],
      ['double', { button: 0, mode: 6 }],
    ]);
    expect(clickArgs('number')).toEqual({ button: 0, mode: 2 });
  });

  it('picks a layout from the slot count and window type', () => {
    expect(layoutFor(27, 'minecraft:generic_9x3')).toBe('chest:3');
    expect(layoutFor(54, 'minecraft:generic_9x6')).toBe('chest:6');
    expect(layoutFor(5, 'minecraft:hopper')).toBe('hopper');
    expect(layoutFor(9, 'minecraft:generic_3x3')).toBe('dispenser');
    expect(layoutFor(9, 'minecraft:generic_9x1')).toBe('chest:1');
    expect(layoutFor(3, 'minecraft:furnace')).toBe('chest:1');
    expect(layoutFor(10)).toBe('chest:2');
    expect(layoutFor(0)).toBe('chest:1');
    expect(layoutFor(90)).toBe('chest:6');
  });

  it('reads text components as plain strings without prismarine-chat', () => {
    expect(plainText('Shiny')).toBe('Shiny');
    expect(plainText(null)).toBeUndefined();
    expect(plainText('{"text":"Lobby ","extra":[{"text":"1","color":"gold"},{"text":" (full)"}]}')).toBe('Lobby 1 (full)');
    expect(plainText('"quoted"')).toBe('quoted');
    expect(plainText('{not json')).toBe('{not json');
    expect(plainText({ translate: 'container.chest' })).toBe('container.chest');
    expect(plainText({ translate: 'x.y', fallback: 'Chest' })).toBe('Chest');
    expect(plainText(['a', { text: 'b' }, 3])).toBe('ab3');
    // NBT components, as prismarine-nbt holds them.
    const nbt = { type: 'compound', value: { text: { type: 'string', value: 'Skyblock' }, extra: { type: 'list', value: { type: 'compound', value: [{ text: { type: 'string', value: ' #2' } }] } } } };
    expect(plainText(nbt)).toBe('Skyblock #2');
  });

  it('uses prismarine-chat for components when a loader and a registry are there, and falls back when it fails', () => {
    const seen: unknown[] = [];
    const loader: ChatLoader = (registry) => ({
      fromNotch(message) {
        seen.push(registry);
        if (message === '{"boom":1}') throw new Error('bad component');
        return { toString: () => `chat<${typeof message === 'string' ? message : JSON.stringify(message)}>` };
      },
    });
    const text = textReader({ version: '26.3' }, loader);
    expect(text('plain')).toBe('plain');
    expect(text('{"text":"x"}')).toBe('chat<{"text":"x"}>');
    expect(text({ text: 'y' })).toBe('chat<{"text":"y"}>');
    expect(text('{"boom":1}')).toBe('');
    expect(text(undefined)).toBeUndefined();
    expect(seen).toEqual([{ version: '26.3' }, { version: '26.3' }, { version: '26.3' }]);

    // No registry, no loader, or a loader that cannot take the registry: plain strings.
    expect(textReader(undefined, loader)({ text: 'z' })).toBe('z');
    expect(textReader({}, null)('{"text":"z"}')).toBe('z');
    expect(textReader({}, () => { throw new Error('unknown version'); })({ text: 'z' })).toBe('z');
  });

  it('shows an item with its name, lore and glint', () => {
    expect(windowItem(null)).toBeNull();
    expect(windowItem(item('stone', 3))).toEqual({ id: 'minecraft:stone', count: 3 });
    expect(windowItem(item('compass', 1, 5, { customName: '{"text":"Server selector"}', customLore: ['{"text":"Click to join"}', '', 'plain'], enchants: [{ name: 'unbreaking', lvl: 1 }] }))).toEqual({
      id: 'minecraft:compass',
      count: 1,
      // Vanilla's defaults when a component says nothing: italic names, purple italic lore.
      name: [{ text: 'Server selector', italic: true }],
      lore: [[{ text: 'Click to join', color: 0xaa00aa, italic: true }], '', [{ text: 'plain', color: 0xaa00aa, italic: true }]],
      glint: true,
    });
  });

  it('keeps colours and styles of NBT components, legacy codes and spacer lines, and a head skin', () => {
    // As a 26.x server menu sends it, through prismarine-nbt: typed tags, booleans as bytes.
    const tag = (type: string, value: unknown) => ({ type, value });
    const part = (text: string, color: string, bold: number) =>
      ({ text: tag('string', text), color: tag('string', color), bold: tag('byte', bold), italic: tag('byte', 0), underlined: tag('byte', 0) });
    const line = (...parts: ReturnType<typeof part>[]) => tag('compound', { text: tag('string', ''), extra: tag('list', tag('compound', parts)) });
    const texture = 'eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGZhIn19fQ==';
    const head = {
      ...item('player_head', 1, 0),
      customName: line(part('Pixelmon Installation', 'yellow', 1)),
      customLore: [line(part('View the guide', 'gray', 0)), line(), line(part('[CLICK] ', 'green', 1), part('for links', 'gray', 0))],
      components: [{ type: 'custom_data', data: {} }, { type: 'profile', data: { type: 'partial', uuid: 'd2c46104-03a5-4ce2-b72e-25b207b05487', properties: [{ name: 'textures', value: texture }] } }],
    };
    expect(windowItem(head)).toEqual({
      id: 'minecraft:player_head',
      count: 1,
      name: [{ text: 'Pixelmon Installation', color: 0xffff55, bold: true }],
      lore: [[{ text: 'View the guide', color: 0xaaaaaa }], '', [{ text: '[CLICK] ', color: 0x55ff55, bold: true }, { text: 'for links', color: 0xaaaaaa }]],
      headTexture: texture,
    });
    expect(styledText('\u00a76Gold \u00a7lbold\u00a7r plain')).toEqual([{ text: 'Gold ', color: 0xffaa00 }, { text: 'bold', color: 0xffaa00, bold: true }, ' plain']);
    expect(styledText('{"text":"A","color":"#123456","underlined":true,"extra":[{"text":"B","strikethrough":true}]}')).toEqual([
      { text: 'A', color: 0x123456, underlined: true },
      { text: 'B', color: 0x123456, underlined: true, strikethrough: true },
    ]);
    expect(styledText(null)).toBeUndefined();
    expect(headTexture({ ...item('player_head', 1, 0), nbt: { SkullOwner: { Properties: { textures: [{ Value: 'abc' }] } } } })).toBe('abc');
  });
});

describe('bridge:window', () => {
  it('shows the inventory, follows updateSlot while it is mirrored and plays clicks as the bot', async () => {
    const { api, inventory, calls } = await mirror({ title: 'Inventory of Alice' });
    expect(api.showing).toBeUndefined();
    expect(api.open('inventory')).toBe('inventory');
    await until(() => e.mod.shownView === VIEW);
    expect(api.showing).toBe('inventory');
    expect(e.mod.views()[0]).toMatchObject({ key: VIEW, data: { title: 'Inventory of Alice', layout: 'player_inventory' } });
    expect(shownSlots()).toEqual([[9, 'minecraft:cobblestone', 64], [36, 'minecraft:diamond_pickaxe', 1]]);
    expect(api.view.size).toBe(46);

    inventory.set(9, item('cobblestone', 12, 35));
    inventory.set(40, item('torch', 16, 50));
    inventory.set(36, null);
    await until(() => shownSlots().length === 2 && shownSlots()[0]![2] === 12);
    expect(shownSlots().map((s) => [s[0], s[1]])).toEqual([[9, 'minecraft:cobblestone'], [40, 'minecraft:torch']]);

    expect(await e.mod.clickSlot(VIEW, 9, 'left')).toMatchObject({ ok: true });
    expect(await e.mod.clickSlot(VIEW, 9, 'shift_right')).toMatchObject({ ok: true });
    expect(await e.mod.clickSlot(VIEW, 40, 'drop_all')).toMatchObject({ ok: true });
    expect(await e.mod.clickSlot(VIEW, 40, 'number', { hotbar: 3 })).toMatchObject({ ok: true });
    expect(await e.mod.clickSlot(VIEW, 20, 'double')).toMatchObject({ ok: true });
    expect(calls).toEqual([
      ['clickWindow', 9, 0, 0],
      ['clickWindow', 9, 1, 1],
      ['clickWindow', 40, 1, 4],
      ['clickWindow', 40, 3, 2],
      ['clickWindow', 20, 0, 6],
    ]);
  });

  it('mirrors the open window with its title, layout and items, and falls back to the inventory', async () => {
    const { api, serverOpens, calls } = await mirror();
    expect(api.open()).toBe('inventory');
    await until(() => e.mod.shownView === VIEW);
    expect(e.mod.views()[0]!.data).toMatchObject({ title: "Alice's inventory", layout: 'player_inventory' });
    api.close();
    await until(() => e.mod.shownView === undefined);
    expect(api.showing).toBeUndefined();

    const chest = new FakeWindow(27, 'minecraft:generic_9x3', '{"text":"Server ","extra":[{"text":"selector"}]}');
    chest.slots[4] = item('compass', 1, 9, { customName: { text: 'Survival' }, customLore: ['{"text":"12 online"}'], enchants: [{}] });
    chest.slots[27] = item('dirt', 5);
    serverOpens(chest);
    // Not followed: the view was closed.
    await sleep(20);
    expect(e.mod.shownView).toBeUndefined();

    expect(api.open('window')).toBe('window');
    await until(() => e.mod.shownView === VIEW);
    expect(api.showing).toBe('window');
    expect(e.mod.views()[0]!.data).toMatchObject({ title: 'Server selector', layout: 'chest:3' });
    expect(e.mod.views()[0]!.data.slots).toHaveLength(1);
    expect(e.mod.views()[0]!.data.slots[0]).toMatchObject({ slot: 4, item: { id: 'minecraft:compass', nameSpans: [{ text: 'Survival', italic: true }], loreLines: [{ spans: [{ text: '12 online', color: 0xffaa00aa, italic: true }] }], glint: true } });
    expect(api.view.size).toBe(27);

    chest.set(5, item('emerald', 2));
    chest.set(30, item('stone', 1));
    await until(() => shownSlots().length === 2);
    expect(shownSlots().map((s) => s[0])).toEqual([4, 5]);

    expect(await e.mod.clickSlot(VIEW, 4, 'left')).toMatchObject({ ok: true });
    expect(await e.mod.clickSlot(VIEW, 5, 'shift_left')).toMatchObject({ ok: true });
    expect(calls).toEqual([['clickWindow', 4, 0, 0], ['clickWindow', 5, 0, 1]]);
    // The inventory can still be asked for while a window is open, but not clicked.
    expect(api.open('inventory')).toBe('inventory');
    await until(() => e.mod.views()[0]!.data.layout === 'player_inventory');
    expect(await e.mod.clickSlot(VIEW, 9, 'left')).toMatchObject({ ok: false, message: 'the bot has another window open' });
  });

  it('follows the bot to the next window, closes with it, and closes the bot window when the player closes the view', async () => {
    const { api, bot, serverOpens, calls } = await mirror();
    const page1 = new FakeWindow(9, 'minecraft:generic_9x1', 'Page 1');
    page1.slots[0] = item('paper', 1);
    serverOpens(page1);
    api.open();
    await until(() => e.mod.shownView === VIEW && e.mod.views()[0]!.data.title === 'Page 1');

    const page2 = new FakeWindow(5, 'minecraft:hopper', { text: 'Page 2' });
    page2.slots[2] = item('book', 1);
    serverOpens(page2);
    await until(() => e.mod.views()[0]!.data.title === 'Page 2');
    expect(e.mod.views()[0]!.data).toMatchObject({ layout: 'hopper', slots: [{ slot: 2, item: expect.objectContaining({ id: 'minecraft:book' }) }] });
    expect(e.mod.shownView).toBe(VIEW);
    expect(page1.listenerCount('updateSlot')).toBe(0);
    expect(page2.listenerCount('updateSlot')).toBe(1);
    // A click meant for the first page no longer reaches the bot once it left that window.
    expect(await e.mod.clickSlot(VIEW, 2, 'left')).toMatchObject({ ok: true });
    expect(calls).toEqual([['clickWindow', 2, 0, 0]]);

    // The server closes the window: the view closes with it.
    bot.currentWindow = null;
    bot.emit('windowClose', page2);
    await until(() => e.mod.shownView === undefined);
    expect(api.showing).toBeUndefined();
    expect(page2.listenerCount('updateSlot')).toBe(0);

    // The player closes the view: the bot closes its window.
    const page3 = new FakeWindow(27, 'minecraft:generic_9x3', 'Page 3');
    serverOpens(page3);
    api.open();
    await until(() => e.mod.shownView === VIEW);
    e.mod.closeView();
    await until(() => calls.some((c) => c[0] === 'closeWindow'));
    expect(calls.at(-1)).toEqual(['closeWindow', 'minecraft:generic_9x3']);
    expect(bot.currentWindow).toBeNull();
    expect(api.view.isOpen).toBe(false);
  });

  it('keeps the bot window open and stays put with closeWindow and follow off', async () => {
    const { api, bot, serverOpens, calls } = await mirror({ closeWindow: false, follow: false });
    const first = new FakeWindow(9, 'minecraft:generic_9x1', 'First');
    serverOpens(first);
    api.open();
    await until(() => e.mod.shownView === VIEW);
    const second = new FakeWindow(9, 'minecraft:generic_9x1', 'Second');
    serverOpens(second);
    await sleep(30);
    expect(e.mod.views()[0]!.data.title).toBe('First');
    expect(await e.mod.clickSlot(VIEW, 0, 'left')).toMatchObject({ ok: false, message: 'the bot no longer has this window open' });
    e.mod.closeView();
    await until(() => !api.view.isOpen);
    expect(calls).toEqual([]);
    expect(bot.currentWindow).toBe(second);
  });

  it('registers Open inventory and Open window on the bot and mirrors the inventory when the player opens the view', async () => {
    const { api, serverOpens } = await mirror();
    expect(e.mod.actions().map((a) => [a.key, a.data.label, a.data.target.type])).toEqual([
      [`${ALICE}/bridge:window/~action/inventory`, 'Open inventory', 'bot'],
      [`${ALICE}/bridge:window/~action/window`, 'Open window', 'bot'],
    ]);
    expect(await e.mod.invokeAction(`${ALICE}/bridge:window/~action/window`, { type: 'bot', objectKey: ALICE })).toEqual({ ok: true, message: 'No window open; showing the inventory' });
    await until(() => e.mod.shownView === VIEW);
    expect(api.showing).toBe('inventory');
    e.mod.closeView();
    await until(() => !api.view.isOpen);

    serverOpens(new FakeWindow(27, 'minecraft:generic_9x3', 'Chest'));
    expect(await e.mod.invokeAction(`${ALICE}/bridge:window/~action/window`, { type: 'bot', objectKey: ALICE })).toMatchObject({ ok: true, message: undefined });
    await until(() => e.mod.shownView === VIEW && e.mod.views()[0]!.data.title === 'Chest');
    e.mod.closeView();
    await until(() => !api.view.isOpen);

    // Opened by the player from the mod, with nothing mirrored: the inventory.
    e.mod.openView(VIEW);
    await until(() => api.showing === 'inventory');
    expect(e.mod.views()[0]!.data.layout).toBe('player_inventory');
  });

  it('manages the inventory with the quick gestures', async () => {
    const { api, calls } = await mirror({ gestures: 'quick' });
    api.open('inventory');
    await until(() => e.mod.shownView === VIEW);
    expect(await e.mod.clickSlot(VIEW, 9, 'left')).toMatchObject({ ok: true });
    expect(await e.mod.clickSlot(VIEW, 9, 'shift_left')).toMatchObject({ ok: true });
    expect(await e.mod.clickSlot(VIEW, 36, 'drop')).toMatchObject({ ok: true });
    expect(await e.mod.clickSlot(VIEW, 36, 'drop_all')).toMatchObject({ ok: true });
    expect(await e.mod.clickSlot(VIEW, 9, 'number', { hotbar: 2 })).toMatchObject({ ok: true });
    expect(await e.mod.clickSlot(VIEW, 20, 'shift_right')).toMatchObject({ ok: false });
    expect(await e.mod.clickSlot(VIEW, 20, 'drop')).toMatchObject({ ok: false });
    expect(await e.mod.clickSlot(VIEW, 20, 'number', { hotbar: 0 })).toMatchObject({ ok: false });
    expect(calls).toEqual([
      ['tossStack', 'cobblestone'],
      ['toss', 800, 0, 1],
      ['tossStack', 'diamond_pickaxe'],
      ['moveSlotItem', 9, 38],
    ]);
  });

  it('rejects gestures the bot cannot perform with unsupported, and setup without an inventory', async () => {
    const inventory = new FakeWindow(46);
    inventory.slots[9] = item('dirt', 1);
    const b = await attached(Object.assign(new FakeBot('Alice'), { inventory }));
    await b.use(windowMirror({ action: false }));
    await until(() => e.mod.views().length === 1);
    expect(e.mod.actions()).toEqual([]);
    b.get(windowMirror)!.open('inventory');
    await until(() => e.mod.shownView === VIEW);
    expect(await e.mod.clickSlot(VIEW, 9, 'left')).toMatchObject({ ok: false, message: 'this bot cannot click windows' });
    await b.unload('bridge:window');

    await b.use(windowMirror({ action: false, gestures: 'quick' }));
    await until(() => e.mod.views().length === 1);
    b.get(windowMirror)!.open('inventory');
    await until(() => e.mod.shownView === VIEW);
    expect(await e.mod.clickSlot(VIEW, 9, 'shift_left')).toMatchObject({ ok: false, message: 'this bot cannot toss items' });
    expect(await e.mod.clickSlot(VIEW, 9, 'number', { hotbar: 1 })).toMatchObject({ ok: false, message: 'this bot cannot move inventory slots' });

    const bare = load(hub().plugin(), new FakeBot('Bob'));
    expect((await rejection(bare.use(windowMirror()))).code).toBe('unsupported');
    const ctl = hub().controller({ name: 'Boss' });
    expect((await rejection(ctl.use(windowMirror()))).code).toBe('unsupported');
  });

  it('stops following the window and removes its view on unload', async () => {
    const { b, bot, inventory, api } = await mirror();
    api.open('inventory');
    expect(inventory.listenerCount('updateSlot')).toBe(1);
    expect(bot.listenerCount('windowOpen')).toBe(1);
    await b.unload('bridge:window');
    expect(inventory.listenerCount('updateSlot')).toBe(0);
    expect(bot.listenerCount('windowOpen')).toBe(0);
    expect(bot.listenerCount('windowClose')).toBe(0);
    await until(() => e.mod.views().length === 0);
    inventory.set(9, item('dirt', 1));
    await sleep(30);
    expect(e.mod.views()).toEqual([]);
  });
});
