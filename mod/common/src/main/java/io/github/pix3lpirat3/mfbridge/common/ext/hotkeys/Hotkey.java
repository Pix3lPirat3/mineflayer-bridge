package io.github.pix3lpirat3.mfbridge.common.ext.hotkeys;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Objects;

/**
 * A key the player bound to a chat line for bots, such as F6 for {@code @farmers stop}.
 *
 * @param key the vanilla key name, such as {@code key.keyboard.f6}
 * @param ctrl whether Ctrl must be held
 * @param shift whether Shift must be held
 * @param alt whether Alt must be held
 * @param line the line it runs, as if typed in chat
 */
public record Hotkey(String key, boolean ctrl, boolean shift, boolean alt, String line) {

    /** Checks the values. */
    public Hotkey {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(line, "line");
    }

    /**
     * Returns whether a press matches, modifiers exactly.
     *
     * @param name the pressed key's name
     * @param c Ctrl held
     * @param s Shift held
     * @param a Alt held
     * @return true when this hotkey fires
     */
    public boolean matches(final String name, final boolean c, final boolean s, final boolean a) {
        return key.equals(name) && ctrl == c && shift == s && alt == a;
    }

    /**
     * Returns the key as the player reads it, such as {@code Ctrl + F6}.
     *
     * @return the label
     */
    public String label() {
        return label(key, ctrl, shift, alt);
    }

    /**
     * Returns a key combination as the player reads it.
     *
     * @param key the key name, or empty for none
     * @param ctrl Ctrl
     * @param shift Shift
     * @param alt Alt
     * @return the label
     */
    public static String label(final String key, final boolean ctrl, final boolean shift, final boolean alt) {
        final StringBuilder b = new StringBuilder();
        if (ctrl) {
            b.append("Ctrl + ");
        }
        if (shift) {
            b.append("Shift + ");
        }
        if (alt) {
            b.append("Alt + ");
        }
        String name;
        try {
            name = InputConstants.getKey(key).getDisplayName().getString();
        } catch (final IllegalArgumentException e) {
            name = key;
        }
        return b.append(name).toString();
    }
}
