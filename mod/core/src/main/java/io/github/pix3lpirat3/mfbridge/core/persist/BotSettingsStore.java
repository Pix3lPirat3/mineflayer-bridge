package io.github.pix3lpirat3.mfbridge.core.persist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The player's values for bot-declared settings, saved per bot identity
 * and stored as {@code player/settings.json}. Values are keyed by the full
 * {@code core:settings} object key, which contains the owner identity, then by setting key. Only
 * values the player changed from the default are kept. Loaded once; saves are handed to an
 * asynchronous writer.
 *
 * <p>Stored values are JSON scalars as Gson reads them ({@code Boolean}, {@code Double}, {@code
 * String}); callers re-validate them against the current declaration before use.
 *
 * <p>The file also remembers which device each bot identity belongs to: the device that first
 * opened a session with it.
 */
@ClientThread
public final class BotSettingsStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private record FileFormat(int v, @Nullable Map<String, Map<String, Object>> values,
            @Nullable Map<String, String> devices) {}

    private final Path file;
    private final BiConsumer<Path, byte[]> writer;
    private final Map<String, Map<String, Object>> values = new TreeMap<>();
    /** Bot identity to the id of the device it belongs to. */
    private final Map<String, String> devices = new TreeMap<>();
    /** The same for devices that are only known in this run; never saved. */
    private final Map<String, String> runDevices = new HashMap<>();

    private BotSettingsStore(Path file, BiConsumer<Path, byte[]> writer) {
        this.file = file;
        this.writer = writer;
    }

    /**
     * Loads stored values; a missing or corrupt file yields none.
     *
     * @param writer persists {@code (file, bytes)} asynchronously
     */
    public static BotSettingsStore load(Path file, BiConsumer<Path, byte[]> writer, Logger log) {
        BotSettingsStore store = new BotSettingsStore(file, writer);
        try {
            FileFormat f = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), FileFormat.class);
            Map<String, Map<String, Object>> stored = f == null ? null : f.values();
            if (stored != null) {
                for (Map.Entry<String, Map<String, Object>> e : stored.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) {
                        continue;
                    }
                    Map<String, Object> group = new TreeMap<>();
                    for (Map.Entry<String, Object> v : e.getValue().entrySet()) {
                        if (v.getKey() != null && v.getValue() != null) {
                            group.put(v.getKey(), v.getValue());
                        }
                    }
                    if (!group.isEmpty()) {
                        store.values.put(e.getKey(), group);
                    }
                }
            }
            Map<String, String> owners = f == null ? null : f.devices();
            if (owners != null) {
                for (Map.Entry<String, String> e : owners.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        store.devices.put(e.getKey(), e.getValue());
                    }
                }
            }
        } catch (NoSuchFileException e) {
            log.debug("No bot settings yet at {}", file);
        } catch (IOException | JsonParseException | ClassCastException e) {
            log.warn("Ignoring unreadable bot settings {}: {}", file, e.toString());
        }
        return store;
    }

    /** Returns the stored values of one settings object, or an empty map. Unmodifiable. */
    public Map<String, Object> get(String settingsKey) {
        Map<String, Object> group = values.get(settingsKey);
        return group == null ? Map.of() : Collections.unmodifiableMap(group);
    }

    /**
     * Stores or clears one value and saves.
     *
     * @param value the value, or null to fall back to the declared default
     */
    public void set(String settingsKey, String key, @Nullable Object value) {
        if (value == null) {
            Map<String, Object> group = values.get(settingsKey);
            if (group == null || group.remove(key) == null) {
                return;
            }
            if (group.isEmpty()) {
                values.remove(settingsKey);
            }
        } else {
            values.computeIfAbsent(settingsKey, k -> new TreeMap<>()).put(key, value);
        }
        save();
    }

    /** Returns the id of the device a bot identity belongs to, or null when no device claimed it. */
    public @Nullable String device(String identity) {
        String d = runDevices.get(identity);
        return d != null ? d : devices.get(identity);
    }

    /** Gives a bot identity to a device and saves. */
    public void bind(String identity, String deviceId) {
        runDevices.remove(identity);
        if (!deviceId.equals(devices.put(identity, deviceId))) {
            save();
        }
    }

    /** Gives a bot identity to a device for this run only. */
    public void bindForRun(String identity, String deviceId) {
        runDevices.put(identity, deviceId);
    }

    /**
     * Forgets which device a bot identity belongs to, so the next device to use it gets it.
     *
     * @return whether one was remembered
     */
    public boolean unbind(String identity) {
        boolean run = runDevices.remove(identity) != null;
        if (devices.remove(identity) != null) {
            save();
            return true;
        }
        return run;
    }

    /** Returns the bot identities that belong to a device, sorted. */
    public List<String> identitiesOf(String deviceId) {
        TreeSet<String> out = new TreeSet<>();
        for (Map<String, String> m : List.of(devices, runDevices)) {
            for (Map.Entry<String, String> e : m.entrySet()) {
                if (e.getValue().equals(deviceId)) {
                    out.add(e.getKey());
                }
            }
        }
        return List.copyOf(out);
    }

    private void save() {
        String json = GSON.toJson(new FileFormat(1, values, devices));
        writer.accept(file, json.getBytes(StandardCharsets.UTF_8));
    }
}
