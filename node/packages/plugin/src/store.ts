import { bytesEqual, diffPatch, type Lifetime, type ObjectTraits, type Scope, type WorldKey } from '@mineflayer-bridge/protocol';
import { BlobTable, type BlobData } from './blobs.ts';

/** One declared object. `key` is relative to the owner: `<extension scope>/<local id>`. */
export interface Declared {
  readonly key: string;
  readonly kind: string;
  readonly layer: string | undefined;
  readonly lifetime: Lifetime;
  readonly scope: Scope;
  readonly world: WorldKey | undefined;
  readonly ttlMs: number | undefined;
  readonly data: Uint8Array;
  readonly rev: number;
  /** Blob the data refers to by hash; kept in {@link DeclaredStore.blobs} while this object is declared. */
  readonly blob?: BlobData | undefined;
  /**
   * Action keys in it are owner-relative, like `key`; the session adds the owner when it sends
   * them.
   */
  readonly traits?: ObjectTraits | undefined;
}

export type DeclaredInput = Omit<Declared, 'rev'>;

/** A pending write produced by {@link DeclaredStore.drain}. */
/** Room left in a frame for the envelope and the fields around an object's data, in bytes. */
export const OBJECT_FRAME_OVERHEAD = 512;

export type StoreWrite = { type: 'full'; object: Declared } | { type: 'patch'; object: Declared; fields: Uint8Array; traits: ObjectTraits | undefined };

/** Changes since the previous drain, in the order they must be sent: removals first, then writes. */
export interface StoreChanges {
  keys: string[];
  prefixes: string[];
  writes: StoreWrite[];
}

/** Traits hold plain JSON data (no typed arrays), so their JSON text compares them. */
export function sameTraits(a: ObjectTraits | undefined, b: ObjectTraits | undefined): boolean {
  return a === b || (a !== undefined && b !== undefined && JSON.stringify(a) === JSON.stringify(b));
}

function sameMeta(a: DeclaredInput, b: DeclaredInput): boolean {
  return (
    a.kind === b.kind &&
    a.layer === b.layer &&
    a.lifetime === b.lifetime &&
    a.scope === b.scope &&
    a.world?.server === b.world?.server &&
    a.world?.backend === b.world?.backend &&
    a.world?.dimension === b.world?.dimension
  );
}

/**
 * What one mod session has been sent of a {@link DeclaredStore}, and what changed since. Each link
 * of a hub has its own cursor over the same declared objects, so every mod gets the same state,
 * each at its own pace.
 */
export class StoreCursor {
  /** What the mod holds as of the last drain, so later writes can become patches. */
  private readonly sent = new Map<string, Declared>();
  private readonly dirty = new Set<string>();
  private readonly removedKeys = new Set<string>();
  private removedPrefixes: string[] = [];
  /** Called whenever there is something to drain. */
  onDirty: (() => void) | undefined;

  constructor(
    private readonly objects: ReadonlyMap<string, Declared>,
    private readonly detach: (c: StoreCursor) => void,
  ) {}

  /** @internal An object was declared or replaced. */
  written(key: string): void {
    this.dirty.add(key);
    this.removedKeys.delete(key);
  }

  /** @internal An object was removed. */
  removed(key: string): void {
    this.dirty.delete(key);
    if (this.sent.has(key)) this.removedKeys.add(key);
  }

  /** @internal Every key under `prefix` was removed. */
  removedPrefix(prefix: string): void {
    for (const k of this.dirty) if (k.startsWith(prefix)) this.dirty.delete(k);
    for (const k of this.removedKeys) if (k.startsWith(prefix)) this.removedKeys.delete(k);
    if (!this.removedPrefixes.some((p) => prefix.startsWith(p))) {
      this.removedPrefixes = this.removedPrefixes.filter((p) => !p.startsWith(prefix));
      this.removedPrefixes.push(prefix);
    }
  }

  get hasChanges(): boolean {
    return this.dirty.size > 0 || this.removedKeys.size > 0 || this.removedPrefixes.length > 0;
  }

  /** Takes the pending changes, marking them as sent. */
  drain(): StoreChanges {
    const keys = [...this.removedKeys];
    const prefixes = this.removedPrefixes;
    for (const k of keys) this.sent.delete(k);
    for (const p of prefixes) for (const k of [...this.sent.keys()]) if (k.startsWith(p)) this.sent.delete(k);
    const writes: StoreWrite[] = [];
    for (const k of this.dirty) {
      const obj = this.objects.get(k);
      if (obj === undefined) continue;
      const prev = this.sent.get(k);
      // A patch can replace traits but not clear them, so dropping traits takes a full write.
      const patchable = prev !== undefined && sameMeta(prev, obj) && (obj.traits !== undefined || prev.traits === undefined);
      const fields = patchable ? diffPatch(prev.data, obj.data) : null;
      // A patch only pays off when it is smaller than the data it replaces.
      if (fields !== null && fields.length < obj.data.length) writes.push({ type: 'patch', object: obj, fields, traits: sameTraits(prev?.traits, obj.traits) ? undefined : obj.traits });

      else writes.push({ type: 'full', object: obj });
      this.sent.set(k, obj);
    }
    this.dirty.clear();
    this.removedKeys.clear();
    this.removedPrefixes = [];
    return { keys, prefixes, writes };
  }

  /** Every object, for a snapshot after a fresh session open. Marks all as sent and clears pending changes. */
  snapshot(): Declared[] {
    this.sent.clear();
    this.dirty.clear();
    this.removedKeys.clear();
    this.removedPrefixes = [];
    const all = [...this.objects.values()];
    for (const o of all) this.sent.set(o.key, o);
    return all;
  }

  /** Stops tracking changes; the session that used this cursor ended. */
  dispose(): void {
    this.detach(this);
  }
}

/**
 * The declared state of one session. Holds every object the session declared; each {@link
 * StoreCursor} tracks what one mod has been sent and turns changes made in one tick into the
 * smallest set of removals, full writes and field patches.
 *
 * `drain`, `snapshot`, `hasChanges` and `onDirty` on the store itself use a default cursor, created
 * on first use.
 */
export class DeclaredStore {
  private readonly objects = new Map<string, Declared>();
  private readonly cursors = new Set<StoreCursor>();
  private defaultCursor: StoreCursor | undefined;
  private lastRev = 0;
  /** Declared objects per kind, kept for the client-side quota checks. */
  private readonly kinds = new Map<string, number>();
  /** Blobs referenced by declared objects. */
  readonly blobs = new BlobTable();

  /** Called after a `persistent` object is written or any object is removed. */
  onPersistentChange: (() => void) | undefined;

  /** A new cursor that starts with every declared object pending. */
  cursor(): StoreCursor {
    const c = new StoreCursor(this.objects, (x) => this.cursors.delete(x));
    for (const k of this.objects.keys()) c.written(k);
    this.cursors.add(c);
    return c;
  }

  private get primary(): StoreCursor {
    return (this.defaultCursor ??= this.cursor());
  }

  /** Called whenever the default cursor has something to drain. */
  get onDirty(): (() => void) | undefined {
    return this.primary.onDirty;
  }

  set onDirty(fn: (() => void) | undefined) {
    this.primary.onDirty = fn;
  }

  private notify(): void {
    for (const c of this.cursors) c.onDirty?.();
  }

  get size(): number {
    return this.objects.size;
  }

  /** Number of declared objects of `kind`. */
  count(kind: string): number {
    return this.kinds.get(kind) ?? 0;
  }

  /** Number of declared objects with the interactive trait. */
  get interactive(): number {
    return this.interactiveCount;
  }

  private interactiveCount = 0;

  private countInteractive(o: { traits?: ObjectTraits | undefined }, by: number): void {
    if (o.traits?.interactive !== undefined) this.interactiveCount += by;
  }

  private counted(kind: string, by: number): void {
    const n = (this.kinds.get(kind) ?? 0) + by;
    if (n === 0) this.kinds.delete(kind);
    else this.kinds.set(kind, n);
  }

  /**
   * Revisions are `max(previous + 1, Date.now())`, so they keep increasing across process restarts
   * and a re-declaring process wins over objects its previous run left in the resume window.
   */
  private nextRev(): number {
    this.lastRev = Math.max(this.lastRev + 1, Date.now());
    return this.lastRev;
  }

  /** Declares or replaces an object. Re-declaring identical data without a TTL is a no-op. */
  put(input: DeclaredInput): void {
    const prev = this.objects.get(input.key);
    // A restored object carries no blob bytes, so the same data with a blob attached is a change.
    if (prev !== undefined && input.ttlMs === undefined && prev.ttlMs === undefined && prev.blob?.hex === input.blob?.hex && sameMeta(prev, input) && sameTraits(prev.traits, input.traits) && bytesEqual(prev.data, input.data)) return;
    const obj: Declared = { ...input, rev: this.nextRev() };
    if (obj.blob !== undefined) this.blobs.acquire(obj.blob);
    if (prev?.blob !== undefined) this.blobs.release(prev.blob);
    if (prev?.kind !== obj.kind) {
      if (prev !== undefined) this.counted(prev.kind, -1);
      this.counted(obj.kind, 1);
    }
    if (prev !== undefined) this.countInteractive(prev, -1);
    this.countInteractive(obj, 1);
    this.objects.set(obj.key, obj);
    for (const c of this.cursors) c.written(obj.key);
    if (obj.lifetime === 'persistent' || prev?.lifetime === 'persistent') this.onPersistentChange?.();
    this.notify();
  }

  /** Loads objects from persistence without marking them dirty; they go out with the next snapshot. */
  restore(input: DeclaredInput): void {
    if (this.objects.has(input.key)) return;
    this.objects.set(input.key, { ...input, rev: this.nextRev() });
    if (input.blob !== undefined) this.blobs.acquire(input.blob);
    this.counted(input.kind, 1);
    this.countInteractive(input, 1);
    for (const c of this.cursors) c.written(input.key);
    this.notify();
  }

  get(key: string): Declared | undefined {
    return this.objects.get(key);
  }

  has(key: string): boolean {
    return this.objects.has(key);
  }

  keys(prefix = ''): string[] {
    const out: string[] = [];
    for (const k of this.objects.keys()) if (k.startsWith(prefix)) out.push(k);
    return out;
  }

  remove(key: string): void {
    const prev = this.objects.get(key);
    if (prev === undefined) return;
    this.objects.delete(key);
    this.counted(prev.kind, -1);
    this.countInteractive(prev, -1);
    if (prev.blob !== undefined) this.blobs.release(prev.blob);
    for (const c of this.cursors) c.removed(key);
    if (prev.lifetime === 'persistent') this.onPersistentChange?.();
    this.notify();
  }

  /** Removes every key starting with `prefix`, sent to the mod as one prefix removal. */
  removePrefix(prefix: string): void {
    let persistent = false;
    for (const [k, o] of this.objects) {
      if (!k.startsWith(prefix)) continue;
      this.objects.delete(k);
      this.counted(o.kind, -1);
      this.countInteractive(o, -1);
      if (o.blob !== undefined) this.blobs.release(o.blob);
      if (o.lifetime === 'persistent') persistent = true;
    }
    for (const c of this.cursors) c.removedPrefix(prefix);
    if (persistent) this.onPersistentChange?.();
    this.notify();
  }

  get hasChanges(): boolean {
    return this.primary.hasChanges;
  }

  /** Takes the default cursor's pending changes, marking them as sent. */
  drain(): StoreChanges {
    return this.primary.drain();
  }

  /** Every object, for a snapshot through the default cursor. */
  snapshot(): Declared[] {
    return this.primary.snapshot();
  }

  /** Persistent objects, for saving. */
  persistent(): Declared[] {
    return [...this.objects.values()].filter((o) => o.lifetime === 'persistent');
  }
}
