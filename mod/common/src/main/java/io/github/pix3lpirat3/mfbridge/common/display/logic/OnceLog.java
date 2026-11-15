package io.github.pix3lpirat3.mfbridge.common.display.logic;

import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * Remembers which problems were already logged, so a bot that keeps sending the same broken value
 * (for example invalid SNBT in a display's {@code data}) produces one warning instead of one per
 * revision. Bounded: the oldest entries are forgotten first. Game-free; not thread-safe.
 */
public final class OnceLog {

    private final int capacity;
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();

    /**
     * Creates a log that remembers up to {@code capacity} problems.
     *
     * @param capacity the number of distinct problems kept, at least 1
     */
    public OnceLog(final int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /**
     * Records a problem.
     *
     * @param problem a key identifying the problem, such as the rejected value
     * @return true the first time a problem is seen (while it is remembered), false afterwards
     */
    public boolean first(final String problem) {
        if (!seen.add(problem)) {
            return false;
        }
        if (seen.size() > capacity) {
            final Iterator<String> it = seen.iterator();
            it.next();
            it.remove();
        }
        return true;
    }
}
