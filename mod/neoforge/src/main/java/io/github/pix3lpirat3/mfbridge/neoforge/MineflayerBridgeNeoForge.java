package io.github.pix3lpirat3.mfbridge.neoforge;

import io.github.pix3lpirat3.mfbridge.common.BridgeClient;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.platform.Platform;
import io.github.pix3lpirat3.mfbridge.common.ui.command.BridgeCommands;
import io.github.pix3lpirat3.mfbridge.common.version.Versions;
import java.lang.reflect.Method;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStartedEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** NeoForge client entrypoint: forwards NeoForge events to {@link BridgeClient}. */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public final class MineflayerBridgeNeoForge {

    /**
     * Created by FML on the client.
     *
     * @param modBus this mod's event bus
     * @param container this mod's container
     */
    public MineflayerBridgeNeoForge(final IEventBus modBus, final ModContainer container) {
        final BridgeClient client = BridgeClient.initialize(Platform.load());
        Constants.LOG.info("{} {} on NeoForge", Constants.MOD_NAME, client.platform().modVersion());

        // The mods list's Config button opens Bridge home; the settings are one click further.
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (mod, parent) -> client.createHomeScreen(parent));

        modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
            for (final KeyMapping.Category category : client.keyCategories()) {
                try {
                    event.registerCategory(category);
                } catch (final IllegalArgumentException | IllegalStateException alreadyRegistered) {
                    // Another mod registered the same category first; its keys join it.
                }
            }
            for (final KeyMapping key : client.keyMappings()) {
                event.register(key);
            }
        });
        modBus.addListener(RegisterGuiLayersEvent.class,
                event -> event.registerAboveAll(BridgeClient.HUD_ELEMENT_ID, client::onHudRender));
        modBus.addListener(RegisterRenderPipelinesEvent.class, MineflayerBridgeNeoForge::registerPipelines);

        final IEventBus gameBus = NeoForge.EVENT_BUS;
        gameBus.addListener(ClientStartedEvent.class, event -> client.onClientStarted());
        gameBus.addListener(ClientStoppingEvent.class, event -> client.onClientStopping());
        gameBus.addListener(ClientTickEvent.Post.class, event -> client.onEndClientTick());
        gameBus.addListener(ClientPlayerNetworkEvent.LoggingIn.class, event -> client.onPlayJoin());
        gameBus.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> client.onPlayLeave());
        gameBus.addListener(ClientPlayerNetworkEvent.Clone.class, event -> client.onLevelChanged());
        // Lowest priority, so the Bridge button sees buttons other mods add to the same screens and steps over them.
        gameBus.addListener(EventPriority.LOWEST, ScreenEvent.Init.Post.class, event -> {
            final AbstractWidget button = client.entryButton(event.getScreen());
            if (button != null) {
                event.addListener(button);
            }
        });
        // Client commands run locally and are never sent to the server.
        gameBus.addListener(RegisterClientCommandsEvent.class,
                event -> event.getDispatcher().register(BridgeCommands.<CommandSourceStack>create(client.ui())));
        // Geometry is submitted while the game collects level submits; the game draws it in its own
        // feature phases, after opaque terrain and with translucent features or OIT.
        gameBus.addListener(SubmitCustomGeometryEvent.class, event -> client.onCollectWorldGeometry(
                event.getSubmitNodeCollector(), event.getPoseStack(), event.getLevelRenderState()));
    }

    /**
     * Hands the game version's pipelines to NeoForge to compile at startup. The event's parameter type
     * moved with the render API, so it is found by name.
     */
    private static void registerPipelines(final RegisterRenderPipelinesEvent event) {
        try {
            Method register = null;
            for (final Method m : event.getClass().getMethods()) {
                if (m.getName().equals("registerPipeline") && m.getParameterCount() == 1) {
                    register = m;
                }
            }
            if (register == null) {
                throw new NoSuchMethodException("RegisterRenderPipelinesEvent.registerPipeline");
            }
            for (final Object pipeline : Versions.CURRENT.gpu().pipelines()) {
                register.invoke(event, pipeline);
            }
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Could not register the bridge's render pipelines", e);
        }
    }
}
