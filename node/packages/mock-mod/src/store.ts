import {
  applyPatch,
  decodeKind,
  Kinds,
  KindsById,
  Messages,
  type EnvelopeHeader,
  type KindName,
  type KindPayloads,
  type Lifetime,
  type MessagePayloads,
  type ObjectFull,
  type ObjectTraits,
  type ObjectWrite,
  type Scope,
  type WorldKey,
} from '@mineflayer-bridge/protocol';
import type { ModCore } from './core.ts';
import { kindPermission } from './devices.ts';
import type { Session } from './session.ts';
import type { MockDeclaration, MockHudPanel, MockHudWidget, MockModEvents, MockObject, MockPlaced } from './types.ts';
import { byKey, isDisplay, toBridgeError } from './util.ts';

/** An object as the mod holds it: envelope fields plus the encoded data. */
export interface StoredObject {
  key: string;
  kind: number;
  kindName: string;
  rev: number;
  layer: string | undefined;
  lifetime: Lifetime;
  scope: Scope;
  world: WorldKey | undefined;
  ttlMs: number | undefined;
  /** Clock ms when the TTL ends, counted from the last write. */
  expiresAt: number | undefined;
  data: Uint8Array;
  /** Object traits; a patch with traits replaces them. */
  traits: ObjectTraits | undefined;
}

type RemoveReason = MockModEvents['objectRemoved'][0]['reason'];

/** An open transaction older than this is discarded, in clock ms. */
export const TX_TIMEOUT_MS = 10_000;

/** A stored object from a full write received at clock time `now`. */
export function fromFull(v: ObjectFull, kindName: string, now: number, fixed: { rev?: number; lifetime?: Lifetime } = {}): StoredObject {
  return {
    key: v.key,
    kind: v.kind,
    kindName,
    rev: fixed.rev ?? v.rev,
    layer: v.layer,
    lifetime: fixed.lifetime ?? v.lifetime ?? 'session',
    scope: v.scope ?? 'global',
    world: v.world,
    ttlMs: v.ttlMs,
    expiresAt: v.ttlMs === undefined ? undefined : now + v.ttlMs,
    data: v.data,
    traits: v.traits,
  };
}

export function toFull(o: StoredObject): ObjectFull {
  return { key: o.key, kind: o.kind, rev: o.rev, layer: o.layer, lifetime: o.lifetime, scope: o.scope, world: o.world, ttlMs: o.ttlMs, data: o.data, traits: o.traits };
}

export function toMockObject(o: StoredObject): MockObject {
  const core = KindsById.get(o.kind);
  return {
    key: o.key,
    kind: o.kindName,
    kindId: o.kind,
    rev: o.rev,
    layer: o.layer,
    lifetime: o.lifetime,
    scope: o.scope,
    world: o.world,
    ttlMs: o.ttlMs,
    data: core === undefined ? o.data : decodeKind(core, o.data),
    raw: o.data,
    traits: o.traits,
  };
}

/** The name of kind id `kind`: a core kind, or an extension kind registered on the session's connection. */
export function kindNameOf(s: Session, kind: number): string | undefined {
  const core = KindsById.get(kind);
  if (core !== undefined) return core.name;
  const name = s.conn?.namesById.get(kind);
  return name !== undefined && s.conn?.kindNames.has(name) === true ? name : undefined;
}

/**
 * Splits `<owner>/<extension>/<layer>/<shape id>`. The owner may contain `/` (controllers), so the
 * layer is the first `/<layer>/` whose preceding segment is an extension id (it has a `:`).
 */
function splitShapeKey(key: string, layer: string): { owner: string; extension: string; id: string } | undefined {
  const marker = `/${layer}/`;
  for (let at = key.indexOf(marker); at >= 0; at = key.indexOf(marker, at + 1)) {
    const head = key.slice(0, at);
    const cut = head.lastIndexOf('/');
    if (cut > 0 && head.slice(cut + 1).includes(':')) return { owner: head.slice(0, cut), extension: head.slice(cut + 1), id: key.slice(at + marker.length) };
  }
  return undefined;
}

/** The object store: bot writes, snapshots, quotas, TTLs and the inspector listings over them. */
export class ObjectStore {
  readonly map = new Map<string, StoredObject>();

  constructor(private readonly core: ModCore) {}

  get(key: string): StoredObject | undefined {
    return this.map.get(key);
  }

  /** The kind name at `key`, if any. */
  kindAt(key: string): string | undefined {
    return this.map.get(key)?.kindName;
  }

  /** Decoded data of the core-kind object at `key` when it is of `kind`. */
  decoded<K extends KindName>(key: string, kind: K): KindPayloads[K] | undefined {
    const o = this.map.get(key);
    return o?.kindName === kind ? decodeKind(Kinds[kind], o.data) : undefined;
  }

  list(prefix: string): MockObject[] {
    this.core.sweep();
    const out: MockObject[] = [];
    for (const o of this.map.values()) if (o.key.startsWith(prefix)) out.push(toMockObject(o));
    return out.sort(byKey);
  }

  one(key: string): MockObject | undefined {
    this.core.sweep();
    const o = this.map.get(key);
    return o === undefined ? undefined : toMockObject(o);
  }

  /** Decoded data of the live core-kind object at `key` when it is of `kind`, after expiring TTLs. */
  data<K extends KindName>(key: string, kind: K): KindPayloads[K] | undefined {
    const o = this.one(key);
    return o === undefined || o.kind !== kind ? undefined : (o.data as KindPayloads[K]);
  }

  /** Declarations of `kind` stored under `<owner>/<extension>/<dir>`, sorted by key. */
  declarations<K extends KindName>(kind: K, dir: string): MockDeclaration<K>[] {
    this.core.sweep();
    const out: MockDeclaration<K>[] = [];
    for (const o of this.map.values()) {
      if (o.kindName !== kind) continue;
      const at = o.key.indexOf(`/${dir}`);
      if (at < 0) continue;
      const head = o.key.slice(0, at);
      const cut = head.lastIndexOf('/');
      out.push({
        key: o.key,
        owner: head.slice(0, cut),
        extension: head.slice(cut + 1),
        id: o.key.slice(at + dir.length + 1),
        scope: o.scope,
        world: o.world,
        data: decodeKind(Kinds[kind], o.data) as KindPayloads[K],
      });
    }
    return out.sort(byKey);
  }

  /** Layer objects whose kind matches, with the key split into owner, extension, layer and shape id. */
  placed(match: (kind: string) => boolean): MockPlaced[] {
    this.core.sweep();
    const out: MockPlaced[] = [];
    for (const o of this.map.values()) {
      if (!match(o.kindName) || o.layer === undefined) continue;
      const parts = splitShapeKey(o.key, o.layer);
      if (parts === undefined) continue;
      out.push({ key: o.key, ...parts, layer: o.layer, kind: o.kindName, scope: o.scope, world: o.world, data: decodeKind(KindsById.get(o.kind)!, o.data), traits: o.traits });
    }
    return out.sort(byKey);
  }

  hudPanels(): MockHudPanel[] {
    const widgets = new Map<string, MockHudWidget[]>();
    for (const o of this.map.values()) {
      if (!o.kindName.startsWith('core:hud.') || o.kindName === 'core:hud.panel') continue;
      const cut = o.key.lastIndexOf('/');
      const data = decodeKind(KindsById.get(o.kind)!, o.data) as { order?: number };
      const list = widgets.get(o.key.slice(0, cut)) ?? [];
      list.push({ key: o.key, id: o.key.slice(cut + 1), kind: o.kindName, order: data.order ?? 0, data });
      widgets.set(o.key.slice(0, cut), list);
    }
    return this.declarations('core:hud.panel', '~hud/').map((p) => ({
      ...p,
      widgets: (widgets.get(p.key) ?? []).sort((a, b) => a.order - b.order || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0)),
    }));
  }

  upsert(s: Session, writes: ObjectWrite[]): void {
    const relatesTo = Messages['obj.upsert'].id;
    if (!this.core.writable(s, undefined, relatesTo)) return;
    const now = this.core.clock.now();
    const fail = (code: string, message: string, key: string): void => this.core.sendSessionError(s, code, message, undefined, relatesTo, { key });
    for (const w of writes) {
      const key = w.value.key;
      if (!key.startsWith(s.prefix)) {
        fail('not_owner', `${key} is outside ${s.prefix}`, key);
        continue;
      }
      s.snapshot?.add(key);
      const prev = this.map.get(key);
      if (w.type === 'full') {
        const v = w.value;
        const kindName = kindNameOf(s, v.kind);
        if (kindName === undefined) {
          fail('unknown_kind', `kind ${v.kind} is not registered`, key);
          continue;
        }
        // A full write must raise the revision, which makes replays safe.
        if (prev !== undefined && v.rev <= prev.rev) continue;
        if (!this.permitted(s, kindName, relatesTo, key)) continue;
        const quota = this.quotaExceeded(s, kindName, prev, v.traits?.interactive !== undefined);
        if (quota !== undefined) {
          fail('quota_exceeded', `${key}: ${quota}`, key);
          continue;
        }
        const core = KindsById.get(v.kind);
        if (core !== undefined) {
          try {
            decodeKind(core, v.data);
          } catch (e) {
            fail('invalid_message', `${key}: ${toBridgeError(e).message}`, key);
            continue;
          }
        }
        const o = fromFull(v, kindName, now);
        this.map.set(key, o);
        this.core.emit('objectChanged', { key, object: toMockObject(o), write: 'full' });
        if (o.kindName === 'core:layer' || prev?.kindName === 'core:layer') this.core.player.refresh(s);
        if (o.kindName === 'core:settings') this.core.controls.sendSettings(s, key);
        if (prev?.kindName === 'core:tool' && o.kindName !== 'core:tool') this.core.forgetDeclaration(key);
      } else {
        const v = w.value;
        if (prev === undefined) {
          fail('invalid_message', `patch to missing key ${key}`, key);
          continue;
        }
        if (v.rev <= prev.rev) continue;
        if (!this.permitted(s, prev.kindName, relatesTo, key)) continue;
        let data: Uint8Array;
        try {
          data = applyPatch(prev.data, v.fields);
          const core = KindsById.get(prev.kind);
          if (core !== undefined) decodeKind(core, data);
        } catch (e) {
          fail('invalid_message', `${key}: ${toBridgeError(e).message}`, key);
          continue;
        }
        prev.data = data;
        prev.rev = v.rev;
        if (v.ttlMs !== undefined) prev.ttlMs = v.ttlMs;
        if (v.traits !== undefined) prev.traits = v.traits;
        // TTL is refreshed by any write, counted from receipt.
        prev.expiresAt = prev.ttlMs === undefined ? undefined : now + prev.ttlMs;
        this.core.emit('objectChanged', { key, object: toMockObject(prev), write: 'patch' });
        if (prev.kindName === 'core:layer') this.core.player.refresh(s);
      }
    }
  }

  remove(s: Session, p: MessagePayloads['obj.remove']): void {
    const relatesTo = Messages['obj.remove'].id;
    if (!this.core.writable(s, undefined, relatesTo)) return;
    let layerChanged = false;
    const drop = (key: string): void => {
      if (this.drop(key, 'remove')?.kindName === 'core:layer') layerChanged = true;
    };
    for (const key of p.keys) {
      if (!key.startsWith(s.prefix)) {
        this.core.sendSessionError(s, 'not_owner', `${key} is outside ${s.prefix}`, undefined, relatesTo, { key });
        continue;
      }
      drop(key);
    }
    for (const prefix of p.prefixes) {
      if (!prefix.startsWith(s.prefix)) {
        this.core.sendSessionError(s, 'not_owner', `prefix ${prefix} is outside ${s.prefix}`, undefined, relatesTo, { prefix });
        continue;
      }
      for (const key of [...this.map.keys()]) if (key.startsWith(prefix)) drop(key);
    }
    if (layerChanged) this.core.player.refresh(s);
  }

  /** Starts buffering the session's writes. A second `obj.begin` discards the open transaction first. */
  beginTx(s: Session, tx: number): void {
    if (s.tx !== undefined) this.discardTx(s, 'replaced');
    s.tx = { id: tx, startedAt: this.core.clock.now(), writes: [] };
  }

  /** Applies every buffered write in order, so nothing in the transaction is visible before the rest. */
  commitTx(s: Session, tx: number, header: EnvelopeHeader): void {
    const t = s.tx;
    if (t === undefined || t.id !== tx) {
      this.core.sendSessionError(s, 'invalid_message', `obj.commit ${tx} has no open transaction`, header);
      return;
    }
    s.tx = undefined;
    for (const w of t.writes) {
      if (w.type === 'upsert') this.upsert(s, w.payload.writes);
      else this.remove(s, w.payload);
    }
    this.core.emit('transaction', { session: this.core.info(s), tx, outcome: 'committed', writes: t.writes.length, reason: undefined });
  }

  /** Drops the open transaction without applying it. */
  discardTx(s: Session, reason: 'replaced' | 'timeout' | 'session_end'): void {
    const t = s.tx;
    if (t === undefined) return;
    s.tx = undefined;
    this.core.emit('transaction', { session: this.core.info(s), tx: t.id, outcome: 'discarded', writes: t.writes.length, reason });
  }

  /** Everything under the owned prefix not in the snapshot goes. */
  endSnapshot(s: Session): void {
    const written = s.snapshot ?? new Set<string>();
    s.snapshot = undefined;
    const removed: string[] = [];
    for (const key of [...this.map.keys()]) {
      if (key.startsWith(s.prefix) && !written.has(key)) {
        removed.push(key);
        this.drop(key, 'snapshot');
      }
    }
    this.core.emit('snapshotEnd', { session: this.core.info(s), removed });
    this.core.player.refresh(s);
  }

  /** Drops the objects under the session's prefix once no other open session has the same owner. */
  dropOwned(s: Session): void {
    for (const other of this.core.sessions.values()) if (other.owner === s.owner && !other.closed) return;
    for (const key of [...this.map.keys()]) if (key.startsWith(s.prefix)) this.drop(key, 'expired');
  }

  /** Removes objects whose TTL ended; shared ones are removed from subscribers too. */
  sweep(now: number): void {
    for (const o of [...this.map.values()]) {
      if (o.expiresAt !== undefined && o.expiresAt <= now) {
        this.map.delete(o.key);
        this.core.emit('objectRemoved', { key: o.key, reason: 'ttl' });
        if (o.key.startsWith('shared/')) this.core.shared.broadcast(undefined, o);
      }
    }
  }

  /** Deletes a bot-owned object and releases what its declaration held. Returns the removed object. */
  private drop(key: string, reason: RemoveReason): StoredObject | undefined {
    const o = this.map.get(key);
    if (o === undefined) return undefined;
    this.map.delete(key);
    this.core.emit('objectRemoved', { key, reason });
    this.core.forgetDeclaration(key);
    return o;
  }

  private permitted(s: Session, kindName: string, relatesTo: number, key: string): boolean {
    const permission = kindPermission(kindName);
    if (permission === undefined || this.core.devices.granted(s, permission)) return true;
    this.core.devices.deny(s, permission, 'obj.upsert', undefined, relatesTo, key);
    return false;
  }

  /**
   * Checks a write against the quotas. Objects are counted per device, over every session it holds;
   * the classes the written kind belongs to are counted per session.
   */
  private quotaExceeded(s: Session, kind: string, prev: StoredObject | undefined, interactive: boolean): string | undefined {
    const q = this.core.opts.quotas ?? {};
    const own = (): StoredObject[] => [...this.map.values()].filter((o) => o.key.startsWith(s.prefix));
    if (prev === undefined) {
      const prefixes = [...this.core.sessions.values()].filter((x) => x.deviceId === s.deviceId).map((x) => x.prefix);
      let held = 0;
      for (const o of this.map.values()) if (prefixes.some((p) => o.key.startsWith(p))) held++;
      if (held >= (q.objects ?? 20_000)) return `object quota of ${q.objects ?? 20_000} reached`;
    }
    if (interactive && prev?.traits?.interactive === undefined && own().filter((o) => o.traits?.interactive !== undefined).length >= (q.interactive ?? 1024)) {
      return `the session already has ${q.interactive ?? 1024} interactive objects`;
    }
    const classes: [string, (k: string) => boolean, number][] = [
      ['displays', isDisplay, q.displays ?? 4096],
      ['entity displays', (k) => k === 'core:display.entity', q.entityDisplays ?? 256],
      ['particle emitters', (k) => k === 'core:particle-emitter', q.emitters ?? 32],
    ];
    for (const [what, inClass, limit] of classes) {
      if (!inClass(kind) || (prev !== undefined && inClass(prev.kindName))) continue;
      let n = 0;
      for (const o of this.map.values()) if (inClass(o.kindName) && o.key.startsWith(s.prefix)) n++;
      if (n >= limit) return `the session already has ${limit} ${what}`;
    }
    return undefined;
  }
}
