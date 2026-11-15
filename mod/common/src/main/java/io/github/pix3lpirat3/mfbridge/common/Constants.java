package io.github.pix3lpirat3.mfbridge.common;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Mod-wide identifiers and the shared logger. */
public final class Constants {

    /** The mod id on both loaders. NeoForge does not allow hyphens, hence the underscore. */
    public static final String MOD_ID = "mineflayer_bridge";

    /** Display name of the mod. */
    public static final String MOD_NAME = "Mineflayer Bridge";

    /** Logger for client-side code. */
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    private Constants() {
    }

    /**
     * Creates an identifier in the mod namespace.
     *
     * @param path the path, lower case
     * @return the identifier {@code mineflayer_bridge:path}
     */
    public static Identifier id(final String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
