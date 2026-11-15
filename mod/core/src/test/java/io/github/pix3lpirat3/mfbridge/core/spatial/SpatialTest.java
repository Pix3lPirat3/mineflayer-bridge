package io.github.pix3lpirat3.mfbridge.core.spatial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.RenderEntry;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.PointData;
import io.github.pix3lpirat3.mfbridge.protocol.core.RouteData;
import io.github.pix3lpirat3.mfbridge.protocol.core.RouteNode;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import io.github.pix3lpirat3.mfbridge.protocol.core.ZoneBox;
import io.github.pix3lpirat3.mfbridge.protocol.core.ZoneData;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SpatialTest {
    static ZoneBox box(int x0, int y0, int z0, int x1, int y1, int z1, String op) {
        return new ZoneBox(new Vec3i(x0, y0, z0), new Vec3i(x1, y1, z1), op);
    }

    static ZoneData zone(String name, List<ZoneBox> boxes) {
        return new ZoneData(name, boxes, null, null, null, List.of("t"), 0xFF00FF00, 0.1f, true, true);
    }

    static ZoneData polygon(String name, List<Vec3i> vertices, int minY, int maxY) {
        return new ZoneData(name, null, vertices, minY, maxY, null, 0xFF00FF00, 0.1f, true, true);
    }

    static RenderEntry entry(String key, KindType<?> kind, Object data) {
        String[] parts = key.split("/", 3);
        return new RenderEntry(key.hashCode(), key, parts[0], parts[1], parts[2], null, kind, 1, data, null, Scope.GLOBAL,
                null);
    }

    @Test
    void subtractLeavesDisjointPiecesWithTheRightVolume() {
        IntBox a = new IntBox(0, 0, 0, 9, 9, 9);
        List<IntBox> rest = a.subtract(new IntBox(3, 3, 3, 5, 5, 5));
        assertEquals(6, rest.size());
        long volume = 0;
        for (int i = 0; i < rest.size(); i++) {
            volume += rest.get(i).volume();
            for (int j = i + 1; j < rest.size(); j++) {
                assertFalse(rest.get(i).intersects(rest.get(j)));
            }
        }
        assertEquals(1000 - 27, volume);
        assertEquals(List.of(a), a.subtract(new IntBox(20, 20, 20, 21, 21, 21)));
        assertTrue(a.subtract(new IntBox(-1, -1, -1, 10, 10, 10)).isEmpty());
    }

    @Test
    void boxOpsApplyInOrder() {
        ZoneShape s = ZoneShape.of(zone("z", List.of(
                box(0, 0, 0, 9, 0, 9, "add"),
                box(5, 0, 0, 14, 0, 9, "add"),
                box(2, 0, 2, 3, 0, 3, "subtract"),
                box(0, 0, 0, 11, 0, 9, "intersect"))));
        ZoneShape.Boxes b = assertInstanceOf(ZoneShape.Boxes.class, s);
        assertEquals(12 * 10 - 4, b.volume());
        assertTrue(s.contains(0, 0, 0));
        assertTrue(s.contains(11, 0, 9));
        assertFalse(s.contains(12, 0, 0), "cut by the intersect");
        assertFalse(s.contains(2, 0, 3), "subtracted");
        assertEquals(new IntBox(0, 0, 0, 11, 0, 9), s.bounds());
        assertTrue(s.contains(11.9, 0.5, 9.99));
        assertFalse(s.contains(-0.01, 0, 0));

        assertSame(ZoneShape.Empty.INSTANCE, ZoneShape.of(zone("e", List.of(box(0, 0, 0, 1, 1, 1, "subtract")))));
        assertSame(ZoneShape.Empty.INSTANCE, ZoneShape.of(zone("e", List.of(
                box(0, 0, 0, 1, 1, 1, "add"), box(5, 5, 5, 6, 6, 6, "intersect")))));
        assertTrue(ZoneShape.of(zone("u", List.of(box(0, 0, 0, 0, 0, 0, "weird")))).contains(0, 0, 0),
                "unknown ops add");
    }

    @Test
    void resolvedBoxesMatchABruteForceRasterisation() {
        Random rnd = new Random(7);
        for (int round = 0; round < 50; round++) {
            List<ZoneBox> boxes = new ArrayList<>();
            String[] ops = {"add", "add", "subtract", "intersect"};
            for (int i = 0; i < 5; i++) {
                int x = rnd.nextInt(8);
                int y = rnd.nextInt(8);
                int z = rnd.nextInt(8);
                boxes.add(box(x, y, z, x + rnd.nextInt(6), y + rnd.nextInt(6), z + rnd.nextInt(6),
                        i == 0 ? "add" : ops[rnd.nextInt(4)]));
            }
            ZoneShape s = ZoneShape.of(zone("r", boxes));
            List<IntBox> parts = s instanceof ZoneShape.Boxes b ? b.parts() : List.of();
            for (int x = -1; x < 15; x++) {
                for (int y = -1; y < 15; y++) {
                    for (int z = -1; z < 15; z++) {
                        boolean in = false;
                        for (ZoneBox zb : boxes) {
                            boolean inBox = IntBox.of(zb.min(), zb.max()).contains(x, y, z);
                            in = switch (zb.op()) {
                                case "subtract" -> in && !inBox;
                                case "intersect" -> in && inBox;
                                default -> in || inBox;
                            };
                        }
                        assertEquals(in, s.contains(x, y, z), "round " + round + " at " + x + " " + y + " " + z);
                        int covering = 0;
                        for (IntBox p : parts) {
                            covering += p.contains(x, y, z) ? 1 : 0;
                        }
                        assertTrue(covering <= 1, "pieces overlap");
                    }
                }
            }
        }
    }

    @Test
    void pointInPolygonCountsTheBoundaryAndHandlesConcaveShapes() {
        int[] xs = {0, 10, 10, 5, 0};
        int[] zs = {0, 0, 10, 5, 10};
        assertTrue(Polygons.contains(xs, zs, 1, 1));
        assertTrue(Polygons.contains(xs, zs, 0, 0), "vertex");
        assertTrue(Polygons.contains(xs, zs, 5, 0), "edge");
        assertTrue(Polygons.contains(xs, zs, 5, 5), "notch vertex");
        assertFalse(Polygons.contains(xs, zs, 5, 7), "inside the notch");
        assertTrue(Polygons.contains(xs, zs, 1, 8));
        assertFalse(Polygons.contains(xs, zs, 11, 5));
        assertFalse(Polygons.contains(xs, zs, -1, 0));
        assertTrue(Polygons.contains(xs, zs, 9.5, 9.0));
        assertFalse(Polygons.contains(xs, zs, 5.0, 8.0));
    }

    @Test
    void prismTestsBlockCentresWithTheEvenOddRule() {
        ZoneShape s = ZoneShape.of(polygon("p", List.of(new Vec3i(0, 64, 0), new Vec3i(4, 64, 0), new Vec3i(0, 64, 4)), 60, 70));
        ZoneShape.Prism p = assertInstanceOf(ZoneShape.Prism.class, s);
        assertEquals(60, p.minY());
        assertTrue(s.contains(0, 60, 0), "centre 0.5, 0.5");
        assertTrue(s.contains(1, 70, 1), "centre 1.5, 1.5");
        assertFalse(s.contains(2, 65, 2), "centre 2.5, 2.5 lies beyond x + z = 4");
        assertFalse(s.contains(4, 65, 0), "the vertex x is the outline, not a block");
        assertFalse(s.contains(1, 71, 1));
        assertTrue(s.contains(1.9, 69.5, 1.2));
        assertEquals(new IntBox(0, 60, 0, 3, 70, 3), s.bounds());
        assertTrue(s.intersects(new IntBox(-5, 50, -5, 0, 60, 0)));
        assertFalse(s.intersects(new IntBox(3, 60, 3, 8, 70, 8)));

        ZoneData open = new ZoneData("q", null, List.of(new Vec3i(0, 5, 0), new Vec3i(3, 9, 0), new Vec3i(0, 7, 3)), null, null,
                null, 0, 0f, true, true);
        ZoneShape q = ZoneShape.of(open);
        assertTrue(q.contains(0, -2000, 0), "no minY means no lower limit");
        assertTrue(q.contains(0, 4000, 0), "no maxY means no upper limit");
        ZoneSurface drawn = ZoneSurface.of(q);
        assertEquals(ZoneSurface.DRAW_MIN_Y, drawn.quads()[1]);
        assertEquals(ZoneSurface.DRAW_MAX_Y + 1d, drawn.quads()[7]);
        assertSame(ZoneShape.Empty.INSTANCE, ZoneShape.of(polygon("line",
                List.of(new Vec3i(0, 0, 0), new Vec3i(1, 0, 1), new Vec3i(2, 0, 2)), 0, 1)));
        ZoneData both = new ZoneData("b", List.of(box(0, 0, 0, 0, 0, 0, "add")),
                List.of(new Vec3i(10, 0, 10), new Vec3i(20, 0, 10), new Vec3i(10, 0, 20)), null, null, null, 0, 0f, true, true);
        assertInstanceOf(ZoneShape.Boxes.class, ZoneShape.of(both), "boxes win over a polygon");
    }

    @Test
    void triangulationCoversTheArea() {
        int[] xs = {0, 10, 10, 5, 0};
        int[] zs = {0, 0, 10, 5, 10};
        int[] tris = Polygons.triangulate(xs, zs);
        assertEquals(9, tris.length);
        long area = 0;
        for (int t = 0; t < tris.length; t += 3) {
            int a = tris[t];
            int b = tris[t + 1];
            int c = tris[t + 2];
            area += Math.abs((long) (xs[b] - xs[a]) * (zs[c] - zs[a]) - (long) (zs[b] - zs[a]) * (xs[c] - xs[a]));
        }
        assertEquals(Math.abs(Polygons.signedArea2(xs, zs)), area);
    }

    @Test
    void surfaceMergesTouchingBoxes() {
        ZoneSurface single = ZoneSurface.of(ZoneShape.of(zone("a", List.of(box(0, 0, 0, 999, 99, 999, "add")))));
        assertEquals(6, single.quadCount());
        assertEquals(12, single.edgeCount());

        ZoneSurface two = ZoneSurface.of(ZoneShape.of(zone("b", List.of(box(0, 0, 0, 4, 4, 4, "add"),
                box(5, 0, 0, 9, 4, 4, "add")))));
        assertEquals(10, two.quadCount(), "the shared face disappears, the outer faces are split once");
        assertEquals(12, two.edgeCount(), "a merged box has the outline of one box");

        ZoneSurface ell = ZoneSurface.of(ZoneShape.of(zone("l", List.of(box(0, 0, 0, 9, 0, 9, "add"),
                box(5, 0, 5, 9, 0, 9, "subtract")))));
        assertEquals(18, ell.edgeCount(), "an L prism has 6 vertical and 12 ring edges");

        ZoneSurface prism = ZoneSurface.of(ZoneShape.of(polygon("p",
                List.of(new Vec3i(0, 0, 0), new Vec3i(4, 0, 0), new Vec3i(4, 0, 4), new Vec3i(0, 0, 4)), 0, 3)));
        assertEquals(4 + 2 * 2, prism.quadCount());
        assertEquals(12, prism.edgeCount());
        assertEquals(0.0, prism.quads()[0], "prism outlines run through the vertices as given");
        assertSame(ZoneSurface.EMPTY, ZoneSurface.of(ZoneShape.Empty.INSTANCE));
    }

    static List<RouteNode> nodes(double... xyz) {
        List<RouteNode> out = new ArrayList<>();
        for (int i = 0; i < xyz.length; i += 3) {
            out.add(new RouteNode(new Vec3d(xyz[i], xyz[i + 1], xyz[i + 2])));
        }
        return out;
    }

    @Test
    void linearAndLoopedPolylines() {
        List<RouteNode> n = nodes(0, 0, 0, 10, 0, 0, 10, 0, 10);
        assertArrayEquals(new double[] {0, 0, 0, 10, 0, 0, 10, 0, 10},
                RouteGeometry.polyline(n, false, RouteGeometry.Mode.LINEAR, 8));
        double[] loop = RouteGeometry.polyline(n, true, RouteGeometry.Mode.LINEAR, 8);
        assertEquals(12, loop.length);
        assertEquals(0, loop[9]);
        assertEquals(RouteGeometry.Mode.CATMULL_ROM, RouteGeometry.Mode.of("catmull-rom"));
        assertEquals(RouteGeometry.Mode.CATMULL_ROM, RouteGeometry.Mode.of("catmull_rom"));
        assertEquals(RouteGeometry.Mode.LINEAR, RouteGeometry.Mode.of("spline?"));
        assertEquals(3, RouteGeometry.polyline(nodes(1, 2, 3), false, RouteGeometry.Mode.BEZIER, 8).length);
    }

    @Test
    void catmullRomPassesThroughEveryNode() {
        List<RouteNode> n = nodes(0, 0, 0, 10, 5, 0, 20, 0, 3, 30, 2, 9);
        double[] p = RouteGeometry.polyline(n, false, RouteGeometry.Mode.CATMULL_ROM, 4);
        assertEquals((3 * 4 + 1) * 3, p.length);
        for (int i = 0; i < 4; i++) {
            Vec3d v = n.get(i).pos();
            int at = i * 4 * 3;
            assertEquals(v.x(), p[at], 1e-9);
            assertEquals(v.y(), p[at + 1], 1e-9);
            assertEquals(v.z(), p[at + 2], 1e-9);
        }
        double[] loop = RouteGeometry.polyline(n, true, RouteGeometry.Mode.CATMULL_ROM, 4);
        assertEquals((4 * 4 + 1) * 3, loop.length);
        assertEquals(0, loop[loop.length - 3], 1e-9);
    }

    @Test
    void bezierIsOneCurveOverAllNodes() {
        List<RouteNode> n = nodes(0, 0, 0, 10, 0, 0, 10, 0, 10, 0, 0, 10);
        double[] p = RouteGeometry.polyline(n, false, RouteGeometry.Mode.BEZIER, 2);
        assertEquals((3 * 2 + 1) * 3, p.length);
        assertEquals(0, p[0]);
        assertEquals(0, p[2]);
        assertEquals(0, p[p.length - 3], 1e-9);
        assertEquals(10, p[p.length - 1], 1e-9);
        // The cubic's midpoint is (P0 + 3 P1 + 3 P2 + P3) / 8.
        assertEquals((0 + 30 + 30 + 0) / 8d, p[3 * 3], 1e-9);
        assertEquals((0 + 0 + 30 + 10) / 8d, p[3 * 3 + 2], 1e-9);
        double[] loop = RouteGeometry.polyline(n, true, RouteGeometry.Mode.BEZIER, 2);
        assertEquals(loop[0], loop[loop.length - 3], 1e-9);
        assertEquals(loop[2], loop[loop.length - 1], 1e-9);
    }

    @Test
    void bresenhamProducesConnectedBlocks() {
        RouteData r = new RouteData("r", nodes(0.5, 64.2, 0.5, 7.9, 66, 3.1, 7, 66, -4), false, "bresenham", null, 0, 2f, true);
        int[] b = RouteGeometry.blocks(r);
        assertEquals(0, b[0]);
        assertEquals(64, b[1]);
        assertEquals(7, b[b.length - 3]);
        assertEquals(-4, b[b.length - 1]);
        for (int i = 3; i < b.length; i += 3) {
            int dx = Math.abs(b[i] - b[i - 3]);
            int dy = Math.abs(b[i + 1] - b[i - 2]);
            int dz = Math.abs(b[i + 2] - b[i - 1]);
            assertTrue(dx <= 1 && dy <= 1 && dz <= 1 && dx + dy + dz > 0, "step " + i / 3);
        }
        assertEquals(8 + 7, b.length / 3, "7 steps along x, then 7 along z sharing the corner block");
        double[] line = RouteGeometry.polyline(r);
        assertEquals(b.length, line.length);
        assertEquals(0.5, line[0]);
    }

    @Test
    void snappingAndNudges() {
        assertEquals(GizmoMath.Snap.BLOCK, GizmoMath.Snap.of(true, false, false));
        assertEquals(GizmoMath.Snap.NONE, GizmoMath.Snap.of(true, true, true));
        assertEquals(GizmoMath.Snap.HALF, GizmoMath.Snap.of(false, false, true));
        assertEquals(GizmoMath.Snap.BLOCK, GizmoMath.Snap.of(true, false, true));
        assertEquals(3.0, GizmoMath.Snap.BLOCK.snap(2.6));
        assertEquals(2.5, GizmoMath.Snap.HALF.snap(2.6));
        assertEquals(2.6, GizmoMath.Snap.NONE.snap(2.6));
        assertEquals(30.0, GizmoMath.Snap.BLOCK.snapAngle(37));
        assertEquals(37.0, GizmoMath.Snap.HALF.snapAngle(37));
        assertEquals(0.5, GizmoMath.Snap.HALF.nudge());
        assertEquals(1.0, GizmoMath.Snap.NONE.nudge());

        assertArrayEquals(new int[] {0, 0, 1}, GizmoMath.nudgeAxis(0, GizmoMath.Nudge.FORWARD));
        assertArrayEquals(new int[] {-1, 0, 0}, GizmoMath.nudgeAxis(0, GizmoMath.Nudge.RIGHT));
        assertArrayEquals(new int[] {-1, 0, 0}, GizmoMath.nudgeAxis(80, GizmoMath.Nudge.FORWARD));
        assertArrayEquals(new int[] {0, 0, -1}, GizmoMath.nudgeAxis(90, GizmoMath.Nudge.RIGHT));
        assertArrayEquals(new int[] {1, 0, 0}, GizmoMath.nudgeAxis(90, GizmoMath.Nudge.BACK));
        assertArrayEquals(new int[] {0, -1, 0}, GizmoMath.nudgeAxis(33, GizmoMath.Nudge.DOWN));
        assertEquals(90, GizmoMath.yawTowards(0, 0, -1, 0), 1e-9);
        assertEquals(0, GizmoMath.yawTowards(0, 0, 0, 5), 1e-9);
        assertEquals(-170, GizmoMath.wrapDegrees(190), 1e-9);
    }

    @Test
    void rayTests() {
        assertEquals(4, GizmoMath.rayBox(0, 0.5, 0.5, 1, 0, 0, 4, 0, 0, 5, 1, 1), 1e-9);
        assertEquals(-1, GizmoMath.rayBox(0, 0.5, 0.5, -1, 0, 0, 4, 0, 0, 5, 1, 1));
        assertEquals(0, GizmoMath.rayBox(4.5, 0.5, 0.5, 1, 0, 0, 4, 0, 0, 5, 1, 1));
        assertEquals(9, GizmoMath.raySphere(0, 0, 0, 0, 0, 1, 0, 0, 10, 1), 1e-9);
        assertEquals(-1, GizmoMath.raySphere(0, 0, 0, 0, 1, 0, 0, 0, 10, 1));
        assertEquals(5, GizmoMath.rayPlane(0, 10, 0, 0, -1, 0, 0, 5, 0, 0, 1, 0), 1e-9);
        // Looking along -z at height 3 from x = 7: the closest point on the x axis through the origin is x = 7.
        assertEquals(7, GizmoMath.closestOnAxis(7, 3, 10, 0, 0, -1, 0, 0, 0, 1, 0, 0), 1e-9);
    }

    @Test
    void indexAnswersQueries() {
        List<RenderEntry> entries = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            entries.add(entry("shared/bridge:zones/z" + i, CoreKinds.ZONE,
                    zone("Zone " + i, List.of(box(i * 20, 0, 0, i * 20 + 9, 9, 9, "add")))));
        }
        entries.add(entry("player/bridge:waypoints/home", CoreKinds.POINT,
                new PointData("Home", new Vec3d(105, 3, 4), 90f, List.of("base"), 0, "minecraft:bed", true, null)));
        entries.add(entry("bot:a@x/bridge:path-tool/r", CoreKinds.ROUTE,
                new RouteData("Patrol", nodes(0, 20, 0, 50, 20, 0), false, "linear", List.of("base"), 0, 2f, true)));
        entries.add(entry("shared/x/label", CoreKinds.LABEL, "not spatial"));
        SpatialIndex idx = SpatialIndex.build(9, entries);
        assertEquals(42, idx.all().size());
        assertEquals(9, idx.version());
        assertEquals(List.of("shared/bridge:zones/z5"), idx.containing(105.5, 3, 4).stream().map(SpatialIndex.Item::key).toList());
        assertTrue(idx.containing(110, 3, 4).isEmpty());
        assertEquals(2, idx.byTag("base").size());
        assertEquals(40, idx.byTag("t").size());
        List<String> hits = idx.intersecting(new IntBox(100, 0, 0, 125, 5, 5)).stream().map(SpatialIndex.Item::key).sorted().toList();
        assertEquals(List.of("player/bridge:waypoints/home", "shared/bridge:zones/z5", "shared/bridge:zones/z6"), hits);
        SpatialIndex.Item near = idx.nearest(25, 20, 1, null, 10);
        assertNotNull(near);
        assertEquals("bot:a@x/bridge:path-tool/r", near.key());
        SpatialIndex.Item point = idx.nearest(0, 0, 0, SpatialIndex.Kind.POINT, 200);
        assertNotNull(point);
        assertEquals("Home", point.name());
        assertNull(idx.nearest(0, 500, 0, null, 10));
        assertEquals("Zone 3", idx.get("shared/bridge:zones/z3").name());
        assertTrue(SpatialIndex.EMPTY.all().isEmpty());
    }
}
