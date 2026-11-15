package io.github.pix3lpirat3.mfbridge.core.diag;

import org.jspecify.annotations.Nullable;

/**
 * One connection as the inspector's Connections tab shows it.
 *
 * @param id diagnostic connection id; negative for virtual (replay) connections
 * @param deviceName the device name from {@code hello}, or {@code unknown}
 * @param deviceId the authenticated device id, or null before authentication
 * @param remoteAddress peer address as {@code host:port}, or {@code replay:<file>}
 * @param secure whether the connection runs over TLS
 * @param state handshake state: {@code await_hello}, {@code await_auth}, {@code await_approval},
 *     {@code ready} or {@code closing}
 * @param rttMs last measured heartbeat round trip in milliseconds, or -1 before the first pong
 * @param bytesIn frame bytes received
 * @param bytesOut frame bytes sent
 * @param framesIn frames received
 * @param framesOut frames sent
 * @param messagesPerSecond inbound frames in the last full second
 * @param bytesPerSecond inbound bytes in the last full second
 * @param messageBudget the device's sustained message quota per second
 * @param byteBudget the device's sustained byte quota per second
 * @param queuedInbound frames waiting for intake
 * @param sessions sessions on this connection
 * @param recording whether this connection is being recorded
 */
public record ConnectionInfo(
        long id,
        String deviceName,
        @Nullable String deviceId,
        String remoteAddress,
        boolean secure,
        String state,
        long rttMs,
        long bytesIn,
        long bytesOut,
        long framesIn,
        long framesOut,
        long messagesPerSecond,
        long bytesPerSecond,
        long messageBudget,
        long byteBudget,
        int queuedInbound,
        int sessions,
        boolean recording) {

    /** Returns the larger of the message and byte budget use, as a fraction (1 = at the quota). */
    public double budgetUse() {
        double m = messageBudget <= 0 ? 0 : (double) messagesPerSecond / messageBudget;
        double b = byteBudget <= 0 ? 0 : (double) bytesPerSecond / byteBudget;
        return Math.max(m, b);
    }
}
