package io.github.pix3lpirat3.mfbridge.common.platform;

import io.github.pix3lpirat3.mfbridge.api.BridgeExtension;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Loader services needed by common code. Each loader module provides exactly one implementation,
 * registered in {@code META-INF/services}. Methods are safe to call from any thread.
 */
public interface Platform {

    /**
     * Loads the implementation provided by the running loader.
     *
     * @return the platform
     * @throws IllegalStateException if no implementation, or more than one, is on the class path
     */
    static Platform load() {
        final var providers = ServiceLoader.load(Platform.class, Platform.class.getClassLoader()).stream().toList();
        if (providers.size() != 1) {
            throw new IllegalStateException("Expected exactly one Platform implementation, found " + providers.size());
        }
        return providers.getFirst().get();
    }

    /**
     * Returns the loader name.
     *
     * @return {@code fabric} or {@code neoforge}
     */
    String loaderName();

    /**
     * Returns this mod's version as declared in the loader metadata.
     *
     * @return the version, such as {@code 0.1.0}
     */
    String modVersion();

    /**
     * Returns the game's config directory. The bridge keeps its files in a {@code mineflayer_bridge}
     * subdirectory of it.
     *
     * @return an absolute path
     */
    Path configDir();

    /**
     * Returns whether another mod is loaded.
     *
     * @param modId the mod id
     * @return {@code true} if a mod with that id is loaded
     */
    boolean isModLoaded(String modId);

    /**
     * Returns the bridge extensions other mods provide: the {@code mineflayer_bridge}
     * entrypoint on Fabric, {@link ServiceLoader} on NeoForge. First-party extensions are not included.
     *
     * @return the extensions, in a stable order
     */
    List<BridgeExtension> extensions();
}
