package io.github.pix3lpirat3.mfbridge.core.spatial;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import java.util.Arrays;

/**
 * Planar polygon math on integer vertices: point in polygon with the boundary counted as inside,
 * orientation, segment tests and ear-clipping triangulation. Vertices are given as parallel x and z
 * arrays in order, either winding, without repeating the first vertex at the end.
 */
@AnyThread
public final class Polygons {
    private Polygons() {}

    /**
     * Returns whether a lattice point lies inside the polygon or on its boundary. Exact: all
     * arithmetic is on longs.
     */
    public static boolean contains(int[] xs, int[] zs, int px, int pz) {
        int n = xs.length;
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            long xi = xs[i];
            long zi = zs[i];
            long xj = xs[j];
            long zj = zs[j];
            if (onSegment(xj, zj, xi, zi, px, pz)) {
                return true;
            }
            if ((zi > pz) != (zj > pz)) {
                // Crossing x of the edge at height pz compared without division: sign depends on dz.
                long lhs = (px - xi) * (zj - zi);
                long rhs = (xj - xi) * (pz - zi);
                if (zj > zi ? lhs < rhs : lhs > rhs) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    /**
     * Returns whether a continuous point lies inside the polygon (even-odd rule; points on the
     * boundary may go either way).
     */
    public static boolean contains(int[] xs, int[] zs, double px, double pz) {
        int n = xs.length;
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double zi = zs[i];
            double zj = zs[j];
            if ((zi > pz) != (zj > pz)) {
                double cross = xs[i] + (pz - zi) * (xs[j] - xs[i]) / (zj - zi);
                if (px < cross) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    private static boolean onSegment(long ax, long az, long bx, long bz, long px, long pz) {
        long cross = (bx - ax) * (pz - az) - (bz - az) * (px - ax);
        return cross == 0 && px >= Math.min(ax, bx) && px <= Math.max(ax, bx) && pz >= Math.min(az, bz)
                && pz <= Math.max(az, bz);
    }

    /** Returns twice the signed area: positive when the vertices run counter-clockwise in (x, z). */
    public static long signedArea2(int[] xs, int[] zs) {
        long sum = 0;
        int n = xs.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            sum += (long) xs[j] * zs[i] - (long) xs[i] * zs[j];
        }
        return sum;
    }

    /** Returns whether two closed segments share a point. */
    public static boolean segmentsIntersect(double ax, double az, double bx, double bz, double cx, double cz, double dx,
            double dz) {
        double d1 = orient(cx, cz, dx, dz, ax, az);
        double d2 = orient(cx, cz, dx, dz, bx, bz);
        double d3 = orient(ax, az, bx, bz, cx, cz);
        double d4 = orient(ax, az, bx, bz, dx, dz);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        return (d1 == 0 && within(cx, cz, dx, dz, ax, az)) || (d2 == 0 && within(cx, cz, dx, dz, bx, bz))
                || (d3 == 0 && within(ax, az, bx, bz, cx, cz)) || (d4 == 0 && within(ax, az, bx, bz, dx, dz));
    }

    private static double orient(double ax, double az, double bx, double bz, double px, double pz) {
        return (bx - ax) * (pz - az) - (bz - az) * (px - ax);
    }

    private static boolean within(double ax, double az, double bx, double bz, double px, double pz) {
        return px >= Math.min(ax, bx) && px <= Math.max(ax, bx) && pz >= Math.min(az, bz) && pz <= Math.max(az, bz);
    }

    /**
     * Returns whether the closed rectangle {@code [x0, x1] x [z0, z1]} and the closed polygon share a
     * point.
     */
    public static boolean intersectsRect(int[] xs, int[] zs, double x0, double z0, double x1, double z1) {
        int n = xs.length;
        for (int i = 0; i < n; i++) {
            if (xs[i] >= x0 && xs[i] <= x1 && zs[i] >= z0 && zs[i] <= z1) {
                return true;
            }
        }
        if (contains(xs, zs, (x0 + x1) / 2, (z0 + z1) / 2)) {
            return true;
        }
        double[] rx = {x0, x1, x1, x0};
        double[] rz = {z0, z0, z1, z1};
        for (int i = 0, j = n - 1; i < n; j = i++) {
            for (int k = 0; k < 4; k++) {
                int l = (k + 1) & 3;
                if (segmentsIntersect(xs[j], zs[j], xs[i], zs[i], rx[k], rz[k], rx[l], rz[l])) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Removes repeated consecutive vertices and collinear middle vertices, so triangulation and
     * outlines see a simple ring.
     *
     * @return {@code [xs, zs]} of the cleaned ring; fewer than three vertices when it is degenerate
     */
    public static int[][] clean(int[] xs, int[] zs) {
        int n = xs.length;
        int[] ox = Arrays.copyOf(xs, n);
        int[] oz = Arrays.copyOf(zs, n);
        boolean changed = true;
        while (changed && n >= 3) {
            changed = false;
            for (int i = 0; i < n && n >= 3; i++) {
                int prev = (i + n - 1) % n;
                int next = (i + 1) % n;
                long cross = ((long) ox[i] - ox[prev]) * ((long) oz[next] - oz[prev])
                        - ((long) oz[i] - oz[prev]) * ((long) ox[next] - ox[prev]);
                boolean same = ox[i] == ox[prev] && oz[i] == oz[prev];
                if (same || cross == 0) {
                    System.arraycopy(ox, i + 1, ox, i, n - i - 1);
                    System.arraycopy(oz, i + 1, oz, i, n - i - 1);
                    n--;
                    changed = true;
                    i--;
                }
            }
        }
        return new int[][] {Arrays.copyOf(ox, n), Arrays.copyOf(oz, n)};
    }

    /**
     * Triangulates a simple polygon by ear clipping. Self-intersecting input still terminates: when no
     * ear is found the rest is fanned from its first vertex.
     *
     * @return vertex index triples into the input arrays; {@code 3 * (n - 2)} entries for {@code n >= 3}
     */
    public static int[] triangulate(int[] xs, int[] zs) {
        int n = xs.length;
        if (n < 3) {
            return new int[0];
        }
        boolean ccw = signedArea2(xs, zs) > 0;
        int[] ring = new int[n];
        for (int i = 0; i < n; i++) {
            ring[i] = i;
        }
        int[] out = new int[3 * (n - 2)];
        int written = 0;
        int m = n;
        int guard = 0;
        int i = 0;
        while (m > 3 && guard < m) {
            int a = ring[(i + m - 1) % m];
            int b = ring[i % m];
            int c = ring[(i + 1) % m];
            if (isEar(xs, zs, ring, m, a, b, c, ccw)) {
                out[written++] = a;
                out[written++] = b;
                out[written++] = c;
                int at = i % m;
                System.arraycopy(ring, at + 1, ring, at, m - at - 1);
                m--;
                guard = 0;
            } else {
                i++;
                guard++;
            }
        }
        for (int k = 1; k + 1 < m; k++) {
            out[written++] = ring[0];
            out[written++] = ring[k];
            out[written++] = ring[k + 1];
        }
        return Arrays.copyOf(out, written);
    }

    private static boolean isEar(int[] xs, int[] zs, int[] ring, int m, int a, int b, int c, boolean ccw) {
        long cross = ((long) xs[b] - xs[a]) * ((long) zs[c] - zs[a]) - ((long) zs[b] - zs[a]) * ((long) xs[c] - xs[a]);
        if (ccw ? cross <= 0 : cross >= 0) {
            return false;
        }
        for (int k = 0; k < m; k++) {
            int p = ring[k];
            if (p == a || p == b || p == c) {
                continue;
            }
            if (inTriangle(xs[a], zs[a], xs[b], zs[b], xs[c], zs[c], xs[p], zs[p])) {
                return false;
            }
        }
        return true;
    }

    private static boolean inTriangle(long ax, long az, long bx, long bz, long cx, long cz, long px, long pz) {
        long d1 = (bx - ax) * (pz - az) - (bz - az) * (px - ax);
        long d2 = (cx - bx) * (pz - bz) - (cz - bz) * (px - bx);
        long d3 = (ax - cx) * (pz - cz) - (az - cz) * (px - cx);
        boolean neg = d1 < 0 || d2 < 0 || d3 < 0;
        boolean pos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(neg && pos);
    }
}
