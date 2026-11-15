import { externalTypes, fieldDoc, header, hexId, pascal, requiredCount, typeSummary, type Field, type KindDef, type MessageDef, type Schema, type StructDef, type TypeRef } from './model.ts';

const code = (name: string): string => `\`${name}\``;

/** A one-line TSDoc comment; a `*\/` in schema text is escaped. */
function tsdoc(text: string, indent = ''): string {
  return `${indent}/** ${text.replace(/\*\//g, '*\\/')} */`;
}

function tsType(t: TypeRef): string {
  switch (t.t) {
    case 'prim':
      switch (t.name) {
        case 'bool': return 'boolean';
        case 'string': return 'string';
        case 'bytes': return 'Uint8Array';
        case 'any': return 'unknown';
        default: return 'number';
      }
    case 'array':
      return { f64: 'Float64Array', f32: 'Float32Array', i32: 'Int32Array', u16: 'Uint16Array' }[t.elem];
    case 'list': return `${tsType(t.of)}[]`;
    case 'map': return `Record<string, ${tsType(t.of)}>`;
    default: return t.name;
  }
}

function writeExpr(t: TypeRef, v: string): string {
  switch (t.t) {
    case 'prim':
      switch (t.name) {
        case 'bool': return `w.bool(${v})`;
        case 'i32': case 'i64': return `w.int(${v})`;
        case 'u32': case 'u64': return `w.uint(${v})`;
        case 'f32': return `w.f32(${v})`;
        case 'f64': return `w.f64(${v})`;
        case 'string': return `w.str(${v})`;
        case 'bytes': return `w.bin(${v})`;
        case 'any': return `w.any(${v})`;
      }
      break;
    case 'array': return `w.typedArray(${v})`;
    case 'list': return `writeList(w, ${v}, (x) => ${writeExpr(t.of, 'x')})`;
    case 'map': return `writeMap(w, ${v}, (x) => ${writeExpr(t.of, 'x')})`;
    default: return `${t.name}Codec.write(w, ${v})`;
  }
  throw new Error('unreachable');
}

function readExpr(t: TypeRef): string {
  switch (t.t) {
    case 'prim':
      switch (t.name) {
        case 'bool': return 'r.bool()';
        case 'i32': case 'i64': return 'r.int()';
        case 'u32': case 'u64': return 'r.uint()';
        case 'f32': case 'f64': return 'r.float()';
        case 'string': return 'r.str()';
        case 'bytes': return 'r.bin()';
        case 'any': return 'r.any()';
      }
      break;
    case 'array': return `r.${t.elem}Array()`;
    case 'list': return `readList(r, () => ${readExpr(t.of)})`;
    case 'map': return `readMap(r, () => ${readExpr(t.of)})`;
    default: return `${t.name}Codec.read(r)`;
  }
  throw new Error('unreachable');
}

function defaultLiteral(f: Field): string {
  const d = f.default!;
  const t = f.type;
  if (t.t === 'enum') return `'${d}'`;
  if (t.t === 'prim' && t.name === 'string') return JSON.stringify(d.replace(/^['"]|['"]$/g, ''));
  if (t.t === 'prim' && t.name === 'bool') return d === 'true' ? 'true' : 'false';
  if (t.t === 'prim') return String(Number(d));
  throw new Error(`${f.name}: defaults are only supported on primitives and enums`);
}

function emitStruct(s: StructDef | KindDef | MessageDef): string {
  const fields = s.fields;
  const lines: string[] = [];
  lines.push(tsdoc(typeSummary(s, code)));
  if (fields.length === 0) {
    lines.push(`export type ${s.typeName} = Record<string, never>;`);
  } else {
    lines.push(`export interface ${s.typeName} {`);
    for (const f of fields) {
      const doc = fieldDoc(f, code);
      if (doc !== undefined) lines.push(tsdoc(doc, '  '));
      const opt = f.optional || f.default !== undefined;
      lines.push(`  ${f.name}${opt ? '?' : ''}: ${tsType(f.type)}${opt ? ' | undefined' : ''};`);
    }
    lines.push('}');
  }
  const req = requiredCount(fields);
  lines.push(tsdoc(`Wire codec for {@link ${s.typeName}}.`));
  lines.push(`export const ${s.typeName}Codec: Codec<${s.typeName}> = {`);
  lines.push(`  write(w, v) {`);
  lines.push(`    w.arrayHeader(${fields.length});`);
  for (const f of fields) {
    const access = `v.${f.name}`;
    if (f.optional) {
      lines.push(`    if (${access} === undefined || ${access} === null) w.nil(); else ${writeExpr(f.type, access)};`);
    } else if (f.default !== undefined) {
      lines.push(`    ${writeExpr(f.type, `${access} ?? ${defaultLiteral(f)}`)};`);
    } else {
      lines.push(`    ${writeExpr(f.type, access)};`);
    }
  }
  lines.push('  },');
  lines.push('  read(r) {');
  lines.push('    const n = r.arrayHeader();');
  if (req > 0) lines.push(`    if (n < ${req}) throw new WireError('invalid_message', '${s.schemaName}: expected at least ${req} fields, got ' + n);`);
  fields.forEach((f, i) => {
    if (f.optional) {
      lines.push(`    const ${f.name} = n > ${i} && !r.tryNil() ? ${readExpr(f.type)} : undefined;`);
    } else if (f.default !== undefined) {
      lines.push(`    const ${f.name} = n > ${i} && !r.tryNil() ? ${readExpr(f.type)} : ${defaultLiteral(f)};`);
    } else {
      lines.push(`    const ${f.name} = ${readExpr(f.type)};`);
    }
  });
  lines.push(`    for (let i = ${fields.length}; i < n; i++) r.skip();`);
  lines.push(`    return { ${fields.map((f) => f.name).join(', ')} };`);
  lines.push('  },');
  lines.push('};');
  return lines.join('\n');
}

/** An extension's kinds and messages by name; ids are 0 until the mod assigns them per connection. */
function emitExtensionRegistry(schema: Schema): string {
  const base = pascal(schema.extension!.id.replace(/^[a-z0-9]+:/, ''));
  const out: string[] = [];
  out.push(tsdoc(`Payload type of each message of \`${schema.extension!.id}\`.`));
  out.push(`export interface ${base}MessagePayloads {`);
  for (const m of schema.messages) out.push(`  '${m.schemaName}': ${m.typeName};`);
  out.push('}');
  out.push(tsdoc(`Messages of \`${schema.extension!.id}\`, keyed by name.`));
  out.push(`export const ${base}Messages: { readonly [K in keyof ${base}MessagePayloads]: MessageType<${base}MessagePayloads[K]> } = {`);
  for (const m of schema.messages) {
    const extra = [m.response ? `response: '${m.response}'` : '', m.timeoutMs !== undefined ? `timeoutMs: ${m.timeoutMs}` : ''].filter(Boolean);
    out.push(`  '${m.schemaName}': { id: 0, name: '${m.schemaName}', from: '${m.from}', delivery: '${m.delivery}', codec: ${m.typeName}Codec${extra.length ? ', ' + extra.join(', ') : ''} },`);
  }
  out.push('};');
  out.push('');
  out.push(tsdoc(`Data type of each object kind of \`${schema.extension!.id}\`.`));
  out.push(`export interface ${base}KindPayloads {`);
  for (const k of schema.kinds) out.push(`  '${k.schemaName}': ${k.typeName};`);
  out.push('}');
  out.push(tsdoc(`Object kinds of \`${schema.extension!.id}\`, keyed by name.`));
  out.push(`export const ${base}Kinds: { readonly [K in keyof ${base}KindPayloads]: KindType<${base}KindPayloads[K]> } = {`);
  for (const k of schema.kinds) out.push(`  '${k.schemaName}': { id: 0, name: '${k.schemaName}', codec: ${k.typeName}Codec },`);
  out.push('};');
  out.push('');
  return out.join('\n');
}

export function emitTs(schema: Schema): string {
  const out: string[] = [header(schema)];
  const ext = schema.extension;
  if (ext === undefined) {
    out.push("import type { Codec, KindType, MessageType } from '../wire/codec.ts';");
    out.push("import { WireError } from '../wire/errors.ts';");
    out.push("import type { WireReader } from '../wire/reader.ts';");
    out.push("import type { WireWriter } from '../wire/writer.ts';");
    out.push('');
    out.push('/** Protocol version these codecs implement. */');
    out.push(`export const PROTOCOL = { major: ${schema.protocol.major}, minor: ${schema.protocol.minor} } as const;`);
    out.push('');
  } else {
    const core = externalTypes(schema);
    const values = core.map((n) => `${n}Codec`);
    const types = ['Codec', 'KindType', 'MessageType', 'WireReader', 'WireWriter', ...core].map((n) => `type ${n}`);
    out.push(`import { ${[...values, 'WireError', ...types].join(', ')} } from '@mineflayer-bridge/protocol';`);
    out.push('');
  }
  out.push(`function writeList<T>(w: WireWriter, items: readonly T[], f: (x: T) => void): void {
  w.arrayHeader(items.length);
  for (const x of items) f(x);
}
function readList<T>(r: WireReader, f: () => T): T[] {
  const n = r.arrayHeader();
  const out: T[] = new Array(n);
  for (let i = 0; i < n; i++) out[i] = f();
  return out;
}
function writeMap<T>(w: WireWriter, m: Readonly<Record<string, T>>, f: (x: T) => void): void {
  const keys = Object.keys(m);
  w.mapHeader(keys.length);
  for (const k of keys) {
    w.str(k);
    f(m[k]!);
  }
}
function readMap<T>(r: WireReader, f: () => T): Record<string, T> {
  const n = r.mapHeader();
  const out = Object.create(null) as Record<string, T>;
  for (let i = 0; i < n; i++) {
    const k = r.str();
    out[k] = f();
  }
  return out;
}
`);

  for (const e of schema.enums) {
    out.push(tsdoc(`Schema enum ${code(e.name)}, encoded as the value's ordinal.`));
    out.push(`export type ${e.name} = ${e.values.map((v) => `'${v}'`).join(' | ')};`);
    out.push(tsdoc(`Values of {@link ${e.name}} in ordinal order.`));
    out.push(`export const ${e.name}Values: readonly ${e.name}[] = [${e.values.map((v) => `'${v}'`).join(', ')}];`);
    out.push(`const ${e.name}Index: Record<${e.name}, number> = { ${e.values.map((v, i) => `'${v}': ${i}`).join(', ')} };`);
    out.push(tsdoc(`Wire codec for {@link ${e.name}}.`));
    out.push(`export const ${e.name}Codec: Codec<${e.name}> = {
  write(w, v) {
    const i = ${e.name}Index[v];
    if (i === undefined) throw new WireError('invalid_message', '${e.name}: unknown value ' + String(v));
    w.uint(i);
  },
  read(r) {
    const v = ${e.name}Values[r.uint()];
    if (v === undefined) throw new WireError('invalid_message', '${e.name}: unknown ordinal');
    return v;
  },
};`);
    out.push('');
  }

  for (const s of schema.structs) {
    out.push(emitStruct(s));
    out.push('');
  }

  for (const u of schema.unions) {
    out.push(tsdoc(`Schema union ${code(u.name)}, encoded as \`[tag, value]\` with the variant index as tag.`));
    out.push(`export type ${u.name} = ${u.variants.map((v) => `{ type: '${v.key}'; value: ${v.struct} }`).join(' | ')};`);
    out.push(tsdoc(`Wire codec for {@link ${u.name}}.`));
    out.push(`export const ${u.name}Codec: Codec<${u.name}> = {
  write(w, v) {
    w.arrayHeader(2);
    switch (v.type) {
${u.variants.map((v, i) => `      case '${v.key}': w.uint(${i}); ${v.struct}Codec.write(w, v.value); return;`).join('\n')}
    }
  },
  read(r) {
    if (r.arrayHeader() !== 2) throw new WireError('invalid_message', '${u.name}: union must be [tag, value]');
    const tag = r.uint();
    switch (tag) {
${u.variants.map((v, i) => `      case ${i}: return { type: '${v.key}', value: ${v.struct}Codec.read(r) };`).join('\n')}
      default: throw new WireError('invalid_message', '${u.name}: unknown tag ' + tag);
    }
  },
};`);
    out.push('');
  }

  for (const k of schema.kinds) {
    out.push(emitStruct(k));
    out.push('');
  }

  for (const m of schema.messages) {
    out.push(emitStruct(m));
    out.push('');
  }

  if (ext !== undefined) {
    out.push(emitExtensionRegistry(schema));
    return out.join('\n');
  }
  out.push('/** Payload type for each core message name. */');
  out.push('export interface MessagePayloads {');
  for (const m of schema.messages) out.push(`  '${m.schemaName}': ${m.typeName};`);
  out.push('}');
  out.push('/** Name of a core message. */');
  out.push('export type MessageName = keyof MessagePayloads;');
  out.push('');
  out.push('/** Core message registry, keyed by name. */');
  out.push('export const Messages: { readonly [K in MessageName]: MessageType<MessagePayloads[K]> } = {');
  for (const m of schema.messages) {
    const extra = [m.response ? `response: '${m.response}'` : '', m.timeoutMs !== undefined ? `timeoutMs: ${m.timeoutMs}` : ''].filter(Boolean);
    out.push(`  '${m.schemaName}': { id: ${hexId(m.id)}, name: '${m.schemaName}', from: '${m.from}', delivery: '${m.delivery}', codec: ${m.typeName}Codec${extra.length ? ', ' + extra.join(', ') : ''} },`);
  }
  out.push('};');
  out.push('');
  out.push('/** Core messages by wire id. */');
  out.push('export const MessagesById: ReadonlyMap<number, MessageType<unknown>> = new Map(');
  out.push('  Object.values(Messages).map((m) => [m.id, m as MessageType<unknown>]),');
  out.push(');');
  out.push('');
  out.push('/** Data type for each core object kind. */');
  out.push('export interface KindPayloads {');
  for (const k of schema.kinds) out.push(`  '${k.schemaName}': ${k.typeName};`);
  out.push('}');
  out.push('/** Name of a core object kind. */');
  out.push('export type KindName = keyof KindPayloads;');
  out.push('');
  out.push('/** Core object kinds, keyed by name. */');
  out.push('export const Kinds: { readonly [K in KindName]: KindType<KindPayloads[K]> } = {');
  for (const k of schema.kinds) out.push(`  '${k.schemaName}': { id: ${k.id}, name: '${k.schemaName}', codec: ${k.typeName}Codec },`);
  out.push('};');
  out.push('');
  out.push('/** Core kinds by wire id. */');
  out.push('export const KindsById: ReadonlyMap<number, KindType<unknown>> = new Map(');
  out.push('  Object.values(Kinds).map((k) => [k.id, k as KindType<unknown>]),');
  out.push(');');
  out.push('');
  return out.join('\n');
}
