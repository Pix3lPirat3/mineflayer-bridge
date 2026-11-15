package io.github.pix3lpirat3.mfbridge.core.spatial;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;

/**
 * Game-free gizmo math: ray tests against handle geometry, axis and plane drags,
 * snapping and camera-relative nudges. Angles use Minecraft's convention: yaw 0 faces south (+Z) and
 * grows clockwise seen from above, in degrees. Rays are given as origin plus a unit direction.
 */
@AnyThread
public final class GizmoMath {
    /** Angle snap step while Ctrl is held, in degrees. */
    public static final double SNAP_DEGREES = 15d;

    private GizmoMath() {}

    /** How positions and angles snap while dragging or nudging. */
    public enum Snap {
        /** No snapping: Shift, or no modifier. */
        NONE,
        /** Whole blocks and 15 degrees: Ctrl. */
        BLOCK,
        /** Half blocks: Alt. */
        HALF;

        /**
         * Returns the mode for the held modifiers. Shift wins over the others, Ctrl over Alt.
         */
        public static Snap of(boolean ctrl, boolean shift, boolean alt) {
            if (shift) {
                return NONE;
            }
            if (ctrl) {
                return BLOCK;
            }
            return alt ? HALF : NONE;
        }

        /** Returns the grid step in blocks, or 0 for none. */
        public double step() {
            return switch (this) {
                case NONE -> 0d;
                case BLOCK -> 1d;
                case HALF -> 0.5d;
            };
        }

        /** Snaps a coordinate to this mode's grid. */
        public double snap(double v) {
            double s = step();
            return s == 0d ? v : Math.round(v / s) * s;
        }

        /** Snaps an angle in degrees: 15 degree steps for {@link #BLOCK}, unchanged otherwise. */
        public double snapAngle(double degrees) {
            return this == BLOCK ? Math.round(degrees / SNAP_DEGREES) * SNAP_DEGREES : degrees;
        }

        /** Returns the nudge distance for one key press, in blocks: the grid step, or 1 without snapping. */
        public double nudge() {
            return this == NONE ? 1d : step();
        }
    }

    /**
     * Returns the distance along a ray to an axis-aligned box, 0 when the origin is inside.
     *
     * @return the distance, or -1 when the ray misses or the box is behind
     */
    public static double rayBox(double ox, double oy, double oz, double dx, double dy, double dz, double x0, double y0,
            double z0, double x1, double y1, double z1) {
        double tmin = Double.NEGATIVE_INFINITY;
        double tmax = Double.POSITIVE_INFINITY;
        double[] o = {ox, oy, oz};
        double[] d = {dx, dy, dz};
        double[] lo = {x0, y0, z0};
        double[] hi = {x1, y1, z1};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < lo[a] || o[a] > hi[a]) {
                    return -1;
                }
                continue;
            }
            double t1 = (lo[a] - o[a]) / d[a];
            double t2 = (hi[a] - o[a]) / d[a];
            tmin = Math.max(tmin, Math.min(t1, t2));
            tmax = Math.min(tmax, Math.max(t1, t2));
        }
        if (tmax < Math.max(tmin, 0)) {
            return -1;
        }
        return Math.max(tmin, 0);
    }

    /**
     * Returns the distance along a ray to a sphere, 0 when the origin is inside.
     *
     * @return the distance, or -1 when it misses
     */
    public static double raySphere(double ox, double oy, double oz, double dx, double dy, double dz, double cx, double cy,
            double cz, double r) {
        double lx = cx - ox;
        double ly = cy - oy;
        double lz = cz - oz;
        double c2 = lx * lx + ly * ly + lz * lz;
        if (c2 <= r * r) {
            return 0;
        }
        double t = lx * dx + ly * dy + lz * dz;
        if (t < 0) {
            return -1;
        }
        double d2 = c2 - t * t;
        if (d2 > r * r) {
            return -1;
        }
        return t - Math.sqrt(r * r - d2);
    }

    /**
     * Returns the distance along a ray to where it crosses a plane through a point.
     *
     * @return the distance, or -1 when the ray is parallel or the plane is behind
     */
    public static double rayPlane(double ox, double oy, double oz, double dx, double dy, double dz, double px, double py,
            double pz, double nx, double ny, double nz) {
        double denom = dx * nx + dy * ny + dz * nz;
        if (Math.abs(denom) < 1e-9) {
            return -1;
        }
        double t = ((px - ox) * nx + (py - oy) * ny + (pz - oz) * nz) / denom;
        return t < 0 ? -1 : t;
    }

    /**
     * Returns the parameter along an axis line (through {@code p} with unit direction {@code a}) of
     * the point closest to a ray. Dragging a handle along an axis follows this value.
     *
     * @return the signed distance from {@code p} along {@code a}; 0 when the ray is parallel to the axis
     */
    public static double closestOnAxis(double ox, double oy, double oz, double dx, double dy, double dz, double px,
            double py, double pz, double ax, double ay, double az) {
        double b = ax * dx + ay * dy + az * dz;
        double denom = 1 - b * b;
        if (denom < 1e-9) {
            return 0;
        }
        double wx = px - ox;
        double wy = py - oy;
        double wz = pz - oz;
        double d = ax * wx + ay * wy + az * wz;
        double e = dx * wx + dy * wy + dz * wz;
        return (b * e - d) / denom;
    }

    /**
     * Returns the yaw, in degrees, of the direction from a centre to a point projected on the
     * horizontal plane, in Minecraft's convention.
     */
    public static double yawTowards(double cx, double cz, double px, double pz) {
        return Math.toDegrees(Math.atan2(-(px - cx), pz - cz));
    }

    /** Wraps an angle into {@code [-180, 180)} degrees. */
    public static double wrapDegrees(double degrees) {
        double d = degrees % 360d;
        if (d >= 180d) {
            d -= 360d;
        } else if (d < -180d) {
            d += 360d;
        }
        return d;
    }

    /** The nudge keys. */
    public enum Nudge {
        /** Up arrow: away from the camera. */
        FORWARD,
        /** Down arrow: towards the camera. */
        BACK,
        /** Left arrow. */
        LEFT,
        /** Right arrow. */
        RIGHT,
        /** Page Up: world up. */
        UP,
        /** Page Down: world down. */
        DOWN
    }

    /**
     * Returns the world axis a nudge key moves along for a camera yaw: the horizontal keys pick the
     * world axis closest to the camera's forward or right direction, Page Up and Page Down move in y.
     *
     * @return a unit step with one non-zero component
     */
    public static int[] nudgeAxis(double yawDegrees, Nudge key) {
        double yaw = Math.toRadians(yawDegrees);
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        // Facing +Z (yaw 0) the right hand points to -X.
        double rx = -fz;
        double rz = fx;
        return switch (key) {
            case UP -> new int[] {0, 1, 0};
            case DOWN -> new int[] {0, -1, 0};
            case FORWARD -> dominant(fx, fz);
            case BACK -> dominant(-fx, -fz);
            case RIGHT -> dominant(rx, rz);
            case LEFT -> dominant(-rx, -rz);
        };
    }

    private static int[] dominant(double x, double z) {
        if (Math.abs(x) >= Math.abs(z)) {
            return new int[] {x < 0 ? -1 : 1, 0, 0};
        }
        return new int[] {0, 0, z < 0 ? -1 : 1};
    }
}
