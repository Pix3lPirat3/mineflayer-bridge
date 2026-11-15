import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { cases } from '../../../../tools/codegen/src/fixture-cases.ts';
import { encodeCase, FIXTURE_FILE, renderFixtures } from '../../../../tools/codegen/src/fixtures.ts';
import { Kinds, Messages, type KindName, type MessageName } from '../src/generated/core.ts';
import { readHeader, writeHeader } from '../src/wire/envelope.ts';
import { WireReader } from '../src/wire/reader.ts';
import { WireWriter } from '../src/wire/writer.ts';
import { packBlockPos, unpackBlockPos } from '../src/wire/helpers.ts';

const fromHex = (h: string) => new Uint8Array(Buffer.from(h, 'hex'));
const toHex = (b: Uint8Array) => Buffer.from(b).toString('hex');

describe('golden fixtures', () => {
  it('file matches the cases', () => {
    expect(readFileSync(FIXTURE_FILE, 'utf8')).toBe(renderFixtures());
  });

  const rows = readFileSync(FIXTURE_FILE, 'utf8').split('\n').filter((l) => l && !l.startsWith('#')).map((l) => l.split('\t'));
  for (const [space, name, label, hex] of rows) {
    it(`${space} ${name} ${label} re-encodes byte-identically`, () => {
      const bytes = fromHex(hex!);
      const r = new WireReader(bytes);
      const w = new WireWriter();
      if (space === 'envelope') {
        writeHeader(w, readHeader(bytes).header);
      } else if (space === 'message') {
        const codec = Messages[name as MessageName].codec;
        codec.write(w, codec.read(r) as never);
        expect(r.remaining).toBe(0);
      } else {
        const codec = Kinds[name as KindName].codec;
        codec.write(w, codec.read(r) as never);
        expect(r.remaining).toBe(0);
      }
      expect(toHex(w.toBytes())).toBe(hex);
    });
  }

  it('every case encodes deterministically', () => {
    for (const c of cases) expect(toHex(encodeCase(c))).toBe(toHex(encodeCase(c)));
  });
});

describe('forward compatibility', () => {
  it('ignores trailing unknown fields', () => {
    const w = new WireWriter();
    w.arrayHeader(2);
    w.uint(42);
    w.str('field from a newer peer');
    const r = new WireReader(w.toBytes());
    expect(Messages.ping.codec.read(r)).toEqual({ time: 42 });
    expect(r.remaining).toBe(0);
  });

  it('applies defaults for missing trailing fields', () => {
    const w = new WireWriter();
    w.arrayHeader(0);
    expect(Kinds['core:style'].codec.read(new WireReader(w.toBytes()))).toMatchObject({ color: 0xffffffff, depth: 'test', pulse: false });
  });

  it('rejects missing required fields', () => {
    const w = new WireWriter();
    w.arrayHeader(0);
    expect(() => Messages.ping.codec.read(new WireReader(w.toBytes()))).toThrow(/expected at least 1/);
  });
});

describe('wire primitives', () => {
  it('round-trips integer boundaries with smallest encodings', () => {
    const values = [0, 127, 128, 255, 256, 65535, 65536, 2 ** 32 - 1, 2 ** 32, Number.MAX_SAFE_INTEGER, -1, -32, -33, -128, -129, -32768, -32769, -(2 ** 31), -(2 ** 31) - 1, Number.MIN_SAFE_INTEGER];
    const sizes = [1, 1, 2, 2, 3, 3, 5, 5, 9, 9, 1, 1, 2, 2, 3, 3, 5, 5, 9, 9];
    values.forEach((v, i) => {
      const w = new WireWriter();
      w.int(v);
      expect(w.length).toBe(sizes[i]);
      expect(new WireReader(w.toBytes()).int()).toBe(v);
    });
  });

  it('chooses the smallest string header for multi-byte text', () => {
    for (const s of ['', 'a', 'é'.repeat(15), 'é'.repeat(16), '✓'.repeat(90), 'z'.repeat(70000)]) {
      const w = new WireWriter(8);
      w.str(s);
      const len = Buffer.byteLength(s);
      const header = len < 32 ? 1 : len < 256 ? 2 : len < 65536 ? 3 : 5;
      expect(w.length).toBe(header + len);
      expect(new WireReader(w.toBytes()).str()).toBe(s);
    }
  });

  it('rejects truncated input', () => {
    expect(() => new WireReader(fromHex('a5616263')).str()).toThrow(/unexpected end/);
  });

  it('packs block positions in the vanilla layout', () => {
    for (const p of [{ x: 0, y: 0, z: 0 }, { x: -1, y: -64, z: 1 }, { x: 33554431, y: 2047, z: -33554432 }]) {
      expect(unpackBlockPos(packBlockPos(p))).toEqual(p);
    }
    expect(packBlockPos({ x: 1, y: 2, z: 3 })).toBe((1n << 38n) | (3n << 12n) | 2n);
  });
});
