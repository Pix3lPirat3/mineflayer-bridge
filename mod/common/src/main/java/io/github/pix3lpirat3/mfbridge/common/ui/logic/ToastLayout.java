package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Lays out a bridge toast: one title line and a body wrapped to the toast's width, clamped so the
 * toast takes at most {@link #MAX_SLOTS} of the game's toast slots and so always gets shown. Sizes
 * are GUI pixels; the line spacing and paddings follow vanilla's system toast.
 *
 * @param width the toast's width
 * @param title the title line, shortened to fit
 * @param body the body lines
 * @param clamped whether the title or the body was cut
 * @param height the toast's height
 */
public record ToastLayout(int width, String title, List<String> body, boolean clamped, int height) {

    /** Height of one toast slot in vanilla's toast manager. */
    public static final int SLOT_HEIGHT = 32;
    /** Most slots a bridge toast takes; vanilla shows five at a time. */
    public static final int MAX_SLOTS = 3;
    /** Widest toast. */
    public static final int MAX_WIDTH = 240;
    /** Narrowest toast, the vanilla default. */
    public static final int MIN_WIDTH = 160;
    /** Left of the text, past the stripe of vanilla's toast background. */
    public static final int PADDING_X = 18;
    /** Space left and right of the text together. */
    private static final int PADDING_SIDES = 30;
    /** Top of the title line. */
    public static final int PADDING_Y = 7;
    /** Height of a toast without its body lines, as vanilla's system toast. */
    private static final int BASE_HEIGHT = 20;
    /** Vertical step between lines. */
    public static final int LINE = 12;

    /** Copies the body. */
    public ToastLayout {
        body = List.copyOf(body);
    }

    /**
     * Lays out a toast.
     *
     * @param title the title, usually a bot name
     * @param message the body, empty for none
     * @param screenWidth the window width; the toast never gets wider than half of it, nor narrower
     *     than {@link #MIN_WIDTH} unless the window is
     * @param width measures a string
     * @return the layout
     */
    public static ToastLayout of(final String title, final String message, final int screenWidth, final ToIntFunction<String> width) {
        final int widest = Math.max(width.applyAsInt(title), width.applyAsInt(message)) + PADDING_SIDES;
        final int cap = Math.max(Math.min(MIN_WIDTH, screenWidth), Math.min(MAX_WIDTH, screenWidth / 2));
        final int w = Math.min(cap, Math.max(MIN_WIDTH, widest));
        final int text = Math.max(1, w - PADDING_SIDES);
        final String t = TextWrap.ellipsize(title, text, width);
        final int maxBody = (MAX_SLOTS * SLOT_HEIGHT - BASE_HEIGHT) / LINE;
        final TextWrap.Wrapped body = TextWrap.clamp(message, text, maxBody, width);
        final int height = BASE_HEIGHT + LINE * Math.max(1, body.size());
        return new ToastLayout(w, t, body.lines(), body.clamped() || !t.equals(title), height);
    }

    /**
     * Returns the width the text may take.
     *
     * @return the toast's width less its paddings
     */
    public int textWidth() {
        return Math.max(1, width - PADDING_SIDES);
    }

    /**
     * Returns how many toast slots the toast takes.
     *
     * @return 1 to {@link #MAX_SLOTS}
     */
    public int slots() {
        return (height + SLOT_HEIGHT - 1) / SLOT_HEIGHT;
    }
}
