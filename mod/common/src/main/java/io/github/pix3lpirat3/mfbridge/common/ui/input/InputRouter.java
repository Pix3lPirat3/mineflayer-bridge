package io.github.pix3lpirat3.mfbridge.common.ui.input;

import io.github.pix3lpirat3.mfbridge.api.client.InputStage;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.pix3lpirat3.mfbridge.common.display.Displays;
import io.github.pix3lpirat3.mfbridge.common.mixin.AbstractContainerScreenAccessor;
import io.github.pix3lpirat3.mfbridge.common.spatial.SpatialTools;
import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.common.ui.edit.ObjectEditor;
import io.github.pix3lpirat3.mfbridge.common.ui.hud.HudRenderer;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.DisplayText;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.ScrollAccumulator;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.BridgeScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.surface.BotSurfaces;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickKind;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;

/**
 * Input arbitration: takes raw keyboard, mouse and scroll events from the input mixins before
 * vanilla handles them. The home key and the item pick are dealt with inside screens; in game an
 * event runs through the {@link InputPipeline}, then bot keybinds, then vanilla. A consumed press
 * also consumes its repeats and its release. Client thread only.
 */
public final class InputRouter {

    private final PlayerPresence presence;
    private final BotSurfaces surfaces;
    private final PickController picks;
    private final KeybindRouter keybinds;
    private final Displays displays;
    private final SpatialTools spatial;
    private final ObjectEditor objectEditor;
    private final HudRenderer hud;
    private final Consumer<String> focus;
    private final ScrollAccumulator scroll;
    private final InputPipeline input = new InputPipeline();
    private final Set<String> consumed = new HashSet<>();
    private @Nullable KeyMapping homeKey;

    /**
     * Creates the router with an empty chain.
     *
     * @param presence hears of every input event
     * @param surfaces what bots put on screen, which the home key leaves
     * @param picks the active pick
     * @param keybinds bot keybinds, which come after the chain
     * @param displays hears of released buttons
     * @param spatial the zone tools and the gizmo editor, which hear of released buttons
     * @param objectEditor the editor of objects with traits, which hears of released buttons
     * @param hud knows the bot whose panel title is under the cursor
     * @param focus focuses a bot whose panel title the player clicked
     * @param scroll turns scroll amounts into whole steps; shared with the stages
     */
    public InputRouter(final PlayerPresence presence, final BotSurfaces surfaces, final PickController picks, final KeybindRouter keybinds,
            final Displays displays, final SpatialTools spatial, final ObjectEditor objectEditor, final HudRenderer hud,
            final Consumer<String> focus, final ScrollAccumulator scroll) {
        this.presence = presence;
        this.surfaces = surfaces;
        this.picks = picks;
        this.keybinds = keybinds;
        this.displays = displays;
        this.spatial = spatial;
        this.objectEditor = objectEditor;
        this.hud = hud;
        this.focus = focus;
        this.scroll = scroll;
    }

    /**
     * Returns the in-game input chain, where the bridge and extensions add their stages.
     *
     * @return the chain
     */
    public InputPipeline pipeline() {
        return input;
    }

    /**
     * Sets the key that opens Bridge home, so it also works inside what bots put on screen.
     *
     * @param key the home key
     */
    public void setHomeKey(final KeyMapping key) {
        this.homeKey = Objects.requireNonNull(key, "key");
    }

    /**
     * Handles a raw keyboard event before vanilla.
     *
     * @param action {@link InputConstants#PRESS}, {@link InputConstants#RELEASE} or a repeat
     * @param event the key
     * @return whether vanilla must not see the event
     */
    public boolean onKey(final int action, final KeyEvent event) {
        presence.input();
        final Minecraft minecraft = Minecraft.getInstance();
        final String name = InputConstants.getKey(event).getName();
        if (action != InputConstants.PRESS) {
            if (consumed.contains(name)) {
                if (action == InputConstants.RELEASE) {
                    consumed.remove(name);
                    spatial.editor().release();
                }
                return true;
            }
            return keybinds.onKey(name, action);
        }
        final Screen screen = Game.screen();
        final KeyMapping home = homeKey;
        if (home != null && home.matches(event) && surfaces.leaveForHome(screen)) {
            consumed.add(name);
            return true;
        }
        if (screen instanceof BridgeScreen) {
            return false;
        }
        if (screen != null) {
            if (picks.kind() == PickKind.ITEM && screen instanceof AbstractContainerScreen<?>) {
                if (event.isEscape()) {
                    picks.cancel();
                    Game.setScreen(null);
                    consumed.add(name);
                    return true;
                }
                if (event.isConfirmation()) {
                    picks.keyPressed(event);
                    if (!picks.active()) {
                        Game.setScreen(null);
                    }
                    consumed.add(name);
                    return true;
                }
            }
            if (screen instanceof KeyBindsScreen || screen.getFocused() instanceof EditBox box && box.canConsumeInput()) {
                return false;
            }
            return keybinds.onKey(name, action);
        }
        if (minecraft.player == null || Game.overlay() != null) {
            return false;
        }
        if (input.key(event, name) == InputStage.Outcome.CONSUMED) {
            consumed.add(name);
            return true;
        }
        return keybinds.onKey(name, action);
    }

    /**
     * Handles a raw mouse button event before vanilla.
     *
     * @param info the button
     * @param action {@link InputConstants#PRESS} or {@link InputConstants#RELEASE}
     * @return whether vanilla must not see the event
     */
    public boolean onMouseButton(final MouseButtonInfo info, final int action) {
        presence.input();
        final Minecraft minecraft = Minecraft.getInstance();
        final InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(info.button());
        final String name = key.getName();
        if (action != InputConstants.PRESS) {
            if (consumed.remove(name)) {
                displays.release(name);
                spatial.editor().release();
                objectEditor.release();
                return true;
            }
            return keybinds.onKey(name, action);
        }
        final Screen screen = Game.screen();
        if (screen != null) {
            if (picks.kind() == PickKind.ITEM && screen instanceof AbstractContainerScreen<?> container) {
                // The item pick answers from the inventory screen and never clicks the real container.
                final Slot slot = ((AbstractContainerScreenAccessor) container).mfbridge$hoveredSlot();
                if (slot != null && slot.hasItem()) {
                    picks.pickStack(slot.getItem().copy());
                    Game.setScreen(null);
                }
                consumed.add(name);
                return true;
            }
            if (screen instanceof ChatScreen && Game.matches(minecraft.options.keyAttack, key)) {
                // With the cursor free, a click on a panel title focuses its bot.
                final String owner = hud.ownerAt(minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()),
                        minecraft.mouseHandler.getScaledYPos(minecraft.getWindow()));
                if (owner != null && owner.startsWith(DisplayText.botOwner(""))) {
                    focus.accept(owner);
                    consumed.add(name);
                    return true;
                }
            }
            return false;
        }
        if (minecraft.player == null || Game.overlay() != null || !minecraft.mouseHandler.isMouseGrabbed()) {
            return false;
        }
        if (input.button(key, name) == InputStage.Outcome.CONSUMED) {
            consumed.add(name);
            return true;
        }
        if (keybinds.onKey(name, action)) {
            consumed.add(name);
            return true;
        }
        return false;
    }

    /**
     * Handles a raw scroll event before vanilla.
     *
     * @param amount vertical scroll, positive up
     * @return whether vanilla must not see the event
     */
    public boolean onScroll(final double amount) {
        presence.input();
        if (!UiContext.inPlay()) {
            return false;
        }
        if (input.scroll(amount) == InputStage.Outcome.CONSUMED) {
            return true;
        }
        scroll.reset();
        return false;
    }
}
