package io.github.pix3lpirat3.mfbridge.core.chat;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.command.Completion;
import io.github.pix3lpirat3.mfbridge.core.command.ParseResult;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * The chat bridge. The client offers every chat line the player sends to
 * {@link #route} before a chat packet is built, and cancels the line unless the result is {@link
 * ChatRoute.PassThrough}.
 *
 * <p>Capture rules, in order:
 *
 * <ol>
 *   <li>While the active prompt has surface {@code chat} and kind {@code text} or {@code confirm},
 *       every line answers it ({@link ChatRoute.CapturedPrompt}); a confirm takes {@code yes}, {@code
 *       no}, {@code y} or {@code n}.
 *   <li>A line that does not start with the prefix passes through unchanged.
 *   <li>A doubled prefix escapes it: {@code @@hi} passes through as {@code @hi}.
 *   <li>The word after the prefix is compared, ignoring case, with the display names of attached bot
 *       sessions and with {@code all} (only while at least one bot is attached). No match passes the
 *       line through unchanged.
 *   <li>For a match, the rest of the line is parsed by {@link
 *       io.github.pix3lpirat3.mfbridge.core.command.CommandEngine}, the engine behind {@code /bridge},
 *       with the context from {@code BridgeCore.commands()}. It runs as one of the bot's commands
 *       when its first word names one (or an alias) and the subcommands and arguments parse ({@link
 *       ChatRoute.CapturedCommand}); a node that only groups subcommands shows its usage ({@link
 *       ChatRoute.CapturedHelp}); otherwise the text is sent as {@code chat.message} ({@link
 *       ChatRoute.CapturedMessage}). For {@code all}, every attached bot whose command parses runs
 *       it; when none does, every bot gets the text with {@code broadcast} set.
 * </ol>
 *
 * <p>The {@code chat} permission is on for local devices and off for remote ones, so bots on remote devices
 * neither receive chat nor print. Client thread only.
 */
@ClientThread
public interface ChatControls {
    /** The default prefix that addresses bots. */
    String DEFAULT_PREFIX = "@";

    /** Returns the prefix that addresses bots. */
    String prefix();

    /**
     * Sets the prefix that addresses bots.
     *
     * @param prefix one or more characters without whitespace
     * @throws IllegalArgumentException when the prefix is empty or contains whitespace
     */
    void setPrefix(String prefix);

    /**
     * Completes a chat line being typed, through the same engine as {@code
     * /bridge}: bot names and {@code all} after the prefix, then the addressed bot's commands,
     * subcommands and arguments.
     *
     * @param line the chat box text
     * @param cursor the cursor offset in {@code line}
     * @return the completion in {@code line} coordinates, or null when the line is not addressed to
     *     bots (no prefix, an escaped prefix, a name that matches no attached bot, or an active chat
     *     prompt), so the client keeps its normal chat suggestions
     */
    @Nullable CompletableFuture<Completion> complete(String line, int cursor);

    /**
     * Parses a chat line addressed to one bot without running it, for live validation in the chat
     * box.
     *
     * @param line the chat box text
     * @return the parse result in {@code line} coordinates for the first addressed bot that has the
     *     command, or null when the line is not addressed to a bot or its first word after the name
     *     names no command (it would be sent as free text)
     */
    @Nullable ParseResult validate(String line);

    /**
     * Routes a line the player is about to send. Captured lines have already been delivered or run
     * when this returns.
     *
     * @param line the chat line as typed, without a leading slash command
     * @return what happened; never null
     */
    ChatRoute route(String line);

    /**
     * Runs a command line for one bot, as a {@code run_command} chat click does: {@code <command
     * name> [subcommands...] [args...]}, parsed like the text after {@code @bot} in {@link #route}.
     *
     * @param owner the bot's owner key without trailing slash, for example {@code bot:<identity>}
     * @param line the command line
     * @return completes like {@code BridgeCore.invokeCommand}; fails with {@code invalid_message} when
     *     the owner has no such command, the line stops at a node that only groups subcommands, or
     *     the arguments do not parse, and with {@code not_attached} when the owner is not an attached
     *     bot
     */
    CompletableFuture<InvocationResult> runCommandLine(String owner, String line);
}
