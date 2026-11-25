package io.github.pix3lpirat3.mfbridge.common;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.common.version.Keys;

import io.github.pix3lpirat3.mfbridge.common.ext.BridgeFacade;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.pix3lpirat3.mfbridge.common.bridge.CoreIntegration;
import io.github.pix3lpirat3.mfbridge.common.ext.ClientExtensions;
import io.github.pix3lpirat3.mfbridge.common.ext.FirstParty;
import io.github.pix3lpirat3.mfbridge.common.game.GameBridge26x;
import io.github.pix3lpirat3.mfbridge.common.game.ServerKeys;
import io.github.pix3lpirat3.mfbridge.common.platform.Platform;
import io.github.pix3lpirat3.mfbridge.common.render.Blaze3dRenderBackend;
import io.github.pix3lpirat3.mfbridge.common.render.DemoRenderSource;
import io.github.pix3lpirat3.mfbridge.common.render.command.FrameCommands;
import io.github.pix3lpirat3.mfbridge.common.render.command.WorldRenderSource;
import io.github.pix3lpirat3.mfbridge.common.render.retained.RetainedRenderer;
import io.github.pix3lpirat3.mfbridge.common.seam.GameBridge;
import io.github.pix3lpirat3.mfbridge.common.seam.Notification;
import io.github.pix3lpirat3.mfbridge.common.seam.RenderBackend;
import io.github.pix3lpirat3.mfbridge.common.settings.BridgeSettings;
import io.github.pix3lpirat3.mfbridge.common.settings.JoinTipsState;
import io.github.pix3lpirat3.mfbridge.common.settings.SettingsScreen;
import io.github.pix3lpirat3.mfbridge.common.settings.SettingsStore;
import io.github.pix3lpirat3.mfbridge.common.spatial.PointIcons;
import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import io.github.pix3lpirat3.mfbridge.common.ui.home.BridgeHomeScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.home.EntryButtons;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.diag.BridgeMetrics;
import io.github.pix3lpirat3.mfbridge.core.diag.Gauge;
import io.github.pix3lpirat3.mfbridge.core.diag.Metric;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The client half of the bridge, shared by both loaders.
 *
 * <p>Loader glue forwards game events to the {@code on*} methods; integration code plugs in through
 * {@link #setWorldRenderSource} and {@link #setLifecycleListener}. Every method runs on the client
 * (render) thread unless stated otherwise; 26.x renders on the client thread.
 */
public final class BridgeClient {

    /** Id of the HUD element registered by both loaders. */
    public static final Identifier HUD_ELEMENT_ID = Constants.id("hud");

    /** System property that enables the demo render source in development. */
    public static final String DEMO_PROPERTY = "mfbridge.demo";

    /** Queue gauges walk concurrent queues, so they are sampled four times a second, not every frame. */
    private static final long GAUGE_PERIOD_NANOS = 250_000_000L;

    private static @Nullable BridgeClient instance;

    private final Platform platform;
    private final SettingsStore settingsStore;
    private final JoinTipsState joinTips;
    /** Ticks until the join tip prints, so it lands after the server's own join messages; -1 for none. */
    private int tipTicks = -1;
    private JoinTipsState.Tip pendingTip = JoinTipsState.Tip.NONE;
    private final GameBridge26x gameBridge;
    private final Blaze3dRenderBackend renderBackend;
    private final KeyMapping openHomeKey;
    private final BridgeUi ui;
    private final RetainedRenderer retained;
    private final ClientExtensions extensions;
    private volatile Supplier<@Nullable BridgeCore> coreSource = () -> null;
    private long gaugesSampledNanos;
    private volatile WorldRenderSource worldRenderSource = WorldRenderSource.EMPTY;
    /** Reused every frame; render thread only. */
    private final FrameCommands frameCommands = new FrameCommands();
    private ClientLifecycleListener lifecycleListener = ClientLifecycleListener.NONE;
    private BridgeSettings settings = BridgeSettings.DEFAULTS;
    private @Nullable String serverKey;
    private @Nullable String dimension;

    private BridgeClient(final Platform platform) {
        this.platform = platform;
        this.settingsStore = new SettingsStore(platform.configDir().resolve(Constants.MOD_ID).resolve("settings.json5"));
        this.joinTips = new JoinTipsState(platform.configDir().resolve(Constants.MOD_ID).resolve("tips.json"));
        this.gameBridge = new GameBridge26x();
        this.renderBackend = new Blaze3dRenderBackend();
        this.ui = new BridgeUi(this::notify);
        this.retained = new RetainedRenderer();
        retained.lookedAt(() -> ui.displays().targetKey());
        this.extensions = new ClientExtensions(FirstParty.extensions(), platform.extensions(), platform.configDir(), ui.input(),
                new BridgeFacade(ui));
        // Bridge home has a key in game. B has no vanilla 26.3 binding of its own; F3+B
        // (hitboxes) is a debug chord, and vanilla does not click other mappings for a key it used as one.
        this.openHomeKey = BridgeUi.keyMapping("open_home", Keys.KEY_B);
        ui.setHomeFactory(this::createHomeScreen);
        ui.setSettingsFactory(this::createSettingsScreen);
        ui.setHomeKey(openHomeKey);
        gameBridge.addWorldRenderListener(frame -> {
            final long start = System.nanoTime();
            final FrameCommands commands = frameCommands;
            commands.clear();
            worldRenderSource.frameCommands(frame, commands);
            renderBackend.drawImmediate(frame, commands);
            final BridgeMetrics m = metrics();
            if (m != null) {
                m.record(Metric.DRAW_IMMEDIATE, System.nanoTime() - start);
                m.gauge(Gauge.IMMEDIATE_COMMANDS, commands.size());
            }
        });
        // Vanilla visuals are submitted in the same collection pass as the shapes.
        gameBridge.addWorldRenderListener(frame -> {
            final long start = System.nanoTime();
            ui.displays().render(frame);
            final BridgeMetrics m = metrics();
            if (m != null) {
                m.record(Metric.DRAW_DISPLAYS, System.nanoTime() - start);
            }
        });
        // Point icon items join the same collection pass.
        final PointIcons icons = new PointIcons(() -> coreSource.get());
        gameBridge.addWorldRenderListener(icons::render);
        // The retained tier syncs and meshes here and draws later, after translucency.
        gameBridge.addWorldRenderListener(frame -> retained.frame(frame, coreSource.get()));
        gameBridge.addWorldRenderListener(extensions::worldRender);
    }

    /**
     * Creates the singleton. Called once by the loader entrypoint, before any other method.
     *
     * @param platform the loader services
     * @return the new client
     * @throws IllegalStateException if called twice
     */
    public static BridgeClient initialize(final Platform platform) {
        if (instance != null) {
            throw new IllegalStateException("BridgeClient is already initialized");
        }
        final BridgeClient client = new BridgeClient(platform);
        instance = client;
        final CoreIntegration integration = new CoreIntegration(client);
        client.setLifecycleListener(integration);
        client.ui.setCoreSource(integration::core);
        client.coreSource = integration::core;
        if (Boolean.getBoolean(DEMO_PROPERTY)) {
            final DemoRenderSource demo = new DemoRenderSource();
            client.setWorldRenderSource(demo);
            client.gameBridge.addHudRenderListener(demo);
            Constants.LOG.info("Demo render source enabled by -D{}=true", DEMO_PROPERTY);
        } else {
            client.setWorldRenderSource(integration);
        }
        return client;
    }

    /**
     * Returns the singleton.
     *
     * @return the client
     * @throws IllegalStateException if {@link #initialize} was not called yet
     */
    public static BridgeClient get() {
        final BridgeClient client = instance;
        if (client == null) {
            throw new IllegalStateException("BridgeClient is not initialized");
        }
        return client;
    }

    /**
     * Returns the mod extensions of this client: their states, switches and client hooks.
     *
     * @return the extensions
     */
    public ClientExtensions extensions() {
        return extensions;
    }

    /**
     * Returns the loader services.
     *
     * @return the platform
     */
    public Platform platform() {
        return platform;
    }

    /**
     * Returns the game seam for this version.
     *
     * @return the game bridge
     */
    public GameBridge gameBridge() {
        return gameBridge;
    }

    /**
     * Returns the render seam for this version.
     *
     * @return the render backend
     */
    public RenderBackend renderBackend() {
        return renderBackend;
    }

    /**
     * Returns the retained render tier: ghost blocks, heatmaps and block highlights.
     *
     * @return the retained renderer
     */
    public RetainedRenderer retained() {
        return retained;
    }

    /**
     * Returns every key mapping of the bridge: the Bridge home key and the UI's keys. Loaders register
     * them with their key mapping API.
     *
     * @return the key mappings, in registration order
     */
    public List<KeyMapping> keyMappings() {
        final List<KeyMapping> keys = new ArrayList<>();
        keys.add(openHomeKey);
        keys.addAll(ui.keyMappings());
        keys.addAll(extensions.keyMappings());
        return keys;
    }

    /**
     * Returns the key categories the loader must register: the bridge's own and any an extension
     * brought, without vanilla's.
     *
     * @return the categories, each once
     */
    public List<KeyMapping.Category> keyCategories() {
        final List<KeyMapping.Category> vanilla = List.of(KeyMapping.Category.MOVEMENT, KeyMapping.Category.MISC,
                KeyMapping.Category.MULTIPLAYER, KeyMapping.Category.GAMEPLAY, KeyMapping.Category.INVENTORY,
                KeyMapping.Category.CREATIVE, KeyMapping.Category.SPECTATOR, KeyMapping.Category.DEBUG);
        final List<KeyMapping.Category> out = new ArrayList<>();
        for (final KeyMapping key : keyMappings()) {
            if (!vanilla.contains(key.getCategory()) && !out.contains(key.getCategory())) {
                out.add(key.getCategory());
            }
        }
        return out;
    }

    /**
     * Returns the in-game UI layer: input arbitration, picks, prompts, forms, palette, tools and HUD.
     * Loaders register the {@code /bridge} client command for it.
     *
     * @return the UI
     */
    public BridgeUi ui() {
        return ui;
    }

    /**
     * Returns the current settings.
     *
     * @return the settings last loaded or saved
     */
    public BridgeSettings settings() {
        return settings;
    }

    /**
     * Sets the source of immediate-tier draw commands. Safe to call from any thread; the next frame uses it.
     *
     * @param source the source, or {@link WorldRenderSource#EMPTY} to draw nothing
     */
    public void setWorldRenderSource(final WorldRenderSource source) {
        this.worldRenderSource = Objects.requireNonNull(source, "source");
    }

    /**
     * Sets the listener that receives lifecycle callbacks, replacing any previous one.
     *
     * @param listener the listener, or {@link ClientLifecycleListener#NONE}
     */
    public void setLifecycleListener(final ClientLifecycleListener listener) {
        this.lifecycleListener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Creates the settings screen.
     *
     * @param parent the screen to return to when it closes, or null for none
     * @return a new screen
     */
    public Screen createSettingsScreen(final @Nullable Screen parent) {
        return createSettingsScreen(parent, null);
    }

    /**
     * Creates the settings screen, or the settings of one bot.
     *
     * @param parent the screen to return to when it closes, or null for none
     * @param owner {@code bot:<identity>} to show only that bot's settings groups, or null for everything
     * @return a new screen
     */
    public Screen createSettingsScreen(final @Nullable Screen parent, final @Nullable String owner) {
        return new SettingsScreen(parent, settings, this::applySettings, ui, owner);
    }

    /**
     * Creates Bridge home. The Mod Menu and NeoForge config entry points open it too.
     *
     * @param parent the screen to return to when it closes, or null for none
     * @return a new screen
     */
    public Screen createHomeScreen(final @Nullable Screen parent) {
        joinTips.menuOpened();
        return new BridgeHomeScreen(parent, this);
    }

    /**
     * Loader glue: a screen finished initialising, after every other mod's handler. Returns the Bridge
     * button to add to the title screen or pause menu.
     *
     * @param screen the screen
     * @return the button for the loader to add, or null when this screen gets none
     */
    public @Nullable AbstractWidget entryButton(final Screen screen) {
        return EntryButtons.create(screen, () -> ui.openPrimary(screen));
    }

    /**
     * Shows a toast through the game bridge.
     *
     * @param notification what to show
     */
    public void notify(final Notification notification) {
        gameBridge.showToast(notification);
    }

    /** Loader glue: the client finished starting. Loads settings from disk. */
    public void onClientStarted() {
        final SettingsStore.LoadResult result = settingsStore.load();
        settings = result.settings();
        renderBackend.setLabelsVisible(settings.showLabels());
        ui.setShowAllHud(settings.showAllHud());
        ui.setMessagesInChat(settings.messagesInChat());
        ui.sounds().setGain(settings.botSoundVolume() / (float) BridgeSettings.MAX_BOT_SOUND_VOLUME);
        result.problem().ifPresent(problem -> notify(new Notification(
                Constants.MOD_NAME, "Settings file ignored: " + problem, Notification.Severity.WARNING)));
        retained.install();
        lifecycleListener.onClientStarted(settings);
    }

    /** Loader glue: the client is shutting down. */
    public void onClientStopping() {
        onWorldLeave();
        lifecycleListener.onClientStopping();
        renderBackend.close();
        retained.close();
    }

    /** Loader glue: end of a client tick. Handles the primary menu key, catches dimension changes and ticks the UI. */
    public void onEndClientTick() {
        final Minecraft minecraft = Minecraft.getInstance();
        while (openHomeKey.consumeClick()) {
            if (Game.screen() == null) {
                joinTips.menuOpened();
                ui.openPrimary(null);
            }
        }
        onLevelChanged();
        onTick();
        ui.tick();
        extensions.tick(minecraft.player != null && Game.screen() == null);
    }

    /** Loader glue: the play connection is ready and the player is in a world. */
    public void onPlayJoin() {
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        onWorldJoin(ServerKeys.current(minecraft), level.dimension().identifier().toString());
    }

    /** Loader glue: the play connection closed. */
    public void onPlayLeave() {
        onWorldLeave();
    }

    /**
     * Loader glue: the client level instance changed, as on respawn or dimension travel. Also polled every
     * tick, because the loaders report dimension travel through different events.
     */
    public void onLevelChanged() {
        final String key = serverKey;
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        if (key == null || level == null) {
            return;
        }
        final String current = level.dimension().identifier().toString();
        // Opening a singleplayer world to LAN changes its key while the player stays in it.
        final String currentKey = ServerKeys.current(minecraft);
        if (!currentKey.equals(key)) {
            onWorldJoin(currentKey, current);
            return;
        }
        if (!current.equals(dimension)) {
            dimension = current;
            lifecycleListener.onDimensionChange(key, current);
        }
    }

    /**
     * Loader glue: the game is collecting world geometry for this frame.
     *
     * @param collector the frame's submit collector
     * @param poseStack the level pose stack, at identity; positions are camera-relative
     * @param levelState the frame's level render state
     */
    public void onCollectWorldGeometry(final SubmitNodeCollector collector, final PoseStack poseStack, final LevelRenderState levelState) {
        gameBridge.fireWorldRender(collector, poseStack, levelState);
    }

    /**
     * Loader glue: the HUD element is being drawn.
     *
     * @param graphics the GUI extractor for this frame
     * @param deltaTracker the frame's delta tracker
     */
    public void onHudRender(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
        gameBridge.fireHudRender(graphics, deltaTracker);
        ui.renderHud(graphics);
        extensions.hud(graphics, deltaTracker);
        // The HUD is drawn after the world, so the retained tier's cost for this frame is complete here.
        final long retainedNanos = retained.takeFrameNanos();
        final BridgeMetrics m = metrics();
        if (m != null) {
            m.record(Metric.DRAW_RETAINED, retainedNanos);
            final long now = System.nanoTime();
            if (now - gaugesSampledNanos >= GAUGE_PERIOD_NANOS) {
                gaugesSampledNanos = now;
                m.gauge(Gauge.RETAINED_BYTES, retained.gpuBytes());
                m.gauge(Gauge.UPLOAD_QUEUE, retained.queueDepth());
            }
        }
    }

    /** Returns the running core's metrics, or null while the endpoint is down. */
    private @Nullable BridgeMetrics metrics() {
        final BridgeCore core = coreSource.get();
        return core == null ? null : core.inspector().metrics();
    }

    /** Integration hook: one client tick passed. Called by {@link #onEndClientTick}. */
    public void onTick() {
        lifecycleListener.onTick();
        if (tipTicks > 0 && --tipTicks == 0) {
            tipTicks = -1;
            printTip(pendingTip);
        }
    }

    /** The welcome or the one-line reminder, as client-only chat lines. */
    private void printTip(final JoinTipsState.Tip tip) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (tip == JoinTipsState.Tip.NONE || minecraft.player == null) {
            return;
        }
        final String command = settings.commandName();
        final Component key = openHomeKey.isUnbound()
                ? Component.literal("/" + command + " menu")
                : Component.empty().append(openHomeKey.getTranslatedKeyMessage()).withStyle(ChatFormatting.YELLOW);
        final Component open = Component.translatable("mineflayer_bridge.tips.open").withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(ui.chat().localClick(() -> {
                    joinTips.menuOpened();
                    ui.openPrimary(null);
                })));
        final Component prefix = Component.literal(settings.chatPrefix()).withStyle(ChatFormatting.YELLOW);
        final var chat = Game.chat();
        if (tip == JoinTipsState.Tip.WELCOME) {
            chat.addClientSystemMessage(Component.translatable("mineflayer_bridge.tips.welcome.title").withStyle(ChatFormatting.GOLD));
            chat.addClientSystemMessage(Component.translatable("mineflayer_bridge.tips.welcome.menu", key, open).withStyle(ChatFormatting.GRAY));
            chat.addClientSystemMessage(Component.translatable("mineflayer_bridge.tips.welcome.chat", prefix, command).withStyle(ChatFormatting.GRAY));
            chat.addClientSystemMessage(Component.translatable("mineflayer_bridge.tips.welcome.more").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            chat.addClientSystemMessage(Component.translatable("mineflayer_bridge.tips.reminder", key, prefix, open).withStyle(ChatFormatting.GRAY));
        }
    }

    /**
     * Integration hook: the player joined a world. Ignored if already joined to the same world.
     *
     * @param newServerKey {@code host:port} or {@code singleplayer:<save folder>}
     * @param newDimension the dimension id
     */
    public void onWorldJoin(final String newServerKey, final String newDimension) {
        if (newServerKey.equals(serverKey)) {
            if (!newDimension.equals(dimension)) {
                dimension = newDimension;
                lifecycleListener.onDimensionChange(newServerKey, newDimension);
            }
            return;
        }
        if (serverKey != null) {
            onWorldLeave();
        }
        serverKey = newServerKey;
        dimension = newDimension;
        lifecycleListener.onWorldJoin(newServerKey, newDimension);
        pendingTip = joinTips.onJoin(settings.joinTips());
        tipTicks = pendingTip == JoinTipsState.Tip.NONE ? -1 : 40;
    }

    /** Integration hook: the player left the world. Ignored when not in a world. */
    public void onWorldLeave() {
        if (serverKey == null) {
            return;
        }
        serverKey = null;
        dimension = null;
        lifecycleListener.onWorldLeave();
    }

    private void applySettings(final BridgeSettings newSettings) {
        settings = newSettings;
        renderBackend.setLabelsVisible(newSettings.showLabels());
        ui.setShowAllHud(newSettings.showAllHud());
        ui.setMessagesInChat(newSettings.messagesInChat());
        ui.sounds().setGain(newSettings.botSoundVolume() / (float) BridgeSettings.MAX_BOT_SOUND_VOLUME);
        final String problem = settingsStore.save(newSettings);
        if (problem != null) {
            notify(new Notification(Constants.MOD_NAME, I18n.get("mineflayer_bridge.settings.save_failed", problem), Notification.Severity.ERROR));
        }
        lifecycleListener.onSettingsChanged(newSettings);
    }
}
