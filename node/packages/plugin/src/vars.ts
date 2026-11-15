import { BridgeError } from '@mineflayer-bridge/protocol';
import type { ScopeObjects } from './scope.ts';
import type { VarsApi, VarValue } from './types.ts';

/** One `core:vars` object per owner and scope, at `<scope>/~vars`. */
const VARS_ID = '~vars';
/** Names exclude `{`, `}` and `:`, which the template syntax uses. */
const NAME = /^[A-Za-z0-9_.-]{1,64}$/;

function checked(name: string, value: VarValue): VarValue {
  if (typeof name !== 'string' || !NAME.test(name)) throw new BridgeError('invalid_message', `variable names are 1 to 64 letters, digits, _, . or -: ${String(name)}`);
  if (typeof value === 'string' || typeof value === 'boolean' || (typeof value === 'number' && Number.isFinite(value))) return value;
  throw new BridgeError('invalid_message', `variable ${name} must be a string, a finite number or a boolean`);
}

/**
 * The variables of one scope. Every change rewrites the one object; the store coalesces the writes
 * of a tick into one patch. An empty map removes the object.
 */
export class ScopeVars implements VarsApi {
  private readonly values = new Map<string, VarValue>();

  constructor(private readonly objects: ScopeObjects) {}

  set(name: string, value: VarValue): void {
    this.values.set(name, checked(name, value));
    this.write();
  }

  setMany(values: Readonly<Record<string, VarValue>>): void {
    if (typeof values !== 'object' || values === null) throw new BridgeError('invalid_message', 'setMany needs an object of variables');
    const entries = Object.entries(values).map(([k, v]) => [k, checked(k, v)] as const);
    for (const [k, v] of entries) this.values.set(k, v);
    if (entries.length > 0) this.write();
  }

  get(name: string): VarValue | undefined {
    return this.values.get(name);
  }

  delete(name: string): boolean {
    if (!this.values.delete(name)) return false;
    this.write();
    return true;
  }

  all(): Record<string, VarValue> {
    return Object.fromEntries(this.values);
  }

  /** Forgets the values without touching the object; the scope's removal takes it. */
  clear(): void {
    this.values.clear();
  }

  private write(): void {
    if (this.values.size === 0) this.objects.remove(VARS_ID);
    else this.objects.put(VARS_ID, 'core:vars', { values: Object.fromEntries(this.values) }, { world: 'global' });
  }
}
