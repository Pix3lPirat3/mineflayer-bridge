import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const PLUGIN = join(dirname(fileURLToPath(import.meta.url)), '..');
const ROOT = join(PLUGIN, '..', '..', '..');
const PROTOCOL = join(PLUGIN, '..', 'protocol');
const SPECIFIER = '@mineflayer-bridge/protocol';

const { values: args } = parseArgs({ options: { out: { type: 'string', default: join(ROOT, 'build', 'release') } } });

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((e) => {
    const p = join(dir, e);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
}

for (const d of [join(PLUGIN, 'dist'), join(PROTOCOL, 'dist')]) {
  if (!existsSync(join(d, 'index.js'))) throw new Error(`${d} is not built; run pnpm build first`);
}

const stage = mkdtempSync(join(tmpdir(), 'mfb-pack-'));
try {
  const copy = (from: string, to: string) => cpSync(from, to, { recursive: true, filter: (f) => !f.endsWith('.map') });
  copy(join(PLUGIN, 'dist'), join(stage, 'dist'));
  copy(join(PROTOCOL, 'dist'), join(stage, 'dist', 'protocol'));
  copy(join(PLUGIN, 'types'), join(stage, 'types'));
  cpSync(join(ROOT, 'LICENSE'), join(stage, 'LICENSE'));
  const version = (JSON.parse(readFileSync(join(PLUGIN, 'package.json'), 'utf8')) as { version: string }).version;
  writeFileSync(join(stage, 'README.md'), readFileSync(join(ROOT, 'README.md'), 'utf8').replaceAll('{{version}}', version));

  const target = join(stage, 'dist', 'protocol', 'index.js');
  const pattern = new RegExp(`(['"])${SPECIFIER.replace('/', '\\/')}\\1`, 'g');
  let rewritten = 0;
  for (const file of walk(join(stage, 'dist'))) {
    if (!/\.(js|d\.ts)$/.test(file) || file.startsWith(join(stage, 'dist', 'protocol') + sep)) continue;
    const text = readFileSync(file, 'utf8');
    if (!text.includes(SPECIFIER)) continue;
    let rel = relative(dirname(file), target).split(sep).join('/');
    if (!rel.startsWith('.')) rel = `./${rel}`;
    writeFileSync(file, text.replace(pattern, (_, q: string) => `${q}${rel}${q}`));
    rewritten++;
  }
  const left = walk(join(stage, 'dist')).filter((f) => readFileSync(f, 'utf8').includes(SPECIFIER));
  if (left.length > 0) throw new Error(`unrewritten protocol imports in ${left.join(', ')}`);

  const pkg = JSON.parse(readFileSync(join(PLUGIN, 'package.json'), 'utf8')) as Record<string, unknown> & {
    dependencies: Record<string, string>;
    devDependencies?: Record<string, string>;
  };
  delete pkg.dependencies[SPECIFIER];
  const wsTypes = pkg.devDependencies?.['@types/ws'];
  if (wsTypes !== undefined) pkg.dependencies['@types/ws'] = wsTypes;
  for (const k of ['devDependencies', 'scripts']) delete pkg[k];
  pkg.files = ['dist', 'types', 'LICENSE', 'README.md'];
  writeFileSync(join(stage, 'package.json'), `${JSON.stringify(pkg, null, 2)}\n`);

  const out = resolve(args.out);
  mkdirSync(out, { recursive: true });
  const cmd = `npm pack --pack-destination "${out}"`;
  const name = execFileSync(cmd, { cwd: stage, encoding: 'utf8', shell: process.platform === 'win32' ? 'cmd.exe' : '/bin/sh' }).trim().split('\n').pop()!;
  console.log(`pack: ${join(out, name)} (${rewritten} files rewritten to the bundled protocol)`);
} finally {
  rmSync(stage, { recursive: true, force: true });
}
