import { BridgeError, type KindPayloads, type MessagePayloads } from '@mineflayer-bridge/protocol';
import { itemId } from './ext-util.ts';
import { guard, invoke, type InputHost, type Route } from './input.ts';
import type {
  ContainerCloseReason,
  InvocationReturn,
  OpenCloseEvents,
  Screen,
  ScreenButton,
  ScreenCycle,
  ScreenEventContext,
  ScreenItemInput,
  ScreenOptions,
  ScreenRowInput,
  ScreenTabInput,
  ScreenToggle,
} from './input-types.ts';
import type { ScopeObjects } from './scope.ts';
import { resourceId } from './check.ts';
import { asBridgeError, checkKeys, Emitter, toColor } from './util.ts';
import { checkViewId } from './views.ts';

export const SCREEN_DIR = '~screen/';

type WireScreen = KindPayloads['core:screen'];
type WireTab = WireScreen['tabs'][number];
type WireItem = WireTab['rows'][number]['items'][number];
type Handler = (value: unknown, ctx: ScreenEventContext) => InvocationReturn;

const OPTION_KEYS: ReadonlySet<string> = new Set(['title', 'tabs', 'rows', 'home', 'order', 'onOpen', 'onClose']);
const CONTROL_KEYS = ['id', 'tooltip', 'width', 'enabled', 'sound'] as const;
const ITEM_KEYS: Readonly<Record<string, ReadonlySet<string>>> = {
  heading: new Set(['heading']),
  text: new Set(['text', 'color', 'width']),
  button: new Set(['button', 'icon', 'onClick', 'open', ...CONTROL_KEYS]),
  toggle: new Set(['toggle', 'value', 'onChange', ...CONTROL_KEYS]),
  cycle: new Set(['cycle', 'options', 'value', 'onChange', ...CONTROL_KEYS]),
  item: new Set(['item', 'text', 'tooltip', 'width']),
  spacer: new Set(['spacer', 'width']),
};
/** Kind keys in the order they are looked for; `text` comes last because `item` rows carry text too. */
const KINDS = ['heading', 'button', 'toggle', 'cycle', 'item', 'spacer', 'text'] as const;

function kindOf(item: ScreenItemInput): (typeof KINDS)[number] {
  const kind = KINDS.find((k) => k in item);
  if (kind === undefined) throw new BridgeError('invalid_message', `screen item needs one of ${KINDS.join(', ')}`);
  return kind;
}

function width(v: number | undefined, where: string): number | undefined {
  if (v === undefined) return undefined;
  if (!Number.isInteger(v) || v <= 0) throw new BridgeError('invalid_message', `${where}: width must be a positive integer`);
  return v;
}

/** A declared screen of one scope at `~screen/<id>`. */
export class ScreenImpl implements Screen {
  readonly id: string;
  /** Key relative to the owner: `<scope>/~screen/<id>`. */
  readonly rel: string;
  private options!: ScreenOptions;
  private handlers = new Map<string, Handler>();
  /** Values the player set on toggles and cycles of static tabs, applied until the options are replaced. */
  private readonly overrides = new Map<string, unknown>();
  /** A button opens another screen, whose full key needs the owner. */
  private hasLinks = false;
  private opened = false;
  /** Whether a session the mod had open saw this screen open; only then can a restart lose it. */
  private delivered = false;
  private pendingOpen = false;
  private live = true;
  private readonly route: Route;
  private readonly events = new Emitter<OpenCloseEvents>();

  constructor(
    private readonly host: InputHost,
    private readonly objects: ScopeObjects,
    id: string,
    options: ScreenOptions,
    private readonly forget: (screen: ScreenImpl) => void,
  ) {
    checkViewId('screen id', id);
    this.id = id;
    this.rel = `${objects.scope}/${SCREEN_DIR}${id}`;
    this.route = { accepts: 'screen', handle: (payload, type) => this.onMessage(type, payload) };
    this.set(options);
    host.router.set(this.rel, this.route);
  }

  get isOpen(): boolean {
    return this.opened;
  }

  get alive(): boolean {
    return this.live;
  }

  update(patch: Partial<ScreenOptions>): void {
    this.set({ ...this.options, ...patch });
  }

  on<E extends keyof OpenCloseEvents>(event: E, fn: (...args: OpenCloseEvents[E]) => void): () => void {
    return this.events.on(event, fn);
  }

  set(options: ScreenOptions): void {
    if (!this.live) return;
    checkKeys('screen option', options, OPTION_KEYS);
    if (typeof options.title !== 'string') throw new BridgeError('invalid_message', `screen ${this.id} needs a title`);
    if ((options.tabs === undefined) === (options.rows === undefined)) throw new BridgeError('invalid_message', `screen ${this.id} needs tabs or rows, not both`);
    if (options.home !== undefined && options.home !== 'tab' && options.home !== 'menu') throw new BridgeError('invalid_message', `screen home must be tab or menu: ${String(options.home)}`);
    if (options.order !== undefined && !Number.isInteger(options.order)) throw new BridgeError('invalid_message', 'screen order must be an integer');
    const previous = this.options;
    this.options = options;
    this.overrides.clear();
    try {
      this.write();
    } catch (e) {
      if (previous !== undefined) this.options = previous;
      throw e;
    }
  }

  refresh(): void {
    if (!this.live) return;
    try {
      this.write();
    } catch (e) {
      this.host.reportError(asBridgeError(e));
    }
  }

  private tabs(): readonly ScreenTabInput[] {
    const o = this.options;
    if (o.tabs !== undefined) return typeof o.tabs === 'function' ? o.tabs() : o.tabs;
    const rows = typeof o.rows === 'function' ? o.rows() : o.rows!;
    return [{ id: 'main', label: o.title, rows }];
  }

  /** Builds the declaration and its handlers; validates everything before anything is sent. */
  private build(): { data: WireScreen; handlers: Map<string, Handler>; links: boolean } {
    const handlers = new Map<string, Handler>();
    const ids = new Set<string>();
    let links = false;
    const tabs: WireTab[] = this.tabs().map((t, ti) => {
      if (typeof t.label !== 'string') throw new BridgeError('invalid_message', `screen ${this.id}: tab ${ti} needs a label`);
      const tabId = t.id ?? String(ti);
      return {
        id: tabId,
        label: t.label,
        rows: t.rows.map((r: ScreenRowInput, ri) => ({
          items: (Array.isArray(r) ? (r as readonly ScreenItemInput[]) : [r as ScreenItemInput]).map((item, ci) => {
            const wire = this.item(item, `${tabId}.${ri}.${ci}`, handlers);
            if (wire.id !== undefined) {
              if (ids.has(wire.id)) throw new BridgeError('invalid_message', `screen ${this.id}: duplicate item id ${wire.id}`);
              ids.add(wire.id);
            }
            if (wire.open !== undefined) links = true;
            return wire;
          }),
        })),
      };
    });
    const data: WireScreen = { title: this.options.title, tabs };
    if (this.options.home !== undefined) data.home = this.options.home;
    if (this.options.order !== undefined) data.order = this.options.order;
    return { data, handlers, links };
  }

  private item(input: ScreenItemInput, position: string, handlers: Map<string, Handler>): WireItem {
    const kind = kindOf(input);
    const where = `screen ${this.id} item ${position}`;
    checkKeys(`${where} (${kind}) option`, input, ITEM_KEYS[kind]!);
    switch (kind) {
      case 'heading':
        return { kind, text: String((input as { heading: string }).heading) };
      case 'text': {
        const t = input as { text: string; color?: Parameters<typeof toColor>[0]; width?: number };
        const w: WireItem = { kind, text: String(t.text) };
        if (t.color !== undefined) w.color = toColor(t.color);
        const wd = width(t.width, where);
        if (wd !== undefined) w.width = wd;
        return w;
      }
      case 'item': {
        const t = input as { item: string; text?: string; tooltip?: string; width?: number };
        const w: WireItem = { kind, icon: itemId(t.item) };
        if (t.text !== undefined) w.text = t.text;
        if (t.tooltip !== undefined) w.tooltip = t.tooltip;
        const wd = width(t.width, where);
        if (wd !== undefined) w.width = wd;
        return w;
      }
      case 'spacer': {
        const w: WireItem = { kind };
        const wd = width((input as { width?: number }).width, where);
        if (wd !== undefined) w.width = wd;
        return w;
      }
      default:
        return this.control(kind, input as ScreenButton | ScreenToggle | ScreenCycle, position, where, handlers);
    }
  }

  private control(kind: 'button' | 'toggle' | 'cycle', c: ScreenButton | ScreenToggle | ScreenCycle, position: string, where: string,
    handlers: Map<string, Handler>): WireItem {
    const id = c.id ?? position;
    if (typeof id !== 'string' || id === '') throw new BridgeError('invalid_message', `${where}: id must be a non-empty string`);
    const w: WireItem = { kind, id };
    if (c.tooltip !== undefined) w.tooltip = c.tooltip;
    const wd = width(c.width, where);
    if (wd !== undefined) w.width = wd;
    if (c.enabled === false) w.enabled = false;
    if (c.sound !== undefined) w.sound = resourceId('screen sound', c.sound);
    if (kind === 'button') {
      const b = c as ScreenButton;
      w.text = String(b.button);
      if (b.icon !== undefined) w.icon = itemId(b.icon);
      if (b.open !== undefined) w.open = this.linkKey(b.open);
      if (b.onClick !== undefined) {
        const fn = b.onClick;
        handlers.set(id, (_v, ctx) => fn(ctx));
      }
      return w;
    }
    if (kind === 'toggle') {
      const t = c as ScreenToggle;
      const value = this.overrides.has(id) ? this.overrides.get(id) : t.value;
      if (typeof value !== 'boolean') throw new BridgeError('invalid_message', `${where}: toggle value must be a boolean`);
      w.text = String(t.toggle);
      w.value = value;
      const fn = t.onChange;
      handlers.set(id, (v, ctx) => this.accept(id, v, fn === undefined ? undefined : () => fn(v as boolean, ctx)));
      return w;
    }
    const y = c as ScreenCycle;
    if (!Array.isArray(y.options) || y.options.length === 0 || !y.options.every((o) => typeof o === 'string')) {
      throw new BridgeError('invalid_message', `${where}: cycle options must be a non-empty list of strings`);
    }
    const chosen = this.overrides.has(id) ? this.overrides.get(id) : y.value;
    const index = typeof chosen === 'number' ? chosen : y.options.indexOf(chosen as string);
    if (!Number.isInteger(index) || index < 0 || index >= y.options.length) throw new BridgeError('invalid_message', `${where}: cycle value must be one of its options or an index`);
    w.text = String(y.cycle);
    w.options = [...y.options];
    w.value = index;
    const fn = y.onChange;
    const options = [...y.options];
    handlers.set(id, (v, ctx) => this.accept(id, Number(v), fn === undefined ? undefined : () => fn(options[Number(v)]!, ctx)));
    return w;
  }

  /** Runs a value handler; once it accepts, the new value sticks and the screen is built again. */
  private accept(id: string, value: unknown, run: (() => InvocationReturn) | undefined): InvocationReturn {
    const applied = (): void => {
      this.overrides.set(id, value);
      this.refresh();
    };
    if (run === undefined) {
      applied();
      return undefined;
    }
    const result = run();
    if (result instanceof Promise) {
      return result.then((v) => {
        applied();
        return v;
      });
    }
    applied();
    return result;
  }

  private linkKey(target: Screen | string): string {
    if (target instanceof ScreenImpl) return target.rel;
    if (typeof target !== 'string') throw new BridgeError('invalid_message', 'open must be a screen or the id of one in the same scope');
    checkViewId('screen id', target);
    return `${this.objects.scope}/${SCREEN_DIR}${target}`;
  }

  private write(): void {
    const { data, handlers, links } = this.build();
    // References carry the full object key, so they wait for the owner.
    const owner = this.host.core.owner;
    for (const tab of data.tabs) {
      for (const row of tab.rows) {
        for (const item of row.items) {
          if (item.open === undefined) continue;
          if (owner === undefined) delete item.open;
          else item.open = `${owner}/${item.open}`;
        }
      }
    }
    this.handlers = handlers;
    this.hasLinks = links;
    // A screen is shown wherever the player is.
    this.objects.put(`${SCREEN_DIR}${this.id}`, 'core:screen', data, { world: 'global' });
  }

  private send(type: 'screen.open' | 'screen.close'): boolean {
    const owner = this.host.core.owner;
    if (owner === undefined) return false;
    this.host.core.send(type, this.host.link.encode(type, { screen: `${owner}/${this.rel}` }));
    return true;
  }

  /** The full object key, once the owner is known. */
  private objectKey(): string | undefined {
    const owner = this.host.core.owner;
    return owner === undefined ? undefined : `${owner}/${this.rel}`;
  }

  open(): void {
    if (!this.live) return;
    this.pendingOpen = !this.send('screen.open');
    this.delivered = !this.pendingOpen && this.host.core.connected;
    this.host.opens.sent(this, 'screen', () => this.objectKey(), () => {
      this.pendingOpen = false;
      if (this.opened) this.markClosed('refused');
    });
    this.markOpen();
  }

  close(): void {
    if (!this.live) return;
    this.pendingOpen = false;
    if (!this.opened) return;
    this.send('screen.close');
    this.markClosed('bot');
  }

  private markOpen(): void {
    if (this.opened) return;
    this.opened = true;
    if (typeof this.options.tabs === 'function' || typeof this.options.rows === 'function') this.refresh();
    const fn = this.options.onOpen;
    if (fn !== undefined) guard(this.host, () => fn(this));
    this.events.emit('open');
  }

  private markClosed(reason: ContainerCloseReason): void {
    this.opened = false;
    this.host.opens.settled(this);
    const fn = this.options.onClose;
    if (fn !== undefined) guard(this.host, () => fn(this, reason));
    this.events.emit('close', reason);
  }

  /**
   * A fresh session starts with nothing open at the mod: an open screen closes as `lost` rather than
   * popping up again unasked, while an open the mod has not seen yet is sent now. Links that needed
   * the owner are written now.
   */
  sessionOpened(fresh: boolean): void {
    if (!this.live) return;
    if (this.hasLinks) this.refresh();
    if (fresh && this.opened && this.delivered) this.markClosed('lost');
    else if (this.pendingOpen || (fresh && this.opened)) this.pendingOpen = !this.send('screen.open');
    this.delivered = this.opened && !this.pendingOpen;
  }

  remove(): void {
    if (!this.live) return;
    this.retire();
    this.objects.remove(`${SCREEN_DIR}${this.id}`);
  }

  /** Makes the handle inert and drops its route without touching the object; used on unload. */
  retire(): void {
    if (!this.live) return;
    this.live = false;
    this.opened = false;
    this.pendingOpen = false;
    this.host.router.delete(this.rel, this.route);
    this.forget(this);
    this.events.emit('remove');
  }

  private onMessage(type: string, payload: unknown): void {
    if (!this.live) return;
    if (type === 'screen.open') {
      this.delivered = true;
      this.markOpen();
      return;
    }
    if (type === 'screen.close') {
      this.pendingOpen = false;
      if (this.opened) this.markClosed('player');
      return;
    }
    this.host.opens.settled(this);
    const p = payload as MessagePayloads['screen.event'];
    const ctx: ScreenEventContext = { invocation: p.invocation, key: p.screen, path: [], screen: this, item: p.item, player: this.host.origin() };
    const handler = this.handlers.get(p.item);
    invoke(this.host, p.invocation, () => (handler === undefined ? undefined : handler(p.value, ctx)));
  }
}
