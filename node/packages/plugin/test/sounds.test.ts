import { describe, expect, it } from 'vitest';
import { ALICE_PREFIX, fixture, thrown, until, type Env } from './helpers.ts';

/** The smallest input that passes the Ogg check; the plugin does not decode audio. */
const OGG = new Uint8Array([0x4f, 0x67, 0x67, 0x53, 0, 2, 0, 0, 1, 2, 3, 4]);

let e: Env;
const { attached } = fixture((f) => (e = f.e));

function data(key: string): Record<string, unknown> | undefined {
  return e.mod.object(key)?.data as Record<string, unknown> | undefined;
}

describe('sound emitters', () => {
  it('declares a looping sound, patches it, replays it and stops on remove', async () => {
    const b = await attached();
    const key = `${ALICE_PREFIX}/fx/alarm`;
    const s = b.layer('fx').sound('alarm', [1, 64, 2], 'block.bell.use', { volume: 0.5, interval: 2000, category: 'ambient' });
    await until(() => data(key));
    expect(e.mod.object(key)?.kind).toBe('core:sound-emitter');
    expect(data(key)).toMatchObject({ pos: { x: 1, y: 64, z: 2 }, sound: 'minecraft:block.bell.use', loop: true, intervalMs: 2000, category: 'ambient', plays: 0 });
    s.update({ sound: 'minecraft:block.note_block.pling', loop: false, interval: null });
    await until(() => data(key)?.sound === 'minecraft:block.note_block.pling');
    expect(data(key)).toMatchObject({ loop: false, intervalMs: 0 });
    s.play();
    s.play();
    await until(() => data(key)?.plays === 2, 5000, 'two replays');
    s.move([3, 64, 3], { interpolate: 500 });
    await until(() => (data(key)?.pos as { x: number }).x === 3);
    s.remove();
    await until(() => e.mod.object(key) === undefined);
  });

  it('sends custom Ogg audio as a blob and follows a group', async () => {
    const b = await attached();
    const g = b.layer('fx').group('bot', { follow: 'self', offset: [0, 2, 0] });
    g.sound('voice', [0, 0, 0], OGG, { positional: false });
    const key = `${ALICE_PREFIX}/fx/bot/voice`;
    await until(() => data(key));
    const d = data(key)!;
    expect(d).toMatchObject({ group: 'bot', positional: false, size: OGG.length });
    expect(d.sound).toBeUndefined();
    const hash = Buffer.from(d.blob as Uint8Array).toString('hex');
    await until(() => e.mod.blob(hash), 5000, 'audio blob stored');
    expect([...e.mod.blob(hash)!]).toEqual([...OGG]);
    g.emitter('sparks', [0, 1, 0], 'flame');
    await until(() => data(`${ALICE_PREFIX}/fx/bot/sparks`)?.group === 'bot', 5000, 'emitter in the group');
  });

  it('rejects bad sounds and enforces the quota before sending', async () => {
    const b = await attached();
    const layer = b.layer('fx');
    expect(thrown(() => layer.sound('x', [0, 64, 0], new Uint8Array([1, 2, 3, 4]))).message).toMatch(/Ogg Vorbis/);
    expect(thrown(() => layer.sound('x', [0, 64, 0], 'a b')).code).toBe('invalid_message');
    expect(thrown(() => layer.sound('x', [0, 64, 0], 'pling', { category: 'loud' as never })).message).toMatch(/sound category/);
    expect(thrown(() => b.effects.sound('pling', { category: 'loud' as never })).message).toMatch(/sound category/);
    for (let i = 0; i < 16; i++) layer.sound(`s${i}`, [i, 64, 0], 'pling');
    expect(thrown(() => layer.sound('s-more', [0, 64, 0], 'pling')).code).toBe('quota_exceeded');
    layer.sound('s0', [0, 65, 0], 'pling');
  });

  it('carries the category of one-shot sounds and the sound of screen controls', async () => {
    const b = await attached();
    b.effects.sound('ui.button.click', { category: 'ui' });
    await until(() => e.mod.effects().length === 1);
    expect(e.mod.effects()[0]!.effect).toMatchObject({ type: 'sound', value: { id: 'minecraft:ui.button.click', category: 'ui' } });
    b.screen('main', { title: 'A', rows: [{ button: 'Ping', id: 'ping', sound: 'block.note_block.pling' }] });
    await until(() => e.mod.screens()[0]);
    expect(e.mod.screens()[0]!.data.tabs[0]!.rows[0]!.items[0]).toMatchObject({ sound: 'minecraft:block.note_block.pling' });
  });
});
