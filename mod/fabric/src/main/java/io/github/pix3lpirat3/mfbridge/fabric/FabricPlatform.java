package io.github.pix3lpirat3.mfbridge.fabric;

import io.github.pix3lpirat3.mfbridge.api.BridgeExtension;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.platform.Platform;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/** {@link Platform} on Fabric Loader. Loaded through {@link java.util.ServiceLoader}. */
public final class FabricPlatform implements Platform {

    /** Creates the platform; called by {@link java.util.ServiceLoader}. */
    public FabricPlatform() {
    }

    @Override
    public String loaderName() {
        return "fabric";
    }

    @Override
    public String modVersion() {
        return FabricLoader.getInstance().getModContainer(Constants.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isModLoaded(final String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public List<BridgeExtension> extensions() {
        return FabricLoader.getInstance().getEntrypoints(Constants.MOD_ID, BridgeExtension.class);
    }
}
