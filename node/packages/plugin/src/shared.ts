import {
  applyPatch,
  BridgeError,
  decodeKind,
  encodeKind,
  encodePatchFields,
  Kinds,
  KindsById,
  Messages,
  splitArray,
  type KindName,
  type KindPayloads,
  type MessagePayloads,
  type ObjectWrite,
  type WorldKey,
} from '@mineflayer-bridge/protocol';
import type { Link } from './link.ts';
import type { CoreSet } from './players.ts';
import { SPATIAL_KINDS, SpatialIndex, spatialViews } from './spatial.ts';
import type { SharedPoints, SharedRoutes, SharedZones } from './spatial-types.ts';
import type { SharedApi, SharedCreateOptions, SharedFilter, SharedObject } from './types.ts';
import { Emitter } from './util.ts';

const MAX_TIMEOUT_MS = Messages['shared.op'].timeoutMs ?? 10_000;

function decodeData(kind: number, data: Uint8Array): unknown {
  const core = KindsById.get(kind);
  if (core === undefined) return data;
  try {
    return decodeKind(core, data);
  } catch {
    return data;
  }
}

/** The session-wide view of shared objects the mod broadcast to this session. */
export class SharedState {
  readonly objects = new Map<string, SharedObject & { kindId: number }>();
  readonly events = new Emitter<{ change: [SharedObject]; remove: [string] }>();

  private spatialIndex: SpatialIndex | undefined;

  constructor(private readonly link: Pick<Link, 'kindName'>) {}

  /** The local index of shared spatial definitions, built on first use. */
  get spatial(): SpatialIndex {
    return (this.spatialIndex ??= new SpatialIndex(this));
  }

  upsert(writes: ObjectWrite[]): void {
    for (const w of writes) {
      if (w.type === 'full') {
        const v = w.value;
        const o = { key: v.key, kind: this.link.kindName(v.kind), kindId: v.kind, rev: v.rev, layer: v.layer, scope: v.scope ?? 'global', world: v.world, data: decodeData(v.kind, v.data), raw: v.data };
        this.objects.set(v.key, o);
        this.events.emit('change', o);
      } else {
        const prev = this.objects.get(w.value.key);
        if (prev === undefined) continue;
        const raw = applyPatch(prev.raw, w.value.fields);
        const o = { ...prev, rev: w.value.rev, raw, data: decodeData(prev.kindId, raw) };
        this.objects.set(o.key, o);
        this.events.emit('change', o);
      }
    }
  }

  remove(p: MessagePayloads['obj.remove']): void {
    for (const k of p.keys) if (this.objects.delete(k)) this.events.emit('remove', k);
    for (const prefix of p.prefixes) {
      for (const k of [...this.objects.keys()]) {
        if (!k.startsWith(prefix)) continue;
        this.objects.delete(k);
        this.events.emit('remove', k);
      }
    }
  }

  reset(): void {
    for (const k of [...this.objects.keys()]) {
      this.objects.delete(k);
      this.events.emit('remove', k);
    }
  }
}

/** {@link SharedApi} bound to one scope; `signal` cancels pending ops. */
export class SharedApiImpl implements SharedApi {
  readonly zones: SharedZones;
  readonly points: SharedPoints;
  readonly routes: SharedRoutes;

  constructor(
    private readonly state: SharedState,
    private readonly core: Pick<CoreSet, 'subscribe' | 'requestPrimary'>,
    private readonly link: Pick<Link, 'kindId'>,
    private readonly scope: string,
    private readonly signal: AbortSignal | undefined,
    world: () => WorldKey | undefined,
  ) {
    ({ zones: this.zones, points: this.points, routes: this.routes } = spatialViews({
      get index() {
        return state.spatial;
      },
      // The views cover the spatial kinds; the index keeps only `shared/bridge:zones/` keys.
      subscribe: () => core.subscribe(SPATIAL_KINDS, []),
      create: (key, kind, data, options) => this.create(key, kind, data, options),
      op: (body, timeoutMs) => this.op(body, timeoutMs),
      world,
      signal,
    }));
  }

  subscribe(filter: SharedFilter = {}): void {
    this.core.subscribe(filter.kinds ?? [], filter.extensions ?? []);
  }

  get(key: string): SharedObject | undefined {
    const o = this.state.objects.get(key);
    return o === undefined ? undefined : publicView(o);
  }

  list(filter: { prefix?: string; kind?: string } = {}): SharedObject[] {
    const out: SharedObject[] = [];
    for (const o of this.state.objects.values()) {
      if (filter.prefix !== undefined && !o.key.startsWith(filter.prefix)) continue;
      if (filter.kind !== undefined && o.kind !== filter.kind) continue;
      out.push(publicView(o));
    }
    return out;
  }

  private key(key: string): string {
    if (key === '') throw new BridgeError('invalid_message', 'shared key must not be empty');
    return key.startsWith('shared/') ? key : `shared/${this.scope}/${key}`;
  }

  private encode(kind: string, data: unknown): { id: number; bytes: Uint8Array } {
    const id = this.link.kindId(kind);
    if (id === undefined) throw new BridgeError('unknown_kind', `mod does not support kind ${kind}`);
    if (data instanceof Uint8Array) return { id, bytes: data };
    if (!Object.hasOwn(Kinds, kind)) throw new BridgeError('invalid_message', `data for non-core kind ${kind} must be pre-encoded bytes`);
    return { id, bytes: encodeKind(Kinds[kind as KindName] as (typeof Kinds)['core:box'], data as KindPayloads['core:box']) };
  }

  private op(body: MessagePayloads['shared.op']['op'], timeoutMs: number | undefined): Promise<MessagePayloads['shared.op.result']> {
    const t = Math.min(timeoutMs ?? MAX_TIMEOUT_MS, MAX_TIMEOUT_MS);
    return this.core.requestPrimary('shared.op', { op: body }, t, this.signal) as Promise<MessagePayloads['shared.op.result']>;
  }

  create<K extends KindName>(key: string, kind: K, data: KindPayloads[K], options?: SharedCreateOptions): Promise<{ key: string; rev: number }>;
  create(key: string, kind: string, data: Uint8Array, options?: SharedCreateOptions): Promise<{ key: string; rev: number }>;
  async create(key: string, kind: string, data: unknown, options: SharedCreateOptions = {}): Promise<{ key: string; rev: number }> {
    const k = this.key(key);
    const { id, bytes } = this.encode(kind, data);
    const r = await this.op(
      {
        type: 'create',
        value: { object: { key: k, kind: id, rev: 1, layer: options.layer, lifetime: 'persistent', scope: options.world === undefined ? 'global' : 'world', world: options.world, data: bytes } },
      },
      options.timeoutMs,
    );
    return { key: r.key, rev: r.rev };
  }

  update<K extends KindName>(key: string, kind: K, data: KindPayloads[K], expectRev: number, options?: { timeoutMs?: number }): Promise<{ key: string; rev: number }>;
  update(key: string, kind: string, data: Uint8Array, expectRev: number, options?: { timeoutMs?: number }): Promise<{ key: string; rev: number }>;
  async update(key: string, kind: string, data: unknown, expectRev: number, options: { timeoutMs?: number } = {}): Promise<{ key: string; rev: number }> {
    const k = this.key(key);
    const { bytes } = this.encode(kind, data);
    // The whole data goes as a patch of every field, so no local copy of the object is needed.
    const fields = encodePatchFields(new Map(splitArray(bytes).map((v, i) => [i, v] as const)));
    const r = await this.op({ type: 'update', value: { key: k, expectRev, fields } }, options.timeoutMs);
    return { key: r.key, rev: r.rev };
  }

  async remove(key: string, expectRev: number, options: { timeoutMs?: number } = {}): Promise<void> {
    await this.op({ type: 'remove', value: { key: this.key(key), expectRev } }, options.timeoutMs);
  }

  on(event: 'change', fn: (object: SharedObject) => void): () => void;
  on(event: 'remove', fn: (key: string) => void): () => void;
  on(event: 'change' | 'remove', fn: ((object: SharedObject) => void) | ((key: string) => void)): () => void {
    const off = event === 'change' ? this.state.events.on('change', fn as (o: SharedObject) => void) : this.state.events.on('remove', fn as (k: string) => void);
    this.signal?.addEventListener('abort', off, { once: true });
    return off;
  }
}

function publicView(o: SharedObject & { kindId: number }): SharedObject {
  return { key: o.key, kind: o.kind, rev: o.rev, layer: o.layer, scope: o.scope, world: o.world, data: o.data, raw: o.raw };
}
