import {
  applyPatch,
  BridgeError,
  decodeKind,
  diffPatch,
  Flags,
  Kinds,
  Messages,
  type Codec,
  type EnvelopeHeader,
  type KindName,
  type KindPayloads,
  type MessagePayloads,
  type ObjectWrite,
  type Scope,
  type WorldKey,
} from '@mineflayer-bridge/protocol';
import type { ModCore } from './core.ts';
import { Pending } from './pending.ts';
import type { Session } from './session.ts';
import { fromFull, kindNameOf, toFull, toMockObject, type StoredObject } from './store.ts';
import type { MockEditAnswer, MockObject, MockPendingEdit, MockProposeOptions, MockSpatial, MockSpatialKind } from './types.ts';
import { encode, toBridgeError, worldsMatch } from './util.ts';

/** The first-party namespace of shared zones, points and routes. */
const SPATIAL_PREFIX = 'shared/bridge:zones/';
const EDIT_TIMEOUT_MS = Messages['obj.edit.propose'].timeoutMs ?? 10_000;
/** Shared zones, points and routes: without `editable` traits every field is offered. */
const SPATIAL_KINDS: ReadonlySet<string> = new Set(['core:zone', 'core:point', 'core:route']);

/**
 * The player can only edit the fields an object's `editable` traits list.
 * @throws BridgeError `invalid_message` for a field the client would not offer.
 */
function checkEditable(o: MockObject, fields: readonly string[]): void {
  const listed = o.traits?.editable?.map((f) => f.field);
  if (listed === undefined) {
    if (SPATIAL_KINDS.has(o.kind)) return;
    throw new BridgeError('invalid_message', `mock: ${o.key} has no editable fields`);
  }
  for (const f of fields) if (!listed.includes(f)) throw new BridgeError('invalid_message', `mock: field ${f} of ${o.key} is not editable`);
}

type PutOptions = { scope?: Scope; world?: WorldKey; layer?: string };

/** Shared objects, the spatial listings, and player edit proposals. */
export class SharedObjects {
  private readonly edits = new Pending<MockEditAnswer, Omit<MockPendingEdit, 'owner'>>();

  constructor(private readonly core: ModCore) {}

  put<K extends KindName>(key: string, kind: K, data: KindPayloads[K], options: PutOptions): number {
    if (!key.startsWith('shared/')) throw new BridgeError('not_owner', `shared keys start with shared/: ${key}`);
    const k = Kinds[kind];
    const prev = this.core.store.get(key);
    const o: StoredObject = {
      key,
      kind: k.id,
      kindName: kind,
      rev: (prev?.rev ?? 0) + 1,
      layer: options.layer,
      lifetime: 'persistent',
      scope: options.scope ?? (options.world === undefined ? 'global' : 'world'),
      world: options.world,
      ttlMs: undefined,
      expiresAt: undefined,
      data: encode(k.codec as Codec<KindPayloads[K]>, data),
      traits: prev?.traits,
    };
    this.core.store.map.set(key, o);
    this.core.emit('objectChanged', { key, object: toMockObject(o), write: 'shared' });
    this.broadcast(o, undefined);
    return o.rev;
  }

  playerCreate<K extends MockSpatialKind>(kind: K, data: KindPayloads[K], options: { id?: string; world?: WorldKey }): { key: string; rev: number } {
    const key = `${SPATIAL_PREFIX}${options.id ?? crypto.randomUUID()}`;
    const prev = this.core.store.get(key);
    if (prev !== undefined) throw new BridgeError('conflict', `${key} exists`, { details: { rev: prev.rev } });
    const o: PutOptions = {};
    if (options.world !== undefined) o.world = options.world;
    return { key, rev: this.put(key, kind, data, o) };
  }

  playerEdit<K extends KindName>(key: string, patch: Partial<KindPayloads[K]>, options: { expectRev?: number }): number {
    const prev = this.core.store.get(key);
    if (prev === undefined || !key.startsWith('shared/') || !Object.hasOwn(Kinds, prev.kindName)) throw new BridgeError('invalid_message', `no shared core object at ${key}`);
    if (options.expectRev !== undefined && options.expectRev !== prev.rev) throw new BridgeError('conflict', `${key} is at another revision`, { details: { rev: prev.rev } });
    const kind = prev.kindName as K;
    const data = { ...(decodeKind(Kinds[kind], prev.data) as object), ...patch } as KindPayloads[K];
    const o: PutOptions = { scope: prev.scope };
    if (prev.world !== undefined) o.world = prev.world;
    if (prev.layer !== undefined) o.layer = prev.layer;
    return this.put(key, kind, data, o);
  }

  playerRemove(key: string): void {
    const o = this.core.store.get(key);
    if (o === undefined) return;
    this.core.store.map.delete(key);
    this.core.emit('objectRemoved', { key, reason: 'shared' });
    this.broadcast(undefined, o);
  }

  proposeEdit<K extends KindName>(key: string, patch: Partial<KindPayloads[K]>, options: MockProposeOptions): Promise<MockEditAnswer> {
    const o = this.core.store.one(key);
    if (o === undefined || !Object.hasOwn(Kinds, o.kind)) throw new BridgeError('invalid_message', `no core object at ${key}`);
    const s = this.core.liveOwner(key);
    const kind = o.kind as K;
    if (options.unchecked !== true) checkEditable(o, Object.keys(patch));
    const proposed = { ...(o.data as object), ...patch } as KindPayloads[K];
    const fields = options.fields ?? diffPatch(o.raw, encode(Kinds[kind].codec as Codec<KindPayloads[K]>, proposed));
    if (fields === null) throw new BridgeError('invalid_message', `patch for ${key} shrinks the data`);
    const req = this.core.nextRequest++;
    const baseRev = options.baseRev ?? o.rev;
    return this.edits.expect(
      req,
      s.owner,
      { key, baseRev, proposed },
      options.timeoutMs ?? EDIT_TIMEOUT_MS,
      () => new BridgeError('timeout', `mock: no obj.edit.result for ${key} within the deadline`),
      () => this.core.sendSession(s, 'obj.edit.propose', { key, baseRev, fields }, { req }),
    );
  }

  pendingEdits(): MockPendingEdit[] {
    return [...this.edits.values()].map((e) => ({ key: e.key, owner: e.owner, baseRev: e.baseRev, proposed: e.proposed }));
  }

  onEditResult(s: Session, header: EnvelopeHeader, p: MessagePayloads['obj.edit.result']): void {
    if (header.req === undefined || (header.flags & Flags.RESPONSE) === 0) return;
    this.edits.answer(header.req, s.owner, { accepted: p.accepted, reason: p.reason });
  }

  spatial<K extends MockSpatialKind>(kind: K): MockSpatial<K>[] {
    const out: MockSpatial<K>[] = [];
    for (const o of this.core.store.list('')) {
      if (o.kind !== kind) continue;
      const shared = o.key.startsWith('shared/');
      const owner = shared ? 'shared' : (this.ownerOf(o.key) ?? o.key.slice(0, o.key.indexOf('/')));
      out.push({ key: o.key, owner, shared, rev: o.rev, layer: o.layer, world: o.world, data: o.data as KindPayloads[K] });
    }
    return out;
  }

  subscribe(s: Session, p: MessagePayloads['shared.subscribe']): void {
    s.subscription = { kinds: p.kinds, extensions: p.extensions };
    const writes: ObjectWrite[] = [];
    for (const o of this.core.store.map.values()) if (this.visibleTo(s, o)) writes.push({ type: 'full', value: toFull(o) });
    for (let i = 0; i < writes.length; i += 256) this.core.sendSession(s, 'obj.upsert', { writes: writes.slice(i, i + 256) });
  }

  /** A bot's `shared.op`: create, update with `expectRev`, or delete with `expectRev`. */
  op(s: Session, header: EnvelopeHeader, p: MessagePayloads['shared.op']): void {
    const relatesTo = Messages['shared.op'].id;
    const fail = (code: string, message: string, details?: unknown): void => this.core.sendSessionError(s, code, message, header, relatesTo, details);
    if (header.req === undefined) return fail('invalid_message', 'shared.op needs a request id');
    if (s.superseded) return fail('superseded', 'session is read-only');
    const op = p.op;
    const key = op.type === 'create' ? op.value.object.key : op.value.key;
    if (!key.startsWith('shared/')) return fail('not_owner', `shared.op on non-shared key ${key}`);
    const prev = this.core.store.get(key);
    let next: StoredObject | undefined;
    if (op.type === 'create') {
      if (prev !== undefined) return fail('conflict', `${key} exists`, { rev: prev.rev });
      const v = op.value.object;
      const kindName = kindNameOf(s, v.kind);
      if (kindName === undefined) return fail('unknown_kind', `kind ${v.kind} is not registered`);
      next = fromFull(v, kindName, this.core.clock.now(), { rev: 1, lifetime: 'persistent' });
      this.core.store.map.set(key, next);
    } else {
      if (prev === undefined || prev.rev !== op.value.expectRev) return fail('conflict', `${key} is at another revision`, { rev: prev?.rev ?? 0 });
      if (op.type === 'update') {
        try {
          prev.data = applyPatch(prev.data, op.value.fields);
        } catch (e) {
          return fail('invalid_message', toBridgeError(e).message);
        }
        prev.rev += 1;
        next = prev;
      } else {
        this.core.store.map.delete(key);
      }
    }
    this.core.sendSession(s, 'shared.op.result', { key, rev: next?.rev ?? (prev?.rev ?? 0) + 1 }, { req: header.req, response: true });
    if (next !== undefined) {
      this.core.emit('objectChanged', { key, object: toMockObject(next), write: 'shared' });
      this.broadcast(next, undefined);
    } else {
      this.core.emit('objectRemoved', { key, reason: 'shared' });
      this.broadcast(undefined, prev);
    }
  }

  /** Sends a changed shared object, or a removed one, to every subscriber that can see it. */
  broadcast(next: StoredObject | undefined, removed: StoredObject | undefined): void {
    for (const s of this.core.sessions.values()) {
      if (s.closed) continue;
      if (next !== undefined && this.visibleTo(s, next)) {
        this.core.sendSession(s, 'obj.upsert', { writes: [{ type: 'full', value: toFull(next) }] });
      } else if (removed !== undefined && this.visibleTo(s, removed)) {
        this.core.sendSession(s, 'obj.remove', { keys: [removed.key], prefixes: [] });
      }
    }
  }

  clear(): void {
    this.edits.clear();
  }

  private visibleTo(s: Session, o: StoredObject): boolean {
    if (!o.key.startsWith('shared/') || s.subscription === undefined || s.superseded) return false;
    const { kinds, extensions } = s.subscription;
    if (kinds.length > 0 && !kinds.includes(o.kindName)) return false;
    if (extensions.length > 0) {
      const ext = o.key.slice('shared/'.length).split('/')[0] ?? '';
      if (!extensions.includes(ext)) return false;
    }
    // World-scoped objects go only to sessions in that world.
    if (o.scope === 'world') return s.world !== undefined && o.world !== undefined && worldsMatch(s.world, o.world);
    return true;
  }

  /** Owner key of the session prefix holding `key`, if a session is known. */
  private ownerOf(key: string): string | undefined {
    for (const s of this.core.sessions.values()) if (key.startsWith(s.prefix)) return s.owner;
    return undefined;
  }
}
