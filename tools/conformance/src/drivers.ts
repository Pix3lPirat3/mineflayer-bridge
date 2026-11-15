import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { createMockMod, type MockChatOutcome, type MockMod, type MockSessionInfo } from '@mineflayer-bridge/mock-mod';
import type { Target, WorldKey } from '@mineflayer-bridge/protocol';

/** One stored object, as both targets report it. `data` is the kind's encoding. */
export interface DriverObject {
  key: string;
  kind: string;
  rev: number;
  scope: string;
  layer: string | undefined;
  data: Uint8Array | undefined;
}

/** A loaded world for client queries, the same on both targets. */
export interface LoadedWorld {
  player?: { x: number; y: number; z: number };
  entities?: { id: number; type: string; pos: { x: number; y: number; z: number }; name?: string; player?: boolean }[];
  blocks?: { x: number; y: number; z: number; name: string }[];
  unloadedFromX?: number;
}

export interface DriverResult {
  ok: boolean;
  message: string | undefined;
}

/** What a chat line the player typed did. Both targets report it in this shape. */
export interface ChatOutcome {
  /**
   * `pass`: not for the bridge, `text` goes to the game server. `command`: ran a command or order,
   * once per command set whose command parses the line. `help`: stopped at a node that shows its
   * subcommands instead of running. `message`: delivered as free text. `mistyped`: close to an
   * addressable name, kept from the server. `unknown`: addressed something, but nothing could be
   * delivered. `prompt`: went to the active chat prompt.
   */
  route: 'pass' | 'command' | 'help' | 'message' | 'mistyped' | 'unknown' | 'prompt';
  /** The line for the game server, for `pass`. */
  text?: string;
  /** The answers of the invocations, for `command`. */
  results?: DriverResult[];
  /** The name the typed one is closest to, for `mistyped`. */
  closest?: string;
  /** The line with the closest name in place of the typed one, for `mistyped`. */
  corrected?: string;
  /** The help lines shown, for `help`. */
  lines?: string[];
  /** For `message`: why the line, which names a command, did not run as one. */
  commandError?: string;
  /** For `unknown`: the reason shown to the player. */
  message?: string;
  /** For `prompt`: why the line did not fit; the prompt then stays open. */
  error?: string;
}

/** An input request (pick, prompt, form or world menu) waiting in the player's queue. */
export interface DriverInput {
  id: number;
  type: string;
  owner: string;
}

/** Operations a target may lack; a scenario that needs one is skipped on a target without it. */
export type DriverFeature = 'chat' | 'permissions' | 'attach' | 'inputs' | 'clock' | 'notifications' | 'options';

/** How a scenario wants the mod started. Needs the `options` feature when any field is set. */
export interface DriverOptions {
  /** Object quota advertised in `welcome` and enforced by the mod. */
  maxObjects?: number;
  /** Surface cooldowns after the first, second, ... dismissal in a row, in ms. */
  surfaceCooldownsMs?: readonly number[];
}

/**
 * What the conformance scenarios may do to a mod: the player's side of the
 * protocol plus inspection, implemented once over the mock mod and once over the Java core.
 */
export interface ModDriver {
  readonly name: string;
  /** The endpoint file a plugin hub discovers the mod with. */
  readonly endpointFile: string;
  /** A private directory for the plugin's credentials and stores. */
  readonly dir: string;
  setPlayerContext(world: WorldKey | null): Promise<void>;
  objects(prefix: string): Promise<DriverObject[]>;
  sessions(): Promise<{ owner: string; kind: string; name: string; connected: boolean }[]>;
  invokeAction(key: string, target: Target): Promise<DriverResult>;
  invokeCommand(key: string, args: Record<string, unknown>, path?: readonly string[]): Promise<DriverResult>;
  screenEvent(key: string, item: string, value?: boolean | number): Promise<DriverResult>;
  /** Sets what the player's client has loaded; `unloadedFromX` marks columns from that x on as not loaded. */
  setLoadedWorld(world: LoadedWorld): Promise<void>;
  /** Types a chat line as the player and resolves once every command it ran has answered. */
  chat(line: string): Promise<ChatOutcome>;
  /** Replaces the permissions of the device that holds the session of `owner`. */
  setPermissions(owner: string, permissions: readonly string[]): Promise<void>;
  /** Attaches or detaches a bot by its owner key, as the player does on Bridge home. */
  setAttached(owner: string, attached: boolean): Promise<void>;
  /** The waiting input requests, the active one first. */
  inputs(): Promise<DriverInput[]>;
  /** The player dismisses an input request (Esc). */
  dismiss(id: number): Promise<void>;
  /** Moves the mod's clock forward. */
  advance(ms: number): Promise<void>;
  /** The notifications the mod showed, oldest first. */
  notifications(): Promise<{ owner: string; text: string }[]>;
  /** The chat lines bots printed that the mod showed, oldest first; `text` is the spans joined. */
  printed(): Promise<{ owner: string; text: string }[]>;
  close(): Promise<void>;
}

/** Every operation: the mock has them all. */
export const MOCK_FEATURES: ReadonlySet<DriverFeature> = new Set<DriverFeature>(['chat', 'permissions', 'attach', 'inputs', 'clock', 'notifications', 'options']);

/** What the Java conformance server answers: every operation too. A new one stays out until the server handles its op. */
export const JAVA_FEATURES: ReadonlySet<DriverFeature> = new Set<DriverFeature>(['chat', 'permissions', 'attach', 'inputs', 'clock', 'notifications', 'options']);

function chatOutcome(o: MockChatOutcome): ChatOutcome {
  if (!o.captured) return { route: 'pass', text: o.text };
  if (o.prompt !== undefined) return { route: 'prompt', ...(o.prompt.error === undefined ? {} : { error: o.prompt.error }) };
  if (o.mistyped !== undefined) return { route: 'mistyped', closest: o.mistyped.closest, corrected: o.mistyped.corrected };
  if (o.unknown !== undefined) return { route: 'unknown', message: o.unknown };
  const results = o.deliveries.flatMap((d) => (d.routed === 'command' ? [{ ok: d.result.ok, message: d.result.message }] : []));
  if (results.length > 0) return { route: 'command', results };
  for (const d of o.deliveries) if (d.routed === 'usage') return { route: 'help', lines: [...d.usage] };
  return { route: 'message', ...(o.commandError === undefined ? {} : { commandError: o.commandError }) };
}

/** The mock mod, in this process. */
export async function mockDriver(options: DriverOptions = {}): Promise<ModDriver> {
  const dir = await mkdtemp(join(tmpdir(), 'mfb-conf-mock-'));
  const endpointFile = join(dir, 'endpoint.json');
  let ahead = 0;
  const mod: MockMod = await createMockMod({
    clock: { now: () => Date.now() + ahead },
    ...(options.maxObjects === undefined ? {} : { quotas: { objects: options.maxObjects } }),
    ...(options.surfaceCooldownsMs === undefined ? {} : { surfaceCooldownsMs: options.surfaceCooldownsMs }),
  });
  await mod.writeEndpointFile(endpointFile);
  const session = (owner: string): MockSessionInfo => {
    const s = mod.session(owner);
    if (s === undefined) throw new Error(`no session ${owner}`);
    return s;
  };
  return {
    name: 'mock',
    endpointFile,
    dir,
    setPlayerContext: async (world) => mod.setPlayerContext(world),
    objects: async (prefix) =>
      mod.objects(prefix).map((o) => ({ key: o.key, kind: o.kind, rev: o.rev, scope: o.scope, layer: o.layer, data: o.raw })),
    sessions: async () => mod.sessions().map((s) => ({ owner: s.owner, kind: s.kind, name: s.name, connected: s.connected })),
    invokeAction: async (key, target) => mod.invokeAction(key, target),
    invokeCommand: async (key, args, path = []) => mod.invokeCommand(key, args, undefined, path),
    screenEvent: async (key, item, value) => mod.screenEvent(key, item, value),
    setLoadedWorld: async (w) => {
      const from = w.unloadedFromX;
      mod.setLoadedWorld({ ...w, ...(from === undefined ? {} : { loaded: (x: number) => x < from }) });
    },
    chat: async (line) => chatOutcome(await mod.sendChat(line)),
    setPermissions: async (owner, permissions) => mod.setPermissions(session(owner).deviceId, permissions),
    setAttached: async (owner, attached) => mod.setAttached(owner, attached),
    inputs: async () => mod.inputQueue().map((i) => ({ id: i.id, type: i.type, owner: i.owner })),
    dismiss: async (id) => mod.cancel(id),
    advance: async (ms) => {
      ahead += ms;
    },
    notifications: async () => mod.notifies.map((n) => ({ owner: n.owner, text: n.text })),
    printed: async () => mod.printed().map((l) => ({ owner: l.owner, text: l.spans.map((s) => s.text).join('') })),
    async close() {
      await mod.close();
      await rm(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 20 });
    },
  };
}

/** Where `gradlew :core:conformanceClasspath` writes the server's classpath. */
export const JAVA_CLASSPATH_FILE = fileURLToPath(new URL('../../../mod/core/build/conformance/classpath.txt', import.meta.url));

/**
 * Why the Java target does not run, or undefined when it does. It runs only when `CONFORMANCE_JAVA`
 * is set (`1`, or `required` in CI), so a plain `pnpm test` does not start JVMs next to every other
 * test file.
 */
export function javaUnavailable(): string | undefined {
  if (process.env.CONFORMANCE_JAVA === undefined || process.env.CONFORMANCE_JAVA === '') return 'set CONFORMANCE_JAVA=1 to run it';
  if (!existsSync(JAVA_CLASSPATH_FILE)) return `run "mod/gradlew -p mod :core:conformanceClasspath" first (no ${JAVA_CLASSPATH_FILE})`;
  return undefined;
}

/**
 * The Java core, in a child process running ConformanceServer. Options go to the server as
 * `--max-objects <n>` and `--surface-cooldowns <ms,ms,...>` after the class name.
 */
export async function javaDriver(options: DriverOptions = {}): Promise<ModDriver> {
  const dir = await mkdtemp(join(tmpdir(), 'mfb-conf-java-'));
  const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'java') : 'java';
  const args: string[] = [];
  if (options.maxObjects !== undefined) args.push('--max-objects', String(options.maxObjects));
  if (options.surfaceCooldownsMs !== undefined) args.push('--surface-cooldowns', options.surfaceCooldownsMs.join(','));
  const child: ChildProcessWithoutNullStreams = spawn(java, ['-cp', readFileSync(JAVA_CLASSPATH_FILE, 'utf8').trim(),
    'io.github.pix3lpirat3.mfbridge.core.ConformanceServer', ...args], { stdio: ['pipe', 'pipe', 'pipe'] });
  let stderr = '';
  child.stderr.on('data', (d: Buffer) => {
    stderr = (stderr + d.toString()).slice(-4000);
  });
  const waiting = new Map<number, { resolve: (v: Record<string, unknown>) => void; reject: (e: Error) => void }>();
  let nextId = 1;
  const lines = createInterface({ input: child.stdout });
  const ready = new Promise<string>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Java conformance server did not start: ${stderr}`)), 20_000);
    lines.on('line', (line) => {
      const msg = JSON.parse(line) as { event?: string; endpointFile?: string; id?: number; result?: Record<string, unknown>; error?: string };
      if (msg.event === 'ready') {
        clearTimeout(timer);
        resolve(msg.endpointFile!);
        return;
      }
      const w = msg.id === undefined ? undefined : waiting.get(msg.id);
      if (w === undefined) return;
      waiting.delete(msg.id!);
      if (msg.error !== undefined) w.reject(new Error(msg.error));
      else w.resolve(msg.result ?? {});
    });
    child.on('exit', (code) => reject(new Error(`Java conformance server exited with ${code}: ${stderr}`)));
  });
  const endpointFile = await ready;
  const call = (op: string, body: Record<string, unknown> = {}): Promise<Record<string, unknown>> =>
    new Promise((resolve, reject) => {
      const id = nextId++;
      waiting.set(id, { resolve, reject });
      child.stdin.write(`${JSON.stringify({ id, op, ...body })}\n`);
    });
  const result = (r: Record<string, unknown>): DriverResult => ({ ok: r.ok as boolean, message: r.message as string | undefined });
  return {
    name: 'java',
    endpointFile,
    dir,
    setPlayerContext: async (world) => {
      await call('setPlayerContext', { world });
    },
    objects: async (prefix) =>
      ((await call('objects', { prefix })).objects as { key: string; kind: string; rev: number; scope: string; layer?: string; data?: string }[]).map((o) => ({
        key: o.key,
        kind: o.kind,
        rev: o.rev,
        scope: o.scope,
        layer: o.layer,
        data: o.data === undefined ? undefined : new Uint8Array(Buffer.from(o.data, 'base64')),
      })),
    sessions: async () => (await call('sessions')).sessions as { owner: string; kind: string; name: string; connected: boolean }[],
    invokeAction: async (key, target) => result(await call('invokeAction', { key, target })),
    invokeCommand: async (key, args, path = []) => result(await call('invokeCommand', { key, args, path })),
    screenEvent: async (key, item, value) => result(await call('screenEvent', { key, item, value })),
    setLoadedWorld: async (world) => {
      await call('setLoadedWorld', { world });
    },
    chat: async (line) => (await call('chat', { line })) as unknown as ChatOutcome,
    setPermissions: async (owner, permissions) => {
      await call('setPermissions', { owner, permissions });
    },
    setAttached: async (owner, attached) => {
      await call('setAttached', { owner, attached });
    },
    inputs: async () => (await call('inputs')).inputs as DriverInput[],
    dismiss: async (id) => {
      await call('dismiss', { input: id });
    },
    advance: async (ms) => {
      await call('advance', { ms });
    },
    notifications: async () => (await call('notifications')).notifications as { owner: string; text: string }[],
    printed: async () => (await call('printed')).printed as { owner: string; text: string }[],
    async close() {
      child.stdin.write(`${JSON.stringify({ op: 'close' })}\n`);
      await new Promise<void>((resolve) => {
        const kill = setTimeout(() => {
          child.kill();
          resolve();
        }, 5000);
        child.on('exit', () => {
          clearTimeout(kill);
          resolve();
        });
      });
      await rm(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 20 });
    },
  };
}
