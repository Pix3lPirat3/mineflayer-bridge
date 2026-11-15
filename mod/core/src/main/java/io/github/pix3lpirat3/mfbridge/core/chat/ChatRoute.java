package io.github.pix3lpirat3.mfbridge.core.chat;

import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * What {@link ChatControls#route} did with a chat line. Every variant except {@link PassThrough} means
 * the line was captured: the client must cancel it before any chat packet is built and keep it in the
 * local chat history only.
 */
public sealed interface ChatRoute {
    /** Returns whether the line was captured and must not reach the server. */
    default boolean captured() {
        return !(this instanceof PassThrough);
    }

    /**
     * The line is not for the bridge.
     *
     * @param lineForServer the line to send instead: the original line, or with one prefix removed
     *     when the player escaped it with a doubled prefix
     */
    record PassThrough(String lineForServer) implements ChatRoute {}

    /**
     * The line ran a bot command, through the same validation as {@code BridgeCore.invokeCommand}.
     *
     * @param command the command name as declared
     * @param runs one run per bot that has the command: one for a bot addressed by name, several for
     *     {@code all}. Immutable.
     */
    record CapturedCommand(String command, List<Run> runs) implements ChatRoute {
        /** Copies the runs. */
        public CapturedCommand {
            runs = List.copyOf(runs);
        }
    }

    /**
     * One command invocation.
     *
     * @param ownerName display name of the bot that runs it
     * @param result completes on the client thread with the bot's answer, or fails like {@code
     *     BridgeCore.invokeCommand}
     */
    record Run(String ownerName, CompletableFuture<InvocationResult> result) {}

    /**
     * The line named a command node that only groups subcommands, or one with subcommands whose
     * required arguments are missing; nothing was sent.
     *
     * @param ownerName display name of the bot whose command it is
     * @param lines usage lines generated from the tree. Immutable.
     */
    record CapturedHelp(String ownerName, List<String> lines) implements ChatRoute {
        /** Copies the lines. */
        public CapturedHelp {
            lines = List.copyOf(lines);
        }
    }

    /**
     * The line was delivered as free text with {@code chat.message}.
     *
     * @param recipients display names of the bots that received it. Immutable.
     * @param commandError when the first word names one of the bot's commands but the arguments did
     *     not parse, the reason, so the player can tell a typo from a conversation; else null
     */
    record CapturedMessage(List<String> recipients, @Nullable String commandError) implements ChatRoute {
        /** Copies the recipients. */
        public CapturedMessage {
            recipients = List.copyOf(recipients);
        }
    }

    /**
     * The line answered the active prompt with surface {@code chat}.
     *
     * @param promptId the input queue id of the prompt
     * @param error null when the prompt was answered; otherwise why the line did not fit, and the
     *     prompt stays open
     */
    record CapturedPrompt(long promptId, @Nullable String error) implements ChatRoute {}

    /**
     * The line addressed attached bots but nothing was delivered, for example because no text
     * followed the name or the bot's device may not receive chat.
     *
     * @param message the reason, fit to show the player
     */
    record Unknown(String message) implements ChatRoute {}

    /**
     * The name matched nothing but is close to one that would: most likely a typo, so the line is kept
     * from the server. The player can fix it, or send it as chat with the doubled prefix.
     *
     * @param name the name as typed
     * @param closest the attached bot or controller name it is closest to
     * @param corrected the line with {@code closest} in place of {@code name}
     * @param asChat the line with the prefix doubled, which goes to the server unchanged
     */
    record Mistyped(String name, String closest, String corrected, String asChat) implements ChatRoute {}
}
