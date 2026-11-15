import { describe, expect, it } from 'vitest';
import { parseSnbt } from '@mineflayer-bridge/mock-mod';
import { BridgeError, nbt, toSnbt } from '../src/index.ts';
import { ALICE_PREFIX, fixture, until, type Env } from './helpers.ts';

let e: Env;
const { attached } = fixture((f) => (e = f.e));

describe('SNBT', () => {
  it('quotes strings, writes booleans as bytes and keeps plain numbers as ints or doubles', () => {
    expect(toSnbt({ CustomName: 'say "hi" \\o/', NoAI: true, Silent: false, Age: -24000, Scale: 1.5, Tiny: 1e-7, Big: 3e10 })).toBe(
      '{CustomName:"say \\"hi\\" \\\\o/",NoAI:1b,Silent:0b,Age:-24000,Scale:1.5,Tiny:1e-7d,Big:30000000000d}',
    );
    expect(toSnbt({ 'odd key': 1, skipped: undefined, Tags: ['a', 'b'], Pos: [nbt.double(1), nbt.double(2.5), nbt.double(3)] })).toBe('{"odd key":1,Tags:["a","b"],Pos:[1d,2.5d,3d]}');
    expect(
      toSnbt({
        b: nbt.byte(-3),
        s: nbt.short(300),
        i: nbt.int(7),
        l: nbt.long(2n ** 40n),
        f: nbt.float(0.5),
        ba: nbt.byteArray([1, 2]),
        ia: nbt.intArray([3]),
        la: nbt.longArray([4, 5n]),
        nested: { Inner: { Deep: [] } },
      }),
    ).toBe('{b:-3b,s:300s,i:7,l:1099511627776L,f:0.5f,ba:[B;1b,2b],ia:[I;3],la:[L;4L,5L],nested:{Inner:{Deep:[]}}}');
    expect(toSnbt({ nbtType: 'byte', value: 1 })).toBe('{nbtType:"byte",value:1}');
  });

  it('rejects values SNBT cannot carry', () => {
    expect(() => toSnbt({ x: Number.NaN })).toThrow(/finite/);
    expect(() => toSnbt({ x: null } as never)).toThrow(/null/);
    expect(() => toSnbt({ x: nbt.byte(200) })).toThrow(/byte/);
    expect(() => toSnbt({ x: nbt.long(2 ** 60) })).toThrow(/safe integer/);
    expect(() => toSnbt({ x: nbt.int(1.5) })).toThrow(/int/);
    expect(() => toSnbt([] as never)).toThrow(BridgeError);
  });

  it('round-trips through the mock parser', () => {
    const text = toSnbt({ id: 'minecraft:zombie', Health: nbt.float(5), Tags: ['shown', 'bot'], ArmorItems: [{}, { id: 'minecraft:iron_boots', count: 1 }], Glowing: true });
    expect(parseSnbt(text)).toEqual({ id: 'minecraft:zombie', Health: 5, Tags: ['shown', 'bot'], ArmorItems: [{}, { id: 'minecraft:iron_boots', count: 1 }], Glowing: 1 });
    expect(parseSnbt("{a:[I;1,2],'q':'x\\'y',w:word}")).toEqual({ a: [1, 2], q: "x'y", w: 'word' });
    expect(() => parseSnbt('{a:1')).toThrow(/bad SNBT/);
  });
});

describe('entity display properties', () => {
  it('declares flags, variants, armor stand poses, effects and data, and updates them as patches', async () => {
    const b = await attached();
    const layer = b.layer('mobs');
    const cat = layer.entity('cat', [0, 64, 0], 'cat', { variant: 'minecraft:tabby', color: 'red', onFire: true, frozen: true, effects: ['speed', 'minecraft:glowing', 'speed'] });
    layer.entity('villager', [1, 64, 0], 'villager', { profession: 'minecraft:librarian', variant: 'minecraft:taiga', invisible: true });
    layer.entity('creeper', [2, 64, 0], 'creeper', { charged: true, data: '{ExplosionRadius:0b}' });
    layer.entity('pig', [3, 64, 0], 'pig', { saddled: true, data: { CustomName: 'Bacon', Health: nbt.float(5), PersistenceRequired: true } });
    const stand = layer.entity('stand', [4, 64, 0], 'armor_stand', {
      armorStand: { small: true, showArms: true, noBasePlate: true, pose: { head: [10, 20, 0], rightArm: [-90, 0, 15] } },
    });
    const g = layer.group('crew', { pos: [5, 64, 5] });
    g.entity('guard', [0, 0, 1], 'iron_golem', { invisible: true, effects: ['regeneration'] });
    await until(() => e.mod.displays().length === 6);

    expect(e.mod.entityProps(`${ALICE_PREFIX}/mobs/cat`)).toMatchObject({
      type: 'minecraft:cat',
      variant: 'minecraft:tabby',
      color: 'red',
      onFire: true,
      frozen: true,
      invisible: false,
      effects: ['minecraft:speed', 'minecraft:glowing'],
      armorStand: undefined,
      nbt: undefined,
    });
    expect(e.mod.entityProps(`${ALICE_PREFIX}/mobs/villager`)).toMatchObject({ profession: 'minecraft:librarian', variant: 'minecraft:taiga', invisible: true });
    expect(e.mod.entityProps(`${ALICE_PREFIX}/mobs/creeper`)).toMatchObject({ charged: true, data: '{ExplosionRadius:0b}', nbt: { ExplosionRadius: 0 } });
    expect(e.mod.entityProps(`${ALICE_PREFIX}/mobs/pig`)).toMatchObject({ saddled: true, data: '{CustomName:"Bacon",Health:5f,PersistenceRequired:1b}', nbt: { CustomName: 'Bacon', Health: 5, PersistenceRequired: 1 } });
    expect(e.mod.entityProps(`${ALICE_PREFIX}/mobs/stand`).armorStand).toEqual({ small: true, showArms: true, noBasePlate: true, marker: false, pose: { head: [10, 20, 0], rightArm: [-90, 0, 15] } });
    expect(e.mod.entityProps(`${ALICE_PREFIX}/mobs/crew/guard`)).toMatchObject({ invisible: true, effects: ['minecraft:regeneration'] });

    const writes: string[] = [];
    e.mod.on('objectChanged', (c) => writes.push(c.write));
    cat.update({ onFire: null, effects: [] });
    stand.update({ armorStand: { marker: true } });
    await until(() => e.mod.entityProps(`${ALICE_PREFIX}/mobs/stand`).armorStand?.marker === true && !e.mod.entityProps(`${ALICE_PREFIX}/mobs/cat`).onFire);
    expect(writes).toEqual(['patch', 'patch']);
    expect(e.mod.entityProps(`${ALICE_PREFIX}/mobs/cat`).effects).toEqual([]);
    expect(e.mod.entityProps(`${ALICE_PREFIX}/mobs/stand`).armorStand).toEqual({ small: false, showArms: false, noBasePlate: false, marker: true, pose: {} });
  });

  it('rejects entity options that do not fit', async () => {
    const b = await attached();
    const layer = b.layer('mobs');
    expect(() => layer.entity('a', [0, 0, 0], 'armor_stand', { armorStand: { pose: { head: [1, 2] as never } } })).toThrow(/\[x, y, z\]/);
    expect(() => layer.entity('a', [0, 0, 0], 'armor_stand', { armorStand: { pose: { body: [1, Number.NaN, 0] } } })).toThrow(/finite/);
    expect(() => layer.entity('a', [0, 0, 0], 'cat', { variant: ' ' })).toThrow(/variant/);
    expect(() => layer.entity('a', [0, 0, 0], 'cat', { effects: ['bad id'] })).toThrow(/status effect/);
    expect(() => layer.entity('a', [0, 0, 0], 'cat', { data: '' })).toThrow(/entity data/);
    expect(() => layer.entity('a', [0, 0, 0], 'cat', { data: { x: Number.POSITIVE_INFINITY } })).toThrow(/finite/);
    expect(e.mod.displays()).toEqual([]);
  });
});
