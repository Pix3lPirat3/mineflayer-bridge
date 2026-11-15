package io.github.pix3lpirat3.mfbridge.core.spatial;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import io.github.pix3lpirat3.mfbridge.protocol.core.ZoneBox;
import io.github.pix3lpirat3.mfbridge.protocol.core.ZoneData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The evaluated geometry of a {@code core:zone}, in whole blocks.
 *
 * <ul>
 *   <li><b>Boxes:</b> the boxes are applied in list order to an initially empty set: {@code add}
 *       unites, {@code subtract} removes and {@code intersect} keeps only the blocks inside the box.
 *       An unknown op counts as {@code add}. The result is kept as disjoint boxes.
 *   <li><b>Polygon prism:</b> used when the zone has no boxes and at least three polygon vertices.
 *       The outline runs through the vertices' x and z as given; a block is inside when its centre
 *       {@code (x + 0.5, z + 0.5)} is inside the outline by the even-odd rule and its y is within
 *       {@code minY..maxY} (inclusive). A missing bound means no limit on that side.
 * </ul>
 *
 * <p>These rules match the Node plugin's queries, so outlines drawn in game agree with what bots
 * compute.
 */
@AnyThread
public sealed interface ZoneShape permits ZoneShape.Boxes, ZoneShape.Prism, ZoneShape.Empty {

    /** Largest number of boxes a zone may list; later boxes are ignored. */
    int MAX_BOXES = 256;

    /** Largest number of disjoint pieces kept while evaluating; further ops are ignored. */
    int MAX_PIECES = 16_384;

    /** Evaluates a zone's data. */
    static ZoneShape of(ZoneData zone) {
        List<ZoneBox> boxes = zone.boxes();
        if (boxes != null && !boxes.isEmpty()) {
            List<IntBox> parts = resolve(boxes);
            return parts.isEmpty() ? Empty.INSTANCE : new Boxes(parts);
        }
        List<Vec3i> polygon = zone.polygon();
        if (polygon != null && polygon.size() >= 3) {
            int n = polygon.size();
            int[] xs = new int[n];
            int[] zs = new int[n];
            for (int i = 0; i < n; i++) {
                Vec3i v = polygon.get(i);
                xs[i] = v.x();
                zs[i] = v.z();
            }
            Integer minY = zone.minY();
            Integer maxY = zone.maxY();
            int y0 = minY != null ? minY : Integer.MIN_VALUE;
            int y1 = maxY != null ? maxY : Integer.MAX_VALUE;
            int[][] ring = Polygons.clean(xs, zs);
            if (ring[0].length < 3 || y1 < y0) {
                return Empty.INSTANCE;
            }
            return new Prism(ring[0], ring[1], y0, y1);
        }
        return Empty.INSTANCE;
    }

    /**
     * Applies box ops in order (see the type comment).
     *
     * @return disjoint boxes covering exactly the resulting blocks
     */
    static List<IntBox> resolve(List<ZoneBox> boxes) {
        List<IntBox> acc = new ArrayList<>();
        int count = Math.min(boxes.size(), MAX_BOXES);
        for (int i = 0; i < count; i++) {
            ZoneBox zb = boxes.get(i);
            IntBox b = IntBox.of(zb.min(), zb.max());
            List<IntBox> next = switch (zb.op().toLowerCase(Locale.ROOT)) {
                case "subtract" -> subtractAll(acc, b);
                case "intersect" -> intersectAll(acc, b);
                default -> addDisjoint(acc, b);
            };
            if (next.size() > MAX_PIECES) {
                break;
            }
            acc = next;
        }
        return List.copyOf(acc);
    }

    private static List<IntBox> subtractAll(List<IntBox> acc, IntBox b) {
        List<IntBox> out = new ArrayList<>(acc.size() + 4);
        for (IntBox a : acc) {
            out.addAll(a.subtract(b));
        }
        return out;
    }

    private static List<IntBox> intersectAll(List<IntBox> acc, IntBox b) {
        List<IntBox> out = new ArrayList<>(acc.size());
        for (IntBox a : acc) {
            IntBox cut = a.intersection(b);
            if (cut != null) {
                out.add(cut);
            }
        }
        return out;
    }

    private static List<IntBox> addDisjoint(List<IntBox> acc, IntBox b) {
        List<IntBox> pieces = new ArrayList<>(List.of(b));
        for (IntBox a : acc) {
            if (!a.intersects(b)) {
                continue;
            }
            List<IntBox> rest = new ArrayList<>(pieces.size());
            for (IntBox p : pieces) {
                rest.addAll(p.subtract(a));
            }
            pieces = rest;
            if (pieces.isEmpty()) {
                break;
            }
        }
        List<IntBox> out = new ArrayList<>(acc.size() + pieces.size());
        out.addAll(acc);
        out.addAll(pieces);
        return out;
    }

    /** Returns whether the block at a position is in the zone. */
    boolean contains(int x, int y, int z);

    /** Returns whether the block containing a continuous position is in the zone. */
    default boolean contains(double x, double y, double z) {
        return contains((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    /**
     * Returns whether the zone shares a block with a box. Prisms test the rectangle through the
     * box's outer block centres against the outline, which can count a box whose blocks only straddle
     * a diagonal edge.
     */
    boolean intersects(IntBox box);

    /** Returns the bounding box of the zone's blocks, or null when it is empty. */
    @Nullable IntBox bounds();

    /**
     * Disjoint boxes, the result of the zone's box ops.
     *
     * @param parts at least one disjoint box
     */
    record Boxes(List<IntBox> parts) implements ZoneShape {
        /** Copies the parts. */
        public Boxes {
            parts = List.copyOf(parts);
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("no parts");
            }
        }

        @Override
        public boolean contains(int x, int y, int z) {
            for (IntBox b : parts) {
                if (b.contains(x, y, z)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean intersects(IntBox box) {
            for (IntBox b : parts) {
                if (b.intersects(box)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public IntBox bounds() {
            IntBox out = parts.getFirst();
            for (IntBox b : parts) {
                out = out.union(b);
            }
            return out;
        }

        /** Returns the number of blocks in the zone. */
        public long volume() {
            long sum = 0;
            for (IntBox b : parts) {
                sum += b.volume();
            }
            return sum;
        }
    }

    /**
     * A vertical prism over a polygon.
     *
     * @param xs vertex x, at least three, cleaned of repeats and collinear vertices
     * @param zs vertex z, parallel to {@code xs}
     * @param minY lowest block y, inclusive; {@link Integer#MIN_VALUE} for no limit
     * @param maxY highest block y, inclusive; {@link Integer#MAX_VALUE} for no limit
     */
    record Prism(int[] xs, int[] zs, int minY, int maxY) implements ZoneShape {
        /** Validates the ring. */
        public Prism {
            if (xs.length < 3 || xs.length != zs.length || maxY < minY) {
                throw new IllegalArgumentException("degenerate prism");
            }
            xs = xs.clone();
            zs = zs.clone();
        }

        @Override
        public int[] xs() {
            return xs.clone();
        }

        @Override
        public int[] zs() {
            return zs.clone();
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return y >= minY && y <= maxY && Polygons.contains(xs, zs, x + 0.5, z + 0.5);
        }

        @Override
        public boolean intersects(IntBox box) {
            if (box.maxY() < minY || box.minY() > maxY) {
                return false;
            }
            return Polygons.intersectsRect(xs, zs, box.minX() + 0.5, box.minZ() + 0.5, box.maxX() + 0.5, box.maxZ() + 0.5);
        }

        @Override
        public IntBox bounds() {
            int x0 = Integer.MAX_VALUE;
            int z0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE;
            int z1 = Integer.MIN_VALUE;
            for (int i = 0; i < xs.length; i++) {
                x0 = Math.min(x0, xs[i]);
                x1 = Math.max(x1, xs[i]);
                z0 = Math.min(z0, zs[i]);
                z1 = Math.max(z1, zs[i]);
            }
            return new IntBox(x0, minY, z0, Math.max(x0, x1 - 1), maxY, Math.max(z0, z1 - 1));
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof Prism p && minY == p.minY && maxY == p.maxY && Arrays.equals(xs, p.xs)
                    && Arrays.equals(zs, p.zs);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * Arrays.hashCode(xs) + Arrays.hashCode(zs)) + minY * 7 + maxY;
        }

        @Override
        public String toString() {
            return "Prism[" + xs.length + " vertices, y " + minY + ".." + maxY + "]";
        }
    }

    /** A zone without blocks: no boxes survive the ops, or the polygon is degenerate. */
    enum Empty implements ZoneShape {
        /** The only instance. */
        INSTANCE;

        @Override
        public boolean contains(int x, int y, int z) {
            return false;
        }

        @Override
        public boolean intersects(IntBox box) {
            return false;
        }

        @Override
        public @Nullable IntBox bounds() {
            return null;
        }
    }
}
