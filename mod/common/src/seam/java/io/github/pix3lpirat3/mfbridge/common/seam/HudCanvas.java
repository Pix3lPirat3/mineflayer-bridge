package io.github.pix3lpirat3.mfbridge.common.seam;

/**
 * Screen-space drawing surface for HUD elements. Coordinates are GUI-scaled pixels with the origin at
 * the top left; colours are packed {@code 0xRRGGBBAA}. Valid only during the callback that received it.
 */
public interface HudCanvas {

    /**
     * Returns the GUI-scaled screen width.
     *
     * @return width, in GUI pixels
     */
    int width();

    /**
     * Returns the GUI-scaled screen height.
     *
     * @return height, in GUI pixels
     */
    int height();

    /**
     * Fills an axis-aligned rectangle.
     *
     * @param x0 left edge, inclusive
     * @param y0 top edge, inclusive
     * @param x1 right edge, exclusive
     * @param y1 bottom edge, exclusive
     * @param rgba fill colour
     */
    void fill(int x0, int y0, int x1, int y1, int rgba);

    /**
     * Draws one line of literal text with a drop shadow.
     *
     * @param text literal text, not a translation key
     * @param x left edge
     * @param y top edge
     * @param rgba text colour
     */
    void text(String text, int x, int y, int rgba);

    /**
     * Measures literal text in the HUD font.
     *
     * @param text literal text
     * @return width, in GUI pixels
     */
    int textWidth(String text);
}
