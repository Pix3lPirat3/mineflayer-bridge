package io.github.pix3lpirat3.mfbridge.api;

import io.github.pix3lpirat3.mfbridge.protocol.wire.Delivery;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Direction;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireCodecs;
import org.jspecify.annotations.Nullable;

/**
 * Messages carrying plain values, for extensions that do not use the bridge's code generator. The
 * Node half declares the same name with {@code plainMessage} from the plugin. {@link #message}
 * is the way in for an extension; {@link #type} gives the wire type for code that works with those.
 *
 * <p>Values are {@code null}, {@link Boolean}, {@link Long} or {@link Double}, {@link String},
 * {@code List<Object>} and {@code Map<String, Object>} of these. Integral numbers within the
 * IEEE-754 safe range are encoded as integers and decode as {@link Long}; other numbers are
 * float64 and decode as {@link Double}. Integer, Short, Byte and Float are accepted when sending.
 * Maps need string keys and decode in wire order. Byte arrays and integers above
 * {@link Long#MAX_VALUE} also pass through, as {@code byte[]} and {@link java.math.BigInteger}.
 */
public final class PlainMessages {
    private PlainMessages() {}

    /**
     * Builds a plain-value message type, to register with {@link ExtensionRegistrar#message}.
     *
     * @param name the message name, starting with the extension's namespace and {@code :}
     * @param from which side sends it
     * @param delivery the delivery class; {@link Delivery#CONTROL} is not allowed
     * @throws IllegalArgumentException when the delivery is {@link Delivery#CONTROL}
     */
    public static MessageType<@Nullable Object> type(String name, Direction from, Delivery delivery) {
        if (delivery == Delivery.CONTROL) {
            throw new IllegalArgumentException("extension messages cannot use control delivery");
        }
        return new MessageType<>(0, name, from, delivery, WireCodecs.ANY, null, 0);
    }

    /**
     * Declares a plain-value message, to register with {@link ExtensionRegistrar#message(Message,
     * MessageHandler)} and send with {@link MessageContext#send(Message, Object)} or {@link
     * SessionRef#send(Message, Object)}. This is the whole of what an extension needs to exchange
     * messages with its Node half.
     *
     * @param name the message name, starting with the extension's namespace and {@code :}
     * @param from which side sends it
     * @param delivery how it is delivered
     * @return the message
     */
    public static Message<@Nullable Object> message(String name, Message.From from, Message.Delivery delivery) {
        Direction direction = switch (from) {
            case NODE -> Direction.NODE;
            case MOD -> Direction.MOD;
            case BOTH -> Direction.BOTH;
        };
        Delivery wire = switch (delivery) {
            case RELIABLE -> Delivery.RELIABLE;
            case LATEST -> Delivery.LATEST;
            case UNRELIABLE -> Delivery.UNRELIABLE;
        };
        return Message.of(type(name, direction, wire));
    }
}
