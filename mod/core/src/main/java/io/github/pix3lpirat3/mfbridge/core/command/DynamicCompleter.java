package io.github.pix3lpirat3.mfbridge.core.command;

import io.github.pix3lpirat3.mfbridge.protocol.core.Suggestion;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Asks the owning bot for suggestions ({@code source: dynamic}). The core's
 * implementation is {@link CommandControls#completeDynamic}.
 */
@FunctionalInterface
public interface DynamicCompleter {
    /** Never answers anything. */
    DynamicCompleter NONE = (commandKey, path, arg, input, args, bots) -> CompletableFuture.completedFuture(List.of());

    /**
     * Requests suggestions.
     *
     * @param commandKey full object key of the {@code core:command}
     * @param path canonical subcommand names below the root
     * @param arg name of the argument being completed
     * @param input what the player typed for it so far
     * @param args earlier arguments, already parsed
     * @param bots for an order, the crew bots it is addressed to; empty otherwise
     * @return completes with the suggestions; never fails
     */
    CompletableFuture<List<Suggestion>> complete(String commandKey, List<String> path, String arg, String input,
            Map<String, Object> args, List<String> bots);
}
