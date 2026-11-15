import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const GRADLE = join(ROOT, 'mod', 'gradle.properties');
const PACKAGES = ['node/packages', 'apps']
  .flatMap((dir) => (existsSync(join(ROOT, dir)) ? readdirSync(join(ROOT, dir)).map((d) => join(dir, d)) : []))
  .concat('examples')
  .map((p) => join(ROOT, p, 'package.json'));
const SEMVER = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/;

function gradleVersion(): string {
  const v = /^version=(.+)$/m.exec(readFileSync(GRADLE, 'utf8'))?.[1]?.trim();
  if (v === undefined) throw new Error('no version= in mod/gradle.properties');
  return v;
}

function packages(): { file: string; text: string; version: string }[] {
  return PACKAGES.flatMap((file) => {
    let text: string;
    try {
      text = readFileSync(file, 'utf8');
    } catch {
      return [];
    }
    return [{ file, text, version: (JSON.parse(text) as { version: string }).version }];
  });
}

const arg = process.argv[2];
if (arg === '--check') {
  const want = gradleVersion();
  const wrong = packages().filter((p) => p.version !== want);
  if (wrong.length > 0) {
    console.error(`version ${want} in mod/gradle.properties, but:\n  ${wrong.map((p) => `${p.file}: ${p.version}`).join('\n  ')}`);
    process.exit(1);
  }
  console.log(`version ${want} everywhere`);
} else if (arg !== undefined) {
  if (!SEMVER.test(arg)) throw new Error(`not a version: ${arg}`);
  writeFileSync(GRADLE, readFileSync(GRADLE, 'utf8').replace(/^version=.*$/m, `version=${arg}`));
  for (const p of packages()) writeFileSync(p.file, p.text.replace(/("version":\s*")[^"]+(")/, `$1${arg}$2`));
  console.log(`version set to ${arg}; commit, then tag v${arg} to release`);
} else {
  console.error('usage: pnpm version:set <version> | --check');
  process.exit(2);
}
