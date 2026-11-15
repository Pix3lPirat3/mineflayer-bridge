package io.github.pix3lpirat3.mfbridge.api;

import io.github.pix3lpirat3.mfbridge.api.event.EventBus;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;

/**
 * Registration surface handed to {@link BridgeExtension#register}. Only valid during that call, on
 * the client thread. Kind and message names must start with the extension's namespace followed by
 * {@code :}.
 */
@ClientThread
public interface ExtensionRegistrar {
    /**
     * Registers an object kind. Node halves name it in {@code hello} and get a per-connection id in
     * {@code welcome}.
     *
     * @throws IllegalArgumentException when the name is taken or outside the extension's namespace
     */
    <T> void kind(ObjectKind<T> kind);

    /**
     * Registers a custom message for this extension's Node half that any session may send. The
     * {@link MessageType#id()} is ignored; ids are assigned per connection by name. Inbound messages
     * are decoded with the type's codec and passed to {@code handler}.
     *
     * @throws IllegalArgumentException when the name is taken or outside the extension's namespace
     */
    default <T> void message(MessageType<T> type, MessageHandler<T> handler) {
        message(type, MessageAccess.OPEN, handler);
    }

    /**
     * Registers a custom message, saying who may send it. A message from a session that lacks the
     * permission is answered with {@code permission_denied}, one from a session the player has not
     * attached with {@code not_attached}; neither reaches {@code handler}.
     *
     * @param access what a session needs to send the message
     * @throws IllegalArgumentException when the name is taken or outside the extension's namespace,
     *     or the permission is unknown
     */
    <T> void message(MessageType<T> type, MessageAccess access, MessageHandler<T> handler);

    /**
     * Registers a message that any session may send; see {@link #message(Message, MessageAccess,
     * MessageHandler)}.
     *
     * @throws IllegalArgumentException when the name is taken or outside the extension's namespace
     */
    default <T> void message(Message<T> message, MessageHandler<T> handler) {
        message(message.type(), MessageAccess.OPEN, handler);
    }

    /**
     * Registers a message for this extension's Node half, saying who may send it. Inbound messages
     * are decoded and passed to {@code handler}; a message only the mod sends still has to be
     * registered, so that connections agree on it.
     *
     * @param access what a session needs to send the message
     * @throws IllegalArgumentException when the name is taken or outside the extension's namespace,
     *     or the permission is unknown
     */
    default <T> void message(Message<T> message, MessageAccess access, MessageHandler<T> handler) {
        message(message.type(), access, handler);
    }

    /**
     * Adds a world matcher. Higher priorities run first; the core default matcher runs after all
     * registered ones.
     */
    void matcher(WorldMatcher matcher, int priority);

    /**
     * Registers a typed setting under this extension.
     *
     * @return the live value handle
     * @throws IllegalArgumentException when the key is already registered for this extension
     */
    <T> SettingValue<T> setting(Setting<T> setting);

    /** Returns the bridge event bus, for subscriptions. */
    EventBus events();
}
