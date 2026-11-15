package io.github.pix3lpirat3.mfbridge.core.retained;

/** Whether a block cell belongs to a set, by world block coordinates. Must be pure and thread-safe. */
@FunctionalInterface
public interface CellPredicate {
    /** Returns whether the cell at a world block position is in the set. */
    boolean test(int x, int y, int z);
}
