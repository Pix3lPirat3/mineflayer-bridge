import { BridgeError, type EnvelopeHeader, type MessagePayloads, type PickResult } from '@mineflayer-bridge/protocol';
import type { ModCore } from './core.ts';
import type { Session } from './session.ts';
import type { InputOutcome, MockInputRequest } from './types.ts';
import type { DistributiveOmit } from './util.ts';

interface InputEntry {
  session: Session;
  /** Header of the original request, so error responses can name it. */
  header: EnvelopeHeader;
  info: MockInputRequest;
  /** Clock time at which the request became the active one. */
  activatedAt?: number;
}

/** What a session message contributes to a queued request; the queue fills in the rest. */
type RequestInfo = DistributiveOmit<MockInputRequest, 'id' | 'req' | 'owner' | 'urgent' | 'receivedAt' | 'deadlineAt'>;

/** The player's input queue of picks, prompts, world menus and forms. The head is active. */
export class InputQueue {
  private readonly entries: InputEntry[] = [];
  private nextId = 1;

  constructor(private readonly core: ModCore) {}

  active(): MockInputRequest | undefined {
    this.core.sweep();
    return this.head()?.info;
  }

  /** The active request, stamped with the time it was first seen at the head. */
  private head(): InputEntry | undefined {
    const e = this.entries[0];
    if (e !== undefined) e.activatedAt ??= this.core.clock.now();
    return e;
  }

  list(): MockInputRequest[] {
    this.core.sweep();
    return this.entries.map((e) => e.info);
  }

  onPick(s: Session, header: EnvelopeHeader, p: MessagePayloads['pick.request']): void {
    this.enqueue(s, header, { type: 'pick', kind: p.kind, request: p }, p.timeoutMs ?? 60_000, p.urgent === true);
  }

  onPrompt(s: Session, header: EnvelopeHeader, p: MessagePayloads['prompt.request']): void {
    this.enqueue(s, header, { type: 'prompt', kind: p.kind, request: p, surface: this.surface(p.surface ?? 'hud', 'hud') }, p.timeoutMs ?? 30_000, false);
  }

  onWorldMenu(s: Session, header: EnvelopeHeader, p: MessagePayloads['world-menu.request']): void {
    this.enqueue(s, header, { type: 'world-menu', kind: 'world-menu', request: p }, p.timeoutMs ?? 60_000, false);
  }

  onForm(s: Session, header: EnvelopeHeader, p: MessagePayloads['form.open']): void {
    const timeoutMs = Math.min(p.timeoutMs ?? 3_600_000, this.core.opts.formTimeoutMs ?? Infinity);
    this.enqueue(s, header, { type: 'form', kind: 'form', request: p, surface: this.surface(p.surface ?? 'screen', 'screen') }, timeoutMs, false);
  }

  /** The bot withdrew a request with `pick.cancel`, `prompt.cancel`, `form.close` or `world-menu.cancel`. */
  onWithdrawn(s: Session, request: number): void {
    const i = this.entries.findIndex((e) => e.session === s && e.info.req === request);
    if (i < 0) return;
    const [e] = this.entries.splice(i, 1);
    this.core.emit('inputSettled', { request: e!.info, outcome: 'withdrawn' });
  }

  resolvePick(id: number, result: PickResult | PickResult['value']): void {
    const e = this.take(id, 'pick');
    const r = isPickResult(result) ? result : ({ type: e.info.kind, value: result } as PickResult);
    this.answer(e, 'pick.result', { result: r });
  }

  /** Answers a prompt; `answeredOn` names the surface that took the answer when it is not the one shown. */
  resolvePrompt(id: number, value: unknown, answeredOn?: string): void {
    const found = this.find(id, 'prompt');
    if (found.info.type === 'prompt') checkAnswer(found.info.kind, found.info.request, value, answeredOn ?? found.info.surface);
    const e = this.take(id, 'prompt');
    const surface = answeredOn ?? (e.info.type === 'prompt' ? e.info.surface : undefined);
    this.answer(e, 'prompt.result', { value, timedOut: false, surface });
  }

  resolveWorldMenu(id: number, entry: string): void {
    const found = this.find(id, 'world-menu');
    if (found.info.type === 'world-menu' && !found.info.request.entries.some((x) => x.id === entry)) {
      throw new BridgeError('invalid_message', `mock: world menu ${id} has no entry ${entry}`);
    }
    this.answer(this.take(id, 'world-menu'), 'world-menu.result', { entry });
  }

  resolveForm(id: number, values: Record<string, unknown>): void {
    this.answer(this.take(id, 'form'), 'form.result', { values });
  }

  pressFormButton(id: number, button: string): void {
    const found = this.entries.find((x) => x.info.id === id);
    if (found?.info.type === 'form' && !(found.info.request.buttons ?? []).some((b) => b.id === button)) {
      throw new BridgeError('invalid_message', `mock: form ${id} has no button ${button}`);
    }
    this.answer(this.take(id, 'form'), 'form.result', { values: {}, button });
  }

  cancel(id: number): void {
    const e = this.take(id, undefined);
    this.core.gate.dismissed(e.session.owner, this.core.clock.now());
    this.fail(e, 'cancelled', 'cancelled by the player', 'cancelled');
  }

  timeOut(id: number): void {
    this.expire(this.take(id, undefined));
  }

  /** Expires every request whose deadline passed. */
  sweep(now: number): void {
    for (const e of this.entries.filter((x) => x.info.deadlineAt <= now)) {
      this.entries.splice(this.entries.indexOf(e), 1);
      this.expire(e);
    }
    // The player's chat is not held longer than the window; an unanswered capture counts as dismissed.
    const a = this.head();
    if (a !== undefined && a.info.type === 'prompt' && a.info.request.surface === 'chat' && now - a.activatedAt! >= (this.core.opts.chatPromptWindowMs ?? 60_000)) {
      this.entries.shift();
      this.expire(a);
      this.core.gate.dismissed(a.session.owner, now);
    }
    this.head();
  }

  /** Drops the requests of a session that went away. */
  drop(s: Session): void {
    for (const e of this.entries.filter((x) => x.session === s)) {
      this.entries.splice(this.entries.indexOf(e), 1);
      this.core.emit('inputSettled', { request: e.info, outcome: 'dropped' });
    }
  }

  /** Queue an input request, or refuse it when the bot is not attached or not matched. */
  private enqueue(s: Session, header: EnvelopeHeader, info: RequestInfo, timeoutMs: number, urgent: boolean): void {
    if (header.req === undefined) return this.core.sendSessionError(s, 'invalid_message', `${info.type} request needs a request id`, header);
    const { attached, worldMatched } = this.core.player.attachState(s);
    const worldScoped =
      info.type === 'pick' ? info.kind !== 'item' : info.type === 'world-menu' || (info.type === 'prompt' && (info.request.placement === 'world' || info.surface === 'world'));
    const code = !attached ? 'not_attached' : worldScoped && !worldMatched ? 'world_mismatch' : undefined;
    if (code !== undefined) {
      this.core.emit('inputRejected', { session: this.core.info(s), type: info.type, code });
      return this.core.sendSessionError(s, code, code === 'not_attached' ? 'the player has not attached this bot' : 'the player is in another world', header);
    }
    const max = this.core.opts.quotas?.inputRequests ?? 16;
    if (this.entries.filter((e) => e.session === s).length >= max) {
      this.core.emit('inputRejected', { session: this.core.info(s), type: info.type, code: 'quota_exceeded' });
      return this.core.sendSessionError(s, 'quota_exceeded', `this session already has ${max} input requests waiting`, header, undefined, undefined, true);
    }
    if (this.core.refuseSurface(s, header, header.type, info.type)) return;
    const now = this.core.clock.now();
    const entry: InputEntry = {
      session: s,
      header: { flags: 0, type: header.type, session: header.session, req: header.req },
      info: { ...info, id: this.nextId++, req: header.req, owner: s.owner, urgent, receivedAt: now, deadlineAt: now + timeoutMs } as MockInputRequest,
    };
    // An urgent request goes to the front of the waiting requests, behind earlier urgent ones, and never displaces the active one.
    let at = this.entries.length;
    if (urgent && this.entries.length > 0) {
      at = 1;
      while (at < this.entries.length && this.entries[at]!.info.urgent) at++;
    }
    this.entries.splice(at, 0, entry);
    this.head();
    this.core.emit('inputRequest', entry.info);
  }

  /** A surface the mod cannot show falls back. */
  private surface(requested: string, fallback: string): string {
    const surfaces = this.core.opts.surfaces;
    return surfaces === undefined || surfaces.includes(requested) ? requested : fallback;
  }

  private answer<N extends 'pick.result' | 'prompt.result' | 'world-menu.result' | 'form.result'>(e: InputEntry, type: N, payload: MessagePayloads[N]): void {
    this.core.sendSession(e.session, type, payload, { req: e.info.req, response: true });
    // An answer ends the cooldown, and what the bot shows next follows from it.
    this.core.gate.completed(e.session.owner);
    this.core.gate.asked(e.session.owner, this.core.clock.now());
    this.core.emit('inputSettled', { request: e.info, outcome: 'resolved' });
  }

  private find(id: number, type: MockInputRequest['type'] | undefined): InputEntry {
    const e = this.entries.find((x) => x.info.id === id && (type === undefined || x.info.type === type));
    if (e === undefined) throw new BridgeError('invalid_message', `mock: no ${type ?? 'input'} request ${id} is queued`);
    return e;
  }

  private take(id: number, type: MockInputRequest['type'] | undefined): InputEntry {
    const e = this.find(id, type);
    return this.entries.splice(this.entries.indexOf(e), 1)[0]!;
  }

  private fail(e: InputEntry, code: string, message: string, outcome: InputOutcome): void {
    this.core.sendSessionError(e.session, code, message, e.header);
    this.core.emit('inputSettled', { request: e.info, outcome });
  }

  /** A prompt falls back to its default, while picks and forms reject with `timeout`. */
  private expire(e: InputEntry): void {
    if (e.info.type === 'prompt') {
      this.core.sendSession(e.session, 'prompt.result', { value: e.info.request.defaultValue, timedOut: true, surface: e.info.surface }, { req: e.info.req, response: true });
      this.core.emit('inputSettled', { request: e.info, outcome: 'timeout' });
      return;
    }
    this.fail(e, 'timeout', `${e.info.type} timed out`, 'timeout');
  }
}

function isPickResult(r: PickResult | PickResult['value']): r is PickResult {
  return typeof r === 'object' && r !== null && 'type' in r && 'value' in r && Object.keys(r).length === 2;
}

/** The client checks answers before it sends them. */
function checkAnswer(kind: string, r: MessagePayloads['prompt.request'], value: unknown, surface: string): void {
  const fail = (why: string): never => {
    throw new BridgeError('invalid_message', `mock: ${why}`);
  };
  const within = (v: string, what: string): void => {
    if (r.maxLength !== undefined && v.length > r.maxLength) fail(`${what} is longer than ${r.maxLength} characters`);
  };
  switch (kind) {
    case 'choice': {
      // Each surface only lets the player pick what it shows.
      if (surface === 'crafting') {
        const cells = Array.isArray(value) && value.length === 3 && value.every((x) => Array.isArray(x)) ? (value as unknown[][]).flat() : value;
        if (!Array.isArray(cells) || cells.length !== 9 || !cells.every((c) => c === null || typeof c === 'string')) fail('a crafting answer is three rows of three item ids or nulls');
        const palette = r.options?.map((o) => o.id);
        for (const c of cells as (string | null)[]) if (c !== null && palette !== undefined && !palette.includes(c)) fail(`${c} is not one of the crafting items`);
        return;
      }
      if (surface === 'merchant' && r.offers !== undefined) {
        const offer = typeof value === 'number' ? r.offers[value] : r.offers.find((o) => o.id === value);
        if (offer === undefined) return fail(`${String(value)} is not an offer`);
        if (offer.disabled === true) fail(`offer ${offer.id} is disabled`);
        return;
      }
      if ((surface === 'stonecutter' || surface === 'enchanting') && r.options !== undefined) {
        const ok = typeof value === 'number' ? value >= 0 && value < r.options.length : r.options.some((o) => o.id === value);
        if (!ok) fail(`${String(value)} is not one of the options`);
      }
      return;
    }
    case 'lines':
    case 'pages': {
      const max = kind === 'lines' ? 4 : 100;
      if (!Array.isArray(value) || value.length < 1 || value.length > max || !value.every((x) => typeof x === 'string')) fail(`a ${kind} answer is a list of 1 to ${max} strings`);
      for (const v of value as string[]) within(v, kind === 'lines' ? 'a line' : 'a page');
      return;
    }
    case 'text':
      if (typeof value === 'string') within(value, 'the answer');
      return;
    case 'number':
      if (typeof value === 'number' && ((r.min !== undefined && value < r.min) || (r.max !== undefined && value > r.max))) {
        fail(`${value} is outside ${r.min ?? '-inf'} to ${r.max ?? 'inf'}`);
      }
      return;
  }
}
