import { BridgeError, type ChatSpan } from '@mineflayer-bridge/protocol';
import { guard, type InputHost } from './input.ts';
import type { ChatApi, ChatClickAction, ChatContent, ChatListener, ChatSpanInput } from './input-types.ts';
import { toColor } from './util.ts';

const CLICK_ACTIONS: Readonly<Record<ChatClickAction['action'], string>> = {
  action: 'run_action',
  command: 'run_command',
  order: 'run_order',
  suggest: 'suggest',
  copy: 'copy',
  url: 'open_url',
  bridge: 'bridge',
};

/** A validated span whose `action` click still names a local action id. */
type PendingSpan = { span: ChatSpan; action: string | undefined };

/**
 * Converts the styling of a span (text, colour, bold, italic, hover) to the wire form, ignoring its
 * click. Shared by chat lines and item names and lore.
 * @throws BridgeError `invalid_message` for a span without a text string or with a bad colour.
 */
export function styledSpan(input: string | ChatSpanInput): ChatSpan {
  if (typeof input === 'string') return { text: input };
  if (typeof input !== 'object' || input === null || typeof input.text !== 'string') throw new BridgeError('invalid_message', 'a chat span needs a text string');
  const span: ChatSpan = { text: input.text };
  if (input.color !== undefined) span.color = toColor(input.color);
  if (input.bold === true) span.bold = true;
  if (input.italic === true) span.italic = true;
  if (input.underlined === true) span.underlined = true;
  if (input.strikethrough === true) span.strikethrough = true;
  if (input.obfuscated === true) span.obfuscated = true;
  if (input.hover !== undefined) span.hover = String(input.hover);
  return span;
}

/** The parts of chat content: one string or span, or a list of them. */
export function contentParts(content: ChatContent): readonly (string | ChatSpanInput)[] {
  return typeof content === 'string' || !Array.isArray(content) ? [content as string | ChatSpanInput] : (content as readonly (string | ChatSpanInput)[]);
}

function toSpan(input: string | ChatSpanInput): PendingSpan {
  if (typeof input === 'string') return { span: { text: input }, action: undefined };
  const span = styledSpan(input);
  let action: string | undefined;
  if (input.click !== undefined) {
    const wire = CLICK_ACTIONS[input.click.action];
    if (wire === undefined) throw new BridgeError('invalid_message', `unknown chat click action ${String(input.click.action)}; use action, command, order, suggest, copy, url or bridge`);
    const value = input.click.value;
    if (typeof value !== 'string' || value === '') throw new BridgeError('invalid_message', 'a chat click needs a non-empty value');
    if (input.click.action === 'url' && !/^https?:\/\/\S+$/i.test(value)) throw new BridgeError('invalid_message', `a url click needs an http or https link: ${value}`);
    if (input.click.action === 'bridge' && !/^(menu|settings|(attach|detach)(:\S.*)?)$/.test(value)) {
      throw new BridgeError('invalid_message', `a bridge click is attach, detach, attach:<bot>, detach:<bot>, menu or settings: ${value}`);
    }
    if (input.click.action === 'order' && !/^\s*\S+\s+\S/.test(value)) {
      throw new BridgeError('invalid_message', `an order click is <name> <command line>, such as Miner stop: ${value}`);
    }
    if (input.click.action === 'action') {
      if (value.includes('/')) throw new BridgeError('invalid_message', `a chat click action names an action id of this scope, without /: ${value}`);
      action = value;
    }
    span.click = { action: wire, value };
  }
  return { span, action };
}

/** The chat bridge of one scope. */
export class ScopeChat implements ChatApi {
  private readonly listeners = new Set<ChatListener>();
  private readonly offs: (() => void)[];
  private disposed = false;

  constructor(
    private readonly host: InputHost,
    private readonly scope: string,
  ) {
    this.offs = [
      host.events.on('chat', (p) => {
        const info = { raw: p.raw, broadcast: p.broadcast ?? false };
        for (const fn of [...this.listeners]) guard(host, () => fn(p.text, info));
      }),
    ];
  }

  print(content: ChatContent): void {
    const spans = contentParts(content).map(toSpan);
    if (spans.every((s) => s.span.text === '')) throw new BridgeError('invalid_message', 'a chat line needs some text');
    if (this.disposed) return;
    const owner = this.host.core.owner;
    // A chat line is about now, so nothing is kept for a session that is not open.
    if (owner === undefined || !this.host.core.connected) {
      this.host.log.debug('chat line dropped: the session is not open');
      return;
    }
    // References to declarations carry the full object key.
    const wire = spans.map(({ span, action }) => (action === undefined ? span : { ...span, click: { action: 'run_action', value: `${owner}/${this.scope}/~action/${action}` } }));
    this.host.core.sendIfOpen('chat.print', this.host.link.encode('chat.print', { spans: wire }));
  }

  on(event: 'message', fn: ChatListener): () => void {
    if (event !== 'message') throw new BridgeError('invalid_message', `unknown chat event ${String(event)}`);
    if (typeof fn !== 'function') throw new BridgeError('invalid_message', 'chat listener must be a function');
    if (!this.disposed) this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  /** Drops listeners and unsent lines; called when the scope unloads. */
  dispose(): void {
    this.disposed = true;
    for (const off of this.offs) off();
    this.listeners.clear();
  }
}
