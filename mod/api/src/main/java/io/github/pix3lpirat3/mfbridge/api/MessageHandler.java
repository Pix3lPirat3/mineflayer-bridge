package io.github.pix3lpirat3.mfbridge.api;

/**
 * Handles one custom message type from Node. Runs on the client thread inside the budgeted intake
 * drain. Throwing {@link BridgeException} sends that error to the peer; any other exception is
 * reported as {@code internal}.
 *
 * @param <T> decoded message type
 */
@FunctionalInterface
public interface MessageHandler<T> {
    /** Handles a decoded message. */
    void handle(MessageContext context, T message);
}
