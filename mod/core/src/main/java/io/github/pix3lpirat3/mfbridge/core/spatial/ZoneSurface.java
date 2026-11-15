package io.github.pix3lpirat3.mfbridge.core.spatial;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import java.util.Arrays;
import java.util.List;

/**
 * The visible surface of a zone in world coordinates: boundary faces as quads and outline edges as
 * segments, for translucent volumes with outlines.
 *
 * <p>Box zones are merged per op result: the disjoint boxes are rasterised on a grid compressed to
 * the boxes' own boundary planes, so a zone made of a few boxes yields a few dozen faces however
 * large it is. Faces between two filled cells are dropped, and an edge is drawn only where the
 * surface creases. Prisms yield their side walls, a triangulated top and bottom, and the ring edges;
 * a prism without a vertical limit is drawn clipped to {@link #DRAW_MIN_Y}..{@link #DRAW_MAX_Y}.
 *
 * <p>The arrays are exposed as built; callers treat them as read-only.
 *
 * @param quads four corners per quad, x y z each, in winding order; triangles repeat their third
 *     corner
 * @param edges two end points per segment, x y z each
 */
@AnyThread
public record ZoneSurface(double[] quads, double[] edges) {

    /** Compressed grids larger than this many cells fall back to per-box faces and edges. */
    static final int MAX_CELLS = 1 << 21;

    /** Lowest block y drawn for prisms without a lower limit: the overworld floor of 26.x. */
    public static final int DRAW_MIN_Y = -64;

    /** Highest block y drawn for prisms without an upper limit: the overworld build limit of 26.x. */
    public static final int DRAW_MAX_Y = 319;

    /** Surface of an empty zone. */
    public static final ZoneSurface EMPTY = new ZoneSurface(new double[0], new double[0]);

    /** Returns the number of quads. */
    public int quadCount() {
        return quads.length / 12;
    }

    /** Returns the number of edge segments. */
    public int edgeCount() {
        return edges.length / 6;
    }

    /** Builds the surface of a zone. */
    public static ZoneSurface of(ZoneShape shape) {
        return switch (shape) {
            case ZoneShape.Boxes b -> boxes(b.parts());
            case ZoneShape.Prism p -> prism(p);
            case ZoneShape.Empty e -> EMPTY;
        };
    }

    private static int[] axis(List<IntBox> parts, int which) {
        int[] v = new int[parts.size() * 2];
        int n = 0;
        for (IntBox b : parts) {
            switch (which) {
                case 0 -> {
                    v[n++] = b.minX();
                    v[n++] = b.maxX() + 1;
                }
                case 1 -> {
                    v[n++] = b.minY();
                    v[n++] = b.maxY() + 1;
                }
                default -> {
                    v[n++] = b.minZ();
                    v[n++] = b.maxZ() + 1;
                }
            }
        }
        return Arrays.stream(v).sorted().distinct().toArray();
    }

    private static ZoneSurface boxes(List<IntBox> parts) {
        int[] xs = axis(parts, 0);
        int[] ys = axis(parts, 1);
        int[] zs = axis(parts, 2);
        int nx = xs.length - 1;
        int ny = ys.length - 1;
        int nz = zs.length - 1;
        if ((long) nx * ny * nz > MAX_CELLS) {
            return perBox(parts);
        }
        Grid g = new Grid(nx, ny, nz);
        for (IntBox b : parts) {
            int i0 = Arrays.binarySearch(xs, b.minX());
            int i1 = Arrays.binarySearch(xs, b.maxX() + 1);
            int j0 = Arrays.binarySearch(ys, b.minY());
            int j1 = Arrays.binarySearch(ys, b.maxY() + 1);
            int k0 = Arrays.binarySearch(zs, b.minZ());
            int k1 = Arrays.binarySearch(zs, b.maxZ() + 1);
            for (int i = i0; i < i1; i++) {
                for (int j = j0; j < j1; j++) {
                    for (int k = k0; k < k1; k++) {
                        g.cells[g.index(i, j, k)] = true;
                    }
                }
            }
        }
        Buffer quads = new Buffer();
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                for (int k = 0; k < nz; k++) {
                    if (!g.filled(i, j, k)) {
                        continue;
                    }
                    double x0 = xs[i];
                    double x1 = xs[i + 1];
                    double y0 = ys[j];
                    double y1 = ys[j + 1];
                    double z0 = zs[k];
                    double z1 = zs[k + 1];
                    if (!g.filled(i, j - 1, k)) {
                        quads.quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
                    }
                    if (!g.filled(i, j + 1, k)) {
                        quads.quad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
                    }
                    if (!g.filled(i, j, k - 1)) {
                        quads.quad(x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
                    }
                    if (!g.filled(i, j, k + 1)) {
                        quads.quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
                    }
                    if (!g.filled(i - 1, j, k)) {
                        quads.quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
                    }
                    if (!g.filled(i + 1, j, k)) {
                        quads.quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
                    }
                }
            }
        }
        Buffer edges = new Buffer();
        // Along x: lattice lines at (ys[j], zs[k]); the four cells around are (i, j-1|j, k-1|k).
        for (int j = 0; j <= ny; j++) {
            for (int k = 0; k <= nz; k++) {
                int start = -1;
                for (int i = 0; i <= nx; i++) {
                    boolean draw = i < nx && crease(g.filled(i, j - 1, k - 1), g.filled(i, j, k - 1),
                            g.filled(i, j - 1, k), g.filled(i, j, k));
                    if (draw && start < 0) {
                        start = i;
                    } else if (!draw && start >= 0) {
                        edges.segment(xs[start], ys[j], zs[k], xs[i], ys[j], zs[k]);
                        start = -1;
                    }
                }
            }
        }
        for (int i = 0; i <= nx; i++) {
            for (int k = 0; k <= nz; k++) {
                int start = -1;
                for (int j = 0; j <= ny; j++) {
                    boolean draw = j < ny && crease(g.filled(i - 1, j, k - 1), g.filled(i, j, k - 1),
                            g.filled(i - 1, j, k), g.filled(i, j, k));
                    if (draw && start < 0) {
                        start = j;
                    } else if (!draw && start >= 0) {
                        edges.segment(xs[i], ys[start], zs[k], xs[i], ys[j], zs[k]);
                        start = -1;
                    }
                }
            }
        }
        for (int i = 0; i <= nx; i++) {
            for (int j = 0; j <= ny; j++) {
                int start = -1;
                for (int k = 0; k <= nz; k++) {
                    boolean draw = k < nz && crease(g.filled(i - 1, j - 1, k), g.filled(i, j - 1, k),
                            g.filled(i - 1, j, k), g.filled(i, j, k));
                    if (draw && start < 0) {
                        start = k;
                    } else if (!draw && start >= 0) {
                        edges.segment(xs[i], ys[j], zs[start], xs[i], ys[j], zs[k]);
                        start = -1;
                    }
                }
            }
        }
        return new ZoneSurface(quads.toArray(), edges.toArray());
    }

    /**
     * Whether the lattice line between four cells (two by two around it) lies on a crease of the
     * surface: one or three filled cells form a corner, two diagonal ones a pinch; two adjacent cells
     * form a flat face and none or four mean no surface at all.
     */
    static boolean crease(boolean a, boolean b, boolean c, boolean d) {
        int n = (a ? 1 : 0) + (b ? 1 : 0) + (c ? 1 : 0) + (d ? 1 : 0);
        return n == 1 || n == 3 || (n == 2 && a == d);
    }

    private static ZoneSurface perBox(List<IntBox> parts) {
        Buffer quads = new Buffer();
        Buffer edges = new Buffer();
        for (IntBox b : parts) {
            double x0 = b.minX();
            double y0 = b.minY();
            double z0 = b.minZ();
            double x1 = b.maxX() + 1d;
            double y1 = b.maxY() + 1d;
            double z1 = b.maxZ() + 1d;
            quads.quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
            quads.quad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
            quads.quad(x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
            quads.quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            quads.quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            quads.quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
            boxEdges(edges, x0, y0, z0, x1, y1, z1);
        }
        return new ZoneSurface(quads.toArray(), edges.toArray());
    }

    /** Appends the twelve edges of a box. */
    static void boxEdges(Buffer e, double x0, double y0, double z0, double x1, double y1, double z1) {
        e.segment(x0, y0, z0, x1, y0, z0);
        e.segment(x0, y0, z1, x1, y0, z1);
        e.segment(x0, y1, z0, x1, y1, z0);
        e.segment(x0, y1, z1, x1, y1, z1);
        e.segment(x0, y0, z0, x0, y1, z0);
        e.segment(x1, y0, z0, x1, y1, z0);
        e.segment(x0, y0, z1, x0, y1, z1);
        e.segment(x1, y0, z1, x1, y1, z1);
        e.segment(x0, y0, z0, x0, y0, z1);
        e.segment(x1, y0, z0, x1, y0, z1);
        e.segment(x0, y1, z0, x0, y1, z1);
        e.segment(x1, y1, z0, x1, y1, z1);
    }

    private static ZoneSurface prism(ZoneShape.Prism p) {
        int[] xs = p.xs();
        int[] zs = p.zs();
        int n = xs.length;
        double y0 = Math.max(p.minY(), Math.min(DRAW_MIN_Y, p.maxY()));
        double y1 = Math.min(p.maxY(), Math.max(DRAW_MAX_Y, p.minY())) + 1d;
        Buffer quads = new Buffer();
        Buffer edges = new Buffer();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double ax = xs[j];
            double az = zs[j];
            double bx = xs[i];
            double bz = zs[i];
            quads.quad(ax, y0, az, bx, y0, bz, bx, y1, bz, ax, y1, az);
            edges.segment(ax, y0, az, bx, y0, bz);
            edges.segment(ax, y1, az, bx, y1, bz);
            edges.segment(bx, y0, bz, bx, y1, bz);
        }
        int[] tris = Polygons.triangulate(xs, zs);
        for (int t = 0; t + 2 < tris.length; t += 3) {
            double ax = xs[tris[t]];
            double az = zs[tris[t]];
            double bx = xs[tris[t + 1]];
            double bz = zs[tris[t + 1]];
            double cx = xs[tris[t + 2]];
            double cz = zs[tris[t + 2]];
            quads.quad(ax, y0, az, bx, y0, bz, cx, y0, cz, cx, y0, cz);
            quads.quad(ax, y1, az, cx, y1, cz, bx, y1, bz, bx, y1, bz);
        }
        return new ZoneSurface(quads.toArray(), edges.toArray());
    }

    private static final class Grid {
        final int nx;
        final int ny;
        final int nz;
        final boolean[] cells;

        Grid(int nx, int ny, int nz) {
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.cells = new boolean[nx * ny * nz];
        }

        int index(int i, int j, int k) {
            return (i * ny + j) * nz + k;
        }

        boolean filled(int i, int j, int k) {
            return i >= 0 && j >= 0 && k >= 0 && i < nx && j < ny && k < nz && cells[index(i, j, k)];
        }
    }

    /** A growable double array. */
    static final class Buffer {
        private double[] data = new double[96];
        private int size;

        void quad(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz,
                double dx, double dy, double dz) {
            put(ax, ay, az);
            put(bx, by, bz);
            put(cx, cy, cz);
            put(dx, dy, dz);
        }

        void segment(double ax, double ay, double az, double bx, double by, double bz) {
            put(ax, ay, az);
            put(bx, by, bz);
        }

        private void put(double x, double y, double z) {
            if (size + 3 > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = x;
            data[size++] = y;
            data[size++] = z;
        }

        double[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }
}
