package io.github.pix3lpirat3.mfbridge.common.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.display.Displays;
import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.common.seam.Notification;
import io.github.pix3lpirat3.mfbridge.common.sound.BotSounds;
import io.github.pix3lpirat3.mfbridge.common.spatial.SpatialTools;
import io.github.pix3lpirat3.mfbridge.common.ui.chat.ChatBridgeClient;
import io.github.pix3lpirat3.mfbridge.common.ui.command.CompletionSources;
import io.github.pix3lpirat3.mfbridge.common.ui.declared.DeclaredScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.devices.DevicesScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.devices.PermissionPrompts;
import io.github.pix3lpirat3.mfbridge.common.ui.edit.ObjectEditor;
import io.github.pix3lpirat3.mfbridge.common.ui.hud.HudRenderer;
import io.github.pix3lpirat3.mfbridge.common.ui.hud.InputOverlay;
import io.github.pix3lpirat3.mfbridge.common.ui.input.BridgeStages;
import io.github.pix3lpirat3.mfbridge.common.ui.input.Crosshair;
import io.github.pix3lpirat3.mfbridge.common.ui.input.InputPipeline;
import io.github.pix3lpirat3.mfbridge.common.ui.input.InputRouter;
import io.github.pix3lpirat3.mfbridge.common.ui.input.InterceptClient;
import io.github.pix3lpirat3.mfbridge.common.ui.input.KeybindRouter;
import io.github.pix3lpirat3.mfbridge.common.ui.input.PickController;
import io.github.pix3lpirat3.mfbridge.common.ui.input.PlayerPresence;
import io.github.pix3lpirat3.mfbridge.common.ui.input.PromptController;
import io.github.pix3lpirat3.mfbridge.common.ui.input.ToolController;
import io.github.pix3lpirat3.mfbridge.common.ui.inspector.Highlight;
import io.github.pix3lpirat3.mfbridge.common.ui.inspector.HudEditorScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.inspector.InspectorScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.DisplayText;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.PacketGuard;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.ScrollAccumulator;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.PaletteScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.surface.BotForms;
import io.github.pix3lpirat3.mfbridge.common.ui.surface.BotSurfaces;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.Notifier;
import io.github.pix3lpirat3.mfbridge.core.decl.SettingsDecl;
import io.github.pix3lpirat3.mfbridge.core.diag.Metric;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import io.github.pix3lpirat3.mfbridge.core.spatial.SpatialIndex;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * The in-game UI layer. It owns the parts and is what the rest of the mod talks to: the input
 * router and its stages, what bots put on screen ({@link BotSurfaces}), the command palette, the
 * radial menus, the bridge's keys, the messages and pending answers ({@link PlayerFeedback}), plus
 * picks, prompts, tools, bot keybinds, displays, the chat bridge, the packet guard and the bot HUD.
 * Each client tick it follows the core and lets the parts catch up.
 *
 * <p>One bot is focused (cycle key, Bridge home, clicking the bot or its
 * HUD panel title, or the bot's own request); bot-owned entries of the radial menu, palette and tool
 * wheel filter to it. One object is selected by clicking it and drawn with a thin outline; Esc
 * clears it.
 *
 * <p>Everything here and in the parts runs on the client thread.
 */
public final class BridgeUi implements UiContext {

    /** The bridge's section of the Controls screen; the loaders register it. */
    public static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(Constants.id("keys"));

    private static final int SELECTED_RGBA = 0xFFFFFFC0;
    private static final int PENDING_RGBA = 0xFFC040C0;

    private static @Nullable BridgeUi current;

    private final HudRenderer hud = new HudRenderer();
    private final InputOverlay overlay = new InputOverlay();
    private final Highlight highlight = new Highlight();
    private final List<DrawCommand> previewScratch = new ArrayList<>();
    private final PacketGuard packetGuard = new PacketGuard(Constants.MOD_ID);
    private final BotSounds sounds = new BotSounds();
    private final PlayerPresence presence = new PlayerPresence();
    private final ChatBridgeClient chat;
    private final PlayerFeedback feedback;
    private final InterceptClient intercepts;
    private final PermissionPrompts permissionPrompts;
    private final PickController picks;
    private final SpatialTools spatial;
    private final ObjectEditor objectEditor;
    private final PromptController prompts;
    private final ToolController tools;
    private final KeybindRouter keybinds;
    private final Displays displays;
    private final BotSurfaces surfaces;
    private final ActionRunner actions;
    private final CommandPalette palette;
    private final RadialMenus menus;
    private final BridgeKeys keys;
    private final BridgeStages stages;
    private final InputRouter router;

    private Supplier<@Nullable BridgeCore> coreSource = () -> null;
    private Function<@Nullable Screen, Screen> settingsFactory = parent -> {
        throw new IllegalStateException("no settings screen set");
    };
    private Function<@Nullable Screen, Screen> homeFactory = parent -> {
        throw new IllegalStateException("Bridge home is not set up");
    };
    private boolean homeRequested;
    private boolean showAllHud;
    private boolean showAllToggled;
    private @Nullable BridgeCore lastCore;
    private @Nullable Screen lastScreen;

    /**
     * Creates the UI layer and makes it the instance the input mixins call.
     *
     * @param notifier shows toasts
     */
    public BridgeUi(final Consumer<Notification> notifier) {
        this.chat = new ChatBridgeClient(this, packetGuard);
        this.feedback = new PlayerFeedback(notifier, chat, sounds, this::nowMs);
        this.intercepts = new InterceptClient(this);
        this.permissionPrompts = new PermissionPrompts(this::core, this::toastInfo);
        this.picks = new PickController(this);
        this.spatial = new SpatialTools(this, picks);
        this.objectEditor = new ObjectEditor(this, spatial.writes());
        this.prompts = new PromptController(this);
        this.tools = new ToolController(this);
        this.keybinds = new KeybindRouter(this, tools);
        this.displays = new Displays(this);
        final BotForms forms = new BotForms(this, picks);
        this.surfaces = new BotSurfaces(this, picks, prompts, displays, forms,
                (parent, decl, primary) -> new DeclaredScreen(parent, this, decl, primary), this::openHome);
        this.actions = new ActionRunner(this);
        this.palette = new CommandPalette(this, this::focusAllows, spatial, actions, forms::pickBlock, this::ownEntries);
        this.menus = new RadialMenus(this, this::focusAllows, displays, tools, spatial, actions);
        this.keys = new BridgeKeys(this, picks, spatial, tools, intercepts, palette, menus, () -> openInspector(null),
                () -> showAllToggled = !showAllToggled);
        final ScrollAccumulator scroll = new ScrollAccumulator();
        this.stages = new BridgeStages(this, picks, prompts, tools, displays, spatial, objectEditor, this::focus, keys.undo(), keys.redo(),
                scroll);
        this.router = new InputRouter(presence, surfaces, picks, keybinds, displays, spatial, objectEditor, hud, this::focus, scroll);
        stages.install(router.pipeline());
        Crosshair.bots(entity -> {
            final BridgeCore core = core();
            return core != null && entity instanceof Player player ? Crosshair.botOwner(core, player) : null;
        });
        current = this;
    }

    /**
     * Creates a keyboard key mapping of the bridge in the Miscellaneous category.
     *
     * @param name the mapping name; the translation key is {@code key.mineflayer_bridge.<name>}
     * @param keyCode the default GLFW key code, or {@code InputConstants.UNKNOWN.getValue()} for unbound
     * @return the key mapping
     */
    public static KeyMapping keyMapping(final String name, final int keyCode) {
        return new KeyMapping("key." + Constants.MOD_ID + "." + name, InputConstants.Type.KEYBOARD, keyCode, KEY_CATEGORY);
    }

    /**
     * Returns the instance the input mixins route to.
     *
     * @return the UI, or null before the mod initialised
     */
    public static @Nullable BridgeUi current() {
        return current;
    }

    /**
     * Returns a bot's display name.
     *
     * @param core the running core
     * @param owner the bot's owner key
     * @return the name, or {@code owner} when the bot is unknown
     */
    public static String botName(final BridgeCore core, final String owner) {
        final var p = core.presence().get(owner.substring(Math.min(owner.length(), DisplayText.botOwner("").length())));
        return p == null ? owner : p.displayName();
    }

    /**
     * Describes a failed invocation for the player.
     *
     * @param error the failure, possibly wrapped
     * @return a short message
     */
    public static String describe(final Throwable error) {
        final Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof BridgeException b) {
            return b.getMessage() == null ? b.code() : b.getMessage();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    // Setup and settings.

    /**
     * Sets where the running core comes from.
     *
     * @param source returns the core, or null while the endpoint is down
     */
    public void setCoreSource(final Supplier<@Nullable BridgeCore> source) {
        this.coreSource = Objects.requireNonNull(source, "source");
    }

    /**
     * Sets how Bridge home is created.
     *
     * @param factory creates the screen for a parent screen, or for null to close to the game
     */
    public void setHomeFactory(final Function<@Nullable Screen, Screen> factory) {
        this.homeFactory = Objects.requireNonNull(factory, "factory");
    }

    /**
     * Sets how the mod's settings screen is made.
     *
     * @param factory makes the screen over a parent, or over the game for null
     */
    public void setSettingsFactory(final Function<@Nullable Screen, Screen> factory) {
        this.settingsFactory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public void openHome() {
        openHome(null);
    }

    @Override
    public void openSettings() {
        Minecraft.getInstance().gui.setScreen(settingsFactory.apply(null));
    }

    /** Sets the key that opens Bridge home, so it also works inside what bots put on screen. */
    public void setHomeKey(final KeyMapping key) {
        router.setHomeKey(key);
    }

    /**
     * Applies the "Show all HUD panels" setting; the toggle key flips it for the
     * running game.
     *
     * @param showAll whether every bot's panels and boss bars render in full
     */
    public void setShowAllHud(final boolean showAll) {
        showAllHud = showAll;
        showAllToggled = false;
    }

    /** Applies the "Bot messages" setting: whether messages go to chat rather than toasts. */
    public void setMessagesInChat(final boolean inChat) {
        feedback.setMessagesInChat(inChat);
    }

    /** Returns the bridge's vanilla key mappings to register with the loader. Immutable. */
    public List<KeyMapping> keyMappings() {
        return keys.mappings();
    }

    // The parts.

    /** Returns what bots put on screen: forms, declared screens and container views. */
    public BotSurfaces surfaces() {
        return surfaces;
    }

    /** Returns the command palette, which also runs commands from screens. */
    public CommandPalette palette() {
        return palette;
    }

    /** Returns the runner of bot actions. */
    public ActionRunner actions() {
        return actions;
    }

    /** Returns the in-game input chain, where extensions add their stages. */
    public InputPipeline input() {
        return router.pipeline();
    }

    /** Returns what the player is doing, reported to bots each tick. */
    public PlayerPresence presence() {
        return presence;
    }

    /** Returns the first-party spatial tools and the gizmo editor. */
    public SpatialTools spatial() {
        return spatial;
    }

    /** Returns the client half of interaction intercepts, which the Minecraft mixin calls. */
    public InterceptClient intercepts() {
        return intercepts;
    }

    /** Returns the key that opens and closes the tool wheel. */
    public KeyMapping toolWheelKey() {
        return keys.toolWheel();
    }

    /** Returns the active pick. */
    public PickController picks() {
        return picks;
    }

    /** Returns the active prompt. */
    public PromptController prompts() {
        return prompts;
    }

    /** Returns tool mode. */
    public ToolController tools() {
        return tools;
    }

    /** Returns the displays, element interactions, world menus and client effects. */
    public Displays displays() {
        return displays;
    }

    /** Returns the client half of the chat bridge. */
    public ChatBridgeClient chat() {
        return chat;
    }

    /** Returns the packet guard the connection mixin consults. */
    public PacketGuard packetGuard() {
        return packetGuard;
    }

    // What the controllers and screens share.

    @Override
    public @Nullable BridgeCore core() {
        return coreSource.get();
    }

    @Override
    public BotSounds sounds() {
        return sounds;
    }

    @Override
    public long nowMs() {
        final BridgeCore core = core();
        return core == null ? System.nanoTime() / 1_000_000L : core.config().clock().nanoTime() / 1_000_000L;
    }

    @Override
    public void markHandled(final long id) {
        surfaces.markHandled(id);
    }

    @Override
    public void toastInfo(final String title, final String message) {
        feedback.info(title, message);
    }

    @Override
    public void toastError(final String title, final String message) {
        feedback.error(title, message);
    }

    @Override
    public void track(final String label, final String owner, final CompletableFuture<InvocationResult> result) {
        feedback.track(label, owner, result, false);
    }

    @Override
    public void trackInChat(final String label, final String owner, final CompletableFuture<InvocationResult> result) {
        feedback.track(label, owner, result, true);
    }

    @Override
    public boolean openContainerView(final String viewKey) {
        return surfaces.openContainerView(viewKey);
    }

    /** Shows a bot's notification, in chat or as a toast, and plays its sound. */
    public void notice(final Notifier.Notification n) {
        feedback.notice(n);
    }

    /** Returns the actions and commands waiting for an answer, oldest first; a live view. */
    public List<PlayerFeedback.Pending> pending() {
        return feedback.pending();
    }

    // Focus.

    /**
     * Focuses a bot and says so.
     *
     * @param owner the bot's owner, {@code bot:<identity>}
     */
    public void focus(final String owner) {
        final BridgeCore core = core();
        if (core == null) {
            return;
        }
        core.focus().focus(owner);
        if (owner.equals(core.focus().focused())) {
            toastInfo(Constants.MOD_NAME, I18n.get("mineflayer_bridge.focus.focused", botName(core, owner)));
        }
    }

    /** Bot-owned entries follow the focused bot; others always show. */
    private boolean focusAllows(final String key) {
        final BridgeCore core = core();
        final String focused = core == null ? null : core.focus().focused();
        return focused == null || !key.startsWith(DisplayText.botOwner("")) || DisplayText.ownedBy(key, focused);
    }

    // Opening screens.

    /**
     * Opens Bridge home now.
     *
     * @param parent the screen to return to, or null to close to the game
     */
    public void openHome(final @Nullable Screen parent) {
        Minecraft.getInstance().gui.setScreen(homeFactory.apply(parent));
    }

    /**
     * Opens the primary menu: the declared screen the player picked while its
     * owner still offers it, else Bridge home.
     *
     * @param parent the screen to return to, or null to close to the game
     */
    public void openPrimary(final @Nullable Screen parent) {
        surfaces.openPrimary(parent);
    }

    /**
     * Opens the primary menu on the next client tick, closing to the game. For callers that run while a
     * screen is about to close, such as a chat command: the chat screen closes itself after the
     * command ran, which would close a screen opened right away.
     */
    public void requestHome() {
        homeRequested = true;
    }

    /**
     * Opens the devices screen.
     *
     * @param parent the screen to return to, or null to close to the game
     */
    public void openDevices(final @Nullable Screen parent) {
        Minecraft.getInstance().gui.setScreen(new DevicesScreen(parent, this::core));
    }

    /**
     * Opens the inspector.
     *
     * @param parent the screen to return to, or null to close to the game
     */
    public void openInspector(final @Nullable Screen parent) {
        Minecraft.getInstance().gui.setScreen(new InspectorScreen(parent, this::core, highlight));
    }

    /**
     * Opens the HUD editor.
     *
     * @param parent the screen to return to, or null to close to the game
     */
    public void openHudEditor(final @Nullable Screen parent) {
        Minecraft.getInstance().gui.setScreen(new HudEditorScreen(parent, this::core, hud));
    }

    /**
     * Opens the context menu for something picked off the map: an entity, a point
     * or a zone. Its actions get the selected bots. Opens nothing and returns false when no action
     * applies, so the map can do its own thing with the click.
     *
     * @param entity the entity clicked on, or null
     * @param zone the zone clicked in, or null
     * @param point the point clicked on, or null
     * @param title shown in the menu's centre
     * @param bots names of the bots selected on the map
     * @param parent the map, to return to
     * @return whether a menu opened
     */
    public boolean openMapMenu(final @Nullable Entity entity, final SpatialIndex.@Nullable Item zone, final SpatialIndex.@Nullable Item point,
            final String title, final List<String> bots, final Screen parent) {
        return menus.openMapMenu(entity, zone, point, title, bots, parent);
    }

    /** The mod's own palette entries: Bridge home, devices, the inspector and the HUD editor. */
    private List<PaletteScreen.Entry> ownEntries() {
        return List.of(
                new PaletteScreen.Entry(I18n.get("mineflayer_bridge.home"), I18n.get("screen.mineflayer_bridge.palette.home.detail"),
                        Constants.MOD_NAME, () -> openHome(null)),
                new PaletteScreen.Entry(I18n.get("screen.mineflayer_bridge.palette.devices"),
                        I18n.get("screen.mineflayer_bridge.palette.devices.detail"), Constants.MOD_NAME, () -> openDevices(null)),
                new PaletteScreen.Entry(I18n.get("screen.mineflayer_bridge.palette.inspector"),
                        I18n.get("screen.mineflayer_bridge.palette.inspector.detail"), Constants.MOD_NAME, () -> openInspector(null)),
                new PaletteScreen.Entry(I18n.get("screen.mineflayer_bridge.palette.hud_editor"),
                        I18n.get("screen.mineflayer_bridge.palette.hud_editor.detail"), Constants.MOD_NAME, () -> openHudEditor(null)));
    }

    // The client tick.

    /** Client tick: follows the core and its input queue, rebuilds keybinds on change and handles the bridge's keys. */
    public void tick() {
        if (homeRequested) {
            homeRequested = false;
            surfaces.openPrimary(null);
        }
        final BridgeCore core = core();
        if (core != lastCore) {
            lastCore = core;
            hud.invalidate();
            surfaces.reset();
            feedback.clear();
            chat.coreChanged(core);
            spatial.select(null);
            objectEditor.cancel();
            stages.clearSelection();
            highlight.clear();
            if (core != null) {
                core.commands().setContext(Crosshair.argContext());
                CompletionSources.register(core, () -> core.renderSnapshot().entries());
            }
            displays.coreChanged(core);
        }
        if (core == null) {
            keys.drain();
            return;
        }
        final Screen screen = Minecraft.getInstance().gui.screen();
        if (screen != lastScreen) {
            // What bots offer goes stale (items dropped, blocks broken): a closed chat asks again.
            if (lastScreen != null) {
                core.commands().forgetCompletions();
            }
            lastScreen = screen;
        }
        keybinds.sync(core.declarations());
        surfaces.sync(core);
        prompts.tick();
        surfaces.openRequested(core);
        permissionPrompts.tick();
        picks.tick();
        displays.tick();
        spatial.tick();
        keys.handle();
    }

    // Raw input, called by the mixins before vanilla. Each returns whether vanilla must not see the event.

    /** Returns whether a pick, world menu, hovered element or tool owns the mouse, so vanilla attack, use and pick-block must not run. */
    public boolean capturesGameInput() {
        return stages.capturesGameInput();
    }

    /** Handles a raw keyboard event; see {@link InputRouter#onKey}. */
    public boolean onKey(final int action, final KeyEvent event) {
        return router.onKey(action, event);
    }

    /** Handles a raw mouse button event; see {@link InputRouter#onMouseButton}. */
    public boolean onMouseButton(final MouseButtonInfo info, final int action) {
        return router.onMouseButton(info, action);
    }

    /** Handles a raw scroll event; see {@link InputRouter#onScroll}. */
    public boolean onScroll(final double amount) {
        return router.onScroll(amount);
    }

    /** Runs right after vanilla's crosshair pick; see {@link Displays#afterPick}. */
    public void afterPick() {
        displays.afterPick();
    }

    // Drawing.

    /** Returns the draw commands of the visible zones, points and routes, except the one being edited; cached. */
    public List<DrawCommand> spatialCommands() {
        return spatial.staticCommands();
    }

    /** Returns the inspector highlight's draw commands for this frame; empty while nothing is highlighted. Immutable. */
    public List<DrawCommand> highlightCommands() {
        return highlight.commands(System.currentTimeMillis());
    }

    /**
     * Returns the pick preview geometry for this frame.
     *
     * @return draw commands to add to the frame, valid until the next call
     */
    public List<DrawCommand> previewCommands() {
        final List<DrawCommand> pick = picks.active() ? picks.preview() : List.of();
        previewScratch.clear();
        spatial.preview(previewScratch);
        objectEditor.preview(previewScratch);
        final String sel = stages.selected();
        displays.outlines(previewScratch, sel, sel != null && spatial.writes().waiting(sel) ? PENDING_RGBA : SELECTED_RGBA);
        if (previewScratch.isEmpty()) {
            return pick;
        }
        previewScratch.addAll(pick);
        return previewScratch;
    }

    /**
     * Draws the bot HUD and the input overlays.
     *
     * @param graphics the frame's GUI extractor
     */
    public void renderHud(final GuiGraphicsExtractor graphics) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.hud.isHidden()) {
            return;
        }
        final BridgeCore core = core();
        if (core != null) {
            final long start = System.nanoTime();
            hud.render(graphics, core.declarations(), core.hudLayouts(), core.variables(), core.focus().focused(),
                    showAllHud != showAllToggled);
            core.inspector().metrics().record(Metric.DRAW_HUD, System.nanoTime() - start);
        }
        if (minecraft.gui.screen() == null) {
            overlay.render(graphics, this, core == null ? 0 : core.input().queued());
            displays.renderHud(graphics);
            final String hint = picks.active() ? "" : objectEditor.moving() ? objectEditor.hint() : spatial.hint();
            if (!hint.isEmpty()) {
                overlay.spatialHint(graphics, hint);
            }
        }
    }

    /**
     * Draws the item pick hint over a container screen.
     *
     * @param graphics the frame's GUI extractor
     * @param screen the open screen
     */
    public void renderScreenOverlay(final GuiGraphicsExtractor graphics, final Screen screen) {
        if (picks.kind() == PickKind.ITEM && screen instanceof AbstractContainerScreen<?>) {
            overlay.renderItemPick(graphics, this);
        }
    }

    // Bot settings, for the settings screen.

    /** Returns the bot settings groups with their current values. */
    public List<SettingsDecl> botSettings() {
        return declarations().settings();
    }

    /**
     * Sets a bot setting.
     *
     * @param settingsKey the group's object key
     * @param key the setting key
     * @param value the new value
     * @throws IllegalArgumentException with a message for the player when the value is rejected
     * @throws IllegalStateException when the bridge is not running
     */
    public void setBotSetting(final String settingsKey, final String key, final Object value) {
        final BridgeCore core = core();
        if (core == null) {
            throw new IllegalStateException("The bridge is not running");
        }
        core.setSettingValue(settingsKey, key, value);
    }
}
