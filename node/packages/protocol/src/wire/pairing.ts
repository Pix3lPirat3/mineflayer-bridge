import { createHash } from 'node:crypto';
import { BridgeError } from './errors.ts';

/** Default mod port. */
export const DEFAULT_PORT = 25590;

/** Bytes of the certificate fingerprint carried in a pairing URI. */
export const PAIRING_PREFIX_BYTES = 20;

const BASE32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

/** Encodes bytes as RFC 4648 base32, uppercase and without padding. */
export function toBase32(bytes: Uint8Array): string {
  let out = '';
  let bits = 0;
  let value = 0;
  for (const b of bytes) {
    value = (value << 8) | b;
    bits += 8;
    while (bits >= 5) {
      out += BASE32[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += BASE32[(value << (5 - bits)) & 31];
  return out;
}

/**
 * Decodes RFC 4648 base32. Case is ignored and trailing `=` padding is accepted.
 * @throws BridgeError `invalid_message` for a character outside the alphabet.
 */
export function fromBase32(text: string): Uint8Array {
  const clean = text.replace(/=+$/, '').toUpperCase();
  const out: number[] = [];
  let bits = 0;
  let value = 0;
  for (const c of clean) {
    const v = BASE32.indexOf(c);
    if (v < 0) throw new BridgeError('invalid_message', `invalid base32 character ${JSON.stringify(c)}`);
    value = ((value << 5) | v) & 0xffff;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return new Uint8Array(out);
}

/**
 * The canonical fingerprint of a DER certificate: `sha256:<64 lowercase hex digits>`. This is the
 * form the plugin stores credentials under and compares pins with.
 */
export function certificateFingerprint(der: Uint8Array): string {
  return `sha256:${createHash('sha256').update(der).digest('hex')}`;
}

/**
 * Normalises a SHA-256 fingerprint written as hex, with or without a `sha256:` prefix, colons or
 * upper case, to the canonical `sha256:<hex>` form. Returns `undefined` for anything else.
 */
export function normalizeFingerprint(text: string): string | undefined {
  const body = text.trim().replace(/^sha-?256:/i, '').replace(/:/g, '').toLowerCase();
  return /^[0-9a-f]{64}$/.test(body) ? `sha256:${body}` : undefined;
}

/** The raw 32 bytes of a canonical fingerprint, or `undefined` when it does not parse. */
export function fingerprintBytes(fingerprint: string): Uint8Array | undefined {
  const n = normalizeFingerprint(fingerprint);
  return n === undefined ? undefined : new Uint8Array(Buffer.from(n.slice(7), 'hex'));
}

/** True when the fingerprint's raw bytes start with `prefix`. */
export function fingerprintMatchesPrefix(fingerprint: string, prefix: Uint8Array): boolean {
  const bytes = fingerprintBytes(fingerprint);
  if (bytes === undefined || prefix.length === 0 || prefix.length > bytes.length) return false;
  for (let i = 0; i < prefix.length; i++) if (bytes[i] !== prefix[i]) return false;
  return true;
}

/** A parsed pairing URI. */
export interface PairingUri {
  /** Host name or address; IPv6 without brackets. */
  readonly host: string;
  readonly port: number;
  /** Leading bytes of the mod's certificate fingerprint, at least `PAIRING_PREFIX_BYTES` (20). */
  readonly fingerprintPrefix: Uint8Array;
  /** The single-use pairing code, sent as the `pair` proof. */
  readonly code: string;
  /** The TLS endpoint the URI names, `wss://<host>:<port>`, without the protocol path. */
  readonly endpoint: string;
}

/**
 * Parses `mfb://<host>:<port>?fp=<base32 prefix>&code=<8 chars>`. The port defaults to 25590.
 * @throws BridgeError `invalid_message` when the scheme, host, prefix or code does not fit.
 */
export function parsePairingUri(uri: string): PairingUri {
  const fail = (why: string): never => {
    throw new BridgeError('invalid_message', `invalid pairing URI: ${why}`);
  };
  let u: URL;
  try {
    u = new URL(uri.trim());
  } catch {
    return fail('not a URI');
  }
  if (u.protocol !== 'mfb:') fail(`scheme must be mfb://, got ${u.protocol}//`);
  if (u.hostname === '') fail('no host');
  const port = u.port === '' ? DEFAULT_PORT : Number(u.port);
  const fp = u.searchParams.get('fp');
  const code = u.searchParams.get('code');
  if (fp === null || fp === '') return fail('no fp parameter');
  if (code === null || !/^[A-Za-z0-9]{8}$/.test(code)) return fail('code must be 8 letters or digits');
  const fingerprintPrefix = fromBase32(fp);
  if (fingerprintPrefix.length < PAIRING_PREFIX_BYTES || fingerprintPrefix.length > 32) fail(`fp must encode ${PAIRING_PREFIX_BYTES} to 32 bytes, got ${fingerprintPrefix.length}`);
  const host = u.hostname.startsWith('[') ? u.hostname.slice(1, -1) : u.hostname;
  return { host, port, fingerprintPrefix, code, endpoint: `wss://${u.hostname}:${port}` };
}

/** Builds a pairing URI with the first {@link PAIRING_PREFIX_BYTES} bytes of `fingerprint`. */
export function formatPairingUri(host: string, port: number, fingerprint: string, code: string): string {
  const bytes = fingerprintBytes(fingerprint);
  if (bytes === undefined) throw new BridgeError('invalid_message', `not a SHA-256 fingerprint: ${fingerprint}`);
  const h = host.includes(':') && !host.startsWith('[') ? `[${host}]` : host;
  return `mfb://${h}:${port}?fp=${toBase32(bytes.subarray(0, PAIRING_PREFIX_BYTES))}&code=${code}`;
}
