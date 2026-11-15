import { BridgeError, type EffectBody } from '@mineflayer-bridge/protocol';
import { finite, nonNegativeInt, resourceId, spreadVec } from './check.ts';
import type { EffectsApi, ParticleOptions, SoundOptions, TitleOptions } from './display-types.ts';
import type { InputHost } from './input.ts';
import { soundCategory } from './sounds.ts';
import { toVec3 } from './util.ts';

/**
 * `effects` of one session, sent as unreliable `effect.play`. Effects above the advertised
 * `effectsPerSecond` (sounds, particles, titles and action bars together) are dropped here, as the
 * mod would drop them anyway.
 */
export class EffectsImpl implements EffectsApi {
  /** Send times of recent effects, in ms, oldest first; at most one second's worth. */
  private readonly recent: number[] = [];
  private warned = false;

  constructor(private readonly host: InputHost) {}

  private play(effect: EffectBody): void {
    if (!this.admit()) return;
    this.host.core.sendUnreliable('effect.play', this.host.link.encode('effect.play', { effect }));
  }

  private admit(): boolean {
    const now = Date.now();
    while (this.recent.length > 0 && now - this.recent[0]! >= 1000) this.recent.shift();
    const limit = this.host.link.limits.effectsPerSecond;
    if (this.recent.length >= limit) {
      if (!this.warned) this.host.log.warn(`dropping effects above ${limit} per second (the player's quota)`);
      this.warned = true;
      return false;
    }
    this.warned = false;
    this.recent.push(now);
    return true;
  }

  sound(id: string, o: SoundOptions = {}): void {
    const value: Extract<EffectBody, { type: 'sound' }>['value'] = { id: resourceId('sound id', id) };
    if (o.at !== undefined) value.at = toVec3(o.at, 'sound position');
    if (o.volume !== undefined) value.volume = finite('sound volume', o.volume);
    if (o.pitch !== undefined) value.pitch = finite('sound pitch', o.pitch);
    if (o.category !== undefined) value.category = soundCategory(o.category);
    this.play({ type: 'sound', value });
  }

  particles(type: string, o: ParticleOptions): void {
    if (typeof o !== 'object' || o === null) throw new BridgeError('invalid_message', 'particles need options with a position');
    const value: Extract<EffectBody, { type: 'particles' }>['value'] = { type: resourceId('particle type', type), at: toVec3(o.at, 'particle position') };
    if (o.count !== undefined) value.count = nonNegativeInt('particle count', o.count);
    if (o.spread !== undefined) value.spread = spreadVec('particle spread', o.spread);
    if (o.speed !== undefined) value.speed = finite('particle speed', o.speed);
    if (o.options !== undefined) value.options = o.options;
    this.play({ type: 'particles', value });
  }

  title(title: string, o: TitleOptions = {}): void {
    if (typeof title !== 'string') throw new BridgeError('invalid_message', 'title must be a string');
    const value: Extract<EffectBody, { type: 'title' }>['value'] = { title };
    if (o.subtitle !== undefined) value.subtitle = String(o.subtitle);
    if (o.fadeIn !== undefined) value.fadeIn = nonNegativeInt('title fadeIn', o.fadeIn);
    if (o.stay !== undefined) value.stay = nonNegativeInt('title stay', o.stay);
    if (o.fadeOut !== undefined) value.fadeOut = nonNegativeInt('title fadeOut', o.fadeOut);
    this.play({ type: 'title', value });
  }

  actionBar(text: string): void {
    if (typeof text !== 'string') throw new BridgeError('invalid_message', 'action-bar text must be a string');
    this.play({ type: 'actionbar', value: { text } });
  }
}
