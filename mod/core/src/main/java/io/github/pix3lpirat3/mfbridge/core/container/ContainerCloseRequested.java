package io.github.pix3lpirat3.mfbridge.core.container;

import io.github.pix3lpirat3.mfbridge.api.event.EventType;

/**
 * Posted on the client thread when an open or requested container view must close: its bot sent
 * {@code container.close}, removed the view, was detached, left the player's world or disconnected.
 * The core has already forgotten the view, so the client closes its screen without calling {@link
 * ContainerControls#closed}.
 *
 * @param viewKey full key of the {@code core:container-view} object
 */
public record ContainerCloseRequested(String viewKey) {
    /** The event type to subscribe to on {@code BridgeCore.events()}. */
    public static final EventType<ContainerCloseRequested> TYPE =
            EventType.create("core:container.close-requested", ContainerCloseRequested.class);
}
