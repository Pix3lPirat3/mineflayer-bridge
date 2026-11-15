import { WireReader } from '@mineflayer-bridge/protocol';
import { describe, expect, it } from 'vitest';
import { botConsole, ConsoleMessages, LOCAL_DEFAULT_PERMISSIONS, type BotConsoleApi, type BridgeError } from '../src/index.ts';
import { ALICE, FakeBot, fixture, load, OVERWORLD, rejection, sleep, until, type Env } from './helpers.ts';

let e: Env;
const f = fixture((x) => (e = x.e));
const { hub, attached } = f;

/** A clock the test moves by hand. */
function manualClock(): { now(): number; advance(ms: number): void } {
  let t = 1_000_000;
  return { now: () => t, advance: (ms) => void (t += ms) };
}

/** Takes `permissions` away from the device of the first session and waits until `b` knows. */
async function revoke(b: { can(p: never): boolean }, ...permissions: string[]): Promise<void> {
  const deviceId = e.mod.sessions()[0]!.deviceId;
  e.mod.setPermissions(deviceId, LOCAL_DEFAULT_PERMISSIONS.filter((p) => !permissions.includes(p)));
  await until(() => permissions.every((p) => !b.can(p as never)));
}

describe('rules the mock shares with the mod', () => {
  it('asks for the chat permission before a prompt may take the chat', async () => {
    const b = await attached();
    await revoke(b, 'chat');
    const err = await rejection(b.prompt.text('Name?', { surface: 'chat' }));
    expect(err.code).toBe('permission_denied');
    expect(err.details).toMatchObject({ permission: 'chat' });
    // Other surfaces only need input.pick.
    const queued = e.mod.waitFor('inputRequest');
    const answer = b.prompt.text('Name?');
    e.mod.resolvePrompt((await queued)[0].id, 'Steve');
    expect(await answer).toBe('Steve');
  });

  it('gives the chat back after 60 s and counts the unanswered prompt as dismissed', async () => {
    const clock = manualClock();
    await e.restart({ clock, surfaceCooldownsMs: [5000] });
    const b = await attached();
    const queued = e.mod.waitFor('inputRequest');
    const settled = e.mod.waitFor('inputSettled');
    const answer = b.prompt.text('Name?', { surface: 'chat', default: 'nobody', timeoutMs: 600_000 });
    expect((await queued)[0]).toMatchObject({ type: 'prompt', surface: 'chat' });
    clock.advance(59_000);
    await sleep(60);
    expect(e.mod.active()).toBeDefined();
    clock.advance(1000);
    expect((await settled)[0].outcome).toBe('timeout');
    expect(await answer).toBe('nobody');
    expect((await rejection(b.prompt.confirm('Still there?'))).code).toBe('cooldown');
  });

  it('leaves a prompt on another surface open past 60 s', async () => {
    const clock = manualClock();
    await e.restart({ clock });
    const b = await attached();
    const queued = e.mod.waitFor('inputRequest');
    const answer = b.prompt.text('Name?', { timeoutMs: 600_000 });
    const id = (await queued)[0].id;
    clock.advance(120_000);
    await sleep(60);
    expect(e.mod.active()?.id).toBe(id);
    e.mod.resolvePrompt(id, 'Alex');
    expect(await answer).toBe('Alex');
  });

  it('refuses a session beyond the device limit with quota_exceeded', async () => {
    await e.restart({ maxSessionsPerDevice: 2 });
    const h = hub();
    const a = load(h.plugin(), new FakeBot('Alice'));
    const b = load(h.plugin(), new FakeBot('Bob'));
    await until(() => a.state.connected && b.state.connected);
    const c = load(h.plugin(), new FakeBot('Carol'));
    const errors: BridgeError[] = [];
    c.on('error', (err) => errors.push(err as BridgeError));
    const err = await rejection(c.ready);
    expect(err.code).toBe('quota_exceeded');
    expect(err.message).toContain('2 sessions');
    expect(e.mod.sessions().map((s) => s.name).sort()).toEqual(['Alice', 'Bob']);
    // A closed session frees its place.
    await a.close();
    await until(() => e.mod.sessions().length === 1);
  });

  it('cuts notify text to 4096 characters and 20 lines', async () => {
    const b = await attached();
    b.notify('x'.repeat(5000));
    b.notify(Array.from({ length: 30 }, (_, i) => `line ${i + 1}`).join('\n'));
    b.notify('short');
    await until(() => e.mod.notifies.length === 3);
    const [long, tall, short] = e.mod.notifies.map((n) => n.text);
    expect(long).toHaveLength(4096);
    expect(tall!.split('\n')).toHaveLength(20);
    expect(tall!.endsWith('line 20')).toBe(true);
    expect(short).toBe('short');
  });

  it('takes console lines only from an attached session that may notify', async () => {
    const lines = (): string[] =>
      e.mod
        .extensionMessages(ALICE)
        .filter((m) => m.type === 'bridge:console.lines')
        .flatMap((m) => ConsoleMessages['bridge:console.lines'].codec.read(new WireReader(m.payload)).lines.map((l) => l.text));
    const b = load(hub().plugin({ extensions: [botConsole({ flushMs: 10 })] }), new FakeBot('Alice'));
    e.mod.setPlayerContext(OVERWORLD);
    await until(() => b.state.attached && b.has('bridge:console'));
    const api = b.get<BotConsoleApi>('bridge:console')!;
    api.info('one');
    await until(() => lines().includes('one'));

    await revoke(b, 'notify');
    const denied = e.mod.waitFor('permissionDenied', (d) => d.permission === 'notify');
    api.info('two');
    expect((await denied)[0].type).toBe('bridge:console.lines');
    expect(lines()).toEqual(['one']);

    e.mod.setPermissions(e.mod.sessions()[0]!.deviceId, LOCAL_DEFAULT_PERMISSIONS);
    await until(() => b.can('notify'));
    e.mod.setAttached('alice@play.example.net:25565', false);
    await until(() => !b.state.attached);
    const refused = e.mod.waitFor('protocolError', (p) => p.code === 'not_attached');
    api.info('three');
    await refused;
    expect(lines()).toEqual(['one']);
  });

  it('counts titles and action bars against effectsPerSecond with sounds and particles', async () => {
    await e.restart({ quotas: { effectsPerSecond: 4 } });
    const b = await attached();
    expect(b.limits.effectsPerSecond).toBe(4);
    b.effects.sound('block.note_block.pling');
    b.effects.particles('flame', { at: [0, 64, 0] });
    b.effects.title('three');
    b.effects.actionBar('four');
    b.effects.title('five');
    b.effects.actionBar('six');
    await until(() => e.mod.effects().length === 4);
    await sleep(50);
    expect(e.mod.effects().map((x) => x.effect.type)).toEqual(['sound', 'particles', 'title', 'actionbar']);
    expect(f.logger.warnings.filter((w) => w.includes('dropping effects above 4 per second'))).toHaveLength(1);
  });
});
