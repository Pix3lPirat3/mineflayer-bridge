import { BridgeError, type HudAnchor, type KindName, type KindPayloads } from '@mineflayer-bridge/protocol';
import type {
  BossbarHandle,
  BossbarOptions,
  HudBadgeHandle,
  HudBarHandle,
  HudBarOptions,
  HudItemHandle,
  HudItemOptions,
  HudKvEntries,
  HudKvHandle,
  HudListHandle,
  HudListOptions,
  HudPanel,
  HudPanelOptions,
  HudSparklineHandle,
  HudSparklineOptions,
  HudTextHandle,
  HudTextOptions,
  HudWidgetOptions,
} from './input-types.ts';
import { finite, segment } from './check.ts';
import type { ScopeObjects } from './scope.ts';
import type { ColorLike } from './types.ts';
import { Emitter, toColor } from './util.ts';

const HUD_DIR = '~hud/';
const BOSSBAR_DIR = '~bossbar/';
const DEFAULT_SPARKLINE_CAPACITY = 60;
const ANCHORS: ReadonlySet<string> = new Set(['top-left', 'top-center', 'top-right', 'middle-left', 'middle-right', 'bottom-left', 'bottom-right']);
const BOSSBAR_COLORS: ReadonlySet<string> = new Set(['pink', 'blue', 'red', 'green', 'yellow', 'purple', 'white']);
const BOSSBAR_OVERLAYS: ReadonlySet<string> = new Set(['progress', 'notched_6', 'notched_10', 'notched_12', 'notched_20']);

type WidgetKind = Exclude<Extract<KindName, `core:hud.${string}`>, 'core:hud.panel'>;

function missing(what: string, id: string): BridgeError {
  return new BridgeError('invalid_message', `${what} ${id} does not exist yet; give its content on the first call`);
}

/** One declared widget. Handles of removed widgets are inert so they cannot resurrect it. */
abstract class Widget<K extends WidgetKind> {
  abstract readonly kind: K;
  alive = true;

  constructor(
    protected readonly panel: HudPanelImpl,
    readonly id: string,
    protected order: number,
  ) {}

  protected abstract build(): KindPayloads[K];

  write(): void {
    if (!this.alive) return;
    this.panel.writeWidget(this.id, this.kind, this.build());
  }

  setOrder(order: number | undefined): void {
    if (order !== undefined) this.order = order;
  }

  protected base(): { panel: string; order: number } {
    return { panel: this.panel.id, order: this.order };
  }
}

class TextWidget extends Widget<'core:hud.text'> implements HudTextHandle {
  readonly kind = 'core:hud.text';
  text = '';
  maxLines: number | undefined;
  color: number | undefined;

  protected build(): KindPayloads['core:hud.text'] {
    const d: KindPayloads['core:hud.text'] = { ...this.base(), text: this.text };
    if (this.maxLines !== undefined) d.maxLines = this.maxLines;
    if (this.color !== undefined) d.color = this.color;
    return d;
  }

  set(text: string): void {
    this.text = String(text);
    this.write();
  }
}

class KvWidget extends Widget<'core:hud.kv'> implements HudKvHandle {
  readonly kind = 'core:hud.kv';
  private rows = new Map<string, { value: string; color: number | undefined }>();

  protected build(): KindPayloads['core:hud.kv'] {
    return {
      ...this.base(),
      entries: [...this.rows].map(([key, r]) => (r.color === undefined ? { key, value: r.value } : { key, value: r.value, color: r.color })),
    };
  }

  load(entries: HudKvEntries): void {
    const rows = new Map<string, { value: string; color: number | undefined }>();
    if (Array.isArray(entries)) {
      for (const e of entries as Extract<HudKvEntries, readonly unknown[]>) rows.set(e.key, { value: String(e.value), color: e.color === undefined ? undefined : toColor(e.color) });
    } else {
      for (const [k, v] of Object.entries(entries as Readonly<Record<string, string | number | boolean>>)) rows.set(k, { value: String(v), color: undefined });
    }
    this.rows = rows;
  }

  set(key: string, value: string | number | boolean, color?: ColorLike): void {
    const prev = this.rows.get(key);
    this.rows.set(key, { value: String(value), color: color === undefined ? prev?.color : toColor(color) });
    this.write();
  }

  remove(key: string): void {
    if (this.rows.delete(key)) this.write();
  }

  replace(entries: HudKvEntries): void {
    this.load(entries);
    this.write();
  }

  get(key: string): string | undefined {
    return this.rows.get(key)?.value;
  }
}

class BarWidget extends Widget<'core:hud.bar'> implements HudBarHandle {
  readonly kind = 'core:hud.bar';
  private value = 0;
  private min: number | undefined;
  private max: number | undefined;
  private label: string | undefined;
  private color: number | undefined;

  protected build(): KindPayloads['core:hud.bar'] {
    const d: KindPayloads['core:hud.bar'] = { ...this.base(), value: this.value };
    if (this.label !== undefined) d.label = this.label;
    if (this.min !== undefined) d.min = this.min;
    if (this.max !== undefined) d.max = this.max;
    if (this.color !== undefined) d.color = this.color;
    return d;
  }

  apply(o: Partial<HudBarOptions>): void {
    if (o.value !== undefined) this.value = finite('bar value', o.value);
    if (o.min !== undefined) this.min = finite('bar min', o.min);
    if (o.max !== undefined) this.max = finite('bar max', o.max);
    if (o.label !== undefined) this.label = o.label;
    if (o.color !== undefined) this.color = toColor(o.color);
    this.setOrder(o.order);
  }

  set(value: number): void {
    this.value = finite('bar value', value);
    this.write();
  }

  update(o: Partial<HudBarOptions>): void {
    this.apply(o);
    this.write();
  }
}

class ListWidget extends Widget<'core:hud.list'> implements HudListHandle {
  readonly kind = 'core:hud.list';
  items: string[] = [];
  selected: number | undefined;
  maxRows: number | undefined;

  protected build(): KindPayloads['core:hud.list'] {
    const d: KindPayloads['core:hud.list'] = { ...this.base(), items: this.items };
    if (this.selected !== undefined) d.selected = this.selected;
    if (this.maxRows !== undefined) d.maxRows = this.maxRows;
    return d;
  }

  set(items: readonly string[]): void {
    this.items = items.map(String);
    this.write();
  }

  select(index: number | undefined): void {
    if (index !== undefined && !Number.isInteger(index)) throw new BridgeError('invalid_message', 'list selection must be an integer');
    this.selected = index;
    this.write();
  }
}

class BadgeWidget extends Widget<'core:hud.badge'> implements HudBadgeHandle {
  readonly kind = 'core:hud.badge';
  text = '';
  color: number | undefined;

  protected build(): KindPayloads['core:hud.badge'] {
    const d: KindPayloads['core:hud.badge'] = { ...this.base(), text: this.text };
    if (this.color !== undefined) d.color = this.color;
    return d;
  }

  set(text: string, color?: ColorLike): void {
    this.text = String(text);
    if (color !== undefined) this.color = toColor(color);
    this.write();
  }
}

/**
 * A ring buffer of samples. Pushes only mark it dirty; one microtask encodes it, so a burst of
 * pushes in one tick becomes a single store write (and one patch), and an unchanged buffer is
 * dropped by the store.
 */
class SparklineWidget extends Widget<'core:hud.sparkline'> implements HudSparklineHandle {
  readonly kind = 'core:hud.sparkline';
  private ring = new Float32Array(DEFAULT_SPARKLINE_CAPACITY);
  private start = 0;
  private length = 0;
  private scheduled = false;
  min: number | undefined;
  max: number | undefined;
  color: number | undefined;

  configure(o: HudSparklineOptions): void {
    if (o.capacity !== undefined) {
      if (!Number.isInteger(o.capacity) || o.capacity < 1) throw new BridgeError('invalid_message', 'sparkline capacity must be a positive integer');
      if (o.capacity !== this.ring.length) {
        const kept = this.values().slice(-o.capacity);
        this.ring = new Float32Array(o.capacity);
        this.ring.set(kept);
        this.start = 0;
        this.length = kept.length;
      }
    }
    if (o.min !== undefined) this.min = finite('sparkline min', o.min);
    if (o.max !== undefined) this.max = finite('sparkline max', o.max);
    if (o.color !== undefined) this.color = toColor(o.color);
    this.setOrder(o.order);
  }

  protected build(): KindPayloads['core:hud.sparkline'] {
    const d: KindPayloads['core:hud.sparkline'] = { ...this.base(), values: Float32Array.from(this.values()) };
    if (this.min !== undefined) d.min = this.min;
    if (this.max !== undefined) d.max = this.max;
    if (this.color !== undefined) d.color = this.color;
    return d;
  }

  push(...values: number[]): void {
    const cap = this.ring.length;
    for (const v of values) {
      finite('sparkline sample', v);
      if (this.length < cap) {
        this.ring[(this.start + this.length) % cap] = v;
        this.length++;
      } else {
        this.ring[this.start] = v;
        this.start = (this.start + 1) % cap;
      }
    }
    this.schedule();
  }

  clear(): void {
    this.start = 0;
    this.length = 0;
    this.schedule();
  }

  values(): number[] {
    const out: number[] = new Array(this.length);
    for (let i = 0; i < this.length; i++) out[i] = this.ring[(this.start + i) % this.ring.length]!;
    return out;
  }

  /** Collects a tick's pushes; the session flush runs later, on setImmediate. */
  private schedule(): void {
    if (this.scheduled) return;
    this.scheduled = true;
    queueMicrotask(() => {
      this.scheduled = false;
      this.write();
    });
  }
}

class ItemWidget extends Widget<'core:hud.item'> implements HudItemHandle {
  readonly kind = 'core:hud.item';
  item = '';
  count: number | undefined;
  label: string | undefined;

  apply(itemId: string, o: Omit<HudItemOptions, 'order'>): void {
    if (typeof itemId !== 'string' || itemId === '') throw new BridgeError('invalid_message', 'item id must be a non-empty string');
    this.item = itemId;
    if (o.count !== undefined) {
      if (!Number.isInteger(o.count) || o.count < 0) throw new BridgeError('invalid_message', 'item count must be a non-negative integer');
      this.count = o.count;
    }
    if (o.label !== undefined) this.label = o.label;
  }

  protected build(): KindPayloads['core:hud.item'] {
    const d: KindPayloads['core:hud.item'] = { ...this.base(), item: this.item };
    if (this.count !== undefined) d.count = this.count;
    if (this.label !== undefined) d.label = this.label;
    return d;
  }

  set(itemId: string, o: Omit<HudItemOptions, 'order'> = {}): void {
    this.apply(itemId, o);
    this.write();
  }
}

type AnyWidget = TextWidget | KvWidget | BarWidget | ListWidget | BadgeWidget | SparklineWidget | ItemWidget;

/** A HUD panel of one scope. Widgets are separate objects that name the panel. */
export class HudPanelImpl implements HudPanel {
  readonly id: string;
  private readonly objects: ScopeObjects;
  private readonly widgets = new Map<string, AnyWidget>();
  private nextOrder = 0;
  private data: KindPayloads['core:hud.panel'] = {};
  private declared = false;
  private retiredScope = false;
  private readonly events = new Emitter<{ removed: [] }>();

  constructor(objects: ScopeObjects, id: string) {
    segment('HUD panel id', id);
    this.objects = objects;
    this.id = id;
  }

  update(o: HudPanelOptions): void {
    if (o.anchor !== undefined && !ANCHORS.has(o.anchor)) throw new BridgeError('invalid_message', `unknown HUD anchor ${String(o.anchor)}`);
    const d = { ...this.data };
    if (o.title !== undefined) d.title = o.title;
    if (o.anchor !== undefined) d.anchor = o.anchor.replaceAll('-', '_') as HudAnchor;
    if (o.order !== undefined) d.order = o.order;
    if (o.collapsed !== undefined) d.collapsed = o.collapsed;
    this.data = d;
    this.declare();
  }

  get alive(): boolean {
    return this.declared && !this.retiredScope;
  }

  on(event: 'removed', fn: () => void): () => void {
    return this.events.on(event, fn);
  }

  private declare(): void {
    this.declared = true;
    this.objects.put(`${HUD_DIR}${this.id}`, 'core:hud.panel', this.data, { world: 'global' });
  }

  /** @internal Writes one widget; a destroyed panel is declared again first. */
  writeWidget<K extends WidgetKind>(id: string, kind: K, data: KindPayloads[K]): void {
    if (!this.declared) this.declare();
    this.objects.put(`${HUD_DIR}${this.id}/${id}`, kind, data, { world: 'global' });
  }

  private widget<W extends AnyWidget>(id: string, type: new (panel: HudPanelImpl, id: string, order: number) => W, order: number | undefined): { w: W; fresh: boolean } {
    segment('HUD widget id', id);
    const prev = this.widgets.get(id);
    if (prev instanceof type) {
      prev.setOrder(order);
      return { w: prev, fresh: false };
    }
    if (prev !== undefined) prev.alive = false;
    const w = new type(this, id, order ?? this.nextOrder);
    this.nextOrder = Math.max(this.nextOrder, (order ?? this.nextOrder) + 1);
    this.widgets.set(id, w);
    return { w, fresh: true };
  }

  text(id: string, text?: string, o: HudTextOptions = {}): HudTextHandle {
    const { w, fresh } = this.widget(id, TextWidget, o.order);
    if (fresh && text === undefined) throw missing('text widget', id);
    if (text !== undefined) w.text = String(text);
    if (o.maxLines !== undefined) w.maxLines = o.maxLines;
    if (o.color !== undefined) w.color = toColor(o.color);
    if (fresh || text !== undefined || o.maxLines !== undefined || o.color !== undefined || o.order !== undefined) w.write();
    return w;
  }

  kv(id: string, entries?: HudKvEntries, o: HudWidgetOptions = {}): HudKvHandle {
    const { w, fresh } = this.widget(id, KvWidget, o.order);
    if (entries !== undefined) w.load(entries);
    if (fresh || entries !== undefined || o.order !== undefined) w.write();
    return w;
  }

  bar(id: string, o?: HudBarOptions): HudBarHandle {
    const { w, fresh } = this.widget(id, BarWidget, o?.order);
    if (fresh && o === undefined) throw missing('bar widget', id);
    if (o !== undefined) {
      w.apply(o);
      w.write();
    }
    return w;
  }

  list(id: string, items?: readonly string[], o: HudListOptions = {}): HudListHandle {
    const { w, fresh } = this.widget(id, ListWidget, o.order);
    if (items !== undefined) w.items = items.map(String);
    if (o.selected !== undefined) w.selected = o.selected;
    if (o.maxRows !== undefined) w.maxRows = o.maxRows;
    if (fresh || items !== undefined || o.selected !== undefined || o.maxRows !== undefined || o.order !== undefined) w.write();
    return w;
  }

  badge(id: string, text?: string, color?: ColorLike, o: HudWidgetOptions = {}): HudBadgeHandle {
    const { w, fresh } = this.widget(id, BadgeWidget, o.order);
    if (fresh && text === undefined) throw missing('badge widget', id);
    if (text !== undefined) w.text = String(text);
    if (color !== undefined) w.color = toColor(color);
    if (fresh || text !== undefined || color !== undefined || o.order !== undefined) w.write();
    return w;
  }

  sparkline(id: string, o: HudSparklineOptions = {}): HudSparklineHandle {
    const { w, fresh } = this.widget(id, SparklineWidget, o.order);
    w.configure(o);
    if (fresh || Object.keys(o).length > 0) w.write();
    return w;
  }

  item(id: string, itemId?: string, o: HudItemOptions = {}): HudItemHandle {
    const { w, fresh } = this.widget(id, ItemWidget, o.order);
    if (fresh && itemId === undefined) throw missing('item widget', id);
    if (itemId !== undefined) w.apply(itemId, o);
    if (fresh || itemId !== undefined || o.order !== undefined) w.write();
    return w;
  }

  has(id: string): boolean {
    return this.widgets.has(id);
  }

  remove(id: string): void {
    const w = this.widgets.get(id);
    if (w === undefined) return;
    w.alive = false;
    this.widgets.delete(id);
    this.objects.remove(`${HUD_DIR}${this.id}/${id}`);
  }

  clear(): void {
    for (const w of this.widgets.values()) w.alive = false;
    this.widgets.clear();
    this.objects.removePrefix(`${HUD_DIR}${this.id}/`);
  }

  /** Makes every handle inert without touching the objects; used when the scope unloads. */
  retire(): void {
    const was = this.alive;
    for (const w of this.widgets.values()) w.alive = false;
    this.widgets.clear();
    this.retiredScope = true;
    if (was) this.events.emit('removed');
    this.events.removeAll();
  }

  destroy(): void {
    const was = this.alive;
    this.clear();
    this.declared = false;
    this.objects.remove(`${HUD_DIR}${this.id}`);
    if (was) this.events.emit('removed');
  }
}

function bossbarData(o: BossbarOptions): KindPayloads['core:bossbar'] {
  if (typeof o.text !== 'string') throw new BridgeError('invalid_message', 'boss bar text must be a string');
  const d: KindPayloads['core:bossbar'] = { text: o.text };
  if (o.progress !== undefined) d.progress = Math.min(1, Math.max(0, finite('boss bar progress', o.progress)));
  if (o.color !== undefined) {
    if (!BOSSBAR_COLORS.has(o.color)) throw new BridgeError('invalid_message', `unknown boss bar colour ${String(o.color)}`);
    d.color = o.color;
  }
  if (o.overlay !== undefined) {
    if (!BOSSBAR_OVERLAYS.has(o.overlay)) throw new BridgeError('invalid_message', `unknown boss bar overlay ${String(o.overlay)}`);
    d.overlay = o.overlay;
  }
  return d;
}

/** A boss bar of one scope at `~bossbar/<id>`. */
export class BossbarImpl implements BossbarHandle {
  readonly id: string;
  private options: BossbarOptions;
  private live = true;
  private readonly events = new Emitter<{ removed: [] }>();

  constructor(
    private readonly objects: ScopeObjects,
    id: string,
    options: BossbarOptions,
    private readonly forget: (bar: BossbarImpl) => void,
  ) {
    segment('boss bar id', id);
    this.id = id;
    this.options = { ...options };
    this.write();
  }

  private write(): void {
    this.objects.put(`${BOSSBAR_DIR}${this.id}`, 'core:bossbar', bossbarData(this.options), { world: 'global' });
  }

  get alive(): boolean {
    return this.live;
  }

  on(event: 'removed', fn: () => void): () => void {
    return this.events.on(event, fn);
  }

  replace(options: BossbarOptions): void {
    this.live = true;
    this.options = { ...options };
    this.write();
  }

  update(options: Partial<BossbarOptions>): void {
    if (!this.live) return;
    const next = { ...this.options };
    for (const [k, v] of Object.entries(options)) if (v !== undefined) (next as Record<string, unknown>)[k] = v;
    bossbarData(next);
    this.options = next;
    this.write();
  }

  /** Makes the handle inert without removing the object. */
  retire(): void {
    if (!this.live) return;
    this.live = false;
    this.events.emit('removed');
    this.events.removeAll();
  }

  remove(): void {
    if (!this.live) return;
    this.objects.remove(`${BOSSBAR_DIR}${this.id}`);
    this.forget(this);
    this.retire();
  }
}
