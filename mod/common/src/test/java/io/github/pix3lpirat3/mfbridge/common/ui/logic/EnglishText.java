package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** The core en_us.json as {@link Text}, so tests assert the English the player sees. */
final class EnglishText implements Text {

    private static final Path FILE = Path.of("src/main/resources/assets/mineflayer_bridge/lang/en_us.json");

    static final EnglishText INSTANCE = new EnglishText();

    private final Map<String, String> values = new HashMap<>();

    private EnglishText() {
        try {
            final JsonObject json = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
            json.entrySet().forEach(e -> values.put(e.getKey(), e.getValue().getAsString()));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String get(final String key, final Object... args) {
        final String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("no key " + key);
        }
        return String.format(Locale.ROOT, value, args);
    }
}
