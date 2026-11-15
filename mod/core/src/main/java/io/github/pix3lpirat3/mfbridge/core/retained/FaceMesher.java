package io.github.pix3lpirat3.mfbridge.core.retained;

/**
 * The boundary faces of a set of unit cells ({@code core:block-highlight} faces mode, heatmap
 * cells): a face is emitted only where the neighbouring cell is not in the set, so touching blocks
 * show one surface.
 *
 * <p>Stateless; safe on any thread.
 */
public final class FaceMesher {
    /** Towards -y. Directions follow the game's order: down, up, north, south, west, east. */
    public static final int DOWN = 0;
    /** Towards +y. */
    public static final int UP = 1;
    /** Towards -z. */
    public static final int NORTH = 2;
    /** Towards +z. */
    public static final int SOUTH = 3;
    /** Towards -x. */
    public static final int WEST = 4;
    /** Towards +x. */
    public static final int EAST = 5;

    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};

    /** Receives one face of a cell. */
    @FunctionalInterface
    public interface FaceSink {
        /** Receives the face of the cell at a world block position in direction {@code dir}. */
        void face(int x, int y, int z, int dir);
    }

    private FaceMesher() {}

    /** Returns the x step of a direction. */
    public static int dx(int dir) {
        return DX[dir];
    }

    /** Returns the y step of a direction. */
    public static int dy(int dir) {
        return DY[dir];
    }

    /** Returns the z step of a direction. */
    public static int dz(int dir) {
        return DZ[dir];
    }

    /**
     * Emits the boundary faces of the cells inside one section.
     *
     * @return the number of faces emitted
     */
    public static int mesh(int sx, int sy, int sz, CellPredicate cells, FaceSink sink) {
        int bx = sx << 4;
        int by = sy << 4;
        int bz = sz << 4;
        int emitted = 0;
        for (int y = by; y < by + 16; y++) {
            for (int z = bz; z < bz + 16; z++) {
                for (int x = bx; x < bx + 16; x++) {
                    if (!cells.test(x, y, z)) {
                        continue;
                    }
                    for (int dir = 0; dir < 6; dir++) {
                        if (!cells.test(x + DX[dir], y + DY[dir], z + DZ[dir])) {
                            sink.face(x, y, z, dir);
                            emitted++;
                        }
                    }
                }
            }
        }
        return emitted;
    }

    /**
     * Writes the four corners of one face of an axis-aligned cube as xyz triples into {@code out},
     * wound counter-clockwise seen from outside.
     *
     * @param x minimum corner x
     * @param y minimum corner y
     * @param z minimum corner z
     * @param size edge length
     * @param out receives 12 floats
     */
    public static void corners(float x, float y, float z, float size, int dir, float[] out) {
        corners(x, y, z, x + size, y + size, z + size, dir, out);
    }

    /**
     * Writes the four corners of one face of an axis-aligned box as xyz triples into {@code out},
     * wound counter-clockwise seen from outside.
     *
     * @param x minimum corner x
     * @param y minimum corner y
     * @param z minimum corner z
     * @param x1 maximum corner x
     * @param y1 maximum corner y
     * @param z1 maximum corner z
     * @param out receives 12 floats
     */
    public static void corners(float x, float y, float z, float x1, float y1, float z1, int dir, float[] out) {
        switch (dir) {
            case DOWN -> set(out, x, y, z, x1, y, z, x1, y, z1, x, y, z1);
            case UP -> set(out, x, y1, z, x, y1, z1, x1, y1, z1, x1, y1, z);
            case NORTH -> set(out, x, y, z, x, y1, z, x1, y1, z, x1, y, z);
            case SOUTH -> set(out, x, y, z1, x1, y, z1, x1, y1, z1, x, y1, z1);
            case WEST -> set(out, x, y, z, x, y, z1, x, y1, z1, x, y1, z);
            default -> set(out, x1, y, z, x1, y1, z, x1, y1, z1, x1, y, z1);
        }
    }

    private static void set(float[] o, float ax, float ay, float az, float bx, float by, float bz, float cx, float cy,
            float cz, float dx, float dy, float dz) {
        o[0] = ax;
        o[1] = ay;
        o[2] = az;
        o[3] = bx;
        o[4] = by;
        o[5] = bz;
        o[6] = cx;
        o[7] = cy;
        o[8] = cz;
        o[9] = dx;
        o[10] = dy;
        o[11] = dz;
    }
}
