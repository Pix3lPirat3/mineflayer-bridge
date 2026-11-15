package io.github.pix3lpirat3.mfbridge.api;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;

/**
 * A session a mod extension can keep and send to later, until it closes. A session that resumes
 * on a new connection stays the same ref.
 */
public interface SessionRef {
    /** Returns the per-connection session id; it changes when the session resumes on a new connection. */
    @ClientThread
    int sessionId();

    /** Returns the session's owned key prefix, for example {@code bot:<identity>/}. */
    @AnyThread
    String ownerPrefix();

    /** Returns the session's display name. */
    @AnyThread
    String displayName();

    /** Returns whether the session is still open; false once it closed or was superseded. */
    @ClientThread
    boolean isOpen();

    /**
     * Queues an extension message to the session's Node half, using the type's delivery class.
     * Reliable messages sent while the session waits to resume are replayed when it does.
     *
     * @return false, sending nothing, when the session is no longer open or its connection did not
     *     negotiate the type
     * @throws IllegalArgumentException when the type may not be sent by the mod
     */
    @ClientThread
    <T> boolean send(MessageType<T> type, T message);

    /**
     * Queues a message the extension declared; see {@link #send(MessageType, Object)}.
     *
     * @return false, sending nothing, when the session is no longer open or its connection did not
     *     negotiate the message
     * @throws IllegalArgumentException when the message may not be sent by the mod
     */
    @ClientThread
    default <T> boolean send(Message<T> message, T value) {
        return send(message.type(), value);
    }
}
