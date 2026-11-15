package io.github.pix3lpirat3.mfbridge.core.history;

import java.util.List;

/**
 * One entry of the edit history, as shown in a history UI.
 *
 * @param id unique within its world store, increasing with age
 * @param label what the edit did, for example "Move zone"
 * @param timeMs when it was recorded, wall-clock milliseconds since the epoch
 * @param keys the object keys it changed
 * @param applied true while the edit is in effect, false once undone (it can then be redone)
 */
public record HistoryEntry(long id, String label, long timeMs, List<String> keys, boolean applied) {
    /** Copies the keys. */
    public HistoryEntry {
        keys = List.copyOf(keys);
    }
}
