import { describe, expect, it } from 'vitest';
import type { MockObject } from '@mineflayer-bridge/mock-mod';
import { defineExtension, type ElementEvent, type ExtensionContext } from '../src/index.ts';
import { ALICE, ALICE_PREFIX, fixture, sleep, thrown, until, type Env } from './helpers.ts';

let e: Env;
const { attached } = fixture((f) => (e = f.e));

const object = (key: string): Promise<MockObject> => until(() => e.mod.object(key), 5000, key);

describe('object traits', () => {
  it('sends traits for every kind, with action ids as full keys and editable entries normalised', async () => {
    const b = await attached();
    const layer = b.layer('plan');
    const hits: ElementEvent[] = [];
    layer.box('site', [0, 64, 0], 4, {
      color: '#ff0000',
      interactive: { tooltip: 'Site', on: { primary: (ev) => void hits.push(ev) } },
      editable: ['min', { field: 'size', editor: 'gizmo', label: 'Size' }],
      actions: ['inspect', 'inspect', 'clear'],
      visibleWhen: { maxDistance: 48, focusedOnly: true },
    });
    layer.blocks('ore', [[1, 2, 3]], { hover: { glow: '#00ff00' } });
    layer.zone('farm', { name: 'Farm', boxes: [[[0, 0, 0], [3, 3, 3]]] }, { editable: ['name'] });
    layer.group('g', { pos: [0, 70, 0], actions: ['inspect'] });

    const box = await object(`${ALICE_PREFIX}/plan/site`);
    expect(box.traits).toMatchObject({
      interactive: { inputs: ['primary'], tooltip: 'Site' },
      editable: [{ field: 'min' }, { field: 'size', editor: 'gizmo', label: 'Size' }],
      actions: [`${ALICE_PREFIX}/~action/inspect`, `${ALICE_PREFIX}/~action/clear`],
      visibleWhen: { maxDistance: 48, focusedOnly: true, lookedAt: false },
    });
    // `color` alone is an inline style.
    expect((await object(`${ALICE_PREFIX}/~style/~inline/plan/site`)).data).toMatchObject({ color: 0xffff0000 });
    expect((await object(`${ALICE_PREFIX}/plan/ore`)).traits).toMatchObject({ hover: { glow: 0xff00ff00 } });
    expect((await object(`${ALICE_PREFIX}/plan/farm`)).traits).toMatchObject({ editable: [{ field: 'name' }] });
    expect((await object(`${ALICE_PREFIX}/plan/g`)).traits).toMatchObject({ actions: [`${ALICE_PREFIX}/~action/inspect`] });

    e.mod.elementEvent(`${ALICE_PREFIX}/plan/site`, 'primary', { modifiers: { shift: true } });
    await until(() => hits.length === 1);
    expect(hits[0]).toMatchObject({ id: 'site', layer: 'plan', input: 'primary', modifiers: { shift: true, ctrl: false, alt: false } });
  });

  it('replaces traits with a patch, clears them with a full write, and validates them', async () => {
    const b = await attached();
    const layer = b.layer('ui');
    const key = `${ALICE_PREFIX}/ui/note`;
    const l = layer.label('note', [0, 65, 0], 'Hi', { editable: ['text'] });
    await object(key);
    const writes: string[] = [];
    e.mod.on('objectChanged', (c) => {
      if (c.key === key) writes.push(c.write);
    });
    l.update({ editable: ['text', 'pos'] });
    await until(() => e.mod.object(key)?.traits?.editable?.length === 2);
    l.update({ editable: null });
    await until(() => e.mod.object(key)?.traits === undefined);
    expect(writes).toEqual(['patch', 'full']);

    expect(thrown(() => layer.label('x', [0, 0, 0], 'x', { editable: ['colour'] })).message).toMatch(/core:label has no field colour/);
    expect(thrown(() => layer.label('x', [0, 0, 0], 'x', { editable: [{ field: 'text', editor: 'slider' as never }] })).message).toMatch(/unknown editor slider/);
    expect(thrown(() => layer.label('x', [0, 0, 0], 'x', { editable: ['text', 'text'] })).message).toMatch(/twice/);
    expect(thrown(() => layer.label('x', [0, 0, 0], 'x', { actions: ['a/b'] })).code).toBe('invalid_message');
    expect(thrown(() => layer.label('x', [0, 0, 0], 'x', { visibleWhen: { minDistance: 10, maxDistance: 5 } })).message).toMatch(/above maxDistance/);
    expect(thrown(() => layer.box('x', [0, 0, 0], 1, { style: 'core:default', color: '#fff000' })).message).toMatch(/cannot be combined/);
    expect(layer.has('x')).toBe(false);
  });

  it('gives every shape a handle with update, remove, alive and removed events', async () => {
    const b = await attached();
    const layer = b.layer('shapes');
    const key = `${ALICE_PREFIX}/shapes/wp`;
    const wp = layer.waypoint('wp', [1, 64, 1], 'Home', { beam: true, style: { lineWidth: 3 }, color: '#00ff00' });
    expect(layer.waypoint('wp', [1, 64, 1], 'Home', { beam: true, style: { lineWidth: 3 }, color: '#00ff00' })).toBe(wp);
    expect(wp).toMatchObject({ id: 'wp', layer: 'shapes', key: 'user:default/shapes/wp', alive: true });
    await object(key);
    expect((await object(`${ALICE_PREFIX}/~style/~inline/shapes/wp`)).data).toMatchObject({ lineWidth: 3, color: 0xff00ff00 });
    wp.update({ name: 'Base', pos: [2, 64, 2], beam: null });
    await until(() => (e.mod.object(key)?.data as { name: string }).name === 'Base');
    expect(e.mod.object(key)!.data).toMatchObject({ pos: { x: 2, y: 64, z: 2 }, beam: false });
    expect(thrown(() => wp.update({ pos: [Number.NaN, 0, 0] })).code).toBe('invalid_message');

    const removed: string[] = [];
    wp.on('removed', () => removed.push('wp'));
    const line = layer.line('l', [0, 0, 0], [1, 1, 1]);
    line.on('removed', () => removed.push('line'));
    layer.box('l', [0, 0, 0], 1);
    expect(line.alive).toBe(false);
    wp.remove();
    expect(wp.alive).toBe(false);
    expect(removed).toEqual(['line', 'wp']);
    wp.update({ name: 'ignored' });
    await until(() => e.mod.object(key) === undefined && e.mod.object(`${ALICE_PREFIX}/~style/~inline/shapes/wp`) === undefined);
  });

  it('accepts interpolateMs as a spelling of interpolate', async () => {
    const b = await attached();
    const t = b.layer('holo').text('t', [0, 65, 0], 'x', { interpolateMs: 150 });
    const key = `${ALICE_PREFIX}/holo/t`;
    await until(() => (e.mod.object(key)?.data as { interpolateMs?: number } | undefined)?.interpolateMs === 150);
    t.move([1, 65, 0], { interpolateMs: 300 });
    await until(() => (e.mod.object(key)?.data as { interpolateMs: number }).interpolateMs === 300);
    expect(thrown(() => t.update({ interpolate: 10, interpolateMs: 20 })).message).toMatch(/same option/);
  });
});

describe('player edits of any kind', () => {
  it('accepts listed fields by default, applies them to the handle state and emits edited', async () => {
    const b = await attached();
    const key = `${ALICE_PREFIX}/ui/sign`;
    const t = b.layer('ui').text('sign', [0, 65, 0], 'Hello', { editable: ['text', { field: 'pos', editor: 'gizmo' }], background: '#000000' });
    await object(key);
    const edited: [string, string, readonly string[]][] = [];
    t.on('edited', (data, previous, info) => edited.push([previous.text, data.text, info.fields]));
    expect(await e.mod.proposeEdit<'core:display.text'>(key, { text: 'Edited', pos: { x: 1, y: 65, z: 0 } })).toEqual({ accepted: true, reason: undefined });
    expect(e.mod.object(key)!.data).toMatchObject({ text: 'Edited', pos: { x: 1, y: 65, z: 0 } });
    expect(edited).toEqual([['Hello', 'Edited', ['pos', 'text']]]);
    // The edit is part of the handle's state, so a later update keeps it.
    t.update({ scale: 2 });
    await until(() => (e.mod.object(key)?.data as { scale?: number }).scale === 2);
    expect(e.mod.object(key)!.data).toMatchObject({ text: 'Edited', pos: { x: 1, y: 65, z: 0 } });

    expect(await e.mod.proposeEdit<'core:display.text'>(key, { background: 0xff112233 }, { unchecked: true })).toEqual({ accepted: false, reason: 'not_editable' });
    expect(await e.mod.proposeEdit<'core:display.text'>(key, { billboard: 'sideways' }, { unchecked: true })).toEqual({ accepted: false, reason: 'invalid_message' });
  });

  it('asks onEdit with the changed fields, and takes true, false, a reason or a promise', async () => {
    const b = await attached();
    const errors: Error[] = [];
    b.on('error', (err) => errors.push(err));
    const key = `${ALICE_PREFIX}/plan/site`;
    const box = b.layer('plan').box('site', [0, 64, 0], 2, { editable: ['min', 'size'] });
    await object(key);
    const seen: (readonly string[])[] = [];
    box.onEdit((proposed, current, { fields }) => {
      seen.push(fields);
      if (proposed.size.x > 8) return `too big: ${proposed.size.x}`;
      if (proposed.min.y < current.min.y) return false;
      return new Promise<boolean>((resolve) => setTimeout(() => resolve(true), 20));
    });
    expect(await e.mod.proposeEdit<'core:box'>(key, { size: { x: 9, y: 2, z: 2 } })).toEqual({ accepted: false, reason: 'too big: 9' });
    expect(await e.mod.proposeEdit<'core:box'>(key, { min: { x: 0, y: 60, z: 0 } })).toEqual({ accepted: false, reason: 'rejected' });
    expect(await e.mod.proposeEdit<'core:box'>(key, { min: { x: 3, y: 64, z: 3 }, size: { x: 4, y: 4, z: 4 } })).toEqual({ accepted: true, reason: undefined });
    expect(seen).toEqual([['size'], ['min'], ['min', 'size']]);
    expect(e.mod.object(key)!.data).toMatchObject({ min: { x: 3, y: 64, z: 3 }, size: { x: 4, y: 4, z: 4 } });

    const clear = box.onEdit(() => {
      throw new Error('nope');
    });
    expect(await e.mod.proposeEdit<'core:box'>(key, { size: { x: 1, y: 1, z: 1 } })).toEqual({ accepted: false, reason: 'nope' });
    expect(errors.map((x) => x.message)).toEqual(['nope']);
    clear();
    expect(await e.mod.proposeEdit<'core:box'>(key, { size: { x: 1, y: 1, z: 1 } })).toEqual({ accepted: true, reason: undefined });
  });

  it('answers unsupported for retained shapes and for derived display fields', async () => {
    const b = await attached();
    b.layer('plan').blocks('ore', [[0, 0, 0]], { editable: ['mode'] });
    const g = b.layer('plan').group('g', { pos: [0, 64, 0] });
    g.text('child', [0, 1, 0], 'x', { editable: ['text', 'group'] });
    await object(`${ALICE_PREFIX}/plan/ore`);
    await object(`${ALICE_PREFIX}/plan/g/child`);
    expect(await e.mod.proposeEdit<'core:block-highlight'>(`${ALICE_PREFIX}/plan/ore`, { mode: 'faces' })).toEqual({ accepted: false, reason: 'unsupported' });
    expect(await e.mod.proposeEdit<'core:display.text'>(`${ALICE_PREFIX}/plan/g/child`, { group: 'other' })).toEqual({ accepted: false, reason: 'unsupported' });
    expect(await e.mod.proposeEdit<'core:display.text'>(`${ALICE_PREFIX}/plan/g/child`, { text: 'y' })).toEqual({ accepted: true, reason: undefined });
  });

  it('sends the edit answer after the accepted data, also inside a transaction', async () => {
    const b = await attached();
    const key = `${ALICE_PREFIX}/ui/note`;
    const l = b.layer('ui').label('note', [0, 65, 0], 'Hi', { editable: ['text'] });
    await object(key);
    let release!: () => void;
    const held = b.transaction(() => new Promise<void>((resolve) => (release = resolve)));
    const answer = e.mod.proposeEdit<'core:label'>(key, { text: 'Edited' });
    await until(() => e.mod.openTransaction(ALICE) === undefined && e.mod.pendingEdits().length === 1);
    await sleep(30);
    expect(e.mod.pendingEdits()).toHaveLength(1);
    release();
    await held;
    expect(await answer).toEqual({ accepted: true, reason: undefined });
    expect((e.mod.object(key)!.data as { text: string }).text).toBe('Edited');
    expect(l.alive).toBe(true);
  });

  it('routes element events and edits of extension objects and drops them on unload', async () => {
    const b = await attached();
    let ctx!: ExtensionContext;
    const hits: string[] = [];
    await b.use(
      defineExtension({
        id: 'acme:board',
        version: '1.0.0',
        setup(c) {
          ctx = c;
          c.layer('board').path('route', [[0, 64, 0], [5, 64, 0]], { interactive: { on: { secondary: () => void hits.push('route') } }, editable: ['points'] });
        },
      })(),
    );
    const key = `${ALICE}/acme:board/board/route`;
    await object(key);
    e.mod.elementEvent(key, 'secondary');
    await until(() => hits.length === 1);
    expect(await e.mod.proposeEdit<'core:path'>(key, { points: Float64Array.of(0, 64, 0, 9, 64, 0) })).toEqual({ accepted: true, reason: undefined });
    await b.unload('acme:board');
    expect(ctx.signal.aborted).toBe(true);
    await until(() => e.mod.object(key) === undefined);
  });
});
