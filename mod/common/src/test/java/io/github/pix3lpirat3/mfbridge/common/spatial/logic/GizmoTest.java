package io.github.pix3lpirat3.mfbridge.common.spatial.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.core.RenderEntry;
import io.github.pix3lpirat3.mfbridge.core.spatial.GizmoMath;
import io.github.pix3lpirat3.mfbridge.core.spatial.SpatialIndex;
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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GizmoTest {
    static ZoneData box(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
        return new ZoneData("Z", List.of(new ZoneBox(new Vec3i(x0, y0, z0), new Vec3i(x1, y1, z1))), null, null, null, null,
                0xFF00FF00, 0.1f, true, true);
    }

    static Gizmo.Handle handle(final Object data, final Gizmo.Type type, final int sub) {
        for (final Gizmo.Handle h : Gizmo.handles(data, 0)) {
            if (h.type() == type && h.sub() == sub) {
                return h;
            }
        }
        throw new AssertionError("no handle " + type + " " + sub);
    }

    /** A ray from 10 blocks south of a point, looking north (-z) at it. */
    static Gizmo.Ray towards(final double x, final double y, final double z) {
        return new Gizmo.Ray(x, y, z + 10, 0, 0, -1);
    }

    /** A ray from 10 blocks west of a point, looking east (+x) at it. */
    static Gizmo.Ray fromWest(final double x, final double y, final double z) {
        return new Gizmo.Ray(x - 10, y, z, 1, 0, 0);
    }

    static Gizmo.Hit hitOf(final Object data, final Gizmo.Handle h, final Gizmo.Ray ray) {
        final Gizmo.Hit hit = Gizmo.hit(Gizmo.handles(data, 0), ray, 64);
        assertNotNull(hit);
        assertEquals(h, hit.handle());
        return hit;
    }

    static SpatialIndex.Item item(final String key, final KindType<?> kind, final Object data) {
        final SpatialIndex.Item it = SpatialIndex.item(new RenderEntry(1, key, "shared", "bridge:zones", "x", null, kind, 3, data,
                null, Scope.GLOBAL, null));
        assertNotNull(it);
        return it;
    }

    @Test
    void cornerDragResizesFromTheOppositeCorner() {
        final ZoneData z = box(0, 0, 0, 3, 3, 3);
        final Gizmo.Handle far = handle(z, Gizmo.Type.CORNER, 7);
        assertEquals(4.0, far.x());
        final Gizmo.Hit hit = hitOf(z, far, towards(far.x(), far.y(), far.z()));
        final Gizmo.Drag drag = new Gizmo.Drag(hit, z, towards(far.x(), far.y(), far.z()));
        final Object moved = drag.update(towards(far.x() + 2.4, far.y() + 1.2, far.z()), GizmoMath.Snap.NONE);
        final ZoneBox b = ((ZoneData) moved).boxes().getFirst();
        assertEquals(new Vec3i(0, 0, 0), b.min());
        assertEquals(new Vec3i(5, 4, 3), b.max());
        final Object crossed = drag.update(towards(far.x() - 8, far.y(), far.z()), GizmoMath.Snap.NONE);
        final ZoneBox c = ((ZoneData) crossed).boxes().getFirst();
        assertEquals(-4, c.min().x(), "dragging past the opposite corner flips the box");
        assertEquals(-1, c.max().x());
        assertSame(z, drag.start());
    }

    @Test
    void faceDragMovesOneFaceAlongItsAxisAndKeepsOneBlock() {
        final ZoneData z = box(0, 0, 0, 3, 3, 3);
        final Gizmo.Handle east = handle(z, Gizmo.Type.FACE, 1);
        final Gizmo.Ray start = new Gizmo.Ray(east.x(), east.y() + 10, east.z(), 0, -1, 0);
        final Gizmo.Hit hit = Gizmo.hit(Gizmo.handles(z, 0), start, 64);
        assertNotNull(hit);
        assertEquals(east, hit.handle());
        final Gizmo.Drag drag = new Gizmo.Drag(hit, z, start);
        final ZoneData wider = (ZoneData) drag.update(new Gizmo.Ray(east.x() + 3.2, east.y() + 10, east.z(), 0, -1, 0),
                GizmoMath.Snap.NONE);
        assertEquals(new Vec3i(6, 3, 3), wider.boxes().getFirst().max());
        final ZoneData thin = (ZoneData) drag.update(new Gizmo.Ray(east.x() - 30, east.y() + 10, east.z(), 0, -1, 0),
                GizmoMath.Snap.NONE);
        assertEquals(0, thin.boxes().getFirst().max().x(), "the box keeps at least one block");
    }

    @Test
    void bodyDragAndScrollMoveTheWholeBox() {
        final ZoneData z = box(0, 0, 0, 3, 3, 3);
        final Gizmo.Handle body = handle(z, Gizmo.Type.BODY, 0);
        final Gizmo.Hit hit = hitOf(z, body, towards(body.x(), body.y(), body.z()));
        final Gizmo.Drag drag = new Gizmo.Drag(hit, z, towards(body.x(), body.y(), body.z()));
        drag.scroll(-4);
        final ZoneData moved = (ZoneData) drag.update(towards(body.x(), body.y(), body.z()), GizmoMath.Snap.NONE);
        assertEquals(new Vec3i(0, 0, 2), moved.boxes().getFirst().min(), "scrolling in pulls the grab point closer");
    }

    @Test
    void pointMoveSnapsAndYawRingTurns() {
        final PointData p = new PointData("P", new Vec3d(0.5, 64, 0.5), 0f, null, 0, null, true, null);
        final Gizmo.Handle move = handle(p, Gizmo.Type.MOVE, 0);
        final Gizmo.Ray from = fromWest(move.x(), move.y(), move.z());
        final Gizmo.Drag drag = new Gizmo.Drag(hitOf(p, move, from), p, from);
        final Gizmo.Ray to = fromWest(move.x() + 1.3, move.y() + 0.2, move.z());
        assertEquals(1.8, ((PointData) drag.update(to, GizmoMath.Snap.NONE)).pos().x(), 1e-9);
        assertEquals(2.0, ((PointData) drag.update(to, GizmoMath.Snap.BLOCK)).pos().x(), 1e-9);
        assertEquals(2.0, ((PointData) drag.update(to, GizmoMath.Snap.HALF)).pos().x(), 1e-9);
        assertEquals(64.0, ((PointData) drag.update(to, GizmoMath.Snap.HALF)).pos().y(), 1e-9);

        // Looking straight down onto the ring on its west side (-x) means yaw 90.
        final Gizmo.Ray down = new Gizmo.Ray(0.5 - Gizmo.RING_RADIUS, 70, 0.5, 0, -1, 0);
        final Gizmo.Hit ring = Gizmo.hit(Gizmo.handles(p, -1), down, 64);
        assertNotNull(ring);
        assertEquals(Gizmo.Type.YAW, ring.handle().type());
        final Gizmo.Drag turn = new Gizmo.Drag(ring, p, down);
        final Gizmo.Ray southWest = new Gizmo.Ray(0.5 - 0.8, 70, 0.5 + 0.9, 0, -1, 0);
        assertEquals(41.6, ((PointData) turn.update(southWest, GizmoMath.Snap.NONE)).yaw(), 0.1);
        assertEquals(45.0, ((PointData) turn.update(southWest, GizmoMath.Snap.BLOCK)).yaw(), 1e-6);
    }

    @Test
    void routeNodesInsertDeleteAndNudge() {
        final RouteData r = new RouteData("R", List.of(new RouteNode(new Vec3d(0, 64, 0)), new RouteNode(new Vec3d(10, 64, 0)),
                new RouteNode(new Vec3d(10, 64, 10))));
        final SpatialEdits.Insertion at = SpatialEdits.insertion(r, new Gizmo.Ray(4, 70, 0.2, 0, -1, 0));
        assertNotNull(at);
        assertEquals(1, at.index());
        assertEquals(4, at.pos().x(), 1e-9);
        assertNull(SpatialEdits.insertion(r, new Gizmo.Ray(4, 70, 3, 0, -1, 0)), "too far from every segment");
        final RouteData four = SpatialEdits.withInsertedNode(r, at.index(), at.pos());
        assertEquals(4, four.nodes().size());
        assertEquals(new Vec3d(4, 64, 0), four.nodes().get(1).pos());
        final RouteData back = SpatialEdits.withoutNode(four, 1);
        assertEquals(r.nodes(), back.nodes());
        final RouteData two = SpatialEdits.withoutNode(SpatialEdits.withoutNode(r, 0), 0);
        assertEquals(2, two.nodes().size(), "a route keeps two nodes");

        final RouteData nudged = (RouteData) SpatialEdits.nudge(r, 2, GizmoMath.nudgeAxis(0, GizmoMath.Nudge.FORWARD), 0.5);
        assertEquals(10.5, nudged.nodes().get(2).pos().z(), 1e-9);
        final RouteData all = (RouteData) SpatialEdits.nudge(r, -1, new int[] {0, 1, 0}, 1);
        assertEquals(65, all.nodes().get(0).pos().y(), 1e-9);

        final Gizmo.Handle node = handle(r, Gizmo.Type.NODE, 0);
        final Gizmo.Ray from = fromWest(node.x(), node.y(), node.z());
        final Gizmo.Drag drag = new Gizmo.Drag(hitOf(r, node, from), r, from);
        final RouteData dragged = (RouteData) drag.update(fromWest(node.x() - 2, node.y(), node.z()), GizmoMath.Snap.BLOCK);
        assertEquals(-2, dragged.nodes().get(0).pos().x(), 1e-9);
    }

    @Test
    void zoneNudgesMoveWholeBlocks() {
        final ZoneData z = box(0, 0, 0, 3, 3, 3);
        final ZoneData moved = (ZoneData) SpatialEdits.nudge(z, 0, new int[] {-1, 0, 0}, 0.5);
        assertEquals(new Vec3i(-1, 0, 0), moved.boxes().getFirst().min(), "half steps round up to a block");
        final ZoneData polygon = new ZoneData("P", null, List.of(new Vec3i(0, 5, 0), new Vec3i(4, 5, 0), new Vec3i(0, 5, 4)), 60,
                null, null, 0, 0.1f, true, true);
        final ZoneData up = (ZoneData) SpatialEdits.nudge(polygon, -1, new int[] {0, 1, 0}, 1);
        assertEquals(61, up.minY());
        assertNull(up.maxY(), "an open upper limit stays open");
        assertEquals(polygon, SpatialEdits.withoutVertex(polygon, 0), "a polygon keeps three vertices");
        final ZoneData top = SpatialEdits.withPrismY(polygon, true, 50);
        assertEquals(60, top.maxY(), "the top cannot go below the bottom");
    }

    @Test
    void pickFindsTheNearestEditableObject() {
        final SpatialIndex.Item zone = item("shared/bridge:zones/a", CoreKinds.ZONE, box(0, 60, 0, 9, 69, 9));
        final SpatialIndex.Item point = item("shared/bridge:zones/p", CoreKinds.POINT, new PointData("P", new Vec3d(5, 65, 15)));
        final SpatialIndex.Item locked = item("shared/bridge:zones/l", CoreKinds.POINT,
                new PointData("L", new Vec3d(5, 65, 18), null, null, 0, null, false, null));
        final Gizmo.Ray ray = new Gizmo.Ray(5, 65, 25, 0, 0, -1);
        assertEquals(point, Gizmo.pick(List.of(zone, point, locked), ray, 64), "the locked point is skipped");
        assertEquals(zone, Gizmo.pick(List.of(zone, locked), ray, 64));
        assertNull(Gizmo.pick(List.of(zone), ray, 10), "the zone is behind the block");
    }

    @Test
    void shapesMergeZonesAndHideTheEditedObject() {
        final SpatialIndex index = SpatialIndex.build(1, List.of(
                new RenderEntry(1, "shared/bridge:zones/a", "shared", "bridge:zones", "a", null, CoreKinds.ZONE,
                        1, new ZoneData("A", List.of(new ZoneBox(new Vec3i(0, 0, 0), new Vec3i(4, 4, 4)),
                                new ZoneBox(new Vec3i(5, 0, 0), new Vec3i(9, 4, 4))), null, null, null, null, 0x80FF0000, 0.2f, true,
                                true), null, Scope.GLOBAL, null),
                new RenderEntry(2, "shared/bridge:zones/p", "shared", "bridge:zones", "p", null, CoreKinds.POINT, 1,
                        new PointData("P", new Vec3d(1, 2, 3), 90f, null, 0xFF0000FF, null, true, null), null, Scope.GLOBAL, null)));
        final List<DrawCommand> all = SpatialShapes.all(index, Set.of());
        assertEquals(10, all.stream().filter(c -> c instanceof DrawCommand.Quad).count());
        assertEquals(12 + 1, all.stream().filter(c -> c instanceof DrawCommand.Line).count(), "outline plus the point's arrow");
        assertTrue(all.stream().anyMatch(c -> c instanceof DrawCommand.Label l && l.text().equals("A")));
        final DrawCommand.Quad q = (DrawCommand.Quad) all.stream().filter(c -> c instanceof DrawCommand.Quad).findFirst().orElseThrow();
        assertEquals(0xFF000080 | 0xFF, q.rgba());
        assertEquals(0.2f, q.fillAlpha());
        final List<DrawCommand> hidden = SpatialShapes.all(index, Set.of("shared/bridge:zones/a"));
        assertEquals(2, hidden.size(), "only the point's marker and arrow remain");
    }

    @Test
    void rulerMeasures() {
        final Ruler r = Ruler.measure(new Vec3i(0, 64, 0), new Vec3i(3, 68, -4));
        assertEquals(Math.sqrt(9 + 16 + 16), r.euclidean(), 1e-9);
        assertEquals(11, r.manhattan());
        assertEquals(4, r.sizeX());
        assertEquals(5, r.sizeY());
        assertEquals(5, r.sizeZ());
        assertEquals(-143.13, r.yaw(), 0.01);
        assertTrue(r.pitch() < 0, "looking up has a negative pitch");
        assertEquals("3.0 m | 3 manhattan | 1x1x4 | yaw 180.0 pitch 0.0",
                Ruler.measure(new Vec3i(0, 0, 0), new Vec3i(0, 0, -3)).label());
    }
}
