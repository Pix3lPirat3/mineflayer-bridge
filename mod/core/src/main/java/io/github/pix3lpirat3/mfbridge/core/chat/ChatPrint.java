package io.github.pix3lpirat3.mfbridge.core.chat;

import io.github.pix3lpirat3.mfbridge.api.event.EventType;
import io.github.pix3lpirat3.mfbridge.protocol.core.ChatSpan;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Posted on the client thread when an attached bot prints a client-only chat line ({@code
 * chat.print}). The client shows it prefixed with the bot's name tag and never sends it to the server.
 *
 * <p>Click values: {@code run_action} carries the full object key of a {@code core:action}; {@code
 * run_command} carries a command line for the printing bot, {@code <command name> [positional
 * args...]}, to run with {@link ChatControls#runCommandLine}; {@code suggest} and {@code copy} carry
 * text for the chat box and the clipboard; {@code open_url} carries an http or https link, opened
 * after the game's own confirmation.
 *
 * @param ownerName display name of the printing bot
 * @param ownerIdentity its bot identity, or null for a controller
 * @param owner its owner key without trailing slash, for example {@code bot:<identity>}
 * @param spans the line's text spans, in order. Immutable.
 */
public record ChatPrint(String ownerName, @Nullable String ownerIdentity, String owner, List<ChatSpan> spans) {
    /** The event type to subscribe to on {@code BridgeCore.events()}. */
    public static final EventType<ChatPrint> TYPE = EventType.create("core:chat.print", ChatPrint.class);

    /** Copies the spans. */
    public ChatPrint {
        spans = List.copyOf(spans);
    }
}
