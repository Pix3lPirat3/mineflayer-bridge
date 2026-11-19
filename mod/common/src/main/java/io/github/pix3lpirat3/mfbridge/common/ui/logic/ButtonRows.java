package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays buttons out in centred rows that wrap when they do not fit the width. A button keeps its
 * preferred width within the minimum and the row width; a label wider than its button is the
 * caller's to shorten. Widths are GUI pixels.
 */
public final class ButtonRows {

    /**
     * Where one button goes.
     *
     * @param index the button's position in the input
     * @param row its row, from 0
     * @param x its left edge, relative to the left of the area
     * @param width its width
     */
    public record Placed(int index, int row, int x, int width) {
    }

    private ButtonRows() {
    }

    /**
     * Places buttons in rows.
     *
     * @param preferred each button's preferred width, in order
     * @param available the width of the area
     * @param gap the space between buttons in a row
     * @param minWidth the narrowest button, unless the area is narrower
     * @return one entry per button, in input order
     */
    public static List<Placed> layout(final int[] preferred, final int available, final int gap, final int minWidth) {
        final int area = Math.max(1, available);
        final int[] widths = new int[preferred.length];
        for (int i = 0; i < preferred.length; i++) {
            widths[i] = Math.min(area, Math.max(Math.min(minWidth, area), preferred[i]));
        }
        final List<Placed> out = new ArrayList<>(preferred.length);
        int start = 0;
        int row = 0;
        while (start < widths.length) {
            int end = start;
            int used = 0;
            while (end < widths.length) {
                final int next = used + (end == start ? 0 : gap) + widths[end];
                if (end > start && next > area) {
                    break;
                }
                used = next;
                end++;
            }
            int x = (area - used) / 2;
            for (int i = start; i < end; i++) {
                out.add(new Placed(i, row, x, widths[i]));
                x += widths[i] + gap;
            }
            start = end;
            row++;
        }
        return out;
    }

    /**
     * Returns how many rows a layout uses.
     *
     * @param placed a result of {@link #layout}
     * @return the row count, 0 for no buttons
     */
    public static int rows(final List<Placed> placed) {
        return placed.isEmpty() ? 0 : placed.getLast().row() + 1;
    }
}
