import { describe, expect, it } from 'vitest';
import { boxContains, bresenham, closestOnRoute, distance, distanceSq, distanceToRoute, distanceToZone, horizontalDistance, routeBlocks, routeIntersects, routeLength, routePoints, toBlockBox, zoneBounds, zoneBoxes, zoneContains, zoneIntersects, type ZoneGeometry } from '../src/index.ts';

const v = (x: number, y: number, z: number) => ({ x, y, z });
const box = (a: [number, number, number], b: [number, number, number], op = 'add') => ({ min: v(...a), max: v(...b), op });
const volume = (z: ZoneGeometry) => zoneBoxes(z).reduce((n, b) => n + (b.max.x - b.min.x + 1) * (b.max.y - b.min.y + 1) * (b.max.z - b.min.z + 1), 0);
const nodes = (...ps: [number, number, number][]) => ps.map((p) => ({ pos: v(...p) }));

describe('zone geometry', () => {
  const holed: ZoneGeometry = { boxes: [box([0, 0, 0], [9, 9, 9]), box([3, 3, 3], [5, 5, 5], 'subtract')] };

  it('applies box ops in list order with inclusive, floored block coordinates', () => {
    expect(zoneContains(holed, [1, 1, 1])).toBe(true);
    expect(zoneContains(holed, [4, 4, 4])).toBe(false);
    expect(zoneContains(holed, [9.99, 0, 0])).toBe(true);
    expect(zoneContains(holed, [10, 0, 0])).toBe(false);
    expect(zoneContains(holed, [-0.01, 0, 0])).toBe(false);
    const subtractFirst: ZoneGeometry = { boxes: [box([3, 3, 3], [5, 5, 5], 'subtract'), box([0, 0, 0], [9, 9, 9])] };
    expect(zoneContains(subtractFirst, [4, 4, 4])).toBe(true);
    const intersected: ZoneGeometry = { boxes: [box([0, 0, 0], [4, 0, 0]), box([6, 0, 0], [9, 0, 0]), box([3, 0, 0], [7, 0, 0], 'intersect')] };
    expect([2, 3, 4, 5, 6, 7, 8].map((x) => zoneContains(intersected, [x, 0, 0]))).toEqual([false, true, true, false, true, true, false]);
    const reversed: ZoneGeometry = { boxes: [box([9, 9, 9], [0, 0, 0])] };
    expect(zoneContains(reversed, [5, 5, 5])).toBe(true);
    expect(zoneContains({ boxes: [box([0, 0, 0], [1, 1, 1], 'xor')] }, [0, 0, 0])).toBe(false);
  });

  it('decomposes box sets into disjoint boxes with exact bounds', () => {
    expect(volume(holed)).toBe(1000 - 27);
    expect(volume({ boxes: [box([0, 0, 0], [3, 3, 3]), box([2, 2, 2], [5, 5, 5])] })).toBe(64 + 64 - 8);
    expect(zoneBounds(holed)).toEqual({ min: v(0, 0, 0), max: v(9, 9, 9) });
    const two = { boxes: [box([0, 0, 0], [3, 3, 3]), box([10, 0, 0], [12, 3, 3])] };
    expect(zoneBounds(two)).toEqual({ min: v(0, 0, 0), max: v(12, 3, 3) });
    expect(zoneBounds({ boxes: [...two.boxes, box([10, 0, 0], [12, 3, 3], 'subtract')] })).toEqual({ min: v(0, 0, 0), max: v(3, 3, 3) });
    expect(zoneBounds({ boxes: [box([0, 0, 0], [3, 3, 3]), box([5, 5, 5], [6, 6, 6], 'intersect')] })).toBeUndefined();
    expect(zoneBounds({})).toBeUndefined();
  });

  it('tests polygon prisms with closed edges and an inclusive y range', () => {
    const tri: ZoneGeometry = { polygon: [v(0, 0, 0), v(10, 0, 0), v(0, 0, 10)], minY: 0, maxY: 5 };
    expect(zoneContains(tri, [1, 0, 1])).toBe(true);
    expect(zoneContains(tri, [5, 5.9, 5])).toBe(true);
    expect(zoneContains(tri, [6, 0, 6])).toBe(false);
    expect(zoneContains(tri, [1, 6, 1])).toBe(false);
    expect(zoneContains(tri, [0, -1, 0])).toBe(false);
    expect(zoneBounds(tri)).toEqual({ min: v(0, 0, 0), max: v(10, 5, 10) });
    const unbounded: ZoneGeometry = { polygon: tri.polygon };
    expect(zoneContains(unbounded, [1, -500, 1])).toBe(true);
    expect(zoneBounds(unbounded)?.max.y).toBe(Infinity);
    expect(zoneContains({ polygon: [v(0, 0, 0), v(1, 0, 1)] }, [0, 0, 0])).toBe(false);
  });

  it('intersects boxes exactly for box sets and by area for polygons', () => {
    expect(zoneIntersects(holed, [[4, 4, 4], [5, 5, 5]])).toBe(false);
    expect(zoneIntersects(holed, { min: [4, 4, 4], max: [6, 4, 4] })).toBe(true);
    expect(zoneIntersects(holed, [[20, 0, 0], [30, 9, 9]])).toBe(false);
    const tri: ZoneGeometry = { polygon: [v(0, 0, 0), v(10, 0, 0), v(0, 0, 10)], minY: 0, maxY: 5 };
    expect(zoneIntersects(tri, [[-5, 0, -5], [20, 3, 20]])).toBe(true);
    expect(zoneIntersects(tri, [[2, 0, 2], [3, 0, 3]])).toBe(true);
    expect(zoneIntersects(tri, [[8, 0, 8], [12, 0, 12]])).toBe(false);
    expect(zoneIntersects(tri, [[1, 6, 1], [2, 9, 2]])).toBe(false);
    expect(zoneIntersects(tri, [[4, 0, -3], [5, 0, 20]])).toBe(true);
  });

  it('measures distance to box faces and to polygon edges', () => {
    const unit: ZoneGeometry = { boxes: [box([0, 0, 0], [0, 0, 0])] };
    expect(distanceToZone(unit, [3, 0.5, 0.5])).toBe(2);
    expect(distanceToZone(unit, [0.5, 0.5, 0.5])).toBe(0);
    expect(distanceToZone(holed, [4.5, 4.5, 4.5])).toBe(1.5);
    const square: ZoneGeometry = { polygon: [v(0, 0, 0), v(10, 0, 0), v(10, 0, 10), v(0, 0, 10)], minY: 0, maxY: 0 };
    expect(distanceToZone(square, [5, 0, 5])).toBe(0);
    expect(distanceToZone(square, [20.5, 0, 5])).toBe(10);
    expect(distanceToZone(square, [5, 4, 5])).toBe(3);
    expect(distanceToZone({}, [0, 0, 0])).toBe(Infinity);
  });

  it('normalises boxes and points', () => {
    expect(toBlockBox([[3.7, -0.2, 9], [1, 5, 2]])).toEqual({ min: v(1, -1, 2), max: v(3, 5, 9) });
    expect(boxContains([[0, 0, 0], [1, 1, 1]], [1.9, 0, 0])).toBe(true);
    expect(() => toBlockBox([[Number.NaN, 0, 0], [1, 1, 1]])).toThrow(/finite/);
    expect(distance([0, 0, 0], [3, 4, 0])).toBe(5);
    expect(distanceSq(v(1, 1, 1), [2, 2, 2])).toBe(3);
    expect(horizontalDistance([0, 100, 0], [3, 0, 4])).toBe(5);
  });
});

describe('route geometry', () => {
  it('rasterises 3D Bresenham lines one block per step along the dominant axis', () => {
    const line = bresenham([0, 0, 0], [5, 2, -1]);
    expect(line).toHaveLength(6);
    expect(line[0]).toEqual(v(0, 0, 0));
    expect(line[5]).toEqual(v(5, 2, -1));
    for (let i = 1; i < line.length; i++) {
      const a = line[i - 1]!;
      const b = line[i]!;
      expect(Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y), Math.abs(a.z - b.z))).toBe(1);
    }
    expect(bresenham([0, 0, 0], [0, -3, 1]).map((p) => p.y)).toEqual([0, -1, -2, -3]);
    expect(bresenham([0.5, 0.5, 0.5], [0.9, 0.1, 0.2])).toEqual([v(0, 0, 0)]);
  });

  it('interpolates linear, Catmull-Rom and Bezier routes as polylines', () => {
    const square = nodes([0, 0, 0], [10, 0, 0], [10, 0, 10], [0, 0, 10]);
    expect(routePoints({ nodes: square })).toHaveLength(4);
    expect(routePoints({ nodes: square, looped: true }).at(-1)).toEqual(v(0, 0, 0));
    expect(routeLength({ nodes: square, looped: true })).toBe(40);

    const cr = routePoints({ nodes: square, interpolation: 'catmull_rom' }, { segments: 4 });
    expect(cr).toHaveLength(3 * 4 + 1);
    for (let i = 0; i < 4; i++) expect(cr[i * 4]).toEqual(square[i]!.pos);
    const crLoop = routePoints({ nodes: square, interpolation: 'catmull-rom', looped: true }, { segments: 4 });
    expect(crLoop).toHaveLength(4 * 4 + 1);
    expect(crLoop.at(-1)).toEqual(v(0, 0, 0));

    const bz = routePoints({ nodes: nodes([0, 0, 0], [10, 0, 10], [20, 0, 0]), interpolation: 'bezier' }, { segments: 2 });
    expect(bz).toHaveLength(5);
    expect(bz[0]).toEqual(v(0, 0, 0));
    expect(bz[2]).toEqual(v(10, 0, 5));
    expect(bz[4]).toEqual(v(20, 0, 0));
    expect(routePoints({ nodes: nodes([1, 2, 3]), interpolation: 'bezier' })).toEqual([v(1, 2, 3)]);
    expect(routePoints({ nodes: [] })).toEqual([]);
    expect(() => routePoints({ nodes: square }, { segments: 0 })).toThrow(/segments/);
  });

  it('gives bresenham routes as connected block positions without repeating the start of a loop', () => {
    const route = { nodes: nodes([0, 0, 0], [3, 0, 0], [3, 0, 3]), interpolation: 'bresenham', looped: true };
    const blocks = routePoints(route);
    expect(blocks[0]).toEqual(v(0, 0, 0));
    expect(blocks).toContainEqual(v(3, 0, 3));
    expect(blocks.at(-1)).not.toEqual(v(0, 0, 0));
    expect(new Set(blocks.map((b) => `${b.x},${b.y},${b.z}`)).size).toBe(blocks.length);
    expect(blocks.every((b) => Number.isInteger(b.x) && Number.isInteger(b.z))).toBe(true);
    const smooth = routeBlocks({ nodes: nodes([0, 0, 0], [8, 0, 8], [16, 0, 0]), interpolation: 'catmull_rom' });
    for (let i = 1; i < smooth.length; i++) {
      const a = smooth[i - 1]!;
      const b = smooth[i]!;
      expect(Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y), Math.abs(a.z - b.z))).toBe(1);
    }
  });

  it('finds the closest point and tests boxes against the polyline', () => {
    const route = { nodes: nodes([0, 64, 0], [10, 64, 0]) };
    expect(closestOnRoute(route, [4, 64, 3])).toEqual({ point: v(4, 64, 0), distance: 3, segment: 0 });
    expect(distanceToRoute(route, [-4, 64, 3])).toBe(5);
    expect(distanceToRoute({ nodes: [] }, [0, 0, 0])).toBe(Infinity);
    expect(routeIntersects(route, [[5, 60, -1], [6, 70, 1]])).toBe(true);
    expect(routeIntersects(route, [[5, 60, 2], [6, 70, 3]])).toBe(false);
    expect(routeIntersects({ nodes: nodes([5, 5, 5]) }, [[5, 5, 5], [5, 5, 5]])).toBe(true);
  });
});
