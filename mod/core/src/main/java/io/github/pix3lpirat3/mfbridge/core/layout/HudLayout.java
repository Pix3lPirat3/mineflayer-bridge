package io.github.pix3lpirat3.mfbridge.core.layout;

import io.github.pix3lpirat3.mfbridge.protocol.core.HudAnchor;
import org.jspecify.annotations.Nullable;

/**
 * The player's placement of one HUD panel: an anchor slot or a free position, a
 * width and visibility.
 *
 * @param anchor the anchor slot the panel stacks in, or null for a free position
 * @param x left edge of a free panel as a fraction of the GUI width, clamped to 0..1
 * @param y top edge of a free panel as a fraction of the GUI height, clamped to 0..1
 * @param width panel width in GUI pixels, or 0 for the natural width
 * @param hidden whether the panel is hidden
 */
public record HudLayout(@Nullable HudAnchor anchor, double x, double y, int width, boolean hidden) {
    /** Clamps the fractions and the width. */
    public HudLayout {
        x = Double.isFinite(x) ? Math.clamp(x, 0d, 1d) : 0d;
        y = Double.isFinite(y) ? Math.clamp(y, 0d, 1d) : 0d;
        width = Math.max(0, width);
    }

    /** Returns a layout that keeps the panel in a slot at its natural width. */
    public static HudLayout anchored(HudAnchor anchor) {
        return new HudLayout(anchor, 0, 0, 0, false);
    }

    /** Returns a layout with a free position. */
    public static HudLayout free(double x, double y) {
        return new HudLayout(null, x, y, 0, false);
    }

    /** Returns whether the panel has a free position. */
    public boolean isFree() {
        return anchor == null;
    }

    /** Returns a copy with another width. */
    public HudLayout withWidth(int newWidth) {
        return new HudLayout(anchor, x, y, newWidth, hidden);
    }

    /** Returns a copy with another visibility. */
    public HudLayout withHidden(boolean newHidden) {
        return new HudLayout(anchor, x, y, width, newHidden);
    }
}
