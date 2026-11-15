package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import io.github.pix3lpirat3.mfbridge.core.layout.HudLayout;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudAnchor;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Places HUD panels on screen: panels in an anchor slot stack away from their corner
 * or edge and collapse to their headers before they overlap (Overflow); panels with a free position
 * sit where the player dropped them, kept on screen. Also turns a drop in the HUD editor into a
 * {@link HudLayout}: near an anchor slot it snaps to the slot, elsewhere it is a free position.
 * All sizes are GUI pixels.
 */
public final class HudPlacement {
    /** Distance from the screen edge to the first panel. */
    public static final int MARGIN = 4;
    /** Gap between stacked panels. */
    public static final int GAP = 3;
    /** Height of a panel header, which is what a collapsed panel shows. */
    public static final int TITLE_HEIGHT = 12;
    /** Space kept free above the bottom edge for the hotbar, status bars and chat input (vanilla 26.3). */
    public static final int HOTBAR_RESERVE = 48;
    /** Narrowest panel. */
    public static final int MIN_WIDTH = 60;
    /** Widest panel the player can resize to. */
    public static final int MAX_WIDTH = 400;
    /** How close a dropped panel must come to an anchor slot to snap into it. */
    public static final int SNAP = 24;

    /**
     * One panel to place.
     *
     * @param key panel object key
     * @param anchor the slot it stacks in; ignored when {@code free}
     * @param free whether it has a free position
     * @param fx left edge of a free panel as a fraction of the screen width
     * @param fy top edge of a free panel as a fraction of the screen height
     * @param width panel width
     * @param fullHeight height with every widget shown
     * @param collapsed whether it shows only its header regardless of space
     */
    public record Panel(String key, HudAnchor anchor, boolean free, double fx, double fy, int width, int fullHeight,
            boolean collapsed) {
    }

    /**
     * A placed panel.
     *
     * @param key panel object key
     * @param x left edge
     * @param y top edge
     * @param width width
     * @param height drawn height
     * @param collapsed whether only the header is drawn
     */
    public record Placed(String key, int x, int y, int width, int height, boolean collapsed) {
    }

    private HudPlacement() {
    }

    /**
     * Applies a saved layout to a declared panel.
     *
     * @param key panel key
     * @param declared the anchor the bot declared
     * @param saved the player's layout, or null
     * @param naturalWidth width the panel would take by itself
     * @param fullHeight height with every widget shown at the effective width
     * @param collapsed whether the bot declared it collapsed
     * @return the panel to place
     */
    public static Panel panel(final String key, final HudAnchor declared, final @Nullable HudLayout saved, final int naturalWidth,
            final int fullHeight, final boolean collapsed) {
        final int width = effectiveWidth(saved, naturalWidth);
        if (saved == null) {
            return new Panel(key, declared, false, 0, 0, width, fullHeight, collapsed);
        }
        final HudAnchor anchor = saved.anchor();
        return new Panel(key, anchor == null ? declared : anchor, anchor == null, saved.x(), saved.y(), width, fullHeight, collapsed);
    }

    /**
     * Returns the width a panel is drawn at: the player's width when set, else its natural width,
     * within {@link #MIN_WIDTH} and {@link #MAX_WIDTH}.
     */
    public static int effectiveWidth(final @Nullable HudLayout saved, final int naturalWidth) {
        final int w = saved != null && saved.width() > 0 ? saved.width() : naturalWidth;
        return Math.clamp(w, MIN_WIDTH, MAX_WIDTH);
    }

    /**
     * Places panels. Within a slot, panels keep the order of the list.
     *
     * @param panels panels in declaration order
     * @param screenWidth GUI width
     * @param screenHeight GUI height
     * @param bossBottom y below the last boss bar, 0 when there is none; the top center slot starts below it
     * @param toastBottom y below the last toast, 0 when there is none; the top right slot starts below it
     * @return placed panels; a slot that runs out of room drops the rest of its panels
     */
    public static List<Placed> place(final List<Panel> panels, final int screenWidth, final int screenHeight, final int bossBottom,
            final int toastBottom) {
        final List<Placed> out = new ArrayList<>(panels.size());
        final Map<HudAnchor, List<Panel>> slots = new EnumMap<>(HudAnchor.class);
        for (final Panel p : panels) {
            if (p.free()) {
                out.add(free(p, screenWidth, screenHeight));
            } else {
                slots.computeIfAbsent(p.anchor(), a -> new ArrayList<>()).add(p);
            }
        }
        for (final Map.Entry<HudAnchor, List<Panel>> e : slots.entrySet()) {
            stack(e.getKey(), e.getValue(), screenWidth, screenHeight, bossBottom, toastBottom, out);
        }
        return out;
    }

    private static Placed free(final Panel p, final int screenWidth, final int screenHeight) {
        final int h = p.collapsed() ? TITLE_HEIGHT : p.fullHeight();
        final int x = Math.clamp(Math.round(p.fx() * screenWidth), 0, Math.max(0, screenWidth - p.width()));
        final int y = Math.clamp(Math.round(p.fy() * screenHeight), 0, Math.max(0, screenHeight - Math.min(h, screenHeight)));
        return new Placed(p.key(), x, y, p.width(), h, p.collapsed());
    }

    private static void stack(final HudAnchor anchor, final List<Panel> list, final int screenWidth, final int screenHeight,
            final int bossBottom, final int toastBottom, final List<Placed> out) {
        final int topStart = switch (anchor) {
            case TOP_CENTER -> Math.max(MARGIN, bossBottom + MARGIN);
            case TOP_RIGHT -> Math.max(MARGIN, toastBottom + MARGIN);
            default -> MARGIN;
        };
        final int limit = Math.max(TITLE_HEIGHT, screenHeight - topStart - HOTBAR_RESERVE);
        int total = 0;
        for (final Panel p : list) {
            total += p.fullHeight() + GAP;
        }
        int y = switch (anchor) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> topStart;
            case MIDDLE_LEFT, MIDDLE_RIGHT -> Math.max(MARGIN, screenHeight / 2 - Math.min(total, screenHeight / 2) / 2);
            case BOTTOM_LEFT, BOTTOM_RIGHT -> screenHeight - MARGIN;
        };
        final boolean upwards = anchor == HudAnchor.BOTTOM_LEFT || anchor == HudAnchor.BOTTOM_RIGHT;
        final int budget = anchor == HudAnchor.MIDDLE_LEFT || anchor == HudAnchor.MIDDLE_RIGHT ? screenHeight / 2 : limit;
        int used = 0;
        for (final Panel p : list) {
            // Panels collapse to their headers before they overlap.
            boolean collapsed = p.collapsed();
            int h = collapsed ? TITLE_HEIGHT : p.fullHeight();
            if (used + h > budget) {
                collapsed = true;
                h = TITLE_HEIGHT;
                if (used + h > budget) {
                    break;
                }
            }
            final int x = switch (anchor) {
                case TOP_LEFT, MIDDLE_LEFT, BOTTOM_LEFT -> MARGIN;
                case TOP_CENTER -> screenWidth / 2 - p.width() / 2;
                case TOP_RIGHT, MIDDLE_RIGHT, BOTTOM_RIGHT -> screenWidth - MARGIN - p.width();
            };
            final int top = upwards ? y - h : y;
            out.add(new Placed(p.key(), x, top, p.width(), h, collapsed));
            y = upwards ? y - h - GAP : y + h + GAP;
            used += h + GAP;
        }
    }

    /**
     * Returns the slot whose reference point is within {@link #SNAP} of the same point of a panel
     * at the given place, or null. A top-left slot compares top-left corners, a bottom-right slot
     * bottom-right corners, and so on.
     */
    public static @Nullable HudAnchor snap(final int x, final int y, final int width, final int height, final int screenWidth,
            final int screenHeight) {
        HudAnchor best = null;
        double bestDistance = SNAP;
        for (final HudAnchor a : HudAnchor.values()) {
            final double px = switch (a) {
                case TOP_LEFT, MIDDLE_LEFT, BOTTOM_LEFT -> x;
                case TOP_CENTER -> x + width / 2.0;
                case TOP_RIGHT, MIDDLE_RIGHT, BOTTOM_RIGHT -> x + width;
            };
            final double py = switch (a) {
                case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> y;
                case MIDDLE_LEFT, MIDDLE_RIGHT -> y + height / 2.0;
                case BOTTOM_LEFT, BOTTOM_RIGHT -> y + height;
            };
            final double ax = switch (a) {
                case TOP_LEFT, MIDDLE_LEFT, BOTTOM_LEFT -> MARGIN;
                case TOP_CENTER -> screenWidth / 2.0;
                case TOP_RIGHT, MIDDLE_RIGHT, BOTTOM_RIGHT -> screenWidth - MARGIN;
            };
            final double ay = switch (a) {
                case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> MARGIN;
                case MIDDLE_LEFT, MIDDLE_RIGHT -> screenHeight / 2.0;
                case BOTTOM_LEFT, BOTTOM_RIGHT -> screenHeight - MARGIN;
            };
            final double d = Math.hypot(px - ax, py - ay);
            if (d < bestDistance) {
                bestDistance = d;
                best = a;
            }
        }
        return best;
    }

    /**
     * Turns a drop in the HUD editor into a layout: the snapped slot when near one, else a free
     * position as screen fractions. Width and visibility are carried over from {@code previous}.
     *
     * @param x left edge where the panel was dropped
     * @param y top edge where the panel was dropped
     * @param previous the panel's layout before the drag, or null
     */
    public static HudLayout dropped(final int x, final int y, final int width, final int height, final int screenWidth,
            final int screenHeight, final @Nullable HudLayout previous) {
        final HudAnchor snapped = snap(x, y, width, height, screenWidth, screenHeight);
        final int keptWidth = previous == null ? 0 : previous.width();
        final boolean hidden = previous != null && previous.hidden();
        if (snapped != null) {
            return new HudLayout(snapped, 0, 0, keptWidth, hidden);
        }
        final double fx = screenWidth <= 0 ? 0 : (double) Math.clamp(x, 0, Math.max(0, screenWidth - width)) / screenWidth;
        final double fy = screenHeight <= 0 ? 0 : (double) Math.clamp(y, 0, Math.max(0, screenHeight - height)) / screenHeight;
        return new HudLayout(null, fx, fy, keptWidth, hidden);
    }
}
