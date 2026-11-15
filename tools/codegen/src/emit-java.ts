import { constCase, externalTypes, fieldDoc, header, hexId, pascal, requiredCount, typeSummary, type EnumDef, type Field, type KindDef, type MessageDef, type Schema, type StructDef, type TypeRef, type UnionDef } from './model.ts';

export const JAVA_PACKAGE = 'io.github.pix3lpirat3.mfbridge.protocol.core';
/** The package and header of the schema being emitted; an extension schema emits into its own package. */
let pkg = JAVA_PACKAGE;
let fileHeader = '';
let coreImports: string[] = [];
const WIRE = 'io.github.pix3lpirat3.mfbridge.protocol.wire';
const code = (name: string): string => `{@code ${name}}`;

/** Escapes schema text for Javadoc, which is HTML and ends at `*\/`. */
function escapeDoc(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\*\//g, '*&#47;');
}

/** A Javadoc comment: one line for a single entry, a block otherwise. Entries are already escaped. */
function javadoc(entries: string[]): string {
  if (entries.length === 1) return `/** ${entries[0]} */`;
  return ['/**', ...entries.map((e) => (e === '' ? ' *' : ` * ${e}`)), ' */'].join('\n');
}

const JAVA_KEYWORDS = new Set(['abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch', 'char', 'class', 'const', 'continue', 'default', 'do', 'double', 'else', 'enum', 'extends', 'final', 'finally', 'float', 'for', 'goto', 'if', 'implements', 'import', 'instanceof', 'int', 'interface', 'long', 'native', 'new', 'package', 'private', 'protected', 'public', 'return', 'short', 'static', 'strictfp', 'super', 'switch', 'synchronized', 'this', 'throw', 'throws', 'transient', 'try', 'void', 'volatile', 'while', 'record', 'var', 'yield', 'sealed', 'permits']);

function ident(name: string): string {
  return JAVA_KEYWORDS.has(name) ? `${name}_` : name;
}

function isPrimitive(t: TypeRef): boolean {
  return t.t === 'prim' && ['bool', 'i32', 'u32', 'i64', 'u64', 'f32', 'f64'].includes(t.name);
}

function javaType(t: TypeRef, boxed = false): string {
  switch (t.t) {
    case 'prim':
      switch (t.name) {
        case 'bool': return boxed ? 'Boolean' : 'boolean';
        case 'i32': case 'u32': return boxed ? 'Integer' : 'int';
        case 'i64': case 'u64': return boxed ? 'Long' : 'long';
        case 'f32': return boxed ? 'Float' : 'float';
        case 'f64': return boxed ? 'Double' : 'double';
        case 'string': return 'String';
        case 'bytes': return 'byte[]';
        case 'any': return 'Object';
      }
      break;
    case 'array': return { f64: 'double[]', f32: 'float[]', i32: 'int[]', u16: 'short[]' }[t.elem];
    case 'list': return `List<${javaType(t.of, true)}>`;
    case 'map': return `Map<String, ${javaType(t.of, true)}>`;
    default: return t.name;
  }
  throw new Error('unreachable');
}

/** Codec expression for non-primitive positions (list elements, map values). */
function codecExpr(t: TypeRef): string {
  switch (t.t) {
    case 'prim':
      return `WireCodecs.${{ bool: 'BOOL', i32: 'I32', u32: 'U32', i64: 'I64', u64: 'U64', f32: 'F32', f64: 'F64', string: 'STRING', bytes: 'BYTES', any: 'ANY' }[t.name]}`;
    case 'array':
      return `WireCodecs.${{ f64: 'F64_ARRAY', f32: 'F32_ARRAY', i32: 'I32_ARRAY', u16: 'U16_ARRAY' }[t.elem]}`;
    case 'list': return `WireCodecs.list(${codecExpr(t.of)})`;
    case 'map': return `WireCodecs.map(${codecExpr(t.of)})`;
    default: return `${t.name}.CODEC`;
  }
}

function writeStmt(t: TypeRef, v: string): string {
  if (t.t === 'prim') {
    switch (t.name) {
      case 'bool': return `w.writeBool(${v});`;
      case 'i32': case 'i64': return `w.writeInt(${v});`;
      case 'u32': return `w.writeUInt(Integer.toUnsignedLong(${v}));`;
      case 'u64': return `w.writeULong(${v});`;
      case 'f32': return `w.writeFloat(${v});`;
      case 'f64': return `w.writeDouble(${v});`;
      case 'string': return `w.writeString(${v});`;
      case 'bytes': return `w.writeBytes(${v});`;
      case 'any': return `w.writeAny(${v});`;
    }
  }
  return `${codecExpr(t)}.write(w, ${v});`;
}

function readExpr(t: TypeRef): string {
  if (t.t === 'prim') {
    switch (t.name) {
      case 'bool': return 'r.readBool()';
      case 'i32': return 'r.readInt()';
      case 'u32': return 'r.readUInt()';
      case 'i64': return 'r.readLong()';
      case 'u64': return 'r.readULong()';
      case 'f32': return 'r.readFloat()';
      case 'f64': return 'r.readDouble()';
      case 'string': return 'r.readString()';
      case 'bytes': return 'r.readBytes()';
      case 'any': return 'r.readAny()';
    }
  }
  return `${codecExpr(t)}.read(r)`;
}

function defaultLiteral(f: Field): string {
  const d = f.default!;
  const t = f.type;
  if (t.t === 'enum') return `${t.name}.${constCase(d)}`;
  if (t.t === 'prim') {
    switch (t.name) {
      case 'bool': return d === 'true' ? 'true' : 'false';
      case 'string': return JSON.stringify(d.replace(/^['"]|['"]$/g, ''));
      case 'f32': return `${Number(d)}f`;
      case 'f64': return `${Number(d)}d`;
      case 'i64': case 'u64': return `${Number(d)}L`;
      case 'i32': return String(Number(d));
      case 'u32': return Number(d) > 0x7fffffff ? `(int) ${Number(d)}L` : String(Number(d));
    }
  }
  throw new Error(`${f.name}: defaults are only supported on primitives and enums`);
}

function fieldType(f: Field): string {
  const nullable = f.optional;
  const type = javaType(f.type, nullable && isPrimitive(f.type));
  return nullable ? `@Nullable ${type}` : type;
}

function imports(body: string): string {
  const lines: string[] = [];
  if (body.includes('List<')) lines.push('import java.util.List;');
  if (body.includes('Map<')) lines.push('import java.util.Map;');
  if (body.includes('@Nullable')) lines.push('import org.jspecify.annotations.Nullable;');
  lines.push(`import ${WIRE}.WireCodec;`);
  if (body.includes('WireCodecs.')) lines.push(`import ${WIRE}.WireCodecs;`);
  if (body.includes('WireException')) lines.push(`import ${WIRE}.WireException;`);
  lines.push(`import ${WIRE}.WireReader;`);
  lines.push(`import ${WIRE}.WireWriter;`);
  for (const name of coreImports) if (new RegExp(`\\b${name}\\b`).test(body)) lines.push(`import ${JAVA_PACKAGE}.${name};`);
  return lines.join('\n');
}

function file(body: string): string {
  return `${fileHeader}package ${pkg};\n\n${imports(body)}\n\n${body}`;
}

function emitRecord(s: StructDef | KindDef | MessageDef): string {
  const fields = s.fields;
  const components = fields.map((f) => `${fieldType(f)} ${ident(f.name)}`).join(', ');
  const req = requiredCount(fields);
  const b: string[] = [];
  const summary = escapeDoc(typeSummary(s, code));
  const params = fields.flatMap((f) => {
    const doc = fieldDoc(f, code);
    return doc === undefined ? [] : [`@param ${ident(f.name)} ${escapeDoc(doc)}`];
  });
  b.push(javadoc(params.length > 0 ? [summary, '', ...params] : [summary]));
  b.push(`public record ${s.typeName}(${components}) {`);
  b.push('    /** Wire codec for this record. */');
  b.push(`    public static final WireCodec<${s.typeName}> CODEC = new WireCodec<>() {`);
  b.push('        @Override');
  b.push(`        public void write(WireWriter w, ${s.typeName} v) {`);
  b.push(`            w.writeArrayHeader(${fields.length});`);
  for (const f of fields) {
    const access = `v.${ident(f.name)}()`;
    if (f.optional) {
      b.push(`            if (${access} == null) {`);
      b.push('                w.writeNil();');
      b.push('            } else {');
      b.push(`                ${writeStmt(f.type, access)}`);
      b.push('            }');
    } else {
      b.push(`            ${writeStmt(f.type, access)}`);
    }
  }
  b.push('        }');
  b.push('');
  b.push('        @Override');
  b.push(`        public ${s.typeName} read(WireReader r) {`);
  b.push('            int n = r.readArrayHeader();');
  if (req > 0) {
    b.push(`            if (n < ${req}) {`);
    b.push(`                throw WireException.invalid("${s.schemaName}: expected at least ${req} fields, got " + n);`);
    b.push('            }');
  }
  fields.forEach((f, i) => {
    const name = ident(f.name);
    const type = fieldType(f);
    if (f.optional) {
      b.push(`            ${type} ${name} = n > ${i} && !r.tryReadNil() ? ${readExpr(f.type)} : null;`);
    } else if (f.default !== undefined) {
      b.push(`            ${type} ${name} = n > ${i} && !r.tryReadNil() ? ${readExpr(f.type)} : ${defaultLiteral(f)};`);
    } else {
      b.push(`            ${type} ${name} = ${readExpr(f.type)};`);
    }
  });
  b.push(`            r.skipValues(n - ${fields.length});`);
  b.push(`            return new ${s.typeName}(${fields.map((f) => ident(f.name)).join(', ')});`);
  b.push('        }');
  b.push('    };');
  const shortCtor = emitRequiredConstructor(s);
  if (shortCtor) b.push(shortCtor);
  b.push(...emitWithers(s));
  b.push('}');
  b.push('');
  return file(b.join('\n'));
}

/** Erasure-level signature used to detect constructor clashes. */
function erasedSignature(types: string[]): string {
  return types.map((t) => t.replace(/@Nullable /g, '').replace(/<.*>/g, '')).join(',');
}

/**
 * Secondary constructor taking only the leading required fields, so appending optional or
 * defaulted fields to the schema does not break hand-written call sites. Omitted when it
 * would match the canonical signature (all fields required) or clash after erasure.
 */
function emitRequiredConstructor(s: StructDef): string | null {
  const fields = s.fields;
  const req = requiredCount(fields);
  if (req === fields.length) return null;
  const head = fields.slice(0, req);
  const canonical = erasedSignature(fields.map(fieldType));
  const short = erasedSignature(head.map(fieldType));
  if (short === canonical) return null;
  const params = head.map((f) => `${fieldType(f)} ${ident(f.name)}`).join(', ');
  const args = fields.map((f, i) => {
    if (i < req) return ident(f.name);
    if (f.optional) return 'null';
    return defaultLiteral(f);
  });
  return [
    '',
    `    /** Creates a value from the required fields; later fields take null or their schema default. */`,
    `    public ${s.typeName}(${params}) {`,
    `        this(${args.join(', ')});`,
    '    }',
  ].join('\n');
}

/**
 * Copy methods for the fields after the required ones: with the short constructor they build a value
 * as {@code new X(required).withA(a).withB(b)}, which keeps compiling when the schema appends fields.
 */
function emitWithers(s: StructDef): string[] {
  const fields = s.fields;
  const req = requiredCount(fields);
  const out: string[] = [];
  fields.forEach((f, i) => {
    if (i < req) return;
    const name = ident(f.name);
    const args = fields.map((g) => (g === f ? name : ident(g.name)));
    out.push(
      '',
      `    /** Returns a copy with ${code(f.name)} replaced. */`,
      `    public ${s.typeName} with${pascal(f.name)}(${fieldType(f)} ${name}) {`,
      `        return new ${s.typeName}(${args.join(', ')});`,
      '    }',
    );
  });
  return out;
}

function emitEnum(e: EnumDef): string {
  const consts = e.values.map((v) => `    ${constCase(v)}("${v}")`).join(',\n');
  const body = `/** Schema enum ${code(e.name)}, encoded as the value's ordinal. */
public enum ${e.name} {
${consts};

    private static final ${e.name}[] VALUES = values();

    /** Wire codec for this enum. */
    public static final WireCodec<${e.name}> CODEC = new WireCodec<>() {
        @Override
        public void write(WireWriter w, ${e.name} v) {
            w.writeUInt(v.ordinal());
        }

        @Override
        public ${e.name} read(WireReader r) {
            int i = r.readUInt();
            if (i < 0 || i >= VALUES.length) {
                throw WireException.invalid("${e.name}: unknown ordinal " + i);
            }
            return VALUES[i];
        }
    };

    private final String wireName;

    ${e.name}(String wireName) {
        this.wireName = wireName;
    }

    /** Name used in the schema and on the Node side. */
    public String wireName() {
        return wireName;
    }
}
`;
  return file(body);
}

function emitUnion(u: UnionDef): string {
  const variants = u.variants.map((v) => ({ ...v, cls: pascal(v.key) }));
  const body = `/** Schema union ${code(u.name)}, encoded as {@code [tag, value]} with the variant index as tag. */
public sealed interface ${u.name} permits ${variants.map((v) => `${u.name}.${v.cls}`).join(', ')} {
${variants.map((v) => `    /** Variant ${code(v.key)}. */\n    record ${v.cls}(${v.struct} value) implements ${u.name} {}`).join('\n\n')}

    /** Wire codec for this union. */
    WireCodec<${u.name}> CODEC = new WireCodec<>() {
        @Override
        public void write(WireWriter w, ${u.name} v) {
            w.writeArrayHeader(2);
            switch (v) {
${variants.map((v, i) => `                case ${v.cls} x -> {
                    w.writeUInt(${i});
                    ${v.struct}.CODEC.write(w, x.value());
                }`).join('\n')}
            }
        }

        @Override
        public ${u.name} read(WireReader r) {
            if (r.readArrayHeader() != 2) {
                throw WireException.invalid("${u.name}: union must be [tag, value]");
            }
            int tag = r.readUInt();
            return switch (tag) {
${variants.map((v, i) => `                case ${i} -> new ${v.cls}(${v.struct}.CODEC.read(r));`).join('\n')}
                default -> throw WireException.invalid("${u.name}: unknown tag " + tag);
            };
        }
    };
}
`;
  return file(body);
}

function emitRegistry(schema: Schema): Record<string, string> {
  const msgs = schema.messages.map((m) => {
    const dir = m.from.toUpperCase();
    const del = m.delivery.toUpperCase();
    const resp = m.response ? `"${m.response}"` : 'null';
    return `    public static final MessageType<${m.typeName}> ${m.constName} =\n            new MessageType<>(${hexId(m.id)}, "${m.schemaName}", Direction.${dir}, Delivery.${del}, ${m.typeName}.CODEC, ${resp}, ${m.timeoutMs ?? 0});`;
  });
  const messages = `${fileHeader}package ${pkg};

import ${WIRE}.Delivery;
import ${WIRE}.Direction;
import ${WIRE}.MessageType;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Core message registry. */
public final class CoreMessages {
    /** Protocol major version these types implement. */
    public static final int PROTOCOL_MAJOR = ${schema.protocol.major};
    /** Protocol minor version these types implement. */
    public static final int PROTOCOL_MINOR = ${schema.protocol.minor};

${msgs.join('\n')}

    /** Every core message, in schema order. */
    public static final List<MessageType<?>> ALL = List.of(
${schema.messages.map((m) => `            ${m.constName}`).join(',\n')});

    private static final MessageType<?>[] BY_ID = new MessageType<?>[0x100];

    static {
        for (MessageType<?> type : ALL) {
            BY_ID[type.id()] = type;
        }
    }

    private CoreMessages() {}

    /** Core message for a wire id, or null if the id is not a core message. */
    public static @Nullable MessageType<?> byId(int id) {
        return id >= 0 && id < BY_ID.length ? BY_ID[id] : null;
    }
}
`;
  const kinds = `${fileHeader}package ${pkg};

import ${WIRE}.KindType;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Core object kinds. */
public final class CoreKinds {
${schema.kinds.map((k) => `    public static final KindType<${k.typeName}> ${k.constName} = new KindType<>(${k.id}, "${k.schemaName}", ${k.typeName}.CODEC);`).join('\n')}

    /** Every core object kind, in schema order. */
    public static final List<KindType<?>> ALL = List.of(
${schema.kinds.map((k) => `            ${k.constName}`).join(',\n')});

    private static final KindType<?>[] BY_ID = new KindType<?>[0x100];

    static {
        for (KindType<?> type : ALL) {
            BY_ID[type.id()] = type;
        }
    }

    private CoreKinds() {}

    /** Core kind for a wire id, or null if the id is not a core kind. */
    public static @Nullable KindType<?> byId(int id) {
        return id >= 0 && id < BY_ID.length ? BY_ID[id] : null;
    }
}
`;
  return { 'CoreMessages.java': messages, 'CoreKinds.java': kinds };
}

/** An extension's registry: its kinds and messages with id 0, renumbered by the mod per connection. */
function emitExtensionRegistry(schema: Schema, className: string): string {
  const msgs = schema.messages.map((m) => `    /** Message {@code ${m.schemaName}}. */\n    public static final MessageType<${m.typeName}> ${m.constName} =\n            new MessageType<>(0, "${m.schemaName}", Direction.${m.from.toUpperCase()}, Delivery.${m.delivery.toUpperCase()}, ${m.typeName}.CODEC, ${m.response ? `"${m.response}"` : 'null'}, ${m.timeoutMs ?? 0});`);
  const kinds = schema.kinds.map((k) => `    /** Kind {@code ${k.schemaName}}. */\n    public static final KindType<${k.typeName}> ${k.constName} = new KindType<>(0, "${k.schemaName}", ${k.typeName}.CODEC);`);
  return `${fileHeader}package ${pkg};

import ${WIRE}.Delivery;
import ${WIRE}.Direction;
import ${WIRE}.KindType;
import ${WIRE}.MessageType;
import java.util.List;

/** Kinds and messages of extension {@code ${schema.extension!.id}}. */
public final class ${className} {
${[...kinds, ...msgs].join('\n\n')}

    /** Every kind, in schema order. */
    public static final List<KindType<?>> KINDS = List.of(${schema.kinds.map((k) => k.constName).join(', ')});

    /** Every message, in schema order. */
    public static final List<MessageType<?>> MESSAGES = List.of(${schema.messages.map((m) => m.constName).join(', ')});

    private ${className}() {}
}
`;
}

/** Returns file name to contents. */
export function emitJava(schema: Schema): Record<string, string> {
  const ext = schema.extension;
  pkg = ext?.java ?? JAVA_PACKAGE;
  fileHeader = header(schema);
  coreImports = externalTypes(schema);
  const files: Record<string, string> = {};
  for (const e of schema.enums) files[`${e.name}.java`] = emitEnum(e);
  for (const s of schema.structs) files[`${s.typeName}.java`] = emitRecord(s);
  for (const u of schema.unions) files[`${u.name}.java`] = emitUnion(u);
  for (const k of schema.kinds) files[`${k.typeName}.java`] = emitRecord(k);
  for (const m of schema.messages) files[`${m.typeName}.java`] = emitRecord(m);
  if (ext === undefined) {
    Object.assign(files, emitRegistry(schema));
  } else {
    const className = `${pascal(ext.id.replace(/^[a-z0-9]+:/, ''))}Wire`;
    files[`${className}.java`] = emitExtensionRegistry(schema, className);
  }
  const what = ext === undefined ? 'core protocol types' : `wire types of extension {@code ${ext.id}}`;
  files['package-info.java'] = `${fileHeader}/** Generated ${what}. */\n@NullMarked\npackage ${pkg};\n\nimport org.jspecify.annotations.NullMarked;\n`;
  return files;
}
