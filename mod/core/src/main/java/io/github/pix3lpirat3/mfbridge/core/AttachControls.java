package io.github.pix3lpirat3.mfbridge.core;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import org.jspecify.annotations.Nullable;

/**
 * The player's attach choices. An explicit choice per bot identity wins over the
 * policy. Choices and the policy are persisted. Controllers are always attached. Client thread only.
 */
@ClientThread
public interface AttachControls {
    /** Returns the current policy. */
    AttachPolicy policy();

    /** Replaces the policy. */
    void setPolicy(AttachPolicy policy);

    /** Returns whether a bot identity is currently attached. */
    boolean isAttached(String identity);

    /** Returns whether a bot identity is in the player's world, as the {@code AUTO} policy sees it. */
    boolean worldMatched(String identity);

    /** Returns the explicit choice for an identity, or null when the policy decides. */
    @Nullable Boolean choice(String identity);

    /**
     * Sets or clears the explicit choice. Detaching never disconnects.
     *
     * @param attached the choice, or null to let the policy decide
     */
    void setChoice(String identity, @Nullable Boolean attached);
}
