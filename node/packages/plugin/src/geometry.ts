// Game-free geometry for spatial definitions. Every coordinate is in
// blocks, in world space. Zones are block-based: a position is inside a zone when the block that
// contains it (`floor` of each axis) is inside.
import { BridgeError, type Vec3d, type Vec3i, type ZoneBox } from '@mineflayer-bridge/protocol';
import type { Vec3Like } from './types.ts';
import { toVec3 } from './util.ts';

/** How a zone box combines with the boxes before it, applied in list order. */
export type ZoneOp = 'add' | 'subtract' | 'intersect';

/** How a route connects its nodes. */
export type RouteInterpolation = 'linear' | 'bresenham' | 'catmull_rom' | 'bezier';

/** An axis-aligned box of whole blocks; `min` and `max` are both inside (inclusive). */
export interface BlockBox {
  readonly min: Vec3i;
  readonly max: Vec3i;
}

/**
 * A box given loosely: `{ min, max }` or a `[corner, corner]` pair. Corners may be in any order and
 * are floored to block positions; both corner blocks are inside.
 */
export type BoxLike = { readonly min: Vec3Like; readonly max: Vec3Like } | readonly [Vec3Like, Vec3Like];

/** The geometric fields of `core:zone` data. */
export interface ZoneGeometry {
  readonly boxes?: readonly ZoneBox[] | undefined;
  /** Prism vertices; only `x` and `z` are used. */
  readonly polygon?: readonly Vec3i[] | undefined;
  /** Lowest block layer of a polygon prism, inclusive. Absent means unbounded. */
  readonly minY?: number | undefined;
  /** Highest block layer of a polygon prism, inclusive. Absent means unbounded. */
  readonly maxY?: number | undefined;
}

/** The geometric fields of `core:route` data. */
export interface RouteGeometry {
  readonly nodes: readonly { readonly pos: Vec3d }[];
  readonly looped?: boolean | undefined;
  /** One of {@link RouteInterpolation}; anything else is treated as `linear`. */
  readonly interpolation?: string | undefined;
}

/** Sampling of curved routes. */
export interface RouteSampleOptions {
  /** Samples per span between two nodes for `catmull_rom` and `bezier`, 1 to 1024. Default 8. */
  segments?: number;
}

/** The point of a route nearest to a position. */
export interface RouteClosest {
  readonly point: Vec3d;
  /** Distance from the queried position, in blocks. */
  readonly distance: number;
  /** Index of the polyline segment of {@link routePoints} that holds `point`. */
  readonly segment: number;
}

const DEFAULT_SEGMENTS = 8;
const MAX_SEGMENTS = 1024;

function vi(x: number, y: number, z: number): Vec3i {
  return { x, y, z };
}

function blockOf(v: Vec3Like, what: string): Vec3i {
  const p = toVec3(v, what);
  return vi(Math.floor(p.x), Math.floor(p.y), Math.floor(p.z));
}

function ordered(a: Vec3i, b: Vec3i): BlockBox {
  return { min: vi(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z)), max: vi(Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z)) };
}

/**
 * Normalises a {@link BoxLike} to a {@link BlockBox}: corners floored and sorted per axis.
 * @throws BridgeError `invalid_message` when a corner is not three finite numbers.
 */
export function toBlockBox(box: BoxLike): BlockBox {
  if (Array.isArray(box)) {
    const [a, b] = box as readonly [Vec3Like, Vec3Like];
    return ordered(blockOf(a, 'box corner'), blockOf(b, 'box corner'));
  }
  const o = box as { readonly min: Vec3Like; readonly max: Vec3Like };
  return ordered(blockOf(o.min, 'box min'), blockOf(o.max, 'box max'));
}

function overlap(a: BlockBox, b: BlockBox): BlockBox | undefined {
  const min = vi(Math.max(a.min.x, b.min.x), Math.max(a.min.y, b.min.y), Math.max(a.min.z, b.min.z));
  const max = vi(Math.min(a.max.x, b.max.x), Math.min(a.max.y, b.max.y), Math.min(a.max.z, b.max.z));
  return min.x > max.x || min.y > max.y || min.z > max.z ? undefined : { min, max };
}

/** `a` minus `b` as at most six disjoint boxes: two x slabs, then y slabs and z slabs inside the overlap. */
function subtract(a: BlockBox, b: BlockBox): BlockBox[] {
  const i = overlap(a, b);
  if (i === undefined) return [a];
  const out: BlockBox[] = [];
  const push = (x0: number, y0: number, z0: number, x1: number, y1: number, z1: number): void => {
    out.push({ min: vi(x0, y0, z0), max: vi(x1, y1, z1) });
  };
  if (a.min.x < i.min.x) push(a.min.x, a.min.y, a.min.z, i.min.x - 1, a.max.y, a.max.z);
  if (i.max.x < a.max.x) push(i.max.x + 1, a.min.y, a.min.z, a.max.x, a.max.y, a.max.z);
  if (a.min.y < i.min.y) push(i.min.x, a.min.y, a.min.z, i.max.x, i.min.y - 1, a.max.z);
  if (i.max.y < a.max.y) push(i.min.x, i.max.y + 1, a.min.z, i.max.x, a.max.y, a.max.z);
  if (a.min.z < i.min.z) push(i.min.x, i.min.y, a.min.z, i.max.x, i.max.y, i.min.z - 1);
  if (i.max.z < a.max.z) push(i.min.x, i.min.y, i.max.z + 1, i.max.x, i.max.y, a.max.z);
  return out;
}

function inBox(b: BlockBox, p: Vec3i): boolean {
  return p.x >= b.min.x && p.x <= b.max.x && p.y >= b.min.y && p.y <= b.max.y && p.z >= b.min.z && p.z <= b.max.z;
}

/** Box sets win over a polygon when both are present; a polygon needs three vertices. */
function form(zone: ZoneGeometry): 'boxes' | 'polygon' | 'empty' {
  if (zone.boxes !== undefined && zone.boxes.length > 0) return 'boxes';
  if (zone.polygon !== undefined && zone.polygon.length >= 3) return 'polygon';
  return 'empty';
}

function yRange(zone: ZoneGeometry): { lo: number; hi: number } {
  const lo = zone.minY ?? -Infinity;
  const hi = zone.maxY ?? Infinity;
  return lo <= hi ? { lo, hi } : { lo: hi, hi: lo };
}

/**
 * The block set of a box-set zone as disjoint boxes, after applying every op in order. Boxes with an
 * unknown op are skipped. A polygon or empty zone gives `[]`.
 */
export function zoneBoxes(zone: ZoneGeometry): BlockBox[] {
  if (form(zone) !== 'boxes') return [];
  let region: BlockBox[] = [];
  for (const zb of zone.boxes!) {
    const b = ordered(zb.min, zb.max);
    switch (zb.op ?? 'add') {
      case 'add': {
        let pieces = [b];
        for (const r of region) pieces = pieces.flatMap((p) => subtract(p, r));
        region.push(...pieces);
        break;
      }
      case 'subtract':
        region = region.flatMap((r) => subtract(r, b));
        break;
      case 'intersect':
        region = region.map((r) => overlap(r, b)).filter((r): r is BlockBox => r !== undefined);
        break;
      default:
        break;
    }
  }
  return region;
}

function onSegment(ax: number, az: number, bx: number, bz: number, x: number, z: number): boolean {
  const cross = (bx - ax) * (z - az) - (bz - az) * (x - ax);
  if (cross !== 0) return false;
  return x >= Math.min(ax, bx) && x <= Math.max(ax, bx) && z >= Math.min(az, bz) && z <= Math.max(az, bz);
}

/** Closed point-in-polygon on the x/z plane: points on an edge are inside. */
function inPolygon(poly: readonly Vec3i[], x: number, z: number): boolean {
  let inside = false;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const a = poly[i]!;
    const b = poly[j]!;
    if (onSegment(a.x, a.z, b.x, b.z, x, z)) return true;
    if (a.z > z !== b.z > z && x < ((b.x - a.x) * (z - a.z)) / (b.z - a.z) + a.x) inside = !inside;
  }
  return inside;
}

/**
 * True when the block containing `pos` is inside the zone. Box ops apply in list order; a polygon
 * prism holds the blocks whose `x, z` lie in the closed polygon and whose `y` is in `minY..maxY`.
 * @throws BridgeError `invalid_message` when `pos` is not three finite numbers.
 */
export function zoneContains(zone: ZoneGeometry, pos: Vec3Like): boolean {
  const p = blockOf(pos, 'pos');
  switch (form(zone)) {
    case 'boxes': {
      let inside = false;
      for (const zb of zone.boxes!) {
        const hit = inBox(ordered(zb.min, zb.max), p);
        const op = zb.op ?? 'add';
        if (op === 'add') inside ||= hit;
        else if (op === 'subtract') inside &&= !hit;
        else if (op === 'intersect') inside &&= hit;
      }
      return inside;
    }
    case 'polygon': {
      const { lo, hi } = yRange(zone);
      return p.y >= lo && p.y <= hi && inPolygon(zone.polygon!, p.x, p.z);
    }
    case 'empty':
      return false;
  }
}

/**
 * The smallest block box holding the whole zone, or `undefined` for an empty zone. For a polygon
 * prism without `minY`/`maxY` the y bounds are `-Infinity`/`Infinity`.
 */
export function zoneBounds(zone: ZoneGeometry): BlockBox | undefined {
  switch (form(zone)) {
    case 'boxes': {
      const boxes = zoneBoxes(zone);
      if (boxes.length === 0) return undefined;
      let { min, max } = boxes[0]!;
      for (const b of boxes) {
        min = vi(Math.min(min.x, b.min.x), Math.min(min.y, b.min.y), Math.min(min.z, b.min.z));
        max = vi(Math.max(max.x, b.max.x), Math.max(max.y, b.max.y), Math.max(max.z, b.max.z));
      }
      return { min, max };
    }
    case 'polygon': {
      const poly = zone.polygon!;
      const { lo, hi } = yRange(zone);
      const xs = poly.map((v) => v.x);
      const zs = poly.map((v) => v.z);
      return { min: vi(Math.min(...xs), lo, Math.min(...zs)), max: vi(Math.max(...xs), hi, Math.max(...zs)) };
    }
    case 'empty':
      return undefined;
  }
}

function segmentsCross(ax: number, az: number, bx: number, bz: number, cx: number, cz: number, dx: number, dz: number): boolean {
  const d1 = (dx - cx) * (az - cz) - (dz - cz) * (ax - cx);
  const d2 = (dx - cx) * (bz - cz) - (dz - cz) * (bx - cx);
  const d3 = (bx - ax) * (cz - az) - (bz - az) * (cx - ax);
  const d4 = (bx - ax) * (dz - az) - (bz - az) * (dx - ax);
  if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true;
  return onSegment(cx, cz, dx, dz, ax, az) || onSegment(cx, cz, dx, dz, bx, bz) || onSegment(ax, az, bx, bz, cx, cz) || onSegment(ax, az, bx, bz, dx, dz);
}

function polygonMeetsRect(poly: readonly Vec3i[], r: BlockBox): boolean {
  if (poly.some((v) => v.x >= r.min.x && v.x <= r.max.x && v.z >= r.min.z && v.z <= r.max.z)) return true;
  const corners: [number, number][] = [
    [r.min.x, r.min.z],
    [r.max.x, r.min.z],
    [r.max.x, r.max.z],
    [r.min.x, r.max.z],
  ];
  if (corners.some(([x, z]) => inPolygon(poly, x, z))) return true;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const a = poly[i]!;
    const b = poly[j]!;
    for (let k = 0; k < 4; k++) {
      const [cx, cz] = corners[k]!;
      const [dx, dz] = corners[(k + 1) % 4]!;
      if (segmentsCross(a.x, a.z, b.x, b.z, cx, cz, dx, dz)) return true;
    }
  }
  return false;
}

/**
 * True when the zone and `box` share at least one block. Exact for box sets (the op result is
 * decomposed into disjoint boxes); for a polygon prism the closed polygon is tested against the box
 * in block coordinates.
 * @throws BridgeError `invalid_message` when a corner of `box` is not three finite numbers.
 */
export function zoneIntersects(zone: ZoneGeometry, box: BoxLike): boolean {
  const b = toBlockBox(box);
  switch (form(zone)) {
    case 'boxes':
      return zoneBoxes(zone).some((r) => overlap(r, b) !== undefined);
    case 'polygon': {
      const { lo, hi } = yRange(zone);
      return b.max.y >= lo && b.min.y <= hi && polygonMeetsRect(zone.polygon!, b);
    }
    case 'empty':
      return false;
  }
}

/** Distance from `p` to the world-space box `[min, max + 1)` of a block box. */
function boxDistance(b: BlockBox, p: Vec3d): number {
  const dx = Math.max(b.min.x - p.x, 0, p.x - (b.max.x + 1));
  const dy = Math.max(b.min.y - p.y, 0, p.y - (b.max.y + 1));
  const dz = Math.max(b.min.z - p.z, 0, p.z - (b.max.z + 1));
  return Math.hypot(dx, dy, dz);
}

function segmentDistance2d(ax: number, az: number, bx: number, bz: number, x: number, z: number): number {
  const vx = bx - ax;
  const vz = bz - az;
  const len = vx * vx + vz * vz;
  const t = len === 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * vx + (z - az) * vz) / len));
  return Math.hypot(ax + t * vx - x, az + t * vz - z);
}

/**
 * Distance in blocks from `pos` to the nearest part of the zone; 0 inside. Box sets measure to the
 * world-space faces of their blocks. Polygon prisms measure horizontally to the polygon drawn through
 * block centres, so they are accurate to half a block. An empty zone is infinitely far away.
 * @throws BridgeError `invalid_message` when `pos` is not three finite numbers.
 */
export function distanceToZone(zone: ZoneGeometry, pos: Vec3Like): number {
  const p = toVec3(pos, 'pos');
  switch (form(zone)) {
    case 'boxes': {
      let best = Infinity;
      for (const b of zoneBoxes(zone)) best = Math.min(best, boxDistance(b, p));
      return best;
    }
    case 'polygon': {
      if (zoneContains(zone, p)) return 0;
      const poly = zone.polygon!;
      const { lo, hi } = yRange(zone);
      const dy = Math.max(lo - p.y, 0, p.y - (hi + 1));
      let dh = 0;
      if (!inPolygon(poly, Math.floor(p.x), Math.floor(p.z))) {
        dh = Infinity;
        for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
          const a = poly[i]!;
          const b = poly[j]!;
          dh = Math.min(dh, segmentDistance2d(a.x + 0.5, a.z + 0.5, b.x + 0.5, b.z + 0.5, p.x, p.z));
        }
      }
      return Math.hypot(dh, dy);
    }
    case 'empty':
      return Infinity;
  }
}

/** Squared straight-line distance in blocks squared. @throws BridgeError `invalid_message` on a non-finite vector. */
export function distanceSq(a: Vec3Like, b: Vec3Like): number {
  const p = toVec3(a, 'a');
  const q = toVec3(b, 'b');
  return (p.x - q.x) ** 2 + (p.y - q.y) ** 2 + (p.z - q.z) ** 2;
}

/** Straight-line distance in blocks. @throws BridgeError `invalid_message` on a non-finite vector. */
export function distance(a: Vec3Like, b: Vec3Like): number {
  return Math.sqrt(distanceSq(a, b));
}

/** Distance on the x/z plane in blocks, ignoring height. @throws BridgeError `invalid_message` on a non-finite vector. */
export function horizontalDistance(a: Vec3Like, b: Vec3Like): number {
  const p = toVec3(a, 'a');
  const q = toVec3(b, 'b');
  return Math.hypot(p.x - q.x, p.z - q.z);
}

/**
 * Every block on the 3D Bresenham line from the block of `a` to the block of `b`, both included,
 * each step moving one block along the dominant axis.
 * @throws BridgeError `invalid_message` when an end is not three finite numbers.
 */
export function bresenham(a: Vec3Like, b: Vec3Like): Vec3i[] {
  const from = blockOf(a, 'from');
  const to = blockOf(b, 'to');
  const d = [Math.abs(to.x - from.x), Math.abs(to.y - from.y), Math.abs(to.z - from.z)] as const;
  const s = [Math.sign(to.x - from.x), Math.sign(to.y - from.y), Math.sign(to.z - from.z)] as const;
  const p = [from.x, from.y, from.z];
  const major = d[0] >= d[1] && d[0] >= d[2] ? 0 : d[1] >= d[2] ? 1 : 2;
  const minorA = (major + 1) % 3;
  const minorB = (major + 2) % 3;
  const out: Vec3i[] = [vi(p[0]!, p[1]!, p[2]!)];
  let errA = 2 * d[minorA]! - d[major]!;
  let errB = 2 * d[minorB]! - d[major]!;
  for (let i = 0; i < d[major]!; i++) {
    p[major]! += s[major]!;
    if (errA >= 0) {
      p[minorA]! += s[minorA]!;
      errA -= 2 * d[major]!;
    }
    if (errB >= 0) {
      p[minorB]! += s[minorB]!;
      errB -= 2 * d[major]!;
    }
    errA += 2 * d[minorA]!;
    errB += 2 * d[minorB]!;
    out.push(vi(p[0]!, p[1]!, p[2]!));
  }
  return out;
}

function segmentsOf(o: RouteSampleOptions): number {
  const n = o.segments ?? DEFAULT_SEGMENTS;
  if (!Number.isInteger(n) || n < 1 || n > MAX_SEGMENTS) throw new BridgeError('invalid_message', `segments must be an integer from 1 to ${MAX_SEGMENTS}`);
  return n;
}

function catmullRom(p0: Vec3d, p1: Vec3d, p2: Vec3d, p3: Vec3d, t: number): Vec3d {
  const t2 = t * t;
  const t3 = t2 * t;
  const axis = (a: number, b: number, c: number, d: number): number => 0.5 * (2 * b + (c - a) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (3 * b - a - 3 * c + d) * t3);
  return { x: axis(p0.x, p1.x, p2.x, p3.x), y: axis(p0.y, p1.y, p2.y, p3.y), z: axis(p0.z, p1.z, p2.z, p3.z) };
}

function deCasteljau(control: readonly Vec3d[], t: number): Vec3d {
  const work = control.map((c) => ({ ...c }));
  for (let n = work.length - 1; n > 0; n--) {
    for (let i = 0; i < n; i++) {
      const a = work[i]!;
      const b = work[i + 1]!;
      work[i] = { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t, z: a.z + (b.z - a.z) * t };
    }
  }
  return work[0]!;
}

function asInterpolation(v: string | undefined): RouteInterpolation {
  // The public name is `catmull-rom`; the schema uses `catmull_rom`. Both are read.
  if (v === 'catmull-rom') return 'catmull_rom';
  return v === 'bresenham' || v === 'catmull_rom' || v === 'bezier' ? v : 'linear';
}

/**
 * The blocks a route passes through. `bresenham` routes rasterise the straight lines between node
 * blocks; other modes rasterise their {@link routePoints} polyline. Consecutive duplicates are
 * dropped, and a looped route does not repeat its first block at the end.
 * @throws BridgeError `invalid_message` when `segments` is out of range.
 */
export function routeBlocks(route: RouteGeometry, options: RouteSampleOptions = {}): Vec3i[] {
  const line = asInterpolation(route.interpolation) === 'bresenham' ? closedNodes(route) : routePoints(route, options);
  if (line.length === 0) return [];
  const out: Vec3i[] = [blockOf(line[0]!, 'route point')];
  for (let i = 1; i < line.length; i++) {
    for (const b of bresenham(line[i - 1]!, line[i]!)) {
      const last = out[out.length - 1]!;
      if (b.x !== last.x || b.y !== last.y || b.z !== last.z) out.push(b);
    }
  }
  const first = out[0]!;
  const last = out[out.length - 1]!;
  if (route.looped === true && out.length > 1 && first.x === last.x && first.y === last.y && first.z === last.z) out.pop();
  return out;
}

/** Node positions, with the first repeated at the end of a looped route of two or more nodes. */
function closedNodes(route: RouteGeometry): Vec3d[] {
  const pts = route.nodes.map((n) => ({ x: n.pos.x, y: n.pos.y, z: n.pos.z }));
  if (route.looped === true && pts.length > 1) pts.push({ ...pts[0]! });
  return pts;
}

/**
 * The route as a polyline, in blocks:
 * - `linear`: the node positions;
 * - `catmull_rom`: a uniform Catmull-Rom spline through every node, `segments` samples per span;
 * - `bezier`: one Bezier curve with the nodes as control points (it passes through the first and
 *   last node), `segments` samples per span between control points;
 * - `bresenham`: the block positions of {@link routeBlocks}.
 *
 * A looped route ends where it started (except `bresenham`, which never repeats a block).
 * @throws BridgeError `invalid_message` when `segments` is out of range.
 */
export function routePoints(route: RouteGeometry, options: RouteSampleOptions = {}): Vec3d[] {
  const mode = asInterpolation(route.interpolation);
  const segments = segmentsOf(options);
  if (mode === 'bresenham') return routeBlocks(route, options);
  const nodes = route.nodes.map((n) => n.pos);
  if (mode === 'linear' || nodes.length < 2) return closedNodes(route);
  const looped = route.looped === true;
  if (mode === 'bezier') {
    const control = closedNodes(route);
    const total = segments * (control.length - 1);
    const out: Vec3d[] = [];
    for (let k = 0; k <= total; k++) out.push(deCasteljau(control, k / total));
    return out;
  }
  const n = nodes.length;
  const at = (i: number): Vec3d => (looped ? nodes[((i % n) + n) % n]! : nodes[Math.max(0, Math.min(n - 1, i))]!);
  const spans = looped ? n : n - 1;
  const out: Vec3d[] = [];
  for (let i = 0; i < spans; i++) {
    for (let k = 0; k < segments; k++) out.push(catmullRom(at(i - 1), at(i), at(i + 1), at(i + 2), k / segments));
  }
  const end = looped ? nodes[0]! : nodes[n - 1]!;
  out.push({ x: end.x, y: end.y, z: end.z });
  return out;
}

/** Length of the {@link routePoints} polyline in blocks. @throws BridgeError `invalid_message` when `segments` is out of range. */
export function routeLength(route: RouteGeometry, options: RouteSampleOptions = {}): number {
  const pts = routePoints(route, options);
  let len = 0;
  for (let i = 1; i < pts.length; i++) len += distance(pts[i - 1]!, pts[i]!);
  return len;
}

/**
 * The point of the {@link routePoints} polyline nearest to `pos`, or `undefined` for a route
 * without nodes. A single node is its own nearest point (segment 0).
 * @throws BridgeError `invalid_message` when `pos` is not finite or `segments` is out of range.
 */
export function closestOnRoute(route: RouteGeometry, pos: Vec3Like, options: RouteSampleOptions = {}): RouteClosest | undefined {
  const p = toVec3(pos, 'pos');
  const pts = routePoints(route, options);
  if (pts.length === 0) return undefined;
  if (pts.length === 1) return { point: pts[0]!, distance: distance(pts[0]!, p), segment: 0 };
  let best: RouteClosest | undefined;
  for (let i = 1; i < pts.length; i++) {
    const a = pts[i - 1]!;
    const b = pts[i]!;
    const v = { x: b.x - a.x, y: b.y - a.y, z: b.z - a.z };
    const len = v.x * v.x + v.y * v.y + v.z * v.z;
    const t = len === 0 ? 0 : Math.max(0, Math.min(1, ((p.x - a.x) * v.x + (p.y - a.y) * v.y + (p.z - a.z) * v.z) / len));
    const q = { x: a.x + v.x * t, y: a.y + v.y * t, z: a.z + v.z * t };
    const d = distance(q, p);
    if (best === undefined || d < best.distance) best = { point: q, distance: d, segment: i - 1 };
  }
  return best;
}

/** Distance from `pos` to a route's polyline in blocks; infinite for a route without nodes. */
export function distanceToRoute(route: RouteGeometry, pos: Vec3Like, options: RouteSampleOptions = {}): number {
  return closestOnRoute(route, pos, options)?.distance ?? Infinity;
}

/**
 * True when part of the route's polyline lies in `box` (its world-space extent `[min, max + 1)`).
 * @throws BridgeError `invalid_message` on a bad box or `segments`.
 */
export function routeIntersects(route: RouteGeometry, box: BoxLike, options: RouteSampleOptions = {}): boolean {
  const b = toBlockBox(box);
  const lo = [b.min.x, b.min.y, b.min.z];
  const hi = [b.max.x + 1, b.max.y + 1, b.max.z + 1];
  const pts = routePoints(route, options);
  if (pts.length === 1) return inBox(b, blockOf(pts[0]!, 'route point'));
  for (let i = 1; i < pts.length; i++) {
    const a = [pts[i - 1]!.x, pts[i - 1]!.y, pts[i - 1]!.z];
    const c = [pts[i]!.x, pts[i]!.y, pts[i]!.z];
    // Slab test of the segment against the closed box.
    let t0 = 0;
    let t1 = 1;
    let hit = true;
    for (let k = 0; k < 3 && hit; k++) {
      const d = c[k]! - a[k]!;
      if (d === 0) {
        if (a[k]! < lo[k]! || a[k]! > hi[k]!) hit = false;
        continue;
      }
      let ta = (lo[k]! - a[k]!) / d;
      let tb = (hi[k]! - a[k]!) / d;
      if (ta > tb) [ta, tb] = [tb, ta];
      t0 = Math.max(t0, ta);
      t1 = Math.min(t1, tb);
      if (t0 > t1) hit = false;
    }
    if (hit) return true;
  }
  return false;
}

/** True when the block containing `pos` is in `box`. @throws BridgeError `invalid_message` on bad input. */
export function boxContains(box: BoxLike, pos: Vec3Like): boolean {
  return inBox(toBlockBox(box), blockOf(pos, 'pos'));
}
