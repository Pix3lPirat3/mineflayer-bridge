import { describe, expect, it } from 'vitest';
import type { CommandNode } from '@mineflayer-bridge/protocol';
import { filterSuggestions, helpLines, parseLine, parseSelector, planCompletion, split, tokenize, type SelectorHook } from '../src/commands.ts';

const mine: CommandNode = {
  name: 'mine',
  aliases: ['m'],
  args: [],
  runnable: false,
  children: [
    {
      name: 'start',
      aliases: ['go'],
      args: [
        { name: 'block', type: 'block_id' },
        { name: 'count', type: 'int', optional: true, min: 1 },
      ],
    },
    { name: 'stop', args: [] },
    {
      name: 'zone',
      args: [],
      runnable: false,
      children: [{ name: 'set', args: [{ name: 'from', type: 'block_pos' }, { name: 'note', type: 'greedy', optional: true }] }],
    },
  ],
};

const none: SelectorHook = () => 'no selectors here';

describe('command engine', () => {
  it('tokenises with offsets', () => {
    expect(tokenize('  mine  start x')).toEqual([
      { text: 'mine', start: 2, end: 6 },
      { text: 'start', start: 8, end: 13 },
      { text: 'x', start: 14, end: 15 },
    ]);
  });

  it('groups quoted words into one, with backslash escapes inside the quotes', () => {
    expect(tokenize('say "hello  there" x')).toEqual([
      { text: 'say', start: 0, end: 3 },
      { text: 'hello  there', start: 4, end: 18 },
      { text: 'x', start: 19, end: 20 },
    ]);
    expect(tokenize('a"b c"d')).toEqual([{ text: 'ab cd', start: 0, end: 7 }]);
    expect(tokenize('"say \\"hi\\" \\\\"')).toEqual([{ text: 'say "hi" \\', start: 0, end: 15 }]);
    expect(tokenize('""')).toEqual([{ text: '', start: 0, end: 2 }]);
    expect(split('say "open quote ')).toEqual({ tokens: [{ text: 'say', start: 0, end: 3 }, { text: 'open quote ', start: 4, end: 16 }], trailingSpace: false, openQuote: true });
    expect(split('say ')).toMatchObject({ trailingSpace: true, openQuote: false });
  });

  it('walks names and aliases, parses arguments and generates help from the tree', () => {
    expect(parseLine(mine, 'mine go iron 3', none)).toEqual({ kind: 'invoke', path: ['start'], args: { block: 'minecraft:iron', count: 3 } });
    expect(parseLine(mine, 'MINE Start Iron', none)).toEqual({ kind: 'invoke', path: ['start'], args: { block: 'minecraft:iron' } });
    expect(parseLine(mine, 'm zone set 1 2 3 a  b', none)).toEqual({ kind: 'invoke', path: ['zone', 'set'], args: { from: { x: 1, y: 2, z: 3 }, note: 'a  b' } });
    expect(parseLine(mine, 'm zone set 1 2 3 "a  b"', none)).toMatchObject({ kind: 'invoke', args: { note: 'a  b' } });
    expect(parseLine(mine, 'mine zone', none)).toEqual({ kind: 'usage', path: ['zone'], usage: ['mine zone <set>', '  mine zone set <from:block_pos> [note:greedy]'] });
    expect(helpLines(['mine'], mine)).toEqual(['mine <start|stop|zone>', '  mine start <block:block_id> [count:int]', '  mine stop', '  mine zone <set>']);
    expect(parseLine(mine, 'mine zone get', none)).toEqual({ kind: 'error', path: ['zone'], error: "Unknown subcommand 'get' of 'mine zone'; expected set", start: 10, end: 13 });
    expect(parseLine(mine, 'mine zone set 1 x 3', none)).toMatchObject({ kind: 'error', error: "'from' must be a number, not 'x'", start: 16, end: 17 });
    expect(parseLine(mine, 'mine zone set 1 2', none)).toMatchObject({ kind: 'error', error: "'from' needs x, y and z", start: 14, end: 17 });
    expect(parseLine(mine, 'mine zone set @looked', none)).toMatchObject({ kind: 'error', error: "'from': no selectors here", start: 14, end: 21 });
    expect(parseLine(mine, 'mine zone set here', none)).toMatchObject({ kind: 'error', error: "'from': the crosshair is not on a block" });
    expect(parseLine(mine, 'mine go', none)).toMatchObject({ kind: 'error', error: "Missing argument 'block'", start: 7, end: 7 });
    expect(parseLine(mine, 'mine stop now please', none)).toMatchObject({ kind: 'error', error: 'Too many arguments: now please', start: 10, end: 20 });
  });

  it('converts argument values as the mod does', () => {
    const node = (args: CommandNode['args']): CommandNode => ({ name: 'c', args });
    const value = (arg: CommandNode['args'][number], text: string): unknown => {
      const r = parseLine(node([arg]), `c ${text}`, none, { x: 10.5, y: 64, z: -3.5 });
      return r.kind === 'invoke' ? r.args.v : r.kind === 'error' ? r.error : r;
    };
    expect(value({ name: 'v', type: 'int', min: 1, max: 5 }, '+3')).toBe(3);
    expect(value({ name: 'v', type: 'int', min: 1, max: 5 }, '0')).toBe("'v' must be at least 1");
    expect(value({ name: 'v', type: 'int', min: 1, max: 5 }, '6')).toBe("'v' must be at most 5");
    expect(value({ name: 'v', type: 'int' }, '1e3')).toBe("'v' must be a whole number, not '1e3'");
    expect(value({ name: 'v', type: 'int' }, '2.0')).toBe("'v' must be a whole number, not '2.0'");
    expect(value({ name: 'v', type: 'float' }, '1e3')).toBe(1000);
    expect(value({ name: 'v', type: 'float' }, '0x10')).toBe("'v' must be a number, not '0x10'");
    expect(value({ name: 'v', type: 'bool' }, 'Yes')).toBe(true);
    expect(value({ name: 'v', type: 'bool' }, 'off')).toBe(false);
    expect(value({ name: 'v', type: 'bool' }, 'maybe')).toBe("'v' must be true or false, not 'maybe'");
    expect(value({ name: 'v', type: 'choice', choices: ['Fast', 'slow'] }, 'fast')).toBe('Fast');
    expect(value({ name: 'v', type: 'choice', choices: ['Fast', 'slow'] }, 'mid')).toBe("'v' must be one of Fast, slow");
    expect(value({ name: 'v', type: 'string', max: 3 }, 'abcd')).toBe("'v' length must be at most 3");
    expect(value({ name: 'v', type: 'string' }, '"two words"')).toBe('two words');
    expect(value({ name: 'v', type: 'player' }, '@me')).toBe('@me');
    expect(value({ name: 'v', type: 'block_id' }, 'Iron_Ore')).toBe('minecraft:iron_ore');
    expect(value({ name: 'v', type: 'item_id' }, 'not/an:id:x')).toBe("'v': unknown item 'not/an:id:x'");
    expect(value({ name: 'v', type: 'entity' }, '-7')).toBe(-7);
    expect(value({ name: 'v', type: 'entity' }, 'zombie')).toBe("'v': no loaded entity or player 'zombie'");
    expect(value({ name: 'v', type: 'entity_list' }, '3,1,3')).toEqual([3, 1]);
    expect(value({ name: 'v', type: 'entity_list' }, '3,,1')).toBe("'v': empty entry in the list");
    expect(value({ name: 'v', type: 'block_pos' }, '~ ~1 ~-2')).toEqual({ x: 10, y: 65, z: -6 });
    expect(value({ name: 'v', type: 'block_pos' }, '1.9 2 -0.5')).toEqual({ x: 1, y: 2, z: -1 });
    expect(value({ name: 'v', type: 'vec3' }, '~ ~1 ~-2')).toEqual({ x: 10.5, y: 65, z: -5.5 });
    expect(value({ name: 'v', type: 'greedy', max: 5 }, 'a b c d')).toBe("'v' length must be at most 5");
  });

  it('fills defaults and shows help for a node with subcommands whose required arguments are missing', () => {
    const go: CommandNode = {
      name: 'go',
      args: [{ name: 'where', type: 'string' }, { name: 'speed', type: 'int', defaultValue: 2 }],
      children: [{ name: 'home', args: [] }],
    };
    expect(parseLine(go, 'go there', none)).toEqual({ kind: 'invoke', path: [], args: { where: 'there', speed: 2 } });
    expect(parseLine(go, 'go', none)).toEqual({ kind: 'usage', path: [], usage: ['go <where:string> [speed:int=2]', '  go home'] });
    expect(parseLine(go, 'go home now', none)).toMatchObject({ kind: 'error', error: 'Too many arguments: now' });
    const stop: CommandNode = { name: 'stop', args: [], children: [{ name: 'all', args: [] }] };
    expect(parseLine(stop, 'stop', none)).toEqual({ kind: 'invoke', path: [], args: {} });
    expect(parseLine(stop, 'stop now', none)).toMatchObject({ kind: 'error', error: "Too many arguments: now; 'now' is not a subcommand; expected all" });
  });

  it('parses selectors and refuses unknown kinds and options', () => {
    expect(parseSelector('@nearest[type=minecraft:cow,from=bot]')).toEqual({ kind: 'nearest', options: { type: 'minecraft:cow', from: 'bot' } });
    expect(parseSelector('@me')).toEqual({ kind: 'me', options: {} });
    expect(parseSelector('@all[limit=3,distance=2..8]')).toEqual({ kind: 'all', options: { limit: '3', distance: '2..8' } });
    expect(parseSelector('@everyone')).toBe("Unknown selector '@everyone'; use @me, @self, @looked, @nearest or @all");
    expect(parseSelector('@all[colour=red]')).toBe("Unknown selector option 'colour'; use type, tag, distance, from, limit");
    expect(parseSelector('@me[type=cow]')).toBe('@me takes no options');
    expect(parseSelector('@all[type=cow')).toBe("Expected ']' to close the selector options");
    expect(parseSelector('@all[type]')).toBe('Expected key=value in selector options');
    expect(parseSelector('@all[type=]')).toBe("'type' needs a value");
    expect(parseSelector('@all[distance=8..2]')).toBe("'distance' must be a range of non-negative numbers, lowest first");
    expect(parseSelector('@all[distance=near]')).toBe("'distance' must be a range such as ..16, 4.. or 2..8");
    expect(parseSelector('@all[from=me]')).toBe("'from' must be player or bot");
    expect(parseSelector('@all[limit=0]')).toBe("'limit' must be at least 1");
  });

  it('plans completion of names, arguments and multi-word positions', () => {
    expect(planCompletion([mine], 'mi', () => none)).toEqual({ kind: 'words', start: 0, words: [{ text: 'mine' }] });
    expect(planCompletion([mine], 'mine s', () => none)).toEqual({ kind: 'words', start: 5, words: [{ text: 'start' }, { text: 'stop' }] });
    expect(planCompletion([mine], 'mine go iron ', () => none)).toMatchObject({ kind: 'arg', start: 13, path: ['start'], arg: { name: 'count' }, input: '', args: { block: 'minecraft:iron' } });
    expect(planCompletion([mine], 'mine zone set 1 2', () => none)).toMatchObject({ kind: 'arg', start: 16, arg: { name: 'from' }, input: '2', args: {} });
    expect(planCompletion([mine], 'mine zone set 1 2 3 hello wor', () => none)).toMatchObject({ kind: 'arg', start: 20, arg: { name: 'note' }, input: 'hello wor', args: { from: { x: 1, y: 2, z: 3 } } });
    expect(planCompletion([mine], 'mine go iron 0 ', () => none)).toEqual({ kind: 'none', start: 15, error: "'count' must be at least 1" });
    expect(planCompletion([mine], 'dig ', () => none)).toEqual({ kind: 'none', start: 4, error: 'unknown command dig' });
  });

  it('filters client suggestions by prefix, ignoring case and the minecraft namespace, up to the limit', () => {
    expect(filterSuggestions(['minecraft:Iron_ore', 'minecraft:gold_ore', 'mod:iron_x'], 'iron', 5)).toEqual([{ text: 'minecraft:Iron_ore' }]);
    expect(filterSuggestions(['a1', { text: 'a2', tooltip: 't' }, 'a3'], 'a', 2)).toEqual([{ text: 'a1' }, { text: 'a2', tooltip: 't' }]);
  });
});
