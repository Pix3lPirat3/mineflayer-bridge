import { describe, expect, it } from 'vitest';
import { selection, selectionArea, type SelectionArea } from '../src/index.ts';
import { ALICE, fixture, OVERWORLD, thrown, until, type Env } from './helpers.ts';

let e: Env;
const { hub, attached } = fixture((f) => (e = f.e));

const SCOPE = `${ALICE}/bridge:selection`;
const TOOL = `${SCOPE}/~tool/region`;

/** Ids of the shapes drawn on the selection layer of `scope`, sorted. */
const drawn = (scope = SCOPE): string[] =>
  e.mod
    .objects(`${scope}/selection/`)
    .map((o) => o.key.slice(`${scope}/selection/`.length))
    .sort();

const block = (x: number, y: number, z: number) => ({ type: 'block', block: { x, y, z } }) as const;

describe('selectionArea', () => {
  it('spans two corners in any order, and one corner is a single block', () => {
    expect(selectionArea(undefined, undefined)).toBeUndefined();
    expect(selectionArea({ x: 5, y: 64, z: -2 }, { x: 1, y: 70, z: 4 })).toEqual({ min: { x: 1, y: 64, z: -2 }, max: { x: 5, y: 70, z: 4 } });
    expect(selectionArea({ x: 1, y: 2, z: 3 }, undefined)).toEqual({ min: { x: 1, y: 2, z: 3 }, max: { x: 1, y: 2, z: 3 } });
    expect(selectionArea(undefined, { x: 1, y: 2, z: 3 })).toEqual({ min: { x: 1, y: 2, z: 3 }, max: { x: 1, y: 2, z: 3 } });
  });
});

describe('bridge:selection', () => {
  it('sets corners from the tool, draws the box and tells the player', async () => {
    const b = await attached();
    await b.use(selection());
    const api = b.get(selection)!;
    await until(() => e.mod.tools().length === 1);
    expect(e.mod.tools()[0]).toMatchObject({ key: TOOL, data: { label: 'Region select', icon: 'minecraft:golden_hoe', targets: ['block'], inputs: ['primary', 'secondary'] } });
    expect(api.describe()).toBe('nothing selected');
    expect(api.area()).toBeUndefined();

    const changes: (SelectionArea | undefined)[] = [];
    api.on('change', (a) => changes.push(a));
    e.mod.setActiveTool(TOOL);
    await until(() => e.mod.notifies.length === 1);
    expect(e.mod.notifies[0]!.text).toBe('Region select: left-click a block for corner 1, right-click for corner 2');

    e.mod.toolEvent(TOOL, 'primary', { target: block(5, 64, -2) });
    await until(() => api.corner1 !== undefined);
    expect(api.corner1).toEqual({ x: 5, y: 64, z: -2 });
    expect(api.corner2).toBeUndefined();
    expect(api.describe()).toBe('1 x 1 x 1 (1 blocks) from 5 64 -2 to 5 64 -2; corner 2 not set yet');
    await until(() => drawn().length === 4);
    expect(drawn()).toEqual(['box', 'corner1', 'label1', 'title']);

    e.mod.toolEvent(TOOL, 'secondary', { target: block(1, 64, 4) });
    await until(() => api.corner2 !== undefined);
    expect(api.area()).toEqual({ min: { x: 1, y: 64, z: -2 }, max: { x: 5, y: 64, z: 4 } });
    expect(api.describe()).toBe('5 x 1 x 7 (35 blocks) from 1 64 -2 to 5 64 4');
    await until(() => drawn().length === 6);
    expect(drawn()).toEqual(['box', 'corner1', 'corner2', 'label1', 'label2', 'title']);
    const box = e.mod.object(`${SCOPE}/selection/box`)!;
    expect(box).toMatchObject({ kind: 'core:box', scope: 'world', data: { min: { x: 1, y: 64, z: -2 }, size: { x: 5, y: 1, z: 7 } } });
    expect(e.mod.object(`${SCOPE}/selection/label2`)).toMatchObject({ data: { pos: { x: 1.5, y: 65.5, z: 4.5 }, text: '2' } });

    await until(() => e.mod.notifies.length === 3);
    expect(e.mod.notifies.slice(1).map((n) => n.text)).toEqual([
      'Corner 1: 1 x 1 x 1 (1 blocks) from 5 64 -2 to 5 64 -2; corner 2 not set yet',
      'Corner 2: 5 x 1 x 7 (35 blocks) from 1 64 -2 to 5 64 4',
    ]);
    expect(changes).toEqual([
      { min: { x: 5, y: 64, z: -2 }, max: { x: 5, y: 64, z: -2 } },
      { min: { x: 1, y: 64, z: -2 }, max: { x: 5, y: 64, z: 4 } },
    ]);

    // A click on nothing changes nothing and says why.
    e.mod.toolEvent(TOOL, 'primary', { target: { type: 'air' } });
    await until(() => e.mod.notifies.length === 4);
    expect(e.mod.notifies[3]).toMatchObject({ text: 'Region select: look at a block first', level: 'warn' });
    expect(changes).toHaveLength(2);
  });

  it('sets and clears through the API, flooring positions and skipping changes that change nothing', async () => {
    const b = await attached();
    await b.use(selection({ tool: false, color: '#00ffff' }));
    const api = b.get(selection)!;
    expect(e.mod.tools()).toEqual([]);
    const changes: (SelectionArea | undefined)[] = [];
    const off = api.on('change', (a) => changes.push(a));

    api.set(2, [3.7, 64.2, -1.5]);
    expect(api.corner2).toEqual({ x: 3, y: 64, z: -2 });
    expect(api.describe()).toBe('1 x 1 x 1 (1 blocks) from 3 64 -2 to 3 64 -2; corner 1 not set yet');
    api.set(2, { x: 3, y: 64, z: -2 });
    api.set(1, { x: 0, y: 60, z: 0 });
    expect(changes).toHaveLength(2);
    expect(api.area()).toEqual({ min: { x: 0, y: 60, z: -2 }, max: { x: 3, y: 64, z: 0 } });
    await until(() => drawn().length === 6);
    // The corners handed out are copies.
    (api.corner1 as { x: number }).x = 99;
    expect(api.corner1).toEqual({ x: 0, y: 60, z: 0 });

    expect(thrown(() => api.set(3 as never, [0, 0, 0])).code).toBe('invalid_message');
    expect(thrown(() => api.set(1, [0, Number.NaN, 0])).code).toBe('invalid_message');

    api.clear();
    api.clear();
    expect(changes).toEqual([expect.anything(), expect.anything(), undefined]);
    expect(api.corner1).toBeUndefined();
    expect(api.describe()).toBe('nothing selected');
    await until(() => drawn().length === 0);

    off();
    api.set(1, [1, 1, 1]);
    expect(changes).toHaveLength(3);
    expect(e.mod.notifies).toEqual([]);
  });

  it('works for a controller, drawn in whatever world the player is in', async () => {
    const h = hub();
    await attached('Alice', h);
    const ctl = h.controller({ name: 'Boss', extensions: [selection({ label: 'Area', icon: 'minecraft:stick', notify: false })] });
    await until(() => ctl.state.connected && ctl.has('bridge:selection'));
    const scope = `${ctl.session.owner}/bridge:selection`;
    const tool = `${scope}/~tool/region`;
    await until(() => e.mod.tools().length === 1);
    expect(e.mod.tools()[0]).toMatchObject({ key: tool, data: { label: 'Area', icon: 'minecraft:stick' } });
    e.mod.setPlayerContext(OVERWORLD);
    e.mod.setActiveTool(tool);
    e.mod.toolEvent(tool, 'primary', { target: block(0, 64, 0) });
    e.mod.toolEvent(tool, 'secondary', { target: block(2, 66, 2) });
    const api = ctl.get(selection)!;
    await until(() => api.corner2 !== undefined);
    expect(api.describe()).toBe('3 x 3 x 3 (27 blocks) from 0 64 0 to 2 66 2');
    await until(() => drawn(scope).length === 6);
    expect(e.mod.object(`${scope}/selection/box`)).toMatchObject({ scope: 'global' });
    expect(e.mod.notifies).toEqual([]);
  });

  it('labels the box with what it is, its size and how to use it; the bot changes the words', async () => {
    const b = await attached();
    await b.use(selection({ tool: false, hint: 'sel' }));
    const api = b.get(selection)!;
    const title = (): unknown => (e.mod.object(`${SCOPE}/selection/title`)?.data as { text?: string } | undefined)?.text;
    expect(api.label).toBeUndefined();
    api.set(1, [1, 64, -2]);
    api.set(2, [5, 64, 4]);
    expect(api.label).toBe('Selection 5×1×7 · sel');
    await until(() => title() === 'Selection 5×1×7 · sel');
    expect(e.mod.object(`${SCOPE}/selection/title`)).toMatchObject({ data: { pos: { x: 3.5, y: 66.1, z: 1.5 } } });
    // The corners keep their numbers.
    expect(e.mod.object(`${SCOPE}/selection/label1`)).toMatchObject({ data: { text: '1' } });

    api.setLabel({ title: 'Editing {farm}', hint: '@eco zone save' });
    expect(api.label).toBe('Editing {farm} 5×1×7 · @eco zone save');
    await until(() => title() === 'Editing (farm) 5×1×7 · @eco zone save');
    api.setLabel({ hint: '' });
    await until(() => title() === 'Selection 5×1×7');
    expect(thrown(() => api.setLabel({ title: 3 as never })).code).toBe('invalid_message');

    api.setLabel({ title: 'farm' });
    api.clear();
    api.set(1, [0, 60, 0]);
    expect(api.label).toBe('Selection 1×1×1 · sel');
  });

  it('removes the tool and the drawing on unload', async () => {
    const b = await attached();
    await b.use(selection());
    const api = b.get(selection)!;
    api.set(1, [0, 64, 0]);
    await until(() => drawn().length === 4 && e.mod.tools().length === 1);
    let calls = 0;
    api.on('change', () => calls++);
    await b.unload('bridge:selection');
    await until(() => e.mod.objects(`${SCOPE}/`).length === 0);
    expect(e.mod.tools()).toEqual([]);
    expect(calls).toBe(0);
  });
});
