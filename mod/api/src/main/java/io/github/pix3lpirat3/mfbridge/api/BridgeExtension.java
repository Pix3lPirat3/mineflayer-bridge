package io.github.pix3lpirat3.mfbridge.api;

import java.util.List;

/**
 * Entry point for a mod-side bridge extension. Discovered through the Fabric
 * entrypoint {@code mineflayer_bridge} or {@link java.util.ServiceLoader} on NeoForge.
 */
public interface BridgeExtension {
    /** Returns the stable extension id; its string form is what Node halves name in {@code hello}. */
    ExtensionId id();

    /** Returns the extension version, advertised in {@code welcome}. */
    SemVer version();

    /**
     * Returns the extensions this one needs, as {@code id} or {@code id@range} strings.
     * They register first; when one is missing, switched off or failed, this extension
     * is not registered and Tools > Extensions says why.
     */
    default List<String> requires() {
        return List.of();
    }

    /**
     * Returns extensions this one uses when they are present, in the same form: they register first,
     * and this extension registers without them otherwise.
     */
    default List<String> optional() {
        return List.of();
    }

    /**
     * Registers kinds, message handlers, matchers and settings. Runs once on the client thread
     * during client init, before the bridge accepts connections. Must not block.
     */
    void register(ExtensionRegistrar registrar);
}
