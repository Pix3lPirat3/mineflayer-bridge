import { describe, expect, it } from 'vitest';
import { Kinds } from '../src/generated/core.ts';
import { authProof, verifyAuthProof } from '../src/wire/auth.ts';
import { decodeKind, encodeKind } from '../src/wire/helpers.ts';
import { applyPatch, decodePatchFields, diffPatch, encodePatchFields } from '../src/wire/patch.ts';

describe('field patches', () => {
  const box = Kinds['core:box'];
  const a = encodeKind(box, { min: { x: 1, y: 2, z: 3 }, size: { x: 1, y: 1, z: 1 }, style: 'a' });
  const b = encodeKind(box, { min: { x: 5, y: 2, z: 3 }, size: { x: 1, y: 1, z: 1 }, style: 'a' });

  it('diff then apply reproduces the target bytes', () => {
    const fields = diffPatch(a, b)!;
    expect([...decodePatchFields(fields).keys()]).toEqual([0]);
    expect(applyPatch(a, fields)).toEqual(b);
    expect(decodeKind(box, applyPatch(a, fields)).min.x).toBe(5);
  });

  it('identical data gives an empty patch', () => {
    expect(decodePatchFields(diffPatch(a, a)!).size).toBe(0);
  });

  it('pads with nil when patching past the end', () => {
    const short = encodeKind(Kinds['core:layer'], {});
    const fields = encodePatchFields(new Map([[3, Uint8Array.of(0x01)]]));
    expect(Array.from(applyPatch(short, fields))).toEqual([0x94, 0xc0, 0xc3, 0xc0, 0x01]);
  });

  it('refuses to shrink', () => {
    expect(diffPatch(a, Uint8Array.of(0x91, 0xc0))).toBeNull();
  });
});

describe('auth proof', () => {
  it('is HMAC over nonce, u64be connection id and device id', () => {
    const key = new Uint8Array(32).fill(7);
    const nonce = new Uint8Array(32).fill(1);
    const proof = authProof(key, nonce, 2 ** 40 + 5, 'dev');
    expect(proof).toHaveLength(32);
    expect(verifyAuthProof(key, nonce, 2 ** 40 + 5, 'dev', proof)).toBe(true);
    expect(verifyAuthProof(key, nonce, 2 ** 40 + 6, 'dev', proof)).toBe(false);
    expect(verifyAuthProof(key, nonce, 2 ** 40 + 5, 'dev2', proof)).toBe(false);
  });
});
