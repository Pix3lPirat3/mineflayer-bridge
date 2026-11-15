package io.github.pix3lpirat3.mfbridge.core.ext;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.pix3lpirat3.mfbridge.api.Setting;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Saved values of mod extension settings, {@code extension-settings.json} in the bridge directory:
 * one object keyed by {@code <extension id>/<setting key>}. Values that no longer match their setting
 * are ignored, so a changed type falls back to the default.
 */
public final class ExtensionSettingsFile {

    private static final Gson GSON = new Gson();

    private final Map<String, JsonElement> values = new TreeMap<>();
    private volatile @Nullable Consumer<byte[]> writer;

    private ExtensionSettingsFile() {}

    /** Returns a store that keeps values in memory only. */
    public static ExtensionSettingsFile memory() {
        return new ExtensionSettingsFile();
    }

    /**
     * Reads the file. A missing or unreadable file gives no saved values.
     *
     * @param file the file
     * @param log where problems go
     * @return the store; it writes nothing until {@link #writeWith} is called
     */
    public static ExtensionSettingsFile load(Path file, Logger log) {
        ExtensionSettingsFile out = new ExtensionSettingsFile();
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (root.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                    out.values.put(e.getKey(), e.getValue());
                }
            }
        } catch (NoSuchFileException e) {
            // no saved values yet
        } catch (IOException | JsonParseException | IllegalStateException e) {
            log.warn("Ignoring unreadable {}: {}", file, e.toString());
        }
        return out;
    }

    /**
     * Sets where changes are written: the whole file's bytes on every change.
     *
     * @param writer receives the encoded file
     */
    public void writeWith(Consumer<byte[]> writer) {
        this.writer = writer;
    }

    /**
     * Returns the saved value of a setting, or null when none is saved or it no longer fits.
     *
     * @param qualifiedKey {@code <extension id>/<key>}
     * @param setting the setting
     * @param <T> value type
     * @return the value, or null
     */
    public synchronized <T> @Nullable T get(String qualifiedKey, Setting<T> setting) {
        JsonElement e = values.get(qualifiedKey);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        Object v = decode(e.getAsJsonPrimitive(), setting.type());
        return setting.type().isInstance(v) ? setting.type().cast(v) : null;
    }

    /**
     * Saves a value and writes the file.
     *
     * @param qualifiedKey {@code <extension id>/<key>}
     * @param value the value: a boolean, number or string
     */
    public void put(String qualifiedKey, Object value) {
        byte[] bytes;
        synchronized (this) {
            JsonPrimitive p = value instanceof Boolean b ? new JsonPrimitive(b)
                    : value instanceof Number n ? new JsonPrimitive(n) : new JsonPrimitive(value.toString());
            if (p.equals(values.put(qualifiedKey, p))) {
                return;
            }
            JsonObject root = new JsonObject();
            values.forEach(root::add);
            bytes = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        }
        Consumer<byte[]> w = writer;
        if (w != null) {
            w.accept(bytes);
        }
    }

    /**
     * Parses what the player typed for a setting.
     *
     * @param setting the setting
     * @param text the input
     * @param <T> value type
     * @return the value, or null when the text does not fit the setting's type
     */
    public static <T> @Nullable T parse(Setting<T> setting, String text) {
        String t = text.strip();
        Object v;
        try {
            if (setting.type() == String.class) {
                v = text;
            } else if (setting.type() == Boolean.class) {
                v = t.equalsIgnoreCase("true") ? Boolean.TRUE : t.equalsIgnoreCase("false") ? Boolean.FALSE : null;
            } else if (setting.type() == Integer.class) {
                v = Integer.parseInt(t);
            } else if (setting.type() == Long.class) {
                v = Long.parseLong(t);
            } else {
                double d = Double.parseDouble(t);
                v = Double.isFinite(d) ? d : null;
            }
        } catch (NumberFormatException e) {
            v = null;
        }
        return setting.type().isInstance(v) ? setting.type().cast(v) : null;
    }

    private static @Nullable Object decode(JsonPrimitive p, Class<?> type) {
        try {
            if (type == Boolean.class) {
                return p.isBoolean() ? p.getAsBoolean() : null;
            }
            if (type == String.class) {
                return p.isString() ? p.getAsString() : null;
            }
            if (!p.isNumber()) {
                return null;
            }
            if (type == Double.class) {
                return p.getAsDouble();
            }
            double d = p.getAsDouble();
            if (d != Math.rint(d)) {
                return null;
            }
            if (type == Integer.class) {
                return d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE ? p.getAsInt() : null;
            }
            return type == Long.class ? p.getAsLong() : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
