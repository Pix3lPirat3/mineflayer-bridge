package io.github.pix3lpirat3.mfbridge.common.version;

import net.minecraft.SharedConstants;

/** Picks the {@link VersionSupport} of the running game, once. */
public final class Versions {

    /** The running game's support; a static final, so calls through it inline. */
    public static final VersionSupport CURRENT = load(SharedConstants.getCurrentVersion().name());

    private Versions() {
    }

    private static VersionSupport load(final String version) {
        final String module = VersionNames.module(version);
        if (module == null) {
            throw new IllegalStateException("Mineflayer Bridge does not support Minecraft " + version);
        }
        try {
            return (VersionSupport) Class.forName("io.github.pix3lpirat3.mfbridge." + module + ".Support")
                    .getDeclaredConstructor().newInstance();
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Mineflayer Bridge: no support classes for Minecraft " + version, e);
        }
    }
}
