import { describe, expect, it } from 'vitest';
import { defineExtension, type ChatMessageInfo, type ExtensionContext } from '../src/index.ts';
import { ALICE, FakeBot, fixture, load, OVERWORLD, sleep, until, type Env } from './helpers.ts';

const BOB = 'bot:bob@play.example.net:25565';

let e: Env;
const { hub, attached } = fixture((f) => (e = f.e));

/** Collects chat messages a bot receives. */
function inbox(b: { chat: { on(event: 'message', fn: (text: string, info: ChatMessageInfo) => void): () => void } }): [string, ChatMessageInfo][] {
  const got: [string, ChatMessageInfo][] = [];
  b.chat.on('message', (text, info) => got.push([text, info]));
  return got;
}

describe('chat.print', () => {
  it('sends plain text as one span and styled spans with colours, hover and clicks', async () => {
    const b = await attached();
    b.chat.print('Mining done');
    b.chat.print([
      'Found ',
      { text: '12 diamonds', color: '#55ffff', bold: true, hover: 'at y -58' },
      { text: ' [go]', color: 0x00ff00, italic: true, click: { action: 'action', value: 'go' } },
      { text: ' [again]', click: { action: 'command', value: 'mine diamond_ore 12' } },
      { text: ' [say]', click: { action: 'suggest', value: '@alice ' } },
      { text: ' [copy]', click: { action: 'copy', value: '1 -58 3' } },
      { text: ' [link]', click: { action: 'url', value: 'https://example.net/a' } },
    ]);
    b.chat.print({ text: 'one span' });
    await until(() => e.mod.printed().length === 3);
    const [plain, styled, single] = e.mod.printed();
    expect(plain).toMatchObject({ owner: ALICE, spans: [{ text: 'Mining done' }] });
    expect(single!.spans).toMatchObject([{ text: 'one span' }]);
    expect(styled!.spans).toMatchObject([
      { text: 'Found ' },
      { text: '12 diamonds', color: 0xff55ffff, bold: true, hover: 'at y -58' },
      { text: ' [go]', color: 0xff00ff00, italic: true, click: { action: 'run_action', value: `${ALICE}/user:default/~action/go` } },
      { text: ' [again]', click: { action: 'run_command', value: 'mine diamond_ore 12' } },
      { text: ' [say]', click: { action: 'suggest', value: '@alice ' } },
      { text: ' [copy]', click: { action: 'copy', value: '1 -58 3' } },
      { text: ' [link]', click: { action: 'open_url', value: 'https://example.net/a' } },
    ]);
  });

  it('rejects malformed lines', async () => {
    const b = await attached();
    expect(() => b.chat.print('')).toThrow(/needs some text/);
    expect(() => b.chat.print([])).toThrow(/needs some text/);
    expect(() => b.chat.print({ text: 'x', color: '#nope' })).toThrow(/colour/);
    expect(() => b.chat.print({ text: 'x', click: { action: 'open_url' as 'copy', value: 'https://example.com' } })).toThrow(/unknown chat click action/);
    expect(() => b.chat.print({ text: 'x', click: { action: 'action', value: 'a/b' } })).toThrow(/without \//);
    expect(() => b.chat.print({ text: 'x', click: { action: 'copy', value: '' } })).toThrow(/non-empty value/);
    expect(() => b.chat.print({ text: 'x', click: { action: 'url', value: 'javascript:alert(1)' } })).toThrow(/http or https/);
  });

  it('drops lines printed while the session is not open instead of showing them late', async () => {
    const b = load(hub().plugin(), new FakeBot('Alice'));
    b.chat.print('early');
    await b.ready;
    // Only an attached bot's lines are shown.
    e.mod.setPlayerContext(OVERWORLD);
    await until(() => b.state.attached, 5000, 'attach');
    b.chat.print('now');
    await until(() => e.mod.printed().length === 1, 5000, 'print');
    await sleep(50);
    expect(e.mod.printed().map((p) => p.spans[0]!.text)).toEqual(['now']);
  });
});

describe('chat capture', () => {
  it('leaves lines without the prefix, escaped lines and unknown names to the server', async () => {
    const b = await attached();
    const got = inbox(b);
    expect(await e.mod.sendChat('hello everyone')).toEqual({ captured: false, text: 'hello everyone' });
    expect(await e.mod.sendChat('@@alice hi')).toEqual({ captured: false, text: '@alice hi' });
    expect(await e.mod.sendChat('@carol hi')).toEqual({ captured: false, text: '@carol hi' });
    expect(await e.mod.sendChat('@')).toEqual({ captured: false, text: '@' });
    await sleep(30);
    expect(got).toEqual([]);
  });

  it('delivers free text addressed to the bot by name, matched without case', async () => {
    const b = await attached();
    const got = inbox(b);
    expect(await e.mod.sendChat('@ALICE what should I build?')).toEqual({ captured: true, broadcast: false, deliveries: [{ owner: ALICE, routed: 'message', text: 'what should I build?' }] });
    await until(() => got.length === 1);
    expect(got[0]).toEqual(['what should I build?', { raw: '@ALICE what should I build?', broadcast: false }]);
  });

  it('keeps a line for a detached bot from the server chat, and runs nothing', async () => {
    const b = await attached();
    e.mod.setAttached('alice@play.example.net:25565', false);
    await until(() => !b.state.attached);
    expect(await e.mod.sendChat('@alice come')).toEqual({ captured: true, broadcast: false, deliveries: [], unknown: 'Alice is not in your world, or is not shown here' });
    expect(await e.mod.sendChat('@steve hi')).toEqual({ captured: false, text: '@steve hi' });
  });

  it('runs a line that parses as one of the bot commands, with positional arguments and defaults', async () => {
    const b = await attached();
    const got = inbox(b);
    const runs: Record<string, unknown>[] = [];
    b.commands.register('mine', {
      args: [
        { name: 'block', type: 'block-id' },
        { name: 'count', type: 'int', min: 1, default: 64 },
      ],
      run: (args) => {
        runs.push(args);
        return `mining ${String(args.count)}`;
      },
    });
    b.commands.register('say', { args: [{ name: 'text', type: 'greedy' }], run: (args) => String(args.text) });
    b.commands.register('goto', { args: [{ name: 'pos', type: 'block-pos' }, { name: 'sprint', type: 'bool', optional: true }], run: () => undefined });
    await until(() => e.mod.commands().length === 3);

    const r = await e.mod.sendChat('@alice mine iron_ore 32');
    expect(r).toEqual({ captured: true, broadcast: false, deliveries: [{ owner: ALICE, routed: 'command', command: `${ALICE}/user:default/~cmd/mine`, args: { block: 'minecraft:iron_ore', count: 32 }, result: { ok: true, message: 'mining 32' } }] });
    expect(await e.mod.sendChat('@alice mine minecraft:gold_ore')).toMatchObject({ deliveries: [{ routed: 'command', result: { ok: true, message: 'mining 64' } }] });
    expect(runs).toEqual([{ block: 'minecraft:iron_ore', count: 32 }, { block: 'minecraft:gold_ore', count: 64 }]);
    expect(await e.mod.sendChat('@alice say hello  there')).toMatchObject({ deliveries: [{ routed: 'command', args: { text: 'hello  there' }, result: { message: 'hello  there' } }] });
    expect(await e.mod.sendChat('@alice goto 1 64 -3 true')).toMatchObject({ deliveries: [{ routed: 'command', args: { pos: { x: 1, y: 64, z: -3 }, sprint: true } }] });

    expect(await e.mod.sendChat('@alice say "hello there"')).toMatchObject({ deliveries: [{ routed: 'command', args: { text: 'hello there' } }] });
    expect(await e.mod.sendChat('@alice MINE Iron_Ore 2')).toMatchObject({ deliveries: [{ routed: 'command', args: { block: 'minecraft:iron_ore', count: 2 } }] });
    expect(runs).toHaveLength(3);

    // A line that names a command but does not parse goes to the bot as chat, with the reason for the player.
    const failed = async (line: string, commandError: string): Promise<void> => {
      expect(await e.mod.sendChat(`@alice ${line}`)).toEqual({ captured: true, broadcast: false, deliveries: [{ owner: ALICE, routed: 'message', text: line }], commandError });
    };
    await failed('mine iron_ore many', "mine: 'count' must be a whole number, not 'many'");
    await failed('mine iron_ore 0', "mine: 'count' must be at least 1");
    await failed('mine', "mine: Missing argument 'block'");
    await failed('goto 1 2', "goto: 'pos' needs x, y and z");
    await failed('mine a 1 extra', 'mine: Too many arguments: extra');
    expect(runs).toHaveLength(3);
    expect(await e.mod.sendChat('@alice mining is fun')).toEqual({ captured: true, broadcast: false, deliveries: [{ owner: ALICE, routed: 'message', text: 'mining is fun' }] });
    await until(() => got.length === 6);
    expect(got.map((g) => g[0])).toEqual(['mine iron_ore many', 'mine iron_ore 0', 'mine', 'goto 1 2', 'mine a 1 extra', 'mining is fun']);
  });

  it('broadcasts @all: bots that have the command run it, otherwise every attached bot gets the text', async () => {
    const h = hub();
    const alice = await attached('Alice', h);
    const bob = await attached('Bob', h);
    const carol = load(h.plugin(), new FakeBot('Carol'));
    await until(() => carol.state.connected);
    e.mod.setAttached('carol@play.example.net:25565', false);
    await until(() => e.mod.session('carol@play.example.net:25565') !== undefined);
    const toAlice = inbox(alice);
    const toBob = inbox(bob);
    const toCarol = inbox(carol);
    bob.commands.register('come', { run: () => 'coming' });
    await until(() => e.mod.commands().length === 1);

    const r = await e.mod.sendChat('@all come');
    expect(r).toMatchObject({ captured: true, broadcast: true });
    expect(r.captured && r.deliveries).toEqual([
      { owner: BOB, routed: 'command', command: `${BOB}/user:default/~cmd/come`, args: {}, result: { ok: true, message: 'coming' } },
    ]);
    expect(await e.mod.sendChat('@all hello')).toMatchObject({ captured: true, broadcast: true });
    await until(() => toBob.length === 1 && toAlice.length === 1);
    expect(toAlice[0]).toEqual(['hello', { raw: '@all hello', broadcast: true }]);
    expect(toBob[0]).toEqual(['hello', { raw: '@all hello', broadcast: true }]);
    expect(toCarol).toEqual([]);
  });

  it('runs commands of a controller addressed by name, but leaves controllers out of @all', async () => {
    const h = hub();
    const alice = await attached('Alice', h);
    const ctl = h.controller({ name: 'Fleet' });
    await until(() => ctl.state.connected, 5000, 'controller session');
    ctl.commands.register('stop', { args: [{ name: 'bot', type: 'string' }], run: ({ bot }) => `stopped ${String(bot)}` });
    await until(() => e.mod.commands().length === 1, 5000, 'controller command');
    expect(await e.mod.sendChat('@fleet stop farmer1')).toMatchObject({ captured: true, broadcast: false, deliveries: [{ routed: 'command', result: { ok: true, message: 'stopped farmer1' } }] });
    const toAlice = inbox(alice);
    const r = await e.mod.sendChat('@all stop now');
    expect(r.captured && r.deliveries.map((d) => d.owner)).toEqual([ALICE]);
    await until(() => toAlice.length === 1);
  });

  it('tells a command how the player ran it: chat for @name lines, command for /bridge', async () => {
    const b = await attached();
    const sources: (string | undefined)[] = [];
    b.commands.register('ping', { run: (_args, ctx) => (sources.push(ctx.source), 'pong') });
    await until(() => e.mod.commands().length === 1);
    await e.mod.sendChat('@alice ping');
    await e.mod.runCommand('alice', 'ping');
    expect(sources).toEqual(['chat', 'command']);
  });

  it('leaves @all to the server while no bot is attached', async () => {
    expect(await e.mod.sendChat('@all anyone?')).toEqual({ captured: false, text: '@all anyone?' });
  });

  it('sends a crew order once to its controller, with the bots addressed by name, group or all', async () => {
    const h = hub();
    const alice = await attached('Alice', h);
    await attached('Bob', h);
    const ctl = h.controller({ name: 'Eco' });
    await until(() => ctl.state.connected, 5000, 'controller session');
    const seen: { where: unknown; bots: readonly string[] | undefined; source: string | undefined }[] = [];
    ctl.orders.register('go', { args: [{ name: 'where', type: 'string' }], run: ({ where }, ctx) => (seen.push({ where, bots: ctx.bots, source: ctx.source }), `going ${String(where)}`) });
    ctl.commands.register('status', { run: () => 'all good' });
    // Completion of an order hears who it is for, as running it does.
    ctl.orders.register('hold', { args: [{ name: 'item', type: 'string', complete: (_i, ctx) => (ctx.bots ?? ['nobody']).map((b) => `${b.toLowerCase()}_sword`) }], run: () => 'ok' });
    ctl.crew.set({ bots: ['Alice', 'Bob', 'Carol'], groups: { pair: ['alice', 'Bob'], solo: ['Bob'] } });
    expect(ctl.crew.get()).toEqual({ bots: ['Alice', 'Bob', 'Carol'], groups: { pair: ['alice', 'Bob'], solo: ['Bob'] } });
    await until(() => e.mod.addresses().some((a) => a.name === 'pair'), 5000, 'crew');
    expect(e.mod.addresses().map((a) => `${a.kind}:${a.name}`)).toEqual(['all:all', 'bot:Alice', 'bot:Bob', 'controller:Eco', 'group:pair', 'group:solo']);

    expect(await e.mod.sendChat('@alice go home')).toMatchObject({ deliveries: [{ owner: ctl.session.owner, routed: 'command', bots: ['Alice'], result: { ok: true, message: 'going home' } }] });
    expect(await e.mod.sendChat('@pair go farm')).toMatchObject({ broadcast: true, deliveries: [{ bots: ['Alice', 'Bob'] }] });
    const all = await e.mod.sendChat('@all go mine');
    expect(all.captured && all.deliveries).toHaveLength(1);
    expect(seen.map((x) => x.bots)).toEqual([['Alice'], ['Alice', 'Bob'], ['Alice', 'Bob']]);
    expect((await e.mod.complete('alice', 'hold ')).suggestions.map((x) => x.text)).toEqual(['alice_sword']);
    expect((await e.mod.complete('pair', 'hold ')).suggestions.map((x) => x.text)).toEqual(['alice_sword', 'bob_sword']);
    expect(seen[0]!.source).toBe('chat');
    expect(await e.mod.sendChat('@eco status')).toMatchObject({ deliveries: [{ routed: 'command', result: { message: 'all good' } }] });
    expect(await e.mod.sendChat('@eco go home')).toMatchObject({ deliveries: [{ owner: ctl.session.owner, routed: 'message', text: 'go home' }] });

    const toAlice = inbox(alice);
    expect(await e.mod.sendChat('@pair hello')).toMatchObject({ broadcast: true, deliveries: [{ owner: ALICE, routed: 'message' }, { routed: 'message' }] });
    await until(() => toAlice.length === 1);
    expect(await e.mod.runCommand('solo', 'go x')).toMatchObject({ status: 'invoked', result: { message: 'going x' } });
    expect(seen.at(-1)).toMatchObject({ bots: ['Bob'], source: 'command' });
    const key = e.mod.objects().find((o) => o.key.endsWith('/~order/go'))!.key;
    await expect(e.mod.invokeCommand(key, { where: 'x' })).rejects.toMatchObject({ code: 'invalid_message' });
    expect(await e.mod.invokeCommand(key, { where: 'y' }, undefined, [], ['Bob'])).toEqual({ ok: true, message: 'going y' });

    ctl.crew.clear();
    await until(() => !e.mod.addresses().some((a) => a.name === 'pair'), 5000, 'crew cleared');
    expect(await e.mod.sendChat('@alice go home')).toMatchObject({ deliveries: [{ owner: ALICE, routed: 'message' }] });
  });

  it('checks crew definitions', async () => {
    const h = hub();
    const ctl = h.controller({ name: 'Eco' });
    await until(() => ctl.state.connected, 5000, 'controller session');
    expect(() => ctl.crew.set({ bots: ['a b'] })).toThrow(/without spaces/);
    expect(() => ctl.crew.set({ bots: ['Alice'], groups: { all: ['Alice'] } })).toThrow(/cannot be named all/);
    expect(() => ctl.crew.set({ bots: ['Alice'], groups: { pair: ['Bob'] } })).toThrow(/not one of the crew's bots/);
    expect(() => ctl.crew.set({ bots: ['Alice'], members: [] } as never)).toThrow(/use bots/);
    expect(() => ctl.orders.register('a b', { run: () => {} })).toThrow(/order name/);
  });

  it('honours a custom prefix', async () => {
    await e.restart({ chatPrefix: '!' });
    const b = await attached();
    const got = inbox(b);
    expect(await e.mod.sendChat('@alice hi')).toEqual({ captured: false, text: '@alice hi' });
    expect(await e.mod.sendChat('!!alice hi')).toEqual({ captured: false, text: '!alice hi' });
    expect(await e.mod.sendChat('!alice hi')).toMatchObject({ captured: true });
    await until(() => got.length === 1);
  });
});

describe('chat listeners', () => {
  it('removes listeners with the remover and on unload', async () => {
    let ctx!: ExtensionContext;
    const b = await attached();
    await b.use(defineExtension({ id: 'acme:talk', version: '1.0.0', setup: (c) => void (ctx = c) }));
    const fromExt: string[] = [];
    const fromOff: string[] = [];
    const fromRemover: string[] = [];
    const all: string[] = [];
    ctx.chat.on('message', (t) => fromExt.push(t));
    const off = b.chat.on('message', (t) => fromOff.push(t));
    const remove = b.chat.on('message', (t) => fromRemover.push(t));
    b.chat.on('message', (t) => all.push(t));
    await e.mod.sendChat('@alice one');
    await until(() => all.length === 1);
    off();
    remove();
    await b.unload('acme:talk');
    ctx.chat.print('after unload');
    await e.mod.sendChat('@alice two');
    await until(() => all.length === 2);
    expect([fromExt, fromOff, fromRemover, all]).toEqual([['one'], ['one'], ['one'], ['one', 'two']]);
    expect(e.mod.printed()).toEqual([]);
  });

  it('reports a throwing listener as an error event and keeps delivering', async () => {
    const b = await attached();
    const errors: Error[] = [];
    b.on('error', (err) => errors.push(err));
    b.chat.on('message', () => {
      throw new Error('boom');
    });
    const got = inbox(b);
    await e.mod.sendChat('@alice hi');
    await until(() => got.length === 1 && errors.length === 1);
    expect(errors[0]!.message).toBe('boom');
  });
});
