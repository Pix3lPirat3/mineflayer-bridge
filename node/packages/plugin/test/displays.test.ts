import { describe, expect, it } from 'vitest';
import type { MockDisplay, MockInputRequest } from '@mineflayer-bridge/mock-mod';
import { toQuaternion } from '../src/displays.ts';
import { defineExtension, type ElementEvent, type ExtensionContext } from '../src/index.ts';
import { ALICE, ALICE_PREFIX, FakeBot, fixture, load, OVERWORLD, quietLogger, rejection, SERVER, sleep, thrown, until, type Env } from './helpers.ts';

let e: Env;
const { hub, attached } = fixture((f) => ({ e, logger } = f));
let logger: ReturnType<typeof quietLogger>;

function display(key: string): MockDisplay | undefined {
  return e.mod.displays().find((d) => d.key === key);
}

async function shown(key: string): Promise<MockDisplay> {
  return until(() => display(key), 5000, `display ${key}`);
}

async function nextInput(): Promise<MockInputRequest> {
  const [r] = await e.mod.waitFor('inputRequest');
  return r;
}

/** Rotates `v` by the quaternion `q` (`q v q*`). */
function rotate(q: Float32Array, v: [number, number, number]): [number, number, number] {
  const [x, y, z, w] = q as unknown as [number, number, number, number];
  const [vx, vy, vz] = v;
  const tx = 2 * (y * vz - z * vy);
  const ty = 2 * (z * vx - x * vz);
  const tz = 2 * (x * vy - y * vx);
  return [vx + w * tx + (y * tz - z * ty), vy + w * ty + (z * tx - x * tz), vz + w * tz + (x * ty - y * tx)];
}

function close(a: readonly number[], b: readonly number[]): void {
  expect(a.length).toBe(b.length);
  a.forEach((v, i) => expect(v).toBeCloseTo(b[i]!, 5));
}

describe('quaternions', () => {
  it('passes quaternions through and converts Minecraft angles', () => {
    close([...toQuaternion([0, 0, 0, 1])], [0, 0, 0, 1]);
    close([...toQuaternion({})], [0, 0, 0, 1]);
    // Yaw 90 faces west, so the front (+Z) turns to -X.
    close(rotate(toQuaternion({ yaw: 90 }), [0, 0, 1]), [-1, 0, 0]);
    close(rotate(toQuaternion({ yaw: -90 }), [0, 0, 1]), [1, 0, 0]);
    // Positive pitch tilts the front down.
    close(rotate(toQuaternion({ pitch: 90 }), [0, 0, 1]), [0, -1, 0]);
    // Roll turns the top towards west at yaw 0.
    close(rotate(toQuaternion({ roll: 90 }), [0, 1, 0]), [-1, 0, 0]);
    // Roll, then pitch, then yaw: the top rolled west, then yawed 90 faces north (-Z).
    close(rotate(toQuaternion({ yaw: 90, roll: 90 }), [0, 1, 0]), [0, 0, -1]);
    const q = toQuaternion({ yaw: 30, pitch: 20, roll: 10 });
    expect(Math.hypot(...q)).toBeCloseTo(1, 6);
    expect(thrown(() => toQuaternion([0, 0, 1] as unknown as [number, number, number, number])).code).toBe('invalid_message');
    expect(thrown(() => toQuaternion({ yaw: Number.NaN })).code).toBe('invalid_message');
  });
});

describe('displays', () => {
  it('declares text, item, block and entity displays with the common fields', async () => {
    const b = await attached();
    const layer = b.layer('holo');
    layer.text('title', [1, 65, 1], 'Smelting', {
      lineWidth: 120,
      background: '#00000080',
      shadow: true,
      seeThrough: true,
      align: 'left',
      billboard: 'center',
      scale: 2,
      transform: { offset: [0, 0.5, 0], rotation: { yaw: 90 }, scale: 0.5 },
      interpolate: 200,
      animate: 'bob',
      brightness: 15,
      glow: '#ff0000',
      hover: { scale: 1.2, glow: 0xffffff, text: 'Hovered' },
      maxDistance: 32,
    });
    layer.item('ore', [2, 65, 2], { name: 'iron_ore', count: 3, customName: 'Ore' }, { mode: 'gui', animate: { type: 'spin', amplitude: 0.5, periodMs: 1000 } });
    layer.block('chest', [3, 65, 3], 'chest[facing=north]', { transform: { rotation: [0, 0, 0, 1], scale: [1, 2, 1] } });
    layer.entity('stand-in', [4, 65, 4], 'player', {
      yaw: 45,
      pitch: 10,
      headYaw: 90,
      pose: 'crouching',
      name: 'Alice',
      nameVisible: true,
      baby: false,
      skin: 'Alice',
      equipment: { head: 'diamond_helmet', mainhand: { id: 'minecraft:stick', glint: true } },
    });

    const text = await shown(`${ALICE_PREFIX}/holo/title`);
    expect(text).toMatchObject({ owner: ALICE, extension: 'user:default', layer: 'holo', id: 'title', kind: 'core:display.text', scope: 'world', world: OVERWORLD, group: undefined });
    expect(text.data).toMatchObject({
      pos: { x: 1, y: 65, z: 1 },
      text: 'Smelting',
      lineWidth: 120,
      background: 0x80000000,
      shadow: true,
      seeThrough: true,
      align: 'left',
      billboard: 'center',
      scale: 2,
      interpolateMs: 200,
      animate: 'bob',
      brightness: 15,
      glow: 0xffff0000,
      maxDistance: 32,
    });
    // Hover and interactive travel as traits, not as display data.
    expect(text.traits).toMatchObject({ hover: { glow: 0xffffffff, text: 'Hovered' } });
    expect((text.data as { hover?: unknown }).hover).toBeUndefined();
    const t = (text.data as { transform: { offset: unknown; scale: unknown; rotation: Float32Array } }).transform;
    expect(t.offset).toEqual({ x: 0, y: 0.5, z: 0 });
    expect(t.scale).toEqual({ x: 0.5, y: 0.5, z: 0.5 });
    close([...t.rotation], [...toQuaternion({ yaw: 90 })]);

    expect((await shown(`${ALICE_PREFIX}/holo/ore`)).data).toMatchObject({ item: { id: 'minecraft:iron_ore', count: 3, name: 'Ore' }, mode: 'gui', animate: 'spin', animateAmplitude: 0.5, animatePeriodMs: 1000 });
    expect((await shown(`${ALICE_PREFIX}/holo/chest`)).data).toMatchObject({ state: 'minecraft:chest[facing=north]', transform: { scale: { x: 1, y: 2, z: 1 } } });
    expect((await shown(`${ALICE_PREFIX}/holo/stand-in`)).data).toMatchObject({
      type: 'minecraft:player',
      yaw: 45,
      pitch: 10,
      headYaw: 90,
      pose: 'crouching',
      name: 'Alice',
      nameVisible: true,
      skin: 'Alice',
      equipment: { head: { id: 'minecraft:diamond_helmet' }, mainhand: { id: 'minecraft:stick', glint: true } },
    });
  });

  it('lists displays of controllers, whose owner contains a slash', async () => {
    const ctl = hub().controller({ name: 'ops' });
    await until(() => ctl.state.connected);
    ctl.layer('user:default').text('note', [0, 64, 0], 'hi', { world: 'global' });
    const d = await until(() => e.mod.displays()[0]);
    expect(d).toMatchObject({ extension: 'user:default', layer: 'user:default', id: 'note', scope: 'global' });
    expect(d.owner).toMatch(/^ctl:.+\/ops$/);
  });

  it('rejects options that do not fit before sending anything', async () => {
    const b = await attached();
    const layer = b.layer('holo');
    const bad: (() => unknown)[] = [
      () => layer.text('a/b', [0, 0, 0], 'x'),
      () => layer.text('', [0, 0, 0], 'x'),
      () => layer.text('t', [0, Number.NaN, 0], 'x'),
      () => layer.text('t', [0, 0, 0], 'x', { billboard: 'sideways' as 'fixed' }),
      () => layer.text('t', [0, 0, 0], 'x', { brightness: 16 }),
      () => layer.text('t', [0, 0, 0], 'x', { animate: 'wobble' as 'bob' }),
      () => layer.text('t', [0, 0, 0], 'x', { align: 'justify' as 'left' }),
      () => layer.item('i', [0, 0, 0], { id: 'minecraft:stick' }, { mode: 'hand' as 'gui' }),
      () => layer.entity('e', [0, 0, 0], 'zombie', { equipment: { tail: 'stick' } as never }),
      () => layer.text('t', [0, 0, 0], 'x', { interactive: { on: { key: () => undefined } } }),
      () => layer.text('t', [0, 0, 0], 'x', { interactive: { on: { primary: 'go' as never } } }),
      () => layer.text('t', [0, 0, 0], 'x', { interactive: { hitbox: 0 } }),
    ];
    for (const fn of bad) expect(thrown(fn).code).toBe('invalid_message');
    await sleep(30);
    expect(e.mod.displays()).toEqual([]);
  });

  it('sends updates as field patches, clears fields with null and moves with interpolation', async () => {
    const b = await attached();
    const t = b.layer('holo').text('title', [0, 64, 0], 'one', { glow: '#00ff00', shadow: true });
    await shown(`${ALICE_PREFIX}/holo/title`);
    const writes: string[] = [];
    e.mod.on('objectChanged', (c) => writes.push(c.write));

    t.update({ text: 'two', glow: null, scale: 3 });
    await until(() => (display(`${ALICE_PREFIX}/holo/title`)?.data as { text: string }).text === 'two');
    expect(display(`${ALICE_PREFIX}/holo/title`)!.data).toMatchObject({ text: 'two', shadow: true, scale: 3, glow: undefined });

    t.move([5, 70, 5], { interpolate: 500 });
    await until(() => (display(`${ALICE_PREFIX}/holo/title`)?.data as { pos: { x: number } }).pos.x === 5);
    expect(display(`${ALICE_PREFIX}/holo/title`)!.data).toMatchObject({ pos: { x: 5, y: 70, z: 5 }, interpolateMs: 500, text: 'two' });
    expect(writes).toEqual(['patch', 'patch']);

    expect(thrown(() => t.update({ brightness: 99 })).code).toBe('invalid_message');
    expect(t.alive).toBe(true);
    t.update({ shadow: false });
    await until(() => (display(`${ALICE_PREFIX}/holo/title`)?.data as { shadow: boolean }).shadow === false);
    expect(display(`${ALICE_PREFIX}/holo/title`)!.data).toMatchObject({ brightness: undefined, text: 'two' });
  });

  it('returns the same handle for the same id and kind, and retires it when another kind takes over', async () => {
    const b = await attached();
    const layer = b.layer('holo');
    const a = layer.text('x', [0, 64, 0], 'first', { shadow: true });
    const again = layer.text('x', [1, 64, 0], 'second');
    expect(again).toBe(a);
    await until(() => (display(`${ALICE_PREFIX}/holo/x`)?.data as { text: string } | undefined)?.text === 'second');
    expect(display(`${ALICE_PREFIX}/holo/x`)!.data).toMatchObject({ shadow: false });

    const block = layer.block('x', [0, 64, 0], 'stone');
    expect(a.alive).toBe(false);
    a.update({ text: 'ghost' });
    await until(() => display(`${ALICE_PREFIX}/holo/x`)?.kind === 'core:display.block');
    layer.box('x', [0, 64, 0], 1);
    expect(block.alive).toBe(false);
    await until(() => display(`${ALICE_PREFIX}/holo/x`) === undefined);
    expect(e.mod.object(`${ALICE_PREFIX}/holo/x`)?.kind).toBe('core:box');
  });

  it('removes displays through the handle, the layer and clear, leaving the handles inert', async () => {
    const b = await attached();
    const layer = b.layer('holo');
    const one = layer.text('one', [0, 64, 0], '1');
    const two = layer.item('two', [0, 64, 0], 'diamond');
    const three = layer.block('three', [0, 64, 0], 'stone');
    await until(() => e.mod.displays().length === 3);
    one.remove();
    layer.remove('two');
    await until(() => e.mod.displays().length === 1);
    expect(one.alive).toBe(false);
    expect(two.alive).toBe(false);
    layer.clear();
    await until(() => e.mod.displays().length === 0);
    expect(three.alive).toBe(false);
    one.update({ text: 'back' });
    three.move([1, 1, 1]);
    await sleep(30);
    expect(e.mod.displays()).toEqual([]);
  });
});

describe('groups', () => {
  it('places children relative to the group and moves them with one write', async () => {
    const b = await attached();
    const g = b.layer('ui').group('furnace', { pos: [10.5, 65.6, 10.5], billboard: 'vertical', interpolate: 100, transform: { scale: 2 } });
    const title = g.text('title', [0, 0.4, 0], 'Smelting 12/64');
    g.item('input', [-0.3, 0, 0], 'minecraft:iron_ore', { scale: 0.4 });
    g.entity('mob', [0, -1, 0], 'zombie');
    expect(title.id).toBe('furnace/title');
    await until(() => e.mod.groups()[0]?.children.length === 3);
    const [group] = e.mod.groups();
    expect(group).toMatchObject({ key: `${ALICE_PREFIX}/ui/furnace`, id: 'furnace', layer: 'ui' });
    expect(group!.data).toMatchObject({ pos: { x: 10.5, y: 65.6, z: 10.5 }, billboard: 'vertical', interpolateMs: 100, transform: { scale: { x: 2, y: 2, z: 2 } } });
    expect(group!.children.map((c) => [c.id, c.group, (c.data as { pos: unknown }).pos])).toEqual([
      ['furnace/input', 'furnace', { x: -0.3, y: 0, z: 0 }],
      ['furnace/mob', 'furnace', { x: 0, y: -1, z: 0 }],
      ['furnace/title', 'furnace', { x: 0, y: 0.4, z: 0 }],
    ]);

    const writes: string[] = [];
    e.mod.on('objectChanged', (c) => writes.push(c.key));
    g.move([20, 70, 20], { interpolate: 400 });
    await until(() => (e.mod.groups()[0]?.data as { pos: { x: number } }).pos.x === 20);
    expect(writes).toEqual([`${ALICE_PREFIX}/ui/furnace`]);
    expect(e.mod.groups()[0]!.data).toMatchObject({ interpolateMs: 400 });
    expect(thrown(() => g.text('a/b', [0, 0, 0], 'x')).code).toBe('invalid_message');
  });

  it('follows the bot or an entity with an offset', async () => {
    const b = await attached();
    const layer = b.layer('ui');
    const self = layer.group('self', { follow: 'self', offset: [0, 2.2, 0] });
    layer.group('cow', { follow: { id: 42 } });
    await until(() => e.mod.groups().length === 2);
    expect(e.mod.groups().map((g) => g.data)).toEqual([
      expect.objectContaining({ followEntity: 42, followSelf: false, pos: { x: 0, y: 0, z: 0 } }),
      expect.objectContaining({ followSelf: true, pos: { x: 0, y: 2.2, z: 0 } }),
    ]);
    self.update({ follow: null, pos: [1, 2, 3] });
    await until(() => (e.mod.groups()[1]?.data as { followSelf: boolean }).followSelf === false);
    expect(e.mod.groups()[1]!.data).toMatchObject({ pos: { x: 1, y: 2, z: 3 } });
    self.update({ follow: 7 });
    await until(() => (e.mod.groups()[1]?.data as { followEntity?: number }).followEntity === 7);
    expect(e.mod.groups()[1]!.data).toMatchObject({ pos: { x: 0, y: 0, z: 0 } });

    expect(thrown(() => layer.group('a', {})).code).toBe('invalid_message');
    expect(thrown(() => layer.group('b', { follow: 'self', pos: [0, 0, 0] })).code).toBe('invalid_message');
    expect(thrown(() => layer.group('c', { pos: [0, 0, 0], offset: [0, 1, 0] })).code).toBe('invalid_message');
    expect(thrown(() => self.update({ follow: null })).code).toBe('invalid_message');
  });

  it('flattens nested groups and rewrites them when an ancestor moves', async () => {
    const b = await attached();
    const outer = b.layer('ui').group('machine', { pos: [100, 64, 100] });
    const inner = outer.group('panel', { pos: [0, 2, 0] });
    inner.text('label', [0, 0.25, 0], 'Running');
    const leaf = inner.group('knob', { pos: [1, 0, 0] });
    leaf.block('cap', [0, 0, 0], 'stone_button');
    expect(thrown(() => outer.group('follower', { follow: 'self' })).code).toBe('invalid_message');
    await until(() => e.mod.groups().length === 3 && e.mod.displays().length === 2);
    const pos = (id: string): unknown => (e.mod.groups().find((g) => g.id === id)?.data as { pos: unknown }).pos;
    expect(pos('machine/panel')).toEqual({ x: 100, y: 66, z: 100 });
    expect(pos('machine/panel/knob')).toEqual({ x: 101, y: 66, z: 100 });
    const [machine, panel] = e.mod.groups();
    expect(machine!.groups.map((g) => g.id)).toEqual(['machine/panel']);
    expect(panel!.children.map((c) => c.id)).toEqual(['machine/panel/label']);

    outer.move([0, 64, 0], { interpolate: 250 });
    await until(() => (pos('machine/panel/knob') as { x: number }).x === 1);
    expect(pos('machine/panel')).toEqual({ x: 0, y: 66, z: 0 });
    expect(e.mod.groups().find((g) => g.id === 'machine/panel/knob')!.data).toMatchObject({ interpolateMs: 250 });

    const follower = b.layer('ui').group('rider', { follow: 9, offset: [0, 1, 0] });
    follower.group('hat', { pos: [0, 0.5, 0] });
    await until(() => e.mod.groups().some((g) => g.id === 'rider/hat'));
    expect(e.mod.groups().find((g) => g.id === 'rider/hat')!.data).toMatchObject({ followEntity: 9, pos: { x: 0, y: 1.5, z: 0 } });
  });

  it('removes a group with its children and nested groups in one removal', async () => {
    const b = await attached();
    const layer = b.layer('ui');
    const g = layer.group('menu', { pos: [0, 64, 0] });
    const child = g.text('a', [0, 0, 0], 'A');
    const nested = g.group('sub', { pos: [0, 1, 0] });
    const grandchild = nested.item('b', [0, 0, 0], 'apple');
    layer.text('other', [0, 64, 0], 'stays');
    await until(() => e.mod.displays().length === 3 && e.mod.groups().length === 2);
    const removals: unknown[] = [];
    e.mod.on('message', (m) => {
      if (m.type === 'obj.remove') removals.push(m.payload);
    });
    g.remove();
    await until(() => e.mod.groups().length === 0 && e.mod.displays().length === 1);
    expect(removals).toEqual([{ keys: [`${ALICE_PREFIX}/ui/menu`], prefixes: [`${ALICE_PREFIX}/ui/menu/`] }]);
    expect([g.alive, child.alive, nested.alive, grandchild.alive]).toEqual([false, false, false, false]);
    expect(thrown(() => g.text('c', [0, 0, 0], 'C')).code).toBe('invalid_message');

    const again = layer.group('menu', { pos: [0, 64, 0] });
    again.text('a', [0, 0, 0], 'A');
    await until(() => e.mod.groups()[0]?.children.length === 1);
    layer.remove('menu');
    await until(() => e.mod.groups().length === 0 && e.mod.displays().length === 1);
  });
});

describe('interactive elements', () => {
  it('declares the inputs of the handlers present and routes events with friendly fields', async () => {
    const b = await attached();
    const events: [string, ElementEvent | number | string][] = [];
    const t = b.layer('shop').item('offer', [0, 65, 0], 'diamond', {
      interactive: {
        tooltip: 'Left: one, Right: all',
        maxDistance: 6,
        hitbox: [1, 2, 1],
        tags: ['shop'],
        keys: ['f'],
        on: {
          primary: (ev) => void events.push(['primary', ev]),
          secondary: (ev) => void events.push(['secondary', ev]),
          middle: (ev) => void events.push(['middle', ev]),
          scroll: (d) => void events.push(['scroll', d]),
          key: { q: (ev) => void events.push(['q', ev]), 'key.keyboard.e': (ev) => void events.push(['e', ev]) },
          hover: (ev) => void events.push(['hover', ev]),
          leave: (ev) => void events.push(['leave', ev]),
          drag: (ev) => void events.push([`drag-${String(ev.phase)}`, ev]),
        },
      },
    });
    const key = `${ALICE_PREFIX}/shop/offer`;
    const d = await shown(key);
    expect(d.traits?.interactive).toEqual({
      inputs: ['hover_enter', 'hover_leave', 'primary', 'secondary', 'middle', 'scroll', 'key', 'drag_start', 'drag', 'drag_end'],
      tooltip: 'Left: one, Right: all',
      keys: ['key.keyboard.f', 'key.keyboard.q', 'key.keyboard.e'],
      maxDistance: 6,
      hitbox: { x: 1, y: 2, z: 1 },
      tags: ['shop'],
    });

    e.mod.elementEvent(key, 'primary', { local: { x: 0.1, y: 0.2, z: 0.3 }, modifiers: { shift: true, alt: true } });
    e.mod.elementEvent(key, 'secondary', { modifiers: 2 });
    e.mod.elementEvent(key, 'middle');
    e.mod.elementEvent(key, 'scroll', { delta: -3 });
    e.mod.elementEvent(key, 'key', { key: 'key.keyboard.q' });
    e.mod.elementEvent(key, 'key', { key: 'key.keyboard.e' });
    e.mod.elementEvent(key, 'hover_enter');
    e.mod.elementEvent(key, 'hover_leave');
    e.mod.elementEvent(key, 'drag_start', { local: { x: 0, y: 0, z: 0 } });
    e.mod.elementEvent(key, 'drag', { local: { x: 0.5, y: 0, z: 0 } });
    e.mod.elementEvent(key, 'drag_end', { local: { x: 1, y: 0, z: 0 } });
    expect(thrown(() => e.mod.elementEvent(key, 'key', { key: 'key.keyboard.z' })).code).toBe('invalid_message');
    await until(() => events.length === 11);
    expect(events.map(([n]) => n)).toEqual(['primary', 'secondary', 'middle', 'scroll', 'q', 'e', 'hover', 'leave', 'drag-start', 'drag-move', 'drag-end']);
    expect(events[0]![1]).toEqual({
      id: 'offer',
      layer: 'shop',
      input: 'primary',
      local: { x: expect.closeTo(0.1, 5), y: expect.closeTo(0.2, 5), z: expect.closeTo(0.3, 5) },
      modifiers: { shift: true, ctrl: false, alt: true },
      delta: undefined,
      key: undefined,
      phase: undefined,
    });
    expect((events[1]![1] as ElementEvent).modifiers).toEqual({ shift: false, ctrl: true, alt: false });
    expect(events[3]![1]).toBe(-3);
    expect(events[4]![1]).toMatchObject({ input: 'key', key: 'key.keyboard.q' });
    expect(events[9]![1]).toMatchObject({ input: 'drag', phase: 'move', local: { x: 0.5, y: 0, z: 0 } });
    expect(t.alive).toBe(true);
  });

  it('declares only the handled inputs, swaps handlers on update and stops on interactive null', async () => {
    const b = await attached();
    const errors: Error[] = [];
    b.on('error', (err) => errors.push(err));
    const hits: string[] = [];
    const key = `${ALICE_PREFIX}/ui/button`;
    const t = b.layer('ui').text('button', [0, 65, 0], '[ Go ]', {
      interactive: { keys: ['g'], on: { primary: () => void hits.push('first'), key: (k) => void hits.push(k) } },
    });
    expect((await shown(key)).traits?.interactive).toEqual({ inputs: ['primary', 'key'], keys: ['key.keyboard.g'] });
    expect(thrown(() => e.mod.elementEvent(key, 'hover_enter')).code).toBe('invalid_message');
    e.mod.elementEvent(key, 'key', { key: 'key.keyboard.g' });
    await until(() => hits.length === 1);

    t.update({ interactive: { on: { primary: () => void hits.push('second'), secondary: () => Promise.reject(new Error('boom')) } } });
    await until(() => display(key)?.traits?.interactive?.inputs.includes('secondary'));
    e.mod.elementEvent(key, 'primary');
    e.mod.elementEvent(key, 'secondary');
    await until(() => hits.length === 2 && errors.length === 1);
    expect(hits).toEqual(['key.keyboard.g', 'second']);
    expect(errors[0]!.message).toBe('boom');

    t.update({ interactive: null });
    await until(() => display(key)?.traits === undefined);
    expect(thrown(() => e.mod.elementEvent(key, 'primary')).code).toBe('invalid_message');
  });

  it('drops the routes of an extension on unload, with its displays', async () => {
    const b = await attached();
    let api: { hits: number } = { hits: 0 };
    let saved: ExtensionContext | undefined;
    const ext = defineExtension({
      id: 'acme:shop',
      version: '1.0.0',
      setup(ctx) {
        saved = ctx;
        const state = { hits: 0 };
        const g = ctx.layer('stall').group('stall', { pos: [0, 64, 0] });
        g.item('offer', [0, 1, 0], 'emerald', { interactive: { on: { primary: () => void state.hits++ } } });
        ctx.layer('stall').emitter('sparkle', [0, 65, 0], 'happy_villager');
        return state;
      },
    });
    await b.use(ext());
    api = b.get<{ hits: number }>('acme:shop')!;
    const key = `${ALICE}/acme:shop/stall/stall/offer`;
    await shown(key);
    e.mod.elementEvent(key, 'primary');
    await until(() => api.hits === 1);
    await b.unload('acme:shop');
    await until(() => e.mod.displays().length === 0 && e.mod.groups().length === 0 && e.mod.emitters().length === 0);
    expect(thrown(() => e.mod.elementEvent(key, 'primary')).code).toBe('invalid_message');
    expect(saved!.signal.aborted).toBe(true);
  });

  it('keeps displays and handlers through a resume and a mod restart', async () => {
    const b = await attached();
    let hits = 0;
    const key = `${ALICE_PREFIX}/ui/btn`;
    b.layer('ui').text('btn', [0, 65, 0], 'Press', { interactive: { on: { primary: () => void hits++ } } });
    b.layer('ui').group('g', { follow: 'self' }).text('tag', [0, 2, 0], 'Alice');
    await shown(key);

    // Count the drop by event: the plugin can reconnect within one poll on a fast machine.
    let drops = 0;
    const off = b.on('disconnected', () => void drops++);
    e.mod.disconnect(ALICE);
    await until(() => drops > 0, 5000, 'disconnect');
    off();
    await until(() => b.state.connected, 5000, 'resume');
    e.mod.elementEvent(key, 'primary');
    await until(() => hits === 1);

    await e.restart();
    e.mod.setPlayerContext(OVERWORLD);
    await until(() => display(key) !== undefined && e.mod.groups()[0]?.children.length === 1, 8000, 'redeclared');
    e.mod.elementEvent(key, 'primary');
    await until(() => hits === 2);
  });
});

describe('particle emitters', () => {
  it('declares, patches, moves and removes an emitter', async () => {
    const b = await attached();
    const em = b.layer('fx').emitter('smoke', [0, 65, 0], 'campfire_cosy_smoke', { rate: 2, count: 3, spread: 0.5, speed: 0.01, options: { scale: 2 } });
    await until(() => e.mod.emitters().length === 1);
    expect(e.mod.emitters()[0]).toMatchObject({ key: `${ALICE_PREFIX}/fx/smoke`, kind: 'core:particle-emitter', id: 'smoke' });
    expect(e.mod.emitters()[0]!.data).toEqual({
      pos: { x: 0, y: 65, z: 0 },
      particle: 'minecraft:campfire_cosy_smoke',
      rate: expect.closeTo(2, 5),
      count: 3,
      spread: { x: 0.5, y: 0.5, z: 0.5 },
      speed: expect.closeTo(0.01, 5),
      options: { scale: 2 },
    });
    em.update({ particle: 'flame', spread: null, rate: 8 });
    em.move([1, 66, 1], { interpolate: 100 });
    await until(() => (e.mod.emitters()[0]?.data as { particle: string }).particle === 'minecraft:flame');
    expect(e.mod.emitters()[0]!.data).toMatchObject({ pos: { x: 1, y: 66, z: 1 }, rate: 8, spread: undefined });
    expect(thrown(() => em.update({ rate: 0 })).code).toBe('invalid_message');
    em.remove();
    await until(() => e.mod.emitters().length === 0);
  });
});

describe('quotas', () => {
  it('rejects displays, entity displays and emitters beyond the session quota before sending', async () => {
    const b = await attached();
    const layer = b.layer('many');
    for (let i = 0; i < 256; i++) layer.entity(`e${i}`, [i, 64, 0], 'zombie');
    const entity = thrown(() => layer.entity('one-more', [0, 64, 0], 'zombie'));
    expect(entity.code).toBe('quota_exceeded');
    expect(entity.details).toMatchObject({ limit: 256 });
    layer.entity('e0', [0, 65, 0], 'skeleton');

    for (let i = 0; i < 32; i++) layer.emitter(`p${i}`, [i, 64, 0], 'flame');
    expect(thrown(() => layer.emitter('p-more', [0, 64, 0], 'flame')).code).toBe('quota_exceeded');

    for (let i = 256; i < 4096; i++) layer.text(`t${i}`, [i, 64, 0], 'x');
    expect(thrown(() => layer.block('b', [0, 64, 0], 'stone')).code).toBe('quota_exceeded');
    expect(thrown(() => b.objects.put('many/raw', 'core:display.text', { pos: { x: 0, y: 0, z: 0 }, text: 'x' })).code).toBe('quota_exceeded');
    layer.text('t300', [0, 64, 0], 'replaced');
    layer.remove('t300');
    layer.block('b', [0, 64, 0], 'stone');
    await until(() => e.mod.displays().length === 4096, 10_000, 'all displays');
    expect(logger.warnings).toEqual([]);
  });

  it('checks against the quotas the mod advertises in welcome instead of the defaults', async () => {
    await e.restart({ quotas: { displays: 2, entityDisplays: 1, emitters: 1, interactive: 7, effectsPerSecond: 3, objects: 99 } });
    const b = await attached();
    expect(b.limits).toMatchObject({ maxDisplays: 2, maxEntityDisplays: 1, maxEmitters: 1, maxInteractive: 7, effectsPerSecond: 3, maxObjects: 99 });
    const errors: string[] = [];
    e.mod.on('protocolError', (p) => errors.push(p.code));
    const layer = b.layer('few');
    layer.entity('a', [0, 64, 0], 'cow');
    expect(thrown(() => layer.entity('b', [0, 64, 0], 'pig')).details).toMatchObject({ limit: 1 });
    layer.text('c', [0, 64, 0], 'one');
    expect(thrown(() => layer.text('d', [0, 64, 0], 'two')).details).toMatchObject({ limit: 2 });
    layer.emitter('e', [0, 64, 0], 'flame');
    expect(thrown(() => layer.emitter('f', [0, 64, 0], 'flame')).code).toBe('quota_exceeded');
    await until(() => e.mod.emitters().length === 1);
    expect(e.mod.displays().map((d) => d.id)).toEqual(['a', 'c']);
    for (let i = 0; i < 5; i++) b.effects.sound('block.note_block.pling');
    b.effects.title('titles count too');
    await until(() => e.mod.effects().length === 3);
    await sleep(50);
    expect(e.mod.effects().map((x) => x.effect.type)).toEqual(['sound', 'sound', 'sound']);
    expect(errors).toEqual([]);
  });
});

describe('world menus', () => {
  it('resolves with the chosen entry id', async () => {
    const b = await attached();
    const queued = nextInput();
    const choice = b.worldMenu({
      at: [0, 66, 0],
      layout: 'row',
      radius: 2,
      prompt: 'Which item should I drop?',
      timeoutMs: 20_000,
      entries: [
        { id: '36', item: { name: 'diamond', count: 2 }, label: 'Diamond' },
        { id: 'stone', block: 'minecraft:stone' },
        { id: 'cow', entity: 'minecraft:cow', label: 'Cow' },
        { id: 'none', text: 'Nothing' },
      ],
    });
    const r = await queued;
    expect(r).toMatchObject({ type: 'world-menu', kind: 'world-menu', request: { at: { x: 0, y: 66, z: 0 }, layout: 'row', prompt: 'Which item should I drop?', timeoutMs: 20_000 } });
    expect(r.type === 'world-menu' && r.request.radius).toBeCloseTo(2);
    expect(r.type === 'world-menu' && r.request.entries.map((x) => [x.id, x.item?.id, x.block, x.entity, x.text, x.label])).toEqual([
      ['36', 'minecraft:diamond', undefined, undefined, undefined, 'Diamond'],
      ['stone', undefined, 'minecraft:stone', undefined, undefined, undefined],
      ['cow', undefined, undefined, 'minecraft:cow', undefined, 'Cow'],
      ['none', undefined, undefined, undefined, 'Nothing', undefined],
    ]);
    expect(thrown(() => e.mod.resolveWorldMenu(r.id, 'missing')).code).toBe('invalid_message');
    e.mod.resolveWorldMenu(r.id, 'cow');
    expect(await choice).toBe('cow');
  });

  it('rejects with cancelled, withdraws on abort and rejects with timeout at the deadline', async () => {
    const b = await attached();
    const entries = [{ id: 'yes', text: 'Yes' }, { id: 'no', text: 'No' }];
    let queued = nextInput();
    const cancelled = b.worldMenu({ at: [0, 66, 0], entries });
    e.mod.cancel((await queued).id);
    expect((await rejection(cancelled)).code).toBe('cancelled');

    queued = nextInput();
    const controller = new AbortController();
    const aborted = b.worldMenu({ at: [0, 66, 0], entries, signal: controller.signal });
    await queued;
    const settled = e.mod.waitFor('inputSettled');
    controller.abort();
    expect((await rejection(aborted)).code).toBe('cancelled');
    expect((await settled)[0].outcome).toBe('withdrawn');

    queued = nextInput();
    const late = b.worldMenu({ at: [0, 66, 0], entries, timeoutMs: 50 });
    await queued;
    expect((await rejection(late)).code).toBe('timeout');
    expect(e.mod.inputQueue()).toEqual([]);
  });

  it('checks options before sending and needs the player in the bot world', async () => {
    const b = await attached();
    const bad = [
      b.worldMenu({ at: [0, 0, 0], entries: [] }),
      b.worldMenu({ at: [0, 0, 0], entries: [{ id: 'a', text: 'A' }, { id: 'a', text: 'B' }] }),
      b.worldMenu({ at: [0, 0, 0], entries: [{ id: 'a' }] }),
      b.worldMenu({ at: [0, 0, 0], entries: [{ id: 'a', text: 'A' }], layout: 'spiral' as 'ring' }),
      b.worldMenu({ at: [0, 0, 0], entries: [{ id: 'a', text: 'A' }], timeoutMs: 0 }),
      b.worldMenu({ at: [0, 0, 0], entries: [{ id: 'a', text: 'A' }], radius: -1 }),
    ];
    for (const p of bad) expect((await rejection(p)).code).toBe('invalid_message');
    e.mod.setAttached('alice@play.example.net:25565', true);
    e.mod.setPlayerContext({ server: SERVER, dimension: 'minecraft:the_nether' });
    await until(() => !b.state.worldMatched);
    expect((await rejection(b.worldMenu({ at: [0, 0, 0], entries: [{ id: 'a', text: 'A' }] }))).code).toBe('world_mismatch');
  });
});

describe('world prompts and reported surfaces', () => {
  it('asks confirm and choice prompts on the world surface and reports it', async () => {
    const b = await attached();
    const surfaces: string[] = [];
    let queued = nextInput();
    const ok = b.prompt.confirm('Place 214 blocks?', { surface: 'world', at: [0, 66, 0], onSurface: (s) => void surfaces.push(s) });
    let r = await queued;
    expect(r).toMatchObject({ type: 'prompt', kind: 'confirm', surface: 'world', request: { surface: 'world', placement: 'world', at: { x: 0, y: 66, z: 0 } } });
    e.mod.resolvePrompt(r.id, true);
    expect(await ok).toBe(true);

    queued = nextInput();
    const tool = b.prompt.choice('Which tool?', ['pickaxe', 'shovel'], { surface: 'world', at: [0, 66, 0], onSurface: (s) => void surfaces.push(s) });
    r = await queued;
    e.mod.resolvePrompt(r.id, 'shovel');
    expect(await tool).toBe('shovel');

    queued = nextInput();
    const text = b.prompt.text('Name?', { timeoutMs: 50, onSurface: (s) => void surfaces.push(s) });
    await queued;
    expect(await text).toBe('');
    expect(surfaces).toEqual(['world', 'world', 'hud']);
  });

  it('reports the fallback surface the mod used', async () => {
    await e.restart({ surfaces: ['hud', 'screen'] });
    const b = await attached();
    let surface: string | undefined;
    const queued = nextInput();
    const ok = b.prompt.confirm('Here?', { surface: 'world', at: [0, 66, 0], onSurface: (s) => (surface = s) });
    const r = await queued;
    expect(r).toMatchObject({ surface: 'hud' });
    e.mod.resolvePrompt(r.id, false);
    expect(await ok).toBe(false);
    expect(surface).toBe('hud');
  });
});

describe('effects', () => {
  it('plays sounds, particles, titles and action-bar messages unreliably', async () => {
    const b = await attached();
    b.effects.sound('block.note_block.pling', { at: [1, 2, 3], volume: 0.5, pitch: 2 });
    b.effects.sound('minecraft:entity.experience_orb.pickup');
    b.effects.particles('happy_villager', { at: [0, 65, 0], count: 10, spread: [0.5, 1, 0.5], speed: 0.1, options: { color: 1 } });
    b.effects.title('Done', { subtitle: '214 blocks', fadeIn: 5, stay: 40, fadeOut: 5 });
    b.effects.actionBar('Mining iron');
    await until(() => e.mod.effects().length === 5);
    const all = e.mod.effects();
    expect(all.every((x) => x.owner === ALICE)).toBe(true);
    expect(all.map((x) => x.effect)).toEqual([
      { type: 'sound', value: { id: 'minecraft:block.note_block.pling', at: { x: 1, y: 2, z: 3 }, volume: 0.5, pitch: 2 } },
      { type: 'sound', value: { id: 'minecraft:entity.experience_orb.pickup', at: undefined, volume: 1, pitch: 1 } },
      {
        type: 'particles',
        value: { type: 'minecraft:happy_villager', at: { x: 0, y: 65, z: 0 }, count: 10, spread: { x: 0.5, y: 1, z: 0.5 }, speed: expect.closeTo(0.1, 5), options: { color: 1 } },
      },
      { type: 'title', value: { title: 'Done', subtitle: '214 blocks', fadeIn: 5, stay: 40, fadeOut: 5 } },
      { type: 'actionbar', value: { text: 'Mining iron' } },
    ]);
    const unsequenced: boolean[] = [];
    e.mod.on('message', (m) => {
      if (m.type === 'effect.play') unsequenced.push(m.header.seq === undefined);
    });
    b.effects.actionBar('again');
    await until(() => unsequenced.length === 1);
    expect(unsequenced).toEqual([true]);
    expect(thrown(() => b.effects.sound('')).code).toBe('invalid_message');
    expect(thrown(() => b.effects.particles('flame', { at: [0, 0, 0], count: -1 })).code).toBe('invalid_message');
    expect(thrown(() => b.effects.title('x', { stay: 1.5 })).code).toBe('invalid_message');
  });

  it('drops effects while the session is not open', async () => {
    const bot = new FakeBot('Alice');
    const b = load(hub().plugin(), bot);
    b.effects.actionBar('too early');
    await until(() => b.state.connected);
    b.effects.actionBar('on time');
    await until(() => e.mod.effects().length === 1);
    await sleep(30);
    expect(e.mod.effects().map((x) => x.effect)).toEqual([{ type: 'actionbar', value: { text: 'on time' } }]);
  });

  it('exists on extension contexts too', async () => {
    const b = await attached();
    await b.use(
      defineExtension({
        id: 'acme:fx',
        version: '1.0.0',
        setup(ctx) {
          ctx.effects.title('Hello');
        },
      })(),
    );
    await until(() => e.mod.effects().length === 1);
    expect(e.mod.effects()[0]!.effect).toMatchObject({ type: 'title', value: { title: 'Hello' } });
  });
});
