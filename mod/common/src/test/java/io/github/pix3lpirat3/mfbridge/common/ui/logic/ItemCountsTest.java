package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ItemCountsTest {

    @Test
    void smallCountsRenderAsVanilla() {
        assertNull(ItemCounts.text(1, null));
        assertNull(ItemCounts.text(0, null));
        assertEquals("2", ItemCounts.text(2, null));
        assertEquals("99", ItemCounts.text(99, null));
    }

    @Test
    void largeCountsRenderCompactly() {
        assertEquals("99+", ItemCounts.text(100, null));
        assertEquals("99+", ItemCounts.text(999, null));
        assertEquals("1k", ItemCounts.text(1000, null));
        assertEquals("1.2k", ItemCounts.text(1250, null));
        assertEquals("9.9k", ItemCounts.text(9999, null), "rounds down");
        assertEquals("15k", ItemCounts.text(15_400, null));
        assertEquals("999k", ItemCounts.text(999_999, null));
        assertEquals("1M", ItemCounts.text(1_000_000, null));
        assertEquals("3.4M", ItemCounts.text(3_456_789, null));
        assertEquals("4.2B", ItemCounts.text((int) 4_294_967_295L, null), "counts are unsigned");
    }

    @Test
    void countTextWins() {
        assertEquals("x", ItemCounts.text(5000, "x"));
        assertEquals("", ItemCounts.text(1, ""));
    }

    @Test
    void framesCycleAtFrameMs() {
        assertEquals(0, ItemCounts.frame(3, 500, 499));
        assertEquals(1, ItemCounts.frame(3, 500, 500));
        assertEquals(0, ItemCounts.frame(3, 500, 1500));
        assertEquals(0, ItemCounts.frame(1, 500, 9999));
        assertEquals(0, ItemCounts.frame(4, 0, 9999));
    }
}
