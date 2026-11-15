import { WireReader, WireWriter } from '@mineflayer-bridge/protocol';
import { describe, expect, it } from 'vitest';
import { defineExtension, plainMessage, type BotBridge, type MessageType } from '../src/index.ts';
import { ALICE, FakeBot, fixture, load, OVERWORLD, until, type Env } from './helpers.ts';

let e: Env;
const { hub } = fixture((f) => (e = f.e));

const hex = (b: Uint8Array): string => Buffer.from(b).toString('hex');
const bytes = (h: string): Uint8Array => new Uint8Array(Buffer.from(h, 'hex'));

function encode(type: MessageType<unknown>, v: unknown): string {
  const w = new WireWriter(64);
  type.codec.write(w, v);
  return hex(w.toBytes());
}

const decode = (type: MessageType<unknown>, h: string): unknown => type.codec.read(new WireReader(bytes(h)));

// The same vectors are checked by the mod's PlainMessagesTest.
const VECTORS: [string, unknown, string, unknown][] = [
  ['map', { id: 7, pos: [1.5, -2, 0], ok: true, tag: null, name: 'hé' }, '85a2696407a3706f7393cb3ff8000000000000fe00a26f6bc3a3746167c0a46e616d65a368c3a9', undefined],
  ['ints', [0, -1, 127, 128, -33, 300, 70000, -70000, 2 ** 40, Number.MAX_SAFE_INTEGER], '9a00ff7fcc80d0dfcd012cce00011170d2fffeee90cf0000010000000000cf001fffffffffffff', undefined],
  ['floats', [2.0, 0.1, -0.5, 1e300], '9402cb3fb999999999999acbbfe0000000000000cb7e37e43c8800759c', undefined],
  ['empty', [[], {}, ''], '939080a0', undefined],
  ['undefined fields', { a: 1, b: undefined }, '81a16101', { a: 1 }],
];

describe('plainMessage', () => {
  const note = plainMessage('acme:note', { from: 'both' });

  it('builds a reliable type by default and checks its options', () => {
    expect(note).toMatchObject({ id: 0, name: 'acme:note', from: 'both', delivery: 'reliable' });
    expect(plainMessage('acme:pos', { from: 'node', delivery: 'latest' }).delivery).toBe('latest');
    expect(plainMessage('acme:pos', { from: 'node' }).codec).toBe(note.codec);
    expect(() => plainMessage('note', { from: 'both' })).toThrow(/namespace:name/);
    expect(() => plainMessage('acme:x', { from: 'server' as never })).toThrow(/from/);
    expect(() => plainMessage('acme:x', { from: 'mod', delivery: 'control' as never })).toThrow(/delivery/);
  });

  it.each(VECTORS)('encodes the %s vector like the mod', (_, value, h, back) => {
    expect(encode(note, value)).toBe(h);
    expect(decode(note, h)).toEqual(back ?? value);
  });
});

describe('a third-party extension with plain messages', () => {
  const NOTE = plainMessage<{ text: string; n: number }>('acme:note', { from: 'both' });
  const PUSH = plainMessage<string[]>('acme:push', { from: 'mod' });
  const notes = defineExtension({
    id: 'acme:notes',
    version: '1.0.0',
    messages: [NOTE, PUSH],
    setup(ctx) {
      const got: unknown[] = [];
      ctx.message(NOTE).on((v) => got.push(v));
      ctx.message(PUSH).on((v) => got.push(v));
      return { got, send: (v: { text: string; n: number }) => ctx.message(NOTE).send(v) };
    },
  });
  type NotesApi = { got: unknown[]; send(v: { text: string; n: number }): void };

  async function start(): Promise<{ b: BotBridge; api: NotesApi }> {
    const b = load(hub().plugin({ extensions: [notes] }), new FakeBot('Alice'));
    e.mod.setPlayerContext(OVERWORLD);
    await until(() => b.state.attached && b.has('acme:notes'));
    return { b, api: b.get<NotesApi>('acme:notes')! };
  }

  it('round-trips both ways through the mod', async () => {
    const { api } = await start();
    api.send({ text: 'hi', n: 1.5 });
    const sent = await until(() => e.mod.extensionMessages(ALICE).find((m) => m.type === 'acme:note'));
    expect(hex(sent.payload)).toBe('82a474657874a26869a16ecb3ff8000000000000');

    expect(e.mod.sendExtension(ALICE, 'acme:note', bytes('82a474657874a3796f75a16e03'))).toBe(true);
    expect(e.mod.sendExtension(ALICE, 'acme:push', bytes('92a161a162'))).toBe(true);
    await until(() => api.got.length === 2);
    expect(api.got).toEqual([{ text: 'you', n: 3 }, ['a', 'b']]);
  });
});
