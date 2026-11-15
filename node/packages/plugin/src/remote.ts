import { isIP } from 'node:net';
import { connect, type ConnectionOptions } from 'node:tls';
import type { Duplex } from 'node:stream';
import type { ClientOptions } from 'ws';
import { BridgeError, certificateFingerprint, fingerprintMatchesPrefix, normalizeFingerprint, parsePairingUri } from '@mineflayer-bridge/protocol';
import { defaultEndpointFile, ENDPOINT_ENV, PAIR_ENV, resolveEndpoint } from './files.ts';
import type { CredentialStore, EndpointInfo, StoredDevice } from './types.ts';
import { asBridgeError } from './util.ts';

const PATH = '/bridge/v1';

/** Appends the protocol path when the URL has none (endpoint.json carries only scheme, host and port). */
export function bridgeUrl(url: string): string {
  const u = new URL(url);
  if (u.pathname === '' || u.pathname === '/') u.pathname = PATH;
  return u.toString();
}

/** `<scheme>://<host>[:<port>]` of a URL, the form credentials store endpoints in. */
export function originOf(url: string): string {
  const u = new URL(url);
  return `${u.protocol}//${u.host}`;
}

function isTls(url: string): boolean {
  const p = new URL(url).protocol;
  return p === 'wss:' || p === 'https:';
}

/**
 * How the server certificate of a `wss://` endpoint is checked. CA chains and host names are
 * ignored; only the SHA-256 fingerprint of the leaf counts.
 */
export type Pin =
  /** While pairing: the fingerprint must start with the bytes from the pairing URI. */
  | { readonly kind: 'prefix'; readonly prefix: Uint8Array }
  /** After pairing: the fingerprint must equal the stored full fingerprint. */
  | { readonly kind: 'exact'; readonly fingerprint: string };

/** Where and how one connection attempt connects. */
export interface Target {
  /** WebSocket URL including the protocol path. */
  readonly url: string;
  /** {@link originOf} the endpoint. */
  readonly origin: string;
  /** Local token and fingerprint, when the endpoint came from `endpoint.json` or the options. */
  readonly info: EndpointInfo;
  /** Set for every TLS endpoint; plain `ws://` endpoints are only for loopback with the local token. */
  readonly pin: Pin | undefined;
  /** Pairing code to redeem, when this attempt pairs. */
  readonly pairCode: string | undefined;
  /** Why this endpoint was chosen, for the log: `the endpoint option`, `the endpoint file <path>` and so on. */
  readonly source: string;
}

/** Options {@link resolveTarget} reads. */
export interface TargetOptions {
  endpoint?: string | EndpointInfo | undefined;
  endpointFile?: string | undefined;
  pair?: string | undefined;
  /** Lets a missing local endpoint fall back to the most recently paired remote mod. */
  remoteFallback?: boolean | undefined;
  /** Allows plain `ws://` to a host that is not loopback. */
  allowInsecure?: boolean | undefined;
}

/** True for `localhost`, `127.0.0.0/8` and `::1`. */
function isLoopback(url: string): boolean {
  const host = new URL(url).hostname.toLowerCase();
  if (host === 'localhost' || host === '[::1]') return true;
  return isIP(host) === 4 && host.startsWith('127.');
}

/**
 * Resolves the target of one connection attempt. Order: a pairing URI without a stored credential
 * for its endpoint, the explicit `endpoint`, a pairing URI whose credential exists, `endpointFile`,
 * `MINEFLAYER_BRIDGE_ENDPOINT`, the default `endpoint.json`, and last, only with `remoteFallback`,
 * the most recently paired remote mod in the credential store.
 *
 * @param paired A credential issued earlier on this link that may not be on disk yet.
 * @throws BridgeError `unauthorized` (not retryable) for a TLS endpoint with nothing to pin it to,
 *   and for a plain `ws://` endpoint on a host other than loopback without `allowInsecure`;
 *   `invalid_message` for a malformed pairing URI or fingerprint; `disconnected` (retryable) when no
 *   endpoint can be found.
 */
export async function resolveTarget(o: TargetOptions, credentials: CredentialStore, paired: StoredDevice | undefined, env: NodeJS.ProcessEnv = process.env): Promise<Target> {
  const stored = [...(paired === undefined ? [] : [paired]), ...((await credentials.list?.()) ?? [])];
  const byOrigin = (origin: string): StoredDevice | undefined => stored.find((d) => d.endpoint !== undefined && originOf(d.endpoint) === origin);
  const insecure = o.allowInsecure === true;
  const fromEnv = env[PAIR_ENV];
  const pairUri = o.pair ?? (fromEnv !== undefined && fromEnv !== '' ? fromEnv : undefined);
  if (pairUri !== undefined) {
    const p = parsePairingUri(pairUri);
    const origin = originOf(p.endpoint);
    const from = o.pair === undefined ? `the pairing URI in ${PAIR_ENV}` : 'the pair option';
    const known = stored.find((d) => d.endpoint !== undefined && originOf(d.endpoint) === origin && fingerprintMatchesPrefix(d.fingerprint, p.fingerprintPrefix));
    if (known === undefined) {
      return { url: bridgeUrl(p.endpoint), origin, info: { url: p.endpoint }, pin: { kind: 'prefix', prefix: p.fingerprintPrefix }, pairCode: p.code, source: `${from}, pairing now` };
    }
    if (o.endpoint === undefined) return pinned({ url: p.endpoint }, known.fingerprint, `${from}, already paired`);
  }
  if (o.endpoint !== undefined) return fromInfo(typeof o.endpoint === 'string' ? { url: o.endpoint } : o.endpoint, byOrigin, insecure, 'the endpoint option');
  const envEndpoint = env[ENDPOINT_ENV];
  const discovered = o.endpointFile === undefined && (envEndpoint === undefined || envEndpoint === '');
  const source =
    o.endpointFile !== undefined ? `the endpointFile option (${o.endpointFile})` : discovered ? `the endpoint file ${defaultEndpointFile()}` : `${ENDPOINT_ENV}${/^wss?:\/\//i.test(envEndpoint!) ? '' : ` (file ${envEndpoint})`}`;
  let info: EndpointInfo;
  try {
    info = await resolveEndpoint(o.endpointFile === undefined ? {} : { endpointFile: o.endpointFile }, env);
  } catch (e) {
    if (!discovered) throw e;
    const remote = stored.find((d) => d.endpoint !== undefined);
    if (o.remoteFallback === true && remote?.endpoint !== undefined) {
      return pinned({ url: remote.endpoint }, remote.fingerprint, 'remoteFallback: no local endpoint file, using the most recently paired remote mod');
    }
    if (o.remoteFallback === true) throw e;
    const error = asBridgeError(e);
    const hint =
      remote?.endpoint === undefined
        ? 'set the endpoint option, or remoteFallback: true to use the most recently paired remote mod'
        : `a paired remote mod is stored (${remote.endpoint}); set endpoint to it, or remoteFallback: true to use the most recently paired remote mod`;
    throw new BridgeError(error.code, `${error.message}; ${hint}`, { retryable: error.retryable, cause: e });
  }
  return fromInfo(info, byOrigin, insecure, source);
}

function pinned(info: EndpointInfo, fingerprint: string, source: string): Target {
  return { url: bridgeUrl(info.url), origin: originOf(info.url), info, pin: { kind: 'exact', fingerprint }, pairCode: undefined, source };
}

function fromInfo(info: EndpointInfo, byOrigin: (origin: string) => StoredDevice | undefined, allowInsecure: boolean, source: string): Target {
  const origin = originOf(info.url);
  if (!isTls(info.url)) {
    if (!allowInsecure && !isLoopback(info.url)) {
      throw new BridgeError(
        'unauthorized',
        `refusing the unencrypted endpoint ${origin} (from ${source}): plain ws:// is only for a mod on this machine. Pair with the mod's mfb:// URI to connect over TLS, or set allowInsecure: true if the network is trusted`,
      );
    }
    return { url: bridgeUrl(info.url), origin, info, pin: undefined, pairCode: undefined, source };
  }
  let fingerprint: string | undefined;
  if (info.fingerprint !== undefined) {
    fingerprint = normalizeFingerprint(info.fingerprint);
    if (fingerprint === undefined) throw new BridgeError('invalid_message', `endpoint fingerprint is not a SHA-256 fingerprint: ${info.fingerprint}`);
  }
  fingerprint ??= byOrigin(origin)?.fingerprint;
  if (fingerprint === undefined) {
    throw new BridgeError('unauthorized', `no pinned certificate for ${origin}: pair this device first with the mod's mfb:// URI (the pair option, ${PAIR_ENV} or "mineflayer-bridge pair <uri>")`);
  }
  return pinned(info, fingerprint, source);
}

type Connector = (options: ConnectionOptions & { host?: string; path?: string }, done: (error: Error | null, socket?: Duplex) => void) => undefined;

/**
 * A `createConnection` for `ws` that opens the TLS socket itself and hands it to the HTTP client
 * only after the certificate fingerprint matches `pin`. Nothing, not even the upgrade request, is
 * written to a socket whose certificate does not match.
 *
 * @param timeoutMs TLS handshake timeout, in ms.
 * @param verified Called with the canonical fingerprint of an accepted certificate.
 * @param refused Called with the `unauthorized` error of a rejected certificate, before the socket closes.
 */
export function pinnedConnection(pin: Pin, timeoutMs: number, verified: (fingerprint: string) => void, refused: (error: BridgeError) => void): NonNullable<ClientOptions['createConnection']> {
  const connector: Connector = (options, done) => {
    const host = options.host ?? 'localhost';
    // CA validation is off on purpose: the mod's certificate is self-signed and pinned below.
    const socket = connect({ ...options, path: undefined, servername: isIP(host) === 0 ? host : '', rejectUnauthorized: false } as ConnectionOptions);
    const onError = (e: Error): void => done(e);
    socket.once('error', onError);
    socket.setTimeout(timeoutMs, () => socket.destroy(new BridgeError('timeout', `TLS handshake with ${host} timed out after ${timeoutMs} ms`, { retryable: true })));
    socket.once('secureConnect', () => {
      socket.setTimeout(0);
      socket.off('error', onError);
      const raw = (socket.getPeerCertificate(false) as { raw?: Buffer }).raw;
      const fingerprint = raw === undefined ? undefined : certificateFingerprint(new Uint8Array(raw));
      const ok = fingerprint !== undefined && (pin.kind === 'exact' ? fingerprint === pin.fingerprint : fingerprintMatchesPrefix(fingerprint, pin.prefix));
      if (!ok) {
        const error =
          pin.kind === 'prefix'
            ? new BridgeError('unauthorized', `the certificate of ${host} (${fingerprint ?? 'none'}) does not match the pairing URI; refusing to pair, the connection may be intercepted`, { details: { fingerprint } })
            : new BridgeError(
                'unauthorized',
                `the certificate of ${host} changed from the pinned ${pin.fingerprint} to ${fingerprint ?? 'none'}; refusing to connect. If the mod was reinstalled, run "mineflayer-bridge forget ${pin.fingerprint}" and pair again`,
                { details: { expected: pin.fingerprint, fingerprint } },
              );
        refused(error);
        socket.destroy();
        done(error);
        return;
      }
      verified(fingerprint);
      done(null, socket);
    });
    return undefined;
  };
  return connector as unknown as NonNullable<ClientOptions['createConnection']>;
}
