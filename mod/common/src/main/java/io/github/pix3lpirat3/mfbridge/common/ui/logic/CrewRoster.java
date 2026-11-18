package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import io.github.pix3lpirat3.mfbridge.core.Presence;
import io.github.pix3lpirat3.mfbridge.core.decl.CrewDecl;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Crew bots the mod has no session or record of yet, such as a bot a controller has not logged in. */
public final class CrewRoster {
    private CrewRoster() {}

    /**
     * A crew bot without a presence record.
     *
     * @param name the bot's name as its crew gives it
     * @param controller display name of the controller whose crew it is in
     */
    public record Unseen(String name, String controller) {}

    /** Crew bots not in {@code presence}, by name and ignoring case, each once, in crew order. */
    public static List<Unseen> unseen(final List<CrewDecl> crews, final List<Presence> presence) {
        final Set<String> seen = new HashSet<>();
        for (final Presence p : presence) {
            seen.add(p.displayName().toLowerCase(Locale.ROOT));
        }
        final List<Unseen> out = new ArrayList<>();
        for (final CrewDecl c : crews) {
            for (final String bot : c.data().bots()) {
                if (seen.add(bot.toLowerCase(Locale.ROOT))) {
                    out.add(new Unseen(bot, c.ownerName()));
                }
            }
        }
        return out;
    }
}
