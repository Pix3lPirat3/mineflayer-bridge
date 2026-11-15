package io.github.pix3lpirat3.mfbridge.core.spatial;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.RouteData;
import io.github.pix3lpirat3.mfbridge.protocol.core.RouteNode;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Route interpolation for {@code core:route}: every mode turns the nodes into a polyline.
 *
 * <ul>
 *   <li>{@code linear}: straight segments between nodes.
 *   <li>{@code bresenham}: the blocks a 3D Bresenham walk visits between the nodes' blocks; the
 *       polyline runs through those blocks' centres.
 *   <li>{@code catmull_rom} (also spelled {@code catmull-rom}): a uniform Catmull-Rom spline through
 *       every node; open routes repeat their end nodes as outer control points.
 *   <li>{@code bezier}: one Bezier curve with every node as a control point, as the Node plugin
 *       evaluates it; it starts at the first node and ends at the last (looped routes end back at
 *       the first) and only approaches the inner nodes.
 * </ul>
 *
 * <p>Looped routes close back to the first node. An unknown mode counts as {@code linear}.
 */
@AnyThread
public final class RouteGeometry {
    /** Samples per curved segment. */
    public static final int SAMPLES = 12;

    /** Largest number of points a Bezier curve is sampled at. */
    public static final int MAX_BEZIER_SAMPLES = 512;

    /** Largest number of blocks a Bresenham walk produces; longer routes are cut there. */
    public static final int MAX_BLOCKS = 65_536;

    private RouteGeometry() {}

    /** The interpolation modes. */
    public enum Mode {
        /** Straight segments. */
        LINEAR,
        /** Block steps. */
        BRESENHAM,
        /** Spline through the nodes. */
        CATMULL_ROM,
        /** One Bezier curve over all nodes. */
        BEZIER;

        /** Parses a schema name, defaulting to {@link #LINEAR}. */
        public static Mode of(String name) {
            return switch (name.toLowerCase(Locale.ROOT).replace('-', '_')) {
                case "bresenham" -> BRESENHAM;
                case "catmull_rom", "catmullrom" -> CATMULL_ROM;
                case "bezier" -> BEZIER;
                default -> LINEAR;
            };
        }

        /** Returns the schema name. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Returns the polyline of a route.
     *
     * @return x y z triples; fewer than two points when the route has fewer than two nodes
     */
    public static double[] polyline(RouteData route) {
        return polyline(route.nodes(), route.looped(), Mode.of(route.interpolation()), SAMPLES);
    }

    /**
     * Returns the polyline through nodes.
     *
     * @param samples points per curved segment, at least 1
     * @return x y z triples
     */
    public static double[] polyline(List<RouteNode> nodes, boolean looped, Mode mode, int samples) {
        int n = nodes.size();
        if (n == 0) {
            return new double[0];
        }
        double[] p = positions(nodes);
        if (n == 1) {
            return p;
        }
        boolean loop = looped && n > 2;
        int steps = Math.max(1, samples);
        return switch (mode) {
            case LINEAR -> linear(p, n, loop);
            case BRESENHAM -> centres(blocks(p, n, loop));
            case CATMULL_ROM -> catmullRom(p, n, loop, steps);
            case BEZIER -> bezier(p, n, loop, steps);
        };
    }

    /**
     * Returns the blocks of a Bresenham walk through the route's nodes, whatever its mode.
     *
     * @return x y z block triples, consecutive blocks differ, at most {@link #MAX_BLOCKS}
     */
    public static int[] blocks(RouteData route) {
        List<RouteNode> nodes = route.nodes();
        int n = nodes.size();
        double[] p = positions(nodes);
        return blocks(p, n, route.looped() && n > 2);
    }

    /** Returns the node positions as x y z triples. */
    private static double[] positions(List<RouteNode> nodes) {
        int n = nodes.size();
        double[] p = new double[n * 3];
        for (int i = 0; i < n; i++) {
            Vec3d v = nodes.get(i).pos();
            p[i * 3] = v.x();
            p[i * 3 + 1] = v.y();
            p[i * 3 + 2] = v.z();
        }
        return p;
    }

    private static double[] linear(double[] p, int n, boolean loop) {
        if (!loop) {
            return p.clone();
        }
        double[] out = Arrays.copyOf(p, p.length + 3);
        System.arraycopy(p, 0, out, p.length, 3);
        return out;
    }

    private static int[] blocks(double[] p, int n, boolean loop) {
        Ints out = new Ints();
        int segments = loop ? n : n - 1;
        if (n == 1) {
            out.add((int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2]));
        }
        for (int s = 0; s < segments && out.size < MAX_BLOCKS * 3; s++) {
            int a = s;
            int b = (s + 1) % n;
            walk(out, (int) Math.floor(p[a * 3]), (int) Math.floor(p[a * 3 + 1]), (int) Math.floor(p[a * 3 + 2]),
                    (int) Math.floor(p[b * 3]), (int) Math.floor(p[b * 3 + 1]), (int) Math.floor(p[b * 3 + 2]));
        }
        return out.toArray();
    }

    /** 3D Bresenham from one block to another, both included; skips a start equal to the last block written. */
    static void walk(Ints out, int x0, int y0, int z0, int x1, int y1, int z1) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int dz = Math.abs(z1 - z0);
        int sx = Integer.signum(x1 - x0);
        int sy = Integer.signum(y1 - y0);
        int sz = Integer.signum(z1 - z0);
        int x = x0;
        int y = y0;
        int z = z0;
        out.addDistinct(x, y, z);
        if (dx >= dy && dx >= dz) {
            int e1 = 2 * dy - dx;
            int e2 = 2 * dz - dx;
            for (int i = 0; i < dx && out.size < MAX_BLOCKS * 3; i++) {
                if (e1 > 0) {
                    y += sy;
                    e1 -= 2 * dx;
                }
                if (e2 > 0) {
                    z += sz;
                    e2 -= 2 * dx;
                }
                e1 += 2 * dy;
                e2 += 2 * dz;
                x += sx;
                out.add(x, y, z);
            }
        } else if (dy >= dx && dy >= dz) {
            int e1 = 2 * dx - dy;
            int e2 = 2 * dz - dy;
            for (int i = 0; i < dy && out.size < MAX_BLOCKS * 3; i++) {
                if (e1 > 0) {
                    x += sx;
                    e1 -= 2 * dy;
                }
                if (e2 > 0) {
                    z += sz;
                    e2 -= 2 * dy;
                }
                e1 += 2 * dx;
                e2 += 2 * dz;
                y += sy;
                out.add(x, y, z);
            }
        } else {
            int e1 = 2 * dy - dz;
            int e2 = 2 * dx - dz;
            for (int i = 0; i < dz && out.size < MAX_BLOCKS * 3; i++) {
                if (e1 > 0) {
                    y += sy;
                    e1 -= 2 * dz;
                }
                if (e2 > 0) {
                    x += sx;
                    e2 -= 2 * dz;
                }
                e1 += 2 * dy;
                e2 += 2 * dx;
                z += sz;
                out.add(x, y, z);
            }
        }
    }

    private static double[] centres(int[] blocks) {
        double[] out = new double[blocks.length];
        for (int i = 0; i < blocks.length; i++) {
            out[i] = blocks[i] + 0.5;
        }
        return out;
    }

    private static double[] catmullRom(double[] p, int n, boolean loop, int steps) {
        int segments = loop ? n : n - 1;
        double[] out = new double[(segments * steps + 1) * 3];
        int w = 0;
        for (int s = 0; s < segments; s++) {
            int i1 = s;
            int i2 = loop ? (s + 1) % n : s + 1;
            int i0 = loop ? (s - 1 + n) % n : Math.max(0, s - 1);
            int i3 = loop ? (s + 2) % n : Math.min(n - 1, s + 2);
            for (int k = s == 0 ? 0 : 1; k <= steps; k++) {
                double t = (double) k / steps;
                double t2 = t * t;
                double t3 = t2 * t;
                for (int c = 0; c < 3; c++) {
                    double a = p[i0 * 3 + c];
                    double b = p[i1 * 3 + c];
                    double d = p[i2 * 3 + c];
                    double e = p[i3 * 3 + c];
                    out[w++] = 0.5 * (2 * b + (-a + d) * t + (2 * a - 5 * b + 4 * d - e) * t2 + (-a + 3 * b - 3 * d + e) * t3);
                }
            }
        }
        return Arrays.copyOf(out, w);
    }

    private static double[] bezier(double[] p, int n, boolean loop, int steps) {
        int m = loop ? n + 1 : n;
        double[] ctrl = loop ? Arrays.copyOf(p, p.length + 3) : p;
        if (loop) {
            System.arraycopy(p, 0, ctrl, p.length, 3);
        }
        int samples = Math.min(MAX_BEZIER_SAMPLES, (m - 1) * steps);
        double[] out = new double[(samples + 1) * 3];
        double[] work = new double[m * 3];
        for (int k = 0; k <= samples; k++) {
            double t = (double) k / samples;
            // De Casteljau: stable for many control points, unlike expanding the Bernstein sum.
            System.arraycopy(ctrl, 0, work, 0, m * 3);
            for (int level = m - 1; level > 0; level--) {
                for (int i = 0; i < level; i++) {
                    for (int c = 0; c < 3; c++) {
                        work[i * 3 + c] = work[i * 3 + c] * (1 - t) + work[(i + 1) * 3 + c] * t;
                    }
                }
            }
            out[k * 3] = work[0];
            out[k * 3 + 1] = work[1];
            out[k * 3 + 2] = work[2];
        }
        return out;
    }

    /** A growable int array of triples. */
    static final class Ints {
        private int[] data = new int[48];
        int size;

        void add(int x, int y, int z) {
            if (size + 3 > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = x;
            data[size++] = y;
            data[size++] = z;
        }

        void addDistinct(int x, int y, int z) {
            if (size >= 3 && data[size - 3] == x && data[size - 2] == y && data[size - 1] == z) {
                return;
            }
            add(x, y, z);
        }

        int[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }
}
