package io.github.pix3lpirat3.mfbridge.api;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;

/**
 * The session a custom message arrived on. Only valid during the handler call, on the client
 * thread; keep {@link #session()} to send later.
 */
@ClientThread
public interface MessageContext {
    /** Returns the per-connection session id the message arrived on. */
    int sessionId();

    /** Returns the session's owned key prefix, for example {@code bot:<identity>/}. */
    String ownerPrefix();

    /** Returns the session's display name. */
    String displayName();

    /**
     * Queues an extension message to the session's Node half, using the type's delivery class. The
     * type must be a registered extension message that the session's connection negotiated.
     *
     * @throws BridgeException with {@code unknown_type} when the connection did not negotiate it
     */
    <T> void send(MessageType<T> type, T message);

    /**
     * Sends a message the extension declared; see {@link #send(MessageType, Object)}.
     *
     * @throws BridgeException {@code unknown_type} when the connection did not negotiate the message
     */
    default <T> void send(Message<T> message, T value) {
        send(message.type(), value);
    }

    /** Returns a handle to this session that stays valid after the handler returns. */
    SessionRef session();
}
