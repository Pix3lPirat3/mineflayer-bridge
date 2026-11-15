package io.github.pix3lpirat3.mfbridge.core.input;

import io.github.pix3lpirat3.mfbridge.api.event.EventType;
import org.jspecify.annotations.Nullable;

/**
 * Posted on the client thread when the active input request or the number of queued requests
 * changed.
 *
 * @param active the request now active, or null when none is
 * @param queued requests waiting behind the active one
 */
public record InputChanged(@Nullable InputRequest active, int queued) {
    /** The event type to subscribe to on {@code BridgeCore.events()}. */
    public static final EventType<InputChanged> TYPE = EventType.create("core:input.changed", InputChanged.class);
}
