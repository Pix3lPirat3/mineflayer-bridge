package io.github.pix3lpirat3.mfbridge.common.display.logic;

/**
 * The interpolated part of a display: position, offset, rotation and scale ({@code
 * transform} and {@code interpolate}). Positions are in blocks, world space or relative to the parent
 * group; the rotation is a unit quaternion {@code x, y, z, w} applied after the offset. Mutable and
 * reused, so interpolation allocates nothing. Confined to one thread.
 */
public final class DisplayPose {
    /** Position, blocks. */
    public double x;
    /** Position, blocks. */
    public double y;
    /** Position, blocks. */
    public double z;
    /** Offset along the parent axes, blocks. */
    public double ox;
    /** Offset along the parent axes, blocks. */
    public double oy;
    /** Offset along the parent axes, blocks. */
    public double oz;
    /** Rotation quaternion. */
    public double qx;
    /** Rotation quaternion. */
    public double qy;
    /** Rotation quaternion. */
    public double qz;
    /** Rotation quaternion. */
    public double qw = 1d;
    /** Scale per axis. */
    public double sx = 1d;
    /** Scale per axis. */
    public double sy = 1d;
    /** Scale per axis. */
    public double sz = 1d;

    /** Creates the identity pose at the origin. */
    public DisplayPose() {
    }

    /**
     * Sets every component. The rotation is normalised; a zero or non-finite quaternion becomes the
     * identity.
     *
     * @param pos position {@code x, y, z}
     * @param offset offset {@code x, y, z}
     * @param rotation quaternion {@code x, y, z, w}, or null for none
     * @param scale scale {@code x, y, z}
     */
    public void set(final double[] pos, final double[] offset, final float[] rotation, final double[] scale) {
        x = pos[0];
        y = pos[1];
        z = pos[2];
        ox = offset[0];
        oy = offset[1];
        oz = offset[2];
        if (rotation.length == 4) {
            qx = rotation[0];
            qy = rotation[1];
            qz = rotation[2];
            qw = rotation[3];
        } else {
            qx = 0d;
            qy = 0d;
            qz = 0d;
            qw = 1d;
        }
        normalize();
        sx = scale[0];
        sy = scale[1];
        sz = scale[2];
    }

    /**
     * Copies another pose.
     *
     * @param other the pose to copy
     */
    public void copy(final DisplayPose other) {
        x = other.x;
        y = other.y;
        z = other.z;
        ox = other.ox;
        oy = other.oy;
        oz = other.oz;
        qx = other.qx;
        qy = other.qy;
        qz = other.qz;
        qw = other.qw;
        sx = other.sx;
        sy = other.sy;
        sz = other.sz;
    }

    /**
     * Sets this pose between two others: linear for position, offset and scale, spherical for the
     * rotation along the shorter arc.
     *
     * @param from the pose at {@code t = 0}
     * @param to the pose at {@code t = 1}
     * @param t progress, clamped to {@code [0, 1]}
     */
    public void lerp(final DisplayPose from, final DisplayPose to, final double t) {
        final double p = clamp01(t);
        x = mix(from.x, to.x, p);
        y = mix(from.y, to.y, p);
        z = mix(from.z, to.z, p);
        ox = mix(from.ox, to.ox, p);
        oy = mix(from.oy, to.oy, p);
        oz = mix(from.oz, to.oz, p);
        sx = mix(from.sx, to.sx, p);
        sy = mix(from.sy, to.sy, p);
        sz = mix(from.sz, to.sz, p);
        double bx = to.qx;
        double by = to.qy;
        double bz = to.qz;
        double bw = to.qw;
        double dot = from.qx * bx + from.qy * by + from.qz * bz + from.qw * bw;
        if (dot < 0d) {
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
            dot = -dot;
        }
        final double wa;
        final double wb;
        if (dot > 0.9995d) {
            // Nearly parallel: the normalised linear blend is exact enough and avoids dividing by sin(0).
            wa = 1d - p;
            wb = p;
        } else {
            final double angle = Math.acos(Math.min(1d, dot));
            final double sin = Math.sin(angle);
            wa = Math.sin((1d - p) * angle) / sin;
            wb = Math.sin(p * angle) / sin;
        }
        qx = from.qx * wa + bx * wb;
        qy = from.qy * wa + by * wb;
        qz = from.qz * wa + bz * wb;
        qw = from.qw * wa + bw * wb;
        normalize();
    }

    private void normalize() {
        final double len = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        if (!(len > 1e-9) || !Double.isFinite(len)) {
            qx = 0d;
            qy = 0d;
            qz = 0d;
            qw = 1d;
            return;
        }
        qx /= len;
        qy /= len;
        qz /= len;
        qw /= len;
    }

    /**
     * Returns interpolation progress.
     *
     * @param startMs when the interpolation started, ms
     * @param durationMs its length, ms; 0 or less means it is already complete
     * @param nowMs the current time, ms
     * @return progress in {@code [0, 1]}
     */
    public static double progress(final long startMs, final long durationMs, final long nowMs) {
        if (durationMs <= 0L) {
            return 1d;
        }
        return clamp01((double) (nowMs - startMs) / durationMs);
    }

    private static double mix(final double a, final double b, final double t) {
        return a + (b - a) * t;
    }

    private static double clamp01(final double v) {
        return Double.isNaN(v) ? 1d : Math.max(0d, Math.min(1d, v));
    }
}
