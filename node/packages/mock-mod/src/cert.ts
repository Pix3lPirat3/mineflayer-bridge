import { generateKeyPairSync, randomBytes, sign } from 'node:crypto';
import { certificateFingerprint } from '@mineflayer-bridge/protocol';

/** A PEM key pair with its self-signed certificate, as {@link generateCertificate} returns it. */
export interface MockCertificate {
  /** PKCS#8 private key, PEM. */
  readonly key: string;
  /** X.509 certificate, PEM. */
  readonly cert: string;
  /** Canonical `sha256:<hex>` fingerprint of the certificate's DER bytes. */
  readonly fingerprint: string;
}

/** Options for {@link generateCertificate}. */
export interface CertificateOptions {
  /** Subject and issuer common name. Default `mineflayer-bridge mock`. */
  commonName?: string;
  /** Validity from one day ago, in days. Default 30. */
  days?: number;
}

function length(n: number): Buffer {
  if (n < 0x80) return Buffer.from([n]);
  const bytes: number[] = [];
  for (let v = n; v > 0; v >>>= 8) bytes.unshift(v & 0xff);
  return Buffer.from([0x80 | bytes.length, ...bytes]);
}

function tlv(tag: number, ...content: Buffer[]): Buffer {
  const body = Buffer.concat(content);
  return Buffer.concat([Buffer.from([tag]), length(body.length), body]);
}

const seq = (...c: Buffer[]): Buffer => tlv(0x30, ...c);

function oid(dotted: string): Buffer {
  const parts = dotted.split('.').map(Number);
  const out: number[] = [40 * parts[0]! + parts[1]!];
  for (const p of parts.slice(2)) {
    const chunk: number[] = [p & 0x7f];
    for (let v = p >>> 7; v > 0; v >>>= 7) chunk.unshift(0x80 | (v & 0x7f));
    out.push(...chunk);
  }
  return tlv(0x06, Buffer.from(out));
}

/** RFC 5280: UTCTime through 2049, GeneralizedTime from 2050. */
function time(d: Date): Buffer {
  const iso = d.toISOString().replace(/[-:T]/g, '').slice(0, 14);
  return d.getUTCFullYear() < 2050 ? tlv(0x17, Buffer.from(`${iso.slice(2)}Z`)) : tlv(0x18, Buffer.from(`${iso}Z`));
}

function name(cn: string): Buffer {
  return seq(tlv(0x31, seq(oid('2.5.4.3'), tlv(0x0c, Buffer.from(cn, 'utf8')))));
}

function pem(label: string, der: Buffer): string {
  const lines = der.toString('base64').match(/.{1,64}/g) ?? [];
  return `-----BEGIN ${label}-----\n${lines.join('\n')}\n-----END ${label}-----\n`;
}

/**
 * Generates an ECDSA P-256 key and a self-signed X.509 v3 certificate for it with `node:crypto` and
 * a minimal DER encoder, so tests need no certificate tooling. The certificate is only meant to be
 * pinned by fingerprint; it carries no extensions and would fail CA validation by design.
 */
export function generateCertificate(options: CertificateOptions = {}): MockCertificate {
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const spki = publicKey.export({ type: 'spki', format: 'der' });
  const serial = randomBytes(16);
  // DER INTEGER: positive (top bit clear) and minimal (first byte non-zero).
  serial[0] = (serial[0]! & 0x7f) | 0x40;
  const now = Date.now();
  const days = options.days ?? 30;
  const ecdsaSha256 = seq(oid('1.2.840.10045.4.3.2'));
  const subject = name(options.commonName ?? 'mineflayer-bridge mock');
  const tbs = seq(
    tlv(0xa0, tlv(0x02, Buffer.from([2]))),
    tlv(0x02, serial),
    ecdsaSha256,
    subject,
    seq(time(new Date(now - 86_400_000)), time(new Date(now + days * 86_400_000))),
    subject,
    spki,
  );
  const signature = sign('sha256', tbs, privateKey);
  const der = seq(tbs, ecdsaSha256, tlv(0x03, Buffer.from([0]), signature));
  return {
    key: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString(),
    cert: pem('CERTIFICATE', der),
    fingerprint: certificateFingerprint(der),
  };
}
