package io.github.pix3lpirat3.mfbridge.fabric;

import io.github.pix3lpirat3.mfbridge.common.BridgeClient;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.platform.Platform;
import io.github.pix3lpirat3.mfbridge.common.ui.command.BridgeCommands;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.resources.Identifier;

/** Fabric client entrypoint: forwards Fabric API events to {@link BridgeClient}. */
public final class MineflayerBridgeFabric implements ClientModInitializer {

    /** Created by Fabric Loader. */
    public MineflayerBridgeFabric() {
    }

    @Override
    public void onInitializeClient() {
        final BridgeClient client = BridgeClient.initialize(Platform.load());
        Constants.LOG.info("{} {} on Fabric", Constants.MOD_NAME, client.platform().modVersion());

        for (final KeyMapping.Category category : client.keyCategories()) {
            try {
                KeyMapping.Category.register(category.id());
            } catch (final IllegalArgumentException alreadyRegistered) {
                // Another mod registered the same category first; its keys join it.
            }
        }
        for (final KeyMapping key : client.keyMappings()) {
            KeyMappingHelper.registerKeyMapping(key);
        }
        // Client commands run locally and are never sent to the server.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(BridgeCommands.<FabricClientCommandSource>create(client.ui())));
        ClientLifecycleEvents.CLIENT_STARTED.register(minecraft -> client.onClientStarted());
        ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> client.onClientStopping());
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> client.onEndClientTick());
        ClientPlayConnectionEvents.JOIN.register((listener, sender, minecraft) -> client.onPlayJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((listener, minecraft) -> client.onPlayLeave());
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((minecraft, level) -> client.onLevelChanged());
        // Geometry is submitted while the game collects level submits; the game draws it in its own
        // feature phases, after opaque terrain and with translucent features or OIT.
        LevelRenderEvents.COLLECT_SUBMITS.register(
                context -> client.onCollectWorldGeometry(context.submitNodeCollector(), context.poseStack(), context.levelState()));
        HudElementRegistry.addLast(BridgeClient.HUD_ELEMENT_ID, client::onHudRender);
        // The Bridge button goes in a phase after the default one, so it sees buttons other mods (Mod Menu)
        // add to the same screens and steps over them.
        final Identifier entryPhase = Constants.id("entry_button");
        ScreenEvents.AFTER_INIT.addPhaseOrdering(Event.DEFAULT_PHASE, entryPhase);
        ScreenEvents.AFTER_INIT.register(entryPhase, (minecraft, screen, width, height) -> {
            final AbstractWidget button = client.entryButton(screen);
            if (button != null) {
                Screens.getWidgets(screen).add(button);
            }
        });
    }
}
