import { BridgeError, Messages, type KindPayloads, type MessagePayloads, type SlotClick, type SlotEntry, type SlotItem } from '@mineflayer-bridge/protocol';
import type { ModCore } from './core.ts';
import { Pending } from './pending.ts';
import { isLive, type Session } from './session.ts';
import type { MockClickOptions, MockCloseOptions, MockContainerAck, MockContainerView, MockStorageMove, MockStorageResult } from './types.ts';

/** Storage slots hold client-only items owned by the view. */
const STORAGE_POLICIES: ReadonlySet<string> = new Set(['take', 'place', 'free', 'ask']);
const TAKES: ReadonlySet<string> = new Set(['take', 'free', 'ask']);
const PLACES: ReadonlySet<string> = new Set(['place', 'free', 'ask']);
/** An infinite template; taking copies it and the slot keeps its item. */
const SOURCE = 'source';
const VANILLA_MAX_STACK = 64;

type ViewData = KindPayloads['core:container-view'];

/** Container views and menus: the player's one screen, slot gestures and storage slots. */
export class Views {
  /** Key of the container view the player's screen shows. One screen at a time, as in the game. */
  shown: string | undefined;
  /** The shown view was opened by its bot, so closing it counts as dismissing it. */
  private shownByBot = false;
  private nextInput = 1;
  private readonly acks = new Pending<MockContainerAck, { key: string }>();
  /** What the player left in storage slots, per view key, valid for the object revision it was derived from. */
  private readonly storages = new Map<string, { rev: number; slots: Map<number, SlotItem> }>();

  constructor(private readonly core: ModCore) {}

  list(): MockContainerView[] {
    return this.core.store.declarations('core:container-view', '~view/').map((d) => ({ ...d, open: d.key === this.shown }));
  }

  open(key: string): void {
    const s = this.core.ownerSession(key, 'core:container-view');
    if (this.shown === key) return;
    this.show(key, 'player');
    this.core.sendSession(s, 'container.open', { view: key });
  }

  close(key: string | undefined, options: MockCloseOptions): boolean {
    const shown = this.shown;
    if (key !== undefined && key !== shown) throw new BridgeError('invalid_message', `mock: view ${key} is not open`);
    if (shown === undefined) return false;
    if (this.data(shown)?.requireAnswer === true && options.confirm !== true) return false;
    this.playerClose(shown);
    return true;
  }

  click(key: string, slot: number, click: SlotClick, options: MockClickOptions): Promise<MockContainerAck> {
    const s = this.shownOwner(key);
    if (!Number.isInteger(slot)) throw new BridgeError('invalid_message', 'mock: slot must be an integer');
    // The mod resolves these clicks itself and sends nothing.
    const policy = this.data(key)?.slots.find((e) => e.slot === slot)?.policy ?? 'button';
    if (policy === 'locked') throw new BridgeError('invalid_message', `mock: slot ${slot} is locked, so a click sends nothing`);
    if (STORAGE_POLICIES.has(policy) || policy === SOURCE) throw new BridgeError('invalid_message', `mock: slot ${slot} is a ${policy === SOURCE ? 'source' : 'storage'} slot; use storageMove`);
    const hotbar = options.hotbar;
    if (click === 'number' && (hotbar === undefined || !Number.isInteger(hotbar) || hotbar < 0 || hotbar > 8)) {
      throw new BridgeError('invalid_message', 'mock: a number click needs a hotbar slot from 0 to 8');
    }
    const p: Omit<MessagePayloads['container.input'], 'input'> = { view: key, slot, click };
    if (hotbar !== undefined) p.hotbar = hotbar;
    const acked = this.input(s, key, p, options.timeoutMs ?? 30_000, 'gesture');
    if (this.data(key)!.closeOnClick === true) this.playerClose(key);
    return acked;
  }

  /** What the storage slots of a view hold now, sorted by slot; empty slots are left out. */
  storage(key: string): SlotEntry[] {
    return [...this.storageOf(key)].sort((a, b) => a[0] - b[0]).map(([slot, item]) => ({ slot, item }));
  }

  async storageMove(key: string, move: MockStorageMove): Promise<MockStorageResult> {
    const s = this.shownOwner(key);
    const view = this.data(key)!;
    const entry = (slot: number): SlotEntry => view.slots.find((e) => e.slot === slot) ?? { slot, item: { id: 'minecraft:air', count: 0 }, policy: 'button' };
    const storage = this.storageOf(key);
    const snapshot = new Map(storage);
    const result = (ok: boolean, message: string | undefined, asked = false, rolledBack = false): MockStorageResult => ({ ok, message, asked, rolledBack, storage: this.storage(key) });
    let moving: SlotItem;
    if (move.from === 'player') {
      if (move.item === undefined) throw new BridgeError('invalid_message', 'mock: a move from the player needs an item');
      moving = { ...move.item, count: move.count ?? move.item.count ?? 1 };
    } else if (entry(move.from).policy === SOURCE) {
      const template = entry(move.from).item;
      if (template.id === 'minecraft:air' || (template.count ?? 1) <= 0) return result(false, 'empty');
      if (move.to === 'player') return result(false, 'virtual copy');
      moving = { ...template, count: move.count ?? template.count ?? 1 };
    } else {
      if (!TAKES.has(entry(move.from).policy ?? 'button')) return result(false, 'locked');
      const held = storage.get(move.from);
      if (held === undefined) return result(false, 'empty');
      moving = { ...held, count: Math.min(move.count ?? held.count ?? 1, held.count ?? 1) };
    }
    if (move.to !== 'player') {
      const target = entry(move.to);
      if (!PLACES.has(target.policy ?? 'button')) return result(false, 'locked');
      if (!this.accepts(target, moving)) return result(false, 'not accepted');
      const there = storage.get(move.to);
      if (there !== undefined && there.id !== moving.id) return result(false, 'occupied');
      const total = (there?.count ?? 0) + (moving.count ?? 1);
      if (total > (target.maxStack ?? VANILLA_MAX_STACK)) return result(false, 'full');
      storage.set(move.to, { ...(there ?? moving), count: total });
    }
    if (move.from !== 'player' && entry(move.from).policy !== SOURCE) {
      const held = storage.get(move.from)!;
      const left = (held.count ?? 1) - (moving.count ?? 1);
      if (left > 0) storage.set(move.from, { ...held, count: left });
      else storage.delete(move.from);
    }
    const askSlot = [move.to, move.from].find((x): x is number => typeof x === 'number' && entry(x).policy === 'ask');
    if (askSlot !== undefined) {
      const fromSlot = move.from === 'player' ? -1 : move.from;
      const ack = await this.input(s, key, { view: key, slot: askSlot, click: 'left', moved: moving, fromSlot }, move.timeoutMs ?? 30_000, 'ask move');
      if (!ack.ok) {
        storage.clear();
        for (const [i, item] of snapshot) storage.set(i, item);
        return result(false, ack.message, true, true);
      }
    }
    this.sendStorage(s, key, view);
    return result(true, undefined, askSlot !== undefined);
  }

  /** A bot asks to show a view; the mod refuses with `container.close` when it cannot. */
  onBotOpen(s: Session, key: string): void {
    if (key === this.shown) return;
    const declared = key.startsWith(s.prefix) && this.core.store.kindAt(key) === 'core:container-view';
    if (!declared || s.superseded || !this.core.player.attachState(s).attached) {
      this.core.sendSession(s, 'container.close', { view: key, lost: true });
      return;
    }
    if (this.core.refuseSurface(s, undefined, Messages['container.open'].id, 'view', key)) return;
    this.show(key, 'bot');
  }

  onBotClose(s: Session, key: string): void {
    if (key === this.shown && key.startsWith(s.prefix)) {
      this.shown = undefined;
      this.core.emit('viewClosed', { key, by: 'bot' });
    }
  }

  onAck(s: Session, p: MessagePayloads['container.ack']): void {
    this.acks.answer(p.input, s.owner, { input: p.input, ok: p.ok, message: p.message }, (e) => e.key === p.view);
  }

  /** Drops the shown view once its bot is detached or gone, as the mod does; the bot hears it was lost. */
  lapse(): void {
    const key = this.shown;
    if (key === undefined) return;
    const s = this.core.liveOwnerOrNone(key);
    if (s !== undefined && !s.superseded && this.core.player.attachState(s).attached) return;
    this.shown = undefined;
    if (s !== undefined) this.core.sendSession(s, 'container.close', { view: key, lost: true });
    this.core.emit('viewClosed', { key, by: 'lost' });
  }

  /** Closes the shown view when its declaration disappears. */
  forget(key: string): void {
    if (key !== this.shown) return;
    this.shown = undefined;
    this.core.emit('viewClosed', { key, by: 'removed' });
  }

  clear(): void {
    this.acks.clear();
  }

  private data(key: string): ViewData | undefined {
    return this.core.store.decoded(key, 'core:container-view');
  }

  /** The owner of `key`, which must be the shown view. */
  private shownOwner(key: string): Session {
    if (this.shown !== key) throw new BridgeError('invalid_message', `mock: view ${key} is not open`);
    return this.core.ownerSession(key, 'core:container-view');
  }

  /** Sends `container.input` with a fresh input id and resolves with the bot's `container.ack`. */
  private input(s: Session, key: string, p: Omit<MessagePayloads['container.input'], 'input'>, timeoutMs: number, what: string): Promise<MockContainerAck> {
    const input = this.nextInput++;
    return this.acks.expect(
      input,
      s.owner,
      { key },
      timeoutMs,
      () => new BridgeError('timeout', `mock: ${what} ${input} got no container.ack within ${timeoutMs} ms`),
      () => this.core.sendSession(s, 'container.input', { ...p, input }),
    );
  }

  /** Storage contents, derived again from the declaration whenever the bot wrote the view since. */
  private storageOf(key: string): Map<number, SlotItem> {
    const o = this.core.store.get(key);
    if (o?.kindName !== 'core:container-view') throw new BridgeError('invalid_message', `mock: no core:container-view at ${key}`);
    const known = this.storages.get(key);
    if (known !== undefined && known.rev === o.rev) return known.slots;
    const slots = new Map<number, SlotItem>();
    for (const e of this.data(key)!.slots) {
      if (STORAGE_POLICIES.has(e.policy ?? 'button') && e.item.id !== 'minecraft:air' && (e.item.count ?? 1) > 0) slots.set(e.slot, e.item);
    }
    this.storages.set(key, { rev: o.rev, slots });
    return slots;
  }

  private accepts(e: SlotEntry, item: SlotItem): boolean {
    const a = e.accepts;
    if (a === undefined || (a.ids === undefined && a.tags === undefined)) return true;
    if (a.ids?.includes(item.id) === true) return true;
    const tags = this.core.opts.itemTags ?? {};
    return (a.tags ?? []).some((t) => tags[t]?.includes(item.id) === true);
  }

  private sendStorage(s: Session, key: string, view: ViewData): void {
    const storage = this.storageOf(key);
    const slots: SlotEntry[] = [];
    for (const e of view.slots) {
      const item = storage.get(e.slot);
      if (STORAGE_POLICIES.has(e.policy ?? 'button') && item !== undefined) slots.push({ slot: e.slot, item, policy: e.policy });
    }
    this.core.sendSession(s, 'container.storage', { view: key, slots });
  }

  /** The player closed a view: tell its bot, then open its parent when it names one. */
  private playerClose(key: string): void {
    const closing = this.core.liveOwnerOrNone(key);
    if (this.shownByBot && closing !== undefined) this.core.gate.dismissed(closing.owner, this.core.clock.now());
    const parent = this.data(key)?.reopenParent;
    this.hide(key);
    if (parent === undefined || this.core.store.kindAt(parent) !== 'core:container-view') return;
    const owner = this.core.liveOwnerOrNone(parent);
    if (owner === undefined) return;
    this.show(parent, 'player');
    this.core.sendSession(owner, 'container.open', { view: parent });
  }

  /** Shows a view, closing the one shown before. */
  private show(key: string, by: 'player' | 'bot'): void {
    if (this.shown !== undefined) this.hide(this.shown);
    this.shown = key;
    this.shownByBot = by === 'bot';
    // The player opened it, so what the owner shows next was asked for.
    const owner = by === 'player' ? this.core.liveOwnerOrNone(key) : undefined;
    if (owner !== undefined) this.core.gate.asked(owner.owner, this.core.clock.now());
    this.core.emit('viewOpened', { key, by });
  }

  /** Closes a view as the player and tells its bot. */
  private hide(key: string): void {
    this.shown = undefined;
    for (const s of this.core.sessions.values()) {
      if (isLive(s) && key.startsWith(s.prefix)) this.core.sendSession(s, 'container.close', { view: key });
    }
    this.core.emit('viewClosed', { key, by: 'player' });
  }
}
