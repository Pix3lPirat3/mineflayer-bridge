import { BridgeError } from '@mineflayer-bridge/protocol';

/**
 * Reads SNBT as `/summon` does, far enough to check what bots send: compounds become objects, lists
 * and typed arrays become arrays, numbers lose their type suffix, and bare words become strings.
 * @throws BridgeError `invalid_message` for malformed input.
 */
export function parseSnbt(text: string): unknown {
  let i = 0;
  const fail = (why: string): never => {
    throw new BridgeError('invalid_message', `mock: bad SNBT at ${i}: ${why}`);
  };
  const ws = (): void => {
    while (i < text.length && /\s/.test(text[i]!)) i++;
  };
  const quoted = (): string => {
    const q = text[i++]!;
    let out = '';
    while (i < text.length && text[i] !== q) {
      if (text[i] === '\\') i++;
      out += text[i++] ?? '';
    }
    if (text[i] !== q) fail('unterminated string');
    i++;
    return out;
  };
  const bare = (): string => {
    const start = i;
    while (i < text.length && /[A-Za-z0-9._+-]/.test(text[i]!)) i++;
    if (i === start) fail('expected a value');
    return text.slice(start, i);
  };
  const scalar = (word: string): unknown => {
    const m = /^([-+]?(?:\d+\.?\d*|\.\d+)(?:e[-+]?\d+)?)([bslfdBSLFD]?)$/.exec(word);
    if (m === null) return word === 'true' ? 1 : word === 'false' ? 0 : word;
    return Number(m[1]);
  };
  const value = (): unknown => {
    ws();
    const c = text[i];
    if (c === '{') {
      i++;
      const out: Record<string, unknown> = {};
      ws();
      if (text[i] === '}') {
        i++;
        return out;
      }
      for (;;) {
        ws();
        const key = text[i] === '"' || text[i] === "'" ? quoted() : bare();
        ws();
        if (text[i++] !== ':') fail('expected :');
        out[key] = value();
        ws();
        const sep = text[i++];
        if (sep === '}') return out;
        if (sep !== ',') fail('expected , or }');
      }
    }
    if (c === '[') {
      i++;
      ws();
      if (/^[BIL];/.test(text.slice(i, i + 2))) i += 2;
      const out: unknown[] = [];
      ws();
      if (text[i] === ']') {
        i++;
        return out;
      }
      for (;;) {
        out.push(value());
        ws();
        const sep = text[i++];
        if (sep === ']') return out;
        if (sep !== ',') fail('expected , or ]');
      }
    }
    if (c === '"' || c === "'") return quoted();
    return scalar(bare());
  };
  const out = value();
  ws();
  if (i !== text.length) fail('trailing characters');
  return out;
}
