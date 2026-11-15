import { applyPatch, BridgeError, decodeKind, encodeKind, Kinds, type KindName, type MessagePayloads } from '@mineflayer-bridge/protocol';
import type { DeclaredStore } from './store.ts';
import type { EditHandler, EditInfo, EditVerdict } from './traits-types.ts';
import { asBridgeError } from './util.ts';

/** Edit reasons sent in `obj.edit.result`; handler strings are sent as given. */
const REASON = {
  unsupported: 'unsupported',
  notOwner: 'not_owner',
  conflict: 'conflict',
  invalid: 'invalid_message',
  notEditable: 'not_editable',
  rejected: 'rejected',
} as const;

/** Shared zones, points and routes: every field is editable unless the data says `editable: false`. */
const SPATIAL_KINDS: ReadonlySet<string> = new Set(['core:zone', 'core:point', 'core:route']);

type Data = Record<string, unknown>;

/** A declared object that answers player edits; registered by its owner-relative key. */
export interface EditTarget {
  /** The handler set with `onEdit`, if any. */
  readonly editHandler: EditHandler<never> | undefined;
  /**
   * Checks that `proposed` is a valid state for the object and returns the write that applies it,
   * or `undefined` when the object cannot take edits.
   * @throws BridgeError `invalid_message` when the proposed data does not validate.
   */
  prepareEdit(proposed: Data, current: Data, info: EditInfo): (() => void) | undefined;
}

/** Edit targets of one session by owner-relative key. */
export class EditRegistry {
  private readonly targets = new Map<string, EditTarget>();

  get(key: string): EditTarget | undefined {
    return this.targets.get(key);
  }

  set(key: string, target: EditTarget): void {
    this.targets.set(key, target);
  }

  /** Removes `target` only if it is still the one registered at `key`. */
  delete(key: string, target: EditTarget): void {
    if (this.targets.get(key) === target) this.targets.delete(key);
  }
}

/** Structural equality of decoded wire values: plain objects, arrays, typed arrays and primitives. */
export function sameValue(a: unknown, b: unknown): boolean {
  if (Object.is(a, b)) return true;
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false;
  if (ArrayBuffer.isView(a) || ArrayBuffer.isView(b)) {
    if (!ArrayBuffer.isView(a) || !ArrayBuffer.isView(b) || a.byteLength !== b.byteLength) return false;
    const x = new Uint8Array(a.buffer, a.byteOffset, a.byteLength);
    const y = new Uint8Array(b.buffer, b.byteOffset, b.byteLength);
    return x.every((v, i) => v === y[i]);
  }
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  const ka = Object.keys(a).filter((k) => (a as Data)[k] !== undefined);
  const kb = Object.keys(b).filter((k) => (b as Data)[k] !== undefined);
  return ka.length === kb.length && ka.every((k) => sameValue((a as Data)[k], (b as Data)[k]));
}

/** What {@link decideEdit} needs from the session. */
export interface EditContext {
  readonly owner: string | undefined;
  readonly store: DeclaredStore;
  readonly edits: EditRegistry;
  reportError(error: BridgeError): void;
}

/** Fields the default policy accepts, or `undefined` for every field. */
function editableFields(kind: string, current: Data, listed: readonly string[] | undefined): readonly string[] | undefined {
  if (listed !== undefined) return listed;
  if (SPATIAL_KINDS.has(kind)) return current['editable'] === false ? [] : undefined;
  return [];
}

/**
 * Answers `obj.edit.propose` for any core kind. The patch is decoded with the kind schema and the
 * changed fields are computed. Without an `onEdit` handler the edit is accepted only when every
 * changed field is listed in the object's `editable` traits (spatial kinds without them follow
 * their `editable` data field). A proposal based on another revision than the declared one, or for
 * an object that changed while the handler ran, answers `conflict`. An accepted edit is written to
 * the declared state (a new revision) before the answer. Never rejects.
 */
export async function decideEdit(p: MessagePayloads['obj.edit.propose'], ctx: EditContext): Promise<MessagePayloads['obj.edit.result']> {
  const no = (reason: string): MessagePayloads['obj.edit.result'] => ({ accepted: false, reason });
  const owner = ctx.owner;
  if (owner === undefined || !p.key.startsWith(`${owner}/`)) return no(REASON.notOwner);
  const rel = p.key.slice(owner.length + 1);
  const declared = ctx.store.get(rel);
  if (declared === undefined) return no(REASON.conflict);
  if (!Object.hasOwn(Kinds, declared.kind)) return no(REASON.unsupported);
  const kind = Kinds[declared.kind as KindName] as (typeof Kinds)['core:box'];
  if (p.baseRev !== declared.rev) return no(REASON.conflict);
  const target = ctx.edits.get(rel);
  let current: Data;
  let proposed: Data;
  let bytes: Uint8Array;
  let info: EditInfo;
  let apply: (() => void) | undefined;
  try {
    current = decodeKind(kind, declared.data) as unknown as Data;
    proposed = decodeKind(kind, applyPatch(declared.data, p.fields)) as unknown as Data;
    bytes = encodeKind(kind, proposed as never);
    info = { fields: Object.keys(proposed).filter((k) => !sameValue(proposed[k], current[k])) };
    apply = target?.prepareEdit(proposed, current, info);
  } catch {
    return no(REASON.invalid);
  }
  if (target !== undefined && apply === undefined) return no(REASON.unsupported);
  let verdict: EditVerdict;
  const handler = target?.editHandler as EditHandler<Data> | undefined;
  try {
    if (handler !== undefined) verdict = await handler(proposed, current, info);
    else {
      const allowed = editableFields(declared.kind, current, declared.traits?.editable?.map((f) => f.field));
      verdict = allowed === undefined || info.fields.every((f) => allowed.includes(f)) ? true : REASON.notEditable;
    }
  } catch (e) {
    const err = asBridgeError(e);
    ctx.reportError(err);
    return no(err.message === '' ? REASON.rejected : err.message);
  }
  if (verdict !== true) return no(typeof verdict === 'string' && verdict !== '' ? verdict : REASON.rejected);
  // Single writer: the bot may have changed or dropped the object while the handler ran.
  if (ctx.store.get(rel) !== declared) return no(REASON.conflict);
  if (apply !== undefined && ctx.edits.get(rel) === target) apply();
  else ctx.store.put({ ...declared, data: bytes });
  return { accepted: true };
}

/** The data field names of a core kind, in schema order. */
const fieldCache = new Map<string, readonly string[]>();

/** Data field names of `kind`, read from a decode of `data` (decoders return every field). */
export function kindFields(kind: KindName, data: Uint8Array): readonly string[] {
  let names = fieldCache.get(kind);
  if (names === undefined) {
    names = Object.keys(decodeKind(Kinds[kind] as (typeof Kinds)['core:box'], data));
    fieldCache.set(kind, names);
  }
  return names;
}

/** @throws BridgeError `invalid_message` when an editable field is not a data field of `kind`. */
export function checkEditable(kind: string, data: Uint8Array, fields: readonly string[]): void {
  if (!Object.hasOwn(Kinds, kind)) return;
  const names = kindFields(kind as KindName, data);
  for (const f of fields) if (!names.includes(f)) throw new BridgeError('invalid_message', `${kind} has no field ${f} to make editable`);
}
