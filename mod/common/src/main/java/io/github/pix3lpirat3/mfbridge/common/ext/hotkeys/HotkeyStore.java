package io.github.pix3lpirat3.mfbridge.common.ext.hotkeys;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The player's hotkeys, kept in {@code hotkeys.json} in the mod's config folder. */
final class HotkeyStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private record FileFormat(@Nullable List<Hotkey> hotkeys) {}

    private final Path file;
    private final List<Hotkey> hotkeys = new ArrayList<>();

    HotkeyStore(final Path file) {
        this.file = file;
        load();
    }

    List<Hotkey> all() {
        return List.copyOf(hotkeys);
    }

    /** Adds a hotkey or replaces the one at {@code index}; a hotkey on the same keys is replaced. */
    void put(final int index, final Hotkey h) {
        if (index >= 0 && index < hotkeys.size()) {
            hotkeys.set(index, h);
        } else {
            hotkeys.add(h);
        }
        for (int i = hotkeys.size() - 1; i >= 0; i--) {
            final Hotkey other = hotkeys.get(i);
            if (other != h && other.matches(h.key(), h.ctrl(), h.shift(), h.alt())) {
                hotkeys.remove(i);
            }
        }
        save();
    }

    void remove(final int index) {
        if (index >= 0 && index < hotkeys.size()) {
            hotkeys.remove(index);
            save();
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            final FileFormat f = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), FileFormat.class);
            if (f != null && f.hotkeys() != null) {
                for (final Hotkey h : f.hotkeys()) {
                    if (h != null && h.key() != null && h.line() != null) {
                        hotkeys.add(h);
                    }
                }
            }
        } catch (final IOException | JsonParseException | NullPointerException e) {
            Constants.LOG.warn("Could not read {}: {}", file, e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(new FileFormat(hotkeys)), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            Constants.LOG.warn("Could not write {}: {}", file, e.getMessage());
        }
    }
}
