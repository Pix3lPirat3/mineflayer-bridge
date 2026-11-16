package io.github.pix3lpirat3.mfbridge.core.command;

import java.util.List;

/**
 * A name the player may type after {@code /bridge} or the chat prefix, and what it reaches.
 *
 * @param name the name as declared: a bot's or controller's display name, a crew group, or {@code all}
 * @param kind what the name is
 * @param commands the command sets a line after the name is parsed against, in order: for a bot its
 *     own commands, then the orders of each crew it is in; for a group or {@code all} the crews'
 *     orders for their bots (and for {@code all} every bot's own commands first). Immutable.
 * @param members bot display names it stands for: the bot itself, a group's live members, or
 *     every live bot; empty for a controller. Immutable.
 * @param chatOwners owner keys, without trailing slash, that get the line as {@code chat.message}
 *     when it names no command. Immutable.
 * @param elsewhere for a bot, that it is not in the player's world; orders still reach it
 */
public record Addressee(String name, Kind kind, List<BotCommands> commands, List<String> members, List<String> chatOwners,
        boolean elsewhere) {
    /** What an address names. */
    public enum Kind {
        /** Every live bot of the player, in any world. */
        ALL,
        /** One live bot, in any world. */
        BOT,
        /** A controller session. */
        CONTROLLER,
        /** A named group of a crew. */
        GROUP
    }

    /** Copies the lists. */
    public Addressee {
        commands = List.copyOf(commands);
        members = List.copyOf(members);
        chatOwners = List.copyOf(chatOwners);
    }

    /** Whether the name stands for several bots, so free text is sent with {@code broadcast} set. */
    public boolean broadcast() {
        return kind == Kind.ALL || kind == Kind.GROUP;
    }
}
