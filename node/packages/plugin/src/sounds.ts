import { BridgeError, type KindPayloads, type Vec3d } from '@mineflayer-bridge/protocol';
import { BlobData } from './blobs.ts';
import { finite, nonNegativeInt, oneOf, resourceId } from './check.ts';
import type { SoundCategory, SoundEmitter, SoundEmitterOptions, SoundEmitterUpdate, SoundSource } from './display-types.ts';
import { Placed } from './displays.ts';

/** Vanilla sound categories (26.3 `SoundSource`), by their wire names. */
export const SOUND_CATEGORIES: ReadonlySet<string> = new Set<SoundCategory>(['master', 'music', 'record', 'weather', 'block', 'hostile', 'neutral', 'player', 'ambient', 'voice', 'ui']);

/** What a sound emitter plays: a sound event, or an Ogg Vorbis file sent as a blob. */
export type SoundContent = { readonly sound: string } | { readonly audio: BlobData };

/** Every Ogg page starts with this capture pattern. */
const OGG_MAGIC = [0x4f, 0x67, 0x67, 0x53];

/** @throws BridgeError `invalid_message` for an unknown category. */
export function soundCategory(v: string): SoundCategory {
  return oneOf('sound category', v, SOUND_CATEGORIES) as SoundCategory;
}

/** @throws BridgeError `invalid_message` for an empty id or bytes that are not an Ogg file. */
export function soundContent(v: SoundSource): SoundContent {
  if (!(v instanceof Uint8Array)) return { sound: resourceId('sound id', v) };
  if (v.length < OGG_MAGIC.length || OGG_MAGIC.some((b, i) => v[i] !== b)) {
    throw new BridgeError('invalid_message', 'custom audio must be an Ogg Vorbis file (it starts with OggS)');
  }
  return { audio: new BlobData(v) };
}

function soundData(pos: Vec3d, content: SoundContent, o: SoundEmitterOptions, plays: number): KindPayloads['core:sound-emitter'] {
  const d: KindPayloads['core:sound-emitter'] = { pos };
  if ('sound' in content) {
    d.sound = content.sound;
  } else {
    d.blob = content.audio.hash;
    d.size = content.audio.payload.length;
  }
  if (o.group !== undefined) d.group = o.group;
  if (o.positional === false) d.positional = false;
  if (o.volume !== undefined) d.volume = finite('sound volume', o.volume);
  if (o.pitch !== undefined) d.pitch = finite('sound pitch', o.pitch);
  if (o.loop === false) d.loop = false;
  if (o.interval !== undefined) d.intervalMs = nonNegativeInt('sound interval', o.interval);
  if (o.category !== undefined) d.category = soundCategory(o.category);
  if (plays > 0) d.plays = plays;
  return d;
}

/** A `core:sound-emitter`. */
export class SoundEmitterImpl extends Placed<'core:sound-emitter', SoundEmitterOptions, SoundContent> implements SoundEmitter {
  /** Bumped by {@link play}, so a replay always changes the object and reaches the mod. */
  private plays = 0;

  protected encode(pos: Vec3d, content: SoundContent, o: SoundEmitterOptions): KindPayloads['core:sound-emitter'] {
    return soundData(pos, content, o, this.plays);
  }

  protected override interpolates(): boolean {
    return false;
  }

  protected override blobOf(content: SoundContent): BlobData | undefined {
    return 'audio' in content ? content.audio : undefined;
  }

  protected override contentField(): { field: string; from: (v: unknown) => SoundContent } {
    return { field: 'sound', from: (v) => soundContent(v as string) };
  }

  update(patch: SoundEmitterUpdate): void {
    const v = patch.sound;
    this.applyUpdate(patch, v === undefined || v === null ? this.content : soundContent(v), ['sound']);
  }

  play(): void {
    if (!this.alive) return;
    this.plays++;
    this.redeclare(this.pos, this.content, this.opts);
  }
}
