import { describe, expect, it } from 'vitest';
import { encodeExtension } from '../src/ext-wire.ts';
import { ping, PingMessages, statusTag, type BotBridge, type BridgePingMark, type Ping, type PingApi, type PlayerChange, type StatusTagApi } from '../src/index.ts';
import { ALICE, FakeBot, fixture, load, OVERWORLD, until, type Env } from './helpers.ts';

let e: Env;
const { hub, attached } = fixture((f) => (e = f.e));

async function bot(extensions: unknown[] = []): Promise<BotBridge> {
  const b = load(hub().plugin({ extensions: extensions as never }), new FakeBot('Alice'));
  e.mod.setPlayerContext(OVERWORLD);
  await until(() => b.state.attached && b.state.worldMatched);
  return b;
}

describe('notify', () => {
  it('sends buttons that run on click, with a sound', async () => {
    const b = await attached();
    const clicks: string[] = [];
    b.notify('Inventory full', { level: 'warn', sound: 'block.note_block.bell', buttons: [{ label: 'Dump', run: () => (clicks.push('dump'), 'dumping') }, { label: 'Ignore', run: () => void clicks.push('ignore') }] });
    await until(() => e.mod.notifies.length === 1);
    const n = e.mod.notifies[0]!;
    expect(n).toMatchObject({ owner: ALICE, text: 'Inventory full', level: 'warn', sound: 'block.note_block.bell' });
    expect(n.buttons.map((x) => x.label)).toEqual(['Dump', 'Ignore']);
    const action = n.buttons[0]!.action!;
    expect(action.startsWith(`${ALICE}/user:default/~action/`)).toBe(true);
    await until(() => e.mod.object(action));
    expect(await e.mod.invokeAction(action, { type: 'notification' })).toEqual({ ok: true, message: 'dumping' });
    expect(clicks).toEqual(['dump']);
  });

  it('sends buttons that fill the chat box, next to ones that run', async () => {
    const b = await attached();
    b.notify('mine stone 3: done', { buttons: [{ label: 'Again', suggest: '@Alice mine stone 3' }, { label: 'Jobs', run: () => 'none' }] });
    await until(() => e.mod.notifies.length === 1);
    const [again, jobs] = e.mod.notifies[0]!.buttons;
    expect(again).toEqual({ label: 'Again', action: undefined, suggest: '@Alice mine stone 3' });
    expect(jobs).toMatchObject({ label: 'Jobs', suggest: undefined });
    expect(() => b.notify('x', { buttons: [{ label: 'Go', suggest: '' }] })).toThrow(/1 to 256/);
    expect(() => b.notify('x', { buttons: [{ label: 'Go', suggest: 'x'.repeat(257) }] })).toThrow(/1 to 256/);
    expect(() => b.notify('x', { buttons: [{ label: 'Go', suggest: '@a', run: () => undefined } as never] })).toThrow(/run function or a line/);
    expect(() => b.notify('x', { buttons: [{ label: 'Go' } as never] })).toThrow(/run function or a line/);
  });

  it('keeps only the latest notifications clickable and checks buttons', async () => {
    await e.restart({ quotas: { notifiesPerSecond: 100 } });
    const b = await attached();
    for (let i = 0; i < 34; i++) b.notify(`n${i}`, { buttons: [{ label: 'Go', run: () => undefined }] });
    await until(() => e.mod.notifies.length === 34);
    const first = e.mod.notifies[0]!.buttons[0]!.action!;
    const last = e.mod.notifies[33]!.buttons[0]!.action!;
    await until(() => e.mod.object(last) !== undefined && e.mod.object(first) === undefined);
    expect(() => b.notify('x', { buttons: [{ label: '', run: () => undefined }] })).toThrow(/label/);
    const five = Array.from({ length: 5 }, () => ({ label: 'b', run: () => undefined }));
    expect(() => b.notify('x', { buttons: five })).toThrow(/at most 4/);
  });
});

describe('player events', () => {
  it('reports joining, dying, respawning, away, back and window focus', async () => {
    const b = await bot();
    const seen: PlayerChange[] = [];
    b.on('player', (change, player) => {
      seen.push(change);
      expect(player.id).toBe('default');
    });
    e.mod.setPlayerState({ alive: false });
    await until(() => seen.includes('died'));
    expect(b.players[0]!.alive).toBe(false);
    e.mod.setPlayerState({ alive: true, away: true });
    await until(() => seen.includes('away'));
    e.mod.setPlayerState({ away: false, windowFocused: false });
    await until(() => seen.includes('blur'));
    e.mod.setPlayerContext({ server: OVERWORLD.server, dimension: 'minecraft:the_nether' });
    await until(() => seen.includes('world'));
    e.mod.setPlayerContext(null);
    await until(() => seen.includes('left'));
    expect(seen).toEqual(['died', 'respawned', 'away', 'back', 'blur', 'world', 'left']);
    expect(b.players[0]!.inWorld).toBe(false);
  });
});

describe('bridge:ping', () => {
  it('hears block and entity pings', async () => {
    const b = await bot([ping()]);
    const api = b.get<PingApi>('bridge:ping')!;
    const got: Ping[] = [];
    api.on((p) => got.push(p));
    const send = (p: BridgePingMark): void => {
      expect(e.mod.sendExtension(ALICE, 'bridge:ping.mark', encodeExtension(PingMessages['bridge:ping.mark'], p))).toBe(true);
    };
    send({ target: { type: 'block', block: { x: 4, y: 63, z: -2 }, face: 'up', pos: { x: 4.5, y: 64, z: -1.5 } }, world: OVERWORLD, block: 'minecraft:chest' });
    send({ target: { type: 'entity', entity: 42, pos: { x: 10, y: 64, z: 3 } }, entityType: 'minecraft:zombie', entityName: 'Bob' });
    await until(() => got.length === 2);
    expect(got[0]).toMatchObject({ type: 'block', block: { x: 4, y: 63, z: -2 }, face: 'up', blockName: 'minecraft:chest', pos: { x: 4.5, y: 64, z: -1.5 }, playerId: 'default' });
    expect(got[1]).toMatchObject({ type: 'entity', entity: { id: 42, type: 'minecraft:zombie', name: 'Bob' } });
    expect(api.last).toBe(got[1]);
  });
});

describe('bridge:status-tag', () => {
  it('shows lines above the bot and hides them', async () => {
    const b = await bot([statusTag()]);
    const tag = b.get<StatusTagApi>('bridge:status-tag')!;
    const text = (): string | undefined => (e.mod.object(`${ALICE}/bridge:status-tag/status-tag/tag/lines`)?.data as { text?: string } | undefined)?.text;
    tag.set(['Farming wheat', 'HP 18  food 14']);
    await until(() => text() === 'Farming wheat\nHP 18  food 14');
    tag.set('Idle');
    await until(() => text() === 'Idle');
    expect(tag.lines).toEqual(['Idle']);
    expect(() => tag.set(['1', '2', '3', '4', '5'])).toThrow(/at most 4/);
    tag.clear();
    await until(() => e.mod.objects(`${ALICE}/bridge:status-tag/status-tag/`).length === 0);
  });
});
