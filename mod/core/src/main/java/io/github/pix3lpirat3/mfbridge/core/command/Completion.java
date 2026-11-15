package io.github.pix3lpirat3.mfbridge.core.command;

import io.github.pix3lpirat3.mfbridge.protocol.core.Suggestion;
import java.util.List;

/**
 * Suggestions for the token at the cursor.
 *
 * @param start offset in the line of the first char the chosen suggestion replaces
 * @param end offset just past the last replaced char: the cursor
 * @param suggestions what to offer, in order; each {@code text} replaces {@code [start, end)}. Immutable.
 */
public record Completion(int start, int end, List<Suggestion> suggestions) {
    /** Copies the suggestions. */
    public Completion {
        suggestions = List.copyOf(suggestions);
    }

    /**
     * Returns no suggestions at the cursor.
     *
     * @param cursor the cursor offset
     * @return an empty completion
     */
    public static Completion none(int cursor) {
        return new Completion(cursor, cursor, List.of());
    }

    /**
     * Returns this completion with its range moved, for a line embedded in a longer input.
     *
     * @param delta chars to add to both ends
     * @return the moved completion
     */
    public Completion shifted(int delta) {
        return new Completion(start + delta, end + delta, suggestions);
    }
}
