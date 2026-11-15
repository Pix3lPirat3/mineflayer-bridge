package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import io.github.pix3lpirat3.mfbridge.core.command.CommandUsage;
import io.github.pix3lpirat3.mfbridge.core.command.TreeNode;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The text of the {@code /bridge} client command at every level: the status
 * shown by {@code /bridge}, usage for {@code /bridge help}, and the command list of {@code /bridge
 * <bot|all>}, plus bot name lookup. Game-free.
 */
public final class BridgeStatus {

    private BridgeStatus() {
    }

    /**
     * One connected bot for the status.
     *
     * @param name display name
     * @param attached whether the player has it attached
     * @param worldMatched whether it is in the player's world
     * @param commands how many commands it declares
     */
    public record Bot(String name, boolean attached, boolean worldMatched, int commands) {
    }

    /**
     * Returns the status lines of {@code /bridge}.
     *
     * @param t resolves the text
     * @param running whether the endpoint is running
     * @param port the bound port, when running
     * @param bots connected bots, in display order
     * @return the lines. Immutable.
     */
    public static List<String> status(final Text t, final boolean running, final int port, final List<Bot> bots) {
        final List<String> out = new ArrayList<>();
        out.add(running ? t.get("mineflayer_bridge.status.listening", port) : t.get("mineflayer_bridge.endpoint.not_running.message"));
        if (!running) {
            return List.copyOf(out);
        }
        if (bots.isEmpty()) {
            out.add(t.get("mineflayer_bridge.status.no_bots"));
            return List.copyOf(out);
        }
        for (final Bot b : bots) {
            out.add(t.get("mineflayer_bridge.status.bot", b.name(),
                    b.attached() ? t.get("mineflayer_bridge.status.attached") : t.get("mineflayer_bridge.status.not_attached"),
                    b.worldMatched() ? t.get("mineflayer_bridge.status.same_world") : t.get("mineflayer_bridge.status.other_world"),
                    b.commands() == 1 ? t.get("mineflayer_bridge.status.commands.one", b.commands())
                            : t.get("mineflayer_bridge.status.commands.other", b.commands())));
        }
        out.add(t.get("mineflayer_bridge.status.hint"));
        return List.copyOf(out);
    }

    /**
     * Returns the usage lines of {@code /bridge help}.
     *
     * @param t resolves the text
     * @return the lines. Immutable.
     */
    public static List<String> usage(final Text t) {
        return List.of(
                t.get("mineflayer_bridge.status.help.status"),
                t.get("mineflayer_bridge.status.help.help"),
                t.get("mineflayer_bridge.status.help.menu"),
                t.get("mineflayer_bridge.status.help.tool"),
                t.get("mineflayer_bridge.status.help.bot"),
                t.get("mineflayer_bridge.status.help.all"),
                t.get("mineflayer_bridge.status.help.run"),
                t.get("mineflayer_bridge.status.help.tree"),
                t.get("mineflayer_bridge.status.help.run_all"));
    }

    /**
     * Returns a command with its argument signature, such as {@code mine <block> [count=64]
     * [mode:fast|safe]}: required arguments in angle brackets, optional ones in square brackets.
     *
     * @param command the declaration
     * @return the signature
     */
    public static String signature(final CommandData command) {
        return CommandUsage.usage(List.of(command.name()), TreeNode.of(command));
    }

    /**
     * One heading of {@code /bridge <name>} and the command trees under it.
     *
     * @param heading the heading line
     * @param commands the commands, in order
     */
    public record Section(String heading, List<CommandData> commands) {
        /** Copies the commands. */
        public Section {
            commands = List.copyOf(commands);
        }
    }

    /**
     * Returns the lines of {@code /bridge <name>}: each section's heading and command trees.
     *
     * @param t resolves the text
     * @param name the address as declared
     * @param sections what it reaches; empty sections are left out
     * @return the lines. Immutable.
     */
    public static List<String> commandList(final Text t, final String name, final List<Section> sections) {
        final List<String> out = new ArrayList<>();
        for (final Section s : sections) {
            if (s.commands().isEmpty()) {
                continue;
            }
            out.add(s.heading());
            for (final CommandData c : s.commands()) {
                for (final String line : CommandUsage.tree(TreeNode.of(c))) {
                    out.add("  " + line);
                }
            }
        }
        return out.isEmpty() ? List.of(t.get("mineflayer_bridge.status.no_commands.bot", name)) : List.copyOf(out);
    }

    /**
     * Finds a bot by name, ignoring case.
     *
     * @param typed the typed name
     * @param names known bot names
     * @return the name as known, or null
     */
    public static @Nullable String findBot(final String typed, final Collection<String> names) {
        final String lower = typed.toLowerCase(Locale.ROOT);
        for (final String n : names) {
            if (n.toLowerCase(Locale.ROOT).equals(lower)) {
                return n;
            }
        }
        return null;
    }
}
