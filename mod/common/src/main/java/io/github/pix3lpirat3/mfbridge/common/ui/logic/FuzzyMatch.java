package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import java.util.Locale;

/** Subsequence fuzzy matching for the command palette. */
public final class FuzzyMatch {

    /** Score of an empty query; every candidate matches it equally. */
    public static final int EMPTY_QUERY_SCORE = 1;

    private static final int BASE = 1;
    private static final int CONSECUTIVE_BONUS = 5;
    private static final int WORD_START_BONUS = 8;
    private static final int PREFIX_BONUS = 20;

    private FuzzyMatch() {
    }

    /**
     * Scores how well {@code query} matches {@code candidate}. Every query character must appear in the
     * candidate in order, ignoring case; consecutive runs, word starts and a whole-prefix match score
     * higher.
     *
     * @param query what the player typed; surrounding whitespace is ignored
     * @param candidate the text to match against
     * @return a positive score, higher is better, or -1 when the query is not a subsequence
     */
    public static int score(final String query, final String candidate) {
        final String q = query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return EMPTY_QUERY_SCORE;
        }
        final String c = candidate.toLowerCase(Locale.ROOT);
        int qi = 0;
        int score = 0;
        int previous = -2;
        for (int ci = 0; ci < c.length() && qi < q.length(); ci++) {
            final char ch = c.charAt(ci);
            if (ch != q.charAt(qi)) {
                continue;
            }
            int bonus = BASE;
            if (ci == previous + 1) {
                bonus += CONSECUTIVE_BONUS;
            }
            if (ci == 0 || !Character.isLetterOrDigit(c.charAt(ci - 1))) {
                bonus += WORD_START_BONUS;
            }
            score += bonus;
            previous = ci;
            qi++;
        }
        if (qi < q.length()) {
            return -1;
        }
        if (c.startsWith(q)) {
            score += PREFIX_BONUS;
        }
        // Shorter candidates win ties, so "mine" ranks above "mine-everything" for the query "mine".
        score -= (c.length() - q.length()) / 8;
        return Math.max(score, 1);
    }
}
