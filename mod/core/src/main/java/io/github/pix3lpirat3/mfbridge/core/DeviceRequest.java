package io.github.pix3lpirat3.mfbridge.core;

/**
 * A device asking to connect for the first time.
 *
 * @param deviceId stable device id the plugin generated
 * @param deviceName display name from {@code hello}, chosen by the plugin and therefore untrusted
 * @param remoteAddress peer address as {@code host:port}
 * @param method authentication method used, for example {@code local}
 */
public record DeviceRequest(String deviceId, String deviceName, String remoteAddress, String method) {}
