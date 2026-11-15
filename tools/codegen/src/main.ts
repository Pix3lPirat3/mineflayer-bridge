import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { emitJava, JAVA_PACKAGE } from './emit-java.ts';
import { emitTs } from './emit-ts.ts';
import { parseSchema } from './model.ts';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const check = process.argv.includes('--check');

const schema = parseSchema(readFileSync(join(root, 'schema/core.yaml'), 'utf8'));
const javaDir = join(root, 'mod/protocol/src/main/java', ...JAVA_PACKAGE.split('.'));
const tsFile = join(root, 'node/packages/protocol/src/generated/core.ts');

const outputs = new Map<string, string>();
outputs.set(tsFile, emitTs(schema));
for (const [name, content] of Object.entries(emitJava(schema))) outputs.set(join(javaDir, name), content);
const javaDirs = [javaDir];

const extDir = join(root, 'schema/ext');
const extensions = existsSync(extDir) ? readdirSync(extDir).filter((f) => f.endsWith('.yaml')).sort() : [];
for (const f of extensions) {
  const ext = parseSchema(readFileSync(join(extDir, f), 'utf8'), schema);
  const dir = join(root, 'mod/common/src/main/java', ...ext.extension!.java.split('.'));
  javaDirs.push(dir);
  outputs.set(join(root, 'node/packages/plugin/src/generated', f.replace(/\.yaml$/, '.ts')), emitTs(ext));
  for (const [name, content] of Object.entries(emitJava(ext))) outputs.set(join(dir, name), content);
}

const stale = javaDirs.flatMap((dir) => (existsSync(dir) ? readdirSync(dir).map((f) => join(dir, f)).filter((f) => !outputs.has(f)) : []));

if (check) {
  const drift = [...outputs].filter(([path, content]) => !existsSync(path) || readFileSync(path, 'utf8') !== content).map(([p]) => p);
  drift.push(...stale);
  if (drift.length > 0) {
    console.error(`Generated code is out of date (${drift.length} files). Run \`pnpm gen\`.\n${drift.join('\n')}`);
    process.exit(1);
  }
  console.log('Generated code is up to date.');
} else {
  for (const path of stale) rmSync(path);
  for (const [path, content] of outputs) {
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, content);
  }
  console.log(`Generated ${outputs.size} files (${schema.messages.length} messages, ${schema.kinds.length} kinds, ${extensions.length} extension schemas).`);
}
