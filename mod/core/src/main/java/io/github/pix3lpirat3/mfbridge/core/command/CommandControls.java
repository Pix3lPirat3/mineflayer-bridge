package io.github.pix3lpirat3.mfbridge.core.command;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.Suggestion;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * The core's command engine service: the bots
 * that can be addressed, the game context the client installs, the client completion sources and
 * dynamic completion through {@code command.complete}. {@code /bridge}, the palette argument form
 * and the chat bridge all go through {@link #engine()}. Client thread only.
 */
@ClientThread
public interface CommandControls {
    /** How long {@link #completeDynamic} waits after the last call for an argument before asking the bot, in ms. */
    long DEBOUNCE_MS = 100;

    /**
     * Installs the game context used by chat commands and by callers that pass {@link #context()}.
     *
     * @param context reads the player, crosshair, registries and entities when called
     */
    void setContext(ArgContext context);

    /** Returns the installed game context; {@link ArgContext#NONE} until one is set. */
    ArgContext context();

    /**
     * Registers a client-resolved completion source, replacing any with the same name.
     *
     * @param source the source name, as in {@code CompletionSpec.source}, for example {@code entities}
     * @param completer the implementation
     */
    void registerSource(String source, CompletionSource completer);

    /** Returns the engine, wired to the registered sources and to {@link #completeDynamic}. */
    CommandEngine engine();

    /**
     * Returns every session's own commands: attached bots (with or without commands) and
     * controllers that declare commands, sorted by name. Orders are left out: they are reached
     * through {@link #addressees()}.
     *
     * @return the owners with their visible commands. Immutable.
     */
    List<BotCommands> bots();

    /**
     * Returns every name the player may type after {@code /bridge} or the chat prefix: {@code all}
     * while a bot is attached, then attached bots, live controllers and crew groups, each with the
     * command sets it reaches. A bot reaches its own commands and the orders of every crew it is in;
     * a group reaches its crew's orders. A group named like a bot or controller is left out.
     *
     * @return the address book. Immutable.
     */
    List<Addressee> addressees();

    /**
     * Finds an addressable owner by display name, ignoring case.
     *
     * @param name the typed name
     * @return the owner, or null
     */
    @Nullable BotCommands bot(String name);

    /**
     * Runs a parsed invocation: like {@code BridgeCore.invokeCommand(key, path, args)}, for the
     * invocation's bots when it is an order.
     *
     * @param invocation the parse result
     * @return completes with the bot's answer, or fails like {@code BridgeCore.invokeCommand}
     */
    CompletableFuture<InvocationResult> invoke(ParseResult.Invocation invocation);

    /**
     * Runs a parsed invocation and tells the bot how the player ran it.
     *
     * @param invocation the parse result
     * @param source {@code chat}, {@code command}, {@code palette}, {@code menu}, {@code screen} or
     *     {@code map}; the bot answers later messages the same way
     * @return completes like {@link #invoke(ParseResult.Invocation)}
     */
    CompletableFuture<InvocationResult> invoke(ParseResult.Invocation invocation, String source);

    /**
     * Asks the owning bot for suggestions with {@code command.complete}
     * ({@code source: dynamic}).
     *
     * <p>The core does the debouncing: the request goes out on the first tick at least {@link
     * #DEBOUNCE_MS} after the call, and a newer call for the same command, path and argument within
     * that window replaces it (the older future then completes with the cached fallback). Answers
     * are cached per input prefix and earlier arguments until the command's declaration revision
     * changes; a cache hit completes right away. The request gives up after its 300 ms timeout and
     * completes with the suggestions cached for the longest shorter prefix (filtered by the input),
     * or an empty list; answers that arrive later are dropped. The future therefore completes at
     * most about 400 ms after the call, always on the client thread, and never fails.
     *
     * @param commandKey full object key of the {@code core:command}
     * @param path canonical subcommand names below the root
     * @param arg the argument name
     * @param input what the player typed for it so far
     * @param args earlier arguments, already parsed
     * @return the suggestions
     */
    CompletableFuture<List<Suggestion>> completeDynamic(String commandKey, List<String> path, String arg, String input,
            Map<String, Object> args);
}
