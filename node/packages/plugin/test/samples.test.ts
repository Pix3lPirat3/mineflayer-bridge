import { describe, expect, it } from 'vitest';
import type { MockInbound } from '@mineflayer-bridge/mock-mod';
import type { MessagePayloads } from '@mineflayer-bridge/protocol';
import { defineExtension, LOCAL_DEFAULT_PERMISSIONS, type ClientSampleData, type SamplesApi } from '../src/index.ts';
import { ALICE, fixture, OVERWORLD, sleep, until, type Env } from './helpers.ts';

// Local devices hold every permission; this player withheld the held item.
const GRANTED = LOCAL_DEFAULT_PERMISSIONS.filter((p) => p !== 'client.sample.inventory');
const SAMPLE = {
  position: { x: 10.5, y: 64, z: -3.25 },
  yaw: 90,
  pitch: -10,
  target: { type: 'block', block: { x: 11, y: 64, z: -3 }, face: 'up' },
  heldItem: { id: 'minecraft:diamond_pickaxe', count: 1 },
};

let e: Env;
const { attached } = fixture((f) => (e = f.e), { permissions: () => GRANTED });

/** Collects every `sample.request` the mock receives. */
function requests(): MessagePayloads['sample.request'][] {
  const seen: MessagePayloads['sample.request'][] = [];
  e.mod.on('message', (m: MockInbound) => {
    if (m.type === 'sample.request') seen.push(m.payload as MessagePayloads['sample.request']);
  });
  return seen;
}

describe('client samples', () => {
  it('sends the request, receives only permitted fields and exposes the latest sample', async () => {
    const b = await attached();
    const seen = requests();
    expect(b.samples.latest).toBeUndefined();
    b.samples.request({ rateHz: 10, position: true, target: true, heldItem: true });
    await until(() => e.mod.sampleRequest(ALICE), 2000, 'request');
    expect(e.mod.sampleRequest(ALICE)).toEqual({ rateHz: 10, position: true, target: true, heldItem: true });
    expect(seen).toHaveLength(1);
    expect(b.samples.current).toEqual({ rateHz: 10, position: true, target: true, heldItem: true });

    const got: ClientSampleData[] = [];
    b.samples.on('sample', (s) => got.push(s));
    expect(e.mod.setPlayerSample(SAMPLE)).toBe(1);
    await until(() => got.length === 1);
    // The held item needs client.sample.inventory, which this device was not granted.
    expect(got[0]).toEqual({ position: SAMPLE.position, yaw: 90, pitch: -10, target: { type: 'block', block: { x: 11, y: 64, z: -3 }, face: 'up' } });
    expect(b.samples.latest).toBe(got[0]);
    expect(Object.isFrozen(got[0])).toBe(true);
  });

  it('withdraws with stop, drops samples while detached and forgets the latest on detach', async () => {
    const b = await attached();
    const seen = requests();
    b.samples.request({ position: true });
    await until(() => e.mod.sampleRequest(ALICE));
    expect(e.mod.sampleRequest(ALICE)?.rateHz).toBe(4);
    e.mod.setPlayerSample(SAMPLE);
    await until(() => b.samples.latest);
    expect(b.samples.latest).toEqual({ position: SAMPLE.position, yaw: 90, pitch: -10 });

    e.mod.setAttached(ALICE, false);
    await until(() => !b.state.attached);
    expect(b.samples.latest).toBeUndefined();
    expect(e.mod.setPlayerSample(SAMPLE)).toBe(0);
    e.mod.setAttached(ALICE, null);
    await until(() => b.state.attached);

    b.samples.stop();
    await until(() => seen.length === 2);
    expect(seen[1]).toEqual({ rateHz: 4, position: false, target: false, heldItem: false });
    expect(e.mod.sampleRequest(ALICE)).toBeUndefined();
    expect(b.samples.current).toBeUndefined();
    expect(e.mod.setPlayerSample(SAMPLE)).toBe(0);
  });

  it('merges scope requests, withdraws an extension request on unload and validates the rate', async () => {
    let extSamples: SamplesApi | undefined;
    const got: ClientSampleData[] = [];
    const ext = defineExtension({
      id: 'test:watcher',
      version: '1.0.0',
      setup(ctx) {
        extSamples = ctx.samples;
        ctx.samples.on('sample', (s) => got.push(s));
        ctx.samples.request({ rateHz: 12, target: true });
      },
    });
    const b = await attached();
    b.samples.request({ rateHz: 2, position: true });
    await b.use(ext);
    await until(() => e.mod.sampleRequest(ALICE)?.target === true);
    expect(e.mod.sampleRequest(ALICE)).toEqual({ rateHz: 12, position: true, target: true, heldItem: false });
    expect(extSamples?.current).toEqual({ rateHz: 12, position: false, target: true, heldItem: false });

    e.mod.setPlayerSample(SAMPLE);
    await until(() => got.length === 1);
    await b.unload('test:watcher');
    await until(() => e.mod.sampleRequest(ALICE)?.target === false);
    expect(e.mod.sampleRequest(ALICE)).toEqual({ rateHz: 2, position: true, target: false, heldItem: false });
    await sleep(600);
    e.mod.setPlayerSample(SAMPLE);
    await until(() => b.samples.latest !== undefined && b.samples.latest.target === undefined);
    expect(got).toHaveLength(1);

    expect(() => b.samples.request({ rateHz: 0, position: true })).toThrow(expect.objectContaining({ code: 'invalid_message' }));
    expect(() => b.samples.request({ rateHz: 21, position: true })).toThrow(/\(0, 20\]/);
    expect(() => b.samples.request({ rateHz: Number.NaN, position: true })).toThrow(/rateHz/);
  });

  it('caps the rate at the player limit, keeping the newest sample', async () => {
    const b = await attached();
    e.mod.setSampleRateLimit(5);
    b.samples.request({ rateHz: 20, position: true });
    await until(() => e.mod.sampleRequest(ALICE));
    const got: ClientSampleData[] = [];
    b.samples.on('sample', (s) => got.push(s));
    for (let i = 0; i < 10; i++) e.mod.setPlayerSample({ position: { x: i, y: 64, z: 0 } });
    await until(() => got.length === 2, 2000, 'trailing sample');
    expect(got.map((s) => s.position?.x)).toEqual([0, 9]);
    await sleep(250);
    expect(got).toHaveLength(2);
    expect(() => e.mod.setSampleRateLimit(30)).toThrow(/sample rate/);
  });

  it('sends the request again after the mod restarts', async () => {
    const b = await attached();
    b.samples.request({ rateHz: 8, target: true });
    await until(() => e.mod.sampleRequest(ALICE));
    await e.restart({ permissions: () => GRANTED });
    e.mod.setPlayerContext(OVERWORLD);
    await until(() => e.mod.sampleRequest(ALICE), 5000, 'replayed request');
    expect(e.mod.sampleRequest(ALICE)).toEqual({ rateHz: 8, position: false, target: true, heldItem: false });
    await until(() => b.state.attached);
    e.mod.setPlayerSample(SAMPLE);
    await until(() => b.samples.latest?.target !== undefined);
  });
});
