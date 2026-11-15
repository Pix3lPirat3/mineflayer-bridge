package io.github.pix3lpirat3.mfbridge.common.settings;

import java.util.Locale;
import java.util.Optional;

/** Which bots are attached without the player choosing them. */
public enum AttachPolicy {
    /** Attach bots whose world matches the player's. The default. */
    WORLD_MATCH,
    /** Attach every paired bot regardless of world. */
    ALWAYS_PAIRED,
    /** Attach nothing until the player chooses. */
    MANUAL;

    /**
     * Returns the value written to the settings file.
     *
     * @return the lower-case name, such as {@code world_match}
     */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a settings-file value.
     *
     * @param key the value, case-insensitive
     * @return the policy, or empty if the value is unknown
     */
    public static Optional<AttachPolicy> fromKey(final String key) {
        for (final AttachPolicy policy : values()) {
            if (policy.key().equalsIgnoreCase(key)) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }
}
