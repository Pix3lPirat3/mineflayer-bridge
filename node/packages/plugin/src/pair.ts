import { BridgeError, fingerprintMatchesPrefix, parsePairingUri } from '@mineflayer-bridge/protocol';
import { FileCredentialStore } from './files.ts';
import { Link } from './link.ts';
import { originOf } from './remote.ts';
import type { CredentialStore, Logger, PairedDevice } from './types.ts';
import { defaultLogger } from './util.ts';

/** Options for {@link pairDevice}. */
export interface PairDeviceOptions {
  /** Credential file path or store. Default: `<OS config dir>/mineflayer-bridge/credentials.json`. */
  credentials?: string | CredentialStore | undefined;
  /** Device name the player sees in the device list. Default: the host name. */
  deviceName?: string | undefined;
  /** Gives up after this many ms. Default 30000. */
  timeoutMs?: number | undefined;
  /** Logger. Default: warnings and errors to the console. */
  logger?: Logger | undefined;
}

/** What {@link pairDevice} did. */
export interface PairOutcome extends PairedDevice {
  /** True when a credential for this endpoint and fingerprint was already stored, so no code was used. */
  readonly existing: boolean;
}

/**
 * Pairs this machine with a remote mod without opening any session: connects over TLS, checks the
 * certificate against the URI's fingerprint prefix before sending anything, redeems the code and
 * stores the credential with the full fingerprint. Later hubs then need only the endpoint, or
 * nothing at all.
 *
 * @param uri `mfb://<host>:<port>?fp=<base32 prefix>&code=<8 chars>`.
 * @throws BridgeError `invalid_message` for a malformed URI; `unauthorized` when the certificate
 *   does not match or the code is refused; `timeout` after `timeoutMs`; `disconnected` when the mod
 *   cannot be reached; `internal` when the mod accepted the code but the credential could not be
 *   stored (the device then exists in the mod's device list without a usable credential here).
 */
export async function pairDevice(uri: string, options: PairDeviceOptions = {}): Promise<PairOutcome> {
  const parsed = parsePairingUri(uri);
  const credentials = typeof options.credentials === 'object' ? options.credentials : new FileCredentialStore(options.credentials);
  const origin = originOf(parsed.endpoint);
  const known = ((await credentials.list?.()) ?? []).find((d) => d.endpoint !== undefined && originOf(d.endpoint) === origin && fingerprintMatchesPrefix(d.fingerprint, parsed.fingerprintPrefix));
  if (known !== undefined) return { deviceId: known.deviceId, fingerprint: known.fingerprint, endpoint: origin, existing: true };

  const link = new Link({
    pair: uri,
    credentials,
    reconnect: false,
    logger: options.logger ?? defaultLogger,
    names: () => ({ kinds: [], messages: [] }),
    ...(options.deviceName === undefined ? {} : { deviceName: options.deviceName }),
  });
  const timeoutMs = options.timeoutMs ?? 30_000;
  let timer: NodeJS.Timeout | undefined;
  try {
    return await new Promise<PairOutcome>((resolve, reject) => {
      let lastError: BridgeError | undefined;
      timer = setTimeout(() => reject(new BridgeError('timeout', `pairing did not finish within ${timeoutMs} ms`, { retryable: true })), timeoutMs);
      link.events.on('paired', (p) => resolve({ ...p, existing: false }));
      link.events.on('error', (e) => {
        lastError = e instanceof BridgeError ? e : new BridgeError('internal', e.message, { cause: e });
        // The mod accepted the code but the credential was not saved: the link stays up, so stop here.
        if (e instanceof BridgeError && e.code === 'internal' && e.message.startsWith('paired as')) reject(e);
      });
      link.events.on('disconnected', ({ reason }) => reject(lastError ?? new BridgeError('disconnected', `pairing failed: ${reason}`, { retryable: true })));
      link.start();
    });
  } finally {
    clearTimeout(timer);
    // A failed close must not replace the result or the error of the pairing.
    await link.close().catch((e: unknown) => (options.logger ?? defaultLogger).warn('could not close the pairing connection', { error: e instanceof Error ? e.message : String(e) }));
  }
}
