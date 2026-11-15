package io.github.pix3lpirat3.mfbridge.api;

/**
 * A typed configuration entry of an extension. The player edits it under Tools >
 * Extensions; the value is saved across restarts, and a saved value that no longer fits the type falls
 * back to the default.
 *
 * @param key {@code snake_case} key, unique per extension, for example {@code render.max_distance}
 * @param type value class: {@link Boolean}, {@link Integer}, {@link Long}, {@link Double} or {@link
 *     String}
 * @param defaultValue value used until the player changes it
 * @param description one line shown in the settings screen
 * @param <T> value type
 */
public record Setting<T>(String key, Class<T> type, T defaultValue, String description) {
    /** Validates the key, the value class and the default value. */
    public Setting {
        if (key.isEmpty()) {
            throw new IllegalArgumentException("empty setting key");
        }
        if (type != Boolean.class
                && type != Integer.class
                && type != Long.class
                && type != Double.class
                && type != String.class) {
            throw new IllegalArgumentException("unsupported setting type " + type.getName());
        }
        if (!type.isInstance(defaultValue)) {
            throw new IllegalArgumentException("default value does not match type " + type.getName());
        }
    }
}
