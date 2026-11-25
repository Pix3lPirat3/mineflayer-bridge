package io.github.pix3lpirat3.mfbridge.common.version;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Game version names to support modules, with no game class touched: mixin plugins run before the game loads. */
public final class VersionNames {

    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    private VersionNames() {
    }

    /**
     * Returns the support module's package suffix for a game version: {@code 26.1.2} is {@code mc26_1}.
     *
     * @param version the game's version name
     * @return the suffix, or null when no module supports it
     */
    public static @Nullable String module(final String version) {
        final String[] parts = version.split("[.\\-\\s]", 3);
        if (parts.length < 2 || !parts[0].equals("26")) {
            return null;
        }
        return switch (parts[1]) {
            case "1", "2", "3" -> "mc26_" + parts[1];
            default -> null;
        };
    }

    /**
     * Reads the running game's version name from the game jar's {@code version.json}, as the game does.
     *
     * @param loader where to look
     * @return the name, or null when it cannot be read
     */
    public static @Nullable String running(final ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream("version.json")) {
            if (in == null) {
                return null;
            }
            final Matcher m = NAME.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : null;
        } catch (final IOException e) {
            return null;
        }
    }
}
