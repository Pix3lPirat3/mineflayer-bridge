import { decodeKind, Kinds } from '@mineflayer-bridge/protocol';
import type { ObjectStore } from './store.ts';

/** The variables object of a scope is `<owner>/<extension>/~vars`. */
const VARS_ID = '~vars';
const TOKEN = /\{\{|\}\}|\{([A-Za-z0-9_.-]{1,64})(?::(int|1f|2f|pct))?\}/g;
/** Text fields a template can sit in, by the kinds that have them. */
const TEXT_FIELDS = ['text', 'label', 'name'] as const;

function format(v: unknown, fmt: string | undefined): string {
  if (fmt === undefined || typeof v !== 'number') return String(v);
  switch (fmt) {
    case 'int':
      return String(Math.round(v));
    case '1f':
      return v.toFixed(1);
    case '2f':
      return v.toFixed(2);
    default:
      return `${Math.round(v * 100)}%`;
  }
}

/**
 * Renders a template as the mod does: `{name}` is the value,
 * `{name:int}` rounded, `{name:1f}` and `{name:2f}` with fixed decimals, `{name:pct}` a fraction as
 * a rounded percentage. Numeric formats leave non-numbers as their string. `{{` and `}}` are literal
 * braces; unknown names stay as written.
 */
export function renderTemplate(template: string, values: Readonly<Record<string, unknown>>): string {
  return template.replace(TOKEN, (m: string, name: string | undefined, fmt: string | undefined) => {
    if (name === undefined) return m[0]!;
    return Object.hasOwn(values, name) ? format(values[name], fmt) : m;
  });
}

/** The variables of the scope `<owner>/<extension>`, or `undefined` when it declares none. */
export function scopeVars(store: ObjectStore, scope: string): Record<string, unknown> | undefined {
  return store.decoded(`${scope}/${VARS_ID}`, 'core:vars')?.values;
}

/** The variables visible to the object at `key`: those of the scope that declared it. */
export function varsFor(store: ObjectStore, key: string): Record<string, unknown> {
  for (const o of store.map.values()) {
    if (o.kindName !== 'core:vars' || !o.key.endsWith(`/${VARS_ID}`)) continue;
    if (key.startsWith(o.key.slice(0, -VARS_ID.length))) return decodeKind(Kinds['core:vars'], o.data).values;
  }
  return {};
}

/** The rendered text of the object at `key` (its `text`, `label` or `name` field), or `undefined`. */
export function renderedText(store: ObjectStore, key: string): string | undefined {
  const data = store.one(key)?.data;
  if (typeof data !== 'object' || data === null) return undefined;
  const d = data as Record<string, unknown>;
  const field = TEXT_FIELDS.find((f) => typeof d[f] === 'string');
  return field === undefined ? undefined : renderTemplate(d[field] as string, varsFor(store, key));
}
