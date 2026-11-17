package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.remote.Quota;
import io.github.pix3lpirat3.mfbridge.core.remote.Quotas;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectKeys;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.core.traits.Traits;
import io.github.pix3lpirat3.mfbridge.protocol.core.BlockHighlightData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.GhostBlocksData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HeatmapData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectTraits;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Counted quotas. Per owner: displays, entity displays among them, particle emitters, interactive
 * elements, keybinds, tools, commands, actions and the bytes of object data. Per device, over all
 * of its sessions and the shared objects it created: objects, bytes of object data and retained
 * geometry bytes. Shared objects also have a total of their own. Counts follow the store through
 * {@link #added} and {@link #removed}; {@link #check} runs before a write is stored, so a rejected
 * write never changes the counts. Limits come from the writer's device.
 */
@ClientThread
final class OwnerQuotas {
    private enum Counter {
        DISPLAY("display", Quota.DISPLAYS),
        ENTITY_DISPLAY("entity display", Quota.ENTITY_DISPLAYS),
        EMITTER("particle emitter", Quota.EMITTERS),
        SOUND_EMITTER("sound emitter", Quota.SOUND_EMITTERS),
        INTERACTIVE("interactive element", Quota.INTERACTIVE),
        KEYBIND("keybind", Quota.DECLARATIONS),
        TOOL("tool", Quota.DECLARATIONS),
        COMMAND("command", Quota.COMMANDS),
        ACTION("action", Quota.DECLARATIONS);

        final String label;
        final Quota quota;

        Counter(String label, Quota quota) {
            this.label = label;
            this.quota = quota;
        }
    }

    private static final Counter[] COUNTERS = Counter.values();
    private static final String SHARED = "shared";

    /** What one device holds over all of its sessions. */
    private static final class DeviceUse {
        int objects;
        long bytes;
        long retained;

        boolean empty() {
            return objects == 0 && bytes == 0 && retained == 0;
        }
    }

    private final List<Object2IntOpenHashMap<String>> counts = new ArrayList<>();
    /** Bytes of object data per owner. */
    private final Object2LongOpenHashMap<String> ownerBytes = new Object2LongOpenHashMap<>();
    private final Map<String, DeviceUse> devices = new HashMap<>();
    /** The device whose sessions write for an owner. */
    private final Map<String, String> ownerDevice = new HashMap<>();
    /** The device that created a shared object in this run, by key. */
    private final Map<String, String> sharedCreator = new HashMap<>();
    private final int maxSharedObjects;
    private final long maxSharedBytes;
    private int sharedObjects;
    private long sharedBytes;

    /**
     * Creates the counters.
     *
     * @param maxSharedObjects shared objects kept in total
     * @param maxSharedBytes data bytes of all shared objects together
     */
    OwnerQuotas(int maxSharedObjects, long maxSharedBytes) {
        this.maxSharedObjects = maxSharedObjects;
        this.maxSharedBytes = maxSharedBytes;
        for (int i = 0; i < COUNTERS.length; i++) {
            counts.add(new Object2IntOpenHashMap<>());
        }
    }

    static boolean isDisplay(KindType<?> kind) {
        return kind == CoreKinds.DISPLAY_TEXT || kind == CoreKinds.DISPLAY_ITEM || kind == CoreKinds.DISPLAY_BLOCK
                || kind == CoreKinds.DISPLAY_ENTITY;
    }

    private static int mask(KindType<?> kind, @Nullable Object decoded, @Nullable ObjectTraits traits) {
        int m = 0;
        if (isDisplay(kind)) {
            m |= bit(Counter.DISPLAY);
        }
        // Every interactive object counts, whatever its kind.
        if (Traits.interactive(traits) != null) {
            m |= bit(Counter.INTERACTIVE);
        }
        if (kind == CoreKinds.DISPLAY_ENTITY) {
            m |= bit(Counter.ENTITY_DISPLAY);
        } else if (kind == CoreKinds.PARTICLE_EMITTER) {
            m |= bit(Counter.EMITTER);
        } else if (kind == CoreKinds.SOUND_EMITTER) {
            m |= bit(Counter.SOUND_EMITTER);
        } else if (kind == CoreKinds.KEYBIND) {
            m |= bit(Counter.KEYBIND);
        } else if (kind == CoreKinds.TOOL) {
            m |= bit(Counter.TOOL);
        } else if (kind == CoreKinds.COMMAND) {
            m |= bit(Counter.COMMAND);
        } else if (kind == CoreKinds.ACTION) {
            m |= bit(Counter.ACTION);
        }
        return m;
    }

    private static int mask(StoredObject.State state) {
        return mask(state.kind(), state.decoded(), state.traits());
    }

    private static int bit(Counter c) {
        return 1 << c.ordinal();
    }

    /** Says which device writes for an owner from now on. The owner must hold no objects of another device. */
    void bind(String owner, String deviceId) {
        ownerDevice.put(owner, deviceId);
    }

    /** Forgets the device of an owner whose objects are gone. */
    void unbind(String owner) {
        ownerDevice.remove(owner);
    }

    /**
     * Rejects a session's write that would put its owner or its device over a quota.
     *
     * @param decoded the decoded data of the write
     * @param traits the traits of the write
     * @param dataBytes the encoded size of the write's data
     * @param previous the state the write replaces, or null for a new key
     * @throws BridgeException {@code quota_exceeded}
     */
    void check(String owner, String deviceId, KindType<?> kind, @Nullable Object decoded, @Nullable ObjectTraits traits,
            int dataBytes, StoredObject.@Nullable State previous, Quotas limits) {
        int added = mask(kind, decoded, traits) & ~(previous == null ? 0 : mask(previous));
        for (Counter c : COUNTERS) {
            if ((added & bit(c)) == 0) {
                continue;
            }
            int max = limits.get(c.quota);
            if (counts.get(c.ordinal()).getInt(owner) >= max) {
                throw new BridgeException("quota_exceeded", c.label + " quota of " + max + " reached");
            }
        }
        long ownerMax = Integer.toUnsignedLong(limits.get(Quota.OWNER_BYTES));
        long grows = dataBytes - (previous == null ? 0 : previous.data().length);
        if (grows > 0 && ownerBytes.getLong(owner) + grows > ownerMax) {
            throw new BridgeException("quota_exceeded", "object data quota of " + ownerMax + " bytes per session reached");
        }
        checkDevice(deviceId, decoded, dataBytes, previous, limits);
    }

    /**
     * Rejects a shared create or update that would put the shared total, or the device that makes
     * it, over a quota.
     *
     * @param deviceId the device behind the op, or null for the player
     * @param limits that device's quotas, or null for the player
     * @throws BridgeException {@code quota_exceeded}
     */
    void checkShared(@Nullable String deviceId, @Nullable Object decoded, int dataBytes,
            StoredObject.@Nullable State previous, @Nullable Quotas limits) {
        if (previous == null && sharedObjects >= maxSharedObjects) {
            throw new BridgeException("quota_exceeded", "shared object limit of " + maxSharedObjects + " reached");
        }
        long grows = dataBytes - (previous == null ? 0 : previous.data().length);
        if (grows > 0 && sharedBytes + grows > maxSharedBytes) {
            throw new BridgeException("quota_exceeded", "shared data limit of " + maxSharedBytes + " bytes reached");
        }
        if (deviceId != null && limits != null) {
            checkDevice(deviceId, decoded, dataBytes, previous, limits);
        }
    }

    private void checkDevice(String deviceId, @Nullable Object decoded, int dataBytes, StoredObject.@Nullable State previous,
            Quotas limits) {
        DeviceUse use = devices.get(deviceId);
        int objects = use == null ? 0 : use.objects;
        long bytes = use == null ? 0 : use.bytes;
        long retained = use == null ? 0 : use.retained;
        int maxObjects = limits.get(Quota.OBJECTS);
        if (previous == null && objects >= maxObjects) {
            throw new BridgeException("quota_exceeded", "object quota of " + maxObjects + " reached");
        }
        long maxBytes = Integer.toUnsignedLong(limits.get(Quota.DEVICE_BYTES));
        long grows = dataBytes - (previous == null ? 0 : previous.data().length);
        if (grows > 0 && bytes + grows > maxBytes) {
            throw new BridgeException("quota_exceeded", "object data quota of " + maxBytes + " bytes per device reached");
        }
        long retainedGrows = retainedBytes(decoded) - (previous == null ? 0 : retainedBytes(previous.decoded()));
        long maxRetained = Integer.toUnsignedLong(limits.get(Quota.RETAINED_BYTES));
        if (retainedGrows > 0 && retained + retainedGrows > maxRetained) {
            throw new BridgeException("quota_exceeded", "retained geometry quota of " + (maxRetained >> 20) + " MiB reached");
        }
    }

    /** Bytes of retained geometry an object holds: its blob, or its inline positions. */
    static long retainedBytes(@Nullable Object decoded) {
        return switch (decoded) {
            case GhostBlocksData g -> g.size();
            case HeatmapData h -> h.size();
            case BlockHighlightData b -> b.size() != null ? b.size() : b.positions() == null ? 0 : 4L * b.positions().length;
            case null, default -> 0;
        };
    }

    /** Notes which device creates a shared object, before it is stored. */
    void sharedCreatedBy(String key, String deviceId) {
        sharedCreator.put(key, deviceId);
    }

    /** Counts a stored object. */
    void added(StoredObject o, StoredObject.State state) {
        adjust(o, state, 1);
    }

    /** Uncounts an object that was replaced; its key stays in the store. */
    void replaced(StoredObject o, StoredObject.State previous) {
        adjust(o, previous, -1);
    }

    /** Uncounts an object that left the store. */
    void removed(StoredObject o, StoredObject.State state) {
        adjust(o, state, -1);
        if (o.parts().owner().equals(SHARED)) {
            sharedCreator.remove(o.key());
        }
    }

    private void adjust(StoredObject o, StoredObject.State state, int by) {
        String owner = o.parts().owner();
        long data = (long) state.data().length * by;
        String device;
        if (owner.equals(SHARED)) {
            sharedObjects += by;
            sharedBytes += data;
            device = sharedCreator.get(o.key());
        } else {
            device = ownerDevice.get(owner);
            add(ownerBytes, owner, data);
            int m = mask(state);
            for (Counter c : COUNTERS) {
                if ((m & bit(c)) == 0) {
                    continue;
                }
                Object2IntOpenHashMap<String> map = counts.get(c.ordinal());
                int next = map.getInt(owner) + by;
                if (next <= 0) {
                    map.removeInt(owner);
                } else {
                    map.put(owner, next);
                }
            }
        }
        if (device == null || o.key().startsWith(ObjectKeys.PLAYER_PREFIX)) {
            return;
        }
        DeviceUse use = devices.computeIfAbsent(device, k -> new DeviceUse());
        use.objects += by;
        use.bytes += data;
        use.retained += retainedBytes(state.decoded()) * by;
        if (use.empty()) {
            devices.remove(device);
        }
    }

    private static void add(Object2LongOpenHashMap<String> map, String key, long by) {
        long next = map.getLong(key) + by;
        if (next <= 0) {
            map.removeLong(key);
        } else {
            map.put(key, next);
        }
    }
}
