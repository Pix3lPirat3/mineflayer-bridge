import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { beforeEach, describe, expect, it } from 'vitest';
import type { MockInbound, MockModOptions } from '@mineflayer-bridge/mock-mod';
import { HeatmapBlobCodec, WireWriter, type MessagePayloads } from '@mineflayer-bridge/protocol';
import { BridgeError, defineExtension, type BotBridge, type Volume } from '../src/index.ts';
import type { DeclaredStore } from '../src/store.ts';
import { ALICE, ALICE_PREFIX, FakeBot, fixture, load, sleep, until, type Env } from './helpers.ts';

const MiB = 1024 * 1024;

let e: Env;
const { hub } = fixture((f) => (e = f.e));

beforeEach(() => {
  record();
});
let inbound: MockInbound[];

function record(): void {
  inbound = [];
  e.mod.on('message', (m) => inbound.push(m));
}

async function restart(options: MockModOptions): Promise<void> {
  await e.restart(options);
  record();
}

async function connected(name = 'Alice'): Promise<BotBridge> {
  const b = load(hub().plugin(), new FakeBot(name));
  await until(() => b.state.connected);
  return b;
}

function sent(type: string, owner = ALICE): MockInbound[] {
  return inbound.filter((m) => m.type === type && m.session?.owner === owner && !m.duplicate);
}

function blobsOf(b: BotBridge): DeclaredStore['blobs'] {
  return (b as unknown as { store: DeclaredStore }).store.blobs;
}

/** Deterministic xorshift, so large volumes do not compress away and tests stay reproducible. */
function rng(seed: number): () => number {
  let x = seed;
  return () => {
    x ^= x << 13;
    x ^= x >>> 17;
    x ^= x << 5;
    return (x >>> 0) / 2 ** 32;
  };
}

function noisyVolume(b: BotBridge, sections: number, seed = 1): Volume {
  const v = b.volume();
  const r = rng(seed);
  const states = Array.from({ length: 64 }, (_, i) => `minecraft:wool_${i}`);
  for (let s = 0; s < sections; s++) {
    for (let i = 0; i < 4096; i++) v.set([s * 16 + (i & 15), (i >> 8) & 15, (i >> 4) & 15], states[Math.floor(r() * states.length)]!);
  }
  return v;
}

describe('volume builder', () => {
  it('sets, fills, deletes, counts and bounds blocks in sections', async () => {
    const b = await connected();
    const v = b.volume({ palette: ['minecraft:stone', 'minecraft:dirt'] });
    v.set([0, 64, 0], 'minecraft:glass').set({ x: -1, y: 63, z: 17 }, 1);
    v.fill([2, 2, 2], [0, 0, 0], 0);
    v.setMany([[5, 5, 5], [6, 5, 5]], ['minecraft:oak_log[axis=x]', 'minecraft:oak_log[axis=z]']);
    v.setMany(new Int32Array([7, 5, 5, 8, 5, 5]), 'minecraft:stone');
    expect(v.count).toBe(2 + 27 + 4);
    expect(v.get([-1, 63, 17])).toBe('minecraft:dirt');
    expect(v.get([1, 1, 1])).toBe('minecraft:stone');
    expect(v.get([9, 9, 9])).toBeUndefined();
    expect(v.bounds()).toEqual({ min: { x: -1, y: 0, z: 0 }, max: { x: 8, y: 64, z: 17 } });
    expect(v.delete([0, 64, 0])).toBe(true);
    expect(v.delete([0, 64, 0])).toBe(false);
    expect(v.count).toBe(32);
    expect(() => v.set([0.5, 0, 0], 'minecraft:stone')).toThrow(BridgeError);
    expect(() => v.set([0, 0, 0], 7)).toThrow('not in the palette');
    expect(() => b.volume().set([0, 0, 0], 1)).toThrow('numeric block states need a palette');
    expect(() => v.fill([0, 0, 0], [4096, 4096, 1], 'minecraft:stone')).toThrow(expect.objectContaining({ code: 'quota_exceeded' }));
    v.clear();
    expect(v.count).toBe(0);
    expect(v.bounds()).toBeUndefined();
  });

  it('encodes canonically, so equal contents give the same blob whatever the write order', async () => {
    const b = await connected();
    const layer = b.layer('plan');
    const a = b.volume().set([0, 0, 0], 'minecraft:stone').set([40, 0, 0], 'minecraft:dirt');
    const c = b.volume().set([40, 0, 0], 'minecraft:dirt').set([3, 3, 3], 'minecraft:glass').set([0, 0, 0], 'minecraft:stone');
    c.delete([3, 3, 3]);
    expect(layer.ghostBlocks('a', a).blobHash).toBe(layer.ghostBlocks('c', c).blobHash);
  });
});

describe('ghost blocks', () => {
  it('offers the blob before the object and the mod decodes the volume', async () => {
    const b = await connected();
    const vol = b.volume().set([1, 2, 3], 'minecraft:stone').set([17, 2, 3], 'minecraft:oak_planks');
    const stored = e.mod.waitFor('blobStored');
    const g = b.layer('plan').ghostBlocks('house', vol, { origin: [100, 64, -20], alpha: 0.3, diffAgainstWorld: true, style: { color: '#00ff00' } });
    const [info] = await stored;
    expect(info.hash).toBe(g.blobHash);
    const key = `${ALICE_PREFIX}/plan/house`;
    await until(() => e.mod.ghost(key));
    const ghost = e.mod.ghost(key)!;
    expect(ghost.count).toBe(2);
    expect(ghost.get({ x: 17, y: 2, z: 3 })).toBe('minecraft:oak_planks');
    expect(ghost.sections.map((s) => [s.x, s.y, s.z])).toEqual([[0, 0, 0], [1, 0, 0]]);
    expect(ghost.sections[0]!.palette).toEqual(['', 'minecraft:stone']);
    expect(ghost.data).toMatchObject({ origin: { x: 100, y: 64, z: -20 }, diffAgainstWorld: true, hideMatching: true, style: '~inline/plan/house' });
    expect(ghost.data.alpha).toBeCloseTo(0.3);
    expect(ghost.data.size).toBe(e.mod.blob(g.blobHash!)!.length);
    const hash = createHash('sha256').update(e.mod.blob(g.blobHash!)!).digest('hex');
    expect(hash).toBe(g.blobHash);
    // The reference arrives with its stream already offered.
    const offerAt = inbound.findIndex((m) => m.type === 'stream.offer');
    const upsertAt = inbound.findIndex((m) => m.type === 'obj.upsert' && (m.payload as MessagePayloads['obj.upsert']).writes.some((w) => w.value.key === key));
    expect(offerAt).toBeGreaterThanOrEqual(0);
    expect(offerAt).toBeLessThan(upsertAt);
    // Two sections of 8 KiB indices compress well.
    expect(info.codec).toBe('zstd');
  });

  it('sends a large volume in chunks of at most 256 KiB, interleaved with other traffic', async () => {
    const b = await connected();
    const vol = noisyVolume(b, 300);
    const stored = e.mod.waitFor('blobStored', undefined, 10_000);
    const layer = b.layer('plan');
    const g = layer.ghostBlocks('big', vol);
    for (let i = 0; i < 20; i++) layer.box(`b${i}`, [i, 0, 0], 1);
    await stored;
    const chunks = sent('stream.chunk').map((m) => m.payload as MessagePayloads['stream.chunk']);
    expect(chunks.length).toBeGreaterThan(1);
    expect(Math.max(...chunks.map((c) => c.data.length))).toBeLessThanOrEqual(256 * 1024);
    let offset = 0;
    for (const c of chunks) {
      expect(c.offset).toBe(offset);
      offset += c.data.length;
    }
    expect(sent('stream.end')).toHaveLength(1);
    await until(() => e.mod.ghost(`${ALICE_PREFIX}/plan/big`));
    expect(e.mod.ghost(`${ALICE_PREFIX}/plan/big`)!.count).toBe(300 * 4096);
    expect(e.mod.blobs().map((x) => x.hash)).toContain(g.blobHash);
    // The boxes did not wait for the whole transfer.
    const firstChunk = inbound.findIndex((m) => m.type === 'stream.chunk');
    const lastChunk = inbound.findLastIndex((m) => m.type === 'stream.chunk');
    const boxes = inbound.findIndex((m) => m.type === 'obj.upsert' && (m.payload as MessagePayloads['obj.upsert']).writes.some((w) => w.value.key.endsWith('/plan/b0')));
    expect(boxes).toBeLessThan(lastChunk);
    expect(firstChunk).toBeGreaterThanOrEqual(0);
  });

  it('honours a small window: no chunk exceeds the credit and the transfer still completes', async () => {
    await restart({ streamWindow: 1000 });
    const b = await connected();
    const g = b.layer('plan').ghostBlocks('w', noisyVolume(b, 2));
    await e.mod.waitFor('blobStored', (x) => x.hash === g.blobHash, 10_000);
    const chunks = sent('stream.chunk').map((m) => (m.payload as MessagePayloads['stream.chunk']).data.length);
    expect(Math.max(...chunks)).toBeLessThanOrEqual(1000);
    expect(chunks.reduce((a, n) => a + n, 0)).toBeGreaterThan(2000);
  });

  it('does not send a blob the mod already holds, across sessions', async () => {
    const alice = await connected('Alice');
    const g = alice.layer('plan').ghostBlocks('house', noisyVolume(alice, 3));
    await e.mod.waitFor('blobStored');
    const have = e.mod.waitFor('blobHave');
    const bob = await connected('Bob');
    const h = bob.layer('plan').ghostBlocks('house', noisyVolume(bob, 3));
    expect(h.blobHash).toBe(g.blobHash);
    const [{ session }] = await have;
    expect(session.owner).toBe('bot:bob@play.example.net:25565');
    await until(() => e.mod.ghost('bot:bob@play.example.net:25565/user:default/plan/house'));
    expect(sent('stream.chunk', 'bot:bob@play.example.net:25565')).toHaveLength(0);
    expect(e.mod.blobs()).toHaveLength(1);
  });

  it('re-offers referenced blobs after a fresh session; the mod answers have from its cache', async () => {
    const b = await connected();
    const g = b.layer('plan').ghostBlocks('house', noisyVolume(b, 2));
    await e.mod.waitFor('blobStored');
    const have = e.mod.waitFor('blobHave', (x) => x.hash === g.blobHash);
    e.mod.expireSession(ALICE);
    await have;
    await until(() => e.mod.ghost(`${ALICE_PREFIX}/plan/house`));
    expect(sent('stream.chunk').length).toBeGreaterThan(0);
    expect(sent('stream.end')).toHaveLength(1);
  });

  it('re-sends blobs to a restarted mod, and a persisted cache answers have', async () => {
    const dir = join(e.dir, 'blobs');
    await restart({ blobDir: dir });
    const b = await connected();
    const g = b.layer('plan').ghostBlocks('house', noisyVolume(b, 2));
    await e.mod.waitFor('blobStored');
    await restart({ blobDir: dir });
    expect(e.mod.blobs().map((x) => x.hash)).toEqual([g.blobHash]);
    await e.mod.waitFor('blobHave', (x) => x.hash === g.blobHash);
    await until(() => e.mod.ghost(`${ALICE_PREFIX}/plan/house`));
    await restart({});
    await e.mod.waitFor('blobStored', (x) => x.hash === g.blobHash);
    expect(sent('stream.end')).toHaveLength(1);
  });

  it('finishes a transfer interrupted by a dropped connection after resume', async () => {
    await restart({ streamWindow: 8192 });
    const b = await connected();
    let chunks = 0;
    let resumedAt = -1;
    e.mod.on('message', (m) => {
      if (m.type !== 'stream.chunk' || m.duplicate) return;
      if (++chunks === 3) e.mod.disconnect(ALICE);
    });
    e.mod.on('sessionResumed', () => {
      resumedAt = chunks;
    });
    const vol = noisyVolume(b, 20);
    const stored = e.mod.waitFor('blobStored', undefined, 10_000);
    const g = b.layer('plan').ghostBlocks('house', vol);
    const [info] = await stored;
    expect(info.hash).toBe(g.blobHash);
    expect(resumedAt).toBe(3);
    expect(chunks).toBeGreaterThan(3);
    await until(() => e.mod.ghost(`${ALICE_PREFIX}/plan/house`)?.count === 20 * 4096);
  });

  it('sends nothing for an update with unchanged content, and a new blob only when it changed', async () => {
    const b = await connected();
    const vol = b.volume().fill([0, 0, 0], [3, 3, 3], 'minecraft:stone');
    const g = b.layer('plan').ghostBlocks('house', vol, { alpha: 0.5 });
    await e.mod.waitFor('blobStored');
    const first = g.blobHash;
    const writes = (): number => sent('obj.upsert').length;
    await sleep(30);
    const before = writes();
    g.update();
    b.layer('plan').ghostBlocks('house', vol, { alpha: 0.5 });
    g.update(b.volume().fill([3, 3, 3], [0, 0, 0], 'minecraft:stone'));
    await sleep(60);
    expect(writes()).toBe(before);
    expect(sent('stream.offer')).toHaveLength(1);
    g.update(undefined, { alpha: 0.9 });
    await until(() => (e.mod.ghost(`${ALICE_PREFIX}/plan/house`)?.data.alpha ?? 0) > 0.8);
    expect(g.blobHash).toBe(first);
    expect(sent('stream.offer')).toHaveLength(1);
    const other = b.volume().fill([0, 0, 0], [3, 3, 3], 'minecraft:stone');
    g.update(other);
    other.set([10, 10, 10], 'minecraft:gold_block');
    g.update();
    await e.mod.waitFor('blobStored', (x) => x.hash === g.blobHash);
    expect(g.blobHash).not.toBe(first);
    expect(e.mod.ghost(`${ALICE_PREFIX}/plan/house`)!.count).toBe(65);
    expect(blobsOf(b).size).toBe(1);
  });

  it('releases blobs when shapes go, cancelling an unfinished transfer', async () => {
    await restart({ streamWindow: 0 });
    const ext = defineExtension({
      id: 'test:planner',
      version: '1.0.0',
      setup(ctx) {
        ctx.layer('plan').ghostBlocks('house', ctx.volume().fill([0, 0, 0], [7, 7, 7], 'minecraft:stone'));
      },
    });
    const b = await connected();
    const g = b.layer('mine').ghostBlocks('one', b.volume().set([0, 0, 0], 'minecraft:stone'));
    await b.use(ext);
    await until(() => sent('stream.offer').length === 2);
    expect(blobsOf(b).size).toBe(2);
    const cancelled = e.mod.waitFor('streamRejected', (x) => x.by === 'plugin');
    await b.unload('test:planner');
    const [r] = await cancelled;
    expect(r.reason).toBe('cancelled');
    expect(blobsOf(b).size).toBe(1);
    g.remove();
    expect(g.alive).toBe(false);
    expect(blobsOf(b).size).toBe(0);
    b.layer('mine').ghostBlocks('two', b.volume().set([0, 0, 0], 'minecraft:dirt'));
    b.layer('mine').box('two', [0, 0, 0], 1);
    expect(blobsOf(b).size).toBe(0);
    await until(() => e.mod.objects(ALICE_PREFIX).length > 0 && e.mod.object(`${ALICE_PREFIX}/mine/one`) === undefined);
  });

  it('enforces the blob size and in-flight limits locally with quota_exceeded', async () => {
    await restart({ streamWindow: 0, quotas: { blobBytes: 64 * 1024 }, budget: { messagesPerSecond: 2000, bytesPerSecond: 8 * MiB, streamBytesInFlight: 100 * 1024 } });
    const b = await connected();
    const layer = b.layer('plan');
    const tooBig = (): unknown => layer.ghostBlocks('big', noisyVolume(b, 9));
    expect(tooBig).toThrow(expect.objectContaining({ code: 'quota_exceeded', retryable: false }));
    expect(layer.has('big')).toBe(false);
    layer.ghostBlocks('a', noisyVolume(b, 6, 1));
    let err: unknown;
    try {
      layer.ghostBlocks('b', noisyVolume(b, 6, 2));
    } catch (x) {
      err = x;
    }
    expect(err).toBeInstanceOf(BridgeError);
    expect(err).toMatchObject({ code: 'quota_exceeded', retryable: true });
    expect(layer.has('b')).toBe(false);
    // Replacing the pending blob frees its share.
    expect(() => layer.ghostBlocks('a', noisyVolume(b, 6, 3))).not.toThrow();
  });
});

describe('heatmaps and highlights', () => {
  it('sends heatmap cells and values as a blob with the options', async () => {
    const b = await connected();
    const cells = [[0, 64, 0], [1, 64, 0], [2, 65, -3]] as const;
    const h = b.layer('heat').heatmap('danger', cells, [0.1, 0.5, 2], { min: 0, max: 2, colormap: 'magma', cellSize: 0.5, depth: 'xray' });
    const key = `${ALICE_PREFIX}/heat/danger`;
    await until(() => e.mod.heatmap(key));
    const m = e.mod.heatmap(key)!;
    expect([...m.cells]).toEqual([0, 64, 0, 1, 64, 0, 2, 65, -3]);
    expect([...m.values].map((v) => Math.round(v * 10) / 10)).toEqual([0.1, 0.5, 2]);
    expect(m.data).toMatchObject({ min: 0, max: 2, colormap: 'magma', cellSize: 0.5, depth: 'xray' });
    const w = new WireWriter();
    HeatmapBlobCodec.write(w, { cells: new Int32Array([0, 64, 0, 1, 64, 0, 2, 65, -3]), values: new Float32Array([0.1, 0.5, 2]) });
    expect(h.blobHash).toBe(createHash('sha256').update(w.toBytes()).digest('hex'));
    expect(() => b.layer('heat').heatmap('bad', cells, [1, 2])).toThrow('3 cells but 2 values');
    expect(() => b.layer('heat').heatmap('bad', cells, [1, 2, 3], { colormap: 'nope' as 'grey' })).toThrow('unknown colormap');
    h.update(new Int32Array([5, 5, 5]), new Float32Array([1]));
    await until(() => e.mod.heatmap(key)?.cells.length === 3);
  });

  it('keeps up to 4096 highlighted blocks inline and moves larger sets to a blob', async () => {
    const b = await connected();
    const layer = b.layer('hl');
    const small = Array.from({ length: 4096 }, (_, i) => [i % 64, 64, Math.floor(i / 64)] as const);
    const hs = layer.blocks('small', small, { mode: 'faces', style: 'core:default' });
    expect(hs.blobHash).toBeUndefined();
    const big = new Int32Array(5000 * 3).map((_, i) => (i % 3 === 1 ? 70 : Math.floor(i / 3)));
    const hb = layer.blocks('big', big);
    expect(hb.blobHash).toBeDefined();
    await until(() => e.mod.highlights(`${ALICE_PREFIX}/hl/big`));
    const s = await until(() => e.mod.highlights(`${ALICE_PREFIX}/hl/small`));
    expect(s.fromBlob).toBe(false);
    expect(s.positions.length).toBe(4096 * 3);
    expect(s.data).toMatchObject({ mode: 'faces', style: 'core:default' });
    const l = e.mod.highlights(`${ALICE_PREFIX}/hl/big`)!;
    expect(l.fromBlob).toBe(true);
    expect([...l.positions]).toEqual([...big]);
    expect(l.data.size).toBe(e.mod.blob(hb.blobHash!)!.length);
    hb.update(small);
    expect(hb.blobHash).toBeUndefined();
    await until(() => e.mod.highlights(`${ALICE_PREFIX}/hl/big`)?.fromBlob === false);
    expect(blobsOf(b).size).toBe(0);
    expect(() => layer.blocks('bad', [[0.5, 0, 0]])).toThrow('integer block coordinates');
    expect(() => layer.blocks('bad', [[0, 0, 0]], { mode: 'glow' as 'faces' })).toThrow('outline, faces or both');
  });
});
