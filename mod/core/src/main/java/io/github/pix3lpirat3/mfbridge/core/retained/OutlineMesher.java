package io.github.pix3lpirat3.mfbridge.core.retained;

/**
 * Merged block outlines ({@code core:block-highlight}, ghost blocks): the edges of
 * a set of unit cells with every edge between neighbouring cells removed, and collinear runs merged
 * into one segment.
 *
 * <p>An edge segment is shared by the four cells around it. It is drawn when one or three of them
 * are in the set, or two that only touch along the edge; two face neighbours make a flat surface and
 * four make the inside, so those edges vanish. Each segment is emitted by exactly one section: the
 * one holding the first set cell of the four in a fixed order. Sections meshed independently on
 * different workers therefore never draw an edge twice and never miss one.
 *
 * <p>Stateless; safe on any thread.
 */
public final class OutlineMesher {
    /** Receives one merged segment between two lattice points, in world block-corner coordinates. */
    @FunctionalInterface
    public interface EdgeSink {
        /** Receives a segment from {@code a} to {@code b}, parallel to one axis. */
        void edge(int ax, int ay, int az, int bx, int by, int bz);
    }

    private OutlineMesher() {}

    /**
     * Emits the outline segments owned by one section.
     *
     * @param sx section x (block coordinate {@code >> 4})
     * @param sy section y
     * @param sz section z
     * @param cells the full set, including cells in neighbouring sections
     * @return the number of segments emitted
     */
    public static int mesh(int sx, int sy, int sz, CellPredicate cells, EdgeSink sink) {
        int[] base = {sx << 4, sy << 4, sz << 4};
        int[] p = new int[3];
        int emitted = 0;
        for (int axis = 0; axis < 3; axis++) {
            int b = (axis + 1) % 3;
            int c = (axis + 2) % 3;
            for (int lb = 0; lb <= 16; lb++) {
                for (int lc = 0; lc <= 16; lc++) {
                    int runStart = Integer.MIN_VALUE;
                    for (int t = 0; t <= 16; t++) {
                        boolean draw = t < 16
                                && owned(axis, b, c, base, base[axis] + t, base[b] + lb, base[c] + lc, cells, p);
                        if (draw && runStart == Integer.MIN_VALUE) {
                            runStart = t;
                        } else if (!draw && runStart != Integer.MIN_VALUE) {
                            emit(sink, axis, b, c, base[axis] + runStart, base[axis] + t, base[b] + lb,
                                    base[c] + lc, p);
                            emitted++;
                            runStart = Integer.MIN_VALUE;
                        }
                    }
                }
            }
        }
        return emitted;
    }

    /**
     * Whether the unit segment along {@code axis} starting at {@code t} on the lattice line {@code (lb,
     * lc)} is drawn and owned by the section starting at {@code base}.
     */
    private static boolean owned(int axis, int b, int c, int[] base, int t, int lb, int lc, CellPredicate cells,
            int[] p) {
        // The cells around the line, in ownership order.
        boolean c0 = cell(axis, b, c, t, lb - 1, lc - 1, cells, p);
        boolean c1 = cell(axis, b, c, t, lb, lc - 1, cells, p);
        boolean c2 = cell(axis, b, c, t, lb - 1, lc, cells, p);
        boolean c3 = cell(axis, b, c, t, lb, lc, cells, p);
        int count = (c0 ? 1 : 0) + (c1 ? 1 : 0) + (c2 ? 1 : 0) + (c3 ? 1 : 0);
        boolean visible = count == 1 || count == 3 || count == 2 && (c0 && c3 || c1 && c2);
        if (!visible) {
            return false;
        }
        int ownerB;
        int ownerC;
        if (c0) {
            ownerB = lb - 1;
            ownerC = lc - 1;
        } else if (c1) {
            ownerB = lb;
            ownerC = lc - 1;
        } else if (c2) {
            ownerB = lb - 1;
            ownerC = lc;
        } else {
            ownerB = lb;
            ownerC = lc;
        }
        return ownerB >= base[b] && ownerB < base[b] + 16 && ownerC >= base[c] && ownerC < base[c] + 16;
    }

    private static boolean cell(int axis, int b, int c, int t, int vb, int vc, CellPredicate cells, int[] p) {
        p[axis] = t;
        p[b] = vb;
        p[c] = vc;
        return cells.test(p[0], p[1], p[2]);
    }

    private static void emit(EdgeSink sink, int axis, int b, int c, int from, int to, int lb, int lc, int[] p) {
        p[axis] = from;
        p[b] = lb;
        p[c] = lc;
        int ax = p[0];
        int ay = p[1];
        int az = p[2];
        p[axis] = to;
        sink.edge(ax, ay, az, p[0], p[1], p[2]);
    }
}
