import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import * as plugin from 'mineflayer-bridge';

const EXAMPLES = join(dirname(fileURLToPath(import.meta.url)), '..');
const scripts = readdirSync(EXAMPLES).filter((f) => f.endsWith('.mjs'));

describe('examples', () => {
  it('ships the documented scripts', () => {
    expect(scripts.sort()).toEqual(['basic.mjs', 'commands.mjs', 'crew.mjs', 'menus.mjs', 'pathfinder.mjs', 'showcase.mjs', 'swarm.mjs']);
  });

  it.each(scripts)('%s parses, explains how to run it and imports only real exports', (name) => {
    const file = join(EXAMPLES, name);
    execFileSync(process.execPath, ['--check', file]);
    const source = readFileSync(file, 'utf8');
    expect(source.startsWith(`// ${name}:`)).toBe(true);
    expect(source).toMatch(/Run it against a Minecraft client with the mineflayer-bridge mod installed/);
    const imports = [...source.matchAll(/import \{([^}]+)\} from 'mineflayer-bridge'/g)].flatMap((m) => m[1]!.split(',').map((s) => s.trim()).filter((s) => s !== ''));
    expect(imports.length).toBeGreaterThan(0);
    for (const id of imports) expect(plugin, `${name} imports ${id}`).toHaveProperty(id);
  });
});
