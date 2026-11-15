package io.github.pix3lpirat3.mfbridge.core.input;

import io.github.pix3lpirat3.mfbridge.protocol.core.PromptRequest;
import org.jspecify.annotations.Nullable;

/**
 * A pending {@code prompt.request}; resolve it with {@link InputControls#resolvePrompt}. When it
 * times out the bot receives the request's {@code defaultValue} with {@code timedOut} set.
 *
 * @param id core-assigned id
 * @param ownerName display name of the requester
 * @param ownerIdentity bot identity, or null for a controller
 * @param sessionId wire session id when the request arrived
 * @param request decoded request
 * @param createdAtMs arrival time, bridge clock ms
 * @param deadlineMs arrival plus the request's {@code timeoutMs}, bridge clock ms
 */
public record PromptInput(
        long id,
        String ownerName,
        @Nullable String ownerIdentity,
        int sessionId,
        PromptRequest request,
        long createdAtMs,
        long deadlineMs) implements InputRequest {}
