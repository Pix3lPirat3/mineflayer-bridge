import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { MessagesById, readHeader } from '@mineflayer-bridge/protocol';
import { encodeRecord, readRecording, RECORD_HEADER_BYTES, recorder, RECORDING_MAGIC, type RecorderApi } from '../src/index.ts';
import { FakeBot, fixture, load, OVERWORLD, sleep, until, type Env } from './helpers.ts';

let e: Env;
const { hub } = fixture((f) => (e = f.e));

const typeName = (bytes: Uint8Array): string => MessagesById.get(readHeader(bytes).header.type)?.name ?? '?';

describe('.mfbrec format', () => {
  it('encodes records as u8 direction, u64 BE ms, u32 BE length and the bytes', () => {
    const r = encodeRecord({ direction: 'out', atMs: 0x0102030405, bytes: Uint8Array.of(0xaa, 0xbb) });
    expect(Array.from(r)).toEqual([1, 0, 0, 0, 0x01, 0x02, 0x03, 0x04, 0x05, 0, 0, 0, 2, 0xaa, 0xbb]);
    expect(RECORD_HEADER_BYTES).toBe(13);
    expect(new TextDecoder().decode(RECORDING_MAGIC)).toBe('MFBREC1\n');
    const file = new Uint8Array([...RECORDING_MAGIC, ...r, ...encodeRecord({ direction: 'in', atMs: 7, bytes: new Uint8Array(0) })]);
    expect(readRecording(file)).toEqual([
      { direction: 'out', atMs: 0x0102030405, bytes: Uint8Array.of(0xaa, 0xbb) },
      { direction: 'in', atMs: 7, bytes: new Uint8Array(0) },
    ]);
  });

  it('rejects bad magic, unknown directions and truncation', () => {
    expect(() => readRecording(new TextEncoder().encode('MFBREC2\n'))).toThrow(/bad magic/);
    const r = encodeRecord({ direction: 'in', atMs: 1, bytes: Uint8Array.of(1, 2, 3) });
    expect(() => readRecording(new Uint8Array([...RECORDING_MAGIC, ...r.slice(0, 5)]))).toThrow(/record header/);
    expect(() => readRecording(new Uint8Array([...RECORDING_MAGIC, ...r.slice(0, -1)]))).toThrow(/in a frame/);
    const bad = r.slice();
    bad[0] = 9;
    expect(() => readRecording(new Uint8Array([...RECORDING_MAGIC, ...bad]))).toThrow(expect.objectContaining({ code: 'invalid_message' }));
  });
});

describe('bridge:recorder', () => {
  it('records the frames of the connection in both directions', async () => {
    const path = join(e.dir, 'rec', 'session.mfbrec');
    const before = Date.now();
    const b = load(hub().plugin({ extensions: [recorder({ path })] }), new FakeBot('Alice'));
    await b.ready;
    e.mod.setPlayerContext(OVERWORLD);
    await until(() => b.state.attached);
    b.notify('hello from the recorder');
    await until(() => e.mod.notifies.length === 1);
    await sleep(30);
    const api = b.get<RecorderApi>('bridge:recorder')!;
    expect(api.recording).toBe(true);
    await api.stop();
    expect(api.recording).toBe(false);
    const data = new Uint8Array(await readFile(path));
    expect(data.length).toBe(api.bytes);
    const frames = readRecording(data);
    expect(frames).toHaveLength(api.frames);
    const outTypes = frames.filter((f) => f.direction === 'out').map((f) => typeName(f.bytes));
    const inTypes = frames.filter((f) => f.direction === 'in').map((f) => typeName(f.bytes));
    expect(outTypes).toEqual(expect.arrayContaining(['session.open', 'notify']));
    expect(inTypes).toEqual(expect.arrayContaining(['welcome', 'session.opened']));
    for (const f of frames) {
      expect(f.atMs).toBeGreaterThanOrEqual(before);
      expect(f.atMs).toBeLessThanOrEqual(Date.now());
    }
    const count = api.frames;
    b.notify('not recorded');
    await sleep(30);
    expect(api.frames).toBe(count);
  });

  it('stops at maxBytes and on unload', async () => {
    const path = join(e.dir, 'small.mfbrec');
    const b = load(hub().plugin({ extensions: [recorder({ path, maxBytes: 64 })] }), new FakeBot('Alice'));
    await b.ready;
    const api = b.get<RecorderApi>('bridge:recorder')!;
    await until(() => !api.recording);
    await api.stop();
    expect(api.bytes).toBeLessThanOrEqual(64);
    expect(readRecording(new Uint8Array(await readFile(path)))).toHaveLength(api.frames);

    // A recorder that stopped by itself stays loaded until it is unloaded.
    expect(b.has('bridge:recorder')).toBe(true);
    await b.unload('bridge:recorder');
    const path2 = join(e.dir, 'unload.mfbrec');
    await b.use(recorder({ path: path2 }));
    const api2 = b.get<RecorderApi>('bridge:recorder')!;
    b.notify('one');
    await until(() => api2.frames > 0);
    await b.unload('bridge:recorder');
    expect(api2.recording).toBe(false);
    await api2.stop();
    expect(readRecording(new Uint8Array(await readFile(path2))).length).toBe(api2.frames);
  });

  it('requires a path', async () => {
    const b = load(hub().plugin(), new FakeBot('Alice'));
    await expect(b.use(recorder())).rejects.toMatchObject({ code: 'invalid_message', message: expect.stringContaining('path: required') });
  });
});
