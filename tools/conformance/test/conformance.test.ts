import { EventEmitter } from 'node:events';
import { join } from 'node:path';
import { decodeKind, Kinds, type KindName } from '@mineflayer-bridge/protocol';
import { BridgeError, createBridge, LOCAL_DEFAULT_PERMISSIONS, type BotBridge, type BridgeController, type BridgeHub, type Logger } from 'mineflayer-bridge';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import {
  JAVA_FEATURES,
  javaDriver,
  javaUnavailable,
  MOCK_FEATURES,
  mockDriver,
  type DriverFeature,
  type DriverInput,
  type DriverObject,
  type DriverOptions,
  type ModDriver,
} from '../src/drivers.ts';

const SERVER = 'play.example.net:25565';
const OVERWORLD = { server: SERVER, dimension: 'minecraft:overworld' };
const ALICE = `bot:alice@${SERVER}`;
const PREFIX = `${ALICE}/user:default`;

/** A stand-in mineflayer bot: the fields the plugin reads. */
class FakeBot extends EventEmitter {
  entity = { position: { x: 0, y: 64, z: 0 }, id: 1 };
  game = { dimension: 'overworld' };
  player = null;
  bridge?: BotBridge;

  constructor(readonly username = 'Alice') {
    super();
  }
}

async function until<T>(cond: () => Promise<T | undefined | false | null>, what: string, timeoutMs = 8000): Promise<T> {
  const end = Date.now() + timeoutMs;
  for (;;) {
    const v = await cond();
    if (v !== undefined && v !== null && v !== false) return v;
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 10));
  }
}

function decoded(o: DriverObject | undefined): unknown {
  if (o?.data === undefined) return undefined;
  return decodeKind(Kinds[o.kind as KindName] as Parameters<typeof decodeKind>[0], o.data);
}

/** The `BridgeError` a promise rejects with. */
async function rejection(p: Promise<unknown>): Promise<BridgeError> {
  try {
    await p;
  } catch (err) {
    expect(err).toBeInstanceOf(BridgeError);
    return err as BridgeError;
  }
  throw new Error('expected a rejection');
}

interface ConformanceTarget {
  name: string;
  start: (options?: DriverOptions) => Promise<ModDriver>;
  features: ReadonlySet<DriverFeature>;
}

const unavailable = javaUnavailable();
const targets: ConformanceTarget[] = [{ name: 'mock', start: mockDriver, features: MOCK_FEATURES }];
if (unavailable === undefined) targets.push({ name: 'java', start: javaDriver, features: JAVA_FEATURES });
else if (process.env.CONFORMANCE_JAVA === 'required') throw new Error(`conformance: Java target required but ${unavailable}`);
else console.warn(`conformance: Java target skipped; ${unavailable}`);

describe.each(targets)('conformance against $name', ({ start, features }) => {
  let mod: ModDriver;
  let hub: BridgeHub;
  let b: BotBridge;
  /** What the plugin logged at warn level, such as writes the mod rejected. */
  let warnings: string[];
  /** How the next tests start the mod; a nested suite sets it with {@link startWith}. */
  let options: DriverOptions = {};

  /** True when the target lacks one of the operations, so the suite must be skipped on it. */
  const lacks = (...needed: DriverFeature[]): boolean => needed.some((f) => !features.has(f));

  /** Starts the mod with `o` for every test of the enclosing suite. */
  function startWith(o: DriverOptions): void {
    beforeAll(() => {
      options = o;
    });
    afterAll(() => {
      options = {};
    });
  }

  /** Loads another bot on the hub and waits until the player has it attached. */
  async function addBot(username: string): Promise<BotBridge> {
    const bot = new FakeBot(username);
    hub.plugin()(bot as never, { host: 'play.example.net' });
    const other = bot.bridge!;
    await until(async () => other.state.attached && other.state.worldMatched, `attach ${username}`);
    return other;
  }

  /** Waits until the mod holds the objects with these keys, such as the declarations of commands just registered. */
  const declared = (...keys: string[]): Promise<unknown> =>
    until(async () => {
      const held = new Set((await mod.objects('')).map((o) => o.key));
      return keys.every((k) => held.has(k));
    }, keys.join(', '));

  /** The first waiting input request, once there is one. */
  const nextInput = (): Promise<DriverInput> => until(async () => (await mod.inputs())[0], 'an input request');

  beforeEach(async () => {
    mod = await start(options);
    warnings = [];
    const logger: Logger = { debug() {}, info() {}, warn: (m) => void warnings.push(m), error() {} };
    hub = createBridge({ endpointFile: mod.endpointFile, credentials: join(mod.dir, 'credentials.json'), logger, reconnect: { initialDelayMs: 20, maxDelayMs: 100 } });
    const bot = new FakeBot();
    hub.plugin()(bot as never, { host: 'play.example.net' });
    b = bot.bridge!;
    await mod.setPlayerContext(OVERWORLD);
    await until(async () => b.state.attached && b.state.worldMatched, 'attach');
  }, 30_000);

  afterEach(async () => {
    await hub.close();
    await mod.close();
  });

  it('lists the bot session', async () => {
    const s = await until(async () => (await mod.sessions()).find((x) => x.owner === ALICE), 'session');
    expect(s).toMatchObject({ kind: 'bot', name: 'Alice', connected: true });
  });

  it('stores declared objects with the same data', async () => {
    const layer = b.layer('plan');
    layer.box('crate', [1, 64, 2], 2, { color: '#f59e0b' });
    layer.text('note', [0, 65, 0], 'Hello', { billboard: 'center' });
    const objects = await until(async () => {
      const list = await mod.objects(PREFIX);
      return list.some((o) => o.key.endsWith('/plan/note')) && list;
    }, 'objects');
    const crate = objects.find((o) => o.key === `${PREFIX}/plan/crate`);
    expect(crate).toMatchObject({ kind: 'core:box', layer: 'plan', scope: 'world' });
    expect(decoded(crate)).toMatchObject({ min: { x: 1, y: 64, z: 2 }, size: { x: 2, y: 2, z: 2 } });
    expect(decoded(objects.find((o) => o.key === `${PREFIX}/plan/note`))).toMatchObject({ text: 'Hello', billboard: 'center' });
    layer.remove('crate');
    await until(async () => !(await mod.objects(PREFIX)).some((o) => o.key === `${PREFIX}/plan/crate`), 'removal');
  });

  it('runs actions and commands and returns their answers', async () => {
    b.actions.register('mark', { label: 'Mark', target: { type: 'air' }, run: (t) => `marked ${t.pos?.y}` });
    b.commands.register('mine', {
      description: 'Mine',
      args: [{ name: 'count', type: 'int', min: 1, max: 64 }],
      run: ({ count }) => `mining ${String(count)}`,
    });
    b.commands.register('fail', { description: 'Fails', run: () => { throw new Error('no pickaxe'); } });
    await until(async () => (await mod.objects(PREFIX)).some((o) => o.key.endsWith('~cmd/fail')), 'declarations');
    expect(await mod.invokeAction(`${PREFIX}/~action/mark`, { type: 'air', pos: { x: 0, y: 70, z: 0 } })).toEqual({ ok: true, message: 'marked 70' });
    expect(await mod.invokeCommand(`${PREFIX}/~cmd/mine`, { count: 3 })).toEqual({ ok: true, message: 'mining 3' });
    expect(await mod.invokeCommand(`${PREFIX}/~cmd/fail`, {})).toEqual({ ok: false, message: 'no pickaxe' });
  });

  it('answers client queries with the same order, filters, caps and refusals', async () => {
    await mod.setLoadedWorld({
      player: { x: 0, y: 64, z: 0 },
      entities: [
        { id: 7, type: 'minecraft:zombie', pos: { x: 5, y: 64, z: 0 } },
        { id: 3, type: 'minecraft:cow', pos: { x: 2, y: 64, z: 0 }, name: 'Bessie' },
        { id: 11, type: 'minecraft:zombie', pos: { x: 200, y: 64, z: 0 } },
      ],
      blocks: [
        { x: 2, y: 60, z: 1, name: 'minecraft:iron_ore' },
        { x: 1, y: 61, z: 1, name: 'minecraft:stone' },
        { x: 1, y: 60, z: 1, name: 'minecraft:iron_ore' },
        { x: 40, y: 60, z: 1, name: 'minecraft:iron_ore' },
      ],
      unloadedFromX: 32,
    });
    const near = await b.query.entities();
    expect(near.map((e) => e.id)).toEqual([3, 7]);
    expect(near[0]).toEqual({ id: 3, type: 'minecraft:cow', pos: { x: 2, y: 64, z: 0 }, name: 'Bessie', player: false });
    expect((await b.query.entities({ types: ['minecraft:zombie'], radius: 300 })).map((e) => e.id)).toEqual([7]);
    const ores = await b.query.blocks([10, 65, 10], [0, 55, 0]);
    expect(ores.complete).toBe(true);
    expect(ores.blocks.map((x) => [x.x, x.y, x.name])).toEqual([[1, 60, 'minecraft:iron_ore'], [1, 61, 'minecraft:stone'], [2, 60, 'minecraft:iron_ore']]);
    expect((await b.query.blocks([0, 60, 0], [40, 60, 2])).complete).toBe(false);
    await expect(b.query.blocks([0, 0, 0], [100, 100, 100])).rejects.toMatchObject({ code: 'invalid_message' });
  });

  it('sends screen events to handlers and refuses unknown items', async () => {
    let flips = 0;
    b.screen('main', { title: 'A', rows: [{ toggle: 'Deposit', id: 'deposit', value: false, onChange: () => { flips++; } }, { button: 'Go', id: 'go', onClick: () => 'went' }] });
    await until(async () => (await mod.objects(PREFIX)).some((o) => o.key === `${PREFIX}/~screen/main`), 'screen');
    expect(await mod.screenEvent(`${PREFIX}/~screen/main`, 'go')).toEqual({ ok: true, message: 'went' });
    expect(await mod.screenEvent(`${PREFIX}/~screen/main`, 'deposit', true)).toMatchObject({ ok: true });
    expect(flips).toBe(1);
    await expect(mod.screenEvent(`${PREFIX}/~screen/main`, 'missing')).rejects.toThrow(/missing/);
  });

  describe.skipIf(lacks('chat'))('chat addressing', () => {
    let boss: BridgeController;
    let bob: BotBridge;
    /** The bots each `dig` order was for, oldest first. */
    let digs: (readonly string[] | undefined)[];
    /** Free text that reached Alice. */
    let heard: string[];

    beforeEach(async () => {
      bob = await addBot('Bob');
      boss = hub.controller({ name: 'Boss' });
      digs = [];
      heard = [];
      boss.orders.register('dig', { run: (_a, ctx) => (digs.push(ctx.bots), `digging with ${(ctx.bots ?? []).join(' and ')}`) });
      boss.commands.register('status', { run: () => 'all good' });
      boss.crew.set({ bots: ['Alice', 'Bob'], groups: { miners: ['Bob'] } });
      b.commands.register('jobs', { run: () => '2 jobs' });
      b.chat.on('message', (text) => void heard.push(text));
      await until(async () => {
        const keys = (await mod.objects('')).map((o) => o.key);
        return ['Boss/user:default/~crew/crew', 'Boss/user:default/~order/dig', 'Boss/user:default/~cmd/status', `${PREFIX}/~cmd/jobs`].every((end) => keys.some((k) => k.endsWith(end)));
      }, 'crew, orders and commands');
    });

    it('runs a bot command and a crew order for a bot addressed by name', async () => {
      expect(await mod.chat('@alice jobs')).toEqual({ route: 'command', results: [{ ok: true, message: '2 jobs' }] });
      expect(await mod.chat('@Alice dig')).toEqual({ route: 'command', results: [{ ok: true, message: 'digging with Alice' }] });
      expect(digs).toEqual([['Alice']]);
    });

    it('sends an order once for a group and once for all, naming the bots', async () => {
      expect(await mod.chat('@miners dig')).toEqual({ route: 'command', results: [{ ok: true, message: 'digging with Bob' }] });
      expect(await mod.chat('@all dig')).toEqual({ route: 'command', results: [{ ok: true, message: 'digging with Alice and Bob' }] });
      expect(digs).toEqual([['Bob'], ['Alice', 'Bob']]);
    });

    it('reaches only the own commands of a controller', async () => {
      expect(await mod.chat('@boss status')).toEqual({ route: 'command', results: [{ ok: true, message: 'all good' }] });
      expect((await mod.chat('@boss dig')).route).toBe('message');
      expect(digs).toEqual([]);
    });

    it('delivers text that is no command as chat to the bot', async () => {
      expect((await mod.chat('@alice hello there')).route).toBe('message');
      await until(async () => heard.length === 1, 'the chat message');
      expect(heard).toEqual(['hello there']);
    });

    it('keeps a mistyped name from the server and runs nothing', async () => {
      expect(await mod.chat('@alise jobs')).toEqual({ route: 'mistyped', closest: 'Alice', corrected: '@Alice jobs' });
      expect(await mod.chat('@bosss dig')).toEqual({ route: 'mistyped', closest: 'Boss', corrected: '@Boss dig' });
      expect(await mod.chat('@minerz dig')).toEqual({ route: 'mistyped', closest: 'miners', corrected: '@miners dig' });
      expect(digs).toEqual([]);
      expect(heard).toEqual([]);
    });

    it('keeps a known bot from the server when it cannot be reached', async () => {
      boss.crew.set({ bots: ['Alice', 'Bob', 'Carol'], groups: { miners: ['Bob'], night: ['Carol'] } });
      await until(async () => (await mod.chat('@carol dig')).route === 'unknown', 'the crew with Carol');
      expect(await mod.chat('@night dig')).toMatchObject({ route: 'unknown' });
      await mod.setAttached(`bot:bob@${SERVER}`, false);
      await until(async () => (await mod.chat('@bob dig')).route === 'unknown', 'Bob detached');
      expect(digs).toEqual([]);
    });

    it('passes other names and the doubled prefix on to the server', async () => {
      expect(await mod.chat('@steve hi')).toEqual({ route: 'pass', text: '@steve hi' });
      expect(await mod.chat('@@alice hi')).toEqual({ route: 'pass', text: '@alice hi' });
      expect(await mod.chat('plain chat')).toEqual({ route: 'pass', text: 'plain chat' });
      expect(await mod.chat('@ alice hi')).toEqual({ route: 'pass', text: '@ alice hi' });
      expect(await mod.chat('@')).toEqual({ route: 'pass', text: '@' });
    });

    it('delivers a line that names a command but does not parse as chat, with the reason', async () => {
      b.commands.register('count', { args: [{ name: 'n', type: 'int', min: 1, max: 9 }], run: ({ n }) => `counted ${String(n)}` });
      await declared(`${PREFIX}/~cmd/count`);
      expect(await mod.chat('@alice count 3')).toEqual({ route: 'command', results: [{ ok: true, message: 'counted 3' }] });
      expect(await mod.chat('@alice count many')).toEqual({ route: 'message', commandError: "count: 'n' must be a whole number, not 'many'" });
      expect(await mod.chat('@alice count 12')).toEqual({ route: 'message', commandError: "count: 'n' must be at most 9" });
      expect(await mod.chat('@alice count')).toEqual({ route: 'message', commandError: "count: Missing argument 'n'" });
      expect(await mod.chat('@alice count 1 2')).toEqual({ route: 'message', commandError: 'count: Too many arguments: 2' });
      expect(await mod.chat('@alice counting sheep')).toEqual({ route: 'message' });
      await until(async () => heard.length === 5, 'the chat messages');
      expect(heard).toEqual(['count many', 'count 12', 'count', 'count 1 2', 'counting sheep']);
    });

    it('answers unknown when no text follows the name, and delivers nothing', async () => {
      expect(await mod.chat('@alice')).toEqual({ route: 'unknown', message: 'Type a message or command after @alice' });
      expect(await mod.chat('@Alice   ')).toEqual({ route: 'unknown', message: 'Type a message or command after @Alice' });
      expect(await mod.chat('@all')).toEqual({ route: 'unknown', message: 'Type a message or command after @all' });
      expect(await mod.chat('@boss ')).toEqual({ route: 'unknown', message: 'Type a message or command after @boss' });
      expect(await mod.chat('@alice hi')).toEqual({ route: 'message' });
      await until(async () => heard.length === 1, 'the chat message');
      expect(heard).toEqual(['hi']);
    });

    it('shows help for a node that only groups subcommands, and for one whose required arguments are missing', async () => {
      b.commands.register('farm', {
        description: 'Farming',
        children: {
          start: { args: [{ name: 'crop', type: 'string' }, { name: 'rows', type: 'int', default: 4 }], run: ({ crop, rows }) => `farming ${String(rows)} rows of ${String(crop)}` },
          stop: { description: 'Stop', run: () => 'stopped' },
        },
      });
      b.commands.register('go', { args: [{ name: 'where', type: 'string' }], children: { home: { run: () => 'going home' } }, run: ({ where }) => `going to ${String(where)}` });
      await declared(`${PREFIX}/~cmd/farm`, `${PREFIX}/~cmd/go`);
      expect(await mod.chat('@alice farm')).toEqual({ route: 'help', lines: ['farm <start|stop> - Farming', '  farm start <crop:string> [rows:int=4]', '  farm stop - Stop'] });
      expect(await mod.chat('@alice go')).toEqual({ route: 'help', lines: ['go <where:string>', '  go home'] });
      expect(await mod.chat('@alice go home')).toEqual({ route: 'command', results: [{ ok: true, message: 'going home' }] });
      expect(await mod.chat('@alice go north')).toEqual({ route: 'command', results: [{ ok: true, message: 'going to north' }] });
      expect(await mod.chat('@alice farm start wheat')).toEqual({ route: 'command', results: [{ ok: true, message: 'farming 4 rows of wheat' }] });
      expect(await mod.chat('@alice farm plough')).toEqual({ route: 'message', commandError: "farm: Unknown subcommand 'plough' of 'farm'; expected start, stop" });
      expect(await mod.chat('@alice farm stop now')).toEqual({ route: 'message', commandError: 'farm: Too many arguments: now' });
      expect(await mod.chat('@alice farm start')).toEqual({ route: 'message', commandError: "farm: Missing argument 'crop'" });
    });

    it('reads quotes, names in any case, defaults and argument values the same way', async () => {
      const echo = (args: Record<string, unknown>): string => JSON.stringify(args);
      b.commands.register('say', { args: [{ name: 'to', type: 'string', max: 8 }, { name: 'text', type: 'greedy', optional: true }], run: echo });
      b.commands.register('set', {
        aliases: ['cfg'],
        args: [
          { name: 'mode', type: 'enum', choices: ['Fast', 'slow'] },
          { name: 'on', type: 'bool', default: true },
          { name: 'speed', type: 'float', default: 1.5 },
          { name: 'count', type: 'int', default: 64 },
        ],
        run: echo,
      });
      b.commands.register('goto', { args: [{ name: 'pos', type: 'block-pos' }, { name: 'block', type: 'block-id', optional: true }], run: echo });
      b.commands.register('look', { args: [{ name: 'at', type: 'vec3' }], run: echo });
      b.commands.register('hit', { args: [{ name: 'targets', type: 'entity-list' }, { name: 'who', type: 'player', optional: true }], run: echo });
      await declared(...['say', 'set', 'goto', 'look', 'hit'].map((c) => `${PREFIX}/~cmd/${c}`));
      await mod.setLoadedWorld({ player: { x: 10.5, y: 64, z: -3.5 } });
      const ran = async (line: string): Promise<unknown> => {
        const r = await mod.chat(`@alice ${line}`);
        expect(r, line).toMatchObject({ route: 'command', results: [{ ok: true }] });
        return JSON.parse(r.results![0]!.message!);
      };
      const failed = async (line: string): Promise<string | undefined> => {
        const r = await mod.chat(`@alice ${line}`);
        expect(r.route, line).toBe('message');
        return r.commandError;
      };

      expect(await ran('say "Mr Smith" hello   "big" world')).toEqual({ to: 'Mr Smith', text: 'hello   "big" world' });
      expect(await ran('SAY Bob "one  two"')).toEqual({ to: 'Bob', text: 'one  two' });
      expect(await ran('say "a \\"b\\""')).toEqual({ to: 'a "b"' });
      expect(await failed('say "much too long" hi')).toBe("say: 'to' length must be at most 8");

      expect(await ran('CFG fast')).toEqual({ mode: 'Fast', on: true, speed: 1.5, count: 64 });
      expect(await ran('set SLOW off 1e3 +7')).toEqual({ mode: 'slow', on: false, speed: 1000, count: 7 });
      expect(await ran('set slow YES .5')).toEqual({ mode: 'slow', on: true, speed: 0.5, count: 64 });
      expect(await failed('set mid')).toBe("set: 'mode' must be one of Fast, slow");
      expect(await failed('set fast maybe')).toBe("set: 'on' must be true or false, not 'maybe'");
      expect(await failed('set fast on 0x10')).toBe("set: 'speed' must be a number, not '0x10'");
      expect(await failed('set fast on 1 2.0')).toBe("set: 'count' must be a whole number, not '2.0'");

      expect(await ran('goto ~ ~1 ~-2 Iron_Ore')).toEqual({ pos: { x: 10, y: 65, z: -6 }, block: 'minecraft:iron_ore' });
      expect(await ran('goto 1.9 2 -0.5 mod:ore')).toEqual({ pos: { x: 1, y: 2, z: -1 }, block: 'mod:ore' });
      expect(await ran('look ~ ~1.5 7')).toEqual({ at: { x: 10.5, y: 65.5, z: 7 } });
      expect(await failed('goto 1 2')).toBe("goto: 'pos' needs x, y and z");
      expect(await failed('goto 1 x 3')).toBe("goto: 'pos' must be a number, not 'x'");
      expect(await failed('goto here')).toBe("goto: 'pos': the crosshair is not on a block");
      expect(await failed('look here')).toBe("look: 'at': the crosshair is not on anything");
      expect(await failed('goto 1 2 3 not/an:id:x')).toBe("goto: 'block': unknown block 'not/an:id:x'");

      expect(await ran('hit 3,1,3')).toEqual({ targets: [3, 1] });
      expect(await ran('hit -4 @me')).toEqual({ targets: [-4], who: '@me' });
      expect(await failed('hit 3,,1')).toBe("hit: 'targets': empty entry in the list");
      expect(await failed('hit zombie')).toBe("hit: 'targets': no loaded entity or player 'zombie'");
      expect(await failed('hit @everyone')).toBe("hit: Unknown selector '@everyone'; use @me, @self, @looked, @nearest or @all");
      expect(await failed('hit @all[size=3]')).toBe("hit: Unknown selector option 'size'; use type, tag, distance, from, limit");
    });

    it('runs a line for all in every command set that parses it, and only there', async () => {
      bob.commands.register('jobs', { args: [{ name: 'n', type: 'int' }], run: ({ n }) => `bob has ${String(n)}` });
      await declared(`bot:bob@${SERVER}/user:default/~cmd/jobs`);
      expect(await mod.chat('@all jobs')).toEqual({ route: 'command', results: [{ ok: true, message: '2 jobs' }] });
      expect(await mod.chat('@all jobs 5')).toEqual({ route: 'command', results: [{ ok: true, message: 'bob has 5' }] });
      const neither = await mod.chat('@all jobs 1 2');
      expect(neither.route).toBe('message');
      expect(neither.commandError).toMatch(/^jobs: Too many arguments: /);
      await until(async () => heard.length === 1, 'the chat message');
      expect(heard).toEqual(['jobs 1 2']);
    });
  });

  describe.skipIf(lacks('chat', 'attach'))('chat lines', () => {
    it('shows a line an attached bot prints, drops one of a detached bot and refuses one that is too long', async () => {
      b.chat.print(['Mining ', { text: 'done', bold: true }]);
      await until(async () => (await mod.printed()).length === 1, 'the line');
      expect(await mod.printed()).toEqual([{ owner: ALICE, text: 'Mining done' }]);

      await mod.setAttached(ALICE, false);
      await until(async () => !b.state.attached, 'detach');
      b.chat.print('Nobody sees this');
      b.notify('Nor this');
      await until(async () => warnings.some((w) => w.includes('not_attached')), 'the line to arrive');
      await mod.setAttached(ALICE, true);
      await until(async () => b.state.attached, 'attach');
      b.chat.print(['a'.repeat(4000), 'b'.repeat(97)]);
      await until(async () => warnings.some((w) => w.includes('invalid_message')), 'the refusal');
      b.chat.print('Back again');
      await until(async () => (await mod.printed()).length === 2, 'the next line');
      expect((await mod.printed())[1]).toEqual({ owner: ALICE, text: 'Back again' });
    });
  });

  describe.skipIf(lacks('chat', 'permissions'))('chat permission', () => {
    it('answers unknown for a name whose device may not receive chat, and delivers again once it may', async () => {
      const heard: string[] = [];
      b.commands.register('jobs', { run: () => '2 jobs' });
      b.chat.on('message', (text) => void heard.push(text));
      await declared(`${PREFIX}/~cmd/jobs`);
      await mod.setPermissions(ALICE, LOCAL_DEFAULT_PERMISSIONS.filter((p) => p !== 'chat'));
      await until(async () => !b.can('chat'), 'the permission push');
      expect(await mod.chat('@alice jobs')).toEqual({ route: 'unknown', message: 'alice runs on a device without the chat permission' });
      expect(await mod.chat('@all hello')).toEqual({ route: 'unknown', message: 'all runs on a device without the chat permission' });
      expect(await mod.chat('@alise jobs')).toEqual({ route: 'mistyped', closest: 'Alice', corrected: '@Alice jobs' });
      expect(await mod.chat('@steve hi')).toEqual({ route: 'pass', text: '@steve hi' });

      await mod.setPermissions(ALICE, LOCAL_DEFAULT_PERMISSIONS);
      await until(async () => b.can('chat'), 'the permission push');
      expect(await mod.chat('@alice jobs')).toEqual({ route: 'command', results: [{ ok: true, message: '2 jobs' }] });
      expect(await mod.chat('@all hello')).toEqual({ route: 'message' });
      await until(async () => heard.length === 1, 'the chat message');
      expect(heard).toEqual(['hello']);
    });
  });

  describe.skipIf(lacks('chat', 'inputs'))('chat prompts', () => {
    it('lets the next chat line answer a text prompt on the chat surface, whatever it says', async () => {
      const name = b.prompt.text('Name the base', { surface: 'chat', maxLength: 8 });
      expect(await nextInput()).toMatchObject({ type: 'prompt', owner: ALICE });
      expect(await mod.chat('much too long')).toEqual({ route: 'prompt', error: 'the answer is longer than 8 characters' });
      expect(await mod.inputs()).toHaveLength(1);
      expect(await mod.chat('@all hi')).toEqual({ route: 'prompt' });
      expect(await name).toBe('@all hi');
      expect(await mod.inputs()).toEqual([]);
      expect(await mod.chat('plain chat')).toEqual({ route: 'pass', text: 'plain chat' });
    });

    it('takes yes or no for a confirm prompt on the chat surface', async () => {
      const sure = b.prompt.confirm('Dig here?', { surface: 'chat' });
      await nextInput();
      expect(await mod.chat('maybe')).toEqual({ route: 'prompt', error: 'Answer yes or no' });
      expect(await mod.chat(' Y ')).toEqual({ route: 'prompt' });
      expect(await sure).toBe(true);
      const again = b.prompt.confirm('And here?', { surface: 'chat' });
      await nextInput();
      expect(await mod.chat('no')).toEqual({ route: 'prompt' });
      expect(await again).toBe(false);
    });

    it('leaves chat alone while the active prompt is on another surface', async () => {
      const name = b.prompt.text('Name the base');
      const input = await nextInput();
      expect(await mod.chat('hello')).toEqual({ route: 'pass', text: 'hello' });
      expect(await mod.inputs()).toHaveLength(1);
      await mod.dismiss(input.id);
      expect((await rejection(name)).code).toBe('cancelled');
    });
  });

  describe.skipIf(lacks('permissions', 'inputs'))('permissions', () => {
    it('refuses a request whose permission the player took away, and takes it again once granted', async () => {
      await mod.setPermissions(ALICE, LOCAL_DEFAULT_PERMISSIONS.filter((p) => p !== 'input.pick'));
      await until(async () => !b.can('input.pick'), 'the permission push');
      const err = await rejection(b.prompt.confirm('Dig here?'));
      expect(err.code).toBe('permission_denied');
      expect(err.details).toMatchObject({ permission: 'input.pick' });
      expect(await mod.inputs()).toEqual([]);

      await mod.setPermissions(ALICE, LOCAL_DEFAULT_PERMISSIONS);
      await until(async () => b.can('input.pick'), 'the permission push');
      const asked = b.prompt.confirm('Dig here?');
      const input = await nextInput();
      expect(input).toMatchObject({ type: 'prompt', owner: ALICE });
      await mod.dismiss(input.id);
      expect((await rejection(asked)).code).toBe('cancelled');
    });
  });

  describe.skipIf(lacks('options'))('object quota', () => {
    startWith({ maxObjects: 4 });

    it('advertises the quota and holds no more objects than it allows', async () => {
      expect(b.limits.maxObjects).toBe(4);
      const layer = b.layer('pile');
      let refused: BridgeError | undefined;
      for (let i = 0; i < 8 && refused === undefined; i++) {
        try {
          layer.box(`box${i}`, [i, 64, 0], 1);
        } catch (err) {
          refused = err as BridgeError;
        }
      }
      expect(refused).toBeInstanceOf(BridgeError);
      expect(refused!.code).toBe('quota_exceeded');
      await until(async () => (await mod.objects(ALICE)).length === 4, 'the objects that fit');
      await new Promise((r) => setTimeout(r, 100));
      expect((await mod.objects(ALICE)).length).toBe(4);
      expect(warnings.filter((w) => w.includes('quota_exceeded'))).toEqual([]);
    });

    it('counts the objects of every session of a device, and refuses the write past the quota itself', async () => {
      const BOB = `bot:bob@${SERVER}`;
      const refused: string[] = [];
      const logger: Logger = { debug() {}, info() {}, warn: (m) => void refused.push(m), error() {} };
      const other = createBridge({ endpointFile: mod.endpointFile, credentials: join(mod.dir, 'credentials.json'), logger, reconnect: { initialDelayMs: 20, maxDelayMs: 100 } });
      try {
        const bot = new FakeBot('Bob');
        other.plugin()(bot as never, { host: 'play.example.net' });
        const bob = bot.bridge!;
        await until(async () => bob.state.attached && bob.state.worldMatched, 'attach Bob');
        const mine = b.layer('pile');
        mine.box('box', [0, 64, 0], 1);
        await until(async () => (await mod.objects(ALICE)).length === 2, "Alice's objects");
        const theirs = bob.layer('pile');
        theirs.box('first', [0, 70, 0], 1);
        theirs.box('second', [1, 70, 0], 1);
        await until(async () => refused.some((w) => w.includes('quota_exceeded')), 'the refusal');
        expect((await mod.objects(BOB)).map((o) => o.key)).toEqual([`${BOB}/user:default/pile`, `${BOB}/user:default/pile/first`]);
        expect(await mod.objects(ALICE)).toHaveLength(2);

        mine.remove('box');
        await until(async () => (await mod.objects(ALICE)).length === 1, 'the removal');
        theirs.box('third', [2, 70, 0], 1);
        await until(async () => (await mod.objects(BOB)).length === 3, 'the write that fits');
      } finally {
        await other.close();
      }
    });
  });

  describe.skipIf(lacks('options', 'inputs', 'clock'))('surface cooldown', () => {
    startWith({ surfaceCooldownsMs: [5000] });
    const FIELDS = [{ id: 'n', type: 'number' }] as const;

    /** Opens a form and has the player dismiss it. */
    async function dismissForm(): Promise<void> {
      const form = b.form({ title: 'Dismiss me', fields: FIELDS });
      await mod.dismiss((await nextInput()).id);
      expect((await rejection(form)).code).toBe('cancelled');
    }

    it('refuses unasked surfaces after a dismissal with cooldown and retryAfterMs, until the time has passed', async () => {
      await dismissForm();
      const err = await rejection(b.form({ title: 'Too soon', fields: FIELDS }));
      expect(err.code).toBe('cooldown');
      expect(err.retryable).toBe(true);
      expect(err.retryAfterMs).toBeGreaterThan(0);
      expect(err.retryAfterMs).toBeLessThanOrEqual(5000);
      expect(await mod.inputs()).toEqual([]);

      await mod.advance(5001);
      const later = b.form({ title: 'Later', fields: FIELDS });
      const input = await nextInput();
      expect(input.type).toBe('form');
      await mod.dismiss(input.id);
      expect((await rejection(later)).code).toBe('cancelled');
    });

    it.skipIf(lacks('chat'))('lets a surface through that follows the player talking to the bot', async () => {
      let opened: Promise<unknown> | undefined;
      b.chat.on('message', () => {
        opened = b.form({ title: 'You spoke', fields: FIELDS });
      });
      await dismissForm();
      expect((await rejection(b.form({ title: 'Too soon', fields: FIELDS }))).code).toBe('cooldown');

      expect(await mod.chat('@alice open up')).toEqual({ route: 'message' });
      const input = await nextInput();
      expect(input).toMatchObject({ type: 'form', owner: ALICE });
      await mod.dismiss(input.id);
      expect((await rejection(opened!)).code).toBe('cancelled');
    });

    it('lets a surface through that follows the player running a command of the bot', async () => {
      let opened: Promise<unknown> | undefined;
      b.commands.register('ask', {
        run: () => {
          opened = b.form({ title: 'You asked', fields: FIELDS });
          return 'asked';
        },
      });
      await until(async () => (await mod.objects(PREFIX)).some((o) => o.key === `${PREFIX}/~cmd/ask`), 'the command');
      await dismissForm();
      expect((await rejection(b.form({ title: 'Too soon', fields: FIELDS }))).code).toBe('cooldown');

      expect(await mod.invokeCommand(`${PREFIX}/~cmd/ask`, {})).toEqual({ ok: true, message: 'asked' });
      const input = await nextInput();
      expect(input).toMatchObject({ type: 'form', owner: ALICE });
      await mod.dismiss(input.id);
      expect((await rejection(opened!)).code).toBe('cancelled');
    });
  });

  describe.skipIf(lacks('attach', 'notifications'))('notifications', () => {
    it('shows a notification of an attached bot and refuses one of a detached bot with not_attached', async () => {
      b.notify('Chest is full');
      await until(async () => (await mod.notifications()).length === 1, 'the notification');
      expect(await mod.notifications()).toEqual([{ owner: ALICE, text: 'Chest is full' }]);

      await mod.setAttached(ALICE, false);
      await until(async () => !b.state.attached, 'detach');
      b.notify('Nobody sees this');
      await until(async () => warnings.some((w) => w.includes('notify') && w.includes('not_attached')), 'the refusal');
      expect(await mod.notifications()).toHaveLength(1);

      await mod.setAttached(ALICE, true);
      await until(async () => b.state.attached, 'attach');
      b.notify('Back again');
      await until(async () => (await mod.notifications()).length === 2, 'the second notification');
      expect((await mod.notifications())[1]).toEqual({ owner: ALICE, text: 'Back again' });
    });
  });
});
