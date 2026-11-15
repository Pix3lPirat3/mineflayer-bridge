package io.github.pix3lpirat3.mfbridge.common.settings;

import io.github.pix3lpirat3.mfbridge.common.Constants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Loads and saves {@code <config>/mineflayer_bridge/settings.json5}. Blocking file IO; call off hot paths. */
public final class SettingsStore {

    private final Path file;

    /**
     * Creates a store for one file.
     *
     * @param file the settings file; its parent directory is created on save
     */
    public SettingsStore(final Path file) {
        this.file = file;
    }

    /**
     * Returns the settings file location.
     *
     * @return the path
     */
    public Path file() {
        return file;
    }

    /**
     * Loads the settings. A missing file gives the defaults silently; an unreadable or invalid file gives
     * the defaults plus a problem description, and the file is left untouched so the player can fix it.
     *
     * @return the settings and any problem
     */
    public LoadResult load() {
        try {
            final String text = Files.readString(file, StandardCharsets.UTF_8);
            return new LoadResult(SettingsCodec.decode(text), Optional.empty());
        } catch (final NoSuchFileException e) {
            return new LoadResult(BridgeSettings.DEFAULTS, Optional.empty());
        } catch (final IOException | IllegalArgumentException e) {
            Constants.LOG.warn("Ignoring settings file {}: {}", file, e.getMessage());
            return new LoadResult(BridgeSettings.DEFAULTS, Optional.of(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Saves the settings, replacing the file atomically where the file system allows it.
     *
     * @param settings the settings to write
     * @return null on success, otherwise a description of the failure
     */
    public @Nullable String save(final BridgeSettings settings) {
        try {
            Files.createDirectories(file.getParent());
            final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, SettingsCodec.encode(settings), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return null;
        } catch (final IOException e) {
            Constants.LOG.error("Could not save settings to {}", file, e);
            return String.valueOf(e.getMessage());
        }
    }

    /**
     * Outcome of {@link #load()}.
     *
     * @param settings the settings to use
     * @param problem why the file was ignored, if it was
     */
    public record LoadResult(BridgeSettings settings, Optional<String> problem) {
    }
}
