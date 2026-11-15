import { describe, expect, it } from 'vitest';
import type { MockInputRequest } from '@mineflayer-bridge/mock-mod';
import { BridgeError, defineExtension, type InterceptContext, type Target } from '../src/index.ts';
import { fixture, rejection, sleep, until, type Env } from './helpers.ts';

const ALICE = 'bot:alice@play.example.net:25565';
const BOB = 'bot:bob@play.example.net:25565';
const CHEST: Target = { type: 'block', block: { x: 1, y: 64, z: 2 }, face: 'up' };

let e: Env;
const { attached } = fixture((f) => (e = f.e));

async function nextInput(): Promise<MockInputRequest> {
  const [r] = await e.mod.waitFor('inputRequest');
  return r;
}

describe('interaction intercepts', () => {
  it('declares rules with defaults and routes observed interactions', async () => {
    const b = await attached();
    const seen: [Target, InterceptContext][] = [];
    const off = b.intercepts.register('watch', { target: { type: 'block', tags: ['c:chests'] }, run: (t, ctx) => void seen.push([t, ctx]) });
    b.intercepts.register('deposit', { target: { type: 'block', ids: ['minecraft:chest'] }, input: 'primary', mode: 'cancel', modifier: 'ctrl', priority: 'high', scope: 'global', run: () => 'deposited' });
    await until(() => e.mod.intercepts().length === 2);
    expect(e.mod.intercepts().map((r) => [r.id, r.scope, r.data])).toEqual([
      ['deposit', 'global', { target: { type: 'block', ids: ['minecraft:chest'] }, input: 'primary', mode: 'cancel', modifier: 'control', priority: 'high' }],
      ['watch', 'world', { target: { type: 'block', tags: ['c:chests'] }, input: 'secondary', mode: 'observe', priority: 'normal' }],
    ]);
    expect(b.intercepts.has('watch')).toBe(true);

    const out = await e.mod.interact({ target: CHEST, tags: ['c:chests'] });
    expect(out).toMatchObject({ cancelled: false, by: undefined, deliveries: [{ key: `${ALICE}/user:default/~intercept/watch`, mode: 'observe', cancelled: false }] });
    await until(() => seen.length === 1);
    expect(seen[0]![0]).toEqual(CHEST);
    expect(seen[0]![1]).toMatchObject({ key: `${ALICE}/user:default/~intercept/watch`, cancelled: false });
    expect((await e.mod.interact({ target: CHEST, tags: ['c:barrels'] })).deliveries).toEqual([]);
    expect((await e.mod.interact({ target: CHEST, input: 'primary', id: 'minecraft:chest' })).deliveries).toEqual([]);
    expect((await e.mod.interact({ target: CHEST, input: 'primary', id: 'minecraft:chest', modifiers: { control: true } })).deliveries).toHaveLength(1);

    off();
    await until(() => e.mod.intercepts().length === 1);
    b.intercepts.unregister('deposit');
    await until(() => e.mod.intercepts().length === 0);
  });

  it('cancels only with the permission, answers with the handler result and runs rules in priority order across bots', async () => {
    const alice = await attached('Alice');
    const bob = await attached('Bob');
    const order: string[] = [];
    alice.intercepts.register('open', { target: { type: 'block' }, mode: 'cancel', priority: 'normal', run: async (_t, ctx) => (order.push(`alice:${ctx.cancelled}`), 'opened the deposit menu') });
    bob.intercepts.register('first', { target: { type: 'block' }, mode: 'cancel', priority: 'highest', run: (_t, ctx) => void order.push(`bob-first:${ctx.cancelled}`) });
    bob.intercepts.register('log', { target: { type: 'block' }, priority: 'monitor', run: (_t, ctx) => void order.push(`bob-log:${ctx.cancelled}`) });
    await until(() => e.mod.intercepts().length === 3);
    // Bots of local devices may cancel by default; the player turned it off for both.
    e.mod.setInterceptCancel(ALICE, false);
    e.mod.setInterceptCancel(BOB, false);

    let out = await e.mod.interact({ target: CHEST });
    expect(out.cancelled).toBe(false);
    expect(out.deliveries.map((d) => [d.key.split('/').at(-1), d.cancelled])).toEqual([
      ['first', false],
      ['open', false],
      ['log', false],
    ]);

    e.mod.setInterceptCancel(ALICE, true);
    out = await e.mod.interact({ target: CHEST });
    expect(out).toMatchObject({ cancelled: true, by: `${ALICE}/user:default/~intercept/open`, result: { ok: true, message: 'opened the deposit menu' } });
    expect(out.deliveries.map((d) => [d.owner, d.cancelled])).toEqual([
      [BOB, false],
      [ALICE, false],
      [BOB, true],
    ]);

    e.mod.setInterceptCancel('bob@play.example.net:25565', true);
    out = await e.mod.interact({ target: CHEST });
    expect(out.by).toBe(`${BOB}/user:default/~intercept/first`);
    expect(out.result).toEqual({ ok: true, message: undefined });
    await until(() => order.length === 9);
    // Each bot has its own connection, so only the flags are ordered, not the arrival.
    expect(order.slice(6).sort()).toEqual(['alice:true', 'bob-first:false', 'bob-log:true']);

    alice.intercepts.register('open', { target: { type: 'block' }, mode: 'cancel', priority: 'highest', run: () => Promise.reject(new Error('the chest is locked')) });
    e.mod.setInterceptCancel(BOB, false);
    await sleep(30);
    expect((await e.mod.interact({ target: CHEST })).result).toEqual({ ok: false, message: 'the chest is locked' });
  });

  it('skips rules of detached bots and world rules in another world, and refuses rules nobody handles', async () => {
    const b = await attached();
    b.intercepts.register('world', { target: { type: 'entity' }, run: () => undefined });
    b.objects.put('~intercept/raw', 'core:intercept', { target: { type: 'entity' }, mode: 'cancel' }, { world: 'global' });
    await until(() => e.mod.intercepts().length === 2);
    const out = await e.mod.interact({ target: { type: 'entity', entity: 7 } });
    expect(out).toMatchObject({ cancelled: true, by: `${ALICE}/user:default/~intercept/raw`, result: { ok: false, message: 'not available' } });
    e.mod.setPlayerContext({ server: 'other:25565', dimension: 'minecraft:overworld' });
    e.mod.setAttached(ALICE, true);
    expect((await e.mod.interact({ target: { type: 'entity', entity: 7 } })).deliveries.map((d) => d.key.split('/').at(-1))).toEqual(['raw']);
    e.mod.setAttached(ALICE, false);
    expect((await e.mod.interact({ target: { type: 'entity', entity: 7 } })).deliveries).toEqual([]);
  });

  it('validates rules and drops them with their extension', async () => {
    const b = await attached();
    const bad = (def: object): string => {
      try {
        b.intercepts.register('x', def as never);
      } catch (err) {
        expect(err).toBeInstanceOf(BridgeError);
        return (err as BridgeError).message;
      }
      throw new Error('expected a throw');
    };
    expect(bad({ target: { type: 'block' } })).toMatch(/run function/);
    expect(bad({ target: {}, run: () => undefined })).toMatch(/target type/);
    expect(bad({ target: { type: 'block' }, input: 'middle', run: () => undefined })).toMatch(/primary or secondary/);
    expect(bad({ target: { type: 'block' }, mode: 'block', run: () => undefined })).toMatch(/observe or cancel/);
    expect(bad({ target: { type: 'block' }, priority: 'urgent', run: () => undefined })).toMatch(/unknown intercept priority/);
    expect(bad({ target: { type: 'block' }, modifier: 'meta', run: () => undefined })).toMatch(/modifier/);
    expect(() => b.intercepts.register('a/b', { target: { type: 'block' }, run: () => undefined })).toThrow(BridgeError);

    await b.use(defineExtension({ id: 'acme:guard', version: '1.0.0', setup: (ctx) => void ctx.intercepts.register('chest', { target: { type: 'block' }, run: () => undefined }) }));
    await until(() => e.mod.intercepts().some((r) => r.extension === 'acme:guard'));
    await b.unload('acme:guard');
    await until(() => e.mod.intercepts().length === 0);
  });
});

describe('more vanilla surfaces', () => {
  it('offers item choices on a stonecutter and cost lines on an enchanting table', async () => {
    const b = await attached();
    const pending = b.prompt.choice('Cut into?', [{ id: 'stairs', item: 'stone_stairs', label: 'Stairs' }, { id: 'slab', item: { id: 'stone_slab', count: 2 } }, 'wall'], { surface: 'stonecutter' });
    const r = await nextInput();
    expect(r.type === 'prompt' && r.surface).toBe('stonecutter');
    expect(r.type === 'prompt' && r.request).toMatchObject({
      kind: 'choice',
      choices: ['stairs', 'slab', 'wall'],
      options: [
        { id: 'stairs', label: 'Stairs', item: { id: 'minecraft:stone_stairs' } },
        { id: 'slab', label: 'slab', item: { id: 'minecraft:stone_slab', count: 2 } },
        { id: 'wall', label: 'wall' },
      ],
    });
    expect(() => e.mod.resolvePrompt(r.id, 'door')).toThrow(/not one of the options/);
    e.mod.resolvePrompt(r.id, 1);
    expect(await pending).toBe('slab');

    const ench = b.prompt.choice('Enchant?', [
      { id: 'eff', label: 'Efficiency I', cost: '1 Lapis Lazuli' },
      { id: 'unb', label: 'Unbreaking II', cost: '2 Lapis Lazuli' },
    ], { surface: 'enchanting' });
    const r2 = await nextInput();
    expect(r2.type === 'prompt' && r2.request.options?.map((o) => [o.id, o.label, o.text])).toEqual([
      ['eff', 'Efficiency I', '1 Lapis Lazuli'],
      ['unb', 'Unbreaking II', '2 Lapis Lazuli'],
    ]);
    e.mod.resolvePrompt(r2.id, 'unb');
    expect(await ench).toBe('unb');
    expect((await rejection(b.prompt.choice('x', ['a', 'b', 'c', 'd'], { surface: 'enchanting' }))).message).toMatch(/at most 3/);
    expect((await rejection(b.prompt.choice('x', ['a', 'a']))).message).toMatch(/unique/);
  });

  it('trades on a merchant screen and resolves the chosen offer id', async () => {
    const b = await attached();
    const pending = b.prompt.trade('Buy something?', [
      { id: 'sword', cost: { id: 'emerald', count: 12 }, result: 'diamond_sword', uses: 1, maxUses: 3 },
      { id: 'map', cost: 'emerald', cost2: 'compass', result: { id: 'filled_map', name: 'Treasure map' }, disabled: true },
    ]);
    const r = await nextInput();
    expect(r.type === 'prompt' && [r.surface, r.request.choices]).toEqual(['merchant', ['sword', 'map']]);
    expect(r.type === 'prompt' && r.request.offers).toMatchObject([
      { id: 'sword', cost: { id: 'minecraft:emerald', count: 12 }, result: { id: 'minecraft:diamond_sword' }, uses: 1, maxUses: 3 },
      { id: 'map', cost2: { id: 'minecraft:compass' }, result: { name: 'Treasure map' }, disabled: true },
    ]);
    expect(() => e.mod.resolvePrompt(r.id, 'map')).toThrow(/disabled/);
    e.mod.resolvePrompt(r.id, 'sword');
    expect(await pending).toBe('sword');

    const timed = b.prompt.trade('Quick?', [{ id: 'x', cost: 'dirt', result: 'stone', disabled: true }, { id: 'y', cost: 'dirt', result: 'sand' }]);
    e.mod.timeOut((await nextInput()).id);
    expect(await timed).toBe('y');
    expect((await rejection(b.prompt.trade('x', []))).message).toMatch(/at least one offer/);
    expect((await rejection(b.prompt.trade('x', [{ id: 'a', cost: 'dirt', result: 'stone', uses: -1 }]))).message).toMatch(/uses/);
    expect((await rejection(b.prompt.trade('x', [{ id: 'a', cost: 'dirt', result: 'stone' }], { surface: 'anvil' as never }))).message).toMatch(/merchant/);
  });

  it('asks for a crafting pattern from virtual items', async () => {
    const b = await attached();
    const pending = b.prompt.recipe('Show me the recipe', { items: ['oak_planks', 'stick'] });
    const r = await nextInput();
    expect(r.type === 'prompt' && [r.surface, r.request.options?.map((o) => o.id), r.request.choices]).toEqual(['crafting', ['minecraft:oak_planks', 'minecraft:stick'], undefined]);
    expect(() => e.mod.resolvePrompt(r.id, [['minecraft:diamond', null, null], [null, null, null], [null, null, null]])).toThrow(/not one of the crafting items/);
    e.mod.resolvePrompt(r.id, [
      ['minecraft:oak_planks', 'minecraft:oak_planks', null],
      [null, 'minecraft:stick', null],
      [null, 'minecraft:stick', null],
    ]);
    expect(await pending).toEqual([
      ['minecraft:oak_planks', 'minecraft:oak_planks', null],
      [null, 'minecraft:stick', null],
      [null, 'minecraft:stick', null],
    ]);

    const timed = b.prompt.recipe('Again', { default: [['stick', null, null], [null, null, null], [null, null, null]] });
    e.mod.timeOut((await nextInput()).id);
    expect(await timed).toEqual([['minecraft:stick', null, null], [null, null, null], [null, null, null]]);
    expect((await rejection(b.prompt.recipe('x', { default: [['a']] }))).message).toMatch(/three rows/);
  });

  it('builds prompts only from known options and names the right key for a mistyped one', async () => {
    const b = await attached();
    const asked: unknown[] = [];
    const off = e.mod.on('inputRequest', (r) => asked.push(r));
    const recipe = await rejection(b.prompt.recipe('Recipe?', { options: ['minecraft:oak_planks', 'minecraft:stick'] } as never));
    expect(recipe).toMatchObject({ code: 'invalid_message', message: expect.stringMatching(/unknown recipe prompt option "options"; use items/) });
    expect((await rejection(b.prompt.choice('x', ['a'], { options: ['a'] } as never))).message).toMatch(/"options".*second argument/);
    expect((await rejection(b.prompt.trade('x', [{ id: 'a', cost: 'dirt', result: 'stone' }], { offers: [] } as never))).message).toMatch(/"offers".*second argument/);
    expect((await rejection(b.prompt.trade('x', [{ id: 'a', price: 'dirt', result: 'stone' } as never]))).message).toMatch(/unknown trade offer field "price"; use cost/);
    expect((await rejection(b.prompt.choice('x', [{ id: 'a', name: 'A' } as never]))).message).toMatch(/choice option field "name"; use label/);
    expect((await rejection(b.prompt.text('x', { timeout: 5 } as never))).message).toMatch(/"timeout"; did you mean timeoutMs\?/);
    expect((await rejection(b.prompt.number('x', { minimum: 1 } as never))).message).toMatch(/use min/);
    expect((await rejection(b.prompt.confirm('x', { wood: 'oak' } as never))).message).toMatch(/unknown confirm prompt option "wood"; known: /);
    expect((await rejection(b.prompt.recipe('x', { items: 'stick' } as never))).message).toMatch(/list of items/);
    expect((await rejection(b.pick.block({ range: 5 } as never))).message).toMatch(/block pick option "range"; use maxDistance/);
    expect((await rejection(b.pick.block({ filter: { id: ['minecraft:chest'] } as never }))).message).toMatch(/target filter field "id"; use ids/);
    expect((await rejection(b.worldMenu({ at: [0, 64, 0], options: [{ id: 'a', label: 'A' }] } as never))).message).toMatch(/world menu option "options"; use entries/);
    expect((await rejection(b.worldMenu({ at: [0, 64, 0], entries: [{ id: 'a', name: 'A' } as never] }))).message).toMatch(/entry field "name"; use label/);
    expect((await rejection(b.form({ title: 'F', fields: [{ id: 'a', type: 'select', choices: ['x'] } as never] }))).message).toMatch(/form field property "choices"; use options/);
    expect((await rejection(b.form({ title: 'F', fields: [], submit: 'Go' } as never))).message).toMatch(/use submitLabel/);
    expect((await rejection(b.menu.confirm('Sure?', { yesLabel: 'Yes' } as never))).message).toMatch(/confirm menu option "yesLabel"; use yes/);
    expect((await rejection(b.prompt.text('x', null as never))).message).toMatch(/expected an object/);
    await sleep(30);
    expect(asked).toEqual([]);
    off();

    // Keys set to undefined count as absent, as optional properties spread from elsewhere often are.
    const pending = b.prompt.recipe('Recipe?', { items: ['stick'], surface: undefined, options: undefined } as never);
    const r = await nextInput();
    const sent = r.type === 'prompt' ? Object.entries(r.request).filter(([, v]) => v !== undefined).map(([k]) => k) : [];
    expect(sent.sort()).toEqual(['defaultValue', 'kind', 'options', 'placement', 'surface', 'text', 'timeoutMs']);
    expect(r.type === 'prompt' && r.request.options).toMatchObject([{ id: 'minecraft:stick', item: { id: 'minecraft:stick' } }]);
    e.mod.resolvePrompt(r.id, Array.from({ length: 9 }, () => null));
    await pending;
  });

  it('rejects a recipe with unsupported when the mod falls back to another surface', async () => {
    await e.restart({ surfaces: ['hud'] });
    const b = await attached();
    const pending = b.prompt.recipe('Recipe?');
    const r = await nextInput();
    expect(r.type === 'prompt' && r.surface).toBe('hud');
    e.mod.resolvePrompt(r.id, Array.from({ length: 9 }, () => null));
    expect((await rejection(pending)).code).toBe('unsupported');
    const trade = b.prompt.trade('Fallback', [{ id: 'a', cost: 'dirt', result: 'stone' }]);
    const r2 = await nextInput();
    e.mod.resolvePrompt(r2.id, 'a');
    expect(await trade).toBe('a');
  });
});
