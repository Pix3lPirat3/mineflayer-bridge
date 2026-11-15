package io.github.pix3lpirat3.mfbridge.core.remote;

import io.github.pix3lpirat3.mfbridge.api.event.EventType;

/**
 * Posted on the client thread after a remote device paired successfully.
 *
 * @param deviceId the issued device id
 * @param deviceName the display name from {@code hello}, untrusted
 * @param remoteAddress peer address as {@code host:port}
 */
public record DevicePaired(String deviceId, String deviceName, String remoteAddress) {
    /** The event type to subscribe to on {@code BridgeCore.events()}. */
    public static final EventType<DevicePaired> TYPE = EventType.create("core:device.paired", DevicePaired.class);
}
