package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import java.util.List;

/**
 * Geometry for picks: region volumes, the axis the player looks down, and direction
 * snapping. Angles use Minecraft's convention: yaw 0 faces south (+Z) and grows clockwise seen from
 * above, pitch is positive looking down; both in degrees.
 */
public final class PickGeometry {

    /** Snap step for direction picks while Ctrl is held, in degrees. */
    public static final float SNAP_DEGREES = 15f;

    private static final String[] CARDINALS = {"south", "west", "north", "east"};

    private PickGeometry() {
    }

    /**
     * Returns the number of blocks in the box spanned by two corner blocks, both inclusive.
     *
     * @param a one corner
     * @param b the opposite corner
     * @return the volume, in blocks
     */
    public static long volume(final Vec3i a, final Vec3i b) {
        return ((long) Math.abs(a.x() - b.x()) + 1) * ((long) Math.abs(a.y() - b.y()) + 1) * ((long) Math.abs(a.z() - b.z()) + 1);
    }

    /**
     * Returns the component-wise minimum of two positions.
     *
     * @param a one position
     * @param b another position
     * @return the minimum corner
     */
    public static Vec3i min(final Vec3i a, final Vec3i b) {
        return new Vec3i(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()));
    }

    /**
     * Returns the component-wise maximum of two positions.
     *
     * @param a one position
     * @param b another position
     * @return the maximum corner
     */
    public static Vec3i max(final Vec3i a, final Vec3i b) {
        return new Vec3i(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }

    /**
     * Returns the unit step along the world axis that a look vector points down most.
     *
     * @param dx look vector x
     * @param dy look vector y
     * @param dz look vector z
     * @return a vector with one component of 1 or -1 and the others 0
     */
    public static Vec3i dominantAxis(final double dx, final double dy, final double dz) {
        final double ax = Math.abs(dx);
        final double ay = Math.abs(dy);
        final double az = Math.abs(dz);
        if (ax >= ay && ax >= az) {
            return new Vec3i(dx < 0 ? -1 : 1, 0, 0);
        }
        if (ay >= az) {
            return new Vec3i(0, dy < 0 ? -1 : 1, 0);
        }
        return new Vec3i(0, 0, dz < 0 ? -1 : 1);
    }

    /**
     * Moves a position by whole steps along a unit axis.
     *
     * @param p the position
     * @param axis a unit axis from {@link #dominantAxis}
     * @param steps how far, in blocks; negative moves backwards
     * @return the moved position
     */
    public static Vec3i offset(final Vec3i p, final Vec3i axis, final int steps) {
        return new Vec3i(p.x() + axis.x() * steps, p.y() + axis.y() * steps, p.z() + axis.z() * steps);
    }

    /**
     * Adds two positions.
     *
     * @param a one position
     * @param b another position
     * @return the sum
     */
    public static Vec3i add(final Vec3i a, final Vec3i b) {
        return new Vec3i(a.x() + b.x(), a.y() + b.y(), a.z() + b.z());
    }

    /**
     * Returns the lowest Y among polygon vertices.
     *
     * @param vertices at least one vertex
     * @return the minimum Y
     */
    public static int minY(final List<Vec3i> vertices) {
        int y = Integer.MAX_VALUE;
        for (final Vec3i v : vertices) {
            y = Math.min(y, v.y());
        }
        return y;
    }

    /**
     * Returns the highest Y among polygon vertices.
     *
     * @param vertices at least one vertex
     * @return the maximum Y
     */
    public static int maxY(final List<Vec3i> vertices) {
        int y = Integer.MIN_VALUE;
        for (final Vec3i v : vertices) {
            y = Math.max(y, v.y());
        }
        return y;
    }

    /**
     * Wraps an angle to {@code [-180, 180)}.
     *
     * @param degrees any angle
     * @return the wrapped angle
     */
    public static float wrapDegrees(final float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) {
            d -= 360f;
        }
        if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    /**
     * Rounds an angle to the nearest multiple of {@code step}.
     *
     * @param degrees the angle
     * @param step the step, positive
     * @return the snapped angle
     */
    public static float snap(final float degrees, final float step) {
        return Math.round(degrees / step) * step;
    }

    /**
     * Returns the cardinal direction nearest to a yaw.
     *
     * @param yaw the yaw in degrees, any range
     * @return {@code south}, {@code west}, {@code north} or {@code east}
     */
    public static String cardinal(final float yaw) {
        return CARDINALS[Math.floorMod(Math.round(yaw / 90f), 4)];
    }
}
