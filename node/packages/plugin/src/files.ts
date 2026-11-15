import { randomUUID } from 'node:crypto';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { BridgeError, normalizeFingerprint } from '@mineflayer-bridge/protocol';
import type { BridgeStore, CredentialStore, DeviceCredential, EndpointInfo, PersistedOwner, StoredDevice } from './types.ts';

/** Environment variable that overrides endpoint discovery with a URL or an `endpoint.json` path. */
export const ENDPOINT_ENV = 'MINEFLAYER_BRIDGE_ENDPOINT';

/** Environment variable holding a pairing URI, used when the `pair` option is not given. */
export const PAIR_ENV = 'MINEFLAYER_BRIDGE_PAIR';

interface PlatformInfo {
  platform: NodeJS.Platform;
  env: Readonly<Record<string, string | undefined>>;
  home: string;
}

function current(): PlatformInfo {
  return { platform: process.platform, env: process.env, home: homedir() };
}

/** The Minecraft game directory for the platform (`%APPDATA%/.minecraft`, `~/Library/Application Support/minecraft`, `~/.minecraft`). */
export function minecraftDir(p: PlatformInfo = current()): string {
  if (p.platform === 'win32') return join(p.env['APPDATA'] ?? join(p.home, 'AppData', 'Roaming'), '.minecraft');
  if (p.platform === 'darwin') return join(p.home, 'Library', 'Application Support', 'minecraft');
  return join(p.home, '.minecraft');
}

/** Where the mod writes `endpoint.json`: `<minecraft dir>/config/mineflayer_bridge/endpoint.json`. */
export function defaultEndpointFile(p: PlatformInfo = current()): string {
  return join(minecraftDir(p), 'config', 'mineflayer_bridge', 'endpoint.json');
}

/** The OS per-user config directory for the plugin (`%APPDATA%`, `~/Library/Application Support`, `$XDG_CONFIG_HOME` or `~/.config`). */
export function pluginConfigDir(p: PlatformInfo = current()): string {
  if (p.platform === 'win32') return join(p.env['APPDATA'] ?? join(p.home, 'AppData', 'Roaming'), 'mineflayer-bridge');
  if (p.platform === 'darwin') return join(p.home, 'Library', 'Application Support', 'mineflayer-bridge');
  const xdg = p.env['XDG_CONFIG_HOME'];
  return join(xdg !== undefined && xdg !== '' ? xdg : join(p.home, '.config'), 'mineflayer-bridge');
}

/** Default credential file: `<plugin config dir>/credentials.json`. */
export function defaultCredentialsFile(p: PlatformInfo = current()): string {
  return join(pluginConfigDir(p), 'credentials.json');
}

/** Reads and validates an `endpoint.json`. */
export async function readEndpointFile(path: string): Promise<EndpointInfo> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(await readFile(path, 'utf8'));
  } catch (e) {
    throw new BridgeError('disconnected', `cannot read endpoint file ${path}: ${e instanceof Error ? e.message : String(e)}`, { retryable: true, cause: e });
  }
  if (typeof parsed !== 'object' || parsed === null) throw new BridgeError('invalid_message', `endpoint file ${path} is not an object`);
  const o = parsed as Record<string, unknown>;
  if (typeof o['url'] !== 'string') throw new BridgeError('invalid_message', `endpoint file ${path} has no url`);
  const info: EndpointInfo = { url: o['url'] };
  if (typeof o['localToken'] === 'string') info.localToken = o['localToken'];
  if (typeof o['fingerprint'] === 'string') info.fingerprint = o['fingerprint'];
  return info;
}

/**
 * Resolves the endpoint for one connection attempt. Order: explicit `endpoint`, explicit
 * `endpointFile`, the `MINEFLAYER_BRIDGE_ENDPOINT` variable, then the default file. Files are re-read
 * on every call because the mod writes a new token on every start.
 */
export async function resolveEndpoint(options: { endpoint?: string | EndpointInfo; endpointFile?: string }, env = process.env): Promise<EndpointInfo> {
  if (options.endpoint !== undefined) return typeof options.endpoint === 'string' ? { url: options.endpoint } : options.endpoint;
  if (options.endpointFile !== undefined) return readEndpointFile(options.endpointFile);
  const fromEnv = env[ENDPOINT_ENV];
  if (fromEnv !== undefined && fromEnv !== '') {
    return /^wss?:\/\//i.test(fromEnv) ? { url: fromEnv } : readEndpointFile(fromEnv);
  }
  return readEndpointFile(defaultEndpointFile());
}

/** Writes JSON readable only by the current user, atomically through a temporary file. */
async function writePrivateJson(path: string, value: unknown): Promise<void> {
  await mkdir(dirname(path), { recursive: true });
  // The random part keeps two writers in one process and one millisecond apart.
  const tmp = `${path}.${process.pid}.${Date.now()}.${randomUUID().slice(0, 8)}.tmp`;
  await writeFile(tmp, JSON.stringify(value, null, 2), { mode: 0o600 });
  await rename(tmp, path);
}

async function readJson(path: string): Promise<unknown> {
  try {
    return JSON.parse(await readFile(path, 'utf8'));
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code === 'ENOENT') return undefined;
    throw e;
  }
}

interface CredentialFile {
  v: 1;
  deviceId: string;
  devices: Record<string, DeviceCredential>;
}

/** The key a fingerprint is stored under: canonical when it parses, otherwise as given. */
function fingerprintKey(fingerprint: string): string {
  return normalizeFingerprint(fingerprint) ?? fingerprint;
}

/**
 * Credential store backed by a JSON file (mode 0600). Holds the stable device id used for `local`
 * authentication and the device credentials per mod fingerprint, plus the endpoint of remotely
 * paired mods. Fingerprints are compared in canonical form, so files written with another hex
 * spelling still match.
 *
 * The file is read once and then served from memory; a load that fails is retried by the next call.
 * Every save or forget reads the file again first and changes only its own entry, so several
 * processes sharing one file keep each other's credentials. A reader sees entries written by another
 * process after its own next write, or in a new store.
 */
export class FileCredentialStore implements CredentialStore {
  private cache: Promise<CredentialFile> | undefined;
  private chain: Promise<unknown> = Promise.resolve();

  constructor(readonly path: string = defaultCredentialsFile()) {}

  private load(): Promise<CredentialFile> {
    if (this.cache === undefined) {
      const loading = (async () => {
        const raw = (await readJson(this.path)) as Partial<CredentialFile> | undefined;
        if (raw !== undefined && typeof raw.deviceId === 'string') {
          return { v: 1 as const, deviceId: raw.deviceId, devices: raw.devices ?? {} };
        }
        const created: CredentialFile = { v: 1, deviceId: randomUUID(), devices: raw?.devices ?? {} };
        await writePrivateJson(this.path, created);
        return created;
      })();
      this.cache = loading;
      // A failed load is not kept, so the next call reads the file again.
      loading.catch(() => {
        if (this.cache === loading) this.cache = undefined;
      });
    }
    return this.cache;
  }

  async deviceId(): Promise<string> {
    return (await this.load()).deviceId;
  }

  async device(fingerprint: string): Promise<DeviceCredential | undefined> {
    const devices = (await this.load()).devices;
    const direct = devices[fingerprint];
    if (direct !== undefined) return direct;
    const key = fingerprintKey(fingerprint);
    for (const [k, v] of Object.entries(devices)) if (fingerprintKey(k) === key) return v;
    return undefined;
  }

  async saveDevice(fingerprint: string, credential: DeviceCredential): Promise<void> {
    const key = fingerprintKey(fingerprint);
    await this.update((devices) => {
      for (const k of Object.keys(devices)) if (k !== key && fingerprintKey(k) === key) delete devices[k];
      devices[key] = credential;
      return true;
    });
  }

  /** Every stored credential, most recently paired first. */
  async list(): Promise<StoredDevice[]> {
    const devices = (await this.load()).devices;
    return Object.entries(devices)
      .map(([fingerprint, c]) => ({ ...c, fingerprint: fingerprintKey(fingerprint) }))
      .sort((a, b) => (b.pairedAt ?? 0) - (a.pairedAt ?? 0));
  }

  async forget(fingerprint: string): Promise<boolean> {
    const key = fingerprintKey(fingerprint);
    return this.update((devices) => {
      const doomed = Object.keys(devices).filter((k) => fingerprintKey(k) === key);
      for (const k of doomed) delete devices[k];
      return doomed.length > 0;
    });
  }

  /**
   * Applies `change` to the devices as they are on disk now, not as this store last saw them, and
   * writes the file when `change` returns true. Changes of one store run one after another.
   */
  private async update(change: (devices: Record<string, DeviceCredential>) => boolean): Promise<boolean> {
    const run = this.chain.then(async () => {
      const mine = await this.load();
      const disk = (await readJson(this.path)) as Partial<CredentialFile> | undefined;
      const devices = { ...(disk === undefined ? mine.devices : (disk.devices ?? {})) };
      const changed = change(devices);
      // The device id on disk stays as it is; this process keeps the one it started with.
      if (changed) await writePrivateJson(this.path, { v: 1, deviceId: typeof disk?.deviceId === 'string' ? disk.deviceId : mine.deviceId, devices });
      this.cache = Promise.resolve({ v: 1, deviceId: mine.deviceId, devices });
      return changed;
    });
    this.chain = run.catch(() => undefined);
    return run;
  }
}

/** In-memory {@link BridgeStore}; state lasts for the life of the process. */
export class MemoryStore implements BridgeStore {
  private readonly owners = new Map<string, PersistedOwner>();

  load(owner: string): Promise<PersistedOwner | undefined> {
    const s = this.owners.get(owner);
    return Promise.resolve(s === undefined ? undefined : structuredClone(s));
  }

  save(owner: string, state: PersistedOwner): Promise<void> {
    this.owners.set(owner, structuredClone(state));
    return Promise.resolve();
  }
}

/**
 * {@link BridgeStore} backed by one JSON file (mode 0600) holding every owner. Writes are serialised
 * and atomic, so a crash leaves either the old or the new file.
 */
export class JsonFileStore implements BridgeStore {
  private data: Promise<Record<string, PersistedOwner>> | undefined;
  private chain: Promise<unknown> = Promise.resolve();

  constructor(readonly path: string) {}

  private all(): Promise<Record<string, PersistedOwner>> {
    this.data ??= (async () => {
      const raw = (await readJson(this.path)) as { v?: number; owners?: Record<string, PersistedOwner> } | undefined;
      return raw?.owners ?? {};
    })();
    return this.data;
  }

  async load(owner: string): Promise<PersistedOwner | undefined> {
    const s = (await this.all())[owner];
    return s === undefined ? undefined : structuredClone(s);
  }

  async save(owner: string, state: PersistedOwner): Promise<void> {
    const all = await this.all();
    all[owner] = structuredClone(state);
    const write = this.chain.then(() => writePrivateJson(this.path, { v: 1, owners: all }));
    this.chain = write.catch(() => undefined);
    await write;
  }
}
