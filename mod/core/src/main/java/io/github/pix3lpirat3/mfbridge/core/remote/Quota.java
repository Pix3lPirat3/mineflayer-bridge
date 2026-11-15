package io.github.pix3lpirat3.mfbridge.core.remote;

import org.jspecify.annotations.Nullable;

/**
 * The quotas that the player can raise per device. Objects, object data, retained geometry, blob
 * bytes in flight and the message and byte rates are counted per device, over all of its sessions
 * and connections; the others per session. Defaults for the entries that {@code BridgeConfig} also
 * carries come from the configuration; see {@link Quotas#defaults}.
 */
public enum Quota {
    /** Objects per device, with the shared objects it created. */
    OBJECTS("objects", "Objects", 20_000),
    /** Encoded object data per session, in bytes. */
    OWNER_BYTES("object_bytes", "Object data per session (bytes)", 16 << 20),
    /** Encoded object data per device, in bytes. */
    DEVICE_BYTES("device_object_bytes", "Object data per device (bytes)", 64 << 20),
    /** Display objects per session. */
    DISPLAYS("displays", "Displays", 4_096),
    /** Entity displays per session, counted within {@link #DISPLAYS}. */
    ENTITY_DISPLAYS("entity_displays", "Entity displays", 256),
    /** Particle emitters per session. */
    EMITTERS("emitters", "Particle emitters", 32),
    /** Sound emitters per session; each holds a sound channel while it plays. */
    SOUND_EMITTERS("sound_emitters", "Sound emitters", 16),
    /** Interactive display elements per session. */
    INTERACTIVE("interactive", "Interactive elements", 1_024),
    /** Keybinds, tools, commands and actions per session, each. */
    DECLARATIONS("declarations", "Keybinds, tools, commands, actions (each)", 256),
    /** Sound and particle calls per second. */
    EFFECTS_PER_SECOND("effects_per_second", "Effects per second", 40),
    /** Size of one blob, in bytes. */
    BLOB_BYTES("blob_bytes", "Blob size (bytes)", 32 << 20),
    /** Retained geometry per device: blob and inline bytes of ghost blocks, heatmaps and block highlights. */
    RETAINED_BYTES("retained_bytes", "Retained geometry (bytes)", 64 << 20),
    /** Declared blob bytes in flight per device. */
    BLOB_IN_FLIGHT("blob_in_flight_bytes", "Blob bytes in flight", 128 << 20),
    /** Messages per second per device, sustained. */
    MESSAGES_PER_SECOND("messages_per_second", "Messages per second", 2_000),
    /** Bytes per second per device, sustained. */
    BYTES_PER_SECOND("bytes_per_second", "Bytes per second", 8 << 20);

    private final String key;
    private final String label;
    private final int specDefault;

    Quota(String key, String label, int specDefault) {
        this.key = key;
        this.label = label;
        this.specDefault = specDefault;
    }

    /** Returns the storage key in {@code devices.json}. */
    public String key() {
        return key;
    }

    /** Returns a short label for the quotas screen. */
    public String label() {
        return label;
    }

    /** Returns the built-in default. */
    public int specDefault() {
        return specDefault;
    }

    /** Returns the quota with a storage key, or null when unknown. */
    public static @Nullable Quota byKey(String key) {
        for (Quota q : values()) {
            if (q.key.equals(key)) {
                return q;
            }
        }
        return null;
    }
}
