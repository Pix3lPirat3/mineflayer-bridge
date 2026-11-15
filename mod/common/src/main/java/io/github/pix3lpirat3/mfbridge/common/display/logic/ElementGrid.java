package io.github.pix3lpirat3.mfbridge.common.display.logic;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;

/**
 * Hit testing for interactive elements: oriented hit boxes are
 * bucketed into a grid of chunk-sized cells, and a ray walks only the cells it crosses. Each element
 * is an oriented box (centre, rotation, half extents) plus an anchor, the element's own position, so
 * hits can be reported in element-local space.
 *
 * <p>Rebuilding with {@link #clear} and {@link #add} reuses every buffer, so a steady scene allocates
 * nothing. Confined to one thread.
 */
public final class ElementGrid {
    /** Cell edge length, blocks: one chunk. */
    public static final int CELL = 16;
    private static final int STRIDE = 13;
    private static final int MAX_CELLS = 4096;

    private final Long2ObjectOpenHashMap<IntArrayList> cells = new Long2ObjectOpenHashMap<>();
    private double[] boxes = new double[STRIDE * 64];
    private int[] seen = new int[64];
    private int count;
    private int stamp;

    private int hitId = -1;
    private double hitDistance = Double.POSITIVE_INFINITY;
    private double hitX;
    private double hitY;
    private double hitZ;
    private double localX;
    private double localY;
    private double localZ;

    /** Creates an empty grid. */
    public ElementGrid() {
    }

    /** Removes every element, keeping the buffers. */
    public void clear() {
        if (cells.size() > MAX_CELLS) {
            cells.clear();
        } else {
            for (final IntArrayList list : cells.values()) {
                list.clear();
            }
        }
        count = 0;
    }

    /**
     * Returns the number of elements added since the last {@link #clear}.
     *
     * @return the element count
     */
    public int size() {
        return count;
    }

    /**
     * Adds an element. Its id is the number of elements added before it since the last {@link #clear}.
     *
     * @param cx box centre, world blocks
     * @param cy box centre, world blocks
     * @param cz box centre, world blocks
     * @param q rotation of the box as a unit quaternion {@code x, y, z, w}
     * @param hx half extent along the box's own x axis, blocks
     * @param hy half extent along the box's own y axis, blocks
     * @param hz half extent along the box's own z axis, blocks
     * @param ax the element's position relative to the box centre, in the box's rotated frame
     * @param ay see {@code ax}
     * @param az see {@code ax}
     * @return the element id
     */
    public int add(final double cx, final double cy, final double cz, final double[] q, final double hx, final double hy,
            final double hz, final double ax, final double ay, final double az) {
        final int id = count++;
        if (seen.length < count) {
            boxes = Arrays.copyOf(boxes, boxes.length * 2);
            seen = Arrays.copyOf(seen, seen.length * 2);
        }
        final int o = id * STRIDE;
        boxes[o] = cx;
        boxes[o + 1] = cy;
        boxes[o + 2] = cz;
        boxes[o + 3] = q[0];
        boxes[o + 4] = q[1];
        boxes[o + 5] = q[2];
        boxes[o + 6] = q[3];
        boxes[o + 7] = Math.abs(hx);
        boxes[o + 8] = Math.abs(hy);
        boxes[o + 9] = Math.abs(hz);
        boxes[o + 10] = ax;
        boxes[o + 11] = ay;
        boxes[o + 12] = az;
        seen[id] = 0;
        // The bounding sphere of the oriented box is a cheap world AABB that holds for any rotation.
        final double r = Math.sqrt(hx * hx + hy * hy + hz * hz);
        final int x0 = cell(cx - r);
        final int x1 = cell(cx + r);
        final int y0 = cell(cy - r);
        final int y1 = cell(cy + r);
        final int z0 = cell(cz - r);
        final int z1 = cell(cz + r);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    final long k = key(x, y, z);
                    IntArrayList list = cells.get(k);
                    if (list == null) {
                        list = new IntArrayList(4);
                        cells.put(k, list);
                    }
                    list.add(id);
                }
            }
        }
        return id;
    }

    /**
     * Casts a ray and keeps the nearest hit, readable through the accessors.
     *
     * @param ox ray origin, world blocks
     * @param oy ray origin, world blocks
     * @param oz ray origin, world blocks
     * @param dx ray direction; need not be normalised
     * @param dy ray direction
     * @param dz ray direction
     * @param maxDistance longest distance to test, blocks
     * @return the id of the nearest element hit, or -1
     */
    public int raycast(final double ox, final double oy, final double oz, final double dx, final double dy, final double dz,
            final double maxDistance) {
        hitId = -1;
        hitDistance = Double.POSITIVE_INFINITY;
        final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (count == 0 || !(len > 0d) || !(maxDistance > 0d)) {
            return -1;
        }
        final double ux = dx / len;
        final double uy = dy / len;
        final double uz = dz / len;
        stamp++;
        if (stamp == 0) {
            Arrays.fill(seen, 0);
            stamp = 1;
        }
        int x = cell(ox);
        int y = cell(oy);
        int z = cell(oz);
        final int stepX = ux > 0 ? 1 : -1;
        final int stepY = uy > 0 ? 1 : -1;
        final int stepZ = uz > 0 ? 1 : -1;
        final double deltaX = ux == 0 ? Double.POSITIVE_INFINITY : CELL / Math.abs(ux);
        final double deltaY = uy == 0 ? Double.POSITIVE_INFINITY : CELL / Math.abs(uy);
        final double deltaZ = uz == 0 ? Double.POSITIVE_INFINITY : CELL / Math.abs(uz);
        double nextX = boundary(ox, ux, x);
        double nextY = boundary(oy, uy, y);
        double nextZ = boundary(oz, uz, z);
        double t = 0d;
        // Amanatides and Woo voxel walk; it stops once the nearest hit is closer than the next cell.
        while (t <= maxDistance) {
            final IntArrayList list = cells.get(key(x, y, z));
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    test(list.getInt(i), ox, oy, oz, ux, uy, uz, maxDistance);
                }
            }
            final double exit = Math.min(nextX, Math.min(nextY, nextZ));
            if (hitId >= 0 && hitDistance <= exit) {
                break;
            }
            t = exit;
            if (nextX <= nextY && nextX <= nextZ) {
                x += stepX;
                nextX += deltaX;
            } else if (nextY <= nextZ) {
                y += stepY;
                nextY += deltaY;
            } else {
                z += stepZ;
                nextZ += deltaZ;
            }
        }
        return hitId;
    }

    private void test(final int id, final double ox, final double oy, final double oz, final double ux, final double uy,
            final double uz, final double maxDistance) {
        if (seen[id] == stamp) {
            return;
        }
        seen[id] = stamp;
        final int o = id * STRIDE;
        final double qx = -boxes[o + 3];
        final double qy = -boxes[o + 4];
        final double qz = -boxes[o + 5];
        final double qw = boxes[o + 6];
        final double rx = ox - boxes[o];
        final double ry = oy - boxes[o + 1];
        final double rz = oz - boxes[o + 2];
        // Into the box frame by the conjugate rotation: v' = v + 2w(q x v) + 2 q x (q x v).
        final double ax = qy * rz - qz * ry;
        final double ay = qz * rx - qx * rz;
        final double az = qx * ry - qy * rx;
        final double lox = rx + 2 * (qw * ax + qy * az - qz * ay);
        final double loy = ry + 2 * (qw * ay + qz * ax - qx * az);
        final double loz = rz + 2 * (qw * az + qx * ay - qy * ax);
        final double bx = qy * uz - qz * uy;
        final double by = qz * ux - qx * uz;
        final double bz = qx * uy - qy * ux;
        final double ldx = ux + 2 * (qw * bx + qy * bz - qz * by);
        final double ldy = uy + 2 * (qw * by + qz * bx - qx * bz);
        final double ldz = uz + 2 * (qw * bz + qx * by - qy * bx);
        final double t = rayBox(lox, loy, loz, ldx, ldy, ldz, boxes[o + 7], boxes[o + 8], boxes[o + 9]);
        if (t <= maxDistance && t < hitDistance) {
            hitId = id;
            hitDistance = t;
            hitX = ox + ux * t;
            hitY = oy + uy * t;
            hitZ = oz + uz * t;
            localX = lox + ldx * t - boxes[o + 10];
            localY = loy + ldy * t - boxes[o + 11];
            localZ = loz + ldz * t - boxes[o + 12];
        }
    }

    /**
     * Slab test of a ray against a box centred on the origin.
     *
     * @return the entry distance along the ray, 0 when the origin is inside, or positive infinity on a miss
     */
    static double rayBox(final double ox, final double oy, final double oz, final double dx, final double dy, final double dz,
            final double hx, final double hy, final double hz) {
        double near = 0d;
        double far = Double.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            final double o = axis == 0 ? ox : axis == 1 ? oy : oz;
            final double d = axis == 0 ? dx : axis == 1 ? dy : dz;
            final double h = axis == 0 ? hx : axis == 1 ? hy : hz;
            if (Math.abs(d) < 1e-12) {
                if (o < -h || o > h) {
                    return Double.POSITIVE_INFINITY;
                }
                continue;
            }
            final double t0 = (-h - o) / d;
            final double t1 = (h - o) / d;
            near = Math.max(near, Math.min(t0, t1));
            far = Math.min(far, Math.max(t0, t1));
            if (near > far) {
                return Double.POSITIVE_INFINITY;
            }
        }
        return near;
    }

    /**
     * Returns the distance to the last hit.
     *
     * @return blocks, or positive infinity after a miss
     */
    public double hitDistance() {
        return hitDistance;
    }

    /**
     * Returns the last hit point.
     *
     * @return world x, blocks
     */
    public double hitX() {
        return hitX;
    }

    /**
     * Returns the last hit point.
     *
     * @return world y, blocks
     */
    public double hitY() {
        return hitY;
    }

    /**
     * Returns the last hit point.
     *
     * @return world z, blocks
     */
    public double hitZ() {
        return hitZ;
    }

    /**
     * Returns the last hit point relative to the element's anchor, in its rotated frame.
     *
     * @return local x, blocks
     */
    public double localX() {
        return localX;
    }

    /**
     * Returns the last hit point relative to the element's anchor, in its rotated frame.
     *
     * @return local y, blocks
     */
    public double localY() {
        return localY;
    }

    /**
     * Returns the last hit point relative to the element's anchor, in its rotated frame.
     *
     * @return local z, blocks
     */
    public double localZ() {
        return localZ;
    }

    private static int cell(final double v) {
        return Math.floorDiv((int) Math.floor(v), CELL);
    }

    private static double boundary(final double origin, final double dir, final int cell) {
        if (dir == 0) {
            return Double.POSITIVE_INFINITY;
        }
        final double edge = dir > 0 ? (cell + 1) * (double) CELL : cell * (double) CELL;
        return (edge - origin) / dir;
    }

    private static long key(final int x, final int y, final int z) {
        return ((long) x & 0x1FFFFFL) << 42 | ((long) y & 0x1FFFFFL) << 21 | (long) z & 0x1FFFFFL;
    }
}
