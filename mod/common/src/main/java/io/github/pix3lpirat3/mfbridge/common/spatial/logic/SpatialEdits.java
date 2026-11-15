package io.github.pix3lpirat3.mfbridge.common.spatial.logic;

import io.github.pix3lpirat3.mfbridge.core.spatial.GizmoMath;
import io.github.pix3lpirat3.mfbridge.core.spatial.IntBox;
import io.github.pix3lpirat3.mfbridge.core.spatial.ZoneShape;
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
 * Edits of zone, point and route data as new immutable values: the field-level changes behind gizmo
 * drags, nudges, node insertion and deletion. Every method returns a new value and leaves its input
 * untouched.
 */
public final class SpatialEdits {

    /** How close a Ctrl-click must pass a route segment to insert a node there, in blocks. */
    public static final double INSERT_TOLERANCE = 0.6d;

    private SpatialEdits() {
    }

    /** Returns the zone with box {@code index} replaced. */
    public static ZoneData withBox(final ZoneData z, final int index, final ZoneBox box) {
        final List<ZoneBox> boxes = new ArrayList<>(z.boxes() == null ? List.of() : z.boxes());
        boxes.set(index, box);
        return new ZoneData(z.name(), List.copyOf(boxes), z.polygon(), z.minY(), z.maxY(), z.tags(), z.color(), z.fill(),
                z.showLabel(), z.editable());
    }

    /** Returns the zone with polygon vertex {@code index} moved; the vertex keeps its y. */
    public static ZoneData withVertex(final ZoneData z, final int index, final int x, final int zz) {
        final List<Vec3i> polygon = new ArrayList<>(z.polygon() == null ? List.of() : z.polygon());
        if (index < 0 || index >= polygon.size()) {
            return z;
        }
        polygon.set(index, new Vec3i(x, polygon.get(index).y(), zz));
        return withPolygon(z, polygon, z.minY(), z.maxY());
    }

    /** Returns the zone with polygon vertex {@code index} removed, or the zone unchanged at three vertices. */
    public static ZoneData withoutVertex(final ZoneData z, final int index) {
        final List<Vec3i> polygon = new ArrayList<>(z.polygon() == null ? List.of() : z.polygon());
        if (polygon.size() <= 3 || index < 0 || index >= polygon.size()) {
            return z;
        }
        polygon.remove(index);
        return withPolygon(z, polygon, z.minY(), z.maxY());
    }

    /**
     * Returns the prism with its top or bottom block row set, keeping at least one block of height.
     *
     * @param top true for {@code maxY}, false for {@code minY}
     * @param y the new block y, inclusive
     */
    public static ZoneData withPrismY(final ZoneData z, final boolean top, final int y) {
        if (!(ZoneShape.of(z) instanceof ZoneShape.Prism)) {
            return z;
        }
        final Integer minY = z.minY();
        final Integer maxY = z.maxY();
        if (top) {
            return withPolygon(z, z.polygon(), minY, minY == null ? y : Math.max(y, minY));
        }
        return withPolygon(z, z.polygon(), maxY == null ? y : Math.min(y, maxY), maxY);
    }

    private static ZoneData withPolygon(final ZoneData z, final @Nullable List<Vec3i> polygon, final @Nullable Integer minY,
            final @Nullable Integer maxY) {
        return new ZoneData(z.name(), z.boxes(), polygon == null ? null : List.copyOf(polygon), minY, maxY, z.tags(), z.color(),
                z.fill(), z.showLabel(), z.editable());
    }

    /** Returns the zone moved by whole blocks: every box, or the polygon and its y limits. */
    public static ZoneData shiftZone(final ZoneData z, final int dx, final int dy, final int dz) {
        final List<ZoneBox> boxes = z.boxes();
        if (boxes != null && !boxes.isEmpty()) {
            final List<ZoneBox> out = new ArrayList<>(boxes.size());
            for (final ZoneBox b : boxes) {
                out.add(shiftBox(b, dx, dy, dz));
            }
            return new ZoneData(z.name(), List.copyOf(out), z.polygon(), z.minY(), z.maxY(), z.tags(), z.color(), z.fill(),
                    z.showLabel(), z.editable());
        }
        final List<Vec3i> polygon = z.polygon();
        if (polygon == null) {
            return z;
        }
        final List<Vec3i> out = new ArrayList<>(polygon.size());
        for (final Vec3i v : polygon) {
            out.add(new Vec3i(v.x() + dx, v.y() + dy, v.z() + dz));
        }
        final Integer minY = z.minY();
        final Integer maxY = z.maxY();
        return withPolygon(z, out, minY == null ? null : minY + dy, maxY == null ? null : maxY + dy);
    }

    private static ZoneBox shiftBox(final ZoneBox b, final int dx, final int dy, final int dz) {
        return new ZoneBox(new Vec3i(b.min().x() + dx, b.min().y() + dy, b.min().z() + dz),
                new Vec3i(b.max().x() + dx, b.max().y() + dy, b.max().z() + dz), b.op());
    }

    /** Returns the point at a new position. */
    public static PointData withPos(final PointData p, final Vec3d pos) {
        return new PointData(p.name(), pos, p.yaw(), p.tags(), p.color(), p.icon(), p.editable(), p.pitch());
    }

    /** Returns the point facing a new yaw, in degrees. */
    public static PointData withYaw(final PointData p, final float yaw) {
        return new PointData(p.name(), p.pos(), yaw, p.tags(), p.color(), p.icon(), p.editable(), p.pitch());
    }

    /** Returns the route with node {@code index} moved; the node keeps its data. */
    public static RouteData withNode(final RouteData r, final int index, final Vec3d pos) {
        final List<RouteNode> nodes = new ArrayList<>(r.nodes());
        if (index < 0 || index >= nodes.size()) {
            return r;
        }
        nodes.set(index, new RouteNode(pos, nodes.get(index).data()));
        return withNodes(r, nodes);
    }

    /** Returns the route with a new node inserted before position {@code index}. */
    public static RouteData withInsertedNode(final RouteData r, final int index, final Vec3d pos) {
        final List<RouteNode> nodes = new ArrayList<>(r.nodes());
        nodes.add(Math.clamp(index, 0, nodes.size()), new RouteNode(pos));
        return withNodes(r, nodes);
    }

    /** Returns the route without node {@code index}, or the route unchanged at two nodes. */
    public static RouteData withoutNode(final RouteData r, final int index) {
        final List<RouteNode> nodes = new ArrayList<>(r.nodes());
        if (nodes.size() <= 2 || index < 0 || index >= nodes.size()) {
            return r;
        }
        nodes.remove(index);
        return withNodes(r, nodes);
    }

    /** Returns the route moved by an offset. */
    public static RouteData shiftRoute(final RouteData r, final double dx, final double dy, final double dz) {
        final List<RouteNode> nodes = new ArrayList<>(r.nodes().size());
        for (final RouteNode n : r.nodes()) {
            nodes.add(new RouteNode(new Vec3d(n.pos().x() + dx, n.pos().y() + dy, n.pos().z() + dz), n.data()));
        }
        return withNodes(r, nodes);
    }

    private static RouteData withNodes(final RouteData r, final List<RouteNode> nodes) {
        return new RouteData(r.name(), List.copyOf(nodes), r.looped(), r.interpolation(), r.tags(), r.color(), r.width(),
                r.editable());
    }

    /**
     * Where a Ctrl-click inserts a route node: the point of the straight segment between two nodes
     * that passes closest to the ray, within {@link #INSERT_TOLERANCE}.
     *
     * @param index the insertion index: the new node goes before this node
     * @param pos the new node's position
     */
    public record Insertion(int index, Vec3d pos) {
    }

    /**
     * Finds where a Ctrl-click inserts a node.
     *
     * @return the insertion, or null when the ray passes no segment closely
     */
    public static @Nullable Insertion insertion(final RouteData r, final Gizmo.Ray ray) {
        final List<RouteNode> nodes = r.nodes();
        final int n = nodes.size();
        final int segments = r.looped() && n > 2 ? n : n - 1;
        Insertion best = null;
        double bestD = INSERT_TOLERANCE * INSERT_TOLERANCE;
        for (int s = 0; s < segments; s++) {
            final Vec3d a = nodes.get(s).pos();
            final Vec3d b = nodes.get((s + 1) % n).pos();
            final double sx = b.x() - a.x();
            final double sy = b.y() - a.y();
            final double sz = b.z() - a.z();
            final double len = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (len < 1e-6) {
                continue;
            }
            final double ux = sx / len;
            final double uy = sy / len;
            final double uz = sz / len;
            final double along = Math.clamp(GizmoMath.closestOnAxis(ray.ox(), ray.oy(), ray.oz(), ray.dx(), ray.dy(), ray.dz(),
                    a.x(), a.y(), a.z(), ux, uy, uz), 0, len);
            final double px = a.x() + ux * along;
            final double py = a.y() + uy * along;
            final double pz = a.z() + uz * along;
            final double t = (px - ray.ox()) * ray.dx() + (py - ray.oy()) * ray.dy() + (pz - ray.oz()) * ray.dz();
            if (t < 0) {
                continue;
            }
            final double qx = ray.ox() + ray.dx() * t - px;
            final double qy = ray.oy() + ray.dy() * t - py;
            final double qz = ray.oz() + ray.dz() * t - pz;
            final double d = qx * qx + qy * qy + qz * qz;
            if (d < bestD) {
                bestD = d;
                best = new Insertion(s + 1, new Vec3d(px, py, pz));
            }
        }
        return best;
    }

    /**
     * Nudges an object along a world axis: arrow keys and Page Up and Down.
     *
     * @param active the zone box or route node to move, or -1 for the whole object
     * @param axis a unit step from {@link GizmoMath#nudgeAxis}
     * @param step distance in blocks; zones round it to at least one whole block
     */
    public static Object nudge(final Object data, final int active, final int[] axis, final double step) {
        return switch (data) {
            case ZoneData z -> {
                final int s = (int) Math.max(1, Math.round(step));
                final List<ZoneBox> boxes = z.boxes();
                if (boxes != null && active >= 0 && active < boxes.size()) {
                    yield withBox(z, active, shiftBox(boxes.get(active), axis[0] * s, axis[1] * s, axis[2] * s));
                }
                yield shiftZone(z, axis[0] * s, axis[1] * s, axis[2] * s);
            }
            case PointData p -> withPos(p, new Vec3d(p.pos().x() + axis[0] * step, p.pos().y() + axis[1] * step,
                    p.pos().z() + axis[2] * step));
            case RouteData r -> {
                if (active >= 0 && active < r.nodes().size()) {
                    final Vec3d v = r.nodes().get(active).pos();
                    yield withNode(r, active, new Vec3d(v.x() + axis[0] * step, v.y() + axis[1] * step, v.z() + axis[2] * step));
                }
                yield shiftRoute(r, axis[0] * step, axis[1] * step, axis[2] * step);
            }
            default -> data;
        };
    }

    /** Returns a new zone of one box spanning two corner blocks, for the zone tool. */
    public static ZoneData boxZone(final String name, final List<String> tags, final Vec3i a, final Vec3i b, final int argb) {
        final IntBox box = IntBox.of(a, b);
        return new ZoneData(name, List.of(new ZoneBox(box.min(), box.max())), null, null, null, tags.isEmpty() ? null : tags,
                argb, 0.08f, true, true);
    }

    /** Returns a new polygon zone, for the zone tool's polygon mode. */
    public static ZoneData polygonZone(final String name, final List<String> tags, final List<Vec3i> vertices, final int minY,
            final int maxY, final int argb) {
        return new ZoneData(name, null, List.copyOf(vertices), minY, maxY, tags.isEmpty() ? null : tags, argb, 0.08f, true, true);
    }
}
