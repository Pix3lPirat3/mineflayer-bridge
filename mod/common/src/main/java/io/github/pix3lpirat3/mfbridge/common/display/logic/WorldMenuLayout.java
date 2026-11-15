package io.github.pix3lpirat3.mfbridge.common.display.logic;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Places world menu entries around their anchor ({@code row}, {@code column},
 * {@code ring} and {@code grid}) and splits long menus into pages. Offsets are in blocks within a
 * plane that faces the player: {@code right} along the player's right, {@code up} along world up.
 */
public enum WorldMenuLayout {
    /** A horizontal line, centred on the anchor. */
    ROW(6),
    /** A vertical line, centred on the anchor, first entry on top. */
    COLUMN(6),
    /** A circle of the request's radius, first entry on top, then clockwise. */
    RING(8),
    /** A square-ish grid, filled row by row from the top left. */
    GRID(9);

    /** Smallest spacing between neighbouring entries, blocks. */
    public static final double MIN_SPACING = 0.5d;

    private final int pageSize;

    WorldMenuLayout(final int pageSize) {
        this.pageSize = pageSize;
    }

    /**
     * Parses a wire name; unknown names and null mean {@link #RING}, the schema default.
     *
     * @param name {@code row}, {@code column}, {@code ring} or {@code grid}
     * @return the layout
     */
    public static WorldMenuLayout of(final @Nullable String name) {
        if (name == null) {
            return RING;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "row" -> ROW;
            case "column" -> COLUMN;
            case "grid" -> GRID;
            default -> RING;
        };
    }

    /**
     * Returns how many entries one page shows.
     *
     * @return entries per page, at least 1
     */
    public int pageSize() {
        return pageSize;
    }

    /**
     * Returns the number of pages for a menu.
     *
     * @param entries total entries
     * @return pages, at least 1
     */
    public int pages(final int entries) {
        return Math.max(1, (entries + pageSize - 1) / pageSize);
    }

    /**
     * Returns how many entries the page holding {@code index} shows.
     *
     * @param index an entry index
     * @param entries total entries
     * @return entries on that page
     */
    public int countOnPage(final int index, final int entries) {
        final int first = index / pageSize * pageSize;
        return Math.max(0, Math.min(pageSize, entries - first));
    }

    /**
     * Returns the spacing between neighbouring entries for a radius.
     *
     * @param radius the request's radius, blocks
     * @return spacing, blocks
     */
    public static double spacing(final double radius) {
        return Math.max(MIN_SPACING, radius * 0.5d);
    }

    /**
     * Computes the offset of one entry from the anchor.
     *
     * @param slot the entry's position on its page, {@code 0 <= slot < count}
     * @param count entries on the page
     * @param radius the request's radius, blocks
     * @param out receives {@code right} then {@code up}, blocks
     */
    public void place(final int slot, final int count, final double radius, final double[] out) {
        final int n = Math.max(1, count);
        final double s = spacing(radius);
        switch (this) {
            case ROW -> {
                out[0] = (slot - (n - 1) / 2d) * s;
                out[1] = 0d;
            }
            case COLUMN -> {
                out[0] = 0d;
                out[1] = ((n - 1) / 2d - slot) * s;
            }
            case RING -> {
                if (n == 1) {
                    out[0] = 0d;
                    out[1] = 0d;
                } else {
                    final double angle = Math.PI * 2d * slot / n;
                    out[0] = radius * Math.sin(angle);
                    out[1] = radius * Math.cos(angle);
                }
            }
            case GRID -> {
                final int cols = (int) Math.ceil(Math.sqrt(n));
                final int rows = (n + cols - 1) / cols;
                out[0] = (slot % cols - (cols - 1) / 2d) * s;
                out[1] = ((rows - 1) / 2d - slot / cols) * s;
            }
        }
    }

    /**
     * Moves a highlight by scroll steps, wrapping around all entries; scroll
     * cycles through entries and pages when there are many.
     *
     * @param index the highlighted index, or -1 for none
     * @param steps scroll steps, positive moves forward
     * @param entries total entries
     * @return the new index, or -1 when the menu is empty
     */
    public static int cycle(final int index, final int steps, final int entries) {
        if (entries <= 0) {
            return -1;
        }
        final int from = index < 0 ? (steps >= 0 ? -1 : 0) : index;
        return Math.floorMod(from + steps, entries);
    }
}
