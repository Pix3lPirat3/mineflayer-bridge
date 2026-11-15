import { createHmac, timingSafeEqual } from 'node:crypto';

/**
 * Authentication proof for the `local` and `device` methods:
 * `HMAC-SHA256(key, nonce || u64be(connectionId) || utf8(deviceId))`.
 *
 * @param key Raw key bytes: the decoded `localToken` or the device secret.
 * @param nonce The 32-byte nonce from `auth.challenge`.
 * @param connectionId The `connectionId` from `auth.challenge` (u53).
 * @param deviceId The device id sent in `auth.response`.
 * @returns The 32-byte proof.
 */
export function authProof(key: Uint8Array, nonce: Uint8Array, connectionId: number, deviceId: string): Uint8Array {
  const id = Buffer.alloc(8);
  id.writeBigUInt64BE(BigInt(connectionId));
  const mac = createHmac('sha256', key);
  mac.update(nonce);
  mac.update(id);
  mac.update(Buffer.from(deviceId, 'utf8'));
  return new Uint8Array(mac.digest());
}

/** Constant-time check of a proof produced by {@link authProof}. */
export function verifyAuthProof(key: Uint8Array, nonce: Uint8Array, connectionId: number, deviceId: string, proof: Uint8Array): boolean {
  const expected = authProof(key, nonce, connectionId, deviceId);
  return proof.length === expected.length && timingSafeEqual(expected, proof);
}

/** Decodes base64url (padding optional), as used for `localToken` and stored secrets. */
export function fromBase64Url(text: string): Uint8Array {
  return new Uint8Array(Buffer.from(text, 'base64url'));
}

/** Encodes bytes as unpadded base64url. */
export function toBase64Url(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString('base64url');
}
