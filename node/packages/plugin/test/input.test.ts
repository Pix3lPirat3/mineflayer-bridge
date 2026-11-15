import { describe, expect, it } from 'vitest';
import type { MockInputRequest } from '@mineflayer-bridge/mock-mod';
import { BridgeError, defineExtension, NO_TARGET, type ExtensionContext, type Target } from '../src/index.ts';
import { ALICE, ALICE_PREFIX, FakeBot, fixture, load, OVERWORLD, rejection, SERVER, sleep, until, type Env } from './helpers.ts';

const IDENTITY = 'alice@play.example.net:25565';
const NETHER = { server: SERVER, dimension: 'minecraft:the_nether' };

let e: Env;
const { hub, attached } = fixture((f) => (e = f.e));

/** A connected bot that the player has attached, in the player's world. */
async function nextInput(): Promise<MockInputRequest> {
  const [r] = await e.mod.waitFor('inputRequest');
  return r;
}

describe('picks', () => {
  it('sends a pick request and resolves with the friendly block result', async () => {
    const b = await attached();
    const queued = nextInput();
    const pending = b.pick.block({ prompt: 'Click the chest', maxDistance: 32, filter: { tags: ['c:chests'] } });
    const r = await queued;
    expect(r).toMatchObject({ type: 'pick', kind: 'block', owner: ALICE, urgent: false });
    expect(r.request).toMatchObject({ kind: 'block', prompt: 'Click the chest', maxDistance: 32, timeoutMs: 60_000, filter: { type: 'block', tags: ['c:chests'] } });
    expect(e.mod.active()?.id).toBe(r.id);
    e.mod.resolvePick(r.id, { pos: { x: 1, y: 64, z: -3 }, face: 'up', hit: { x: 1.5, y: 65, z: -2.5 }, state: 'minecraft:chest[facing=north]' });
    expect(await pending).toEqual({ pos: { x: 1, y: 64, z: -3 }, face: 'up', hit: { x: 1.5, y: 65, z: -2.5 }, state: 'minecraft:chest[facing=north]' });
    expect(e.mod.active()).toBeUndefined();
  });

  it('converts every pick kind', async () => {
    const b = await attached();
    const cases: [() => Promise<unknown>, Parameters<typeof e.mod.resolvePick>[1], unknown][] = [
      [() => b.pick.point(), { type: 'point', value: { pos: { x: 0.5, y: 1, z: 2 } } }, { x: 0.5, y: 1, z: 2 }],
      [() => b.pick.region({ maxVolume: 1000 }), { min: { x: 5, y: 0, z: 0 }, max: { x: 0, y: 3, z: 2 } }, { min: { x: 0, y: 0, z: 0 }, max: { x: 5, y: 3, z: 2 } }],
      [() => b.pick.polygon(), { vertices: [{ x: 0, y: 64, z: 0 }, { x: 4, y: 64, z: 0 }, { x: 0, y: 64, z: 4 }], minY: 60, maxY: 70 }, { vertices: [{ x: 0, y: 64, z: 0 }, { x: 4, y: 64, z: 0 }, { x: 0, y: 64, z: 4 }], minY: 60, maxY: 70 }],
      [() => b.pick.entity({ filter: { ids: ['minecraft:cow'] } }), { id: 42, type: 'minecraft:cow', pos: { x: 1, y: 2, z: 3 } }, { id: 42, type: 'minecraft:cow', pos: { x: 1, y: 2, z: 3 } }],
      [() => b.pick.path(), { points: [{ x: 0, y: 0, z: 0 }, { x: 1, y: 0, z: 1 }] }, [{ x: 0, y: 0, z: 0 }, { x: 1, y: 0, z: 1 }]],
      [() => b.pick.direction(), { yaw: 90, pitch: -15, cardinal: 'west' }, { yaw: 90, pitch: -15, cardinal: 'west' }],
      [() => b.pick.item(), { id: 'minecraft:diamond', count: 3 }, { id: 'minecraft:diamond', count: 3 }],
    ];
    for (const [call, result, expected] of cases) {
      const queued = nextInput();
      const pending = call();
      e.mod.resolvePick((await queued).id, result);
      expect(await pending).toEqual(expected);
    }
  });

  it('rejects with invalid_message when the result has the wrong kind', async () => {
    const b = await attached();
    const queued = nextInput();
    const pending = b.pick.block();
    e.mod.resolvePick((await queued).id, { type: 'point', value: { pos: { x: 0, y: 0, z: 0 } } });
    expect((await rejection(pending)).code).toBe('invalid_message');
  });

  it('cancels through an AbortSignal: sends pick.cancel with the request id and rejects with cancelled', async () => {
    const b = await attached();
    const queued = nextInput();
    const ac = new AbortController();
    const pending = b.pick.region({ signal: ac.signal });
    const r = await queued;
    const cancelled = e.mod.waitFor('message', (m) => m.type === 'pick.cancel');
    const settled = e.mod.waitFor('inputSettled');
    ac.abort();
    expect((await rejection(pending)).code).toBe('cancelled');
    const [m] = await cancelled;
    expect(m.payload).toEqual({ request: r.req });
    expect((await settled)[0]).toMatchObject({ outcome: 'withdrawn', request: { id: r.id } });
    expect(e.mod.inputQueue()).toEqual([]);
  });

  it('rejects at once with an already aborted signal, before anything is sent', async () => {
    const b = await attached();
    const err = await rejection(b.pick.point({ signal: AbortSignal.abort() }));
    expect(err.code).toBe('cancelled');
    await sleep(30);
    expect(e.mod.inputQueue()).toEqual([]);
  });

  it('rejects with cancelled when the player presses Esc and with timeout at the deadline', async () => {
    const b = await attached();
    const queued = nextInput();
    const esc = b.pick.entity();
    e.mod.cancel((await queued).id);
    expect((await rejection(esc)).code).toBe('cancelled');

    const started = Date.now();
    const late = b.pick.block({ timeoutMs: 80 });
    const [settled] = await e.mod.waitFor('inputSettled');
    expect(settled.outcome).toBe('timeout');
    expect((await rejection(late)).code).toBe('timeout');
    expect(Date.now() - started).toBeGreaterThanOrEqual(70);
  });

  it('rejects with not_attached and world_mismatch; item picks are not world-scoped', async () => {
    const b = await attached();
    e.mod.setAttached(IDENTITY, false);
    await until(() => !b.state.attached);
    expect((await rejection(b.pick.block())).code).toBe('not_attached');

    e.mod.setAttached(IDENTITY, true);
    e.mod.setPlayerContext(NETHER);
    await until(() => b.state.attached && !b.state.worldMatched);
    expect((await rejection(b.pick.block())).code).toBe('world_mismatch');
    const queued = nextInput();
    const item = b.pick.item();
    e.mod.resolvePick((await queued).id, { id: 'minecraft:stick', count: 1 });
    expect(await item).toEqual({ id: 'minecraft:stick', count: 1 });
  });

  it('waits for the session to open before sending a request made while connecting', async () => {
    const b = load(hub().plugin(), new FakeBot('Alice'));
    e.mod.setPlayerContext(OVERWORLD);
    expect(b.state.connected).toBe(false);
    const pick = b.pick.point({ prompt: 'early' });
    const r = await nextInput();
    expect(r.kind).toBe('point');
    e.mod.resolvePick(r.id, { type: 'point', value: { pos: { x: 1, y: 2, z: 3 } } });
    expect(await pick).toEqual({ x: 1, y: 2, z: 3 });
  });

  it('cancels a request still waiting for the session to open', async () => {
    const b = load(hub().plugin(), new FakeBot('Alice'));
    const ctl = new AbortController();
    const pending = b.prompt.confirm('ok?', { signal: ctl.signal });
    ctl.abort();
    expect((await rejection(pending)).code).toBe('cancelled');
  });

  it('rejects invalid timeouts before sending', async () => {
    const b = await attached();
    expect((await rejection(b.pick.point({ timeoutMs: 0 }))).code).toBe('invalid_message');
    expect((await rejection(b.pick.point({ timeoutMs: 600_001 }))).code).toBe('invalid_message');
  });

  it('queues one request at a time; urgent ones go to the front without displacing the active one', async () => {
    const b = await attached();
    const seen: MockInputRequest[] = [];
    e.mod.on('inputRequest', (r) => seen.push(r));
    const first = b.pick.point({ prompt: 'first' });
    await until(() => seen.length === 1);
    const second = b.pick.point({ prompt: 'second' });
    await until(() => seen.length === 2);
    const urgent = b.pick.point({ prompt: 'urgent', urgent: true });
    await until(() => seen.length === 3);
    expect(e.mod.inputQueue().map((r) => (r.request as { prompt?: string }).prompt)).toEqual(['first', 'urgent', 'second']);
    expect(e.mod.active()?.urgent).toBe(false);
    for (const label of ['first', 'urgent', 'second']) {
      const active = e.mod.active()!;
      expect((active.request as { prompt?: string }).prompt).toBe(label);
      e.mod.resolvePick(active.id, { pos: { x: 0, y: 0, z: 0 } });
    }
    await Promise.all([first, second, urgent]);
  });
});

describe('prompts and forms', () => {
  it('answers confirm, choice, text and number prompts', async () => {
    const b = await attached();
    let queued = nextInput();
    const ok = b.prompt.confirm('Place 214 blocks?', { default: false, timeoutMs: 20_000 });
    let r = await queued;
    expect(r).toMatchObject({ type: 'prompt', kind: 'confirm' });
    expect(r.request).toMatchObject({ text: 'Place 214 blocks?', defaultValue: false, timeoutMs: 20_000, placement: 'hud' });
    e.mod.resolvePrompt(r.id, true);
    expect(await ok).toBe(true);

    queued = nextInput();
    const tool = b.prompt.choice('Which tool?', ['pickaxe', 'shovel'], { placement: 'world', at: [1, 70, 2] });
    r = await queued;
    expect(r.request).toMatchObject({ choices: ['pickaxe', 'shovel'], defaultValue: 'pickaxe', placement: 'world', at: { x: 1, y: 70, z: 2 } });
    e.mod.resolvePrompt(r.id, 'shovel');
    expect(await tool).toBe('shovel');

    queued = nextInput();
    const byIndex = b.prompt.choice('Again?', ['a', 'b', 'c']);
    e.mod.resolvePrompt((await queued).id, 2);
    expect(await byIndex).toBe('c');

    queued = nextInput();
    const name = b.prompt.text('Name?', { default: 'bob' });
    e.mod.resolvePrompt((await queued).id, 'alice');
    expect(await name).toBe('alice');

    queued = nextInput();
    const count = b.prompt.number('How many?');
    e.mod.resolvePrompt((await queued).id, 12.5);
    expect(await count).toBe(12.5);

    queued = nextInput();
    const bad = b.prompt.number('How many?');
    e.mod.resolvePrompt((await queued).id, 'many');
    expect((await rejection(bad)).code).toBe('invalid_message');
  });

  it('resolves with the default when the prompt deadline passes and reports it through onTimeout', async () => {
    const b = await attached();
    const timedOut: boolean[] = [];
    const v = await b.prompt.confirm('Continue?', { default: true, timeoutMs: 60, onTimeout: (x) => timedOut.push(x) });
    expect(v).toBe(true);
    expect(timedOut).toEqual([true]);
    expect(await b.prompt.text('Name?', { timeoutMs: 60 })).toBe('');
  });

  it('cancels a prompt through its signal with prompt.cancel', async () => {
    const b = await attached();
    const queued = nextInput();
    const ac = new AbortController();
    const p = b.prompt.text('Name?', { signal: ac.signal });
    const r = await queued;
    const cancel = e.mod.waitFor('message', (m) => m.type === 'prompt.cancel');
    ac.abort();
    expect((await rejection(p)).code).toBe('cancelled');
    expect((await cancel)[0].payload).toEqual({ request: r.req });
  });

  it('opens a form, maps field types and fills defaults for missing values', async () => {
    const b = await attached();
    const queued = nextInput();
    const pending = b.form({
      title: 'Farm settings',
      submitLabel: 'Save',
      fields: [
        { id: 'crop', type: 'select', options: ['wheat', 'carrots'] },
        { id: 'radius', type: 'slider', min: 4, max: 64, default: 16 },
        { id: 'replant', type: 'toggle', default: true },
        { id: 'extras', type: 'multi-select', options: ['bonemeal', 'fence'] },
        { id: 'chest', type: 'block-pos', required: true },
      ],
    });
    const r = await queued;
    expect(r.type).toBe('form');
    expect(r.request).toMatchObject({ title: 'Farm settings', submitLabel: 'Save' });
    expect(r.type === 'form' && r.request.fields.map((f) => f.type)).toEqual(['select', 'slider', 'toggle', 'multi_select', 'block_pos']);
    e.mod.resolveForm(r.id, { crop: 'carrots', extras: ['fence'], chest: { x: 1, y: 2, z: 3 } });
    const values = await pending;
    expect(values).toEqual({ crop: 'carrots', radius: 16, replant: true, extras: ['fence'], chest: { x: 1, y: 2, z: 3 } });
    const typed: { crop: string; radius: number; replant: boolean; extras: string[]; chest: { x: number } } = values;
    expect(typed.radius).toBe(16);
  });

  it('offers extra buttons and says which one the player pressed', async () => {
    const b = await attached();
    const queued = nextInput();
    const pending = b.form({ title: 'Chest', fields: [{ id: 'name', type: 'text', default: 'chest1' }], buttons: [{ id: 'remove', label: 'Remove container', icon: 'mineflayer_bridge:trash', danger: true }] });
    const r = await queued;
    expect(r.type === 'form' && r.request.buttons).toEqual([{ id: 'remove', label: 'Remove container', icon: 'mineflayer_bridge:trash', danger: true }]);
    e.mod.pressFormButton(r.id, 'remove');
    const v = await pending;
    expect(v).toEqual({ name: 'chest1', button: 'remove' });
    await expect(b.form({ title: 'x', fields: [{ id: 'button', type: 'text' }], buttons: [{ id: 'a', label: 'A' }] })).rejects.toThrow(/field named button/);
  });

  it('closes a form through its signal and on a local timeout', async () => {
    const b = await attached();
    let queued = nextInput();
    const ac = new AbortController();
    const aborted = b.form({ title: 'x', fields: [{ id: 'a', type: 'text' }], signal: ac.signal });
    let r = await queued;
    let close = e.mod.waitFor('message', (m) => m.type === 'form.close');
    ac.abort();
    expect((await rejection(aborted)).code).toBe('cancelled');
    expect((await close)[0].payload).toEqual({ request: r.req });

    queued = nextInput();
    close = e.mod.waitFor('message', (m) => m.type === 'form.close');
    const late = b.form({ title: 'y', fields: [], timeoutMs: 80 });
    r = await queued;
    expect((await rejection(late)).code).toBe('timeout');
    expect((await close)[0].payload).toEqual({ request: r.req });
    await until(() => e.mod.inputQueue().length === 0);
  });

  it('rejects a form with timeout at the mod deadline', async () => {
    await e.restart({ formTimeoutMs: 60 });
    const b = await attached();
    expect((await rejection(b.form({ title: 'x', fields: [] }))).code).toBe('timeout');
  });

  it('rejects malformed forms and prompts', async () => {
    const b = await attached();
    expect((await rejection(b.form({ title: 'x', fields: [{ id: 'a', type: 'text' }, { id: 'a', type: 'toggle' }] }))).code).toBe('invalid_message');
    expect((await rejection(b.prompt.choice('none?', []))).code).toBe('invalid_message');
  });
});

describe('commands and actions', () => {
  it('declares a command at ~cmd/<name> and answers invocations with invocation.result', async () => {
    const b = await attached();
    const calls: unknown[] = [];
    b.commands.register('mine', {
      description: 'Mine a block type',
      args: [
        { name: 'block', type: 'block-id' },
        { name: 'count', type: 'int', min: 1, default: 64 },
        { name: 'mode', type: 'enum', choices: ['fast', 'safe'], optional: true },
      ],
      run: (args, ctx) => {
        calls.push({ args, key: ctx.key });
        return `mining ${String(args['count'])} ${String(args['block'])}`;
      },
    });
    const key = `${ALICE_PREFIX}/~cmd/mine`;
    await until(() => e.mod.commands().length === 1);
    const [cmd] = e.mod.commands();
    expect(cmd).toMatchObject({ key, owner: ALICE, extension: 'user:default', id: 'mine', scope: 'global' });
    expect(cmd!.data).toMatchObject({
      name: 'mine',
      description: 'Mine a block type',
      args: [
        { name: 'block', type: 'block_id', optional: false, defaultValue: undefined, min: undefined, max: undefined, choices: undefined, description: undefined },
        { name: 'count', type: 'int', optional: true, defaultValue: 64, min: 1, max: undefined, choices: undefined, description: undefined },
        { name: 'mode', type: 'choice', optional: true, defaultValue: undefined, min: undefined, max: undefined, choices: ['fast', 'safe'], description: undefined },
      ],
    });
    expect(await e.mod.invokeCommand(key, { block: 'minecraft:iron_ore' })).toEqual({ ok: true, message: 'mining 64 minecraft:iron_ore' });
    expect(calls).toEqual([{ args: { block: 'minecraft:iron_ore', count: 64 }, key }]);
    expect(b.commands.has('mine')).toBe(true);
  });

  it('reports thrown errors, rejections and void results', async () => {
    const b = await attached();
    b.commands.register('boom', {
      run: () => {
        throw new Error('no pickaxe');
      },
    });
    b.commands.register('later', { run: async () => Promise.reject(new BridgeError('timeout', 'gave up')) });
    b.commands.register('quiet', { run: () => undefined });
    await until(() => e.mod.commands().length === 3);
    expect(await e.mod.invokeCommand(`${ALICE_PREFIX}/~cmd/boom`)).toEqual({ ok: false, message: 'no pickaxe' });
    expect(await e.mod.invokeCommand(`${ALICE_PREFIX}/~cmd/later`)).toEqual({ ok: false, message: 'gave up' });
    expect(await e.mod.invokeCommand(`${ALICE_PREFIX}/~cmd/quiet`)).toEqual({ ok: true, message: undefined });
  });

  it('unregisters commands, and a stale unregister function leaves a newer registration alone', async () => {
    const b = await attached();
    const off = b.commands.register('a', { run: () => 'one' });
    b.commands.register('a', { run: () => 'two' });
    off();
    b.commands.register('b', { run: () => 'b' });
    await until(() => e.mod.commands().length === 2);
    expect(await e.mod.invokeCommand(`${ALICE_PREFIX}/~cmd/a`)).toEqual({ ok: true, message: 'two' });
    b.commands.unregister('a');
    await until(() => e.mod.commands().length === 1);
    expect(() => e.mod.invokeCommand(`${ALICE_PREFIX}/~cmd/a`)).toThrow(/no core:command/);
    expect(() => b.commands.register('two words', { run: () => undefined })).toThrow(BridgeError);
  });

  it('declares actions with target specs and world or global scope, and passes the target to run', async () => {
    const b = await attached();
    const targets: Target[] = [];
    b.actions.register('deposit', {
      label: 'Deposit here',
      icon: 'minecraft:chest',
      target: { type: 'block', tags: ['c:chests'] },
      order: 2,
      run: (t) => {
        targets.push(t);
        return 'deposited';
      },
    });
    b.actions.register('summon', { label: 'Summon', target: { type: 'bot' }, scope: 'global', run: async () => 'on my way' });
    await until(() => e.mod.actions().length === 2);
    const [deposit, summon] = e.mod.actions();
    expect(deposit).toMatchObject({ id: 'deposit', scope: 'world', world: OVERWORLD, data: { label: 'Deposit here', icon: 'minecraft:chest', order: 2, target: { type: 'block', tags: ['c:chests'] } } });
    expect(summon).toMatchObject({ id: 'summon', scope: 'global', world: undefined });
    const result = await e.mod.invokeAction(deposit!.key, { type: 'block', block: { x: 3, y: 64, z: 1 }, face: 'north' });
    expect(result).toEqual({ ok: true, message: 'deposited' });
    expect(targets).toEqual([{ type: 'block', block: { x: 3, y: 64, z: 1 }, face: 'north' }]);
    expect(await e.mod.invokeAction(summon!.key, { type: 'bot', objectKey: ALICE })).toEqual({ ok: true, message: 'on my way' });
  });

  it('tells the action how it was run and which bots were selected', async () => {
    const b = await attached();
    const seen: unknown[] = [];
    b.actions.register('attack', { label: 'Attack', target: { type: 'entity', tags: ['mineflayer_bridge:hostile'] }, run: (_t, ctx) => void seen.push([ctx.source, ctx.bots]) });
    await until(() => e.mod.actions().length === 1);
    const [attack] = e.mod.actions();
    await e.mod.invokeAction(attack!.key, { type: 'entity', entity: 7 }, undefined, { source: 'map', bots: ['eco1', 'eco2'] });
    await e.mod.invokeAction(attack!.key, { type: 'entity', entity: 7 });
    expect(seen).toEqual([['map', ['eco1', 'eco2']], [undefined, undefined]]);
  });

  it('ends an action quietly when the player cancels the form it waits on', async () => {
    const b = await attached();
    b.actions.register('rename', { label: 'Rename', target: { type: 'block' }, run: async () => (await b.form({ title: 'Name', fields: [{ id: 'n', type: 'text' }] }), 'renamed') });
    await until(() => e.mod.actions().length === 1);
    const queued = nextInput();
    const result = e.mod.invokeAction(e.mod.actions()[0]!.key, { type: 'block', block: { x: 0, y: 0, z: 0 }, face: 'up' });
    e.mod.cancel((await queued).id);
    expect(await result).toEqual({ ok: true, quiet: true });
  });

  it('lets global actions leave the target out and rejects that for world actions', async () => {
    const b = await attached();
    const seen: Target[] = [];
    b.actions.register('add-bot', { label: 'Add bot', scope: 'global', run: (t) => void seen.push(t) });
    await until(() => e.mod.actions().length === 1);
    const [add] = e.mod.actions();
    expect(add).toMatchObject({ id: 'add-bot', scope: 'global', data: { label: 'Add bot', target: { type: NO_TARGET } } });
    expect(await e.mod.invokeAction(add!.key, { type: NO_TARGET })).toEqual({ ok: true });
    expect(seen).toEqual([{ type: 'none' }]);
    expect(() => b.actions.register('bad', { label: 'Bad', run: () => undefined })).toThrow(/only global actions may leave the target out/);
    expect(() => b.actions.register('bad', { label: 'Bad', scope: 'galaxy' as 'global', run: () => undefined })).toThrow(/scope must be world or global/);
  });

  it('times out an invocation the bot never answers', async () => {
    const b = await attached();
    b.commands.register('hang', { run: () => new Promise<string>(() => {}) });
    await until(() => e.mod.commands().length === 1);
    const err = await rejection(e.mod.invokeCommand(`${ALICE_PREFIX}/~cmd/hang`, {}, 80));
    expect(err.code).toBe('timeout');
  });
});

describe('keybinds and tools', () => {
  it('declares keybinds with normalised keys and inferred activation, and routes press and release', async () => {
    const b = await attached();
    const events: string[] = [];
    b.keybinds.register('stop', { label: 'Stop', keys: ['g'], onPress: () => events.push('stop') });
    b.keybinds.register('boost', { label: 'Boost', keys: ['key.keyboard.left.shift', 'B'], priority: 5, consumes: false, context: 'bot-selected', onPress: () => events.push('down'), onRelease: () => events.push('up') });
    b.keybinds.register('drop', { label: 'Drop', keys: ['q'], onRelease: () => events.push('dropped') });
    await until(() => e.mod.keybinds().length === 3);
    const byId = Object.fromEntries(e.mod.keybinds().map((k) => [k.id, k.data]));
    expect(byId['stop']).toMatchObject({ keys: ['key.keyboard.g'], activation: 'press', context: 'ingame', priority: 0, consumes: true });
    expect(byId['boost']).toMatchObject({ keys: ['key.keyboard.left.shift', 'key.keyboard.b'], activation: 'both', context: 'bot-selected', priority: 5, consumes: false });
    expect(byId['drop']).toMatchObject({ activation: 'release' });
    e.mod.keybindEvent(`${ALICE_PREFIX}/~key/boost`, true);
    e.mod.keybindEvent(`${ALICE_PREFIX}/~key/boost`, false);
    e.mod.keybindEvent(`${ALICE_PREFIX}/~key/stop`, true);
    e.mod.keybindEvent(`${ALICE_PREFIX}/~key/drop`, false);
    await until(() => events.length === 4);
    expect(events).toEqual(['down', 'up', 'stop', 'dropped']);
  });

  it('reports a throwing keybind callback as an error event', async () => {
    const b = await attached();
    const errors: Error[] = [];
    b.on('error', (err) => errors.push(err));
    b.keybinds.register('bad', {
      label: 'Bad',
      keys: ['k'],
      onPress: () => {
        throw new Error('oops');
      },
    });
    await until(() => e.mod.keybinds().length === 1);
    e.mod.keybindEvent(`${ALICE_PREFIX}/~key/bad`, true);
    await until(() => errors.length === 1);
    expect(errors[0]?.message).toBe('oops');
  });

  it('declares tools, routes tool events with targets, and follows tool.state', async () => {
    const b = await attached();
    const log: unknown[] = [];
    let radius = 1;
    b.tools.register('mark-ore', {
      label: 'Mark ore',
      icon: 'minecraft:diamond_ore',
      on: {
        primary: (t) => log.push(['mark', t?.block]),
        secondary: (t, use) => log.push(['unmark', t?.block, use.input]),
        scroll: (d) => (radius += d),
        key: (k) => log.push(['key', k]),
      },
      onActivate: () => log.push('on'),
      onDeactivate: () => log.push('off'),
    });
    b.tools.register('other', { label: 'Other', targets: ['entity'], inputs: ['primary'], onActivate: () => log.push('other on') });
    await until(() => e.mod.tools().length === 2);
    const [mark, other] = e.mod.tools();
    expect(mark!.data).toEqual({ label: 'Mark ore', icon: 'minecraft:diamond_ore', targets: ['block'], inputs: ['primary', 'secondary', 'scroll', 'key'] });
    expect(other!.data).toMatchObject({ targets: ['entity'], inputs: ['primary'] });

    e.mod.setActiveTool(mark!.key);
    await until(() => b.tools.active() === 'mark-ore');
    e.mod.toolEvent(mark!.key, 'primary', { target: { type: 'block', block: { x: 1, y: 2, z: 3 } } });
    e.mod.toolEvent(mark!.key, 'secondary', { target: { type: 'block', block: { x: 4, y: 5, z: 6 } } });
    e.mod.toolEvent(mark!.key, 'scroll', { delta: 3 });
    e.mod.toolEvent(mark!.key, 'key', { key: 'key.keyboard.r' });
    await until(() => log.length === 4);
    e.mod.setActiveTool(other!.key);
    await until(() => b.tools.active() === 'other');
    e.mod.setActiveTool(null);
    await until(() => b.tools.active() === undefined);
    expect(log).toEqual(['on', ['mark', { x: 1, y: 2, z: 3 }], ['unmark', { x: 4, y: 5, z: 6 }, 'secondary'], ['key', 'key.keyboard.r'], 'off', 'other on']);
    expect(radius).toBe(4);
  });
});

describe('settings', () => {
  it('declares a settings group, starts from defaults and emits a change per changed key', async () => {
    const b = await attached();
    const settings = b.settings.define(
      {
        radius: { type: 'int', min: 4, max: 128, default: 32, label: 'Mining radius' },
        replant: { type: 'bool', default: true },
        color: { type: 'color', default: '#3b82f6', label: 'Path color' },
        mode: { type: 'choice', options: ['fast', 'safe'] },
      },
      { title: 'Miner' },
    );
    expect(settings.all()).toEqual({ radius: 32, replant: true, color: 0xff3b82f6, mode: 'fast' });
    const key = `${ALICE_PREFIX}/~settings/default`;
    await until(() => e.mod.settingsGroups().length === 1);
    const [group] = e.mod.settingsGroups();
    expect(group!.key).toBe(key);
    expect(group!.data.title).toBe('Miner');
    expect(group!.data.settings.map((s) => [s.key, s.type, s.defaultValue])).toEqual([
      ['radius', 'int', 32],
      ['replant', 'bool', true],
      ['color', 'color', 0xff3b82f6],
      ['mode', 'choice', 'fast'],
    ]);

    const changes: [string, unknown][] = [];
    settings.on('change', (k, v) => changes.push([k, v]));
    e.mod.setSettingValue(key, 'radius', 64);
    await until(() => changes.length === 1);
    expect(settings.get('radius')).toBe(64);
    const radius: number = settings.get('radius');
    expect(radius).toBe(64);

    e.mod.setSettingValue(key, 'radius', 1000);
    e.mod.setSettingValue(key, 'mode', 'safe');
    await until(() => changes.length === 3);
    expect(changes).toEqual([
      ['radius', 64],
      ['radius', 32],
      ['mode', 'safe'],
    ]);
  });

  it('uses groups and receives the player values again after a reconnect', async () => {
    const b = await attached();
    const a = b.settings.define({ speed: { type: 'float', default: 1 } }, { group: 'movement' });
    const key = `${ALICE_PREFIX}/~settings/movement`;
    await until(() => e.mod.settingsGroups().length === 1);
    e.mod.setSettingValue(key, 'speed', 2.5);
    await until(() => a.get('speed') === 2.5);
    e.mod.expireSession(ALICE);
    e.mod.setSettingValue(key, 'speed', 3.5);
    await until(() => b.state.connected);
    await until(() => a.get('speed') === 3.5);
    expect(a.key).toBe('user:default/~settings/movement');
  });

  it('rejects defaults that do not fit', async () => {
    const b = await attached();
    expect(() => b.settings.define({ mode: { type: 'choice', options: ['a'], default: 'b' } })).toThrow(BridgeError);
    expect(() => b.settings.define({ n: { type: 'int', min: 1, default: 0 } })).toThrow(BridgeError);
  });
});

describe('declared state', () => {
  it('replays input and HUD declarations after the mod restarts', async () => {
    const b = await attached();
    b.commands.register('mine', { run: () => 'ok' });
    b.keybinds.register('stop', { label: 'Stop', keys: ['g'] });
    b.tools.register('mark', { label: 'Mark' });
    b.actions.register('come', { label: 'Come', target: { type: 'air' }, run: () => undefined });
    b.settings.define({ on: { type: 'bool' } });
    b.hud('main', { title: 'Alice' }).kv('stats', { Task: 'Mining' });
    b.bossbar('task', { text: 'Mining', progress: 0.5 });
    await until(() => e.mod.objects(ALICE_PREFIX).length === 8);
    await e.restart();
    e.mod.setPlayerContext(OVERWORLD);
    await until(() => e.mod.objects(ALICE_PREFIX).length === 8, 5000, 'replay');
    expect(e.mod.commands().map((c) => c.id)).toEqual(['mine']);
    expect(e.mod.hudPanels()[0]?.widgets.map((w) => w.id)).toEqual(['stats']);
    expect(e.mod.bossbars()[0]?.data).toMatchObject({ text: 'Mining', progress: 0.5 });
    await until(() => b.state.attached);
    expect(await e.mod.invokeCommand(`${ALICE_PREFIX}/~cmd/mine`)).toEqual({ ok: true, message: 'ok' });
  });

  it('removes everything an extension declared on unload and rejects its pending input requests', async () => {
    let ctx!: ExtensionContext;
    const ext = defineExtension({
      id: 'acme:miner',
      version: '1.0.0',
      setup(c) {
        ctx = c;
        c.commands.register('mine', { run: () => 'ok' });
        c.keybinds.register('stop', { label: 'Stop', keys: ['g'] });
        c.tools.register('mark', { label: 'Mark', onDeactivate: () => undefined });
        c.settings.define({ on: { type: 'bool' } });
        c.hud('main').text('status', 'idle');
        c.bossbar('task', { text: 'Mining' });
      },
    });
    const b = await attached();
    await b.use(ext);
    const extPrefix = `${ALICE}/acme:miner/`;
    await until(() => e.mod.objects(extPrefix).length === 7);
    expect(e.mod.commands()[0]?.key).toBe(`${extPrefix}~cmd/mine`);
    expect(e.mod.hudPanels()[0]).toMatchObject({ extension: 'acme:miner', id: 'main' });

    const queued = nextInput();
    const pick = ctx.pick.block();
    const prompt = ctx.prompt.confirm('sure?');
    const r = await queued;
    const cancels: unknown[] = [];
    e.mod.on('message', (m) => {
      if (m.type === 'pick.cancel' || m.type === 'prompt.cancel') cancels.push(m.payload);
    });
    await until(() => e.mod.inputQueue().length === 2);
    const remove = e.mod.waitFor('message', (m) => m.type === 'obj.remove');
    await b.unload('acme:miner');
    expect((await rejection(pick)).code).toBe('cancelled');
    expect((await rejection(prompt)).code).toBe('cancelled');
    expect((await remove)[0].payload).toEqual({ keys: [], prefixes: [extPrefix] });
    await until(() => e.mod.objects(extPrefix).length === 0);
    await until(() => cancels.length === 2);
    expect(cancels).toContainEqual({ request: r.req });
    expect(e.mod.inputQueue()).toEqual([]);
    expect(() => e.mod.invokeCommand(`${extPrefix}~cmd/mine`)).toThrow(/no core:command/);
  });

  it('answers an invocation for an unknown declaration with ok false', async () => {
    const b = await attached();
    const ext = defineExtension({
      id: 'acme:ghost',
      version: '1.0.0',
      setup(c) {
        c.commands.register('boo', { run: () => 'boo' });
      },
    });
    await b.use(ext);
    await until(() => e.mod.commands().length === 1);
    const key = e.mod.commands()[0]!.key;
    // The route goes at once; the mock still holds the object until the removal arrives.
    const unloaded = b.unload('acme:ghost');
    const result = e.mod.invokeCommand(key, {}, 2000);
    await unloaded;
    expect(await result).toEqual({ ok: false, message: 'not available' });
  });
});
