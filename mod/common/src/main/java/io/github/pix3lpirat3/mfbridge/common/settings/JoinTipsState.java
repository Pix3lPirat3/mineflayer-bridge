package io.github.pix3lpirat3.mfbridge.common.settings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * What the player has seen of the mod's how-to: the welcome, and whether they have opened the menu.
 * Kept in its own small file, apart from the settings the player edits.
 */
public final class JoinTipsState {
    /** What to print on joining a world. */
    public enum Tip {
        /** Nothing. */
        NONE,
        /** The welcome, a few lines. */
        WELCOME,
        /** One line: the menu key and the chat prefix. */
        REMINDER
    }

    private static final String WELCOMED = "welcomed";
    private static final String MENU_OPENED = "menu_opened";

    private final Path file;
    private boolean welcomed;
    private boolean menuOpened;

    /**
     * Reads the state; a missing or unreadable file means nothing was seen yet.
     *
     * @param file the state file
     */
    public JoinTipsState(final Path file) {
        this.file = file;
        try {
            final JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            welcomed = o.has(WELCOMED) && o.get(WELCOMED).getAsBoolean();
            menuOpened = o.has(MENU_OPENED) && o.get(MENU_OPENED).getAsBoolean();
        } catch (final NoSuchFileException e) {
            // First run.
        } catch (final IOException | RuntimeException e) {
            Constants.LOG.debug("Could not read {}: {}", file, e.toString());
        }
    }

    /**
     * Decides what to print on joining a world, and records the welcome as seen.
     *
     * @param tips the player's choice
     * @return the tip to print
     */
    public Tip onJoin(final JoinTips tips) {
        final Tip tip = decide(tips, welcomed, menuOpened);
        if (tip == Tip.WELCOME) {
            welcomed = true;
            save();
        }
        return tip;
    }

    /** Records that the player opened the menu; reminders stop after this unless they asked for every join. */
    public void menuOpened() {
        if (!menuOpened) {
            menuOpened = true;
            save();
        }
    }

    /**
     * The decision on its own: the welcome once, then a reminder until the menu was opened, or on every
     * join when the player chose that.
     *
     * @param tips the player's choice
     * @param welcomed whether the welcome was shown before
     * @param menuOpened whether the player has opened the menu
     * @return the tip to print
     */
    public static Tip decide(final JoinTips tips, final boolean welcomed, final boolean menuOpened) {
        if (tips == JoinTips.OFF) {
            return Tip.NONE;
        }
        if (!welcomed) {
            return Tip.WELCOME;
        }
        return tips == JoinTips.EVERY_JOIN || !menuOpened ? Tip.REMINDER : Tip.NONE;
    }

    private void save() {
        final JsonObject o = new JsonObject();
        o.addProperty(WELCOMED, welcomed);
        o.addProperty(MENU_OPENED, menuOpened);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, o + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            Constants.LOG.debug("Could not write {}: {}", file, e.toString());
        }
    }
}
