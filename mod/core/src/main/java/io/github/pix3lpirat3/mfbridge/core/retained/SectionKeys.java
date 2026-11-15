package io.github.pix3lpirat3.mfbridge.core.retained;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;

/**
 * Packs 16x16x16 section coordinates into one {@code long}: 22 bits for x and z, 20 bits for y, the
 * same layout as the game's section positions, so keys are stable across threads and allocation-free.
 */
@AnyThread
public final class SectionKeys {
    private SectionKeys() {}

    /** Packs section coordinates (block coordinate {@code >> 4}). */
    public static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFL) << 42 | ((long) y & 0xFFFFFL) | ((long) z & 0x3FFFFFL) << 20;
    }

    /** Returns the packed key of the section holding a block position. */
    public static long ofBlock(int bx, int by, int bz) {
        return pack(bx >> 4, by >> 4, bz >> 4);
    }

    /** Returns the section x coordinate of a key. */
    public static int x(long key) {
        return (int) (key >> 42);
    }

    /** Returns the section y coordinate of a key. */
    public static int y(long key) {
        return (int) (key << 44 >> 44);
    }

    /** Returns the section z coordinate of a key. */
    public static int z(long key) {
        return (int) (key << 22 >> 42);
    }

    /** Returns the index of a section-local position in {@code (y * 16 + z) * 16 + x} order. */
    public static int index(int lx, int ly, int lz) {
        return (ly << 8) | (lz << 4) | lx;
    }
}
