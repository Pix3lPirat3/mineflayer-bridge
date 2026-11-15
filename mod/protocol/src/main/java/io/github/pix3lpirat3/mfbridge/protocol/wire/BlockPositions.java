package io.github.pix3lpirat3.mfbridge.protocol.wire;

/** Packed block position: x 26 bits at 38, z 26 bits at 12, y 12 bits at 0. */
public final class BlockPositions {
    private BlockPositions() {}

    /** Packs block coordinates; out-of-range values wrap to their low bits. */
    public static long pack(int x, int y, int z) {
        return ((long) x & 0x3FF_FFFFL) << 38 | ((long) z & 0x3FF_FFFFL) << 12 | ((long) y & 0xFFFL);
    }

    /** Returns the sign-extended x coordinate. */
    public static int x(long packed) {
        return (int) (packed >> 38);
    }

    /** Returns the sign-extended y coordinate. */
    public static int y(long packed) {
        return (int) (packed << 52 >> 52);
    }

    /** Returns the sign-extended z coordinate. */
    public static int z(long packed) {
        return (int) (packed << 26 >> 38);
    }
}
