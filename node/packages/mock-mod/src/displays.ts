import { BridgeError, decodeKind, KindsById, Messages, type ElementInput, type Interactive, type MessagePayloads } from '@mineflayer-bridge/protocol';
import type { ModCore } from './core.ts';
import type { Session } from './session.ts';
import { parseSnbt } from './snbt.ts';
import type { MockDisplay, MockEffect, MockElementEventOptions, MockEntityProps, MockGroup, MockNotifyButton, MockPlaced, MockPose } from './types.ts';
import { isDisplay } from './util.ts';

const POSE_PARTS = ['head', 'body', 'leftArm', 'rightArm', 'leftLeg', 'rightLeg'] as const;

/** Longest message shown, in characters. */
export const MAX_TEXT_CHARS = 4096;
/** Most lines of one message shown. */
export const MAX_TEXT_LINES = 20;

/** Cuts a message to {@link MAX_TEXT_CHARS} characters and {@link MAX_TEXT_LINES} lines, as the mod does. */
export function clampText(text: string): string {
  const out = text.length > MAX_TEXT_CHARS ? text.slice(0, MAX_TEXT_CHARS) : text;
  let lines = 1;
  for (let i = 0; i < out.length; i++) if (out[i] === '\n' && ++lines > MAX_TEXT_LINES) return out.slice(0, i);
  return out;
}

/** A notification a bot showed with `notify`. */
export interface Notification {
  owner: string;
  text: string;
  level: string;
  icon: string | undefined;
  /** Buttons, each with the full key of the action it runs, or the line it puts into the chat box. */
  buttons: MockNotifyButton[];
  sound: string | undefined;
}

/** Displays, groups and emitters in the world, plus what bots played or showed: effects and toasts. */
export class Displays {
  /** Effects dropped for being over `effectsPerSecond`. */
  droppedEffects = 0;
  private readonly effectWindows = new Map<string, { startedAt: number; count: number }>();
  private readonly notifyWindows = new Map<string, { startedAt: number; count: number }>();
  /** Effects bots played with `effect.play`, oldest first. */
  readonly effects: MockEffect[] = [];
  /** Toasts received so far, oldest first. */
  readonly notifications: Notification[] = [];

  constructor(private readonly core: ModCore) {}

  displays(): MockDisplay[] {
    return this.core.store.placed(isDisplay).map((p) => ({ ...p, group: (p.data as { group?: string }).group }));
  }

  groups(): MockGroup[] {
    const displays = this.displays();
    const groups = this.core.store.placed((k) => k === 'core:group');
    const sameLayer = (a: MockPlaced, b: MockPlaced): boolean => a.owner === b.owner && a.extension === b.extension && a.layer === b.layer;
    return groups.map((g) => ({
      ...g,
      children: displays.filter((d) => sameLayer(d, g) && d.group === g.id),
      groups: groups.filter((x) => sameLayer(x, g) && x.id.startsWith(`${g.id}/`) && !x.id.slice(g.id.length + 1).includes('/')),
    }));
  }

  emitters(): MockPlaced[] {
    return this.core.store.placed((k) => k === 'core:particle-emitter');
  }

  entityProps(key: string): MockEntityProps {
    const d = this.core.store.decoded(key, 'core:display.entity');
    if (d === undefined) throw new BridgeError('invalid_message', `mock: no core:display.entity at ${key}`);
    const a = d.armorStand;
    const pose: Partial<Record<(typeof POSE_PARTS)[number], MockPose>> = {};
    if (a !== undefined) {
      for (const part of POSE_PARTS) {
        const p = a[`${part}Pose`];
        if (p !== undefined) pose[part] = [p[0] ?? 0, p[1] ?? 0, p[2] ?? 0];
      }
    }
    return {
      key,
      type: d.type,
      invisible: d.invisible === true,
      onFire: d.onFire === true,
      frozen: d.frozen === true,
      charged: d.charged === true,
      saddled: d.saddled === true,
      variant: d.variant,
      color: d.color,
      profession: d.profession,
      effects: d.effects ?? [],
      armorStand: a === undefined ? undefined : { small: a.small === true, showArms: a.showArms === true, noBasePlate: a.noBasePlate === true, marker: a.marker === true, pose },
      data: d.data,
      nbt: d.data === undefined ? undefined : parseSnbt(d.data),
    };
  }

  elementEvent(key: string, input: ElementInput, options: MockElementEventOptions): void {
    this.core.sweep();
    const o = this.core.store.get(key);
    const core = o === undefined ? undefined : KindsById.get(o.kind);
    if (o === undefined || core === undefined) throw new BridgeError('invalid_message', `mock: no core-kind object at ${key}`);
    // The traits block carries interactions for every kind; display data still may as an alias.
    const interactive = o.traits?.interactive ?? (decodeKind(core, o.data) as { interactive?: Interactive }).interactive;
    if (interactive === undefined || !interactive.inputs.includes(input)) throw new BridgeError('invalid_message', `mock: ${key} does not declare ${input}`);
    if (input === 'key' && (options.key === undefined || !(interactive.keys ?? []).includes(options.key))) {
      throw new BridgeError('invalid_message', `mock: ${key} does not declare key ${String(options.key)}`);
    }
    const s = this.core.liveOwner(key);
    const m = options.modifiers ?? 0;
    const modifiers = typeof m === 'number' ? m : (m.shift === true ? 1 : 0) | (m.ctrl === true ? 2 : 0) | (m.alt === true ? 4 : 0);
    const p: MessagePayloads['element.event'] = { element: key, input, modifiers };
    if (options.delta !== undefined) p.delta = options.delta;
    if (options.key !== undefined) p.key = options.key;
    if (options.local !== undefined) p.local = options.local;
    this.core.sendSession(s, 'element.event', p);
  }

  onEffect(s: Session, p: MessagePayloads['effect.play']): void {
    // Past the per-second limit every kind of effect is dropped without an answer.
    const now = this.core.clock.now();
    const w = this.effectWindows.get(s.owner);
    const window = w === undefined || now - w.startedAt >= 1000 ? { startedAt: now, count: 0 } : w;
    this.effectWindows.set(s.owner, window);
    if (window.count >= (this.core.opts.quotas?.effectsPerSecond ?? 40)) {
      this.droppedEffects++;
      return;
    }
    window.count++;
    const e: MockEffect = { owner: s.owner, effect: p.effect };
    this.effects.push(e);
    this.core.emit('effect', e);
  }

  onNotify(s: Session, p: MessagePayloads['notify']): void {
    const level = p.level ?? 'info';
    const buttons = p.buttons ?? [];
    // As the mod: buttons run the sender's own actions, and the sound is a plain id.
    if (buttons.length > 4) throw new BridgeError('invalid_message', 'a notification takes at most 4 buttons');
    for (const b of buttons) {
      if (b.label.trim() === '' || b.label.length > 32) throw new BridgeError('invalid_message', 'a notification button needs a label of 1 to 32 characters');
      if ((b.action === undefined) === (b.suggest === undefined)) throw new BridgeError('invalid_message', 'a notification button has an action or a line to suggest, not both');
      if (b.action !== undefined && !b.action.startsWith(s.prefix)) throw new BridgeError('invalid_message', `a notification button runs one of the sender's own actions: ${b.action}`);
      if (b.suggest !== undefined && (b.suggest.trim() === '' || b.suggest.length > 256)) throw new BridgeError('invalid_message', 'a notification button suggests a line of 1 to 256 characters');
    }
    if (p.sound !== undefined && !/^[a-z0-9_.-]+(:[a-z0-9_./-]+)?$/.test(p.sound)) throw new BridgeError('invalid_message', `bad sound id ${p.sound}`);
    // As the mod: only an attached bot may notify, at most 10 times a second.
    if (!this.core.player.attachState(s).attached) return this.core.sendSessionError(s, 'not_attached', 'the player has not attached this bot', undefined, Messages.notify.id);
    const now = this.core.clock.now();
    const w = this.notifyWindows.get(s.owner);
    const window = w === undefined || now - w.startedAt >= 1000 ? { startedAt: now, count: 0 } : w;
    this.notifyWindows.set(s.owner, window);
    if (window.count >= (this.core.opts.quotas?.notifiesPerSecond ?? 10)) return this.core.sendSessionError(s, 'rate_limited', 'more than 10 notifications a second', undefined, Messages.notify.id, undefined, true);
    window.count++;
    const text = clampText(p.text);
    const n = { owner: s.owner, text, level, icon: p.icon, buttons: buttons.map((b): MockNotifyButton => ({ label: b.label, action: b.action, suggest: b.suggest })), sound: p.sound };
    this.notifications.push(n);
    this.core.emit('notify', { session: this.core.info(s), text, level, icon: p.icon, buttons: n.buttons, sound: p.sound });
  }
}
