package io.github.pix3lpirat3.mfbridge.core.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Known devices, persisted owner-only in {@code devices.json}:
 * credentials, permission grants, per-bot overrides and quotas. Loaded once at start;
 * every change is handed to an asynchronous writer so the client thread never does I/O.
 */
@ClientThread
public final class DeviceRegistry {
    /** File name inside the bridge directory. */
    public static final String NAME = "devices.json";
    /** {@link Device#method()} of a device that authenticates with the local token. */
    public static final String LOCAL = "local";
    /** {@link Device#method()} of a paired remote device. */
    public static final String PAIRED = "device";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    /**
     * One device. Collections are copied into sorted, unmodifiable ones; null collections (older
     * files) become empty.
     *
     * @param id device id
     * @param name last display name from {@code hello}
     * @param method {@link #LOCAL} or {@link #PAIRED}
     * @param secret base64url device secret issued by pairing, or null for local devices
     * @param firstSeen wall-clock ms of first approval
     * @param lastSeen wall-clock ms of last successful authentication
     * @param lastAddress last peer address
     * @param label the player's name for the device, or null
     * @param grants permission wire name to granted, overriding the default permissions
     * @param bots bot identity to (permission wire name to granted), overriding {@code grants}
     * @param neverAsk permission wire names the player declined to be asked about
     * @param quotas quota storage key to value, overriding the defaults
     */
    public record Device(
            String id,
            String name,
            String method,
            @Nullable String secret,
            long firstSeen,
            long lastSeen,
            @Nullable String lastAddress,
            @Nullable String label,
            Map<String, Boolean> grants,
            Map<String, Map<String, Boolean>> bots,
            List<String> neverAsk,
            Map<String, Integer> quotas) {

        /** Normalizes the collections. */
        public Device {
            grants = sorted(grants);
            Map<String, Map<String, Boolean>> b = new TreeMap<>();
            if (bots != null) {
                for (Map.Entry<String, Map<String, Boolean>> e : bots.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                        b.put(e.getKey(), sorted(e.getValue()));
                    }
                }
            }
            bots = Collections.unmodifiableMap(b);
            neverAsk = neverAsk == null ? List.of() : List.copyOf(new TreeSet<>(neverAsk));
            quotas = sorted(quotas);
        }

        /** A device with no label, grants, overrides or quotas. */
        public Device(String id, String name, String method, @Nullable String secret, long firstSeen, long lastSeen,
                @Nullable String lastAddress) {
            this(id, name, method, secret, firstSeen, lastSeen, lastAddress, null, Map.of(), Map.of(), List.of(),
                    Map.of());
        }

        private static <V> Map<String, V> sorted(@Nullable Map<String, V> m) {
            Map<String, V> out = new TreeMap<>();
            if (m != null) {
                for (Map.Entry<String, V> e : m.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        out.put(e.getKey(), e.getValue());
                    }
                }
            }
            return Collections.unmodifiableMap(out);
        }

        /** Returns whether the device authenticates with the local token. */
        public boolean local() {
            return LOCAL.equals(method);
        }

        /** Returns a copy with new last-seen data. */
        public Device seen(String newName, String address, long nowMillis) {
            return new Device(id, newName, method, secret, firstSeen, nowMillis, address, label, grants, bots, neverAsk,
                    quotas);
        }

        /** Returns a copy with a new label. */
        public Device withLabel(@Nullable String newLabel) {
            return new Device(id, name, method, secret, firstSeen, lastSeen, lastAddress, newLabel, grants, bots,
                    neverAsk, quotas);
        }

        /** Returns a copy with new grants, overrides and never-ask marks. */
        public Device withPermissions(Map<String, Boolean> newGrants, Map<String, Map<String, Boolean>> newBots,
                List<String> newNeverAsk) {
            return new Device(id, name, method, secret, firstSeen, lastSeen, lastAddress, label, newGrants, newBots,
                    newNeverAsk, quotas);
        }

        /** Returns a copy with new quota overrides. */
        public Device withQuotas(Map<String, Integer> newQuotas) {
            return new Device(id, name, method, secret, firstSeen, lastSeen, lastAddress, label, grants, bots, neverAsk,
                    newQuotas);
        }
    }

    private record FileFormat(List<Device> devices) {}

    private final Path file;
    private final BiConsumer<Path, byte[]> writer;
    private final Map<String, Device> devices = new TreeMap<>();

    private DeviceRegistry(Path file, BiConsumer<Path, byte[]> writer) {
        this.file = file;
        this.writer = writer;
    }

    /**
     * Loads the registry. A missing file is an empty registry; a corrupt one is logged and treated as
     * empty, so every device has to be approved again rather than the bridge failing to start.
     *
     * @param writer persists {@code (file, bytes)} asynchronously and owner-only
     */
    public static DeviceRegistry load(Path file, BiConsumer<Path, byte[]> writer, Logger log) {
        DeviceRegistry registry = new DeviceRegistry(file, writer);
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            FileFormat parsed = GSON.fromJson(text, FileFormat.class);
            if (parsed != null && parsed.devices() != null) {
                for (Device d : parsed.devices()) {
                    if (d != null && d.id() != null && d.name() != null && d.method() != null) {
                        registry.devices.put(d.id(), d);
                    }
                }
            }
        } catch (NoSuchFileException e) {
            log.debug("No device registry yet at {}", file);
        } catch (IOException | JsonParseException e) {
            log.warn("Ignoring unreadable device registry {}: {}", file, e.toString());
        }
        return registry;
    }

    /** Returns a device, or null when unknown. */
    public @Nullable Device get(String id) {
        return devices.get(id);
    }

    /** Returns all devices sorted by id. */
    public List<Device> all() {
        return List.copyOf(devices.values());
    }

    /** Adds or replaces a device and persists the registry. */
    public void put(Device device) {
        devices.put(device.id(), device);
        save();
    }

    /** Updates last-seen data of a known device and persists. No-op for unknown devices. */
    public void touch(String id, String name, String address, long nowMillis) {
        Device d = devices.get(id);
        if (d != null) {
            put(d.seen(name, address, nowMillis));
        }
    }

    /**
     * Removes a device (revocation).
     *
     * @return whether it existed
     */
    public boolean remove(String id) {
        boolean removed = devices.remove(id) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    private void save() {
        String json = GSON.toJson(new FileFormat(new ArrayList<>(devices.values())));
        writer.accept(file, json.getBytes(StandardCharsets.UTF_8));
    }
}
