import { BridgeError, Flags, Messages, type EnvelopeHeader, type MessagePayloads, type Suggestion, type Target } from '@mineflayer-bridge/protocol';
import { filterSuggestions, isSpace, matchNode, parseLine, planCompletion, rootNode, strip, tokenize, type CompletionPlan, type Origin, type SelectorHook } from './commands.ts';
import type { ModCore } from './core.ts';
import { Pending } from './pending.ts';
import { checkScreenItem } from './screens.ts';
import { isLive, type Session } from './session.ts';
import type { MockChatDelivery, MockChatLine, MockChatOutcome, MockCommandOutcome, MockCompletion, MockDeclaration, MockInvocationResult } from './types.ts';
import { toBridgeError } from './util.ts';

/** Dynamic completion is given up after 300 ms. */
const COMPLETION_TIMEOUT_MS = Messages['command.complete'].timeoutMs ?? 300;
/** Suggestions shown when a source declares no limit (schema default). */
const DEFAULT_COMPLETION_LIMIT = 20;
const INVOCATION_TIMEOUT_MS = 30_000;
/** The longest chat line a bot may print. */
const MAX_LINE_CHARS = 4096;

/** Commands an address reaches: a session's own, or a crew's orders for some of its bots. */
interface CommandSet {
  readonly name: string;
  readonly session: Session;
  readonly decls: readonly MockDeclaration<'core:command'>[];
  /** For orders, the bots they are for; empty for a session's own commands. */
  readonly bots: readonly string[];
}

/** A name the player may type after the prefix, as the real mod lists them. */
interface Addressee {
  readonly name: string;
  readonly kind: 'all' | 'bot' | 'controller' | 'group';
  readonly sets: CommandSet[];
  /** Who gets the line as `chat.message` when it names no command. */
  readonly chatTo: Session[];
}

type CompletionAnswer = { status: 'answered' | 'failed' | 'timeout'; suggestions: Suggestion[] };
type ArgSuggestions = { suggestions: Suggestion[]; source: string | undefined; dynamic: MockCompletion['dynamic'] };

/** Commands, context actions and the chat bridge: invocations, parsing, completion and printed lines. */
export class ChatCommands {
  /** Every line bots printed with `chat.print`, oldest first. */
  readonly printed: MockChatLine[] = [];
  private nextInvocation = 1;
  private readonly invocations = new Pending<MockInvocationResult>();
  /** `command.complete` requests awaiting `command.suggestions`, by envelope request id. */
  private readonly completions = new Pending<CompletionAnswer>();
  /** Dynamic answers by owner, command revision, path, argument, earlier arguments and input prefix. */
  private readonly completionCache = new Map<string, Suggestion[]>();

  constructor(private readonly core: ModCore) {}

  /** A fresh invocation id for an event that expects no result. */
  invocationId(): number {
    return this.nextInvocation++;
  }

  /** Allocates an invocation id, sends with it, and resolves with the bot's `invocation.result`. */
  invoke(s: Session, timeoutMs: number, send: (invocation: number) => void): Promise<MockInvocationResult> {
    const invocation = this.nextInvocation++;
    // The player ran something of this owner, so what it shows next was asked for.
    this.core.gate.asked(s.owner, this.core.clock.now());
    return this.invocations.expect(
      invocation,
      s.owner,
      {},
      timeoutMs,
      () => new BridgeError('timeout', `mock: invocation ${invocation} got no result within ${timeoutMs} ms`),
      () => send(invocation),
    );
  }

  invokeAction(key: string, target: Target, timeoutMs: number, opts: { source?: string; bots?: readonly string[] } = {}): Promise<MockInvocationResult> {
    const s = this.core.ownerSession(key, 'core:action');
    return this.invoke(s, timeoutMs, (invocation) =>
      this.core.sendSession(s, 'action.invoked', { action: key, invocation, target, source: opts.source, bots: opts.bots === undefined ? undefined : [...opts.bots] }),
    );
  }

  screenEvent(key: string, item: string, value: boolean | number | undefined, timeoutMs: number): Promise<MockInvocationResult> {
    const s = this.core.ownerSession(key, 'core:screen');
    checkScreenItem(this.core, key, item);
    return this.invoke(s, timeoutMs, (invocation) => {
      const p: MessagePayloads['screen.event'] = { screen: key, item, invocation };
      if (value !== undefined) p.value = value;
      this.core.sendSession(s, 'screen.event', p);
    });
  }

  invokeCommand(key: string, args: Record<string, unknown>, timeoutMs: number, path: readonly string[], bots: readonly string[] = []): Promise<MockInvocationResult> {
    const s = this.core.ownerSession(key, 'core:command');
    const orders = this.core.store.data(key, 'core:command')?.orders === true;
    if (orders && bots.length === 0) return Promise.reject(new BridgeError('invalid_message', `mock: ${key} is an order: say which bots it is for`));
    if (!orders && bots.length > 0) return Promise.reject(new BridgeError('invalid_message', `mock: ${key} is not an order and takes no bots`));
    return this.sendCommand(s, key, path, args, timeoutMs, undefined, bots);
  }

  async run(name: string, line: string, timeoutMs: number): Promise<MockCommandOutcome> {
    const to = this.address(name);
    const word = tokenize(line)[0];
    const hit = word === undefined ? undefined : this.firstWith(to.sets, word.text);
    if (hit === undefined) {
      const who = to.sets[0]?.name ?? to.name;
      const error = word === undefined ? `Type a command for ${who}` : `${who} has no command '${word.text}'`;
      return { status: 'error', command: undefined, path: [], error, start: word?.start ?? 0, end: word?.end ?? line.length };
    }
    const { set, cmd } = hit;
    const parsed = parseLine(rootNode(cmd.data), line, this.selectorHook(set.session, cmd.key), this.origin());
    switch (parsed.kind) {
      case 'error':
        return { status: 'error', command: cmd.key, path: parsed.path, error: parsed.error, start: parsed.start, end: parsed.end };
      case 'usage':
        return { status: 'usage', command: cmd.key, path: parsed.path, usage: parsed.usage };
      case 'invoke': {
        const result = await this.sendCommand(set.session, cmd.key, parsed.path, parsed.args, timeoutMs, 'command', set.bots);
        return { status: 'invoked', command: cmd.key, path: parsed.path, args: parsed.args, result };
      }
    }
  }

  async complete(name: string, line: string): Promise<MockCompletion> {
    const to = this.address(name);
    const decls: MockDeclaration<'core:command'>[] = [];
    for (const set of to.sets) for (const d of set.decls) if (!decls.some((x) => x.data.name === d.data.name)) decls.push(d);
    const owner = (root: string): CommandSet | undefined => to.sets.find((set) => set.decls.some((d) => d.data.name === root));
    const keyOf = (root: string): string => decls.find((d) => d.data.name === root)?.key ?? '';
    const plan = planCompletion(
      decls.map((d) => rootNode(d.data)),
      line,
      (root) => this.selectorHook(owner(root.name)?.session ?? to.sets[0]!.session, keyOf(root.name)),
      this.origin(),
    );
    switch (plan.kind) {
      case 'none':
        return { start: plan.start, suggestions: [], source: undefined, dynamic: undefined, error: plan.error };
      case 'words':
        return { start: plan.start, suggestions: plan.words, source: undefined, dynamic: undefined, error: undefined };
      case 'arg': {
        const set = owner(plan.root.name)!;
        const r = await this.argSuggestions(set.session, keyOf(plan.root.name), plan, set.bots);
        return { start: plan.start, suggestions: [...plan.words, ...r.suggestions], source: r.source, dynamic: r.dynamic, error: undefined };
      }
    }
  }

  async sendChat(line: string): Promise<MockChatOutcome> {
    const answered = this.answerPrompt(line);
    if (answered !== undefined) return answered;
    const prefix = this.core.opts.chatPrefix ?? '@';
    if (!line.startsWith(prefix)) return { captured: false, text: line };
    const rest = line.slice(prefix.length);
    if (rest.startsWith(prefix)) return { captured: false, text: rest };
    let space = 0;
    while (space < rest.length && !isSpace(rest[space]!)) space++;
    const name = rest.slice(0, space);
    const text = strip(rest.slice(space));
    if (name === '') return { captured: false, text: line };
    const book = this.addressees();
    const found = book.find((a) => a.name.toLowerCase() === name.toLowerCase());
    if (found === undefined) {
      // Most likely a typo of a name that would match: kept from the server, nothing is delivered.
      const closest = closestName(book, name);
      if (closest === undefined) {
        // A bot the bridge knows is meant, even when it cannot be reached: never sent as chat.
        const why = this.unreachable(name);
        return why === undefined ? { captured: false, text: line } : { captured: true, broadcast: false, deliveries: [], unknown: why };
      }
      const corrected = prefix + closest + rest.slice(name.length);
      return { captured: true, broadcast: false, deliveries: [], mistyped: { name, closest, corrected, asChat: prefix + line } };
    }
    const chat = (s: Session): boolean => this.core.devices.granted(s, 'chat');
    const to: Addressee = { ...found, sets: found.sets.filter((set) => chat(set.session)), chatTo: found.chatTo.filter(chat) };
    const broadcast = to.kind === 'all' || to.kind === 'group';
    const unknown = (why: string): MockChatOutcome => ({ captured: true, broadcast, deliveries: [], unknown: why });
    if (to.sets.length === 0 && to.chatTo.length === 0) return unknown(`${name} runs on a device without the chat permission`);
    if (text === '') return unknown(`Type a message or command after ${prefix}${name}`);
    return this.deliver(line, text, to, broadcast);
  }

  /**
   * Parses the text against every command set of the address: each set whose command parses runs
   * it. When none does, a grouping node shows its help; otherwise the text goes out as
   * `chat.message`, with the reason when it named a command whose arguments did not parse.
   */
  private async deliver(line: string, text: string, to: Addressee, broadcast: boolean): Promise<MockChatOutcome> {
    const word = tokenize(text)[0];
    const runs: { set: CommandSet; cmd: MockDeclaration<'core:command'>; path: string[]; args: Record<string, unknown> }[] = [];
    let help: MockChatDelivery | undefined;
    let commandError: string | undefined;
    for (const set of to.sets) {
      const cmd = word === undefined ? undefined : this.commandIn(set, word.text);
      if (cmd === undefined) continue;
      const parsed = parseLine(rootNode(cmd.data), text, this.selectorHook(set.session, cmd.key), this.origin());
      if (parsed.kind === 'invoke') runs.push({ set, cmd, path: parsed.path, args: parsed.args });
      else if (parsed.kind === 'usage') help ??= { owner: set.session.owner, routed: 'usage', command: cmd.key, path: parsed.path, usage: parsed.usage };
      else commandError ??= `${cmd.data.name}: ${parsed.error}`;
    }
    if (runs.length > 0) {
      const deliveries = await Promise.all(runs.map((r) => this.deliverCommand(r.set, r.cmd, r.path, r.args)));
      return { captured: true, broadcast, deliveries };
    }
    if (help !== undefined) return { captured: true, broadcast, deliveries: [help] };
    const now = this.core.clock.now();
    for (const s of to.chatTo) {
      // The player spoke to it, so what it opens next was asked for.
      this.core.gate.asked(s.owner, now);
      this.core.sendSession(s, 'chat.message', { text, raw: line, broadcast });
    }
    const deliveries = to.chatTo.map((s) => ({ owner: s.owner, routed: 'message' as const, text }));
    return { captured: true, broadcast, deliveries, ...(commandError === undefined ? {} : { commandError }) };
  }

  /** While a `chat` surface prompt for text or a confirmation is active, the next line answers it. */
  private answerPrompt(line: string): MockChatOutcome | undefined {
    const active = this.core.inputs.active();
    if (active === undefined || active.type !== 'prompt' || active.request.surface !== 'chat') return undefined;
    const done = (error?: string): MockChatOutcome => ({ captured: true, broadcast: false, deliveries: [], prompt: { id: active.id, ...(error === undefined ? {} : { error }) } });
    if (active.kind === 'text') {
      try {
        this.core.inputs.resolvePrompt(active.id, line, 'chat');
      } catch (e) {
        return done(toBridgeError(e).message.replace(/^mock: /, ''));
      }
      return done();
    }
    if (active.kind !== 'confirm') return undefined;
    const word = strip(line).toLowerCase();
    const answer = word === 'yes' || word === 'y' ? true : word === 'no' || word === 'n' ? false : undefined;
    if (answer === undefined) return done('Answer yes or no');
    this.core.inputs.resolvePrompt(active.id, answer, 'chat');
    return done();
  }

  /** Where the player stands, for `~` coordinates. */
  private origin(): Origin {
    return this.core.queries.world.player ?? { x: 0, y: 0, z: 0 };
  }

  /** A bot printed a chat line. Only an attached bot's line is shown; a line is at most 4096 characters. */
  onPrint(s: Session, p: MessagePayloads['chat.print']): void {
    if (!this.core.player.attachState(s).attached || p.spans.length === 0) return;
    const length = p.spans.reduce((n, span) => n + span.text.length, 0);
    if (length > MAX_LINE_CHARS) return this.core.sendSessionError(s, 'invalid_message', `a chat line takes at most ${MAX_LINE_CHARS} characters`, undefined, Messages['chat.print'].id);
    const line: MockChatLine = { owner: s.owner, spans: p.spans };
    this.printed.push(line);
    this.core.emit('chatPrinted', line);
  }

  onInvocationResult(s: Session, p: MessagePayloads['invocation.result']): void {
    this.invocations.answer(p.invocation, s.owner, { ok: p.ok, message: p.message, ...(p.quiet === true ? { quiet: true as const } : {}) });
  }

  /** Settles a pending `command.complete`; `suggestions` is `undefined` for an error response. */
  onCompletionResponse(s: Session, header: EnvelopeHeader, suggestions: Suggestion[] | undefined): void {
    if (header.req === undefined || (header.flags & Flags.RESPONSE) === 0) return;
    // Answers after the deadline are dropped.
    this.completions.answer(header.req, s.owner, { status: suggestions === undefined ? 'failed' : 'answered', suggestions: suggestions ?? [] });
  }

  clear(): void {
    this.invocations.clear();
    this.completions.clear();
  }

  /** Runs one command set's command for an addressed chat line. */
  private async deliverCommand(set: CommandSet, cmd: MockDeclaration<'core:command'>, path: string[], args: Record<string, unknown>): Promise<MockChatDelivery> {
    const owner = set.session.owner;
    const result = await this.sendCommand(set.session, cmd.key, path, args, INVOCATION_TIMEOUT_MS, 'chat', set.bots);
    return {
      owner,
      routed: 'command',
      command: cmd.key,
      ...(path.length === 0 ? {} : { path }),
      args,
      ...(set.bots.length === 0 ? {} : { bots: set.bots }),
      result,
    };
  }

  /** Sends `command.invoked`; `path` is left out when empty, `source` says how the player ran it, `bots` names an order's bots. */
  private sendCommand(s: Session, key: string, path: readonly string[], args: Record<string, unknown>, timeoutMs: number, source?: string, bots: readonly string[] = []): Promise<MockInvocationResult> {
    return this.invoke(s, timeoutMs, (invocation) => {
      const p: MessagePayloads['command.invoked'] = { command: key, invocation, args };
      if (path.length > 0) p.path = [...path];
      if (source !== undefined) p.source = source;
      if (bots.length > 0) p.bots = [...bots];
      this.core.sendSession(s, 'command.invoked', p);
    });
  }

  /** Why a bot or group the bridge knows reaches nobody now, as the real mod says it; undefined for other names. */
  private unreachable(name: string): string | undefined {
    const lower = name.toLowerCase();
    for (const c of this.core.store.declarations('core:crew', '~crew/')) {
      const crewBot = c.data.bots.find((b) => b.toLowerCase() === lower);
      if (crewBot !== undefined) return `${crewBot} is offline`;
      const group = (c.data.groups ?? []).find((g) => g.name.toLowerCase() === lower);
      if (group !== undefined) return `None of ${group.name} is online`;
    }
    return undefined;
  }

  /**
   * Every name the player may address, as the real mod builds it: `all` while a bot is attached,
   * live bots in any world (their own commands, then the orders of each crew they are in), live controllers
   * (their own commands), then crew groups not named like any of those.
   */
  addressees(): Addressee[] {
    const live = [...this.core.sessions.values()].filter((s) => s.conn !== null && isLive(s));
    // Every live bot, in any world, as the real mod builds it: the world decides what an order does, not who may give it.
    const bots = live.filter((s) => s.kind === 'bot');
    const nameOf = (s: Session): string => (s.kind === 'bot' ? (s.identity?.username ?? s.name) : s.name);
    const byName = new Map(bots.map((b) => [nameOf(b).toLowerCase(), b] as const));
    const commands = this.core.store.declarations('core:command', '~cmd/');
    const orders = this.core.store.declarations('core:command', '~order/').filter((d) => d.data.orders === true);
    const own = (s: Session): CommandSet => ({ name: nameOf(s), session: s, decls: commands.filter((c) => c.key.startsWith(s.prefix)), bots: [] });
    const crews = this.core.store.declarations('core:crew', '~crew/').flatMap((c) => {
      const session = live.find((s) => c.key.startsWith(s.prefix));
      return session === undefined ? [] : [{ session, data: c.data, orders: orders.filter((o) => o.key.startsWith(session.prefix)) }];
    });
    const present = (names: readonly string[]): Session[] => [...new Set(names.flatMap((n) => byName.get(n.toLowerCase()) ?? []))];
    const out: Addressee[] = [];
    const taken = new Set<string>();
    if (bots.length > 0) {
      const sets = bots.map(own);
      for (const crew of crews) {
        const members = present(crew.data.bots);
        if (crew.orders.length > 0 && members.length > 0) sets.push({ name: crew.session.name, session: crew.session, decls: crew.orders, bots: members.map(nameOf) });
      }
      out.push({ name: 'all', kind: 'all', sets, chatTo: bots });
      taken.add('all');
    }
    for (const b of bots) {
      const sets = [own(b)];
      for (const crew of crews) {
        if (crew.orders.length > 0 && present(crew.data.bots).includes(b)) sets.push({ name: nameOf(b), session: crew.session, decls: crew.orders, bots: [nameOf(b)] });
      }
      out.push({ name: nameOf(b), kind: 'bot', sets, chatTo: [b] });
      taken.add(nameOf(b).toLowerCase());
    }
    for (const c of live.filter((s) => s.kind === 'controller')) {
      if (taken.has(c.name.toLowerCase())) continue;
      out.push({ name: c.name, kind: 'controller', sets: [own(c)], chatTo: [c] });
      taken.add(c.name.toLowerCase());
    }
    for (const crew of crews) {
      for (const g of crew.data.groups ?? []) {
        const members = present(g.members);
        if (taken.has(g.name.toLowerCase()) || members.length === 0) continue;
        const sets: CommandSet[] = crew.orders.length === 0 ? [] : [{ name: g.name, session: crew.session, decls: crew.orders, bots: members.map(nameOf) }];
        const known = out.find((a) => a.kind === 'group' && a.name.toLowerCase() === g.name.toLowerCase());
        if (known === undefined) out.push({ name: g.name, kind: 'group', sets, chatTo: members });
        else {
          known.sets.push(...sets);
          for (const m of members) if (!known.chatTo.includes(m)) known.chatTo.push(m);
        }
      }
    }
    return out;
  }

  /** An address for `/bridge <name>`: what {@link addressees} lists, or a connected bot's own commands. */
  private address(name: string): Addressee {
    const n = name.toLowerCase();
    const found = this.addressees().find((a) => a.name.toLowerCase() === n);
    if (found !== undefined) return found;
    for (const s of this.core.sessions.values()) {
      if ((s.kind !== 'bot' && s.kind !== 'controller') || s.conn === null || !isLive(s)) continue;
      if (s.name.toLowerCase() === n || s.identity?.username.toLowerCase() === n || s.owner === name) {
        return { name: s.name, kind: s.kind === 'bot' ? 'bot' : 'controller', sets: [{ name: s.name, session: s, decls: this.core.store.declarations('core:command', '~cmd/').filter((c) => c.key.startsWith(s.prefix)), bots: [] }], chatTo: [s] };
      }
    }
    throw new BridgeError('disconnected', `mock: no connected bot named ${name}`);
  }

  /** The command a typed word names in a set: an exact name first, then an exact alias, then either ignoring case. */
  private commandIn(set: CommandSet, word: string): MockDeclaration<'core:command'> | undefined {
    const data = matchNode(set.decls.map((c) => c.data), word);
    return data === undefined ? undefined : set.decls.find((c) => c.data === data);
  }

  private firstWith(sets: readonly CommandSet[], word: string): { set: CommandSet; cmd: MockDeclaration<'core:command'> } | undefined {
    for (const set of sets) {
      const cmd = this.commandIn(set, word);
      if (cmd !== undefined) return { set, cmd };
    }
    return undefined;
  }

  /** Resolved on the client before anything reaches the bot. */
  private selectorHook(s: Session, command: string): SelectorHook {
    return (r) => {
      const resolve = this.core.opts.resolveSelector;
      if (resolve === undefined) return `cannot resolve ${r.text} without a resolveSelector hook`;
      try {
        return resolve({ text: r.text, kind: r.kind, options: r.options, bot: s.owner, command, arg: r.arg.name, type: r.arg.type });
      } catch (e) {
        return toBridgeError(e).message;
      }
    };
  }

  private async argSuggestions(s: Session, key: string, plan: Extract<CompletionPlan, { kind: 'arg' }>, bots: readonly string[] = []): Promise<ArgSuggestions> {
    const a = plan.arg;
    const spec = a.completion;
    const source = spec?.source ?? (a.type === 'choice' && a.choices !== undefined ? 'choices' : a.type === 'bool' ? 'bool' : a.type === 'bot' ? 'bots' : undefined);
    const limit = spec?.limit ?? DEFAULT_COMPLETION_LIMIT;
    const done = (words: readonly (string | Suggestion)[]): ArgSuggestions => ({ suggestions: filterSuggestions(words, plan.input, limit), source, dynamic: undefined });
    switch (source) {
      case undefined:
        return { suggestions: [], source, dynamic: undefined };
      case 'choices':
        return done(a.choices ?? []);
      case 'bool':
        return done(['true', 'false']);
      case 'bots':
        return done([...this.core.sessions.values()].filter((x) => x.kind === 'bot' && x.conn !== null && isLive(x)).map((x) => x.identity?.username ?? x.name));
      case 'dynamic': {
        const rev = this.core.store.get(key)?.rev ?? 0;
        const cacheKey = JSON.stringify([s.owner, key, rev, plan.path, a.name, plan.args, plan.input, bots]);
        const hit = this.completionCache.get(cacheKey);
        if (hit !== undefined) return { suggestions: hit.slice(0, limit), source, dynamic: 'cached' };
        const r = await this.requestCompletion(s, { command: key, path: plan.path, arg: a.name, input: plan.input, args: plan.args, ...(bots.length === 0 ? {} : { bots: [...bots] }) });
        if (r.status === 'answered') this.completionCache.set(cacheKey, r.suggestions);
        return { suggestions: r.suggestions.slice(0, limit), source, dynamic: r.status };
      }
      default:
        return done(this.core.opts.completionStubs?.[source] ?? []);
    }
  }

  private requestCompletion(s: Session, p: MessagePayloads['command.complete']): Promise<CompletionAnswer> {
    const req = this.core.nextRequest++;
    return this.completions.expect(
      req,
      s.owner,
      {},
      this.core.opts.completionTimeoutMs ?? COMPLETION_TIMEOUT_MS,
      () => ({ status: 'timeout', suggestions: [] }),
      () => this.core.sendSession(s, 'command.complete', p, { req }),
    );
  }
}

/** Levenshtein distance; names are short. */
export function editDistance(a: string, b: string): number {
  let prev = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++) cur[j] = Math.min(cur[j - 1]! + 1, prev[j]! + 1, prev[j - 1]! + (a[i - 1] === b[j - 1] ? 0 : 1));
    prev = cur;
  }
  return prev[b.length]!;
}

/**
 * The addressable name a typed one is most likely a typo of, as the mod decides it: one edit away
 * (two from six letters on), or one starting with the other. `all` is never suggested.
 */
function closestName(book: readonly Addressee[], typed: string): string | undefined {
  const lower = typed.toLowerCase();
  let best: string | undefined;
  let bestDistance = Infinity;
  for (const a of book) {
    if (a.kind === 'all') continue;
    const known = a.name.toLowerCase();
    const d = editDistance(lower, known);
    const close = (d <= (lower.length >= 6 ? 2 : 1) && d < lower.length) || (known.length >= 3 && lower.startsWith(known)) || (lower.length >= 2 && known.startsWith(lower));
    if (close && d < bestDistance) {
      best = a.name;
      bestDistance = d;
    }
  }
  return best;
}
