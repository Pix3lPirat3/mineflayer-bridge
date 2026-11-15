import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createBridge, type BotBridge, type BridgeHub, type BridgePlayer, type InvocationContext } from '../src/index.ts';
import { ALICE, ALICE_PREFIX, env, FakeBot, load, OVERWORLD, quietLogger, sleep, teardown, until, type Env } from './helpers.ts';

/** Two players, each running the mod, watching the same bots through one hub. */
let alex: Env;
let sam: Env;
let hubs: BridgeHub[] = [];

beforeEach(async () => {
  alex = await env();
  sam = await env();
  hubs = [];
});

afterEach(async () => {
  for (const h of hubs) await h.close();
  await teardown(alex);
  await teardown(sam);
});

const RECONNECT = { initialDelayMs: 20, maxDelayMs: 100 };
const BOX = `${ALICE_PREFIX}/route/a`;
const CMD = `${ALICE_PREFIX}/~cmd/who`;

function twoLinks(extra: { reconnect?: false } = {}): BridgeHub {
  const h = createBridge({
    credentials: alex.credentials,
    logger: quietLogger(),
    reconnect: extra.reconnect ?? RECONNECT,
    links: [
      { id: 'alex', endpointFile: alex.endpointFile },
      { id: 'sam', endpointFile: sam.endpointFile },
    ],
  });
  hubs.push(h);
  return h;
}

/** Loads Alice on `h` and waits until both players have her attached and in their world. */
async function attachedBoth(h: BridgeHub): Promise<BotBridge> {
  const b = load(h.plugin(), new FakeBot('Alice'));
  alex.mod.setPlayerContext(OVERWORLD);
  sam.mod.setPlayerContext(OVERWORLD);
  await until(() => b.players.length === 2 && b.players.every((p) => p.attached && p.worldMatched), 5000, 'both players attached');
  return b;
}

describe('one hub linked to two players', () => {
  it('keeps the single-link shape: one default link and one player', async () => {
    const h = createBridge({ endpointFile: alex.endpointFile, credentials: alex.credentials, logger: quietLogger(), reconnect: RECONNECT });
    hubs.push(h);
    const b = load(h.plugin(), new FakeBot('Alice'));
    alex.mod.setPlayerContext(OVERWORLD);
    await until(() => b.state.attached);
    expect(h.links.map((l) => l.id)).toEqual(['default']);
    expect(b.players.map((p) => p.id)).toEqual(['default']);
    expect(b.player('default')?.attached).toBe(true);
    expect(b.origin).toBeUndefined();
  });

  it('replicates declared objects to every link, including updates and removals', async () => {
    const h = twoLinks();
    const b = await attachedBoth(h);
    expect(h.links.map((l) => l.id)).toEqual(['alex', 'sam']);
    const layer = b.layer('route');
    layer.box('a', [1, 2, 3], 1);
    await until(() => alex.mod.object(BOX) && sam.mod.object(BOX), 5000, 'box on both');
    layer.box('a', [5, 5, 5], 1);
    await until(() => [alex, sam].every((x) => x.mod.objectData(BOX, 'core:box')?.min.x === 5), 5000, 'update on both');
    layer.clear();
    await until(() => alex.mod.object(BOX) === undefined && sam.mod.object(BOX) === undefined, 5000, 'removal on both');
  });

  it('routes input from each player to the same handler, tagged with that player, and answers only the invoker', async () => {
    const h = twoLinks();
    const b = await attachedBoth(h);
    const seen: (string | undefined)[] = [];
    b.commands.register('who', {
      run: (_args, ctx: InvocationContext) => {
        seen.push(ctx.player?.id);
        expect(b.origin?.id).toBe(ctx.player?.id);
        return `hi ${ctx.player?.id ?? '?'}`;
      },
    });
    await until(() => alex.mod.object(CMD) && sam.mod.object(CMD), 5000, 'command on both');
    expect(await alex.mod.invokeCommand(CMD)).toEqual({ ok: true, message: 'hi alex' });
    expect(await sam.mod.invokeCommand(CMD)).toEqual({ ok: true, message: 'hi sam' });
    expect(seen).toEqual(['alex', 'sam']);
    // Outside a handler there is no origin.
    expect(b.origin).toBeUndefined();
  });

  it('sends a prompt made inside a handler to the invoking player only', async () => {
    const h = twoLinks();
    const b = await attachedBoth(h);
    b.commands.register('who', {
      run: async () => ((await b.prompt.confirm('sure?', { timeoutMs: 10_000 })) ? 'yes' : 'no'),
    });
    await until(() => sam.mod.object(CMD), 5000, 'command on sam');
    const queued = sam.mod.waitFor('inputRequest');
    const result = sam.mod.invokeCommand(CMD, {}, 10_000);
    const [r] = await queued;
    await sleep(50);
    expect(alex.mod.inputQueue()).toEqual([]);
    sam.mod.resolvePrompt(r.id, true);
    expect(await result).toEqual({ ok: true, message: 'yes' });
  });

  it('asks every player for a bot-initiated prompt, takes the first answer and withdraws the others', async () => {
    const h = twoLinks();
    const b = await attachedBoth(h);
    const both = Promise.all([alex.mod.waitFor('inputRequest'), sam.mod.waitFor('inputRequest')]);
    const answer = b.prompt.choice('Which?', ['a', 'b'], { timeoutMs: 10_000 });
    const [[ra], [rs]] = await both;
    const withdrawn = alex.mod.waitFor('inputSettled');
    sam.mod.resolvePrompt(rs.id, 'b');
    expect(await answer).toBe('b');
    const [settled] = await withdrawn;
    expect(settled.request.id).toBe(ra.id);
    expect(settled.outcome).toBe('withdrawn');
  });

  it('targets one player with player.run and every player outside it', async () => {
    const h = twoLinks();
    const b = await attachedBoth(h);
    b.player('sam')!.run(() => b.notify('only sam'));
    b.notify('everyone');
    await until(() => alex.mod.notifies.length === 1 && sam.mod.notifies.length === 2, 5000, 'toasts');
    expect(alex.mod.notifies.map((n) => n.text)).toEqual(['everyone']);
    expect(sam.mod.notifies.map((n) => n.text)).toEqual(['only sam', 'everyone']);
    // everyone() leaves a handler's player.
    b.player('alex')!.run(() => b.everyone(() => b.notify('both again')));
    await until(() => alex.mod.notifies.length === 2 && sam.mod.notifies.length === 3, 5000, 'broadcast from inside run');
  });

  it('keeps per-player state and combines it into the singular state', async () => {
    const h = twoLinks();
    const b = load(h.plugin(), new FakeBot('Alice'));
    await until(() => b.players.every((p) => p.connected), 5000, 'both open');
    expect(b.state.attached).toBe(false);
    sam.mod.setPlayerContext(OVERWORLD);
    await until(() => b.player('sam')?.attached === true, 5000, 'sam attached');
    const players: Record<string, BridgePlayer> = Object.fromEntries(b.players.map((p) => [p.id, p]));
    expect(players.alex!.attached).toBe(false);
    expect(players.sam!.worldMatched).toBe(true);
    expect(players.sam!.playerWorld).toEqual(OVERWORLD);
    expect(b.state.attached).toBe(true);
    expect(b.state.worldMatched).toBe(true);
    expect(b.state.playerWorld).toEqual(OVERWORLD);
    expect(players.sam!.name).toBeUndefined();
    sam.mod.setPlayerName('Sam');
    await until(() => b.player('sam')?.name === 'Sam', 5000, 'sam named');
    expect(b.player('alex')?.name).toBeUndefined();
    const detached = new Promise<void>((resolve) => b.on('detached', resolve));
    sam.mod.setPlayerContext(null);
    await detached;
    expect(b.state.attached).toBe(false);
  });

  it('keeps the other link working while one drops, then resumes the dropped one with what it missed', async () => {
    const h = twoLinks();
    const b = await attachedBoth(h);
    const layer = b.layer('route');
    layer.box('a', [0, 0, 0], 1);
    await until(() => alex.mod.object(BOX) && sam.mod.object(BOX), 5000, 'box on both');
    const events: string[] = [];
    b.on('disconnected', () => events.push('disconnected'));
    const resumed = alex.mod.waitFor('sessionResumed');
    let back = false;
    void resumed.then(() => (back = true));
    alex.mod.disconnect(ALICE);
    // The link comes back within milliseconds, so the gap can be missed: either state is fine here.
    await until(() => b.player('alex')?.connected === false || back, 5000, 'alex lost');
    expect(b.state.connected).toBe(true);
    expect(b.state.attached).toBe(true);
    expect(b.player('sam')?.connected).toBe(true);
    layer.box('b', [1, 1, 1], 1);
    await until(() => sam.mod.object(`${ALICE_PREFIX}/route/b`), 5000, 'new box on sam while alex is down');
    const [info] = await resumed;
    expect(info.owner).toBe(ALICE);
    await until(() => alex.mod.object(`${ALICE_PREFIX}/route/b`), 5000, 'alex catches up after resume');
    expect(events).toEqual([]);
    expect(alex.mod.sessions()).toHaveLength(1);
  });

  it('opens a fresh session with a full snapshot on one link without touching the other', async () => {
    const h = twoLinks();
    const b = await attachedBoth(h);
    b.layer('route').box('a', [0, 0, 0], 1);
    await until(() => alex.mod.object(BOX) && sam.mod.object(BOX), 5000, 'box on both');
    const reopened = alex.mod.waitFor('snapshotEnd');
    let samOpens = 0;
    sam.mod.waitFor('sessionOpened', () => (samOpens++, false), 2000).catch(() => undefined);
    alex.mod.expireSession(ALICE);
    await reopened;
    await until(() => alex.mod.object(BOX), 5000, 'box back on alex');
    expect(sam.mod.object(BOX)).toBeDefined();
    expect(samOpens).toBe(0);
  });

  it('adds a link at runtime with the declared state, and closes one link without affecting the other', async () => {
    const h = createBridge({ endpointFile: alex.endpointFile, credentials: alex.credentials, logger: quietLogger(), reconnect: RECONNECT });
    hubs.push(h);
    const b = load(h.plugin(), new FakeBot('Alice'));
    b.layer('route').box('a', [0, 0, 0], 1);
    await until(() => alex.mod.object(BOX), 5000, 'box on alex');
    const link = h.link({ id: 'sam', endpointFile: sam.endpointFile });
    expect(() => h.link({ id: 'sam', endpointFile: sam.endpointFile })).toThrow(/already has a link/);
    await until(() => sam.mod.object(BOX), 5000, 'snapshot to the new link');
    expect(b.players.map((p) => p.id)).toEqual(['default', 'sam']);
    await until(() => link.connected, 5000, 'sam link connected');

    const closed = sam.mod.waitFor('sessionClosed');
    await link.close();
    await closed;
    expect(sam.mod.object(BOX)).toBeUndefined();
    expect(h.links.map((l) => l.id)).toEqual(['default']);
    expect(b.players.map((p) => p.id)).toEqual(['default']);
    b.layer('route').box('c', [2, 2, 2], 1);
    await until(() => alex.mod.object(`${ALICE_PREFIX}/route/c`), 5000, 'alex still gets writes');
    expect(b.state.connected).toBe(true);
  });

  it('opens controllers on every link', async () => {
    const h = twoLinks();
    const ctl = h.controller({ name: 'dash' });
    await until(() => ctl.players.length === 2 && ctl.players.every((p) => p.connected), 5000, 'controller on both');
    expect(alex.mod.sessions().filter((s) => s.kind === 'controller')).toHaveLength(1);
    expect(sam.mod.sessions().filter((s) => s.kind === 'controller')).toHaveLength(1);
  });
});
