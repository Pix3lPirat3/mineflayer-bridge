package io.github.pix3lpirat3.mfbridge.common.ui.logic;

/**
 * Sizes panels to the window and works out scrolling: a panel's width follows the window between
 * a minimum and a maximum and never passes its edges, and a column taller than its viewport
 * scrolls with a scrollbar thumb sized to what is visible. Sizes are GUI pixels.
 */
public final class PanelLayout {

    /** Space kept between a panel and the window edge. */
    public static final int MARGIN = 8;
    /** Width of a scrollbar, track and thumb. */
    public static final int SCROLLBAR_WIDTH = 6;
    /** Shortest a scrollbar thumb gets. */
    public static final int MIN_THUMB = 8;

    private PanelLayout() {
    }

    /**
     * Returns a panel's width for a window.
     *
     * @param screenWidth the window width
     * @param min the narrowest the panel should be, kept only while the window has room
     * @param max the widest the panel should be
     * @param fraction the share of the window the panel takes between those bounds, 0 to 1
     * @return the width, never more than the window minus {@link #MARGIN} on each side (and at least 1)
     */
    public static int width(final int screenWidth, final int min, final int max, final double fraction) {
        final int room = Math.max(1, screenWidth - 2 * MARGIN);
        final int wanted = Math.clamp((long) Math.floor(screenWidth * fraction), Math.min(min, max), Math.max(min, max));
        return Math.min(room, wanted);
    }

    /**
     * Scrolling of a column in a viewport.
     *
     * @param content the column's full height
     * @param viewport the visible height
     */
    public record Scroll(int content, int viewport) {

        /**
         * Returns how far the column can scroll.
         *
         * @return 0 when it fits, else the hidden height
         */
        public int max() {
            return Math.max(0, content - Math.max(0, viewport));
        }

        /**
         * Returns whether the column needs a scrollbar.
         *
         * @return true when it does not fit
         */
        public boolean scrolls() {
            return max() > 0;
        }

        /**
         * Keeps a scroll offset in range.
         *
         * @param offset any offset
         * @return the offset between 0 and {@link #max()}
         */
        public int clamp(final int offset) {
            return Math.clamp(offset, 0, max());
        }

        /**
         * Returns the scrollbar thumb for an offset, within a track as tall as the viewport.
         *
         * @param offset the scroll offset
         * @return the thumb's top relative to the track and its height; the whole track when it fits
         */
        public int[] thumb(final int offset) {
            final int track = Math.max(1, viewport);
            if (!scrolls()) {
                return new int[] {0, track};
            }
            final int height = Math.clamp((long) track * track / Math.max(1, content), Math.min(MIN_THUMB, track), track);
            final int top = (int) ((long) (track - height) * clamp(offset) / max());
            return new int[] {top, height};
        }

        /**
         * Returns the offset that puts the thumb's centre at a point of the track, for a drag.
         *
         * @param trackY the point, relative to the top of the track
         * @return the offset, in range
         */
        public int offsetAt(final double trackY) {
            if (!scrolls()) {
                return 0;
            }
            final int track = Math.max(1, viewport);
            final int height = thumb(0)[1];
            final double free = Math.max(1, track - height);
            return clamp((int) Math.round((trackY - height / 2d) / free * max()));
        }
    }
}
