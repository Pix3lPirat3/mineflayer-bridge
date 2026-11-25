package io.github.pix3lpirat3.mfbridge.common.version;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * The plugin of each version module's mixin config: its mixins apply only when that module is the
 * running game's. Every config ships in both jars, so this is what picks one. It touches no game
 * class, which must not load this early.
 */
public final class VersionMixins implements IMixinConfigPlugin {

    private static @Nullable String running;
    private boolean applies;

    @Override
    public void onLoad(final String mixinPackage) {
        final String module = mixinPackage.replaceFirst("^io\\.github\\.pix3lpirat3\\.mfbridge\\.([a-z0-9_]+)\\.mixin$", "$1");
        applies = module.equals(running());
    }

    private static synchronized @Nullable String running() {
        if (running == null) {
            String name = VersionNames.running(VersionMixins.class.getClassLoader());
            if (name == null) {
                name = VersionNames.running(Thread.currentThread().getContextClassLoader());
            }
            running = name == null ? "" : VersionNames.module(name);
            if (running == null || running.isEmpty()) {
                LoggerFactory.getLogger("Mineflayer Bridge").error("Mineflayer Bridge cannot tell the game version ({}); its version mixins stay off", name);
                running = "";
            }
        }
        return running;
    }

    @Override
    public @Nullable String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        return applies;
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Override
    public @Nullable List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
    }
}
