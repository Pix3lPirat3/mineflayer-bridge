import { EventEmitter } from 'node:events';
import { WireReader } from '@mineflayer-bridge/protocol';
import { describe, expect, it } from 'vitest';
import { encodeExtension } from '../src/ext-wire.ts';
import { botConsole, ConsoleMessages, SpectateMessages, type BotBridge, type BotConsoleApi, type BridgeConsoleLines, type ConsoleLine, type SpectateApi } from '../src/index.ts';
import { legacyText, styledLegacy } from '../src/window.ts';
import { ALICE, FakeBot, fixture, load, OVERWORLD, sleep, until, type Env } from './helpers.ts';

let e: Env;
const { hub } = fixture((f) => (e = f.e));

/** A bot with a protocol client: the console reads the screen packets from it. */
class ScreenBot extends FakeBot {
  readonly _client = new EventEmitter();
}

/** Keys of what the console extension shows start with this. */
const CONSOLE = `${ALICE}/bridge:console`;

async function start(options: Parameters<typeof botConsole>[0] = { flushMs: 10 }): Promise<{ b: BotBridge; bot: ScreenBot; api: BotConsoleApi }> {
  const bot = new ScreenBot('Alice');
  const b = load(hub().plugin({ extensions: [botConsole(options)] }), bot);
  e.mod.setPlayerContext(OVERWORLD);
  await until(() => b.state.attached && b.state.worldMatched);
  return { b, bot, api: b.get<BotConsoleApi>('bridge:console')! };
}

/** Every batch of lines the mod received, decoded with the generated codec. */
function batches(): BridgeConsoleLines[] {
  return e.mod
    .extensionMessages(ALICE)
    .filter((m) => m.type === 'bridge:console.lines')
    .map((m) => ConsoleMessages['bridge:console.lines'].codec.read(new WireReader(m.payload)));
}

function received(): ConsoleLine[] {
  return batches().flatMap((b) => b.lines);
}

/** The player starts or stops spectating Alice, as the mod's spectate extension tells her. */
function spectate(on: boolean): void {
  expect(e.mod.sendExtension(ALICE, 'bridge:spectate.watching', encodeExtension(SpectateMessages['bridge:spectate.watching'], { on }))).toBe(true);
}

/** A chat message as mineflayer hands it over. */
function message(motd: string): { toString(): string; toMotd(): string } {
  return { toString: () => motd.replace(/§(#[0-9a-f]{6}|.)/gi, ''), toMotd: () => motd };
}

function bossBar(id: string, title: string, health: number, color = 'red', dividers = 0): object {
  return { entityUUID: id, title: message(title), health, color, dividers };
}

function chatRows(): string[] {
  return e.mod.objects(`${CONSOLE}/~hud/chat/`).map((o) => (o.data as { text: string }).text);
}

const SCREEN = { flushMs: 0, screenMs: 0, lifecycle: false } as const;

describe('bridge:console', () => {
  it('registers its names when a bot loads it after the hub connected', async () => {
    const h = hub();
    const early = load(h.plugin(), new FakeBot('Early'));
    await until(() => early.state.connected);
    const b = load(h.plugin({ extensions: [botConsole({ flushMs: 10 })] }), new FakeBot('Alice'));
    e.mod.setPlayerContext(OVERWORLD);
    await until(() => b.state.attached && b.has('bridge:console'));
    b.get<BotConsoleApi>('bridge:console')!.info('after the hub was up');
    await until(() => received().some((l) => l.text === 'after the hub was up'), 5000, 'the line');
    expect(early.state.connected).toBe(true);
  });

  it('batches lines with levels and positions to the mod', async () => {
    const { api } = await start();
    api.info('Walking to the mine');
    api.warn('Low food', [10, 64, -3]);
    api.log('x'.repeat(2000), { level: 'debug' });
    await until(() => received().length === 3);
    const [a, b2, c] = received();
    expect(a).toMatchObject({ level: 'info', text: 'Walking to the mine' });
    expect(a!.kind).toBeUndefined();
    expect(b2).toMatchObject({ level: 'warn', text: 'Low food', pos: { x: 10, y: 64, z: -3 } });
    expect(c!.text.length).toBe(1000);
    expect(e.mod.extensionMessages(ALICE).length).toBe(1);
    expect(api.lines.map((l) => [l.level, l.kind])).toEqual([
      ['info', 'log'],
      ['warn', 'log'],
      ['debug', 'log'],
    ]);
  });

  it('captures chat and lifecycle events, and takes input from the console', async () => {
    const { bot, api } = await start();
    bot.emit('messagestr', '<Bob> hi');
    bot.emit('kicked', 'Flying is not enabled');
    const typed: string[] = [];
    api.onInput((t) => typed.push(t));
    const bytes = encodeExtension(ConsoleMessages['bridge:console.input'], { text: 'come here' });
    expect(e.mod.sendExtension(ALICE, 'bridge:console.input', bytes)).toBe(true);
    await until(() => typed.length === 1);
    expect(typed).toEqual(['come here']);
    await until(() => received().length === 3);
    expect(received().map((l) => [l.level, l.text])).toEqual([
      ['info', '<Bob> hi'],
      ['error', 'Kicked: Flying is not enabled'],
      ['info', '> come here'],
    ]);
  });

  it('keeps a bounded history and refuses bad lines', async () => {
    const { api } = await start({ flushMs: 0, keep: 2, chat: false, lifecycle: false });
    api.info('a');
    api.info('b');
    api.info('c');
    expect(api.lines.map((l) => l.text)).toEqual(['b', 'c']);
    expect(() => api.log(5 as never)).toThrow(/string/);
    expect(() => api.info('bad', [Number.NaN, 0, 0])).toThrow();
  });

  it('logs chat and the screen with their kinds and styles', async () => {
    const { bot, api } = await start(SCREEN);
    bot.emit('messagestr', '<Bob> hi', 'chat', message('<Bob> hi'));
    bot.emit('messagestr', 'You cannot break that here', 'system', message('§cYou cannot break §lthat§r here'));
    bot._client.emit('action_bar', { text: '§#ff8800Mana 53/100' });
    bot._client.emit('set_title_time', { fadeIn: 5, stay: 40, fadeOut: 5 });
    bot._client.emit('set_title_subtitle', { text: 'Wave 3' });
    bot._client.emit('set_title_text', { text: '§6Raid' });
    bot.emit('bossBarCreated', bossBar('dragon', '§5Ender Dragon', 0.5, 'purple', 10));
    expect(api.lines.map((l) => [l.kind, l.text])).toEqual([
      ['chat', '<Bob> hi'],
      ['system', 'You cannot break that here'],
      ['action_bar', 'Mana 53/100'],
      ['title', 'Raid | Wave 3'],
      ['boss_bar', 'Ender Dragon'],
    ]);
    expect(api.screen).toEqual({ actionBar: 'Mana 53/100', title: 'Raid', subtitle: 'Wave 3', bossBars: [{ title: 'Ender Dragon', progress: 0.5, color: 'purple' }] });
    await until(() => received().length === 5);
    const [chat, system, bar] = received();
    expect(chat).toMatchObject({ kind: 'chat', text: '<Bob> hi' });
    expect(chat!.spans).toBeUndefined();
    expect(system!.spans).toMatchObject([{ text: 'You cannot break ', color: 0xff5555 }, { text: 'that', color: 0xff5555, bold: true }, { text: ' here' }]);
    expect(bar!.spans).toMatchObject([{ text: 'Mana 53/100', color: 0xff8800 }]);
  });

  it('logs a screen text that keeps coming only once, and an action bar told twice once', async () => {
    const { bot, api } = await start(SCREEN);
    // The packet, and the event a fix or a later mineflayer makes of the same packet.
    bot._client.emit('action_bar', { text: 'No permission' });
    bot.emit('messagestr', 'No permission', 'game_info', message('No permission'));
    await sleep(5);
    for (let i = 0; i < 20; i++) bot._client.emit('action_bar', { text: `Mana ${i}/100` });
    for (let i = 0; i < 20; i++) bot.emit('bossBarUpdated', bossBar('boss', 'Boss fight', i / 20));
    bot.emit('bossBarUpdated', bossBar('boss', 'Boss fight', 1));
    bot.emit('bossBarDeleted', bossBar('boss', 'Boss fight', 0));
    expect(api.lines.map((l) => l.text)).toEqual(['No permission', 'Mana 0/100', 'Boss fight']);
    expect(api.screen.bossBars).toEqual([]);
  });

  it('shows the screen to a player who spectates the bot, and takes it away after', async () => {
    const { b, bot } = await start(SCREEN);
    bot.emit('messagestr', '<Bob> before', 'chat', message('§7<Bob> before'));
    bot.emit('bossBarCreated', bossBar('dragon', '§5Ender Dragon', 0.5, 'purple', 10));
    await sleep(30);
    expect(e.mod.bossbars()).toHaveLength(0);
    expect(e.mod.objects(CONSOLE)).toHaveLength(0);

    spectate(true);
    await until(() => b.get<SpectateApi>('bridge:spectate')!.active);
    await until(() => e.mod.bossbars().length === 1);
    expect(e.mod.bossbars()[0]!.data).toMatchObject({ text: '[Alice] §5Ender Dragon', progress: 0.5, color: 'purple', overlay: 'notched_10' });
    await until(() => chatRows().length === 1);

    bot.emit('bossBarUpdated', bossBar('dragon', '§5Ender Dragon', 0.25, 'purple', 10));
    await until(() => e.mod.bossbars()[0]?.data.progress === 0.25);
    bot._client.emit('set_title_subtitle', { text: 'Wave {3}' });
    bot._client.emit('set_title_text', { text: '§6Raid' });
    bot._client.emit('action_bar', { text: '§cYou cannot break that' });
    bot.emit('messagestr', 'Use {braces}', 'system', message('§eUse {braces}'));
    await until(() => e.mod.effects().length === 2);
    const effects = e.mod.effects().map((x) => x.effect);
    expect(effects.find((x) => x.type === 'title')!.value).toMatchObject({ title: '§6Raid', subtitle: '[Alice] Wave {3}', fadeIn: 10, stay: 70, fadeOut: 20 });
    expect(effects.find((x) => x.type === 'actionbar')!.value).toEqual({ text: '§cYou cannot break that' });
    await until(() => chatRows().length === 2);
    expect(chatRows()).toEqual(['§7<Bob> before', '§eUse {{braces}}']);

    spectate(false);
    await until(() => e.mod.bossbars().length === 0);
    await until(() => e.mod.objects(CONSOLE).length === 0);
    // The title that was up is cleared.
    await until(() => e.mod.effects().length === 3);
    expect(e.mod.effects()[2]!.effect).toMatchObject({ type: 'title', value: { title: '' } });
    bot.emit('bossBarUpdated', bossBar('dragon', '§5Ender Dragon', 0.1, 'purple', 10));
    await sleep(30);
    expect(e.mod.bossbars()).toHaveLength(0);
  });

  it('passes screen changes on no more often than screenMs', async () => {
    const { bot } = await start({ flushMs: 0, screenMs: 50, lifecycle: false, chat: false });
    bot.emit('bossBarCreated', bossBar('boss', 'Boss', 0));
    spectate(true);
    await until(() => e.mod.bossbars().length === 1);
    let changes = 0;
    e.mod.on('objectChanged', () => changes++);
    for (let i = 1; i <= 40; i++) {
      bot.emit('bossBarUpdated', bossBar('boss', 'Boss', i / 40));
      bot._client.emit('action_bar', { text: `Mana ${i}/100` });
      await sleep(2);
    }
    await until(() => e.mod.bossbars()[0]?.data.progress === 1);
    expect(changes).toBeLessThan(10);
    expect(e.mod.effects().length).toBeLessThan(10);
  });

  it('sends the history again when the player attaches the bot', async () => {
    const { b, api } = await start({ flushMs: 0, lifecycle: false, keep: 150 });
    e.mod.setAttached(ALICE, false);
    await until(() => !b.state.attached);
    const before = batches().length;
    for (let i = 0; i < 160; i++) api.info(`line ${i}`);
    await sleep(30);
    expect(batches().length).toBe(before);
    e.mod.setAttached(ALICE, true);
    await until(() => batches().length === before + 2);
    const [first, second] = batches().slice(before);
    expect(first!.replay).toBe(true);
    expect(second!.replay).toBe(false);
    expect([first!.lines.length, second!.lines.length]).toEqual([100, 50]);
    expect(first!.lines[0]!.text).toBe('line 10');
    expect(second!.lines.at(-1)!.text).toBe('line 159');
  });

  it('turns hex colours into the closest colour code', () => {
    expect(legacyText(styledLegacy('§#ff5050red §lbold§r plain §#102030dark'))).toBe('§cred §r§c§lbold§r plain §0dark');
  });
});
