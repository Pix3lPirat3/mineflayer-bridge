package io.github.pix3lpirat3.mfbridge.core.command;

import io.github.pix3lpirat3.mfbridge.protocol.core.CommandArg;
import java.util.ArrayList;
import java.util.List;

/**
 * Usage and help text generated from a command tree. Pure.
 */
public final class CommandUsage {
    private CommandUsage() {}

    /**
     * Returns the argument signature of a node, such as {@code <block:block_id> [count:int=64]}:
     * required arguments in angle brackets, optional ones in square brackets, choices joined by
     * {@code |}.
     *
     * @param node the node
     * @return the signature; empty for a node without arguments
     */
    public static String args(TreeNode node) {
        StringBuilder b = new StringBuilder();
        for (CommandArg a : node.args()) {
            boolean optional = !TreeNode.required(a);
            if (!b.isEmpty()) {
                b.append(' ');
            }
            b.append(optional ? '[' : '<').append(a.name()).append(':');
            List<String> choices = a.choices();
            b.append(choices != null && !choices.isEmpty() ? String.join("|", choices) : a.type().wireName());
            if (a.defaultValue() != null) {
                b.append('=').append(a.defaultValue());
            }
            b.append(optional ? ']' : '>');
        }
        return b.toString();
    }

    /**
     * Returns the usage of one node: its words, then {@code <subcommand>} when it has children and
     * no arguments, then the argument signature.
     *
     * @param words the root name followed by the path, for example {@code [mine, start]}
     * @param node the node at those words
     * @return the usage, for example {@code mine start <block:block_id> [count:int=64]}
     */
    public static String usage(List<String> words, TreeNode node) {
        StringBuilder b = new StringBuilder(String.join(" ", words));
        String args = args(node);
        if (!node.children().isEmpty() && (args.isEmpty() || !node.runnable())) {
            b.append(" <").append(String.join("|", childNames(node))).append('>');
        }
        if (!args.isEmpty() && node.runnable()) {
            b.append(' ').append(args);
        }
        return b.toString();
    }

    private static List<String> childNames(TreeNode node) {
        List<String> out = new ArrayList<>(node.children().size());
        for (TreeNode c : node.children()) {
            out.add(c.name());
        }
        return out;
    }

    /**
     * Returns the help lines of a node: its usage and description, then one line per child.
     *
     * @param words the root name followed by the path
     * @param node the node at those words
     * @return the lines; children are indented by two spaces. Immutable.
     */
    public static List<String> help(List<String> words, TreeNode node) {
        List<String> out = new ArrayList<>();
        out.add(line(usage(words, node), node));
        for (TreeNode c : node.children()) {
            List<String> childWords = new ArrayList<>(words);
            childWords.add(c.name());
            out.add("  " + line(usage(childWords, c), c));
        }
        return List.copyOf(out);
    }

    /**
     * Returns one line per node of a whole tree, depth first: runnable nodes and grouping nodes
     * without runnable descendants appear; aliases follow in parentheses.
     *
     * @param root the root node
     * @return the lines, children indented by two spaces per level. Immutable.
     */
    public static List<String> tree(TreeNode root) {
        List<String> out = new ArrayList<>();
        walk(List.of(root.name()), root, 0, out);
        return List.copyOf(out);
    }

    private static void walk(List<String> words, TreeNode node, int depth, List<String> out) {
        if (node.runnable() || node.children().isEmpty() || depth == 0) {
            String aliases = node.aliases().isEmpty() ? "" : " (" + String.join(", ", node.aliases()) + ")";
            out.add("  ".repeat(depth) + line(usage(words, node) + aliases, node));
        }
        for (TreeNode c : node.children()) {
            List<String> next = new ArrayList<>(words);
            next.add(c.name());
            walk(next, c, depth + 1, out);
        }
    }

    private static String line(String usage, TreeNode node) {
        String d = node.description();
        return d == null || d.isEmpty() ? usage : usage + " - " + d;
    }
}
