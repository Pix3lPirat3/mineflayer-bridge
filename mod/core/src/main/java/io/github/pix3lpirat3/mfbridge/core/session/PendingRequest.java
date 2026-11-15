package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.core.util.Encoding;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import java.util.concurrent.CompletableFuture;

/**
 * A request the mod sent and is waiting on.
 *
 * @param req request id
 * @param response expected response type
 * @param future completed on the client thread with the response or a {@code BridgeException}
 * @param deadlineMs clock ms after which the request fails with {@code timeout}
 * @param <T> response payload type
 */
record PendingRequest<T>(long req, MessageType<T> response, CompletableFuture<T> future, long deadlineMs) {
    /** Completes the future with a decoded response payload. */
    void complete(byte[] payload) {
        future.complete(Encoding.decode(response.codec(), payload));
    }
}
