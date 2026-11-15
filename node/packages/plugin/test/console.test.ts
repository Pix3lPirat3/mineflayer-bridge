import { WireReader } from '@mineflayer-bridge/protocol';
import { describe, expect, it } from 'vitest';
import { encodeExtension } from '../src/ext-wire.ts';
import { botConsole, ConsoleMessages, type BotBridge, type BotConsoleApi, type ConsoleLine } from '../src/index.ts';
import { ALICE, FakeBot, fixture, load, OVERWORLD, until, type Env } from './helpers.ts';

let e: Env;
const { hub } = fixture((f) => (e = f.e));

async function start(options: Parameters<typeof botConsole>[0] = { flushMs: 10 }): Promise<{ b: BotBridge; bot: FakeBot; api: BotConsoleApi }> {
  const bot = new FakeBot('Alice');
  const b = load(hub().plugin({ extensions: [botConsole(options)] }), bot);
  e.mod.setPlayerContext(OVERWORLD);
  await until(() => b.state.attached && b.state.worldMatched);
  return { b, bot, api: b.get<BotConsoleApi>('bridge:console')! };
}

/** Every line the mod received, decoded with the generated codec. */
function received(): ConsoleLine[] {
  return e.mod
    .extensionMessages(ALICE)
    .filter((m) => m.type === 'bridge:console.lines')
    .flatMap((m) => ConsoleMessages['bridge:console.lines'].codec.read(new WireReader(m.payload)).lines);
}

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
    expect(b2).toMatchObject({ level: 'warn', text: 'Low food', pos: { x: 10, y: 64, z: -3 } });
    expect(c!.text.length).toBe(1000);
    expect(e.mod.extensionMessages(ALICE).length).toBe(1);
    expect(api.lines.map((l) => l.level)).toEqual(['info', 'warn', 'debug']);
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
});
