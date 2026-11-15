import { BridgeError, type KindPayloads, type MessagePayloads, type SlotClick, type SlotEntry, type SlotItem, type TargetSpec } from '@mineflayer-bridge/protocol';
import { guard, type InputHost, type Route } from './input.ts';
import type {
  AskEvent,
  AskReturn,
  ContainerClick,
  ContainerCloseReason,
  ContainerEvent,
  ContainerHandlerReturn,
  ContainerItem,
  ContainerSlots,
  ContainerView,
  ContainerViewHandlers,
  ContainerViewOptions,
  ContainerViewUpdate,
  ItemInput,
  SlotPolicies,
  SlotPolicy,
  SlotRule,
  StorageContents,
} from './input-types.ts';
import { resourceId } from './check.ts';
import { fromSlotItem, toSlotItem } from './items.ts';
import type { ScopeObjects } from './scope.ts';
import { asBridgeError } from './util.ts';

const VIEW_DIR = '~view/';
/** Slots of a mineflayer inventory window: crafting output and grid, armour, main, hotbar, offhand. */
const PLAYER_INVENTORY_SLOTS = 46;
const ROW = 9;
const U32_MAX = 0xffffffff;
/**
 * `SlotEntry.item` is required on the wire, so a storage slot without contents is sent as air with
 * count 0, which the mod treats as empty.
 */
const EMPTY_SLOT: SlotItem = { id: 'minecraft:air', count: 0 };

/** The wire gesture names use underscores, the public ones hyphens. */
const CLICKS: Readonly<Record<SlotClick, ContainerClick>> = {
  left: 'left',
  right: 'right',
  middle: 'middle',
  shift_left: 'shift-left',
  shift_right: 'shift-right',
  double: 'double',
  drop: 'drop',
  drop_all: 'drop-all',
  number: 'number',
};

const POLICIES: ReadonlySet<string> = new Set<SlotPolicy>(['button', 'locked', 'take', 'place', 'free', 'ask', 'source']);
/** These slots are client-only storage owned by the view. */
const STORAGE_POLICIES: ReadonlySet<string> = new Set<SlotPolicy>(['take', 'place', 'free', 'ask']);

/** A slot rule in wire form. */
export interface WireRule {
  readonly policy: SlotPolicy;
  readonly accepts?: TargetSpec;
  readonly maxStack?: number;
}

const BUTTON: WireRule = { policy: 'button' };

/**
 * True for the slots whose contents the player changes and the mod reports. `source` slots are not
 * among them: their template item belongs to the declaration, so a storage report never clears it.
 */
export function isStorage(rule: WireRule | undefined): boolean {
  return rule !== undefined && STORAGE_POLICIES.has(rule.policy);
}

/** True for a `source` slot, an infinite template the player copies items from. */
export function isSource(rule: WireRule | undefined): boolean {
  return rule?.policy === 'source';
}

/** Validates a policy or rule and converts it to the wire form. @throws BridgeError `invalid_message`. */
export function toRule(r: SlotPolicy | SlotRule): WireRule {
  const rule: SlotRule = typeof r === 'string' ? { policy: r } : r;
  if (typeof rule !== 'object' || rule === null || !POLICIES.has(rule.policy)) {
    throw new BridgeError('invalid_message', `unknown slot policy ${String(typeof r === 'string' ? r : r?.policy)}; use ${[...POLICIES].join(', ')}`);
  }
  if (rule.policy === 'source' && (rule.accepts !== undefined || rule.maxStack !== undefined)) {
    throw new BridgeError('invalid_message', 'a source slot takes no accepts or maxStack: nothing can be placed into it');
  }
  const out: { policy: SlotPolicy; accepts?: TargetSpec; maxStack?: number } = { policy: rule.policy };
  if (rule.accepts !== undefined) {
    const spec: TargetSpec = { type: 'item' };
    if (rule.accepts.ids !== undefined) spec.ids = rule.accepts.ids.map((id) => resourceId('accepted item id', id));
    if (rule.accepts.tags !== undefined) spec.tags = rule.accepts.tags.map((t) => resourceId('accepted item tag', t));
    out.accepts = spec;
  }
  if (rule.maxStack !== undefined) {
    if (!Number.isInteger(rule.maxStack) || rule.maxStack < 1 || rule.maxStack > U32_MAX) throw new BridgeError('invalid_message', 'maxStack must be a positive integer');
    out.maxStack = rule.maxStack;
  }
  return out;
}

/** The public form of a wire rule. */
function fromRule(r: WireRule): SlotRule {
  const out: { policy: SlotPolicy; accepts?: NonNullable<SlotRule['accepts']>; maxStack?: number } = { policy: r.policy };
  if (r.accepts !== undefined) {
    const a: { ids?: readonly string[]; tags?: readonly string[] } = {};
    if (r.accepts.ids !== undefined) a.ids = [...r.accepts.ids];
    if (r.accepts.tags !== undefined) a.tags = [...r.accepts.tags];
    out.accepts = a;
  }
  if (r.maxStack !== undefined) out.maxStack = r.maxStack;
  return out;
}

/**
 * Checks a view or menu id. Ids starting with `~` are reserved for views the plugin declares itself,
 * such as confirm menus.
 */
export function checkViewId(what: string, id: string): void {
  if (typeof id !== 'string' || id === '' || id.includes('/') || id.startsWith('~')) {
    throw new BridgeError('invalid_message', `${what} must be a non-empty string without / that does not start with ~: ${String(id)}`);
  }
}

/** Wire name and slot count of a layout. @throws BridgeError `invalid_message` for an unknown layout. */
function parseLayout(layout: string): { wire: string; size: number } {
  if (layout === 'player_inventory' || layout === 'player-inventory') return { wire: 'player_inventory', size: PLAYER_INVENTORY_SLOTS };
  if (layout === 'hopper') return { wire: layout, size: 5 };
  if (layout === 'dispenser') return { wire: layout, size: 9 };
  // Furnace input, fuel, output; brewing bottles, ingredient, fuel.
  if (layout === 'furnace') return { wire: layout, size: 3 };
  if (layout === 'brewing') return { wire: layout, size: 5 };
  if (layout === 'lectern') return { wire: layout, size: 0 };
  const chest = /^chest:([1-6])$/.exec(layout);
  if (chest !== null) return { wire: layout, size: Number(chest[1]) * ROW };
  const grid = /^grid:([1-9])x([1-6])$/.exec(layout);
  if (grid !== null) return { wire: layout, size: Number(grid[1]) * Number(grid[2]) };
  throw new BridgeError(
    'invalid_message',
    `unknown container layout ${String(layout)}; use chest:<1-6>, hopper, dispenser, player_inventory, grid:<1-9>x<1-6>, furnace, brewing or lectern`,
  );
}

function slotEntries(slots: ContainerSlots): [number, ItemInput | null | undefined][] {
  if (Array.isArray(slots)) {
    const list = slots as readonly (ItemInput | null | undefined)[];
    const out: [number, ItemInput | null | undefined][] = [];
    for (let i = 0; i < list.length; i++) out.push([i, list[i]]);
    return out;
  }
  if (slots instanceof Map) return [...(slots as ReadonlyMap<number, ItemInput | null | undefined>)];
  return Object.entries(slots as Readonly<Record<number, ItemInput | null | undefined>>).map(([k, v]) => [Number(k), v]);
}

function policyEntries(p: SlotPolicies): [number, SlotPolicy | SlotRule][] {
  if (p instanceof Map) return [...(p as ReadonlyMap<number, SlotPolicy | SlotRule>)];
  return Object.entries(p as Readonly<Record<number, SlotPolicy | SlotRule>>).map(([k, v]) => [Number(k), v]);
}

function fraction(what: string, v: number): number {
  if (typeof v !== 'number' || !(v >= 0 && v <= 1)) throw new BridgeError('invalid_message', `${what} must be a number from 0 to 1: ${String(v)}`);
  return v;
}

function pageList(v: readonly string[]): string[] {
  if (!Array.isArray(v) || !v.every((p) => typeof p === 'string')) throw new BridgeError('invalid_message', 'pages must be a list of strings');
  return [...v];
}

/** An empty storage report entry (air or count 0) means the slot is empty. */
function isEmpty(item: SlotItem): boolean {
  return item.id === EMPTY_SLOT.id || item.count === 0;
}

function reply(host: InputHost, view: string, input: number, ok: boolean, message: string | undefined): void {
  const p: MessagePayloads['container.ack'] = { view, input, ok };
  if (message !== undefined) p.message = message;
  host.core.send('container.ack', host.link.encode('container.ack', p));
}

/**
 * Runs a container handler and answers the gesture with `container.ack`: `false` or a throw rejects
 * it, a string confirms it with that message.
 */
function acknowledge(host: InputHost, view: string, input: number, run: (() => ContainerHandlerReturn) | undefined): void {
  if (run === undefined) {
    reply(host, view, input, true, undefined);
    return;
  }
  let result: ContainerHandlerReturn;
  try {
    result = run();
  } catch (e) {
    reply(host, view, input, false, asBridgeError(e).message);
    return;
  }
  Promise.resolve(result).then(
    (v) => reply(host, view, input, v !== false, typeof v === 'string' ? v : undefined),
    (e: unknown) => reply(host, view, input, false, asBridgeError(e).message),
  );
}

/**
 * Answers an `ask` move: a rejection makes the mod roll the move back and show the reason. Unlike
 * other handlers, a string is a rejection reason.
 */
function answerAsk(host: InputHost, view: string, input: number, run: (() => AskReturn) | undefined): void {
  if (run === undefined) {
    reply(host, view, input, true, undefined);
    return;
  }
  let result: AskReturn;
  try {
    result = run();
  } catch (e) {
    reply(host, view, input, false, asBridgeError(e).message);
    return;
  }
  Promise.resolve(result).then(
    (v) => (typeof v === 'string' ? reply(host, view, input, false, v) : reply(host, view, input, v !== false, undefined)),
    (e: unknown) => reply(host, view, input, false, asBridgeError(e).message),
  );
}

/** A container view of one scope at `~view/<id>`. */
export class ContainerViewImpl implements ContainerView {
  readonly id: string;
  /** Key relative to the owner: `<scope>/~view/<id>`. */
  readonly rel: string;
  private title = '';
  private layout = 'chest:3';
  private capacity = 3 * ROW;
  private closeOnClick = false;
  private requireAnswer = false;
  private filler: SlotItem | undefined;
  private progress: number | undefined;
  private fuel: number | undefined;
  private pages: string[] | undefined;
  private reopenRel: string | undefined;
  private handlers: ContainerViewHandlers = {};
  private readonly items = new Map<number, SlotItem>();
  private readonly rules = new Map<number, WireRule>();
  private readonly storageListeners = new Set<(slots: StorageContents) => void>();
  /** The player changed storage since the last write, so the declared bytes are behind. */
  private storageStale = false;
  private opened = false;
  /** Whether a session the mod had open saw this view open; only then can a restart lose it. */
  private delivered = false;
  /** An open that could not be sent yet because the owner key is not known. */
  private pendingOpen = false;
  private live = true;
  private readonly removedListeners = new Set<() => void>();
  private readonly route: Route;

  constructor(
    private readonly host: InputHost,
    private readonly objects: ScopeObjects,
    id: string,
    options: ContainerViewOptions,
    private readonly forget: (view: ContainerViewImpl) => void,
  ) {
    this.id = id;
    this.rel = `${objects.scope}/${VIEW_DIR}${id}`;
    this.route = { accepts: 'container', handle: (payload, type) => this.onMessage(type, payload) };
    this.redeclare(options);
    host.router.set(this.rel, this.route);
  }

  get isOpen(): boolean {
    return this.opened;
  }

  get size(): number {
    return this.capacity;
  }

  private reopenKey(v: string | ContainerView): string {
    if (v instanceof ContainerViewImpl) return v.rel;
    if (typeof v !== 'string' || v === '' || v.includes('/')) throw new BridgeError('invalid_message', 'reopenParent must be a container view or the id of one in the same scope');
    return `${this.objects.scope}/${VIEW_DIR}${v}`;
  }

  /** Applies a full declaration again: fields, handlers and, when given, the slots and policies. */
  redeclare(o: ContainerViewOptions): void {
    if (!this.live) return;
    if (typeof o.title !== 'string') throw new BridgeError('invalid_message', `container view ${this.id} needs a title`);
    const { wire, size } = parseLayout(o.layout ?? 'chest:3');
    const filler = o.filler === undefined ? undefined : toSlotItem(o.filler, this.host);
    const items = o.slots === undefined ? undefined : this.collect(o.slots, size);
    const rules = o.policies === undefined ? undefined : this.collectRules(o.policies, size);
    const progress = o.progress === undefined ? undefined : fraction('progress', o.progress);
    const fuel = o.fuel === undefined ? undefined : fraction('fuel', o.fuel);
    const pages = o.pages === undefined ? undefined : pageList(o.pages);
    const reopen = o.reopenParent === undefined ? undefined : this.reopenKey(o.reopenParent);
    this.title = o.title;
    this.layout = wire;
    this.capacity = size;
    this.closeOnClick = o.closeOnClick === true;
    this.requireAnswer = o.requireAnswer === true;
    this.filler = filler;
    this.progress = progress;
    this.fuel = fuel;
    this.pages = pages;
    this.reopenRel = reopen;
    this.handlers = { ...(o.on ?? {}) };
    if (items !== undefined) {
      this.items.clear();
      for (const [i, item] of items) if (item !== null) this.items.set(i, item);
    }
    if (rules !== undefined) {
      this.rules.clear();
      for (const [i, r] of rules) if (r.policy !== 'button') this.rules.set(i, r);
    }
    this.trim(size);
    this.write();
  }

  private trim(size: number): void {
    for (const i of this.items.keys()) if (i >= size) this.items.delete(i);
    for (const i of this.rules.keys()) if (i >= size) this.rules.delete(i);
  }

  private checkSlot(slot: number, size = this.capacity): void {
    if (!Number.isInteger(slot) || slot < 0 || slot >= size) throw new BridgeError('invalid_message', `slot ${String(slot)} is outside container view ${this.id} (0 to ${size - 1})`);
  }

  /** Validates every entry before anything changes, so a bad entry leaves the view as it was. */
  private collect(slots: ContainerSlots, size: number): Map<number, SlotItem | null> {
    const out = new Map<number, SlotItem | null>();
    for (const [i, item] of slotEntries(slots)) {
      if (item === null || item === undefined) {
        if (Number.isInteger(i) && i >= 0 && i < size) out.set(i, null);
        continue;
      }
      this.checkSlot(i, size);
      out.set(i, toSlotItem(item, this.host));
    }
    return out;
  }

  private collectRules(policies: SlotPolicies, size: number): Map<number, WireRule> {
    const out = new Map<number, WireRule>();
    for (const [i, r] of policyEntries(policies)) {
      this.checkSlot(i, size);
      out.set(i, toRule(r));
    }
    return out;
  }

  private data(): KindPayloads['core:container-view'] {
    const slots = new Set<number>(this.items.keys());
    for (const i of this.rules.keys()) slots.add(i);
    const entries = [...slots]
      .sort((a, b) => a - b)
      .map((slot): SlotEntry => {
        const e: SlotEntry = { slot, item: this.items.get(slot) ?? EMPTY_SLOT };
        const r = this.rules.get(slot);
        if (r !== undefined) {
          e.policy = r.policy;
          if (r.accepts !== undefined) e.accepts = r.accepts;
          if (r.maxStack !== undefined) e.maxStack = r.maxStack;
        }
        return e;
      });
    const d: KindPayloads['core:container-view'] = { title: this.title, layout: this.layout, slots: entries };
    if (this.closeOnClick) d.closeOnClick = true;
    if (this.requireAnswer) d.requireAnswer = true;
    if (this.filler !== undefined) d.filler = this.filler;
    if (this.progress !== undefined) d.progress = this.progress;
    if (this.fuel !== undefined) d.fuel = this.fuel;
    if (this.pages !== undefined) d.pages = this.pages;
    // References carry the full object key, which needs the owner; the view is
    // written again once the session knows it.
    const owner = this.host.core.owner;
    if (this.reopenRel !== undefined && owner !== undefined) d.reopenParent = `${owner}/${this.reopenRel}`;
    return d;
  }

  private write(): void {
    if (!this.live) return;
    this.storageStale = false;
    // A screen, not a shape: it is shown wherever the player is.
    this.objects.put(`${VIEW_DIR}${this.id}`, 'core:container-view', this.data(), { world: 'global' });
  }

  private send(type: 'container.open' | 'container.close'): boolean {
    const owner = this.host.core.owner;
    if (owner === undefined) return false;
    this.host.core.send(type, this.host.link.encode(type, { view: `${owner}/${this.rel}` }));
    return true;
  }

  /** The full object key, once the owner is known. */
  private objectKey(): string | undefined {
    const owner = this.host.core.owner;
    return owner === undefined ? undefined : `${owner}/${this.rel}`;
  }

  open(): void {
    if (!this.live) return;
    this.pendingOpen = !this.send('container.open');
    this.delivered = !this.pendingOpen && this.host.core.connected;
    this.host.opens.sent(this, 'view', () => this.objectKey(), () => {
      this.pendingOpen = false;
      if (this.opened) this.closed('refused');
    });
    if (this.opened) return;
    this.opened = true;
    const fn = this.handlers.open;
    if (fn !== undefined) guard(this.host, () => fn(this));
  }

  close(): void {
    if (!this.live) return;
    this.pendingOpen = false;
    if (!this.opened) return;
    this.send('container.close');
    this.closed('bot');
  }

  private closed(reason: ContainerCloseReason): void {
    this.opened = false;
    this.host.opens.settled(this);
    const fn = this.handlers.close;
    if (fn !== undefined) guard(this.host, () => fn(this, reason));
  }

  /**
   * A fresh session starts with no screen open at the mod: an open view closes as `lost` rather than
   * popping up again unasked, while an open the mod has not seen yet is sent now. After a resume the
   * outbound log already carries what was sent. Storage the player changed and a parent key that
   * needed the owner are written now.
   */
  sessionOpened(fresh: boolean): void {
    if (!this.live) return;
    if (this.reopenRel !== undefined || (fresh && this.storageStale)) this.write();
    if (fresh && this.opened && this.delivered) this.closed('lost');
    else if (this.pendingOpen || (fresh && this.opened)) this.pendingOpen = !this.send('container.open');
    this.delivered = this.opened && !this.pendingOpen;
  }

  setSlot(slot: number, item: ItemInput | null | undefined): void {
    if (!this.live) return;
    this.checkSlot(slot);
    if (item === null || item === undefined) {
      if (!this.items.delete(slot)) return;
    } else {
      this.items.set(slot, toSlotItem(item, this.host));
    }
    this.write();
  }

  setSlots(slots: ContainerSlots, options: { merge?: boolean } = {}): void {
    if (!this.live) return;
    const next = this.collect(slots, this.capacity);
    if (options.merge !== true) this.items.clear();
    for (const [i, item] of next) {
      if (item === null) this.items.delete(i);
      else this.items.set(i, item);
    }
    this.write();
  }

  /**
   * @internal Replaces every slot and policy at once, as menus compile them. Storage slots that
   * were storage before keep what the player left there; slots that become storage start with
   * their given item.
   */
  compiled(items: ReadonlyMap<number, SlotItem>, rules: ReadonlyMap<number, WireRule>): void {
    if (!this.live) return;
    const kept = new Map<number, SlotItem | null>();
    for (const [i, r] of rules) if (isStorage(r) && isStorage(this.rules.get(i))) kept.set(i, this.items.get(i) ?? null);
    this.items.clear();
    this.rules.clear();
    for (const [i, r] of rules) if (i < this.capacity && r.policy !== 'button') this.rules.set(i, r);
    for (const [i, item] of items) if (i < this.capacity && !kept.has(i)) this.items.set(i, item);
    for (const [i, item] of kept) if (item !== null) this.items.set(i, item);
    this.write();
  }

  slot(slot: number): ContainerItem | undefined {
    this.checkSlot(slot);
    return this.itemAt(slot);
  }

  private itemAt(slot: number): ContainerItem | undefined {
    const s = this.items.get(slot);
    return s === undefined ? undefined : fromSlotItem(s);
  }

  setPolicy(slot: number, rule: SlotPolicy | SlotRule | null): void {
    if (!this.live) return;
    this.checkSlot(slot);
    const r = rule === null ? BUTTON : toRule(rule);
    if (r.policy === 'button') this.rules.delete(slot);
    else this.rules.set(slot, r);
    this.write();
  }

  policy(slot: number): SlotRule {
    this.checkSlot(slot);
    return fromRule(this.rules.get(slot) ?? BUTTON);
  }

  storage(): StorageContents {
    const out = new Map<number, ContainerItem>();
    for (const [i, r] of [...this.rules].sort((a, b) => a[0] - b[0])) {
      if (!isStorage(r)) continue;
      const item = this.items.get(i);
      if (item !== undefined) out.set(i, fromSlotItem(item));
    }
    return out;
  }

  get alive(): boolean {
    return this.live;
  }

  on(event: 'storage', fn: (slots: StorageContents) => void): () => void;
  on(event: 'removed', fn: () => void): () => void;
  on(event: 'storage' | 'removed', fn: ((slots: StorageContents) => void) | (() => void)): () => void {
    if (event !== 'storage' && event !== 'removed') throw new BridgeError('invalid_message', `unknown container view event ${String(event)}`);
    if (typeof fn !== 'function') throw new BridgeError('invalid_message', `${event} listener must be a function`);
    const set: Set<(slots: StorageContents) => void> = event === 'storage' ? this.storageListeners : this.removedListeners;
    if (this.live) set.add(fn);
    return () => set.delete(fn);
  }

  update(o: ContainerViewUpdate): void {
    if (!this.live) return;
    if (o.title !== undefined && typeof o.title !== 'string') throw new BridgeError('invalid_message', 'container view title must be a string');
    const layout = o.layout === undefined ? undefined : parseLayout(o.layout);
    const filler = o.filler === undefined || o.filler === null ? o.filler : toSlotItem(o.filler, this.host);
    const progress = o.progress === undefined || o.progress === null ? o.progress : fraction('progress', o.progress);
    const fuel = o.fuel === undefined || o.fuel === null ? o.fuel : fraction('fuel', o.fuel);
    const pages = o.pages === undefined || o.pages === null ? o.pages : pageList(o.pages);
    const reopen = o.reopenParent === undefined || o.reopenParent === null ? o.reopenParent : this.reopenKey(o.reopenParent);
    const rules = o.policies === undefined ? undefined : this.collectRules(o.policies, layout?.size ?? this.capacity);
    if (o.title !== undefined) this.title = o.title;
    if (layout !== undefined) {
      this.layout = layout.wire;
      this.capacity = layout.size;
      this.trim(layout.size);
    }
    if (o.closeOnClick !== undefined) this.closeOnClick = o.closeOnClick;
    if (o.requireAnswer !== undefined) this.requireAnswer = o.requireAnswer;
    if (filler !== undefined) this.filler = filler ?? undefined;
    if (progress !== undefined) this.progress = progress ?? undefined;
    if (fuel !== undefined) this.fuel = fuel ?? undefined;
    if (pages !== undefined) this.pages = pages ?? undefined;
    if (reopen !== undefined) this.reopenRel = reopen ?? undefined;
    if (rules !== undefined) {
      this.rules.clear();
      for (const [i, r] of rules) if (r.policy !== 'button') this.rules.set(i, r);
    }
    if (o.on !== undefined) this.handlers = { ...o.on };
    this.write();
  }

  remove(): void {
    if (!this.live) return;
    this.retire();
    this.objects.remove(`${VIEW_DIR}${this.id}`);
  }

  /** Makes the handle inert and drops its route without touching the object; used on unload. */
  retire(): void {
    const was = this.live;
    this.live = false;
    this.opened = false;
    this.pendingOpen = false;
    this.storageListeners.clear();
    this.host.router.delete(this.rel, this.route);
    this.forget(this);
    const removed = [...this.removedListeners];
    this.removedListeners.clear();
    if (was) for (const fn of removed) fn();
  }

  /** The mod reports every storage slot after each change. */
  private onStorage(p: MessagePayloads['container.storage']): void {
    const reported = new Map<number, SlotItem>();
    for (const e of p.slots) if (isStorage(this.rules.get(e.slot)) && !isEmpty(e.item)) reported.set(e.slot, e.item);
    for (const [i, r] of this.rules) {
      if (!isStorage(r)) continue;
      const item = reported.get(i);
      if (item === undefined) this.items.delete(i);
      else this.items.set(i, item);
    }
    this.storageStale = true;
    const contents = this.storage();
    const fn = this.handlers.storage;
    if (fn !== undefined) guard(this.host, () => fn(contents, this));
    for (const l of [...this.storageListeners]) guard(this.host, () => l(contents));
  }

  private onMessage(type: string, payload: unknown): void {
    if (!this.live) return;
    // Anything the mod says about the view means it is showing.
    this.host.opens.settled(this);
    if (type === 'container.open') {
      this.delivered = true;
      if (this.opened) return;
      this.opened = true;
      const fn = this.handlers.open;
      if (fn !== undefined) guard(this.host, () => fn(this));
      return;
    }
    if (type === 'container.close') {
      this.pendingOpen = false;
      if (this.opened) this.closed((payload as MessagePayloads['container.close']).lost ? 'lost' : 'player');
      return;
    }
    if (type === 'container.storage') {
      this.onStorage(payload as MessagePayloads['container.storage']);
      return;
    }
    const p = payload as MessagePayloads['container.input'];
    const click = CLICKS[p.click];
    const event: ContainerEvent = {
      view: this,
      slot: p.slot,
      click,
      hotbar: p.hotbar,
      item: this.itemAt(p.slot),
      shift: click === 'shift-left' || click === 'shift-right',
      all: click === 'drop-all',
      player: this.host.origin(),
    };
    const h = this.handlers;
    if (this.rules.get(p.slot)?.policy === 'ask' || p.moved !== undefined) {
      const ask: AskEvent = { ...event, fromSlot: p.fromSlot ?? -1, moved: p.moved === undefined ? undefined : fromSlotItem(p.moved) };
      const fn = h.ask;
      answerAsk(this.host, p.view, p.input, fn === undefined ? undefined : () => fn(ask));
      return;
    }
    const fn = click === 'drop' || click === 'drop-all' ? h.drop : click === 'number' ? h.hotbarSwap : h.click;
    acknowledge(this.host, p.view, p.input, fn === undefined ? undefined : () => fn(event));
  }
}
