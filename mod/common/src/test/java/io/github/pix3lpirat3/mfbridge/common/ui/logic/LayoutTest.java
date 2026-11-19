package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

final class LayoutTest {
    /** Every character 6 pixels wide, as most of the game font; 'i' and space narrower, as there. */
    private static final ToIntFunction<String> FONT = s -> {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            w += c == 'i' || c == '.' ? 2 : c == ' ' ? 4 : 6;
        }
        return w;
    };

    private static final String QUESTION =
            "drop 64 cobblestone, 64 cobblestone, 64 cobblestone, 64 cobblestone and 4 more at 0 100 -3, outside your zones";

    @Test
    void wrapsAtSpacesWithinTheWidth() {
        final List<String> lines = TextWrap.wrap(QUESTION, 200, FONT);
        assertTrue(lines.size() > 1);
        for (final String line : lines) {
            assertTrue(FONT.applyAsInt(line) <= 200, line);
            assertFalse(line.startsWith(" ") || line.endsWith(" "), line);
        }
        assertEquals(QUESTION, String.join(" ", lines), "only spaces are lost at breaks");
    }

    @Test
    void wrapsAtSeveralWidths() {
        for (final int w : new int[] {40, 80, 120, 250, 400, 2000}) {
            final List<String> lines = TextWrap.wrap(QUESTION, w, FONT);
            for (final String line : lines) {
                assertTrue(FONT.applyAsInt(line) <= w, w + ": " + line);
            }
            assertEquals(QUESTION.replace(" ", ""), String.join("", lines).replace(" ", ""), "nothing lost at " + w);
        }
        assertEquals(List.of(QUESTION), TextWrap.wrap(QUESTION, 2000, FONT));
    }

    @Test
    void breaksWordsWiderThanALine() {
        final List<String> lines = TextWrap.wrap("aaaaaaaaaa bb", 24, FONT);
        assertEquals(List.of("aaaa", "aaaa", "aa", "bb"), lines);
        assertEquals(List.of("a", "a"), TextWrap.wrap("aa", 1, FONT), "a line holds one character at least");
    }

    @Test
    void keepsNewlinesAndEmptyText() {
        assertEquals(List.of("one", "", "two"), TextWrap.wrap("one\n\ntwo", 200, FONT));
        assertEquals(List.of(), TextWrap.wrap("", 200, FONT));
    }

    @Test
    void doesNotSplitSurrogatePairs() {
        final String emoji = "😀😀😀";
        for (final String line : TextWrap.wrap(emoji, 13, FONT)) {
            assertFalse(Character.isHighSurrogate(line.charAt(line.length() - 1)), line);
        }
    }

    @Test
    void clampsToALineLimitWithAnEllipsis() {
        final TextWrap.Wrapped two = TextWrap.clamp(QUESTION, 120, 2, FONT);
        assertTrue(two.clamped());
        assertEquals(2, two.size());
        assertTrue(two.lines().get(1).endsWith(TextWrap.ELLIPSIS));
        assertTrue(FONT.applyAsInt(two.lines().get(1)) <= 120);

        final TextWrap.Wrapped all = TextWrap.clamp(QUESTION, 120, 50, FONT);
        assertFalse(all.clamped());
        assertEquals(TextWrap.wrap(QUESTION, 120, FONT), all.lines());
    }

    @Test
    void ellipsizesOneLine() {
        assertEquals("short", TextWrap.ellipsize("short", 100, FONT));
        final String cut = TextWrap.ellipsize("Always, every order, all bots", 100, FONT);
        assertTrue(cut.endsWith(TextWrap.ELLIPSIS));
        assertTrue(FONT.applyAsInt(cut) <= 100);
        assertEquals("", TextWrap.ellipsize("anything", 4, FONT), "no room even for the ellipsis");
        assertEquals("a b", TextWrap.ellipsize("a\nb", 100, FONT));
        assertTrue(TextWrap.overflows("a\nb", 100, FONT));
        assertFalse(TextWrap.overflows("ab", 100, FONT));
    }

    @Test
    void buttonRowsWrapAndCentre() {
        // Eight 100 wide answers of the permission question.
        final int[] widths = {100, 100, 100, 100, 100, 100, 100, 100};
        final List<ButtonRows.Placed> wide = ButtonRows.layout(widths, 900, 4, 40);
        assertEquals(1, ButtonRows.rows(wide));
        assertEquals((900 - (8 * 100 + 7 * 4)) / 2, wide.getFirst().x());

        final List<ButtonRows.Placed> narrow = ButtonRows.layout(widths, 410, 4, 40);
        assertEquals(3, ButtonRows.rows(narrow));
        assertEquals(new ButtonRows.Placed(4, 1, (410 - 308) / 2 + 104, 100), narrow.get(4), "rows of four would be 412 wide");
        for (final ButtonRows.Placed p : narrow) {
            assertTrue(p.x() >= 0 && p.x() + p.width() <= 410, p.toString());
        }

        final List<ButtonRows.Placed> tiny = ButtonRows.layout(new int[] {300, 20}, 150, 4, 40);
        assertEquals(new ButtonRows.Placed(0, 0, 0, 150), tiny.get(0), "a button never gets wider than the row");
        assertEquals(new ButtonRows.Placed(1, 1, 55, 40), tiny.get(1), "nor narrower than the minimum");
        assertEquals(0, ButtonRows.rows(ButtonRows.layout(new int[0], 100, 4, 40)));
    }

    @Test
    void panelWidthFollowsTheWindow() {
        assertEquals(320, PanelLayout.width(1000, 200, 320, 0.9));
        assertEquals(270, PanelLayout.width(300, 200, 320, 0.9));
        assertEquals(200, PanelLayout.width(220, 200, 320, 0.5), "the minimum while the window has room");
        assertEquals(144, PanelLayout.width(160, 200, 320, 0.9), "never past the window edges");
        assertEquals(1, PanelLayout.width(10, 200, 320, 0.9));
    }

    @Test
    void scrollExtentsAndThumb() {
        final PanelLayout.Scroll fits = new PanelLayout.Scroll(100, 200);
        assertFalse(fits.scrolls());
        assertEquals(0, fits.clamp(50));
        assertArrayEquals(new int[] {0, 200}, fits.thumb(0));

        final PanelLayout.Scroll tall = new PanelLayout.Scroll(400, 100);
        assertEquals(300, tall.max());
        assertEquals(300, tall.clamp(999));
        assertEquals(0, tall.clamp(-5));
        assertArrayEquals(new int[] {0, 25}, tall.thumb(0));
        assertArrayEquals(new int[] {75, 25}, tall.thumb(300));
        assertEquals(0, tall.offsetAt(0));
        assertEquals(300, tall.offsetAt(100));
        assertEquals(150, tall.offsetAt(50));

        final PanelLayout.Scroll huge = new PanelLayout.Scroll(100_000, 100);
        assertEquals(PanelLayout.MIN_THUMB, huge.thumb(0)[1]);
    }

    @Test
    void toastsWrapAndStayWithinThreeSlots() {
        final ToastLayout shortOne = ToastLayout.of("eco1", "done", 800, FONT);
        assertEquals(ToastLayout.MIN_WIDTH, shortOne.width());
        assertEquals(1, shortOne.slots());
        assertFalse(shortOne.clamped());

        final ToastLayout question = ToastLayout.of("eco1", QUESTION, 800, FONT);
        assertEquals(ToastLayout.MAX_WIDTH, question.width());
        assertTrue(question.body().size() > 1);
        for (final String line : question.body()) {
            assertTrue(FONT.applyAsInt(line) <= question.textWidth(), line);
        }

        final ToastLayout huge = ToastLayout.of("eco1", QUESTION.repeat(10), 800, FONT);
        assertTrue(huge.clamped());
        assertEquals(ToastLayout.MAX_SLOTS, huge.slots());
        assertTrue(huge.body().getLast().endsWith(TextWrap.ELLIPSIS));

        final ToastLayout small = ToastLayout.of("eco1", QUESTION, 300, FONT);
        assertEquals(ToastLayout.MIN_WIDTH, small.width(), "half of a narrow window, but not under the vanilla width");
        final ToastLayout longTitle = ToastLayout.of("x".repeat(100), "", 800, FONT);
        assertTrue(longTitle.clamped());
        assertTrue(FONT.applyAsInt(longTitle.title()) <= longTitle.textWidth());
    }
}
