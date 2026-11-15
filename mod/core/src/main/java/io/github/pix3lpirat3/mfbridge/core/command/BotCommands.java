package io.github.pix3lpirat3.mfbridge.core.command;

import io.github.pix3lpirat3.mfbridge.core.decl.CommandDecl;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A set of commands an address reaches: one session's own commands, or the orders of a crew for
 * some of its bots. Also carries what selectors and {@code near: bot} completion need to know about
 * the bot.
 *
 * @param name the name it is shown under: the bot's or controller's display name, which for a bot
 *     is also its username in the client level; for a crew's orders to one bot, that bot's name
 * @param owner owner key of the session the commands belong to, without trailing slash, for
 *     example {@code bot:<identity>}
 * @param position the bot's last {@code world.report} position, or null when unknown or not one bot
 * @param commands the command declarations, sorted by name. Immutable.
 * @param bots for orders, the crew bots they are run for; empty for a session's own commands. Immutable.
 */
public record BotCommands(String name, String owner, @Nullable Vec3d position, List<CommandDecl> commands, List<String> bots) {
    /** Copies the lists. */
    public BotCommands {
        commands = List.copyOf(commands);
        bots = List.copyOf(bots);
    }

    /**
     * A session's own commands.
     *
     * @param name display name
     * @param owner owner key without trailing slash
     * @param position last reported position, or null
     * @param commands the declarations, sorted by name
     */
    public BotCommands(String name, String owner, @Nullable Vec3d position, List<CommandDecl> commands) {
        this(name, owner, position, commands, List.of());
    }

    /**
     * Finds the command a typed word names: an exact name, an exact alias, then either ignoring case.
     *
     * @param word the typed word
     * @return the command, or null
     */
    public @Nullable CommandDecl command(String word) {
        for (CommandDecl c : commands) {
            if (c.data().name().equals(word)) {
                return c;
            }
        }
        for (CommandDecl c : commands) {
            List<String> aliases = c.data().aliases();
            if (aliases != null && aliases.contains(word)) {
                return c;
            }
        }
        for (CommandDecl c : commands) {
            if (TreeNode.of(c.data()).answersTo(word)) {
                return c;
            }
        }
        return null;
    }
}
