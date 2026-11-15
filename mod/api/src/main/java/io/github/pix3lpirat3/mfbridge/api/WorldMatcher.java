package io.github.pix3lpirat3.mfbridge.api;

import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;

/**
 * Decides whether a bot's world is the player's world. Matchers form a chain
 * ordered by priority, highest first; the first definite answer wins and the core default matcher
 * runs last. Called on the client thread; must be pure and cheap.
 */
@FunctionalInterface
public interface WorldMatcher {
    /** Outcome of one matcher. */
    enum Result {
        /** Definitely the same world. */
        MATCH,
        /** Definitely a different world. */
        MISMATCH,
        /** No opinion; the next matcher decides. */
        ABSTAIN
    }

    /**
     * Compares two world keys exactly as reported (not normalized).
     *
     * @param player the player's world key
     * @param other the bot's or object's world key
     */
    Result match(WorldKey player, WorldKey other);
}
