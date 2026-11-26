import { BridgeError, decodeKind, Kinds, type Budget, type ClientSample, type Detail, type MessagePayloads, type WorldKey } from '@mineflayer-bridge/protocol';
import type { ModCore } from './core.ts';
import type { Session } from './session.ts';
import type { AttachPolicy, FocusPolicy, LayerToggle, MockSampleRequest } from './types.ts';
import { bareIdentity, worldsMatch } from './util.ts';

/** The player's default rate cap, and the highest it can be set to. */
const DEFAULT_SAMPLE_RATE_HZ = 4;
const MAX_SAMPLE_RATE_HZ = 20;

const DEFAULT_BUDGET: Budget = { messagesPerSecond: 2000, bytesPerSecond: 8 * 1024 * 1024, streamBytesInFlight: 128 * 1024 * 1024 };

/** The player's side of every session: world, attach, layer toggles, level of detail, budget and client samples. */
export class Player {
  world: WorldKey | undefined;
  /** The player's profile name, sent in `player.context`. */
  name: string | undefined;
  /** The player's profile UUID, sent in `player.context`. */
  uuid: string | undefined;
  /** What the player is doing, sent in `player.context`. */
  state = { alive: true, away: false, windowFocused: true };
  /** The player's own world, sent in `player.context` while the player is in a world; `lanPort` while it is open to LAN. */
  singleplayer: { lanPort?: number } | undefined;
  budget: Budget;
  private attachPolicy: AttachPolicy;
  private readonly attachOverrides = new Map<string, boolean>();
  private readonly layerToggles: { filter: LayerToggle; visible: boolean }[] = [];
  private detail: Detail = 'full';
  private sampleRateCapHz = DEFAULT_SAMPLE_RATE_HZ;
  /** Focused bot (owner, identity or name); `null` for none, `undefined` until set: every attached session counts as focused. */
  private focus: string | null | undefined;
  private focusPolicy: FocusPolicy = 'grant';

  constructor(private readonly core: ModCore) {
    this.attachPolicy = core.opts.attachPolicy ?? 'auto';
    this.budget = core.opts.budget ?? DEFAULT_BUDGET;
  }

  setState(state: Partial<{ alive: boolean; away: boolean; windowFocused: boolean }>): void {
    this.state = { ...this.state, ...state };
    this.refreshAll();
  }

  setSingleplayer(singleplayer: { lanPort?: number } | null): void {
    this.singleplayer = singleplayer ?? undefined;
    this.refreshAll();
  }

  setName(name: string | undefined): void {
    this.name = name;
    this.refreshAll();
  }

  setUuid(uuid: string | undefined): void {
    this.uuid = uuid;
    this.refreshAll();
  }

  setWorld(world: WorldKey | null): void {
    this.world = world ?? undefined;
    this.refreshAll();
  }

  setAttached(identity: string, attached: boolean | null): void {
    const id = bareIdentity(identity);
    if (attached === null) this.attachOverrides.delete(id);
    else this.attachOverrides.set(id, attached);
    this.refreshAll();
  }

  setAttachPolicy(policy: AttachPolicy): void {
    this.attachPolicy = policy;
    this.refreshAll();
  }

  setLayerVisible(filter: LayerToggle, visible: boolean): void {
    this.layerToggles.push({ filter: { ...filter }, visible });
    this.refreshAll();
  }

  setDetail(detail: Detail): void {
    this.detail = detail;
    this.refreshAll();
  }

  setFocus(match: string | null): void {
    this.focus = match;
    this.refreshAll();
  }

  setFocusPolicy(policy: FocusPolicy): void {
    this.focusPolicy = policy;
  }

  /** Whether `s` is the focused bot. */
  focused(s: Session): boolean {
    if (this.focus === undefined) return this.attachState(s).attached;
    return this.focus !== null && this.core.findSession(this.focus) === s;
  }

  /** A bot asked for focus; the player grants it unless the policy refuses. */
  onFocusRequest(s: Session, p: MessagePayloads['focus.request']): void {
    const granted = this.focusPolicy === 'grant';
    if (granted) this.setFocus(s.owner);
    this.core.emit('focusRequested', { session: this.core.info(s), reason: p.reason, granted });
  }

  setBudget(budget: Budget): void {
    this.budget = budget;
    for (const s of this.core.sessions.values()) {
      if (s.conn !== null) this.core.sendSession(s, 'budget.update', { budget });
    }
  }

  attachState(s: Session): { attached: boolean; worldMatched: boolean } {
    const inWorld = this.world !== undefined;
    if (s.kind === 'controller') return { attached: true, worldMatched: inWorld };
    const worldMatched = inWorld && s.world !== undefined && worldsMatch(this.world!, s.world);
    const override = this.attachOverrides.get(s.owner.slice(4));
    const attached = override ?? (this.attachPolicy === 'always' ? true : this.attachPolicy === 'auto' ? worldMatched : false);
    return { attached, worldMatched };
  }

  refreshAll(): void {
    for (const s of this.core.sessions.values()) this.refresh(s);
  }

  /** `player.context` and `interest` go out after open and again whenever they change. */
  refresh(s: Session): void {
    if (s.conn === null || s.closed) return;
    const { attached, worldMatched } = this.attachState(s);
    const context: MessagePayloads['player.context'] = {
      inWorld: this.world !== undefined,
      world: this.world,
      attached,
      ...(this.name === undefined ? {} : { name: this.name }),
      ...(this.uuid === undefined ? {} : { uuid: this.uuid }),
      alive: this.state.alive,
      away: this.state.away,
      windowFocused: this.state.windowFocused,
      ...(this.world === undefined || this.singleplayer === undefined ? {} : { singleplayer: this.singleplayer }),
    };
    const layers: MessagePayloads['interest']['layers'] = [];
    for (const o of this.core.store.map.values()) {
      if (o.kindName !== 'core:layer' || !o.key.startsWith(s.prefix)) continue;
      const rest = o.key.slice(s.prefix.length);
      const slash = rest.indexOf('/');
      if (slash < 0) continue;
      const extension = rest.slice(0, slash);
      const layer = o.layer ?? rest.slice(slash + 1);
      const data = decodeKind(Kinds['core:layer'], o.data);
      layers.push({ owner: s.owner, extension, layer, visible: this.layerVisible(s, extension, layer, data.category, data.defaultVisible ?? true) });
    }
    layers.sort((a, b) => (a.extension + '/' + a.layer < b.extension + '/' + b.layer ? -1 : 1));
    const interest: MessagePayloads['interest'] = { attached, worldMatched, detail: this.detail, layers, focused: this.focused(s) };
    const contextKey = JSON.stringify(context);
    const interestKey = JSON.stringify(interest);
    if (contextKey !== s.lastContext) {
      s.lastContext = contextKey;
      this.core.sendSession(s, 'player.context', context);
    }
    if (interestKey !== s.lastInterest) {
      s.lastInterest = interestKey;
      this.core.sendSession(s, 'interest', interest);
    }
  }

  setSampleRateLimit(hz: number): void {
    if (!Number.isFinite(hz) || hz <= 0 || hz > MAX_SAMPLE_RATE_HZ) throw new BridgeError('invalid_message', `mock: sample rate must be in (0, ${MAX_SAMPLE_RATE_HZ}], got ${hz}`);
    this.sampleRateCapHz = hz;
  }

  onSampleRequest(s: Session, p: MessagePayloads['sample.request']): void {
    const r: MockSampleRequest = { rateHz: p.rateHz ?? DEFAULT_SAMPLE_RATE_HZ, position: p.position ?? false, target: p.target ?? false, heldItem: p.heldItem ?? false };
    // A request with every field off withdraws sampling.
    s.sampleRequest = r.position || r.target || r.heldItem ? r : undefined;
    if (s.sampleRequest === undefined) clearSampleTimer(s);
  }

  /** Sends `sample` to each requesting session, filtered by request and permission, within its rate. */
  sample(sample: ClientSample): number {
    let n = 0;
    for (const s of this.core.sessions.values()) {
      const r = s.sampleRequest;
      if (r === undefined || s.conn === null || s.closed || s.superseded) continue;
      const { attached, worldMatched } = this.attachState(s);
      if (!attached || !worldMatched) continue;
      const granted = (permission: string): boolean => this.core.devices.granted(s, permission);
      const out: ClientSample = {};
      if (r.position && granted('client.sample.position')) {
        if (sample.position !== undefined) out.position = sample.position;
        if (sample.yaw !== undefined) out.yaw = sample.yaw;
        if (sample.pitch !== undefined) out.pitch = sample.pitch;
      }
      if (r.target && sample.target !== undefined && granted('client.sample.target')) out.target = sample.target;
      if (r.heldItem && sample.heldItem !== undefined && granted('client.sample.inventory')) out.heldItem = sample.heldItem;
      if (Object.keys(out).length === 0) continue;
      n++;
      const intervalMs = 1000 / Math.min(r.rateHz, this.sampleRateCapHz);
      const wait = s.sampleSentAt + intervalMs - Date.now();
      if (wait <= 0 && s.sampleTimer === undefined) {
        this.sendSample(s, out);
        continue;
      }
      // Latest wins: a sample inside the interval replaces the one waiting.
      s.samplePending = out;
      s.sampleTimer ??= setTimeout(() => {
        s.sampleTimer = undefined;
        const pending = s.samplePending;
        s.samplePending = undefined;
        if (pending !== undefined && s.sampleRequest !== undefined && s.conn !== null && !s.closed) this.sendSample(s, pending);
      }, Math.max(0, wait));
    }
    return n;
  }

  private sendSample(s: Session, sample: ClientSample): void {
    s.sampleSentAt = Date.now();
    this.core.sendSession(s, 'client.sample', sample);
    this.core.emit('sample', { session: this.core.info(s), sample });
  }

  private layerVisible(s: Session, extension: string, layer: string, category: string | undefined, defaultVisible: boolean): boolean {
    let visible = defaultVisible;
    for (const { filter, visible: v } of this.layerToggles) {
      if (filter.owner !== undefined && filter.owner !== s.owner && `bot:${filter.owner}` !== s.owner) continue;
      if (filter.extension !== undefined && filter.extension !== extension) continue;
      if (filter.layer !== undefined && filter.layer !== layer) continue;
      if (filter.category !== undefined && filter.category !== category) continue;
      visible = v;
    }
    return visible;
  }
}

/** Cancels a trailing sample waiting for the rate limit. */
export function clearSampleTimer(s: Session): void {
  if (s.sampleTimer !== undefined) clearTimeout(s.sampleTimer);
  s.sampleTimer = undefined;
  s.samplePending = undefined;
}
