package io.github.pix3lpirat3.mfbridge.core.persist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.AttachPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The player's view preferences: attach policy, attach choices per bot identity, layer overrides,
 * the per-bot intercept cancel permission and HUD panel placements, stored as {@code player/prefs.json}.
 * Loaded once; saves are handed to an asynchronous writer.
 */
@ClientThread
public final class PlayerPrefs {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private record FileFormat(int v, @Nullable String attachPolicy, @Nullable Map<String, Boolean> attach,
            @Nullable Map<String, Boolean> layers, @Nullable Map<String, Boolean> interceptCancel,
            @Nullable Map<String, HudPlacement> hud, @Nullable String primaryMenu) {}

    /**
     * A saved HUD panel placement, as stored in the file.
     *
     * @param anchor anchor wire name such as {@code top_left}, or null for a free position
     * @param x left edge of a free panel as a fraction of the GUI width, 0..1
     * @param y top edge of a free panel as a fraction of the GUI height, 0..1
     * @param width panel width in GUI pixels, or 0 for the natural width
     * @param hidden whether the player hid the panel
     */
    public record HudPlacement(@Nullable String anchor, double x, double y, int width, boolean hidden) {}

    private final Path file;
    private final BiConsumer<Path, byte[]> writer;
    private AttachPolicy policy;
    private final Map<String, Boolean> attach = new TreeMap<>();
    private final Map<String, Boolean> layers = new TreeMap<>();
    private final Map<String, Boolean> interceptCancel = new TreeMap<>();
    private final Map<String, HudPlacement> hud = new TreeMap<>();
    private @Nullable String primaryMenu;

    private PlayerPrefs(Path file, BiConsumer<Path, byte[]> writer, AttachPolicy policy) {
        this.file = file;
        this.writer = writer;
        this.policy = policy;
    }

    /**
     * Loads preferences; a missing or corrupt file yields defaults.
     *
     * @param defaultPolicy policy used when the file has none
     * @param writer persists {@code (file, bytes)} asynchronously
     */
    public static PlayerPrefs load(Path file, AttachPolicy defaultPolicy, BiConsumer<Path, byte[]> writer, Logger log) {
        PlayerPrefs prefs = new PlayerPrefs(file, writer, defaultPolicy);
        try {
            FileFormat f = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), FileFormat.class);
            if (f != null) {
                if (f.attachPolicy() != null) {
                    prefs.policy = AttachPolicy.valueOf(f.attachPolicy());
                }
                copyBooleans(f.attach(), prefs.attach);
                copyBooleans(f.layers(), prefs.layers);
                copyBooleans(f.interceptCancel(), prefs.interceptCancel);
                prefs.primaryMenu = f.primaryMenu();
                Map<String, HudPlacement> placements = f.hud();
                if (placements != null) {
                    for (Map.Entry<String, HudPlacement> e : placements.entrySet()) {
                        if (e.getKey() != null && e.getValue() != null) {
                            prefs.hud.put(e.getKey(), e.getValue());
                        }
                    }
                }
            }
        } catch (NoSuchFileException e) {
            log.debug("No player preferences yet at {}", file);
        } catch (IOException | JsonParseException | IllegalArgumentException e) {
            log.warn("Ignoring unreadable player preferences {}: {}", file, e.toString());
        }
        return prefs;
    }

    private static void copyBooleans(@Nullable Map<String, Boolean> from, Map<String, Boolean> to) {
        if (from != null) {
            for (Map.Entry<String, Boolean> e : from.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    to.put(e.getKey(), e.getValue());
                }
            }
        }
    }

    /** Returns the attach policy. */
    public AttachPolicy policy() {
        return policy;
    }

    /** Sets the attach policy and saves. */
    public void policy(AttachPolicy value) {
        policy = value;
        save();
    }

    /** Returns the mutable attach choices by identity; call {@link #save()} after changing. */
    public Map<String, Boolean> attach() {
        return attach;
    }

    /** Returns the mutable layer overrides by layer key; call {@link #save()} after changing. */
    public Map<String, Boolean> layers() {
        return layers;
    }

    /**
     * Returns the mutable per-bot choices for the intercept cancel permission ({@code
     * input.intercept}) by identity; call {@link #save()} after changing.
     */
    public Map<String, Boolean> interceptCancel() {
        return interceptCancel;
    }

    /**
     * Returns the mutable HUD placements by layout key (see {@code HudLayouts}); call {@link #save()}
     * after changing.
     */
    public Map<String, HudPlacement> hud() {
        return hud;
    }

    /**
     * Returns the object key of the declared screen the player picked as the primary menu.
     *
     * @return the key, or null for Bridge home
     */
    public @Nullable String primaryMenu() {
        return primaryMenu;
    }

    /**
     * Sets the primary menu; call {@link #save()} after changing.
     *
     * @param screenKey object key of a {@code core:screen}, or null for Bridge home
     */
    public void primaryMenu(@Nullable String screenKey) {
        this.primaryMenu = screenKey;
    }

    /** Persists the current preferences asynchronously. */
    public void save() {
        String json = GSON.toJson(new FileFormat(1, policy.name(), attach, layers, interceptCancel, hud, primaryMenu));
        writer.accept(file, json.getBytes(StandardCharsets.UTF_8));
    }
}
