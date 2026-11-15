package io.github.pix3lpirat3.mfbridge.core.command;

import io.github.pix3lpirat3.mfbridge.protocol.core.CommandArg;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandNode;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One node of a command tree: the root {@code core:command} or one of its
 * nested {@link CommandNode}s, with absent lists made empty.
 *
 * @param name the node name
 * @param aliases other names the node answers to. Immutable.
 * @param description what the node does, or null
 * @param args typed positional arguments, in order. Immutable.
 * @param children child nodes, in declaration order. Immutable.
 * @param runnable whether the node has a handler; false for grouping nodes that list their children
 */
public record TreeNode(
        String name,
        List<String> aliases,
        @Nullable String description,
        List<CommandArg> args,
        List<TreeNode> children,
        boolean runnable) {

    /** Copies the lists. */
    public TreeNode {
        aliases = List.copyOf(aliases);
        args = List.copyOf(args);
        children = List.copyOf(children);
    }

    /**
     * Returns the tree of a command declaration.
     *
     * @param data the declaration
     * @return its root node
     */
    public static TreeNode of(CommandData data) {
        return new TreeNode(data.name(), orEmpty(data.aliases()), data.description(), data.args(),
                children(data.children()), data.runnable());
    }

    /**
     * Returns the node of a nested declaration.
     *
     * @param node the declaration
     * @return the node
     */
    public static TreeNode of(CommandNode node) {
        return new TreeNode(node.name(), orEmpty(node.aliases()), node.description(), node.args(),
                children(node.children()), node.runnable());
    }

    private static List<String> orEmpty(@Nullable List<String> list) {
        return list == null ? List.of() : list;
    }

    private static List<TreeNode> children(@Nullable List<CommandNode> nodes) {
        if (nodes == null) {
            return List.of();
        }
        List<TreeNode> out = new ArrayList<>(nodes.size());
        for (CommandNode n : nodes) {
            out.add(of(n));
        }
        return out;
    }

    /**
     * Returns whether a typed word names this node: its name or an alias, ignoring case.
     *
     * @param word the typed word
     * @return whether it matches
     */
    public boolean answersTo(String word) {
        if (name.equalsIgnoreCase(word)) {
            return true;
        }
        for (String a : aliases) {
            if (a.equalsIgnoreCase(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the child a typed word names: an exact name first, then an exact alias, then either
     * ignoring case.
     *
     * @param word the typed word
     * @return the child, or null
     */
    public @Nullable TreeNode child(String word) {
        for (TreeNode c : children) {
            if (c.name.equals(word)) {
                return c;
            }
        }
        for (TreeNode c : children) {
            if (c.aliases.contains(word)) {
                return c;
            }
        }
        for (TreeNode c : children) {
            if (c.answersTo(word)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Follows a path of canonical child names.
     *
     * @param path names below this node, outermost first
     * @return the node at the path, or null when a name is unknown
     */
    public @Nullable TreeNode at(List<String> path) {
        TreeNode node = this;
        for (String step : path) {
            TreeNode next = null;
            for (TreeNode c : node.children) {
                if (c.name.equals(step)) {
                    next = c;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            node = next;
        }
        return node;
    }

    /**
     * Returns whether an argument must be given: it is not optional and has no default.
     *
     * @param arg the declaration
     * @return whether it is required
     */
    public static boolean required(CommandArg arg) {
        return !arg.optional() && arg.defaultValue() == null;
    }

    /** Returns whether any argument of this node is required. */
    public boolean hasRequiredArgs() {
        for (CommandArg a : args) {
            if (required(a)) {
                return true;
            }
        }
        return false;
    }
}
