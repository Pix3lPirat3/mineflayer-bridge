import { EventEmitter } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createMockMod, type MockMod, type MockModOptions } from '@mineflayer-bridge/mock-mod';
import { afterEach, beforeEach, expect } from 'vitest';
import { BridgeError, createBridge, type BotBridge, type BotOptionsLike, type BridgeHub, type BridgePlugin, type Logger } from '../src/index.ts';

/** A stand-in for a mineflayer bot: an EventEmitter with the fields the plugin reads. */
export class FakeBot extends EventEmitter {
  username: string | undefined;
  entity = { position: { x: 0, y: 64, z: 0 }, id: 1 };
  game = { dimension: 'overworld' };
  player: { uuid?: string } | null = null;
  bridge?: BotBridge;

  constructor(username: string | undefined) {
    super();
    this.username = username;
  }

  get b(): BotBridge {
    if (this.bridge === undefined) throw new Error('bridge not installed');
    return this.bridge;
  }

  move(x: number, y: number, z: number): void {
    this.entity.position = { x, y, z };
    this.emit('move');
  }
}

export const SERVER = 'play.example.net:25565';
/** Owner key of the bot `Alice` that {@link Fixture.attached} loads by default. */
export const ALICE = `bot:alice@${SERVER}`;
/** Key prefix of Alice's default scope. */
export const ALICE_PREFIX = `${ALICE}/user:default`;
export const BOT_OPTIONS: BotOptionsLike = { host: 'play.example.net' };
export const OVERWORLD = { server: SERVER, dimension: 'minecraft:overworld' };

export function load(plugin: BridgePlugin, bot: FakeBot, options: BotOptionsLike = BOT_OPTIONS): BotBridge {
  plugin(bot, options);
  return bot.b;
}

/** Polls `cond` until it is truthy. */
export async function until<T>(cond: () => T | undefined | null | false, timeoutMs = 5000, what = 'condition'): Promise<T> {
  const start = Date.now();
  for (;;) {
    const v = cond();
    if (v !== undefined && v !== null && v !== false) return v;
    if (Date.now() - start > timeoutMs) throw new Error(`timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 5));
  }
}

export const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));

/** Collects warnings and errors instead of printing them. */
export function quietLogger(): Logger & { warnings: string[] } {
  const warnings: string[] = [];
  return {
    warnings,
    debug() {},
    info() {},
    warn: (m) => warnings.push(m),
    error: (m) => warnings.push(m),
  };
}

export interface Env {
  mod: MockMod;
  dir: string;
  endpointFile: string;
  credentials: string;
  cleanup: (() => Promise<void>)[];
  restart(options?: MockModOptions): Promise<MockMod>;
}

/**
 * A mock mod with its endpoint file and a private credentials path in a temp directory. The surface
 * cooldown is off unless a test asks for it, so tests may dismiss and reopen freely.
 */
export async function env(options: MockModOptions = {}): Promise<Env> {
  const dir = await mkdtemp(join(tmpdir(), 'mfb-'));
  const endpointFile = join(dir, 'mc', 'config', 'mineflayer_bridge', 'endpoint.json');
  const mod = await createMockMod({ heartbeatMs: 1000, surfaceCooldownsMs: [], ...options });
  await mod.writeEndpointFile(endpointFile);
  const e: Env = {
    mod,
    dir,
    endpointFile,
    credentials: join(dir, 'cfg', 'credentials.json'),
    cleanup: [],
    async restart(next: MockModOptions = {}) {
      const port = e.mod.port;
      await e.mod.close();
      e.mod = await createMockMod({ heartbeatMs: 1000, surfaceCooldownsMs: [], ...options, ...next, port });
      await e.mod.writeEndpointFile(endpointFile);
      return e.mod;
    },
  };
  return e;
}

export async function teardown(e: Env): Promise<void> {
  for (const c of e.cleanup.reverse()) await c();
  await e.mod.close();
  await rm(e.dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 20 });
}

/** Resolves like `p`, or rejects naming `what` after `ms`, so a hang fails at the step that hung. */
export function within<T>(p: Promise<T>, ms: number, what: string): Promise<T> {
  let timer: NodeJS.Timeout | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error(`timed out after ${ms} ms waiting for ${what}`)), ms);
  });
  return Promise.race([p, timeout]).finally(() => clearTimeout(timer));
}

/** The `BridgeError` that `fn` throws; fails the test when it throws nothing or something else. */
export function thrown(fn: () => unknown): BridgeError {
  try {
    fn();
  } catch (err) {
    expect(err).toBeInstanceOf(BridgeError);
    return err as BridgeError;
  }
  throw new Error('expected a throw');
}

/** The `BridgeError` that `p` rejects with; fails the test when it resolves or rejects with something else. */
export async function rejection(p: Promise<unknown>): Promise<BridgeError> {
  try {
    await p;
  } catch (err) {
    expect(err).toBeInstanceOf(BridgeError);
    return err as BridgeError;
  }
  throw new Error('expected a rejection');
}

export type HubOptions = NonNullable<Parameters<typeof createBridge>[0]>;

/** Per-test state of a {@link fixture}; valid inside tests and hooks registered after it. */
export interface Fixture {
  readonly e: Env;
  /** The hubs {@link hub} made in this test, oldest first. */
  readonly hubs: readonly BridgeHub[];
  /** Shared by every hub of the test, so warnings can be asserted. */
  readonly logger: Logger & { warnings: string[] };
  /** A hub on the test's mock mod with fast reconnects, closed after the test. */
  hub(extra?: Partial<HubOptions>): BridgeHub;
  /** Loads `bot` (or a bot of that name) on `h` and waits until it is attached and in the player's world. */
  attached(bot?: string | FakeBot, h?: BridgeHub): Promise<BotBridge>;
}

/**
 * Gives every test a fresh mock mod, its hubs and a quiet logger, and closes them afterwards. Call it
 * at the top of a test file; `bind` runs before each test, for files that keep the env in a variable.
 */
export function fixture(bind: (f: Fixture) => void = () => {}, options: MockModOptions = {}): Fixture {
  let current: Env | undefined;
  let logger = quietLogger();
  let hubs: BridgeHub[] = [];
  const f: Fixture = {
    get e(): Env {
      if (current === undefined) throw new Error('the fixture is only valid inside a test');
      return current;
    },
    get hubs() {
      return hubs;
    },
    get logger() {
      return logger;
    },
    hub(extra = {}) {
      const h = createBridge({ endpointFile: f.e.endpointFile, credentials: f.e.credentials, logger, reconnect: { initialDelayMs: 20, maxDelayMs: 100 }, ...extra });
      hubs.push(h);
      return h;
    },
    async attached(bot = 'Alice', h = f.hub()) {
      const fake = typeof bot === 'string' ? new FakeBot(bot) : bot;
      const b = load(h.plugin(), fake);
      f.e.mod.setPlayerContext(OVERWORLD);
      await until(() => b.state.attached && b.state.worldMatched, 5000, `attach ${fake.username ?? 'bot'}`);
      return b;
    },
  };
  beforeEach(async () => {
    current = await env(options);
    logger = quietLogger();
    hubs = [];
    bind(f);
  });
  afterEach(async () => {
    for (const h of hubs) await h.close();
    if (current !== undefined) await teardown(current);
    current = undefined;
  });
  return f;
}
