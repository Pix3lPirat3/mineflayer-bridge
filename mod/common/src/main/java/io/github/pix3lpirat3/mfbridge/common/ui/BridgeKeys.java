package io.github.pix3lpirat3.mfbridge.common.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.spatial.SpatialTools;
import io.github.pix3lpirat3.mfbridge.common.ui.input.InterceptClient;
import io.github.pix3lpirat3.mfbridge.common.ui.input.PickController;
import io.github.pix3lpirat3.mfbridge.common.ui.input.ToolController;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.RadialMenuScreen;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

/**
 * The bridge's own vanilla key mappings and what each does on the client tick: the palette, the
 * context menu, the tool wheel, the intercept pause, the inspector, the focus cycle and the HUD
 * toggle. Undo and redo are only declared here; the input stages run them. Client thread only.
 */
public final class BridgeKeys {

    private final KeyMapping paletteKey = BridgeUi.keyMapping("palette", InputConstants.KEY_K);
    private final KeyMapping contextKey = BridgeUi.keyMapping("context_menu", InputConstants.KEY_R);
    private final KeyMapping toolWheelKey = BridgeUi.keyMapping("tool_wheel", InputConstants.KEY_V);
    // One key pauses every rule. Unbound so it never steals a key.
    private final KeyMapping pauseInterceptsKey = BridgeUi.keyMapping("pause_intercepts", InputConstants.UNKNOWN.getValue());
    // Undo and redo are Ctrl plus these keys while a zone tool or the editor is active.
    private final KeyMapping undoKey = BridgeUi.keyMapping("spatial_undo", InputConstants.KEY_Z);
    private final KeyMapping redoKey = BridgeUi.keyMapping("spatial_redo", InputConstants.KEY_Y);
    // A developer tool, so unbound by default; the palette opens it too.
    private final KeyMapping inspectorKey = BridgeUi.keyMapping("inspector", InputConstants.UNKNOWN.getValue());
    // Cycling the selected bot is one of the fixed vanilla key mappings.
    private final KeyMapping cycleFocusKey = BridgeUi.keyMapping("cycle_focus", InputConstants.KEY_COMMA);
    private final KeyMapping toggleHudKey = BridgeUi.keyMapping("toggle_hud", InputConstants.UNKNOWN.getValue());
    private final List<KeyMapping> keyMappings = List.of(paletteKey, contextKey, toolWheelKey, pauseInterceptsKey, undoKey, redoKey,
            inspectorKey, cycleFocusKey, toggleHudKey);

    private final UiContext ui;
    private final PickController picks;
    private final SpatialTools spatial;
    private final ToolController tools;
    private final InterceptClient intercepts;
    private final CommandPalette palette;
    private final RadialMenus menus;
    private final Runnable openInspector;
    private final Runnable toggleHud;

    /**
     * Creates the key mappings.
     *
     * @param ui the core and toasts
     * @param picks the active pick; the keys wait while one runs
     * @param spatial the zone tools, which the wheel key puts away
     * @param tools tool mode, which the wheel key leaves
     * @param intercepts paused and resumed by the pause key
     * @param palette opened by the palette key
     * @param menus the context menu and the tool wheel
     * @param openInspector opens the inspector
     * @param toggleHud flips "Show all HUD panels" for the running game
     */
    public BridgeKeys(final UiContext ui, final PickController picks, final SpatialTools spatial, final ToolController tools,
            final InterceptClient intercepts, final CommandPalette palette, final RadialMenus menus, final Runnable openInspector,
            final Runnable toggleHud) {
        this.ui = ui;
        this.picks = picks;
        this.spatial = spatial;
        this.tools = tools;
        this.intercepts = intercepts;
        this.palette = palette;
        this.menus = menus;
        this.openInspector = openInspector;
        this.toggleHud = toggleHud;
    }

    /**
     * Returns the key mappings to register with the loader: palette, context menu, tool wheel,
     * the intercept pause key, the spatial undo and redo keys, the inspector key, the focus cycle key
     * and the HUD toggle.
     *
     * @return the key mappings. Immutable.
     */
    public List<KeyMapping> mappings() {
        return keyMappings;
    }

    /** Returns the key that opens and closes the tool wheel. */
    public KeyMapping toolWheel() {
        return toolWheelKey;
    }

    /** Returns the key that, with Ctrl, undoes a zone edit. */
    public KeyMapping undo() {
        return undoKey;
    }

    /** Returns the key that, with Ctrl, redoes a zone edit. */
    public KeyMapping redo() {
        return redoKey;
    }

    /** Client tick while the core is down: throws the queued clicks away, and says so for the palette key. */
    public void drain() {
        if (clicked(paletteKey)) {
            ui.toastError(Constants.MOD_NAME, I18n.get("mineflayer_bridge.endpoint.not_running.message"));
        }
        for (int i = 0; i < keyMappings.size(); i++) {
            clicked(keyMappings.get(i));
        }
    }

    private static boolean clicked(final KeyMapping key) {
        boolean any = false;
        while (key.consumeClick()) {
            any = true;
        }
        return any;
    }

    /** Client tick while the core runs: acts on the keys pressed since the last tick. */
    public void handle() {
        final Minecraft minecraft = Minecraft.getInstance();
        final boolean free = minecraft.gui.screen() == null && !picks.active() && minecraft.player != null;
        final boolean palette = clicked(paletteKey);
        // A menu chosen from by clicking while its key is held: the key's repeats must not reopen it.
        // Checked every tick, so the hold ends as soon as the key is let go.
        final boolean contextHeld = RadialMenuScreen.heldSinceChoice(contextKey);
        final boolean wheelHeld = RadialMenuScreen.heldSinceChoice(toolWheelKey);
        final boolean context = clicked(contextKey) && !contextHeld;
        final boolean wheel = clicked(toolWheelKey) && !wheelHeld;
        final boolean inspector = clicked(inspectorKey);
        final boolean cycle = clicked(cycleFocusKey);
        if (clicked(toggleHudKey)) {
            toggleHud.run();
        }
        if (clicked(pauseInterceptsKey)) {
            intercepts.togglePause();
        }
        // Undo and redo run from the input stages, where Ctrl is checked; the queued clicks only need draining.
        clicked(undoKey);
        clicked(redoKey);
        if (!free) {
            return;
        }
        final BridgeCore core = ui.core();
        if (cycle && core != null) {
            core.focus().cycle(1);
            final String focused = core.focus().focused();
            if (focused != null) {
                ui.toastInfo(Constants.MOD_NAME, I18n.get("mineflayer_bridge.focus.focused", BridgeUi.botName(core, focused)));
            }
        }
        if (palette) {
            this.palette.open();
        } else if (inspector) {
            openInspector.run();
        } else if (context) {
            menus.openContextMenu(contextKey);
        } else if (wheel) {
            if (spatial.active() != null) {
                spatial.select(null);
            } else if (tools.active() != null) {
                tools.select(null);
            } else {
                menus.openToolWheel(toolWheelKey);
            }
        }
    }
}
