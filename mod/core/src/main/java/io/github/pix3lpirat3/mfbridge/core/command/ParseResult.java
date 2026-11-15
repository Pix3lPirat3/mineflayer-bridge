package io.github.pix3lpirat3.mfbridge.core.command;

import io.github.pix3lpirat3.mfbridge.core.decl.CommandDecl;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** What {@link CommandEngine#parse} made of a command line. */
public sealed interface ParseResult {
    /**
     * The line names a runnable node and its arguments fit.
     *
     * @param command the command declaration
     * @param path canonical subcommand names below the root, outermost first. Immutable.
     * @param node the node that runs
     * @param args argument name to value in declaration order, defaults filled in, selectors
     *     resolved; values are {@code Long}, {@code Double}, {@code Boolean}, {@code String}, entity
     *     id lists, or position maps with {@code x}, {@code y}, {@code z}. Immutable.
     * @param bots for an order, the crew bots it is for; empty otherwise. Immutable.
     */
    record Invocation(CommandDecl command, List<String> path, TreeNode node, Map<String, Object> args, List<String> bots)
            implements ParseResult {
        /** Copies the path, arguments and bots. */
        public Invocation {
            path = List.copyOf(path);
            args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
            bots = List.copyOf(bots);
        }
    }

    /**
     * The line stops at a node that only groups children, or at a node with children whose required
     * arguments are missing: show its usage instead of running it.
     *
     * @param command the command declaration
     * @param path canonical subcommand names below the root. Immutable.
     * @param node the node to explain
     */
    record Help(CommandDecl command, List<String> path, TreeNode node) implements ParseResult {
        /** Copies the path. */
        public Help {
            path = List.copyOf(path);
        }
    }

    /**
     * The line does not parse.
     *
     * @param start offset in the line of the first offending char
     * @param end offset just past the last offending char; equal to {@code start} for something
     *     missing at the end
     * @param message the reason, fit to show the player
     * @param command the command the line names, or null when its first word names none
     */
    record Failure(int start, int end, String message, @Nullable CommandDecl command) implements ParseResult {}
}
