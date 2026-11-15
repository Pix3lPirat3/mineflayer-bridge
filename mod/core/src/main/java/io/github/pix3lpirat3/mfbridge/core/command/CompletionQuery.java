package io.github.pix3lpirat3.mfbridge.core.command;

import io.github.pix3lpirat3.mfbridge.core.decl.CommandDecl;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandArg;
import io.github.pix3lpirat3.mfbridge.protocol.core.CompletionSpec;
import java.util.List;
import java.util.Map;

/**
 * What a {@link CompletionSource} is asked to complete: one argument of one command node.
 *
 * @param bot the bot whose command is being typed
 * @param command the command declaration
 * @param path canonical subcommand names below the root. Immutable.
 * @param arg the argument being typed
 * @param spec its completion spec; when the argument declares none, a default for its type
 * @param input what the player typed for the argument so far (for a position, the current
 *     component), unquoted
 * @param component 0, 1 or 2 for the x, y or z part of a position, else 0
 * @param args earlier arguments, already parsed, in the types {@code command.invoked} carries. Immutable.
 * @param context the game context
 */
public record CompletionQuery(
        BotCommands bot,
        CommandDecl command,
        List<String> path,
        CommandArg arg,
        CompletionSpec spec,
        String input,
        int component,
        Map<String, Object> args,
        ArgContext context) {
    /** Copies the path and arguments. */
    public CompletionQuery {
        path = List.copyOf(path);
        args = Map.copyOf(args);
    }
}
