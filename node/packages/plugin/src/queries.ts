import { BridgeError, type MessagePayloads } from '@mineflayer-bridge/protocol';
import type { Vec3Like } from './types.ts';
import { toVec3 } from './util.ts';

/** How long a query waits for the mod, ms. */
const QUERY_TIMEOUT_MS = 10_000;

/** One entity the player's client has loaded. */
export interface QueriedEntity {
  /** Client entity id, as `layer.entityTag` takes it. */
  readonly id: number;
  /** Entity type id, such as `minecraft:zombie`. */
  readonly type: string;
  readonly pos: { readonly x: number; readonly y: number; readonly z: number };
  /** Player name or custom name. */
  readonly name: string | undefined;
  readonly player: boolean;
}

/** One block the player's client has loaded. */
export interface QueriedBlock {
  readonly x: number;
  readonly y: number;
  readonly z: number;
  /** Block id, such as `minecraft:iron_ore`. */
  readonly name: string;
}

/** Options of {@link QueryApi.entities}. */
export interface QueryEntitiesOptions {
  /** Centre of the search; the player's position by default. */
  center?: Vec3Like;
  /** Radius in blocks. Default 32, at most 128. */
  radius?: number;
  /** Entity type ids to return; every type by default. */
  types?: readonly string[];
  /** Most entities to return, nearest first. Default 256, at most 1024. */
  limit?: number;
}

/** Options of {@link QueryApi.blocks}. */
export interface QueryBlocksOptions {
  /** Block ids to return; every non-air block by default. */
  blocks?: readonly string[];
  /** Most blocks to return. Default 4096, at most 65536. */
  limit?: number;
}

/** The answer of {@link QueryApi.blocks}. */
export interface QueryBlocksAnswer {
  readonly blocks: readonly QueriedBlock[];
  /** False when part of the box is not loaded on the client or the limit cut the answer. */
  readonly complete: boolean;
}

/**
 * Client queries: what the player's client has loaded, for a bot that is attached and in the
 * player's world. Needs the `client.query` permission, which remote devices do not hold by default.
 * Rejects with `permission_denied`, `not_attached`, `world_mismatch`, `unsupported` (an older mod)
 * or `invalid_message` (a box over 65536 blocks).
 */
export interface QueryApi {
  /** Loaded entities near a point, nearest first; the player's own entity is left out. */
  entities(options?: QueryEntitiesOptions): Promise<QueriedEntity[]>;
  /** Loaded blocks in the box between two corners, both inclusive. */
  blocks(from: Vec3Like, to: Vec3Like, options?: QueryBlocksOptions): Promise<QueryBlocksAnswer>;
}

/** What queries need from a session. */
export interface QueryHost {
  request<N extends 'query.entities' | 'query.blocks'>(type: N, payload: MessagePayloads[N], timeoutMs: number, signal?: AbortSignal): Promise<unknown>;
}

function limitOf(v: number | undefined, max: number): number | undefined {
  if (v === undefined) return undefined;
  if (!Number.isInteger(v) || v < 1) throw new BridgeError('invalid_message', `query limit must be a positive integer, got ${String(v)}`);
  return Math.min(v, max);
}

function blockPos(v: Vec3Like, what: string): { x: number; y: number; z: number } {
  const p = toVec3(v, what);
  return { x: Math.floor(p.x), y: Math.floor(p.y), z: Math.floor(p.z) };
}

/** Creates the query API of one session or extension scope; `signal` cancels its pending queries. */
export function createQueries(host: QueryHost, signal?: AbortSignal): QueryApi {
  return {
    async entities(o = {}) {
      const p: MessagePayloads['query.entities'] = {};
      if (o.center !== undefined) p.center = toVec3(o.center, 'center');
      if (o.radius !== undefined) {
        if (!Number.isFinite(o.radius) || o.radius <= 0) throw new BridgeError('invalid_message', `query radius must be a positive number, got ${String(o.radius)}`);
        p.radius = o.radius;
      }
      if (o.types !== undefined) p.types = [...o.types];
      const limit = limitOf(o.limit, 1024);
      if (limit !== undefined) p.limit = limit;
      const r = (await host.request('query.entities', p, QUERY_TIMEOUT_MS, signal)) as MessagePayloads['query.entities.result'];
      return r.entities.map((e) => ({ id: e.id, type: e.type, pos: { x: e.pos.x, y: e.pos.y, z: e.pos.z }, name: e.name, player: e.player === true }));
    },
    async blocks(from, to, o = {}) {
      const p: MessagePayloads['query.blocks'] = { min: blockPos(from, 'from'), max: blockPos(to, 'to') };
      if (o.blocks !== undefined) p.blocks = [...o.blocks];
      const limit = limitOf(o.limit, 65_536);
      if (limit !== undefined) p.limit = limit;
      const r = (await host.request('query.blocks', p, QUERY_TIMEOUT_MS, signal)) as MessagePayloads['query.blocks.result'];
      const blocks: QueriedBlock[] = [];
      for (let i = 0; i < r.ids.length; i++) {
        blocks.push({ x: r.positions[i * 3]!, y: r.positions[i * 3 + 1]!, z: r.positions[i * 3 + 2]!, name: r.palette[r.ids[i]!] ?? 'minecraft:air' });
      }
      return { blocks, complete: r.complete };
    },
  };
}
