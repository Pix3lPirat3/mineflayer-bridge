import { BridgeError, type KindPayloads } from '@mineflayer-bridge/protocol';
import { LayerHandle, type HandleHost } from './handle.ts';
import type { BoxOptions, EntityTagOptions, LabelOptions, LineOptions, PathOptions, ShapeOptions, Vec3Like, WaypointOptions } from './types.ts';
import { mergePatch, toColor, toVec3 } from './util.ts';

/** Kinds of the simple world-space shapes. */
export type ShapeKind = 'core:box' | 'core:line' | 'core:path' | 'core:label' | 'core:entity-tag' | 'core:waypoint';

/** The full state of each shape: its geometry under the wire field names, plus its options. */
export interface ShapeStates {
  'core:box': BoxOptions & { min: Vec3Like; size: Vec3Like | number };
  'core:line': LineOptions & { from: Vec3Like; to: Vec3Like };
  'core:path': PathOptions & { points: readonly Vec3Like[] | Float64Array };
  'core:label': LabelOptions & { pos: Vec3Like; text: string };
  'core:entity-tag': EntityTagOptions & { entity: number | { readonly id: number } };
  'core:waypoint': WaypointOptions & { pos: Vec3Like; name: string };
}

type Build<K extends ShapeKind> = (s: ShapeStates[K]) => KindPayloads[K];

function flatPoints(points: readonly Vec3Like[] | Float64Array): Float64Array {
  if (points instanceof Float64Array) {
    if (points.length % 3 !== 0) throw new BridgeError('invalid_message', 'flat path points must be x, y, z triples');
    return points;
  }
  if (!Array.isArray(points)) throw new BridgeError('invalid_message', 'path points must be a list of positions or flat x, y, z triples');
  const flat = new Float64Array(points.length * 3);
  points.forEach((p, i) => {
    const v = toVec3(p, 'path point');
    flat[i * 3] = v.x;
    flat[i * 3 + 1] = v.y;
    flat[i * 3 + 2] = v.z;
  });
  return flat;
}

/** Wire data of each shape kind without its style reference, which the layer resolves. */
export const shapeBuilders: { readonly [K in ShapeKind]: Build<K> } = {
  'core:box': (s) => ({ min: toVec3(s.min, 'min'), size: typeof s.size === 'number' ? { x: s.size, y: s.size, z: s.size } : toVec3(s.size, 'size') }),
  'core:line': (s) => {
    const d: KindPayloads['core:line'] = { from: toVec3(s.from, 'from'), to: toVec3(s.to, 'to') };
    if (s.arrow !== undefined) d.arrow = s.arrow;
    return d;
  },
  'core:path': (s) => {
    const d: KindPayloads['core:path'] = { points: flatPoints(s.points) };
    if (s.classes !== undefined) d.classes = s.classes instanceof Uint16Array ? s.classes : Uint16Array.from(s.classes);
    if (s.classStyles !== undefined) d.classStyles = Object.fromEntries(Object.entries(s.classStyles).map(([k, v]) => [k, String(v)]));
    return d;
  },
  'core:label': (s) => {
    if (typeof s.text !== 'string') throw new BridgeError('invalid_message', 'label text must be a string');
    const d: KindPayloads['core:label'] = { pos: toVec3(s.pos, 'pos'), text: s.text };
    if (s.scale !== undefined) d.scale = s.scale;
    if (s.background !== undefined) d.background = toColor(s.background);
    if (s.maxDistance !== undefined) d.maxDistance = s.maxDistance;
    return d;
  },
  'core:entity-tag': (s) => {
    const entity = typeof s.entity === 'number' ? s.entity : s.entity.id;
    if (!Number.isInteger(entity)) throw new BridgeError('invalid_message', 'entity id must be an integer');
    const d: KindPayloads['core:entity-tag'] = { entity };
    if (s.text !== undefined) d.text = s.text;
    if (s.outline !== undefined) d.outline = toColor(s.outline);
    if (s.tracer !== undefined) d.tracer = s.tracer;
    return d;
  },
  'core:waypoint': (s) => {
    if (typeof s.name !== 'string') throw new BridgeError('invalid_message', 'waypoint name must be a string');
    const d: KindPayloads['core:waypoint'] = { pos: toVec3(s.pos, 'pos'), name: s.name };
    if (s.beam !== undefined) d.beam = s.beam;
    if (s.edgePin !== undefined) d.edgePin = s.edgePin;
    if (s.tracer !== undefined) d.tracer = s.tracer;
    return d;
  },
};

/** What a shape handle needs from its layer. */
export interface ShapeHost extends HandleHost {
  /** Declares `<layer>/<shapeId>`, resolving the style of `o` and its traits. */
  putShape<K extends ShapeKind>(shapeId: string, kind: K, data: KindPayloads[K], o: ShapeOptions): void;
  /** Removes the shape and its inline style. */
  removeShape(shapeId: string): void;
}

/**
 * A box, line, path, label, entity tag or waypoint. Its whole state is kept and re-encoded on every
 * change; the store turns the difference into a field patch.
 */
export class ShapeImpl<K extends ShapeKind> extends LayerHandle<K, ShapeHost> {
  constructor(
    host: ShapeHost,
    id: string,
    kind: K,
    private state: ShapeStates[K],
  ) {
    super(host, id, kind);
  }

  /** Encodes first, so an option that does not fit throws before any state changes. */
  private commit(s: ShapeStates[K]): void {
    this.host.putShape(this.id, this.kind, shapeBuilders[this.kind](s), s);
    this.state = s;
    this.bound(s);
  }

  declare(): void {
    this.commit(this.state);
  }

  redeclare(s: ShapeStates[K]): void {
    if (this.alive) this.commit(s);
  }

  update(patch: object): void {
    if (this.alive) this.commit(mergePatch(this.state, patch, []));
  }

  protected drop(): void {
    this.host.removeShape(this.id);
  }

  protected editWrite(proposed: KindPayloads[K], fields: readonly string[]): (() => void) | undefined {
    if (fields.includes('style')) return undefined;
    const p = proposed as unknown as Record<string, unknown>;
    const next = mergePatch(this.state, Object.fromEntries(fields.map((f) => [f, p[f] ?? null])), []);
    shapeBuilders[this.kind](next);
    return () => this.commit(next);
  }
}
