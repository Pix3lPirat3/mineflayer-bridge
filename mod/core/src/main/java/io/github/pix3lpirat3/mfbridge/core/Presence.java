package io.github.pix3lpirat3.mfbridge.core;

import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import org.jspecify.annotations.Nullable;

/**
 * Presence record of one bot identity. Outlives sessions.
 *
 * @param identity bot identity: the UUID, or {@code lowercase(username)@server}
 * @param displayName last session display name
 * @param connected whether a session for this identity is currently connected
 * @param lastWorld last reported world key, or null
 * @param lastSeenMillis wall-clock time of the last inbound message, in ms since the epoch
 * @param status last {@code presence.status} value as decoded MessagePack, or null
 */
public record Presence(
        String identity,
        String displayName,
        boolean connected,
        @Nullable WorldKey lastWorld,
        long lastSeenMillis,
        @Nullable Object status) {}
