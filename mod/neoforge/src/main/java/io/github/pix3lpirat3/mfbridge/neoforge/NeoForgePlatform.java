package io.github.pix3lpirat3.mfbridge.neoforge;

import io.github.pix3lpirat3.mfbridge.api.BridgeExtension;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.platform.Platform;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** {@link Platform} on NeoForge. Loaded through {@link java.util.ServiceLoader}. */
public final class NeoForgePlatform implements Platform {

    /** Creates the platform; called by {@link java.util.ServiceLoader}. */
    public NeoForgePlatform() {
    }

    @Override
    public String loaderName() {
        return "neoforge";
    }

    @Override
    public String modVersion() {
        return ModList.get().getModContainerById(Constants.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isModLoaded(final String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public List<BridgeExtension> extensions() {
        final List<BridgeExtension> out = new ArrayList<>();
        ServiceLoader.load(BridgeExtension.class, NeoForgePlatform.class.getClassLoader()).forEach(out::add);
        return out;
    }
}
