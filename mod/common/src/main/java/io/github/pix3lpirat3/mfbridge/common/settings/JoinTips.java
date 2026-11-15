package io.github.pix3lpirat3.mfbridge.common.settings;

import java.util.Locale;
import java.util.Optional;

/** When the mod prints its short how-to in chat on joining a world. */
public enum JoinTips {
    /** The welcome the first time, then a one-line reminder until the player has opened the menu. The default. */
    FIRST_TIME,
    /** A one-line reminder on every join. */
    EVERY_JOIN,
    /** Never. */
    OFF;

    /**
     * Returns the value written to the settings file.
     *
     * @return the lower-case name, such as {@code first_time}
     */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a settings-file value.
     *
     * @param key the value, case-insensitive
     * @return the choice, or empty if the value is unknown
     */
    public static Optional<JoinTips> fromKey(final String key) {
        for (final JoinTips tips : values()) {
            if (tips.key().equalsIgnoreCase(key)) {
                return Optional.of(tips);
            }
        }
        return Optional.empty();
    }
}
