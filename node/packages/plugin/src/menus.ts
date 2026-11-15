import { BridgeError, type SlotItem } from '@mineflayer-bridge/protocol';
import type {
  BarOptions,
  ContainerClick,
  ContainerEvent,
  ContainerHandlerReturn,
  ContainerViewOptions,
  CounterOptions,
  CycleOptions,
  ItemInput,
  MenuLabelOptions,
  Menu,
  MenuArea,
  MenuBar,
  MenuButton,
  MenuCell,
  MenuCounter,
  MenuCycle,
  MenuLabel,
  MenuLook,
  MenuOptions,
  MenuUpdate,
  OpenCloseEvents,
  MenuPane,
  MenuRect,
  MenuSearchOptions,
  MenuSlider,
  MenuStorageCell,
  MenuTab,
  MenuToggle,
  OutlinePaneOptions,
  PagedPane,
  PagedPaneOptions,
  PaletteOptions,
  ScrollPane,
  ScrollPaneOptions,
  SliderOptions,
  SlotPolicy,
  SlotRule,
  StaticPane,
  StaticPaneOptions,
  TabsPane,
  TabsPaneOptions,
  TextPromptOptions,
  ToggleOptions,
} from './input-types.ts';
import { finite } from './check.ts';
import { compactCount, fromSlotItem, toSlotItem, type ItemContext } from './items.ts';
import type { KeyValueStore } from './types.ts';
import { asBridgeError, checkKeys, Emitter } from './util.ts';
import { isSource, isStorage, toRule, type ContainerViewImpl, type WireRule } from './views.ts';

const ROW = 9;
const BUTTON: WireRule = { policy: 'button' };
const LOCKED: WireRule = { policy: 'locked' };
const SOURCE: WireRule = { policy: 'source' };
const PALETTE_KEYS: ReadonlySet<string> = new Set(['slots']);
const LIME = 'minecraft:lime_stained_glass_pane';
const GRAY = 'minecraft:gray_stained_glass_pane';
const DEFAULT_PREV: MenuLook = { item: 'minecraft:arrow', name: 'Previous page' };
const DEFAULT_NEXT: MenuLook = { item: 'minecraft:arrow', name: 'Next page' };
const DEFAULT_BACK: MenuLook = { item: 'minecraft:arrow', name: 'Back' };
const DEFAULT_SCROLL_BACK: MenuLook = { item: 'minecraft:arrow', name: 'Scroll back' };
const DEFAULT_SCROLL_FORWARD: MenuLook = { item: 'minecraft:arrow', name: 'Scroll forward' };
const DEFAULT_SEARCH: MenuLook = { item: 'minecraft:compass', name: 'Search' };
const DEFAULT_CLEAR: MenuLook = { item: 'minecraft:barrier', name: 'Clear search' };
const SEARCH_MAX_LENGTH = 50;
const SEARCH_TIMEOUT_MS = 60_000;

/** Clicks that press a menu button; drop, number keys and the second half of a double click do not. */
const PRESSES: ReadonlySet<ContainerClick> = new Set(['left', 'right', 'middle', 'shift-left', 'shift-right']);

type Click = (event: ContainerEvent) => ContainerHandlerReturn;

/** A compiled cell: what one slot shows and does. */
interface Cell {
  /** `undefined` only for an empty storage slot. */
  readonly item: SlotItem | undefined;
  readonly rule: WireRule;
  readonly onClick: Click | undefined;
  readonly close: boolean;
}

/** What a menu needs from its scope. */
export interface MenuHost {
  view(id: string, options: ContainerViewOptions): ContainerViewImpl;
  forget(menu: MenuImpl): void;
  readonly items: ItemContext;
  /** The scope's key-value store, for {@link MenuOptions.persist}. */
  readonly store: KeyValueStore;
  /** Asks the player for text (`prompt.text` of the scope), for menu search. */
  promptText(text: string, options: TextPromptOptions): Promise<string>;
  reportError(error: BridgeError): void;
}

function isButton(c: unknown): c is MenuButton {
  return typeof c === 'object' && c !== null && (c as { item?: unknown }).item !== undefined;
}

function isStorageCell(c: unknown): c is MenuStorageCell {
  return typeof c === 'object' && c !== null && (c as { storage?: unknown }).storage !== undefined;
}

/** The wire item of a look: the item with the look's presentation fields laid over it. */
function lookItem(look: MenuLook, ctx: ItemContext): SlotItem {
  if (!isButton(look)) return toSlotItem(look as ItemInput, ctx);
  const { item, ...over } = look as MenuButton;
  const merged: Record<string, unknown> = { ...fromSlotItem(toSlotItem(item, ctx)) };
  for (const [k, v] of Object.entries(over)) if (v !== undefined && k !== 'close' && k !== 'onClick') merged[k] = v;
  // A new count without its own text must not keep the compact text of the item's count.
  if ((over as MenuButton).count !== undefined && (over as MenuButton).countText === undefined) delete merged['countText'];
  return toSlotItem(merged as unknown as ItemInput, ctx);
}

function button(item: SlotItem, onClick: Click | undefined, close = false): Cell {
  return { item, rule: BUTTON, onClick, close };
}

function compileCell(c: MenuCell | undefined, ctx: ItemContext): Cell | undefined {
  if (c === null || c === undefined) return undefined;
  if (isStorageCell(c)) {
    const rule = toRule(c.storage);
    if (isSource(rule)) {
      if (c.item === undefined) throw new BridgeError('invalid_message', 'a source cell needs the item the player copies');
      return { item: toSlotItem(c.item, ctx), rule, onClick: undefined, close: false };
    }
    if (!isStorage(rule)) throw new BridgeError('invalid_message', 'a storage cell needs a take, place, free, ask or source policy');
    return { item: c.item === undefined ? undefined : toSlotItem(c.item, ctx), rule, onClick: undefined, close: false };
  }
  if (isButton(c)) return button(lookItem(c, ctx), c.onClick, c.close === true);
  return { item: toSlotItem(c as ItemInput, ctx), rule: LOCKED, onClick: undefined, close: false };
}

function withCount(item: SlotItem, n: number): SlotItem {
  const out: SlotItem = { ...item };
  delete out.count;
  delete out.countText;
  if (n !== 1) out.count = n;
  if (n > 99) out.countText = compactCount(n);
  return out;
}

function intIn(what: string, v: number, min: number, max: number): number {
  if (!Number.isInteger(v) || v < min || v > max) throw new BridgeError('invalid_message', `${what} must be an integer from ${min} to ${max}: ${String(v)}`);
  return v;
}

function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v));
}

/** Two decimals at most, without trailing zeros. */
function format(v: number): string {
  return String(Math.round(v * 100) / 100);
}

/** Panes are layered areas of a menu, compiled into its slots. */
abstract class PaneImpl implements MenuPane {
  private shown = true;
  private live = true;

  constructor(
    protected readonly menu: MenuImpl,
    readonly id: string,
    readonly priority: number,
    readonly order: number,
  ) {}

  get visible(): boolean {
    return this.shown;
  }

  get alive(): boolean {
    return this.live;
  }

  setVisible(visible: boolean): void {
    if (!this.live || this.shown === visible) return;
    this.shown = visible;
    this.menu.render();
  }

  remove(): void {
    if (!this.live) return;
    this.retire();
    this.menu.dropPane(this);
  }

  retire(): void {
    this.live = false;
  }

  abstract cells(): Map<number, Cell>;

  /** JSON-safe state persisted with {@link MenuOptions.persist}; `undefined` for stateless panes. */
  state(): unknown {
    return undefined;
  }

  /** Applies persisted state without calling user callbacks; the menu renders afterwards. */
  restore(_state: unknown): void {}

  protected get ctx(): ItemContext {
    return this.menu.ctx;
  }

  /** State changed: render, and persist when the menu does. */
  protected changed(): void {
    if (this.live) this.menu.stateChanged();
  }

  /** Re-render without a state change. */
  protected redraw(): void {
    if (this.live) this.menu.render();
  }
}

class StaticPaneImpl extends PaneImpl implements StaticPane {
  private compiled: (Cell | undefined)[] = [];
  private fill: Cell | undefined;

  constructor(
    menu: MenuImpl,
    id: string,
    priority: number,
    order: number,
    private readonly slots: readonly number[],
    cells: readonly MenuCell[],
    fill: MenuCell | undefined,
  ) {
    super(menu, id, priority, order);
    this.compiled = cells.map((c) => compileCell(c, menu.ctx));
    this.fill = compileCell(fill, menu.ctx);
  }

  cells(): Map<number, Cell> {
    const out = new Map<number, Cell>();
    this.slots.forEach((slot, i) => {
      const c = this.compiled[i] ?? this.fill;
      if (c !== undefined) out.set(slot, c);
    });
    return out;
  }

  set(cells: readonly MenuCell[]): void {
    if (!this.alive) return;
    this.compiled = cells.map((c) => compileCell(c, this.ctx));
    this.redraw();
  }

  /** @internal Used by outline panes, whose single cell repeats along the border. */
  setFill(cell: MenuCell): void {
    this.fill = compileCell(cell, this.ctx);
  }
}

class OutlinePaneImpl extends StaticPaneImpl {
  override set(cells: readonly MenuCell[]): void {
    if (!this.alive) return;
    this.setFill(cells[0] ?? null);
    this.redraw();
  }
}

/** A look with extra lore lines after its own, for the search button. */
function withLore(look: MenuLook, lines: readonly string[], glint: boolean): MenuLook {
  const extra = glint ? { glint } : {};
  if (!isButton(look)) return { item: look as ItemInput, lore: [...lines], ...extra };
  const b = look as Omit<MenuButton, 'onClick' | 'close'>;
  return { ...b, lore: [...(b.lore ?? []), ...lines], ...extra };
}

/**
 * Default search text of a compiled entry: its shown name, else its item id (without the
 * `minecraft:` namespace, which every vanilla id would match) and the id's path with spaces.
 */
function defaultSearchText(c: Cell | undefined): string {
  const item = c?.item;
  if (item === undefined) return '';
  if (typeof item.name === 'string' && item.name !== '') return item.name;
  const id = String(item.id).replace(/^minecraft:/, '');
  return `${id} ${id.slice(id.indexOf(':') + 1).replace(/_/g, ' ')}`;
}

class PagedPaneImpl extends PaneImpl implements PagedPane {
  private current = 0;
  private raw: readonly MenuCell[];
  private entries: (Cell | undefined)[];
  /** Indexes of the entries that match the filter, in order. */
  private hits: number[] = [];
  private text = '';
  private readonly prevItem: SlotItem;
  private readonly nextItem: SlotItem;
  private readonly clearItem: SlotItem | undefined;
  private asking: Promise<string> | undefined;
  private abort: AbortController | undefined;

  constructor(
    menu: MenuImpl,
    id: string,
    priority: number,
    order: number,
    private readonly slots: readonly number[],
    entries: readonly MenuCell[],
    private readonly prevSlot: number | undefined,
    private readonly nextSlot: number | undefined,
    prevLook: MenuLook,
    nextLook: MenuLook,
    page: number,
    private readonly searchOptions: MenuSearchOptions | undefined,
  ) {
    super(menu, id, priority, order);
    if (slots.length === 0) throw new BridgeError('invalid_message', 'a paginated pane needs at least one slot');
    if (searchOptions !== undefined) {
      if (searchOptions.searchText !== undefined && typeof searchOptions.searchText !== 'function') throw new BridgeError('invalid_message', 'search searchText must be a function');
      intIn('search maxLength', searchOptions.maxLength ?? SEARCH_MAX_LENGTH, 1, 0xffff);
      intIn('search timeoutMs', searchOptions.timeoutMs ?? SEARCH_TIMEOUT_MS, 1, 600_000);
      lookItem(searchOptions.button ?? DEFAULT_SEARCH, menu.ctx);
    }
    this.raw = [...entries];
    this.entries = entries.map((c) => compileCell(c, menu.ctx));
    this.prevItem = lookItem(prevLook, menu.ctx);
    this.nextItem = lookItem(nextLook, menu.ctx);
    this.clearItem = searchOptions?.clear === undefined ? undefined : lookItem(searchOptions.clearButton ?? DEFAULT_CLEAR, menu.ctx);
    this.match();
    this.current = this.clamped(page);
  }

  get page(): number {
    return this.current;
  }

  get pages(): number {
    return Math.max(1, Math.ceil(this.hits.length / this.slots.length));
  }

  get filter(): string {
    return this.text;
  }

  get matches(): number {
    return this.hits.length;
  }

  private clamped(page: number): number {
    return Math.min(this.pages - 1, Math.max(0, Math.trunc(finite('page', page))));
  }

  /** Recomputes the matching entries for the current filter. */
  private match(): void {
    const all = this.entries.map((_, i) => i);
    if (this.text === '') {
      this.hits = all;
      return;
    }
    const needle = this.text.toLowerCase();
    const fn = this.searchOptions?.searchText;
    this.hits = all.filter((i) => {
      try {
        const hay = fn === undefined ? defaultSearchText(this.entries[i]) : String(fn(this.raw[i] ?? null, i));
        return hay.toLowerCase().includes(needle);
      } catch (e) {
        this.menu.report(asBridgeError(e));
        return false;
      }
    });
  }

  cells(): Map<number, Cell> {
    const out = new Map<number, Cell>();
    const base = this.current * this.slots.length;
    this.slots.forEach((slot, i) => {
      const at = this.hits[base + i];
      const c = at === undefined ? undefined : this.entries[at];
      if (c !== undefined) out.set(slot, c);
    });
    if (this.prevSlot !== undefined && this.current > 0) out.set(this.prevSlot, button(this.prevItem, () => this.prev()));
    if (this.nextSlot !== undefined && this.current < this.pages - 1) out.set(this.nextSlot, button(this.nextItem, () => this.next()));
    const o = this.searchOptions;
    if (o !== undefined) {
      const active = this.text !== '';
      const n = this.hits.length;
      const lines = active ? [`Filter: ${this.text}`, `${n} ${n === 1 ? 'match' : 'matches'}`, 'Right click to clear'] : ['Click to search'];
      out.set(o.slot, button(lookItem(withLore(o.button ?? DEFAULT_SEARCH, lines, active), this.ctx), (e) => this.pressSearch(e)));
      if (active && this.clearItem !== undefined && o.clear !== undefined) out.set(o.clear, button(this.clearItem, () => this.filterBy('')));
    }
    return out;
  }

  private pressSearch(e: ContainerEvent): ContainerHandlerReturn {
    if (e.click === 'right' || e.click === 'shift-right') {
      this.filterBy('');
      return undefined;
    }
    // Not returned: the click is acknowledged at once, while the prompt waits for the player.
    void this.search();
    return undefined;
  }

  show(page: number): void {
    if (!this.alive) return;
    this.current = this.clamped(page);
    this.changed();
  }

  next(): void {
    this.show(this.current + 1);
  }

  prev(): void {
    this.show(this.current - 1);
  }

  set(entries: readonly MenuCell[]): void {
    if (!this.alive) return;
    this.raw = [...entries];
    this.entries = entries.map((c) => compileCell(c, this.ctx));
    this.match();
    this.show(this.current);
  }

  filterBy(text: string): void {
    if (!this.alive) return;
    const next = String(text).trim();
    const differs = next !== this.text;
    this.text = next;
    this.match();
    this.current = 0;
    this.changed();
    const fn = this.searchOptions?.onSearch;
    if (differs && fn !== undefined) this.menu.guard(() => fn(next));
  }

  search(): Promise<string> {
    if (!this.alive) return Promise.resolve(this.text);
    this.asking ??= this.ask().finally(() => {
      this.asking = undefined;
    });
    return this.asking;
  }

  /** The anvil prompt behind the search button. */
  private async ask(): Promise<string> {
    const o: Partial<MenuSearchOptions> = this.searchOptions ?? {};
    const maxLength = o.maxLength ?? SEARCH_MAX_LENGTH;
    const abort = new AbortController();
    this.abort = abort;
    let timedOut = false;
    await this.menu.aside(async () => {
      try {
        const answer = await this.menu.promptText(o.prompt ?? 'Search', {
          surface: 'anvil',
          item: o.item ?? 'minecraft:name_tag',
          default: [...this.text].slice(0, maxLength).join(''),
          maxLength,
          timeoutMs: o.timeoutMs ?? SEARCH_TIMEOUT_MS,
          signal: abort.signal,
          onTimeout: () => {
            timedOut = true;
          },
        });
        if (!timedOut) this.filterBy(answer);
      } catch (e) {
        const err = asBridgeError(e);
        if (err.code !== 'cancelled') this.menu.report(err);
      } finally {
        if (this.abort === abort) this.abort = undefined;
      }
    });
    return this.text;
  }

  override retire(): void {
    super.retire();
    this.abort?.abort();
  }

  override state(): unknown {
    return this.text === '' ? this.current : { page: this.current, filter: this.text };
  }

  override restore(state: unknown): void {
    if (typeof state === 'number') {
      this.current = this.clamped(state);
      return;
    }
    if (!isRecord(state)) return;
    if (typeof state['filter'] === 'string') {
      this.text = state['filter'].trim();
      this.match();
    }
    const page = state['page'];
    this.current = this.clamped(typeof page === 'number' && Number.isFinite(page) ? page : 0);
  }
}

class ScrollPaneImpl extends PaneImpl implements ScrollPane {
  private first = 0;
  private entries: (Cell | undefined)[];
  private readonly backItem: SlotItem;
  private readonly forwardItem: SlotItem;

  constructor(
    menu: MenuImpl,
    id: string,
    priority: number,
    order: number,
    private readonly rect: MenuRect,
    entries: readonly MenuCell[],
    private readonly vertical: boolean,
    private readonly backSlot: number | undefined,
    private readonly forwardSlot: number | undefined,
    backLook: MenuLook,
    forwardLook: MenuLook,
    offset: number,
  ) {
    super(menu, id, priority, order);
    this.entries = entries.map((c) => compileCell(c, menu.ctx));
    this.backItem = lookItem(backLook, menu.ctx);
    this.forwardItem = lookItem(forwardLook, menu.ctx);
    this.first = this.clamped(offset);
  }

  /** Entries per line, and lines shown at once. */
  private get shape(): { line: number; shown: number } {
    return this.vertical ? { line: this.rect.width, shown: this.rect.height } : { line: this.rect.height, shown: this.rect.width };
  }

  get offset(): number {
    return this.first;
  }

  get maxOffset(): number {
    const { line, shown } = this.shape;
    return Math.max(0, Math.ceil(this.entries.length / line) - shown);
  }

  private clamped(offset: number): number {
    return Math.min(this.maxOffset, Math.max(0, Math.trunc(finite('scroll offset', offset))));
  }

  cells(): Map<number, Cell> {
    const out = new Map<number, Cell>();
    const { x, y, width, height } = this.rect;
    for (let r = 0; r < height; r++) {
      for (let c = 0; c < width; c++) {
        const index = this.vertical ? (this.first + r) * width + c : (this.first + c) * height + r;
        const cell = this.entries[index];
        if (cell !== undefined) out.set((y + r) * ROW + x + c, cell);
      }
    }
    if (this.backSlot !== undefined && this.first > 0) out.set(this.backSlot, button(this.backItem, () => this.scroll(-1)));
    if (this.forwardSlot !== undefined && this.first < this.maxOffset) out.set(this.forwardSlot, button(this.forwardItem, () => this.scroll(1)));
    return out;
  }

  scrollTo(offset: number): void {
    if (!this.alive) return;
    this.first = this.clamped(offset);
    this.changed();
  }

  scroll(lines: number): void {
    this.scrollTo(this.first + finite('scroll lines', lines));
  }

  set(entries: readonly MenuCell[]): void {
    if (!this.alive) return;
    this.entries = entries.map((c) => compileCell(c, this.ctx));
    this.scrollTo(this.first);
  }

  override state(): unknown {
    return this.first;
  }

  override restore(state: unknown): void {
    if (typeof state === 'number') this.first = this.clamped(state);
  }
}

class TabsPaneImpl extends PaneImpl implements TabsPane {
  private current: string;
  private readonly tabs: readonly MenuTab[];
  private readonly tabSlots: readonly number[];
  private readonly onSelect: ((id: string) => void) | undefined;

  constructor(
    menu: MenuImpl,
    id: string,
    priority: number,
    order: number,
    o: TabsPaneOptions,
    private readonly area: readonly number[],
  ) {
    super(menu, id, priority, order);
    if (!Array.isArray(o.tabs) || o.tabs.length === 0) throw new BridgeError('invalid_message', 'a tabs pane needs at least one tab');
    if (!Array.isArray(o.tabSlots) || o.tabSlots.length !== o.tabs.length) throw new BridgeError('invalid_message', 'a tabs pane needs one tab slot per tab');
    const ids = new Set<string>();
    for (const t of o.tabs) {
      if (typeof t.id !== 'string' || t.id === '' || ids.has(t.id)) throw new BridgeError('invalid_message', `tab ids must be unique non-empty strings: ${String(t.id)}`);
      ids.add(t.id);
    }
    this.tabs = [...o.tabs];
    this.current = o.selected ?? o.tabs[0]!.id;
    if (!ids.has(this.current)) throw new BridgeError('invalid_message', `no tab ${this.current}`);
    this.onSelect = o.onSelect;
    this.tabSlots = [...o.tabSlots];
  }

  get selected(): string {
    return this.current;
  }

  cells(): Map<number, Cell> {
    const out = new Map<number, Cell>();
    this.tabs.forEach((t, i) => {
      const item = lookItem(t.icon, this.ctx);
      const shown = t.id === this.current ? { ...item, glint: true } : item;
      out.set(this.tabSlots[i]!, button(shown, () => this.pick(t.id)));
    });
    const tab = this.tabs.find((t) => t.id === this.current)!;
    const cells = typeof tab.cells === 'function' ? tab.cells() : tab.cells;
    this.area.forEach((slot, i) => {
      const c = compileCell(cells[i], this.ctx);
      if (c !== undefined) out.set(slot, c);
    });
    return out;
  }

  private pick(id: string): void {
    if (id === this.current) return;
    this.select(id);
    const fn = this.onSelect;
    if (fn !== undefined) this.menu.guard(() => fn(id));
  }

  select(id: string): void {
    if (!this.alive) return;
    if (!this.tabs.some((t) => t.id === id)) throw new BridgeError('invalid_message', `no tab ${id}`);
    this.current = id;
    this.changed();
  }

  refresh(): void {
    this.redraw();
  }

  override state(): unknown {
    return this.current;
  }

  override restore(state: unknown): void {
    if (typeof state === 'string' && this.tabs.some((t) => t.id === state)) this.current = state;
  }
}

class ToggleImpl extends PaneImpl implements MenuToggle {
  private on: boolean;
  private readonly onItem: SlotItem;
  private readonly offItem: SlotItem;

  constructor(
    menu: MenuImpl,
    id: string,
    priority: number,
    order: number,
    private readonly slot: number,
    private readonly o: ToggleOptions,
  ) {
    super(menu, id, priority, order);
    this.onItem = lookItem(o.on, menu.ctx);
    this.offItem = lookItem(o.off, menu.ctx);
    this.on = o.value === true;
  }

  get value(): boolean {
    return this.on;
  }

  cells(): Map<number, Cell> {
    return new Map([[this.slot, button(this.on ? this.onItem : this.offItem, (e) => this.flip(e))]]);
  }

  private flip(e: ContainerEvent): ContainerHandlerReturn {
    this.on = !this.on;
    this.changed();
    return this.o.onChange?.(this.on, e);
  }

  set(value: boolean): void {
    if (!this.alive || this.on === value) return;
    this.on = value;
    this.changed();
  }

  override state(): unknown {
    return this.on;
  }

  override restore(state: unknown): void {
    if (typeof state === 'boolean') this.on = state;
  }
}

class CycleImpl<V> extends PaneImpl implements MenuCycle<V> {
  private at: number;

  constructor(
    menu: MenuImpl,
    id: string,
    priority: number,
    order: number,
    private readonly slot: number,
    private readonly o: CycleOptions<V>,
  ) {
    super(menu, id, priority, order);
    if (!Array.isArray(o.values) || o.values.length === 0) throw new BridgeError('invalid_message', 'a cycle button needs at least one value');
    if (typeof o.look !== 'function') throw new BridgeError('invalid_message', 'a cycle button needs a look function');
    this.at = this.wrap(o.index ?? 0);
  }

  private wrap(i: number): number {
    const n = this.o.values.length;
    return ((Math.trunc(finite('cycle index', i)) % n) + n) % n;
  }

  get index(): number {
    return this.at;
  }

  get value(): V {
    return this.o.values[this.at]!;
  }

  cells(): Map<number, Cell> {
    return new Map([[this.slot, button(lookItem(this.o.look(this.value, this.at), this.ctx), (e) => this.step(e))]]);
  }

  private step(e: ContainerEvent): ContainerHandlerReturn {
    this.at = this.wrap(this.at + (e.click === 'right' || e.click === 'shift-right' ? -1 : 1));
    this.changed();
    return this.o.onChange?.(this.value, e);
  }

  set(index: number): void {
    if (!this.alive) return;
    this.at = this.wrap(index);
    this.changed();
  }

  override state(): unknown {
    return this.at;
  }

  override restore(state: unknown): void {
    if (typeof state === 'number') this.at = this.wrap(state);
  }
}

class CounterImpl extends PaneImpl implements MenuCounter {
  private n: number;
  private readonly item: SlotItem;
  private readonly min: number;
  private readonly max: number;
  private readonly step: number;
  private readonly shiftStep: number;

  constructor(
    menu: MenuImpl,
    id: string,
    priority: number,
    order: number,
    private readonly slot: number,
    private readonly o: CounterOptions,
  ) {
    super(menu, id, priority, order);
    this.min = intIn('counter min', o.min ?? 1, 0, 0xffffffff);
    this.max = intIn('counter max', o.max ?? 64, this.min, 0xffffffff);
    this.step = intIn('counter step', o.step ?? 1, 1, 0xffffffff);
    this.shiftStep = intIn('counter shiftStep', o.shiftStep ?? 10, 1, 0xffffffff);
    this.item = lookItem(o.look, menu.ctx);
    this.n = clamp(Math.trunc(finite('counter value', o.value ?? this.min)), this.min, this.max);
  }

  get value(): number {
    return this.n;
  }

  cells(): Map<number, Cell> {
    return new Map([[this.slot, button(withCount(this.item, this.n), (e) => this.click(e))]]);
  }

  private click(e: ContainerEvent): ContainerHandlerReturn {
    if (e.click === 'middle') return undefined;
    const by = (e.shift ? this.shiftStep : this.step) * (e.click === 'right' || e.click === 'shift-right' ? -1 : 1);
    const next = clamp(this.n + by, this.min, this.max);
    if (next === this.n) return undefined;
    this.n = next;
    this.changed();
    return this.o.onChange?.(this.n, e);
  }

  set(value: number): void {
    if (!this.alive) return;
    this.n = clamp(Math.trunc(finite('counter value', value)), this.min, this.max);
    this.changed();
  }

  override state(): unknown {
    return this.n;
  }

  override restore(state: unknown): void {
    if (typeof state === 'number' && Number.isFinite(state)) this.n = clamp(Math.trunc(state), this.min, this.max);
  }
}

class SliderImpl extends PaneImpl implements MenuSlider {
  private v: number;
  private readonly min: number;
  private readonly max: number;
  private readonly filled: SlotItem;
  private readonly empty: SlotItem;
  private readonly knob: SlotItem;

  constructor(
    menu: MenuImpl,
    id: string,
    priority: number,
    order: number,
    private readonly slots: readonly number[],
    private readonly o: SliderOptions,
  ) {
    super(menu, id, priority, order);
    if (slots.length < 2) throw new BridgeError('invalid_message', 'a slider needs at least two slots');
    this.min = finite('slider min', o.min ?? 0);
    this.max = finite('slider max', o.max ?? 1);
    if (this.max <= this.min) throw new BridgeError('invalid_message', 'slider max must be above min');
    this.filled = lookItem(o.filled ?? LIME, menu.ctx);
    this.empty = lookItem(o.empty ?? GRAY, menu.ctx);
    this.knob = o.knob === undefined ? { ...this.filled, glint: true } : lookItem(o.knob, menu.ctx);
    this.v = clamp(finite('slider value', o.value ?? this.min), this.min, this.max);
  }

  get value(): number {
    return this.v;
  }

  private valueAt(i: number): number {
    return this.min + ((this.max - this.min) * i) / (this.slots.length - 1);
  }

  cells(): Map<number, Cell> {
    const knob = Math.round(((this.v - this.min) / (this.max - this.min)) * (this.slots.length - 1));
    const out = new Map<number, Cell>();
    this.slots.forEach((slot, i) => {
      const look = i < knob ? this.filled : i === knob ? this.knob : this.empty;
      const item = look.name === undefined ? { ...look, name: format(this.valueAt(i)) } : look;
      out.set(slot, button(item, (e) => this.pick(i, e)));
    });
    return out;
  }

  private pick(i: number, e: ContainerEvent): ContainerHandlerReturn {
    this.v = this.valueAt(i);
    this.changed();
    return this.o.onChange?.(this.v, e);
  }

  set(value: number): void {
    if (!this.alive) return;
    this.v = clamp(finite('slider value', value), this.min, this.max);
    this.changed();
  }

  override state(): unknown {
    return this.v;
  }

  override restore(state: unknown): void {
    if (typeof state === 'number' && Number.isFinite(state)) this.v = clamp(state, this.min, this.max);
  }
}

class BarImpl extends PaneImpl implements MenuBar {
  private v: number;
  private readonly filled: SlotItem;
  private readonly empty: SlotItem;

  constructor(
    menu: MenuImpl,
    id: string,
    priority: number,
    order: number,
    private readonly slots: readonly number[],
    o: BarOptions,
  ) {
    super(menu, id, priority, order);
    this.filled = lookItem(o.filled ?? LIME, menu.ctx);
    this.empty = lookItem(o.empty ?? GRAY, menu.ctx);
    this.v = clamp(finite('bar value', o.value ?? 0), 0, 1);
  }

  get value(): number {
    return this.v;
  }

  cells(): Map<number, Cell> {
    const exact = this.v * this.slots.length;
    const full = Math.floor(exact);
    const part = exact - full;
    const name = `${Math.round(this.v * 100)}%`;
    const out = new Map<number, Cell>();
    this.slots.forEach((slot, i) => {
      const base = i < full ? this.filled : i === full && part > 0 ? { ...this.filled, bar: part } : this.empty;
      out.set(slot, { item: { ...base, name }, rule: LOCKED, onClick: undefined, close: false });
    });
    return out;
  }

  set(value: number): void {
    if (!this.alive) return;
    this.v = clamp(finite('bar value', value), 0, 1);
    this.redraw();
  }
}

class LabelImpl extends PaneImpl implements MenuLabel {
  private value: string;

  constructor(
    menu: MenuImpl,
    id: string,
    priority: number,
    order: number,
    private readonly slots: readonly number[],
    text: string,
    private readonly font: (char: string) => MenuLook,
  ) {
    super(menu, id, priority, order);
    this.value = String(text);
  }

  get text(): string {
    return this.value;
  }

  cells(): Map<number, Cell> {
    const out = new Map<number, Cell>();
    const chars = [...this.value];
    this.slots.forEach((slot, i) => {
      const ch = chars[i];
      if (ch === undefined || ch.trim() === '') return;
      out.set(slot, { item: lookItem(this.font(ch), this.ctx), rule: LOCKED, onClick: undefined, close: false });
    });
    return out;
  }

  set(text: string): void {
    if (!this.alive) return;
    this.value = String(text);
    this.redraw();
  }
}

function defaultFont(ch: string): MenuLook {
  return { item: 'minecraft:white_stained_glass_pane', name: ch, countText: ch };
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

/**
 * A chest container view whose slots are compiled from a base layer and panes. Every change
 * compiles the whole menu again (at most 54 slots) and writes the view once; the store turns that
 * into a field patch.
 */
export class MenuImpl implements Menu {
  readonly id: string;
  readonly view: ContainerViewImpl;
  readonly rows: number;
  readonly restored: Promise<void>;
  private readonly base = new Map<number, Cell>();
  private readonly panes = new Map<string, PaneImpl>();
  private nextOrder = 0;
  private compiled = new Map<number, Cell>();
  private chars = new Map<string, number[]>();
  private parentMenu: MenuImpl | undefined;
  private onClose: MenuOptions['onClose'];
  private onStorage: MenuOptions['onStorage'];
  private onAsk: MenuOptions['onAsk'];
  private reopenParent: boolean;
  private backSlot: number | undefined;
  private backItem: SlotItem;
  private readonly persist: boolean;
  /** Persisted pane state by pane id, applied to panes as they are created. */
  private saved: Record<string, unknown> | undefined;
  private removed = false;
  /** Set while {@link aside} closes or reopens the view, so the handlers do not hear it. */
  private quiet = false;
  private options: MenuOptions;
  private readonly events = new Emitter<OpenCloseEvents>();

  constructor(
    private readonly host: MenuHost,
    id: string,
    o: MenuOptions,
  ) {
    const rows = o.rows ?? 3;
    if (!Number.isInteger(rows) || rows < 1 || rows > 6) throw new BridgeError('invalid_message', `menu rows must be an integer from 1 to 6: ${String(rows)}`);
    this.id = id;
    this.rows = rows;
    this.options = o;
    this.onClose = o.onClose;
    this.onStorage = o.onStorage;
    this.onAsk = o.onAsk;
    this.reopenParent = o.reopenParent === true;
    this.persist = o.persist === true;
    this.backItem = lookItem(DEFAULT_BACK, host.items);
    this.backSlot = undefined;
    this.setBack(o.back);
    const options: ContainerViewOptions = {
      title: o.title,
      layout: `chest:${rows}`,
      slots: [],
      on: {
        click: (e) => this.press(e),
        ask: (e) => this.onAsk?.(e),
        storage: (slots, view) => this.onStorage?.(slots, view),
        open: () => {
          if (!this.quiet) this.events.emit('open');
        },
        close: (_v, reason) => {
          if (this.quiet) return;
          const fn = this.onClose;
          if (fn !== undefined) fn(reason);
          this.events.emit('close', reason);
        },
      },
    };
    if (o.filler !== undefined) options.filler = o.filler;
    if (o.closeOnClick === true) options.closeOnClick = true;
    if (o.requireAnswer === true) options.requireAnswer = true;
    this.view = host.view(id, options);
    this.restored = this.persist ? this.load() : Promise.resolve();
  }

  /** @internal */
  get ctx(): ItemContext {
    return this.host.items;
  }

  get isOpen(): boolean {
    return this.view.isOpen;
  }

  get alive(): boolean {
    return !this.removed;
  }

  update(patch: MenuUpdate): void {
    if (!this.removed) this.reconfigure({ ...this.options, ...patch });
  }

  on<E extends keyof OpenCloseEvents>(event: E, fn: (...args: OpenCloseEvents[E]) => void): () => void {
    return this.events.on(event, fn);
  }

  get parent(): Menu | undefined {
    return this.parentMenu;
  }

  private get size(): number {
    return this.rows * ROW;
  }

  private get storeKey(): string {
    return `~view/${this.id}`;
  }

  private setBack(back: MenuOptions['back']): void {
    if (back === undefined) return;
    const slot = typeof back === 'number' ? back : back.slot;
    this.checkSlot(slot);
    this.backSlot = slot;
    if (typeof back === 'object' && back.button !== undefined) this.backItem = lookItem(back.button, this.host.items);
  }

  private async load(): Promise<void> {
    try {
      const v = await this.host.store.get(this.storeKey);
      if (!isRecord(v) || this.removed) return;
      this.saved = v;
      for (const p of this.panes.values()) if (v[p.id] !== undefined) p.restore(v[p.id]);
      this.render();
    } catch (e) {
      this.host.reportError(asBridgeError(e));
    }
  }

  private save(): void {
    if (!this.persist || this.removed) return;
    const state: Record<string, unknown> = { ...(this.saved ?? {}) };
    for (const p of this.panes.values()) {
      const s = p.state();
      if (s !== undefined) state[p.id] = s;
    }
    this.saved = state;
    this.host.store.set(this.storeKey, state).catch((e: unknown) => this.host.reportError(asBridgeError(e)));
  }

  /** @internal Runs a user callback, reporting what it throws. */
  guard(fn: () => unknown): void {
    try {
      const r = fn();
      if (r instanceof Promise) r.catch((e: unknown) => this.host.reportError(asBridgeError(e)));
    } catch (e) {
      this.host.reportError(asBridgeError(e));
    }
  }

  /** Applies options of a repeated `menu(id, options)` call. Rows and `persist` cannot change. */
  reconfigure(o: MenuOptions): void {
    this.options = o;
    this.onClose = o.onClose;
    this.onStorage = o.onStorage;
    this.onAsk = o.onAsk;
    this.reopenParent = o.reopenParent === true;
    this.setBack(o.back);
    const u: Parameters<ContainerViewImpl['update']>[0] = { title: o.title };
    if (o.filler !== undefined) u.filler = o.filler;
    if (o.closeOnClick !== undefined) u.closeOnClick = o.closeOnClick;
    if (o.requireAnswer !== undefined) u.requireAnswer = o.requireAnswer;
    this.view.update(u);
    this.render();
  }

  private checkSlot(slot: number): void {
    if (!Number.isInteger(slot) || slot < 0 || slot >= this.size) throw new BridgeError('invalid_message', `slot ${String(slot)} is outside menu ${this.id} (0 to ${this.size - 1})`);
  }

  /** Resolves an area to slot indexes. @throws BridgeError `invalid_message` for slots outside the menu or an unknown layout character. */
  resolve(area: MenuArea | number): number[] {
    if (typeof area === 'number') {
      this.checkSlot(area);
      return [area];
    }
    if (typeof area === 'string') {
      const slots = this.chars.get(area);
      if (slots === undefined) throw new BridgeError('invalid_message', `the last layout of menu ${this.id} has no character ${area}`);
      return [...slots];
    }
    if (Array.isArray(area)) {
      const slots = area as readonly number[];
      for (const s of slots) this.checkSlot(s);
      return [...slots];
    }
    const r = area as MenuRect;
    for (const [k, v] of [['x', r.x], ['y', r.y], ['width', r.width], ['height', r.height]] as const) {
      if (!Number.isInteger(v) || v < (k === 'width' || k === 'height' ? 1 : 0)) throw new BridgeError('invalid_message', `menu area ${k} must be a ${k === 'x' || k === 'y' ? 'non-negative' : 'positive'} integer`);
    }
    if (r.x + r.width > ROW || r.y + r.height > this.rows) throw new BridgeError('invalid_message', `menu area does not fit into ${ROW}x${this.rows}`);
    const out: number[] = [];
    for (let y = r.y; y < r.y + r.height; y++) for (let x = r.x; x < r.x + r.width; x++) out.push(y * ROW + x);
    return out;
  }

  /** Compiles base, panes and the back button into slots, and writes the view. */
  render(): void {
    if (this.removed) return;
    const layers: { priority: number; order: number; cells: () => Map<number, Cell> }[] = [{ priority: 0, order: -1, cells: () => this.base }];
    for (const p of this.panes.values()) if (p.visible) layers.push({ priority: p.priority, order: p.order, cells: () => p.cells() });
    layers.sort((a, b) => a.priority - b.priority || a.order - b.order);
    const out = new Map<number, Cell>();
    for (const l of layers) for (const [slot, c] of l.cells()) if (slot >= 0 && slot < this.size) out.set(slot, c);
    if (this.parentMenu !== undefined && this.backSlot !== undefined) out.set(this.backSlot, button(this.backItem, () => this.back()));
    this.compiled = out;
    const items = new Map<number, SlotItem>();
    const rules = new Map<number, WireRule>();
    for (const [slot, c] of out) {
      if (c.item !== undefined) items.set(slot, c.item);
      if (c.rule.policy !== 'button') rules.set(slot, c.rule);
    }
    this.view.compiled(items, rules);
  }

  /** @internal Reports an error to the scope. */
  report(error: BridgeError): void {
    this.host.reportError(error);
  }

  /** @internal Asks the player for text. */
  promptText(text: string, options: TextPromptOptions): Promise<string> {
    return this.host.promptText(text, options);
  }

  /**
   * @internal Runs `fn` with the menu set aside: an open menu closes so a prompt screen can show,
   * and opens again afterwards with its parent kept. That close and reopen are not reported to the
   * menu's close and open handlers, since for the menu nothing ended.
   */
  async aside<T>(fn: () => Promise<T>): Promise<T> {
    const wasOpen = this.view.isOpen;
    if (wasOpen) this.quietly(() => this.view.close());
    try {
      return await fn();
    } finally {
      if (wasOpen && !this.removed && !this.view.isOpen) this.quietly(() => this.view.open());
    }
  }

  private quietly(fn: () => void): void {
    this.quiet = true;
    try {
      fn();
    } finally {
      this.quiet = false;
    }
  }

  /** @internal A pane's state changed. */
  stateChanged(): void {
    this.render();
    this.save();
  }

  /** @internal */
  dropPane(p: PaneImpl): void {
    if (this.panes.get(p.id) === p) this.panes.delete(p.id);
    this.render();
  }

  private press(e: ContainerEvent): ContainerHandlerReturn {
    if (!PRESSES.has(e.click)) return undefined;
    const c = this.compiled.get(e.slot);
    if (c === undefined || c.rule.policy !== 'button') return undefined;
    const r = c.onClick?.(e);
    if (!c.close) return r;
    return Promise.resolve(r).then((v) => {
      if (v !== false) this.view.close();
      return v;
    });
  }

  private add<P extends PaneImpl>(id: string, make: (order: number) => P): P {
    if (this.removed) throw new BridgeError('invalid_message', `menu ${this.id} was removed`);
    if (typeof id !== 'string' || id === '') throw new BridgeError('invalid_message', 'pane ids must be non-empty strings');
    const p = make(this.nextOrder++);
    const prev = this.panes.get(id);
    prev?.retire();
    this.panes.delete(id);
    this.panes.set(id, p);
    const saved = this.saved?.[id];
    if (saved !== undefined) p.restore(saved);
    this.render();
    return p;
  }

  private priority(o: { priority?: number }): number {
    return o.priority === undefined ? 0 : finite('pane priority', o.priority);
  }

  slot(index: number, b: MenuButton | null): Menu {
    this.checkSlot(index);
    if (b === null) this.base.delete(index);
    else this.base.set(index, button(lookItem(b, this.host.items), b.onClick, b.close === true));
    this.render();
    return this;
  }

  layout(rows: readonly string[], legend: Readonly<Record<string, MenuCell>>): Menu {
    if (!Array.isArray(rows) || rows.length > this.rows) throw new BridgeError('invalid_message', `a layout of menu ${this.id} has 1 to ${this.rows} rows`);
    const chars = new Map<string, number[]>();
    const cells = new Map<string, Cell | undefined>();
    const placed = new Map<number, Cell | undefined>();
    rows.forEach((row, y) => {
      if (typeof row !== 'string') throw new BridgeError('invalid_message', 'layout rows must be strings');
      const cols = [...row.replace(/\s+/g, '')];
      if (cols.length > ROW) throw new BridgeError('invalid_message', `layout row ${y} has more than ${ROW} characters: ${row}`);
      cols.forEach((ch, x) => {
        if (!Object.hasOwn(legend, ch)) throw new BridgeError('invalid_message', `layout character ${ch} is not in the legend`);
        if (!cells.has(ch)) cells.set(ch, compileCell(legend[ch], this.host.items));
        const slot = y * ROW + x;
        const list = chars.get(ch) ?? [];
        list.push(slot);
        chars.set(ch, list);
        placed.set(slot, cells.get(ch));
      });
    });
    for (const [slot, c] of placed) {
      if (c === undefined) this.base.delete(slot);
      else this.base.set(slot, c);
    }
    this.chars = chars;
    this.render();
    return this;
  }

  area(char: string): number[] {
    return this.resolve(char);
  }

  storage(area: MenuArea | number, rule: SlotPolicy | SlotRule): Menu {
    const r = toRule(rule);
    if (isSource(r)) throw new BridgeError('invalid_message', 'source slots need an item each; use menu.palette(items) instead');
    if (!isStorage(r)) throw new BridgeError('invalid_message', 'storage slots need a take, place, free or ask policy');
    for (const slot of this.resolve(area)) this.base.set(slot, { item: undefined, rule: r, onClick: undefined, close: false });
    this.render();
    return this;
  }

  palette(items: readonly ItemInput[], o: PaletteOptions = {}): Menu {
    if (!Array.isArray(items)) throw new BridgeError('invalid_message', 'palette items must be a list');
    checkKeys('palette option', o, PALETTE_KEYS);
    const slots = o.slots === undefined ? this.firstSlots(items.length) : this.resolve(o.slots);
    if (items.length > slots.length) throw new BridgeError('invalid_message', `a palette of ${items.length} items does not fit into ${slots.length} slots`);
    const cells = items.map((i): Cell => ({ item: toSlotItem(i, this.host.items), rule: SOURCE, onClick: undefined, close: false }));
    slots.forEach((slot, n) => {
      const c = cells[n];
      if (c === undefined) this.base.delete(slot);
      else this.base.set(slot, c);
    });
    this.render();
    return this;
  }

  /** The first `n` slots of the menu, row by row. */
  private firstSlots(n: number): number[] {
    if (n > this.size) throw new BridgeError('invalid_message', `a palette of ${n} items does not fit into menu ${this.id} (${this.size} slots)`);
    return Array.from({ length: n }, (_, i) => i);
  }

  fill(item: ItemInput | null): Menu {
    this.view.update({ filler: item });
    return this;
  }

  setTitle(title: string): Menu {
    this.view.update({ title });
    return this;
  }

  pane(id: string, o: StaticPaneOptions | OutlinePaneOptions): StaticPane {
    const priority = this.priority(o);
    if (o.type === 'outline') {
      const rect = o.area ?? { x: 0, y: 0, width: ROW, height: this.rows };
      const inside = this.resolve(rect);
      const border = inside.filter((s) => {
        const x = s % ROW;
        const y = Math.floor(s / ROW);
        return x === rect.x || y === rect.y || x === rect.x + rect.width - 1 || y === rect.y + rect.height - 1;
      });
      return this.add(id, (order) => new OutlinePaneImpl(this, id, priority, order, border, [], o.cell));
    }
    const slots = this.resolve(o.area);
    return this.add(id, (order) => new StaticPaneImpl(this, id, priority, order, slots, o.cells ?? [], o.fill));
  }

  paginated(id: string, o: PagedPaneOptions): PagedPane {
    const slots = this.resolve(o.area);
    if (o.prev !== undefined) this.checkSlot(o.prev);
    if (o.next !== undefined) this.checkSlot(o.next);
    const search = o.search;
    if (search !== undefined) {
      if (!isRecord(search)) throw new BridgeError('invalid_message', 'search must be an object with a slot');
      this.checkSlot(search.slot);
      if (search.clear !== undefined) this.checkSlot(search.clear);
    }
    const priority = this.priority(o);
    return this.add(
      id,
      (order) => new PagedPaneImpl(this, id, priority, order, slots, o.entries, o.prev, o.next, o.prevButton ?? DEFAULT_PREV, o.nextButton ?? DEFAULT_NEXT, o.page ?? 0, search),
    );
  }

  scrolling(id: string, o: ScrollPaneOptions): ScrollPane {
    this.resolve(o.area);
    if (o.back !== undefined) this.checkSlot(o.back);
    if (o.forward !== undefined) this.checkSlot(o.forward);
    if (o.direction !== undefined && o.direction !== 'vertical' && o.direction !== 'horizontal') throw new BridgeError('invalid_message', 'scroll direction must be vertical or horizontal');
    const priority = this.priority(o);
    return this.add(
      id,
      (order) =>
        new ScrollPaneImpl(
          this,
          id,
          priority,
          order,
          o.area,
          o.entries,
          o.direction !== 'horizontal',
          o.back,
          o.forward,
          o.backButton ?? DEFAULT_SCROLL_BACK,
          o.forwardButton ?? DEFAULT_SCROLL_FORWARD,
          o.offset ?? 0,
        ),
    );
  }

  tabs(id: string, o: TabsPaneOptions): TabsPane {
    const area = this.resolve(o.area);
    for (const s of o.tabSlots ?? []) this.checkSlot(s);
    const priority = this.priority(o);
    return this.add(id, (order) => new TabsPaneImpl(this, id, priority, order, o, area));
  }

  toggle(slot: number, o: ToggleOptions): MenuToggle {
    this.checkSlot(slot);
    const id = o.id ?? `toggle@${slot}`;
    const priority = this.priority(o);
    return this.add(id, (order) => new ToggleImpl(this, id, priority, order, slot, o));
  }

  cycle<V>(slot: number, o: CycleOptions<V>): MenuCycle<V> {
    this.checkSlot(slot);
    const id = o.id ?? `cycle@${slot}`;
    const priority = this.priority(o);
    return this.add(id, (order) => new CycleImpl(this, id, priority, order, slot, o));
  }

  counter(slot: number, o: CounterOptions): MenuCounter {
    this.checkSlot(slot);
    const id = o.id ?? `counter@${slot}`;
    const priority = this.priority(o);
    return this.add(id, (order) => new CounterImpl(this, id, priority, order, slot, o));
  }

  slider(area: MenuArea, o: SliderOptions = {}): MenuSlider {
    const slots = this.resolve(area);
    const id = o.id ?? `slider@${slots[0] ?? 0}`;
    const priority = this.priority(o);
    return this.add(id, (order) => new SliderImpl(this, id, priority, order, slots, o));
  }

  bar(area: MenuArea, o: BarOptions = {}): MenuBar {
    const slots = this.resolve(area);
    const id = o.id ?? `bar@${slots[0] ?? 0}`;
    const priority = this.priority(o);
    return this.add(id, (order) => new BarImpl(this, id, priority, order, slots, o));
  }

  label(area: MenuArea, text: string, o: MenuLabelOptions = {}): MenuLabel {
    const slots = this.resolve(area);
    if (o.font !== undefined && typeof o.font !== 'function') throw new BridgeError('invalid_message', 'a label font must be a function');
    const id = o.id ?? `label@${slots[0] ?? 0}`;
    const priority = this.priority(o);
    return this.add(id, (order) => new LabelImpl(this, id, priority, order, slots, text, o.font ?? defaultFont));
  }

  get(id: string): MenuPane | undefined {
    return this.panes.get(id);
  }

  private setParent(parent: MenuImpl | undefined): void {
    if (parent === this.parentMenu) return;
    this.parentMenu = parent;
    if (this.reopenParent) this.view.update({ reopenParent: parent?.view ?? null });
    this.render();
  }

  open(child?: Menu): void {
    if (child === undefined) {
      this.setParent(undefined);
      this.view.open();
      return;
    }
    if (!(child instanceof MenuImpl) || child === this) throw new BridgeError('invalid_message', 'open(child) takes another menu of this bridge');
    child.setParent(this);
    child.view.open();
  }

  back(): void {
    const parent = this.parentMenu;
    if (parent === undefined) {
      this.close();
      return;
    }
    // Opening the parent directly keeps its own parent, so Back works through several levels.
    parent.view.open();
  }

  close(): void {
    this.view.close();
  }

  remove(): void {
    if (this.removed) return;
    this.removed = true;
    for (const p of this.panes.values()) p.retire();
    this.panes.clear();
    this.base.clear();
    this.view.remove();
    this.host.forget(this);
    this.events.emit('remove');
  }
}
