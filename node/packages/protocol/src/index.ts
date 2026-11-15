export * from './generated/core.ts';
export type { Codec, Delivery, Direction, KindType, MessageType } from './wire/codec.ts';
export { Flags, expectFullyRead, readHeader, writeHeader, type DecodedFrame, type EnvelopeHeader } from './wire/envelope.ts';
export { BridgeError, WireError, type ErrorCode } from './wire/errors.ts';
export { WireReader } from './wire/reader.ts';
export { WireWriter } from './wire/writer.ts';
export { decodeKind, encodeKind, encodeMessage, packBlockPos, unpackBlockPos, type BlockPos } from './wire/helpers.ts';
export { applyPatch, bytesEqual, decodePatchFields, diffPatch, encodePatchFields, joinArray, splitArray } from './wire/patch.ts';
export { authProof, fromBase64Url, toBase64Url, verifyAuthProof } from './wire/auth.ts';
export {
  certificateFingerprint,
  DEFAULT_PORT,
  fingerprintBytes,
  fingerprintMatchesPrefix,
  formatPairingUri,
  fromBase32,
  normalizeFingerprint,
  PAIRING_PREFIX_BYTES,
  parsePairingUri,
  toBase32,
  type PairingUri,
} from './wire/pairing.ts';
export { LOCAL_DEFAULT_PERMISSIONS, PERMISSIONS, REMOTE_DEFAULT_PERMISSIONS, type Permission } from './wire/permissions.ts';
