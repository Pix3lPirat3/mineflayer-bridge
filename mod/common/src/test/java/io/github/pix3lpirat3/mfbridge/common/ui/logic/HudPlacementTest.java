package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.layout.HudLayout;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudAnchor;
import java.util.List;
import org.junit.jupiter.api.Test;

final class HudPlacementTest {
    private static final int W = 400;
    private static final int H = 300;

    @Test
    void stacksPanelsInTheirSlots() {
        final List<HudPlacement.Placed> placed = HudPlacement.place(List.of(
                HudPlacement.panel("a", HudAnchor.TOP_LEFT, null, 100, 50, false),
                HudPlacement.panel("b", HudAnchor.TOP_LEFT, null, 120, 40, false),
                HudPlacement.panel("c", HudAnchor.BOTTOM_RIGHT, null, 100, 30, false)), W, H, 0, 0);
        assertEquals(new HudPlacement.Placed("a", 4, 4, 100, 50, false), placed.get(0));
        assertEquals(new HudPlacement.Placed("b", 4, 4 + 50 + 3, 120, 40, false), placed.get(1));
        assertEquals(new HudPlacement.Placed("c", W - 4 - 100, H - 4 - 30, 100, 30, false), placed.get(2));
    }

    @Test
    void savedLayoutsMoveResizeAndFreePanels() {
        final HudLayout moved = HudLayout.anchored(HudAnchor.TOP_RIGHT).withWidth(150);
        final HudLayout free = HudLayout.free(0.5, 0.5);
        final List<HudPlacement.Placed> placed = HudPlacement.place(List.of(
                HudPlacement.panel("a", HudAnchor.TOP_LEFT, moved, 100, 50, false),
                HudPlacement.panel("b", HudAnchor.TOP_LEFT, free, 100, 40, false)), W, H, 0, 0);
        assertEquals(new HudPlacement.Placed("b", 200, 150, 100, 40, false), placed.get(0));
        assertEquals(new HudPlacement.Placed("a", W - 4 - 150, 4, 150, 50, false), placed.get(1));

        final HudPlacement.Placed offscreen = HudPlacement.place(List.of(
                HudPlacement.panel("c", HudAnchor.TOP_LEFT, HudLayout.free(1, 1), 100, 40, false)), W, H, 0, 0).get(0);
        assertEquals(W - 100, offscreen.x(), "free panels stay on screen");
        assertEquals(H - 40, offscreen.y());
        assertEquals(HudPlacement.MAX_WIDTH, HudPlacement.effectiveWidth(HudLayout.free(0, 0).withWidth(9_999), 100));
        assertEquals(HudPlacement.MIN_WIDTH, HudPlacement.effectiveWidth(null, 10));
    }

    @Test
    void topRightPanelsStartBelowToasts() {
        final List<HudPlacement.Placed> placed = HudPlacement.place(List.of(
                HudPlacement.panel("a", HudAnchor.TOP_RIGHT, null, 100, 50, false),
                HudPlacement.panel("b", HudAnchor.TOP_LEFT, null, 100, 50, false)), W, H, 0, 64);
        final HudPlacement.Placed right = placed.stream().filter(p -> p.key().equals("a")).findFirst().orElseThrow();
        final HudPlacement.Placed left = placed.stream().filter(p -> p.key().equals("b")).findFirst().orElseThrow();
        assertEquals(64 + 4, right.y());
        assertEquals(4, left.y());
    }

    @Test
    void fullSlotsCollapseToHeadersBeforeOverlapping() {
        final List<HudPlacement.Placed> placed = HudPlacement.place(List.of(
                HudPlacement.panel("a", HudAnchor.TOP_LEFT, null, 100, 200, false),
                HudPlacement.panel("b", HudAnchor.TOP_LEFT, null, 100, 200, false)), W, H, 0, 0);
        assertFalse(placed.get(0).collapsed());
        assertTrue(placed.get(1).collapsed());
        assertEquals(HudPlacement.TITLE_HEIGHT, placed.get(1).height());
    }

    @Test
    void dropsSnapToNearbySlotsOrBecomeFree() {
        assertEquals(HudAnchor.BOTTOM_RIGHT, HudPlacement.dropped(W - 110, H - 45, 100, 40, W, H, null).anchor());
        assertEquals(HudAnchor.TOP_CENTER, HudPlacement.dropped(150, 10, 100, 40, W, H, null).anchor());
        final HudLayout prior = HudLayout.anchored(HudAnchor.TOP_LEFT).withWidth(180).withHidden(false);
        final HudLayout free = HudPlacement.dropped(100, 150, 100, 40, W, H, prior);
        assertNull(free.anchor());
        assertEquals(0.25, free.x(), 1e-9);
        assertEquals(0.5, free.y(), 1e-9);
        assertEquals(180, free.width(), "the player's width survives a move");
    }
}
