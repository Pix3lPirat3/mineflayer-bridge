package io.github.pix3lpirat3.mfbridge.protocol.wire;

import org.jspecify.annotations.Nullable;

/**
 * Registered message type.
 *
 * @param response name of the response message when this is a request
 * @param timeoutMs default request timeout, 0 when not a request
 */
public record MessageType<T>(
        int id,
        String name,
        Direction from,
        Delivery delivery,
        WireCodec<T> codec,
        @Nullable String response,
        int timeoutMs) {

    /** Returns whether the message is a request that expects a response. */
    public boolean isRequest() {
        return response != null;
    }
}
