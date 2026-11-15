package io.github.pix3lpirat3.mfbridge.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.pix3lpirat3.mfbridge.common.BridgeClient;

/**
 * Mod Menu entrypoint that opens Bridge home from the mods list; the settings are one click further. Mod Menu is optional at runtime:
 * Fabric Loader only loads this class when Mod Menu asks for {@code modmenu} entrypoints.
 */
public final class ModMenuIntegration implements ModMenuApi {

    /** Created by Fabric Loader on behalf of Mod Menu. */
    public ModMenuIntegration() {
    }

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> BridgeClient.get().createHomeScreen(parent);
    }
}
