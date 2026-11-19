package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Wraps and shortens plain text to a width measured by a text-width function, such as the game
 * font's {@code width}. Lines break at spaces; a word wider than a line is broken between
 * characters. Newlines start a new line. Widths are GUI pixels.
 */
public final class TextWrap {

    /** What a shortened text ends with, as vanilla's {@code CommonComponents.ELLIPSIS}. */
    public static final String ELLIPSIS = "...";

    /**
     * Text wrapped to a number of lines.
     *
     * @param lines the lines to draw, at most the line limit
     * @param clamped whether text was left out, so the full text belongs in a tooltip
     */
    public record Wrapped(List<String> lines, boolean clamped) {
        /** Copies the lines. */
        public Wrapped {
            lines = List.copyOf(lines);
        }

        /**
         * Returns the number of lines.
         *
         * @return the line count
         */
        public int size() {
            return lines.size();
        }
    }

    private TextWrap() {
    }

    /**
     * Wraps text to a width.
     *
     * @param text the text; null or empty gives no lines
     * @param maxWidth the widest a line may be; below 1 counts as 1
     * @param width measures a string
     * @return the lines, none wider than {@code maxWidth} unless one character already is
     */
    public static List<String> wrap(final String text, final int maxWidth, final ToIntFunction<String> width) {
        final List<String> out = new ArrayList<>();
        if (text.isEmpty()) {
            return out;
        }
        final int max = Math.max(1, maxWidth);
        for (final String paragraph : text.split("\r?\n", -1)) {
            wrapParagraph(paragraph, max, width, out);
        }
        return out;
    }

    private static void wrapParagraph(final String paragraph, final int max, final ToIntFunction<String> width, final List<String> out) {
        final String[] words = paragraph.split(" ", -1);
        final int first = out.size();
        final StringBuilder line = new StringBuilder();
        for (final String word : words) {
            if (word.isEmpty()) {
                // Runs of spaces keep one space each, as typed.
                if (!line.isEmpty() && width.applyAsInt(line + " ") <= max) {
                    line.append(' ');
                }
                continue;
            }
            final String joined = line.isEmpty() ? word : line + " " + word;
            if (width.applyAsInt(joined) <= max) {
                line.setLength(0);
                line.append(joined);
                continue;
            }
            if (!line.isEmpty()) {
                out.add(line.toString().stripTrailing());
                line.setLength(0);
            }
            String rest = word;
            while (width.applyAsInt(rest) > max) {
                final int cut = fitting(rest, max, width);
                out.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            line.append(rest);
        }
        if (!line.isEmpty() || out.size() == first) {
            out.add(line.toString().stripTrailing());
        }
    }

    /** Length of the longest prefix that fits, at least one character (two for a surrogate pair). */
    private static int fitting(final String s, final int max, final ToIntFunction<String> width) {
        int lo = 0;
        int hi = s.length();
        while (lo < hi) {
            final int mid = (lo + hi + 1) >>> 1;
            if (width.applyAsInt(s.substring(0, mid)) <= max) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        if (lo > 0 && lo < s.length() && Character.isHighSurrogate(s.charAt(lo - 1))) {
            lo--;
        }
        return lo == 0 ? Character.charCount(s.codePointAt(0)) : lo;
    }

    /**
     * Wraps text and keeps at most a number of lines; the last kept line ends with {@link #ELLIPSIS}
     * when anything was left out.
     *
     * @param text the text
     * @param maxWidth the widest a line may be
     * @param maxLines the most lines to keep; below 1 counts as 1
     * @param width measures a string
     * @return the lines and whether text was left out
     */
    public static Wrapped clamp(final String text, final int maxWidth, final int maxLines, final ToIntFunction<String> width) {
        final List<String> lines = wrap(text, maxWidth, width);
        final int limit = Math.max(1, maxLines);
        if (lines.size() <= limit) {
            return new Wrapped(lines, false);
        }
        final List<String> kept = new ArrayList<>(lines.subList(0, limit));
        kept.set(limit - 1, withEllipsis(kept.get(limit - 1), maxWidth, width));
        return new Wrapped(kept, true);
    }

    /**
     * Shortens text to one line, ending with {@link #ELLIPSIS} when it was cut. Newlines count as spaces.
     *
     * @param text the text
     * @param maxWidth the widest the line may be
     * @param width measures a string
     * @return the text itself when it fits, else the longest prefix with the ellipsis that fits, or
     *     an empty string when not even the ellipsis fits
     */
    public static String ellipsize(final String text, final int maxWidth, final ToIntFunction<String> width) {
        final String flat = text.replaceAll("\r?\n", " ");
        if (width.applyAsInt(flat) <= maxWidth) {
            return flat;
        }
        return withEllipsis(flat, maxWidth, width);
    }

    /**
     * Returns whether text needs more than one line, or is cut, at a width.
     *
     * @param text the text
     * @param maxWidth the width available
     * @param width measures a string
     * @return true when {@link #ellipsize} would cut it
     */
    public static boolean overflows(final String text, final int maxWidth, final ToIntFunction<String> width) {
        return text.indexOf('\n') >= 0 || width.applyAsInt(text) > maxWidth;
    }

    private static String withEllipsis(final String line, final int maxWidth, final ToIntFunction<String> width) {
        if (width.applyAsInt(ELLIPSIS) > maxWidth) {
            return "";
        }
        int lo = 0;
        int hi = line.length();
        while (lo < hi) {
            final int mid = (lo + hi + 1) >>> 1;
            if (width.applyAsInt(line.substring(0, mid).stripTrailing() + ELLIPSIS) <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        if (lo > 0 && lo < line.length() && Character.isHighSurrogate(line.charAt(lo - 1))) {
            lo--;
        }
        return line.substring(0, lo).stripTrailing() + ELLIPSIS;
    }
}
