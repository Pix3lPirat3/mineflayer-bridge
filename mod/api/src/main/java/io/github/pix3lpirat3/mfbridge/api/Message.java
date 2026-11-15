package io.github.pix3lpirat3.mfbridge.api;

import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;

/**
 * A message an extension declared: what {@link ExtensionRegistrar#message(Message, MessageHandler)}
 * registers and {@link MessageContext#send(Message, Object)} and {@link SessionRef#send(Message,
 * Object)} send. An extension keeps one instance per message name, usually in a constant.
 *
 * <p>Extensions without generated codecs get theirs from {@link PlainMessages#message}; those with
 * codecs from the bridge's code generator wrap the generated type with {@link #of}.
 *
 * @param <T> the decoded message value
 */
public final class Message<T> {
    /** Which side sends a message. */
    public enum From {
        /** Only the Node half sends it. */
        NODE,
        /** Only the mod half sends it. */
        MOD,
        /** Either half sends it. */
        BOTH
    }

    /** How a message is delivered. */
    public enum Delivery {
        /** In order, and sent again after a reconnect: for anything that must arrive. */
        RELIABLE,
        /** Only the newest one of the same message counts; older ones still waiting are dropped. */
        LATEST,
        /** May be dropped when the connection is busy: for a stream of values that go stale. */
        UNRELIABLE
    }

    private final MessageType<T> type;

    private Message(MessageType<T> type) {
        this.type = type;
    }

    /**
     * Wraps a message type made by the bridge's code generator.
     *
     * @param type the generated type
     * @param <T> the decoded message value
     * @return the message
     */
    public static <T> Message<T> of(MessageType<T> type) {
        return new Message<>(type);
    }

    /** Returns the message name, {@code <namespace>:<name>}. */
    public String name() {
        return type.name();
    }

    /**
     * Returns the wire type behind this message, for the bridge itself and for code that works with
     * generated types. Extensions that use plain values never need it.
     */
    public MessageType<T> type() {
        return type;
    }

    @Override
    public String toString() {
        return "Message[" + type.name() + "]";
    }
}
