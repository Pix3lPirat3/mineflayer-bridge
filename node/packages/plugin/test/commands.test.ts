import { describe, expect, expectTypeOf, it } from 'vitest';
import type { MockModOptions, MockSelector } from '@mineflayer-bridge/mock-mod';
import { BridgeError, defineExtension, type BotBridge, type CompletionContext, type InvocationContext, type Vec3Value } from '../src/index.ts';
import { ALICE, ALICE_PREFIX, fixture, OVERWORLD, sleep, until, type Env } from './helpers.ts';

const MINE = `${ALICE_PREFIX}/~cmd/mine`;

let e: Env;
const { hub, attached } = fixture((f) => (e = f.e));

async function restart(options: MockModOptions): Promise<void> {
  await e.restart(options);
  e.mod.setPlayerContext(OVERWORLD);
}

interface Run {
  path: readonly string[];
  args: Record<string, unknown>;
}

/** An example tree: `mine start <block> [count]`, `mine stop`, `mine give <player> <item>`. */
function registerMine(b: BotBridge, runs: Run[], items: (input: string, ctx: CompletionContext) => string[] = () => []): () => void {
  const record = (args: Record<string, unknown>, ctx: InvocationContext): string => {
    runs.push({ path: ctx.path, args });
    return ctx.path.join(' ');
  };
  return b.commands.register('mine', {
    description: 'Mining jobs',
    aliases: ['m'],
    children: {
      start: {
        description: 'Start mining',
        aliases: ['go'],
        args: [
          { name: 'block', type: 'block-id', complete: 'block-ids' },
          { name: 'count', type: 'int', min: 1, default: 64 },
        ],
        run: record,
      },
      stop: { description: 'Stop mining', aliases: ['halt'], run: record },
      give: {
        args: [
          { name: 'player', type: 'player', complete: 'players' },
          { name: 'item', type: 'item-id', complete: items },
        ],
        run: record,
      },
    },
  });
}

describe('command trees', () => {
  it('declares the tree with aliases, runnable flags and completion specs', async () => {
    const b = await attached();
    registerMine(b, [], () => []);
    b.commands.register('scan', {
      args: [
        { name: 'targets', type: 'entity-list', complete: { source: 'entities', near: 'bot', radius: 16, filter: { types: ['minecraft:zombie'], tags: ['c:undead'] }, limit: 5 } },
        { name: 'ore', type: 'block-id', optional: true, complete: { source: 'blocks', filter: { ids: ['minecraft:iron_ore'] } } },
      ],
      run: () => undefined,
    });
    await until(() => e.mod.commands().length === 2);
    const mine = e.mod.commands().find((c) => c.id === 'mine')!;
    expect(mine.data).toMatchObject({ name: 'mine', description: 'Mining jobs', aliases: ['m'], args: [], runnable: false });
    const [start, stop, give] = mine.data.children!;
    expect(start).toMatchObject({ name: 'start', aliases: ['go'], description: 'Start mining', runnable: true, children: undefined });
    expect(start!.args).toMatchObject([
      { name: 'block', type: 'block_id', optional: false, completion: { source: 'block_ids', near: 'player', limit: 20 } },
      { name: 'count', type: 'int', optional: true, defaultValue: 64, min: 1, completion: undefined },
    ]);
    expect(stop).toMatchObject({ name: 'stop', aliases: ['halt'], args: [], runnable: true });
    expect(give!.args[1]).toMatchObject({ name: 'item', type: 'item_id', completion: { source: 'dynamic', limit: 50 } });
    const scan = e.mod.commands().find((c) => c.id === 'scan')!;
    expect(scan.data).toMatchObject({ runnable: true, aliases: undefined, children: undefined });
    expect(scan.data.args[0]).toMatchObject({
      type: 'entity_list',
      completion: { source: 'entities', near: 'bot', radius: 16, filter: { type: 'entity', ids: ['minecraft:zombie'], tags: ['c:undead'] }, limit: 5 },
    });
    expect(scan.data.args[1]!.completion).toMatchObject({ source: 'blocks', filter: { type: 'block', ids: ['minecraft:iron_ore'] } });
  });

  it('routes invocations by path to the node handler, with defaults and aliases', async () => {
    const b = await attached();
    const runs: Run[] = [];
    registerMine(b, runs);
    await until(() => e.mod.commands().length === 1);

    expect(await e.mod.runCommand('alice', 'mine start iron_ore')).toEqual({
      status: 'invoked',
      command: MINE,
      path: ['start'],
      args: { block: 'minecraft:iron_ore', count: 64 },
      result: { ok: true, message: 'start' },
    });
    expect(await e.mod.runCommand('Alice', 'm go gold_ore 3')).toMatchObject({ status: 'invoked', path: ['start'], result: { ok: true } });
    expect(await e.mod.runCommand('alice', 'MINE Halt')).toMatchObject({ status: 'invoked', path: ['stop'] });
    expect(await e.mod.runCommand('alice', 'mine give Steve diamond')).toMatchObject({ status: 'invoked', path: ['give'], args: { player: 'Steve', item: 'minecraft:diamond' } });
    expect(runs).toEqual([
      { path: ['start'], args: { block: 'minecraft:iron_ore', count: 64 } },
      { path: ['start'], args: { block: 'minecraft:gold_ore', count: 3 } },
      { path: ['stop'], args: {} },
      { path: ['give'], args: { player: 'Steve', item: 'minecraft:diamond' } },
    ]);

    expect(await e.mod.sendChat('@alice m stop')).toMatchObject({ deliveries: [{ routed: 'command', command: MINE, path: ['stop'], result: { ok: true, message: 'stop' } }] });
    expect(await e.mod.sendChat('@alice mine')).toMatchObject({ deliveries: [{ routed: 'usage', path: [], usage: ['mine <start|stop|give> - Mining jobs', '  mine start <block:block_id> [count:int=64] - Start mining', '  mine stop - Stop mining', '  mine give <player:player> <item:item_id>'] }] });
    expect(runs).toHaveLength(5);
  });

  it('lists children of a grouping node and reports parse errors with token ranges, sending nothing', async () => {
    const b = await attached();
    const runs: Run[] = [];
    registerMine(b, runs);
    await until(() => e.mod.commands().length === 1);
    let invoked = 0;
    e.mod.on('message', (m) => {
      if (m.type === 'invocation.result') invoked++;
    });

    expect(await e.mod.runCommand('alice', 'mine')).toEqual({ status: 'usage', command: MINE, path: [], usage: ['mine <start|stop|give> - Mining jobs', '  mine start <block:block_id> [count:int=64] - Start mining', '  mine stop - Stop mining', '  mine give <player:player> <item:item_id>'] });
    expect(await e.mod.runCommand('alice', 'mine dig')).toEqual({ status: 'error', command: MINE, path: [], error: "Unknown subcommand 'dig' of 'mine'; expected start, stop, give", start: 5, end: 8 });
    expect(await e.mod.runCommand('alice', 'mine start iron_ore lots')).toEqual({ status: 'error', command: MINE, path: ['start'], error: "'count' must be a whole number, not 'lots'", start: 20, end: 24 });
    expect(await e.mod.runCommand('alice', 'mine start iron_ore 0')).toMatchObject({ error: "'count' must be at least 1", start: 20, end: 21 });
    expect(await e.mod.runCommand('alice', 'mine start')).toMatchObject({ error: "Missing argument 'block'", start: 10, end: 10 });
    expect(await e.mod.runCommand('alice', 'mine stop now please')).toMatchObject({ error: 'Too many arguments: now please', start: 10, end: 20 });
    expect(await e.mod.runCommand('alice', 'dig')).toEqual({ status: 'error', command: undefined, path: [], error: "Alice has no command 'dig'", start: 0, end: 3 });
    await expect(e.mod.runCommand('nobody', 'mine')).rejects.toThrow(/no connected bot/);
    await sleep(50);
    expect(invoked).toBe(0);
    expect(runs).toEqual([]);
  });

  it('answers ok false for a path the tree does not have', async () => {
    const b = await attached();
    registerMine(b, []);
    await until(() => e.mod.commands().length === 1);
    expect(await e.mod.invokeCommand(MINE, {}, 2000, ['dig'])).toEqual({ ok: false, message: 'mine has no subcommand dig' });
    expect(await e.mod.invokeCommand(MINE, {}, 2000)).toEqual({ ok: false, message: 'mine: choose one of start, stop, give' });
    expect(await e.mod.invokeCommand(MINE, {}, 2000, ['halt'])).toEqual({ ok: true, message: 'stop' });
  });

  it('parses optional, default and greedy arguments and resolves selectors through the test hook', async () => {
    const seen: MockSelector[] = [];
    await restart({
      resolveSelector: (s) => {
        seen.push(s);
        if (s.kind === 'nearest') return [42];
        if (s.kind === 'all') return [42, 43];
        if (s.kind === 'looked') return [{ x: 1.5, y: 64.2, z: -3.7 }];
        return [];
      },
    });
    const b = await attached();
    const got: Record<string, unknown>[] = [];
    b.commands.register('act', {
      children: {
        hit: { args: [{ name: 'target', type: 'entity' }, { name: 'times', type: 'int', default: 1 }], run: (a) => void got.push(a) },
        all: { args: [{ name: 'targets', type: 'entity-list' }], run: (a) => void got.push(a) },
        go: { args: [{ name: 'pos', type: 'block-pos' }, { name: 'sprint', type: 'bool', optional: true }], run: (a) => void got.push(a) },
        tell: { args: [{ name: 'who', type: 'player' }, { name: 'text', type: 'greedy', optional: true }], run: (a) => void got.push(a) },
      },
    });
    await until(() => e.mod.commands().length === 1);

    expect(await e.mod.runCommand('alice', 'act hit @nearest[type=minecraft:zombie,distance=..8] 2')).toMatchObject({ status: 'invoked', args: { target: 42, times: 2 } });
    expect(seen[0]).toMatchObject({ kind: 'nearest', options: { type: 'minecraft:zombie', distance: '..8' }, bot: ALICE, command: `${ALICE_PREFIX}/~cmd/act`, arg: 'target', type: 'entity' });
    expect(await e.mod.runCommand('alice', 'act hit 7')).toMatchObject({ status: 'invoked', args: { target: 7 } });
    expect(await e.mod.runCommand('alice', 'act all @all[type=minecraft:cow]')).toMatchObject({ args: { targets: [42, 43] } });
    expect(await e.mod.runCommand('alice', 'act all 1,2,3')).toMatchObject({ args: { targets: [1, 2, 3] } });
    expect(await e.mod.runCommand('alice', 'act go @looked true')).toMatchObject({ args: { pos: { x: 1, y: 64, z: -4 }, sprint: true } });
    expect(await e.mod.runCommand('alice', 'act go 1 2 3')).toMatchObject({ args: { pos: { x: 1, y: 2, z: 3 } } });
    // A player argument is a name as typed; only entities and positions take selectors.
    expect(await e.mod.runCommand('alice', 'act tell @me hello   there')).toMatchObject({ args: { who: '@me', text: 'hello   there' } });
    expect(await e.mod.runCommand('alice', 'act tell "Steve Jr" "hello there"')).toMatchObject({ args: { who: 'Steve Jr', text: 'hello there' } });
    expect(await e.mod.runCommand('alice', 'act all 7,@all[type=cow],42')).toMatchObject({ args: { targets: [7, 42, 43] } });
    expect(await e.mod.runCommand('alice', 'act tell Bob')).toMatchObject({ status: 'invoked', args: { who: 'Bob' } });
    expect(got).toEqual([
      { target: 42, times: 2 },
      { target: 7, times: 1 },
      { targets: [42, 43] },
      { targets: [1, 2, 3] },
      { pos: { x: 1, y: 64, z: -4 }, sprint: true },
      { pos: { x: 1, y: 2, z: 3 } },
      { who: '@me', text: 'hello   there' },
      { who: 'Steve Jr', text: 'hello there' },
      { targets: [7, 42, 43] },
      { who: 'Bob' },
    ]);

    expect(await e.mod.runCommand('alice', 'act hit @all')).toMatchObject({ status: 'error', error: "'target' takes one entity; use @nearest instead of @all", start: 8, end: 12 });
    expect(await e.mod.runCommand('alice', 'act hit @self')).toMatchObject({ status: 'error', error: "'target': no entity matches @self" });
    expect(await e.mod.runCommand('alice', 'act hit @someone')).toMatchObject({ status: 'error', error: "Unknown selector '@someone'; use @me, @self, @looked, @nearest or @all" });
    expect(await e.mod.runCommand('alice', 'act hit zombie')).toMatchObject({ status: 'error', error: "'target': no loaded entity or player 'zombie'" });
    expect(await e.mod.runCommand('alice', 'act go 1 2')).toMatchObject({ status: 'error', error: "'pos' needs x, y and z", start: 7, end: 10 });
  });

  it('runs and completes command lines addressed to a controller session by its name', async () => {
    const ctl = hub().controller({ name: 'Fleet' });
    ctl.commands.register('fleet', {
      children: {
        stop: { args: [{ name: 'bot', type: 'string', complete: (input) => ['all', 'farmer1'].filter((n) => n.startsWith(input)) }], run: ({ bot }) => `stopped ${String(bot)}` },
      },
    });
    await until(() => e.mod.commands().length === 1, 5000, 'controller command');
    expect(await e.mod.runCommand('fleet', 'fleet stop farmer1')).toMatchObject({ status: 'invoked', result: { ok: true, message: 'stopped farmer1' } });
    expect((await e.mod.complete('Fleet', 'fleet stop f')).suggestions.map((s) => s.text)).toEqual(['farmer1']);
  });

  it('refuses malformed trees before anything is sent', async () => {
    const b = await attached();
    const run = (): undefined => undefined;
    const bad = (def: Parameters<BotBridge['commands']['register']>[1]): BridgeError => {
      try {
        b.commands.register('bad', def);
      } catch (err) {
        expect(err).toBeInstanceOf(BridgeError);
        return err as BridgeError;
      }
      throw new Error('expected a throw');
    };
    expect(bad({}).message).toMatch(/needs a run function or children/);
    expect(bad({ children: { a: {} } }).message).toMatch(/bad a needs a run function or children/);
    expect(bad({ children: { 'two words': { run } } }).code).toBe('invalid_message');
    expect(bad({ children: { a: { run, aliases: ['b'] }, b: { run } } }).message).toMatch(/b names two subcommands/);
    expect(bad({ aliases: ['bad'], run }).message).toMatch(/aliases must be unique/);
    expect(bad({ args: [{ name: 'a', type: 'int', optional: true }, { name: 'b', type: 'int' }], run }).message).toMatch(/follows an optional one/);
    expect(bad({ args: [{ name: 'a', type: 'greedy' }, { name: 'b', type: 'int', optional: true }], run }).message).toMatch(/must be last/);
    expect(bad({ args: [{ name: 'a', type: 'int' }, { name: 'a', type: 'int' }], run }).message).toMatch(/duplicate argument/);
    expect(bad({ args: [{ name: 'a', type: 'string', complete: 'nonsense' as 'bots' }], run }).message).toMatch(/unknown completion source/);
    expect(bad({ args: [{ name: 'a', type: 'string', complete: 'choices' }], run }).message).toMatch(/needs choices/);
    expect(bad({ args: [{ name: 'a', type: 'string', complete: { source: 'entities', limit: 51 } }], run }).message).toMatch(/limit/);
    expect(bad({ args: [{ name: 'a', type: 'string', complete: { source: 'entities', filter: { ids: ['x'], types: ['y'] } } }], run }).message).toMatch(/not both/);
    let deep: { run: () => undefined; children?: Record<string, unknown> } = { run };
    for (let i = 0; i < 8; i++) deep = { run, children: { n: deep } };
    expect(bad(deep as Parameters<BotBridge['commands']['register']>[1]).message).toMatch(/deeper than 8/);
    await sleep(50);
    expect(e.mod.commands()).toEqual([]);
  });

  it('infers argument value types for run', () => {
    const def = {
      args: [
        { name: 'block', type: 'block-id' },
        { name: 'count', type: 'int', default: 64 },
        { name: 'mode', type: 'enum', choices: ['fast', 'safe'], optional: true },
        { name: 'at', type: 'block-pos' },
        { name: 'targets', type: 'entity-list' },
      ],
    } as const;
    type Args = Parameters<NonNullable<import('../src/index.ts').CommandDefinition<typeof def.args>['run']>>[0];
    expectTypeOf<Args>().toEqualTypeOf<{ block: string; count: number; mode: 'fast' | 'safe' | undefined; at: Vec3Value; targets: number[] }>();
  });
});

describe('completion', () => {
  it('suggests command, subcommand and alias names, choices and booleans', async () => {
    const b = await attached();
    registerMine(b, []);
    b.commands.register('mode', { args: [{ name: 'm', type: 'enum', choices: ['fast', 'safe', 'silent'] }, { name: 'loud', type: 'bool', optional: true }], run: () => undefined });
    await until(() => e.mod.commands().length === 2);

    expect(await e.mod.complete('alice', 'm')).toMatchObject({ start: 0, suggestions: [{ text: 'mine', tooltip: 'Mining jobs' }, { text: 'mode' }], source: undefined });
    expect(await e.mod.complete('alice', 'mine ')).toMatchObject({ start: 5, suggestions: [{ text: 'start', tooltip: 'Start mining' }, { text: 'stop' }, { text: 'give' }] });
    expect((await e.mod.complete('alice', 'mine h')).suggestions).toEqual([{ text: 'halt', tooltip: 'alias of stop' }]);
    expect(await e.mod.complete('alice', 'mode s')).toEqual({ start: 5, suggestions: [{ text: 'safe' }, { text: 'silent' }], source: 'choices', dynamic: undefined, error: undefined });
    expect(await e.mod.complete('alice', 'mode fast ')).toMatchObject({ suggestions: [{ text: 'true' }, { text: 'false' }], source: 'bool' });
    expect(await e.mod.complete('alice', 'mode fast true ')).toMatchObject({ suggestions: [], error: undefined });
  });

  it('serves client-only sources from the configured stubs, filtered by prefix and cut to the limit', async () => {
    await restart({ completionStubs: { block_ids: ['minecraft:iron_ore', 'minecraft:gold_ore', 'minecraft:iron_block'], players: ['Steve', { text: 'Sam', tooltip: 'op' }] } });
    const b = await attached();
    registerMine(b, []);
    b.commands.register('pick', { args: [{ name: 'ore', type: 'block-id', complete: { source: 'block-ids', limit: 1 } }], run: () => undefined });
    await until(() => e.mod.commands().length === 2);
    expect(await e.mod.complete('alice', 'mine start iron')).toMatchObject({ start: 11, suggestions: [{ text: 'minecraft:iron_ore' }, { text: 'minecraft:iron_block' }], source: 'block_ids' });
    expect(await e.mod.complete('alice', 'mine give s')).toMatchObject({ suggestions: [{ text: 'Steve' }, { text: 'Sam', tooltip: 'op' }], source: 'players' });
    expect((await e.mod.complete('alice', 'pick ')).suggestions).toEqual([{ text: 'minecraft:iron_ore' }]);
  });

  it('answers dynamic completion with the earlier arguments, normalising strings and cutting at 50', async () => {
    const b = await attached();
    const calls: { input: string; ctx: CompletionContext }[] = [];
    registerMine(b, [], (input, ctx) => {
      calls.push({ input, ctx });
      return Array.from({ length: 60 }, (_, i) => `${input}${i}`);
    });
    b.commands.register('pick', {
      args: [{ name: 'what', type: 'string', complete: async () => [{ text: 'a', display: 'Apple', tooltip: 'fruit' }, 'b'] }],
      run: () => undefined,
    });
    await until(() => e.mod.commands().length === 2);

    const r = await e.mod.complete('alice', 'mine give Steve dia');
    expect(r).toMatchObject({ start: 16, source: 'dynamic', dynamic: 'answered' });
    expect(r.suggestions).toHaveLength(50);
    expect(r.suggestions[0]).toEqual({ text: 'dia0' });
    expect(calls).toEqual([{ input: 'dia', ctx: { args: { player: 'Steve' }, path: ['give'], arg: 'item', key: MINE } }]);
    expect((await e.mod.complete('alice', 'pick ')).suggestions).toEqual([{ text: 'a', display: 'Apple', tooltip: 'fruit' }, { text: 'b' }]);
  });

  it('caches dynamic answers per input prefix until the command changes', async () => {
    const b = await attached();
    let n = 0;
    const register = (description: string): void => {
      b.commands.register('pick', {
        description,
        args: [{ name: 'who', type: 'player' }, { name: 'item', type: 'item-id', complete: (input) => [`${input}-${++n}`] }],
        run: () => undefined,
      });
    };
    register('one');
    await until(() => e.mod.commands().length === 1);
    expect(await e.mod.complete('alice', 'pick Steve d')).toMatchObject({ dynamic: 'answered', suggestions: [{ text: 'd-1' }] });
    expect(await e.mod.complete('alice', 'pick Steve d')).toMatchObject({ dynamic: 'cached', suggestions: [{ text: 'd-1' }] });
    expect(await e.mod.complete('alice', 'pick Steve di')).toMatchObject({ dynamic: 'answered', suggestions: [{ text: 'di-2' }] });
    expect(await e.mod.complete('alice', 'pick Bob d')).toMatchObject({ dynamic: 'answered', suggestions: [{ text: 'd-3' }] });
    register('two');
    await until(() => e.mod.commands()[0]?.data.description === 'two');
    expect(await e.mod.complete('alice', 'pick Steve d')).toMatchObject({ dynamic: 'answered', suggestions: [{ text: 'd-4' }] });
  });

  it('asks the bot again once a cached answer is old, so changed items show', async () => {
    await restart({ completionCacheMs: 50 });
    const b = await attached();
    let items = ['dirt', 'diamond'];
    b.commands.register('drop', {
      args: [{ name: 'item', type: 'item-id', complete: (input) => items.filter((i) => i.startsWith(input)) }],
      run: () => undefined,
    });
    await until(() => e.mod.commands().length === 1);
    expect(await e.mod.complete('alice', 'drop d')).toMatchObject({ dynamic: 'answered', suggestions: [{ text: 'dirt' }, { text: 'diamond' }] });
    items = ['diamond'];
    expect(await e.mod.complete('alice', 'drop d')).toMatchObject({ dynamic: 'cached', suggestions: [{ text: 'dirt' }, { text: 'diamond' }] });
    await sleep(60);
    expect(await e.mod.complete('alice', 'drop d')).toMatchObject({ dynamic: 'answered', suggestions: [{ text: 'diamond' }] });
  });

  it('answers an empty list for a throwing or malformed completion and reports it as an error event', async () => {
    const b = await attached();
    const errors: Error[] = [];
    b.on('error', (err) => errors.push(err));
    b.commands.register('pick', {
      children: {
        boom: {
          args: [
            {
              name: 'x',
              type: 'string',
              complete: () => {
                throw new Error('no idea');
              },
            },
          ],
          run: () => undefined,
        },
        later: { args: [{ name: 'x', type: 'string', complete: async () => Promise.reject(new BridgeError('timeout', 'slow backend')) }], run: () => undefined },
        weird: { args: [{ name: 'x', type: 'string', complete: () => [{ text: '' }] }], run: () => undefined },
      },
    });
    await until(() => e.mod.commands().length === 1);
    expect(await e.mod.complete('alice', 'pick boom ')).toMatchObject({ suggestions: [], dynamic: 'answered' });
    expect(await e.mod.complete('alice', 'pick later ')).toMatchObject({ suggestions: [], dynamic: 'answered' });
    expect(await e.mod.complete('alice', 'pick weird ')).toMatchObject({ suggestions: [], dynamic: 'answered' });
    await until(() => errors.length === 3);
    expect(errors.map((x) => [(x as BridgeError).code, x.message])).toEqual([
      ['internal', 'no idea'],
      ['timeout', 'slow backend'],
      ['invalid_message', 'completion of pick weird x: suggestion text must be a non-empty string'],
    ]);
  });

  it('gives up after the deadline and drops the late answer instead of caching it', async () => {
    await restart({ completionTimeoutMs: 80 });
    const b = await attached();
    let calls = 0;
    registerMine(b, [], () => {
      calls++;
      return [`late-${calls}`];
    });
    b.commands.register('slow', {
      args: [
        {
          name: 'x',
          type: 'string',
          complete: async (input) => {
            calls++;
            await sleep(input === 'wait' ? 250 : 0);
            return [`${input}-${calls}`];
          },
        },
      ],
      run: () => undefined,
    });
    await until(() => e.mod.commands().length === 2);
    const started = Date.now();
    expect(await e.mod.complete('alice', 'slow wait')).toMatchObject({ suggestions: [], dynamic: 'timeout' });
    expect(Date.now() - started).toBeLessThan(240);
    await sleep(300);
    expect(await e.mod.complete('alice', 'slow wait')).toMatchObject({ dynamic: 'timeout' });
    expect(calls).toBe(2);
    expect(await e.mod.complete('alice', 'slow now')).toMatchObject({ suggestions: [{ text: 'now-3' }], dynamic: 'answered' });
  });

  it('stops completing after an earlier argument that does not parse', async () => {
    const b = await attached();
    let calls = 0;
    b.commands.register('give', {
      args: [
        { name: 'count', type: 'int' },
        {
          name: 'item',
          type: 'item-id',
          complete: () => {
            calls++;
            return ['x'];
          },
        },
      ],
      run: () => undefined,
    });
    await until(() => e.mod.commands().length === 1);
    expect(await e.mod.complete('alice', 'give lots ')).toEqual({ start: 10, suggestions: [], source: undefined, dynamic: undefined, error: "'count' must be a whole number, not 'lots'" });
    expect(await e.mod.complete('alice', 'give 3 ')).toMatchObject({ suggestions: [{ text: 'x' }], dynamic: 'answered' });
    expect(calls).toBe(1);
  });
});

describe('lifecycle', () => {
  it('stops answering completions once the extension that declared the command unloads', async () => {
    const b = await attached();
    let calls = 0;
    const ext = defineExtension({
      id: 'acme:miner',
      version: '1.0.0',
      setup(c) {
        c.commands.register('mine', {
          children: {
            give: {
              args: [
                {
                  name: 'item',
                  type: 'item-id',
                  complete: () => {
                    calls++;
                    return ['diamond'];
                  },
                },
              ],
              run: () => 'given',
            },
          },
        });
      },
    });
    await b.use(ext);
    await until(() => e.mod.commands().length === 1);
    expect(await e.mod.complete('alice', 'mine give d')).toMatchObject({ suggestions: [{ text: 'diamond' }] });
    const key = e.mod.commands()[0]!.key;
    const unloaded = b.unload('acme:miner');
    // The route goes at once; the mock still holds the object until the removal arrives.
    const late = e.mod.complete('alice', 'mine give x');
    await unloaded;
    expect(await late).toMatchObject({ suggestions: [], dynamic: 'answered' });
    await until(() => e.mod.commands().length === 0);
    expect(await e.mod.complete('alice', 'mine give d')).toMatchObject({ suggestions: [], error: 'unknown command mine' });
    expect(() => e.mod.invokeCommand(key)).toThrow(/no core:command/);
    expect(calls).toBe(1);
  });

  it('replays the tree after the mod restarts and keeps routing and completing', async () => {
    const b = await attached();
    const runs: Run[] = [];
    registerMine(b, runs, (input) => [`${input}!`]);
    await until(() => e.mod.commands().length === 1);
    const before = e.mod.commands()[0]!.data;
    await restart({});
    await until(() => e.mod.commands().length === 1, 5000, 'replay');
    await until(() => b.state.attached);
    expect(e.mod.commands()[0]!.data).toEqual(before);
    expect(await e.mod.runCommand('alice', 'mine go iron_ore 2')).toMatchObject({ status: 'invoked', result: { ok: true, message: 'start' } });
    expect(await e.mod.complete('alice', 'mine give Steve d')).toMatchObject({ suggestions: [{ text: 'd!' }], dynamic: 'answered' });
    expect(runs).toEqual([{ path: ['start'], args: { block: 'minecraft:iron_ore', count: 2 } }]);
  });

  it('keeps completing across a dropped connection that resumes', async () => {
    const b = await attached();
    registerMine(b, [], (input) => [`${input}?`]);
    await until(() => e.mod.commands().length === 1);
    const resumed = e.mod.waitFor('sessionResumed');
    e.mod.disconnect(e.mod.session('alice@play.example.net:25565')!);
    await resumed;
    await until(() => b.state.connected);
    expect(await e.mod.complete('alice', 'mine give Steve x')).toMatchObject({ suggestions: [{ text: 'x?' }] });
  });
});

describe('the command quota', () => {
  it('takes a vocabulary of more than 256 commands', async () => {
    const b = await attached();
    for (let i = 0; i < 300; i++) b.commands.register(`word${i}`, { run: () => undefined });
    await until(() => e.mod.commands().length === 300, 10000, '300 commands');
  });

  it('refuses the commands past the session quota and keeps the rest', async () => {
    await restart({ quotas: { commands: 3 } });
    const b = await attached();
    for (let i = 0; i < 5; i++) b.commands.register(`word${i}`, { run: () => undefined });
    await until(() => e.mod.commands().length === 3, 5000, 'three commands');
    await sleep(200);
    expect(e.mod.commands()).toHaveLength(3);
  });
});
