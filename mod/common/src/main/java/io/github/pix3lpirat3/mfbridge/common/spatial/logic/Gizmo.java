package io.github.pix3lpirat3.mfbridge.common.spatial.logic;

import io.github.pix3lpirat3.mfbridge.core.spatial.GizmoMath;
import io.github.pix3lpirat3.mfbridge.core.spatial.IntBox;
import io.github.pix3lpirat3.mfbridge.core.spatial.SpatialIndex;
import io.github.pix3lpirat3.mfbridge.core.spatial.ZoneShape;
import io.github.pix3lpirat3.mfbridge.core.spatial.ZoneSurface;
import io.github.pix3lpirat3.mfbridge.protocol.core.PointData;
import io.github.pix3lpirat3.mfbridge.protocol.core.RouteData;
import io.github.pix3lpirat3.mfbridge.protocol.core.RouteNode;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import io.github.pix3lpirat3.mfbridge.protocol.core.ZoneBox;
import io.github.pix3lpirat3.mfbridge.protocol.core.ZoneData;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Gizmo handles of zones, points and routes and the edits that dragging and nudging them make.
 * Handles are small boxes, except the yaw ring of a point. Zone boxes and polygon
 * vertices stay on whole blocks whatever the snap mode; point and route positions follow the snap
 * mode. Coordinates are world blocks; zone handles sit on block corners ({@code max + 1} for the far
 * side), polygon vertex handles on the vertices as given.
 */
public final class Gizmo {

    /** Radius of a point's yaw ring, in blocks. */
    public static final double RING_RADIUS = 1.1d;

    /** Half the width of the yaw ring band that counts as a hit, in blocks. */
    public static final double RING_BAND = 0.15d;

    /** How far axis handles sit from what they move, in blocks. */
    public static final double AXIS_OFFSET = 1.4d;

    /** Smallest grab distance scrolling can reach, in blocks. */
    public static final double MIN_GRAB = 1d;

    /** Grab distance change per scroll step, in blocks. */
    public static final double SCROLL_STEP = 0.5d;

    /** What a handle edits. */
    public enum Type {
        /** A box corner: free move; {@code sub} bit 0, 1, 2 select the far x, y, z side. */
        CORNER,
        /** A box face: move along its axis; {@code sub} is 0..5 for -x, +x, -y, +y, -z, +z. */
        FACE,
        /** A whole box ({@code index}), a whole polygon ({@code index -1}) or a whole route: free move. */
        BODY,
        /** A polygon vertex: move in its horizontal plane. */
        VERTEX,
        /** A prism's top: move along y. */
        TOP,
        /** A prism's bottom: move along y. */
        BOTTOM,
        /** A point: free move. */
        MOVE,
        /** Move along a world axis ({@code sub} 0, 1, 2 for x, y, z) a point or route node {@code index}. */
        AXIS,
        /** A point's yaw ring. */
        YAW,
        /** A route node: free move. */
        NODE
    }

    /**
     * One handle.
     *
     * @param type what it edits
     * @param index box, vertex or node index; -1 for whole objects and points
     * @param sub corner, face or axis selector
     * @param x centre x
     * @param y centre y
     * @param z centre z
     * @param half half the box size, or the ring radius for {@link Type#YAW}
     */
    public record Handle(Type type, int index, int sub, double x, double y, double z, double half) {
    }

    /**
     * A ray from the eye.
     *
     * @param ox origin x
     * @param oy origin y
     * @param oz origin z
     * @param dx unit direction x
     * @param dy unit direction y
     * @param dz unit direction z
     */
    public record Ray(double ox, double oy, double oz, double dx, double dy, double dz) {
        /** Returns the point at a distance along the ray. */
        public double[] at(final double t) {
            return new double[] {ox + dx * t, oy + dy * t, oz + dz * t};
        }
    }

    /**
     * A handle hit by a ray.
     *
     * @param handle the handle
     * @param distance distance along the ray, in blocks
     */
    public record Hit(Handle handle, double distance) {
    }

    private Gizmo() {
    }

    /**
     * Returns the handles of an object's data.
     *
     * @param data a {@link ZoneData}, {@link PointData} or {@link RouteData}
     * @param active the zone box or route node that shows its full handle set, or -1
     */
    public static List<Handle> handles(final Object data, final int active) {
        final List<Handle> out = new ArrayList<>();
        switch (data) {
            case ZoneData z -> zoneHandles(out, z, active);
            case PointData p -> {
                final double x = p.pos().x();
                final double y = p.pos().y();
                final double z = p.pos().z();
                out.add(new Handle(Type.MOVE, -1, 0, x, y, z, 0.25));
                axes(out, -1, x, y, z);
                out.add(new Handle(Type.YAW, -1, 0, x, y, z, RING_RADIUS));
            }
            case RouteData r -> {
                final List<RouteNode> nodes = r.nodes();
                double cx = 0;
                double cy = Double.NEGATIVE_INFINITY;
                double cz = 0;
                for (int i = 0; i < nodes.size(); i++) {
                    final Vec3d p = nodes.get(i).pos();
                    out.add(new Handle(Type.NODE, i, 0, p.x(), p.y(), p.z(), 0.2));
                    cx += p.x();
                    cz += p.z();
                    cy = Math.max(cy, p.y());
                }
                if (active >= 0 && active < nodes.size()) {
                    final Vec3d p = nodes.get(active).pos();
                    axes(out, active, p.x(), p.y(), p.z());
                }
                if (!nodes.isEmpty()) {
                    out.add(new Handle(Type.BODY, -1, 0, cx / nodes.size(), cy + 2 * AXIS_OFFSET, cz / nodes.size(), 0.3));
                }
            }
            default -> {
                // Not a spatial definition: no handles.
            }
        }
        return out;
    }

    private static void axes(final List<Handle> out, final int index, final double x, final double y, final double z) {
        out.add(new Handle(Type.AXIS, index, 0, x + AXIS_OFFSET, y, z, 0.15));
        out.add(new Handle(Type.AXIS, index, 1, x, y + AXIS_OFFSET, z, 0.15));
        out.add(new Handle(Type.AXIS, index, 2, x, y, z + AXIS_OFFSET, 0.15));
    }

    private static void zoneHandles(final List<Handle> out, final ZoneData z, final int active) {
        final List<ZoneBox> boxes = z.boxes();
        if (boxes != null && !boxes.isEmpty()) {
            for (int i = 0; i < boxes.size(); i++) {
                final IntBox b = IntBox.of(boxes.get(i).min(), boxes.get(i).max());
                final double x0 = b.minX();
                final double y0 = b.minY();
                final double z0 = b.minZ();
                final double x1 = b.maxX() + 1d;
                final double y1 = b.maxY() + 1d;
                final double z1 = b.maxZ() + 1d;
                final double cx = (x0 + x1) / 2;
                final double cy = (y0 + y1) / 2;
                final double cz = (z0 + z1) / 2;
                out.add(new Handle(Type.BODY, i, 0, cx, y1 + 1d, cz, 0.3));
                if (i != active && !(active < 0 && i == 0)) {
                    continue;
                }
                for (int c = 0; c < 8; c++) {
                    out.add(new Handle(Type.CORNER, i, c, (c & 1) != 0 ? x1 : x0, (c & 2) != 0 ? y1 : y0, (c & 4) != 0 ? z1 : z0,
                            0.22));
                }
                out.add(new Handle(Type.FACE, i, 0, x0, cy, cz, 0.2));
                out.add(new Handle(Type.FACE, i, 1, x1, cy, cz, 0.2));
                out.add(new Handle(Type.FACE, i, 2, cx, y0, cz, 0.2));
                out.add(new Handle(Type.FACE, i, 3, cx, y1, cz, 0.2));
                out.add(new Handle(Type.FACE, i, 4, cx, cy, z0, 0.2));
                out.add(new Handle(Type.FACE, i, 5, cx, cy, z1, 0.2));
            }
            return;
        }
        if (!(ZoneShape.of(z) instanceof ZoneShape.Prism p)) {
            return;
        }
        final List<Vec3i> polygon = z.polygon();
        if (polygon == null) {
            return;
        }
        final double bottom = drawnBottom(p);
        final double top = drawnTop(p);
        double cx = 0;
        double cz = 0;
        for (int i = 0; i < polygon.size(); i++) {
            final Vec3i v = polygon.get(i);
            out.add(new Handle(Type.VERTEX, i, 0, v.x(), bottom, v.z(), 0.22));
            cx += v.x();
            cz += v.z();
        }
        cx /= polygon.size();
        cz /= polygon.size();
        out.add(new Handle(Type.TOP, -1, 0, cx, top, cz, 0.22));
        out.add(new Handle(Type.BOTTOM, -1, 0, cx, bottom, cz, 0.22));
        out.add(new Handle(Type.BODY, -1, 0, cx, top + 1d, cz, 0.3));
    }

    /**
     * Returns the editable object a ray points at: the nearest zone surface, point marker or route
     * line in front of {@code maxDistance}. Objects whose data is not editable are skipped.
     *
     * @param maxDistance ray length, usually the distance to the block under the crosshair
     * @return the object and its distance, or null
     */
    public static SpatialIndex.@Nullable Item pick(final List<SpatialIndex.Item> items, final Ray r, final double maxDistance) {
        SpatialIndex.Item best = null;
        double bestT = maxDistance;
        for (final SpatialIndex.Item it : items) {
            final double t = it.editable() ? shapeDistance(it, r) : -1;
            if (t >= 0 && t < bestT) {
                bestT = t;
                best = it;
            }
        }
        return best;
    }

    /**
     * Returns the object whose shape a ray meets first, editable or not: a zone surface, point
     * marker or route line in front of {@code maxDistance}.
     *
     * @param maxDistance ray length, usually the distance to the block under the crosshair
     * @return the object, or null
     */
    public static SpatialIndex.@Nullable Item pickShape(final List<SpatialIndex.Item> items, final Ray r, final double maxDistance) {
        SpatialIndex.Item best = null;
        double bestT = maxDistance;
        for (final SpatialIndex.Item it : items) {
            final double t = shapeDistance(it, r);
            if (t >= 0 && t < bestT) {
                bestT = t;
                best = it;
            }
        }
        return best;
    }

    /**
     * Returns the object whose name label a ray passes through first, in front of
     * {@code maxDistance}. Labels are read as the flat boxes they are drawn as.
     *
     * @param maxDistance ray length
     * @return the object, or null
     */
    public static SpatialIndex.@Nullable Item pickLabel(final List<SpatialIndex.Item> items, final Ray r, final double maxDistance) {
        SpatialIndex.Item best = null;
        double bestT = maxDistance;
        for (final SpatialIndex.Item it : items) {
            final double[] at = SpatialShapes.labelAt(it);
            if (at == null) {
                continue;
            }
            final double t = labelDistance(at, it.name(), r);
            if (t >= 0 && t < bestT) {
                bestT = t;
                best = it;
            }
        }
        return best;
    }

    /**
     * Distance along the ray to a label's centre when the ray passes through it, else -1.
     *
     * @param at x, y, z and scale of the label, from {@link SpatialShapes#labelAt}
     */
    static double labelDistance(final double[] at, final String text, final Ray r) {
        final double t = (at[0] - r.ox()) * r.dx() + (at[1] - r.oy()) * r.dy() + (at[2] - r.oz()) * r.dz();
        if (t <= 0) {
            return -1;
        }
        final double[] p = r.at(t);
        final double dx = at[0] - p[0];
        final double dz = at[2] - p[2];
        final double halfWidth = SpatialShapes.labelHalfWidth(text, at[3]);
        return Math.abs(at[1] - p[1]) <= SpatialShapes.labelHalfHeight(at[3]) && dx * dx + dz * dz <= halfWidth * halfWidth ? t : -1;
    }

    private static double shapeDistance(final SpatialIndex.Item it, final Ray r) {
        return switch (it.kind()) {
            case POINT -> GizmoMath.raySphere(r.ox(), r.oy(), r.oz(), r.dx(), r.dy(), r.dz(), it.minX(), it.minY(), it.minZ(), 0.45);
            case ZONE -> {
                final ZoneShape shape = it.zone();
                yield shape == null ? -1 : surface(ZoneSurface.of(shape).quads(), r);
            }
            case ROUTE -> {
                final SpatialEdits.Insertion near = it.data() instanceof RouteData route ? SpatialEdits.insertion(route, r) : null;
                yield near == null ? -1 : (near.pos().x() - r.ox()) * r.dx() + (near.pos().y() - r.oy()) * r.dy()
                        + (near.pos().z() - r.oz()) * r.dz();
            }
        };
    }

    private static double surface(final double[] q, final Ray r) {
        double best = -1;
        for (int i = 0; i + 11 < q.length; i += 12) {
            final double t1 = triangle(q, i, i + 3, i + 6, r);
            final double t2 = triangle(q, i, i + 6, i + 9, r);
            for (final double t : new double[] {t1, t2}) {
                if (t >= 0 && (best < 0 || t < best)) {
                    best = t;
                }
            }
        }
        return best;
    }

    /** Moller-Trumbore ray and triangle test; -1 on a miss. */
    static double triangle(final double[] q, final int a, final int b, final int c, final Ray r) {
        final double e1x = q[b] - q[a];
        final double e1y = q[b + 1] - q[a + 1];
        final double e1z = q[b + 2] - q[a + 2];
        final double e2x = q[c] - q[a];
        final double e2y = q[c + 1] - q[a + 1];
        final double e2z = q[c + 2] - q[a + 2];
        final double px = r.dy() * e2z - r.dz() * e2y;
        final double py = r.dz() * e2x - r.dx() * e2z;
        final double pz = r.dx() * e2y - r.dy() * e2x;
        final double det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1e-12) {
            return -1;
        }
        final double inv = 1 / det;
        final double tx = r.ox() - q[a];
        final double ty = r.oy() - q[a + 1];
        final double tz = r.oz() - q[a + 2];
        final double u = (tx * px + ty * py + tz * pz) * inv;
        if (u < 0 || u > 1) {
            return -1;
        }
        final double qx = ty * e1z - tz * e1y;
        final double qy = tz * e1x - tx * e1z;
        final double qz = tx * e1y - ty * e1x;
        final double v = (r.dx() * qx + r.dy() * qy + r.dz() * qz) * inv;
        if (v < 0 || u + v > 1) {
            return -1;
        }
        final double t = (e2x * qx + e2y * qy + e2z * qz) * inv;
        return t >= 0 ? t : -1;
    }

    /** Bottom of a prism as drawn: its lower limit, or the drawing floor when it has none. */
    static double drawnBottom(final ZoneShape.Prism p) {
        return Math.max(p.minY(), Math.min(ZoneSurface.DRAW_MIN_Y, p.maxY()));
    }

    /** Top of a prism as drawn, on the block corner above its highest block. */
    static double drawnTop(final ZoneShape.Prism p) {
        return Math.min(p.maxY(), Math.max(ZoneSurface.DRAW_MAX_Y, p.minY())) + 1d;
    }

    /**
     * Returns the nearest handle a ray hits.
     *
     * @param maxDistance ray length, in blocks
     * @return the hit, or null when the ray misses every handle
     */
    public static @Nullable Hit hit(final List<Handle> handles, final Ray ray, final double maxDistance) {
        Hit best = null;
        for (final Handle h : handles) {
            final double t = distance(h, ray);
            if (t >= 0 && t <= maxDistance && (best == null || t < best.distance())) {
                best = new Hit(h, t);
            }
        }
        return best;
    }

    private static double distance(final Handle h, final Ray r) {
        if (h.type() == Type.YAW) {
            final double t = GizmoMath.rayPlane(r.ox(), r.oy(), r.oz(), r.dx(), r.dy(), r.dz(), h.x(), h.y(), h.z(), 0, 1, 0);
            if (t < 0) {
                return -1;
            }
            final double px = r.ox() + r.dx() * t - h.x();
            final double pz = r.oz() + r.dz() * t - h.z();
            final double d = Math.sqrt(px * px + pz * pz);
            return Math.abs(d - h.half()) <= RING_BAND ? t : -1;
        }
        final double s = h.half();
        return GizmoMath.rayBox(r.ox(), r.oy(), r.oz(), r.dx(), r.dy(), r.dz(), h.x() - s, h.y() - s, h.z() - s, h.x() + s,
                h.y() + s, h.z() + s);
    }

    /**
     * One drag in progress: grab-at-distance follows the eye plus the look vector times the grab
     * distance, and scrolling changes that distance. {@link #update} turns the current ray into the
     * edited data, always starting from the data the drag began with.
     */
    public static final class Drag {
        private final Handle handle;
        private final Object start;
        private double distance;
        private final double[] grabStart;
        private final double axisStart;
        private final double[] planeStart;

        /**
         * Starts a drag.
         *
         * @param hit the handle and where the ray hit it
         * @param start the object's data when the drag starts
         * @param ray the ray of the click
         */
        public Drag(final Hit hit, final Object start, final Ray ray) {
            this.handle = hit.handle();
            this.start = start;
            this.distance = Math.max(MIN_GRAB, hit.distance());
            this.grabStart = ray.at(distance);
            this.axisStart = axisParam(ray);
            this.planeStart = planeHit(ray);
        }

        /** Returns the handle being dragged. */
        public Handle handle() {
            return handle;
        }

        /** Returns the data the drag started from. */
        public Object start() {
            return start;
        }

        /** Changes the grab distance by whole scroll steps, positive away from the eye. */
        public void scroll(final int steps) {
            distance = Math.max(MIN_GRAB, distance + steps * SCROLL_STEP);
        }

        private int axis() {
            return switch (handle.type()) {
                case FACE -> handle.sub() / 2;
                case AXIS -> handle.sub();
                case TOP, BOTTOM -> 1;
                case CORNER, BODY, VERTEX, MOVE, YAW, NODE -> -1;
            };
        }

        private double axisParam(final Ray r) {
            final int a = axis();
            if (a < 0) {
                return 0;
            }
            return GizmoMath.closestOnAxis(r.ox(), r.oy(), r.oz(), r.dx(), r.dy(), r.dz(), handle.x(), handle.y(), handle.z(),
                    a == 0 ? 1 : 0, a == 1 ? 1 : 0, a == 2 ? 1 : 0);
        }

        private double[] planeHit(final Ray r) {
            final double t = GizmoMath.rayPlane(r.ox(), r.oy(), r.oz(), r.dx(), r.dy(), r.dz(), handle.x(), handle.y(),
                    handle.z(), 0, 1, 0);
            return t < 0 ? new double[] {handle.x(), handle.y(), handle.z()} : r.at(t);
        }

        /**
         * Returns the data for the current ray.
         *
         * @param snap the snap mode of the held modifiers
         * @return new data of the same type as the start data
         */
        public Object update(final Ray ray, final GizmoMath.Snap snap) {
            final double[] grab = ray.at(distance);
            final double dx = grab[0] - grabStart[0];
            final double dy = grab[1] - grabStart[1];
            final double dz = grab[2] - grabStart[2];
            final double along = axisParam(ray) - axisStart;
            return switch (start) {
                case ZoneData z -> zone(z, ray, dx, dy, dz, along);
                case PointData p -> point(p, ray, snap, dx, dy, dz, along);
                case RouteData r -> route(r, snap, dx, dy, dz, along);
                default -> start;
            };
        }

        private ZoneData zone(final ZoneData z, final Ray ray, final double dx, final double dy, final double dz,
                final double along) {
            final List<ZoneBox> boxes = z.boxes();
            if (boxes != null && !boxes.isEmpty()) {
                final int i = handle.index();
                if (i < 0 || i >= boxes.size()) {
                    return z;
                }
                final ZoneBox zb = boxes.get(i);
                final IntBox b = IntBox.of(zb.min(), zb.max());
                final IntBox moved = switch (handle.type()) {
                    case CORNER -> corner(b, handle.sub(), (int) Math.round(handle.x() + dx), (int) Math.round(handle.y() + dy),
                            (int) Math.round(handle.z() + dz));
                    case FACE -> face(b, handle.sub(), (int) Math.round(coord(handle, handle.sub() / 2) + along));
                    case BODY -> shift(b, (int) Math.round(dx), (int) Math.round(dy), (int) Math.round(dz));
                    default -> b;
                };
                return SpatialEdits.withBox(z, i, new ZoneBox(moved.min(), moved.max(), zb.op()));
            }
            return switch (handle.type()) {
                case VERTEX -> {
                    final double[] p = planeHit(ray);
                    yield SpatialEdits.withVertex(z, handle.index(), (int) Math.round(p[0] - planeStart[0] + handle.x()),
                            (int) Math.round(p[2] - planeStart[2] + handle.z()));
                }
                case TOP -> SpatialEdits.withPrismY(z, true, (int) Math.round(handle.y() + along) - 1);
                case BOTTOM -> SpatialEdits.withPrismY(z, false, (int) Math.round(handle.y() + along));
                case BODY -> SpatialEdits.shiftZone(z, (int) Math.round(dx), (int) Math.round(dy), (int) Math.round(dz));
                default -> z;
            };
        }

        private PointData point(final PointData p, final Ray ray, final GizmoMath.Snap snap, final double dx, final double dy,
                final double dz, final double along) {
            final Vec3d s = p.pos();
            return switch (handle.type()) {
                case MOVE -> SpatialEdits.withPos(p, new Vec3d(snap.snap(s.x() + dx), snap.snap(s.y() + dy), snap.snap(s.z() + dz)));
                case AXIS -> SpatialEdits.withPos(p, alongAxis(s, handle.sub(), along, snap));
                case YAW -> {
                    final double[] h = planeHit(ray);
                    final double yaw = GizmoMath.wrapDegrees(snap.snapAngle(GizmoMath.yawTowards(s.x(), s.z(), h[0], h[2])));
                    yield SpatialEdits.withYaw(p, (float) yaw);
                }
                default -> p;
            };
        }

        private RouteData route(final RouteData r, final GizmoMath.Snap snap, final double dx, final double dy, final double dz,
                final double along) {
            final int i = handle.index();
            return switch (handle.type()) {
                case NODE -> {
                    final Vec3d s = r.nodes().get(i).pos();
                    yield SpatialEdits.withNode(r, i, new Vec3d(snap.snap(s.x() + dx), snap.snap(s.y() + dy), snap.snap(s.z() + dz)));
                }
                case AXIS -> SpatialEdits.withNode(r, i, alongAxis(r.nodes().get(i).pos(), handle.sub(), along, snap));
                case BODY -> SpatialEdits.shiftRoute(r, snap.snap(dx), snap.snap(dy), snap.snap(dz));
                default -> r;
            };
        }
    }

    private static Vec3d alongAxis(final Vec3d s, final int axis, final double along, final GizmoMath.Snap snap) {
        return switch (axis) {
            case 0 -> new Vec3d(snap.snap(s.x() + along), s.y(), s.z());
            case 1 -> new Vec3d(s.x(), snap.snap(s.y() + along), s.z());
            default -> new Vec3d(s.x(), s.y(), snap.snap(s.z() + along));
        };
    }

    private static double coord(final Handle h, final int axis) {
        return switch (axis) {
            case 0 -> h.x();
            case 1 -> h.y();
            default -> h.z();
        };
    }

    /** Moves one corner of a box to a block corner lattice point; the opposite corner stays. */
    static IntBox corner(final IntBox b, final int sub, final int lx, final int ly, final int lz) {
        final int fx = (sub & 1) != 0 ? b.minX() : b.maxX() + 1;
        final int fy = (sub & 2) != 0 ? b.minY() : b.maxY() + 1;
        final int fz = (sub & 4) != 0 ? b.minZ() : b.maxZ() + 1;
        return new IntBox(Math.min(lx, fx), Math.min(ly, fy), Math.min(lz, fz),
                Math.max(Math.max(lx, fx) - 1, Math.min(lx, fx)), Math.max(Math.max(ly, fy) - 1, Math.min(ly, fy)),
                Math.max(Math.max(lz, fz) - 1, Math.min(lz, fz)));
    }

    /** Moves one face of a box to a block corner coordinate, keeping the box at least one block thick. */
    static IntBox face(final IntBox b, final int sub, final int lattice) {
        int x0 = b.minX();
        int y0 = b.minY();
        int z0 = b.minZ();
        int x1 = b.maxX();
        int y1 = b.maxY();
        int z1 = b.maxZ();
        switch (sub) {
            case 0 -> x0 = Math.min(lattice, x1);
            case 1 -> x1 = Math.max(lattice - 1, x0);
            case 2 -> y0 = Math.min(lattice, y1);
            case 3 -> y1 = Math.max(lattice - 1, y0);
            case 4 -> z0 = Math.min(lattice, z1);
            default -> z1 = Math.max(lattice - 1, z0);
        }
        return new IntBox(x0, y0, z0, x1, y1, z1);
    }

    static IntBox shift(final IntBox b, final int dx, final int dy, final int dz) {
        return new IntBox(b.minX() + dx, b.minY() + dy, b.minZ() + dz, b.maxX() + dx, b.maxY() + dy, b.maxZ() + dz);
    }
}
