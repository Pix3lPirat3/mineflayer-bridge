package io.github.pix3lpirat3.mfbridge.core.container;

import io.github.pix3lpirat3.mfbridge.api.event.EventType;

/**
 * Posted on the client thread when an attached bot asks the client to open one of its container
 * views ({@code container.open}). The request also stays in {@link ContainerControls#requestedOpen()}
 * until the client opens the view or the request lapses.
 *
 * @param viewKey full key of the {@code core:container-view} object
 */
public record ContainerOpenRequested(String viewKey) {
    /** The event type to subscribe to on {@code BridgeCore.events()}. */
    public static final EventType<ContainerOpenRequested> TYPE =
            EventType.create("core:container.open-requested", ContainerOpenRequested.class);
}
