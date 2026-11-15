import { normalizeFingerprint } from '@mineflayer-bridge/protocol';
import { defaultCredentialsFile, FileCredentialStore } from './files.ts';
import { pairDevice } from './pair.ts';
import { originOf } from './remote.ts';
import type { Logger } from './types.ts';
import { asBridgeError } from './util.ts';

/** Where {@link runCli} writes; `process.stdout` and `process.stderr` by default. */
export interface CliIo {
  out(line: string): void;
  err(line: string): void;
}

const USAGE = `Usage: mineflayer-bridge <command> [options]

Commands:
  pair <mfb-uri>                  Pair this machine with a remote mod and store the credential
  devices                         List stored credentials
  forget <fingerprint|endpoint>   Delete the credential for a fingerprint or wss:// endpoint

Options:
  --credentials <path>   Credential file (default: ${defaultCredentialsFile()})
  --name <device name>   Device name shown in game when pairing (default: the host name)
  --timeout <ms>         Pairing timeout in ms (default: 30000)
  --json                 Machine-readable output for devices
  -h, --help             Show this help`;

interface Parsed {
  positional: string[];
  credentials: string | undefined;
  name: string | undefined;
  timeoutMs: number | undefined;
  json: boolean;
  help: boolean;
}

class UsageError extends Error {}

function parseArgs(argv: readonly string[]): Parsed {
  const p: Parsed = { positional: [], credentials: undefined, name: undefined, timeoutMs: undefined, json: false, help: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!;
    const value = (): string => {
      const v = argv[++i];
      if (v === undefined) throw new UsageError(`${a} needs a value`);
      return v;
    };
    if (a === '--credentials') p.credentials = value();
    else if (a.startsWith('--credentials=')) p.credentials = a.slice('--credentials='.length);
    else if (a === '--name') p.name = value();
    else if (a === '--timeout') {
      const n = Number(value());
      if (!Number.isInteger(n) || n <= 0) throw new UsageError('--timeout must be a positive integer of ms');
      p.timeoutMs = n;
    } else if (a === '--json') p.json = true;
    else if (a === '-h' || a === '--help') p.help = true;
    else if (a.startsWith('-')) throw new UsageError(`unknown option ${a}`);
    else p.positional.push(a);
  }
  return p;
}

/**
 * Runs the `mineflayer-bridge` command line: `pair`, `devices` and `forget`. Errors are printed,
 * never thrown.
 *
 * @param argv Arguments after the program name.
 * @returns The exit code: 0 on success, 1 on failure, 2 on a usage error.
 */
export async function runCli(argv: readonly string[], io: CliIo = { out: (l) => process.stdout.write(`${l}\n`), err: (l) => process.stderr.write(`${l}\n`) }): Promise<number> {
  let args: Parsed;
  try {
    args = parseArgs(argv);
  } catch (e) {
    io.err(`${(e as Error).message}\n\n${USAGE}`);
    return 2;
  }
  const [command, ...rest] = args.positional;
  if (args.help || command === undefined || command === 'help') {
    (command === undefined && !args.help ? io.err : io.out)(USAGE);
    return command === undefined && !args.help ? 2 : 0;
  }
  const store = new FileCredentialStore(args.credentials);
  try {
    switch (command) {
      case 'pair': {
        if (rest.length !== 1) throw new UsageError('pair needs exactly one mfb:// URI');
        const quiet: Logger = { debug() {}, info() {}, warn: (m) => io.err(`warning: ${m}`), error: (m) => io.err(m) };
        const r = await pairDevice(rest[0]!, { credentials: store, deviceName: args.name, timeoutMs: args.timeoutMs, logger: quiet });
        io.out(r.existing ? `Already paired with ${r.endpoint}` : `Paired with ${r.endpoint}`);
        io.out(`  device id:   ${r.deviceId}`);
        io.out(`  fingerprint: ${r.fingerprint}`);
        io.out(`  endpoint:    ${r.endpoint}`);
        io.out(`  credentials: ${store.path}`);
        return 0;
      }
      case 'devices': {
        if (rest.length !== 0) throw new UsageError('devices takes no arguments');
        const devices = await store.list();
        if (args.json) {
          io.out(JSON.stringify(devices.map(({ secret: _secret, ...d }) => d), null, 2));
          return 0;
        }
        if (devices.length === 0) {
          io.out(`No stored credentials in ${store.path}`);
          return 0;
        }
        for (const d of devices) {
          io.out(d.fingerprint);
          io.out(`  device id: ${d.deviceId}`);
          io.out(`  endpoint:  ${d.endpoint ?? '(local mod)'}`);
          if (d.pairedAt !== undefined) io.out(`  paired:    ${new Date(d.pairedAt).toISOString()}`);
        }
        return 0;
      }
      case 'forget': {
        if (rest.length !== 1) throw new UsageError('forget needs a fingerprint or an endpoint');
        const target = rest[0]!;
        const fingerprint = normalizeFingerprint(target);
        let origin: string | undefined;
        if (fingerprint === undefined) {
          try {
            origin = originOf(target);
          } catch {
            throw new UsageError(`${target} is neither a SHA-256 fingerprint nor a wss:// endpoint`);
          }
        }
        const doomed = (await store.list()).filter((d) => d.fingerprint === fingerprint || (origin !== undefined && d.endpoint !== undefined && originOf(d.endpoint) === origin));
        for (const d of doomed) await store.forget(d.fingerprint);
        if (doomed.length === 0) {
          io.err(`No stored credential matches ${target}`);
          return 1;
        }
        for (const d of doomed) io.out(`Forgot ${d.fingerprint}${d.endpoint === undefined ? '' : ` (${d.endpoint})`}`);
        return 0;
      }
      default:
        throw new UsageError(`unknown command ${command}`);
    }
  } catch (e) {
    if (e instanceof UsageError) {
      io.err(`${e.message}\n\n${USAGE}`);
      return 2;
    }
    const error = asBridgeError(e);
    io.err(`mineflayer-bridge ${command}: ${error.message} [${error.code}]`);
    return 1;
  }
}
