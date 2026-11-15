package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import org.jspecify.annotations.Nullable;

/**
 * Formats item counts for slots: counts up to 99 render as vanilla
 * does, larger counts compactly in at most four characters ({@code 1.2k}, {@code 15k}, {@code 3.4M}),
 * and counts from 100 to 999, which have no compact form that fits, as {@code 99+}. Game-free.
 */
public final class ItemCounts {

    /** Largest count vanilla draws in full. */
    public static final int FULL = 99;

    private static final String[] UNITS = {"k", "M", "B"};

    private ItemCounts() {
    }

    /**
     * Returns the text drawn over a slot.
     *
     * @param count the item count as an unsigned 32-bit value
     * @param countText the bot's override, or null
     * @return the text, or null when nothing is drawn (a count of 1 or 0 without override)
     */
    public static @Nullable String text(final int count, final @Nullable String countText) {
        if (countText != null) {
            return countText;
        }
        final long n = Integer.toUnsignedLong(count);
        if (n <= 1) {
            return null;
        }
        if (n <= FULL) {
            return Long.toString(n);
        }
        if (n < 1000) {
            return FULL + "+";
        }
        return compact(n);
    }

    /**
     * Formats a count of at least 1000 with a unit, rounding down so a count never shows more than
     * it is: one decimal below ten units ({@code 1.2k}, {@code 9.9k}, {@code 1k}), whole units above.
     *
     * @param n the count, at least 1000
     * @return the compact text
     */
    public static String compact(final long n) {
        long unit = 1000;
        int index = 0;
        while (index < UNITS.length - 1 && n >= unit * 1000) {
            unit *= 1000;
            index++;
        }
        final long whole = n / unit;
        if (whole >= 10) {
            return whole + UNITS[index];
        }
        final long tenth = n * 10 / unit % 10;
        return tenth == 0 ? whole + UNITS[index] : whole + "." + tenth + UNITS[index];
    }

    /**
     * Returns the frame of an animated item to show ({@code frames}).
     *
     * @param frames the number of frames, at least 1
     * @param frameMs how long each frame shows, in ms; 0 or less shows the first frame
     * @param nowMs a monotonic clock in ms
     * @return the frame index
     */
    public static int frame(final int frames, final long frameMs, final long nowMs) {
        if (frames <= 1 || frameMs <= 0) {
            return 0;
        }
        return Math.floorMod(nowMs / frameMs, frames);
    }
}
