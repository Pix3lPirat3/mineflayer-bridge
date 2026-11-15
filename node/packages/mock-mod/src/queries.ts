import type { EnvelopeHeader, MessagePayloads, Vec3d } from '@mineflayer-bridge/protocol';
import type { ModCore } from './core.ts';
import type { Session } from './session.ts';

/** One entity of the mock's loaded world. */
export interface MockEntity {
  id: number;
  type: string;
  pos: Vec3d;
  name?: string;
  player?: boolean;
}

/** One non-air block of the mock's loaded world. */
export interface MockBlock {
  x: number;
  y: number;
  z: number;
  name: string;
}

/** What the mock's player has loaded, for client queries. */
export interface MockLoadedWorld {
  /** The player's position, the default query centre. */
  player?: Vec3d;
  entities?: readonly MockEntity[];
  blocks?: readonly MockBlock[];
  /** Whether a column is loaded; every column by default. */
  loaded?: (x: number, z: number) => boolean;
}

const MAX_RADIUS = 128;
const MAX_ENTITIES = 1024;
const MAX_VOLUME = 65_536;
const MAX_BLOCKS = 65_536;

/** Client queries over a world the test sets, with the Java core's checks and limits. */
export class Queries {
  world: MockLoadedWorld = {};

  constructor(private readonly core: ModCore) {}

  private check(s: Session, header: EnvelopeHeader): boolean {
    const { attached, worldMatched } = this.core.player.attachState(s);
    if (!attached) this.core.sendSessionError(s, 'not_attached', `the player has not attached ${s.name}`, header, header.type);
    else if (!worldMatched) this.core.sendSessionError(s, 'world_mismatch', "the player is not in this bot's world", header, header.type);
    return attached && worldMatched;
  }

  onEntities(s: Session, header: EnvelopeHeader, q: MessagePayloads['query.entities']): void {
    if (!this.check(s, header)) return;
    const radius = q.radius ?? 32;
    if (!(radius > 0) || !Number.isFinite(radius)) return this.core.sendSessionError(s, 'invalid_message', 'query radius must be a positive number', header, header.type);
    const center = q.center ?? this.world.player;
    if (center === undefined) return this.core.sendSessionError(s, 'not_attached', "the player's position is not known", header, header.type);
    const r = Math.min(radius, MAX_RADIUS);
    const limit = Math.min(q.limit ?? 256, MAX_ENTITIES);
    const types = q.types === undefined ? undefined : new Set(q.types);
    const d2 = (e: MockEntity): number => (e.pos.x - center.x) ** 2 + (e.pos.y - center.y) ** 2 + (e.pos.z - center.z) ** 2;
    const entities = (this.world.entities ?? [])
      .filter((e) => d2(e) <= r * r && (types === undefined || types.has(e.type)))
      .sort((a, b) => d2(a) - d2(b))
      .slice(0, limit)
      .map((e) => ({ id: e.id, type: e.type, pos: e.pos, ...(e.name === undefined ? {} : { name: e.name }), player: e.player === true }));
    this.core.sendSession(s, 'query.entities.result', { entities }, { req: header.req ?? 0, response: true });
  }

  onBlocks(s: Session, header: EnvelopeHeader, q: MessagePayloads['query.blocks']): void {
    if (!this.check(s, header)) return;
    const min = { x: Math.min(q.min.x, q.max.x), y: Math.min(q.min.y, q.max.y), z: Math.min(q.min.z, q.max.z) };
    const max = { x: Math.max(q.min.x, q.max.x), y: Math.max(q.min.y, q.max.y), z: Math.max(q.min.z, q.max.z) };
    const volume = (max.x - min.x + 1) * (max.y - min.y + 1) * (max.z - min.z + 1);
    if (volume > MAX_VOLUME) return this.core.sendSessionError(s, 'invalid_message', `query box of ${volume} blocks is over ${MAX_VOLUME}`, header, header.type);
    const limit = Math.min(q.limit ?? 4096, MAX_BLOCKS);
    const wanted = q.blocks === undefined ? undefined : new Set(q.blocks);
    const loaded = this.world.loaded ?? (() => true);
    let complete = true;
    for (let x = min.x; x <= max.x && complete; x++) for (let z = min.z; z <= max.z && complete; z++) if (!loaded(x, z)) complete = false;
    const inside = (this.world.blocks ?? [])
      .filter((b) => b.x >= min.x && b.x <= max.x && b.y >= min.y && b.y <= max.y && b.z >= min.z && b.z <= max.z)
      .filter((b) => loaded(b.x, b.z) && (wanted === undefined || wanted.has(b.name)))
      // The Java core walks x, then z, then y.
      .sort((a, b) => a.x - b.x || a.z - b.z || a.y - b.y);
    if (inside.length > limit) complete = false;
    const palette: string[] = [];
    const index = new Map<string, number>();
    const positions: number[] = [];
    const ids: number[] = [];
    for (const b of inside.slice(0, limit)) {
      let i = index.get(b.name);
      if (i === undefined) {
        i = palette.length;
        palette.push(b.name);
        index.set(b.name, i);
      }
      positions.push(b.x, b.y, b.z);
      ids.push(i);
    }
    this.core.sendSession(s, 'query.blocks.result', { positions: Int32Array.from(positions), palette, ids: Uint16Array.from(ids), complete }, { req: header.req ?? 0, response: true });
  }
}
