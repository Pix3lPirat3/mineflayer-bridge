package io.github.pix3lpirat3.mfbridge.core.input;

import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuRequest;
import org.jspecify.annotations.Nullable;

/**
 * A pending {@code world-menu.request}; resolve it with {@link
 * InputControls#resolveWorldMenu}. It is world-scoped like a pick, and times out with error {@code
 * timeout}.
 *
 * @param id core-assigned id
 * @param ownerName display name of the requester
 * @param ownerIdentity bot identity, or null for a controller
 * @param sessionId wire session id when the request arrived
 * @param request decoded request
 * @param createdAtMs arrival time, bridge clock ms
 * @param deadlineMs arrival plus the request's {@code timeoutMs}, bridge clock ms
 */
public record WorldMenuInput(
        long id,
        String ownerName,
        @Nullable String ownerIdentity,
        int sessionId,
        WorldMenuRequest request,
        long createdAtMs,
        long deadlineMs) implements InputRequest {}
