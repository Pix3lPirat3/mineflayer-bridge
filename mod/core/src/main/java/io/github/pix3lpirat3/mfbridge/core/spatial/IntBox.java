package io.github.pix3lpirat3.mfbridge.core.spatial;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An axis-aligned box of whole blocks, both corners inclusive, in world block coordinates. Every
 * maximum is at least the matching minimum.
 *
 * @param minX smallest block x
 * @param minY smallest block y
 * @param minZ smallest block z
 * @param maxX largest block x, inclusive
 * @param maxY largest block y, inclusive
 * @param maxZ largest block z, inclusive
 */
@AnyThread
public record IntBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    /**
     * Validates the corners.
     *
     * @throws IllegalArgumentException when a maximum is below its minimum
     */
    public IntBox {
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("box max below min");
        }
    }

    /** Returns the box spanned by two corner blocks given in any order. */
    public static IntBox of(Vec3i a, Vec3i b) {
        return new IntBox(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }

    /** Returns the smallest corner as a block position. */
    public Vec3i min() {
        return new Vec3i(minX, minY, minZ);
    }

    /** Returns the largest corner as a block position. */
    public Vec3i max() {
        return new Vec3i(maxX, maxY, maxZ);
    }

    /** Returns whether the block at a position is inside. */
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** Returns whether the two boxes share at least one block. */
    public boolean intersects(IntBox o) {
        return o.minX <= maxX && o.maxX >= minX && o.minY <= maxY && o.maxY >= minY && o.minZ <= maxZ && o.maxZ >= minZ;
    }

    /** Returns the shared blocks, or null when the boxes are disjoint. */
    public @Nullable IntBox intersection(IntBox o) {
        if (!intersects(o)) {
            return null;
        }
        return new IntBox(Math.max(minX, o.minX), Math.max(minY, o.minY), Math.max(minZ, o.minZ),
                Math.min(maxX, o.maxX), Math.min(maxY, o.maxY), Math.min(maxZ, o.maxZ));
    }

    /** Returns the smallest box containing both. */
    public IntBox union(IntBox o) {
        return new IntBox(Math.min(minX, o.minX), Math.min(minY, o.minY), Math.min(minZ, o.minZ),
                Math.max(maxX, o.maxX), Math.max(maxY, o.maxY), Math.max(maxZ, o.maxZ));
    }

    /** Returns the number of blocks inside. */
    public long volume() {
        return ((long) maxX - minX + 1) * ((long) maxY - minY + 1) * ((long) maxZ - minZ + 1);
    }

    /**
     * Returns this box minus another as at most six disjoint boxes: slabs below and above in y, then
     * in z, then in x, so the pieces never overlap.
     *
     * @return the remaining pieces; this box alone when they are disjoint, empty when {@code o} covers it
     */
    public List<IntBox> subtract(IntBox o) {
        IntBox cut = intersection(o);
        if (cut == null) {
            return List.of(this);
        }
        List<IntBox> out = new ArrayList<>(6);
        if (minY < cut.minY) {
            out.add(new IntBox(minX, minY, minZ, maxX, cut.minY - 1, maxZ));
        }
        if (maxY > cut.maxY) {
            out.add(new IntBox(minX, cut.maxY + 1, minZ, maxX, maxY, maxZ));
        }
        if (minZ < cut.minZ) {
            out.add(new IntBox(minX, cut.minY, minZ, maxX, cut.maxY, cut.minZ - 1));
        }
        if (maxZ > cut.maxZ) {
            out.add(new IntBox(minX, cut.minY, cut.maxZ + 1, maxX, cut.maxY, maxZ));
        }
        if (minX < cut.minX) {
            out.add(new IntBox(minX, cut.minY, cut.minZ, cut.minX - 1, cut.maxY, cut.maxZ));
        }
        if (maxX > cut.maxX) {
            out.add(new IntBox(cut.maxX + 1, cut.minY, cut.minZ, maxX, cut.maxY, cut.maxZ));
        }
        return out;
    }

    /**
     * Returns the squared distance from a point to the nearest point of the box's volume, which spans
     * {@code [min, max + 1)} in continuous coordinates; 0 inside.
     */
    public double distanceSq(double x, double y, double z) {
        double dx = Math.max(Math.max(minX - x, 0), x - (maxX + 1d));
        double dy = Math.max(Math.max(minY - y, 0), y - (maxY + 1d));
        double dz = Math.max(Math.max(minZ - z, 0), z - (maxZ + 1d));
        return dx * dx + dy * dy + dz * dz;
    }
}
