package io.github.pix3lpirat3.mfbridge.core.spatial;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.core.RenderEntry;
import io.github.pix3lpirat3.mfbridge.core.RenderSnapshot;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.PointData;
import io.github.pix3lpirat3.mfbridge.protocol.core.RouteData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ZoneData;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An immutable index over the zones, points and routes the player can see:
 * shared ones, the player's own and those of attached bots in the player's world, with
 * layers honoured, exactly the {@code core:zone}, {@code core:point} and {@code core:route} entries of
 * one render snapshot. Bounds live in a small bounding volume hierarchy, so queries touch only nearby
 * objects. Safe to share across threads.
 */
@AnyThread
public final class SpatialIndex {
    /** An index with no objects. */
    public static final SpatialIndex EMPTY = new SpatialIndex(-1, List.of());

    private static final int LEAF = 4;

    /** The spatial kinds. */
    public enum Kind {
        /** {@code core:zone}. */
        ZONE,
        /** {@code core:point}. */
        POINT,
        /** {@code core:route}. */
        ROUTE
    }

    /**
     * One indexed object.
     *
     * @param key full object key
     * @param owner owner without trailing slash: {@code shared}, {@code player} or a bot owner
     * @param kind which spatial kind
     * @param rev object revision
     * @param name the object's name
     * @param tags the object's tags, possibly empty
     * @param editable whether the data allows gizmo editing
     * @param data the decoded {@link ZoneData}, {@link PointData} or {@link RouteData}
     * @param zone the evaluated zone geometry, or null for points and routes
     * @param polyline the interpolated route, x y z triples, or null for zones and points
     * @param minX bounds minimum x, continuous world coordinates
     * @param minY bounds minimum y
     * @param minZ bounds minimum z
     * @param maxX bounds maximum x
     * @param maxY bounds maximum y
     * @param maxZ bounds maximum z
     */
    public record Item(String key, String owner, Kind kind, long rev, String name, List<String> tags, boolean editable,
            Object data, @Nullable ZoneShape zone, double @Nullable [] polyline, double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {

        /** Returns the squared distance from a point to the object: its blocks, position or route line. */
        public double distanceSq(double x, double y, double z) {
            return switch (kind) {
                case POINT -> {
                    PointData p = (PointData) data;
                    double dx = p.pos().x() - x;
                    double dy = p.pos().y() - y;
                    double dz = p.pos().z() - z;
                    yield dx * dx + dy * dy + dz * dz;
                }
                case ZONE -> {
                    ZoneShape s = zone;
                    if (s != null && s.contains(x, y, z)) {
                        yield 0;
                    }
                    yield boxDistanceSq(x, y, z);
                }
                case ROUTE -> polylineDistanceSq(polyline, x, y, z);
            };
        }

        private double boxDistanceSq(double x, double y, double z) {
            double dx = Math.max(Math.max(minX - x, 0), x - maxX);
            double dy = Math.max(Math.max(minY - y, 0), y - maxY);
            double dz = Math.max(Math.max(minZ - z, 0), z - maxZ);
            return dx * dx + dy * dy + dz * dz;
        }

        boolean overlaps(double x0, double y0, double z0, double x1, double y1, double z1) {
            return minX <= x1 && maxX >= x0 && minY <= y1 && maxY >= y0 && minZ <= z1 && maxZ >= z0;
        }
    }

    private record Node(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int left,
            int right, int start, int end) {}

    private final long version;
    private final List<Item> items;
    private final Map<String, Item> byKey;
    private final List<Node> nodes;
    private final int root;

    private SpatialIndex(long version, List<Item> items) {
        this.version = version;
        List<Item> sorted = new ArrayList<>(items);
        this.nodes = new ArrayList<>();
        this.root = sorted.isEmpty() ? -1 : build(sorted, 0, sorted.size());
        this.items = List.copyOf(sorted);
        Map<String, Item> keys = new HashMap<>();
        for (Item i : this.items) {
            keys.put(i.key(), i);
        }
        this.byKey = Map.copyOf(keys);
    }

    /**
     * Builds an index from render snapshot entries; entries of other kinds are ignored.
     *
     * @param version the snapshot version, reported by {@link #version()}
     */
    public static SpatialIndex build(long version, List<RenderEntry> entries) {
        List<Item> items = new ArrayList<>();
        for (RenderEntry e : entries) {
            Item item = item(e);
            if (item != null) {
                items.add(item);
            }
        }
        return new SpatialIndex(version, items);
    }

    /** Returns whether a change of the render snapshot adds, alters or removes a zone, point or route. */
    public static boolean touches(RenderSnapshot.Change change) {
        for (RenderEntry e : change.upserted()) {
            if (spatial(e.kind())) {
                return true;
            }
        }
        for (RenderEntry e : change.removed()) {
            if (spatial(e.kind())) {
                return true;
            }
        }
        return false;
    }

    private static boolean spatial(KindType<?> kind) {
        return kind == CoreKinds.ZONE || kind == CoreKinds.POINT || kind == CoreKinds.ROUTE;
    }

    /** Returns the indexed form of one entry, or null when it is not a spatial definition or is empty. */
    public static @Nullable Item item(RenderEntry e) {
        KindType<?> k = e.kind();
        return switch (e.data()) {
            case ZoneData z when k == CoreKinds.ZONE -> {
                ZoneShape shape = ZoneShape.of(z);
                IntBox b = shape.bounds();
                if (b == null) {
                    yield null;
                }
                yield new Item(e.key(), e.owner(), Kind.ZONE, e.rev(), z.name(), tags(z.tags()), z.editable(), z, shape,
                        null, b.minX(), b.minY(), b.minZ(), b.maxX() + 1d, b.maxY() + 1d, b.maxZ() + 1d);
            }
            case PointData p when k == CoreKinds.POINT -> new Item(e.key(), e.owner(), Kind.POINT, e.rev(), p.name(),
                    tags(p.tags()), p.editable(), p, null, null, p.pos().x(), p.pos().y(), p.pos().z(), p.pos().x(),
                    p.pos().y(), p.pos().z());
            case RouteData r when k == CoreKinds.ROUTE -> {
                double[] line = RouteGeometry.polyline(r);
                if (line.length < 3) {
                    yield null;
                }
                double[] b = bounds(line);
                yield new Item(e.key(), e.owner(), Kind.ROUTE, e.rev(), r.name(), tags(r.tags()), r.editable(), r, null,
                        line, b[0], b[1], b[2], b[3], b[4], b[5]);
            }
            case null, default -> null;
        };
    }

    private static List<String> tags(@Nullable List<String> tags) {
        return tags == null ? List.of() : List.copyOf(tags);
    }

    private static double[] bounds(double[] xyz) {
        double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int i = 0; i + 2 < xyz.length; i += 3) {
            for (int c = 0; c < 3; c++) {
                b[c] = Math.min(b[c], xyz[i + c]);
                b[c + 3] = Math.max(b[c + 3], xyz[i + c]);
            }
        }
        return b;
    }

    static double polylineDistanceSq(double @Nullable [] p, double x, double y, double z) {
        if (p == null || p.length < 3) {
            return Double.POSITIVE_INFINITY;
        }
        double best = Double.POSITIVE_INFINITY;
        if (p.length == 3) {
            double dx = p[0] - x;
            double dy = p[1] - y;
            double dz = p[2] - z;
            return dx * dx + dy * dy + dz * dz;
        }
        for (int i = 0; i + 5 < p.length; i += 3) {
            double ax = p[i];
            double ay = p[i + 1];
            double az = p[i + 2];
            double sx = p[i + 3] - ax;
            double sy = p[i + 4] - ay;
            double sz = p[i + 5] - az;
            double len2 = sx * sx + sy * sy + sz * sz;
            double t = len2 == 0 ? 0 : Math.clamp(((x - ax) * sx + (y - ay) * sy + (z - az) * sz) / len2, 0, 1);
            double dx = ax + sx * t - x;
            double dy = ay + sy * t - y;
            double dz = az + sz * t - z;
            best = Math.min(best, dx * dx + dy * dy + dz * dz);
        }
        return best;
    }

    private int build(List<Item> list, int start, int end) {
        double x0 = Double.POSITIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY;
        double z0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double y1 = Double.NEGATIVE_INFINITY;
        double z1 = Double.NEGATIVE_INFINITY;
        for (int i = start; i < end; i++) {
            Item it = list.get(i);
            x0 = Math.min(x0, it.minX());
            y0 = Math.min(y0, it.minY());
            z0 = Math.min(z0, it.minZ());
            x1 = Math.max(x1, it.maxX());
            y1 = Math.max(y1, it.maxY());
            z1 = Math.max(z1, it.maxZ());
        }
        if (end - start <= LEAF) {
            nodes.add(new Node(x0, y0, z0, x1, y1, z1, -1, -1, start, end));
            return nodes.size() - 1;
        }
        double ex = x1 - x0;
        double ey = y1 - y0;
        double ez = z1 - z0;
        Comparator<Item> order = ex >= ey && ex >= ez ? Comparator.comparingDouble(i -> i.minX() + i.maxX())
                : ey >= ez ? Comparator.comparingDouble(i -> i.minY() + i.maxY())
                : Comparator.comparingDouble(i -> i.minZ() + i.maxZ());
        list.subList(start, end).sort(order);
        int mid = (start + end) >>> 1;
        int left = build(list, start, mid);
        int right = build(list, mid, end);
        nodes.add(new Node(x0, y0, z0, x1, y1, z1, left, right, start, end));
        return nodes.size() - 1;
    }

    private interface Visitor {
        void visit(Item item);
    }

    private void query(double x0, double y0, double z0, double x1, double y1, double z1, Visitor v) {
        if (root < 0) {
            return;
        }
        int[] stack = new int[64];
        int top = 0;
        stack[top++] = root;
        while (top > 0) {
            Node n = nodes.get(stack[--top]);
            if (n.minX > x1 || n.maxX < x0 || n.minY > y1 || n.maxY < y0 || n.minZ > z1 || n.maxZ < z0) {
                continue;
            }
            if (n.left < 0) {
                for (int i = n.start; i < n.end; i++) {
                    Item it = items.get(i);
                    if (it.overlaps(x0, y0, z0, x1, y1, z1)) {
                        v.visit(it);
                    }
                }
                continue;
            }
            if (top + 2 > stack.length) {
                stack = Arrays.copyOf(stack, stack.length * 2);
            }
            stack[top++] = n.left;
            stack[top++] = n.right;
        }
    }

    /** Returns the render snapshot version this index was built from, or -1 for {@link #EMPTY}. */
    public long version() {
        return version;
    }

    /** Returns every indexed object, in no particular order. Immutable. */
    public List<Item> all() {
        return items;
    }

    /** Returns the object with a key, or null. */
    public @Nullable Item get(String key) {
        return byKey.get(key);
    }

    /** Returns the zones that contain the block at a continuous position. */
    public List<Item> containing(double x, double y, double z) {
        List<Item> out = new ArrayList<>();
        query(x, y, z, x, y, z, it -> {
            ZoneShape s = it.zone();
            if (s != null && s.contains(x, y, z)) {
                out.add(it);
            }
        });
        return out;
    }

    /**
     * Returns the objects inside or touching a block box: zones sharing a block with it, points in
     * one of its blocks and routes whose line bounds overlap it.
     */
    public List<Item> intersecting(IntBox box) {
        List<Item> out = new ArrayList<>();
        double x1 = box.maxX() + 1d;
        double y1 = box.maxY() + 1d;
        double z1 = box.maxZ() + 1d;
        query(box.minX(), box.minY(), box.minZ(), x1, y1, z1, it -> {
            ZoneShape s = it.zone();
            boolean hit = switch (it.kind()) {
                case ZONE -> s != null && s.intersects(box);
                case POINT -> it.minX() >= box.minX() && it.minX() < x1 && it.minY() >= box.minY() && it.minY() < y1
                        && it.minZ() >= box.minZ() && it.minZ() < z1;
                case ROUTE -> true;
            };
            if (hit) {
                out.add(it);
            }
        });
        return out;
    }

    /** Returns the objects carrying a tag, compared exactly. */
    public List<Item> byTag(String tag) {
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            if (it.tags().contains(tag)) {
                out.add(it);
            }
        }
        return out;
    }

    /** Returns the objects of one kind. */
    public List<Item> ofKind(Kind kind) {
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            if (it.kind() == kind) {
                out.add(it);
            }
        }
        return out;
    }

    /**
     * Returns the object nearest to a position.
     *
     * @param kind the kind to look for, or null for any
     * @param maxDistance search radius in blocks
     * @return the nearest object within the radius, or null
     */
    public @Nullable Item nearest(double x, double y, double z, @Nullable Kind kind, double maxDistance) {
        @Nullable Item[] best = {null};
        double[] bestD = {maxDistance * maxDistance};
        query(x - maxDistance, y - maxDistance, z - maxDistance, x + maxDistance, y + maxDistance, z + maxDistance, it -> {
            if (kind != null && it.kind() != kind) {
                return;
            }
            double d = it.distanceSq(x, y, z);
            if (d <= bestD[0]) {
                bestD[0] = d;
                best[0] = it;
            }
        });
        return best[0];
    }
}
