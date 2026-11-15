import { BridgeError, type MessagePayloads, type ToolInput } from '@mineflayer-bridge/protocol';
import type { ModCore } from './core.ts';
import type { Session } from './session.ts';
import type { MockDeclaration, MockInteraction, MockInteractionOutcome, MockInterceptDelivery, MockInvocationResult, MockToolEventOptions } from './types.ts';
import { bareIdentity } from './util.ts';

/** Higher priorities run first, `monitor` always last. */
const INTERCEPT_ORDER: Readonly<Record<string, number>> = { highest: 0, high: 1, normal: 2, low: 3, lowest: 4, monitor: 5 };

type InterceptRule = MockDeclaration<'core:intercept'>;

/** Player controls a bot declares: keybinds, tools, settings values and interaction intercepts. */
export class Controls {
  activeTool: string | undefined;
  private readonly settings = new Map<string, Record<string, unknown>>();
  /** Per-bot `cancel` overrides by bot identity; bots without one follow their device's origin. */
  private readonly interceptGrants = new Map<string, boolean>();

  constructor(private readonly core: ModCore) {}

  keybindEvent(key: string, pressed: boolean): void {
    this.core.sendSession(this.core.ownerSession(key, 'core:keybind'), 'keybind.event', { keybind: key, pressed });
  }

  toolEvent(key: string, input: ToolInput, options: MockToolEventOptions): void {
    const s = this.core.ownerSession(key, 'core:tool');
    const p: MessagePayloads['tool.event'] = { tool: key, input };
    if (options.delta !== undefined) p.delta = options.delta;
    if (options.key !== undefined) p.key = options.key;
    if (options.target !== undefined) p.target = options.target;
    this.core.sendSession(s, 'tool.event', p);
  }

  setActiveTool(key: string | null): void {
    if (key !== null && this.core.store.kindAt(key) !== 'core:tool') throw new BridgeError('invalid_message', `no core:tool at ${key}`);
    this.activeTool = key ?? undefined;
    this.refreshToolAll();
  }

  /** `tool.state` is latest-wins: send it whenever this session's view of the active tool changes. */
  refreshTool(s: Session): void {
    if (s.conn === null || s.closed) return;
    const mine = this.activeTool !== undefined && this.activeTool.startsWith(s.prefix) ? this.activeTool : undefined;
    if (mine === s.toolActive) return;
    s.toolActive = mine;
    this.core.sendSession(s, 'tool.state', mine === undefined ? {} : { active: mine });
  }

  /** Clears the active tool when its declaration disappears. */
  forget(key: string): void {
    if (key !== this.activeTool) return;
    this.activeTool = undefined;
    this.refreshToolAll();
  }

  setSettingValue(settingsKey: string, key: string, value: unknown): void {
    const values = this.settingValues(settingsKey);
    if (value === undefined) delete values[key];
    else values[key] = value;
    this.settings.set(settingsKey, values);
    for (const s of this.core.sessions.values()) if (settingsKey.startsWith(s.prefix)) this.sendSettings(s, settingsKey);
  }

  /** A copy of the player's values of a settings group. */
  settingValues(settingsKey: string): Record<string, unknown> {
    return { ...(this.settings.get(settingsKey) ?? {}) };
  }

  /** Values go to the bot on connect and on every change. */
  sendSettings(s: Session, key: string): void {
    if (s.conn === null || s.closed) return;
    this.core.sendSession(s, 'settings.values', { settings: key, values: this.settingValues(key) });
  }

  /** Sends the values of every settings group the session declared. */
  sendAllSettings(s: Session): void {
    for (const o of this.core.store.map.values()) if (o.kindName === 'core:settings' && o.key.startsWith(s.prefix)) this.sendSettings(s, o.key);
  }

  setInterceptCancel(identity: string, allowed: boolean): void {
    this.interceptGrants.set(bareIdentity(identity), allowed);
  }

  intercepts(): InterceptRule[] {
    return this.core.store.declarations('core:intercept', '~intercept/');
  }

  async interact(o: MockInteraction): Promise<MockInteractionOutcome> {
    const input = o.input ?? 'secondary';
    const held = o.modifiers ?? {};
    const priority = (r: InterceptRule): number => INTERCEPT_ORDER[r.data.priority ?? 'normal'] ?? 2;
    const rules = this.intercepts()
      .filter((r) => {
        const d = r.data;
        if ((d.input ?? 'secondary') !== input || d.target.type !== o.target.type) return false;
        if (d.target.ids !== undefined && (o.id === undefined || !d.target.ids.includes(o.id))) return false;
        if (d.target.tags !== undefined && !d.target.tags.some((t) => o.tags?.includes(t) === true)) return false;
        if (d.modifier !== undefined && held[d.modifier as keyof typeof held] !== true) return false;
        return true;
      })
      .map((r) => ({ rule: r, session: this.interceptSession(r) }))
      .filter((x): x is { rule: InterceptRule; session: Session } => x.session !== undefined)
      .sort((a, b) => priority(a.rule) - priority(b.rule) || (a.rule.key < b.rule.key ? -1 : 1));
    const deliveries: MockInterceptDelivery[] = [];
    let by: string | undefined;
    let pending: Promise<MockInvocationResult> | undefined;
    for (const { rule, session } of rules) {
      const mode = rule.data.mode ?? 'observe';
      const cancelled = by !== undefined;
      deliveries.push({ key: rule.key, owner: session.owner, mode, cancelled });
      const send = (invocation: number): void => this.core.sendSession(session, 'intercept.event', { intercept: rule.key, invocation, target: o.target, cancelled });
      if (!cancelled && mode === 'cancel' && this.mayCancel(session)) {
        by = rule.key;
        pending = this.core.chat.invoke(session, o.timeoutMs ?? 30_000, send);
      } else {
        send(this.core.chat.invocationId());
      }
    }
    return { cancelled: by !== undefined, by, result: pending === undefined ? undefined : await pending, deliveries };
  }

  private refreshToolAll(): void {
    for (const s of this.core.sessions.values()) this.refreshTool(s);
  }

  /** Cancel mode is on for local devices and off for paired ones unless overridden. */
  private mayCancel(s: Session): boolean {
    return this.interceptGrants.get(s.owner.slice(4)) ?? !this.core.devices.isPaired(s.deviceId);
  }

  /** The session a rule reaches: its owner, when attached and, for world-bound rules, in the player's world. */
  private interceptSession(r: InterceptRule): Session | undefined {
    const s = this.core.liveOwnerOrNone(r.key);
    if (s === undefined) return undefined;
    const { attached, worldMatched } = this.core.player.attachState(s);
    if (!attached || (r.scope === 'world' && !worldMatched)) return undefined;
    return s;
  }
}
