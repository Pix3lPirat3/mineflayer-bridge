package io.github.pix3lpirat3.mfbridge.core.remote;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The device permissions with their defaults. Local devices (authenticated with the local token,
 * so they run on the player's own machine) hold every permission by default; remote devices (paired
 * with a code) get the remote default of each. The intercept cancel mode is not listed: it is granted
 * per bot through {@code InterceptControls}, and is on for bots of local devices.
 */
public enum Permission {
    /** World-space primitives. */
    RENDER_WORLD("render.world", "World shapes", true),
    /** HUD widgets. */
    RENDER_HUD("render.hud", "HUD widgets", true),
    /** Toasts. */
    NOTIFY("notify", "Toasts", true),
    /** Picks and prompts. */
    INPUT_PICK("input.pick", "Picks and prompts", true),
    /** Jumping the pick queue. */
    INPUT_PICK_URGENT("input.pick.urgent", "Urgent picks", false),
    /** Registering keybinds. */
    INPUT_KEYBIND("input.keybind", "Keybinds", true),
    /** Registering tools. */
    INPUT_TOOL("input.tool", "Tools", true),
    /** Interaction intercepts in observe mode. */
    INPUT_INTERCEPT("input.intercept", "Observe interactions", true),
    /** Registering commands and actions. */
    COMMANDS("commands", "Commands and actions", true),
    /** Opening form screens. */
    FORM("form", "Forms", true),
    /** Declaring and opening container views and screens. */
    UI_CONTAINER("ui.container", "Container views and screens", true),
    /** Displays, groups and world menus. */
    RENDER_DISPLAY("render.display", "Displays and world menus", true),
    /** Client-only sounds. */
    EFFECTS_SOUND("effects.sound", "Sounds", true),
    /** Client-only particles and emitters. */
    EFFECTS_PARTICLES("effects.particles", "Particles", true),
    /** Titles, action bar and boss bars. */
    EFFECTS_TITLE("effects.title", "Titles and boss bars", true),
    /** Declaring bot settings. */
    SETTINGS("settings", "Bot settings", true),
    /** Receiving chat addressed to the bot and printing client-only chat lines. */
    CHAT("chat", "Chat", false),
    /** Reading shared definitions. */
    SHARED_READ("shared.read", "Read shared definitions", true),
    /** Proposing ops on shared definitions. */
    SHARED_WRITE("shared.write", "Edit shared definitions", false),
    /** Player position samples. */
    CLIENT_SAMPLE_POSITION("client.sample.position", "Player position", false),
    /** Look target, block or entity under the crosshair. */
    CLIENT_SAMPLE_TARGET("client.sample.target", "Crosshair target", false),
    /** Held item. */
    CLIENT_SAMPLE_INVENTORY("client.sample.inventory", "Held item", false),
    /** Asking which entities and blocks the player's client has loaded. */
    CLIENT_QUERY("client.query", "Loaded entities and blocks", false),
    /** Opening controller sessions. */
    CONTROLLER("controller", "Controller sessions", false);

    private static final Map<String, Permission> BY_KEY = new HashMap<>();

    static {
        for (Permission p : values()) {
            BY_KEY.put(p.key, p);
        }
    }

    private final String key;
    private final String label;
    private final boolean remoteDefault;

    Permission(String key, String label, boolean remoteDefault) {
        this.key = key;
        this.label = label;
        this.remoteDefault = remoteDefault;
    }

    /** Returns the wire name, for example {@code render.world}. */
    public String key() {
        return key;
    }

    /** Returns a short label for the permissions screen. */
    public String label() {
        return label;
    }

    /**
     * Returns the default: always granted for a local device, the remote default otherwise.
     *
     * @param local whether the device authenticated with the local token
     */
    public boolean defaultFor(boolean local) {
        return local || remoteDefault;
    }

    /** Returns the permission with a wire name, or null when unknown. */
    public static @Nullable Permission byKey(String key) {
        return BY_KEY.get(key);
    }
}
