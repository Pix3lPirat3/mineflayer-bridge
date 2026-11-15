import { describe, expect, it } from 'vitest';
import type { MockInputRequest } from '@mineflayer-bridge/mock-mod';
import { fixture, rejection, SERVER, until, type Env } from './helpers.ts';

let e: Env;
const { attached } = fixture((f) => (e = f.e));

async function nextInput(): Promise<MockInputRequest> {
  const [r] = await e.mod.waitFor('inputRequest');
  return r;
}

describe('prompt surfaces', () => {
  it('sends the surface and its options for text prompts and resolves with the answer', async () => {
    const b = await attached();
    let queued = nextInput();
    const name = b.prompt.text('Name this chest', { surface: 'anvil', item: 'minecraft:chest', maxLength: 32 });
    let r = await queued;
    expect(r).toMatchObject({ type: 'prompt', kind: 'text', surface: 'anvil' });
    expect(r.request).toMatchObject({ surface: 'anvil', item: 'minecraft:chest', maxLength: 32, placement: 'hud' });
    e.mod.resolvePrompt(r.id, 'Ores');
    expect(await name).toBe('Ores');

    queued = nextInput();
    const answer = b.prompt.text('What should I build?', { surface: 'chat' });
    r = await queued;
    expect(r.request).toMatchObject({ surface: 'chat' });
    expect(() => e.mod.resolvePrompt(r.id, 'x'.repeat(10))).not.toThrow();
    expect(await answer).toBe('x'.repeat(10));

    queued = nextInput();
    const capped = b.prompt.text('Short?', { maxLength: 3 });
    r = await queued;
    expect(() => e.mod.resolvePrompt(r.id, 'toolong')).toThrow(/longer than 3/);
    e.mod.resolvePrompt(r.id, 'ok');
    expect(await capped).toBe('ok');
  });

  it('asks for sign lines and book pages, with the mod checking the answer shape', async () => {
    const b = await attached();
    let queued = nextInput();
    const lines = b.prompt.lines('Sign text', { wood: 'spruce', maxLength: 15 });
    let r = await queued;
    expect(r).toMatchObject({ kind: 'lines', surface: 'sign' });
    expect(r.request).toMatchObject({ kind: 'lines', surface: 'sign', wood: 'spruce', maxLength: 15, defaultValue: [] });
    expect(() => e.mod.resolvePrompt(r.id, ['a', 'b', 'c', 'd', 'e'])).toThrow(/1 to 4 strings/);
    expect(() => e.mod.resolvePrompt(r.id, ['this line is far too long'])).toThrow(/longer than 15/);
    expect(() => e.mod.resolvePrompt(r.id, 'not a list')).toThrow(/1 to 4 strings/);
    e.mod.resolvePrompt(r.id, ['Iron', '', 'Ore']);
    expect(await lines).toEqual(['Iron', '', 'Ore']);

    queued = nextInput();
    const pages = b.prompt.pages('Write the log');
    r = await queued;
    expect(r).toMatchObject({ kind: 'pages', surface: 'book' });
    expect(() => e.mod.resolvePrompt(r.id, [])).toThrow(/1 to 100 strings/);
    e.mod.resolvePrompt(r.id, ['day 1', 'day 2']);
    expect(await pages).toEqual(['day 1', 'day 2']);

    expect(await b.prompt.lines('Timed', { timeoutMs: 50, default: ['x'] })).toEqual(['x']);
  });

  it('sends number bounds and lets the mod refuse an answer outside them', async () => {
    const b = await attached();
    const queued = nextInput();
    const n = b.prompt.number('How many?', { min: 1, max: 64, surface: 'dialog' });
    const r = await queued;
    expect(r.request).toMatchObject({ min: 1, max: 64, surface: 'dialog' });
    expect(() => e.mod.resolvePrompt(r.id, 65)).toThrow(/outside 1 to 64/);
    e.mod.resolvePrompt(r.id, 12);
    expect(await n).toBe(12);
  });

  it('rejects surfaces and options a prompt kind does not support before sending', async () => {
    const b = await attached();
    const bad = [
      // Casts stand in for callers without types.
      b.prompt.number('n', { surface: 'anvil' as 'hud' }),
      b.prompt.choice('c', ['a'], { surface: 'chat' as 'hud' }),
      b.prompt.lines('l', { surface: 'book' as 'sign' }),
      b.prompt.text('t', { maxLength: 0 }),
      b.prompt.text('t', { maxLength: 2.5 }),
      b.prompt.number('n', { min: 5, max: 1 }),
      b.prompt.number('n', { min: Number.NaN }),
      b.form({ title: 'f', fields: [], surface: 'anvil' as 'screen' }),
    ];
    for (const p of bad) expect((await rejection(p)).code).toBe('invalid_message');
    expect(e.mod.inputQueue()).toEqual([]);
  });

  it('treats a world surface like a world placement', async () => {
    const b = await attached();
    e.mod.setAttached('alice@play.example.net:25565', true);
    e.mod.setPlayerContext({ server: SERVER, dimension: 'minecraft:the_nether' });
    await until(() => !b.state.worldMatched);
    expect((await rejection(b.prompt.confirm('Here?', { surface: 'world', at: [0, 64, 0] }))).code).toBe('world_mismatch');
  });

  it('falls back to hud when the mod cannot show the surface', async () => {
    await e.restart({ surfaces: ['hud', 'screen'] });
    const b = await attached();
    let queued = nextInput();
    void b.prompt.lines('Sign text').catch(() => undefined);
    const r = await queued;
    expect(r).toMatchObject({ surface: 'hud', request: { surface: 'sign' } });
    e.mod.resolvePrompt(r.id, ['ok']);

    queued = nextInput();
    void b.form({ title: 'Farm', fields: [], surface: 'dialog' }).catch(() => undefined);
    expect(await queued).toMatchObject({ type: 'form', surface: 'screen', request: { surface: 'dialog' } });
  });
});

describe('form surfaces and deadlines', () => {
  it('sends the surface and the deadline with form.open', async () => {
    const b = await attached();
    const queued = nextInput();
    const values = b.form({ title: 'Farm', surface: 'dialog', timeoutMs: 120_000, fields: [{ id: 'radius', type: 'number', default: 8 }] });
    const r = await queued;
    expect(r).toMatchObject({ type: 'form', surface: 'dialog', request: { title: 'Farm', surface: 'dialog', timeoutMs: 120_000 } });
    expect(r.deadlineAt - r.receivedAt).toBe(120_000);
    e.mod.resolveForm(r.id, {});
    expect(await values).toEqual({ radius: 8 });
  });

  it('defaults to the screen surface and the one hour deadline', async () => {
    const b = await attached();
    const queued = nextInput();
    void b.form({ title: 'x', fields: [] }).catch(() => undefined);
    expect((await queued).request).toMatchObject({ surface: 'screen', timeoutMs: 3_600_000 });
  });

  it('rejects with timeout when the mod enforces the form deadline', async () => {
    const b = await attached();
    const started = Date.now();
    expect((await rejection(b.form({ title: 'x', fields: [], timeoutMs: 60 }))).code).toBe('timeout');
    expect(Date.now() - started).toBeLessThan(2000);
  });
});
