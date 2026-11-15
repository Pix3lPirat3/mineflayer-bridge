package io.github.pix3lpirat3.mfbridge.core.input;

import org.jspecify.annotations.Nullable;

/**
 * A pick, prompt, form or world menu a bot is waiting on. Immutable. Times are in milliseconds of the
 * bridge clock's monotonic time ({@code BridgeClock.nanoTime() / 1_000_000}), not wall-clock time.
 */
public sealed interface InputRequest permits PickInput, PromptInput, FormInput, WorldMenuInput {
    /** Returns the core-assigned id, unique for the life of the bridge; pass it to {@link InputControls}. */
    long id();

    /** Returns the display name of the requesting session. */
    String ownerName();

    /** Returns the bot identity of the requester, or null for a controller. */
    @Nullable String ownerIdentity();

    /** Returns the requester's wire session id when the request arrived. */
    int sessionId();

    /** Returns the arrival time in bridge clock milliseconds. */
    long createdAtMs();

    /** Returns the time at which the request times out, in bridge clock milliseconds. */
    long deadlineMs();
}
