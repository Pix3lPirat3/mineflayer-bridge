package io.github.pix3lpirat3.mfbridge.common.ui.input;

import io.github.pix3lpirat3.mfbridge.api.client.InputStage;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.pix3lpirat3.mfbridge.common.display.Displays;
import io.github.pix3lpirat3.mfbridge.common.spatial.SpatialTools;
import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.common.ui.edit.ObjectEditor;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.ScrollAccumulator;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.protocol.core.ElementInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.ToolInput;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * The bridge's own stages of the in-game input chain, in this order: the active pick, world menu or
 * prompt, selection and Shift+click editing of objects with traits, gizmo handles, interactive
 * elements under the crosshair, spatial tools, tool mode.
 *
 * <p>Also holds which object is selected, since only a click or Esc changes it. Client thread only.
 */
public final class BridgeStages {

    private final UiContext ui;
    private final PickController picks;
    private final PromptController prompts;
    private final ToolController tools;
    private final Displays displays;
    private final SpatialTools spatial;
    private final ObjectEditor objectEditor;
    private final Consumer<String> focus;
    private final KeyMapping undoKey;
    private final KeyMapping redoKey;
    private final ScrollAccumulator scroll;
    private @Nullable String selected;

    /**
     * Creates the stages.
     *
     * @param ui the core
     * @param picks the active pick
     * @param prompts the active prompt
     * @param tools tool mode
     * @param displays world menus and interactive elements
     * @param spatial the zone tools and the gizmo editor
     * @param objectEditor the editor of objects with traits
     * @param focus focuses a bot the player clicked
     * @param undoKey Ctrl plus this key undoes a zone edit
     * @param redoKey Ctrl plus this key redoes one
     * @param scroll turns scroll amounts into whole steps; shared with the router
     */
    public BridgeStages(final UiContext ui, final PickController picks, final PromptController prompts, final ToolController tools,
            final Displays displays, final SpatialTools spatial, final ObjectEditor objectEditor, final Consumer<String> focus,
            final KeyMapping undoKey, final KeyMapping redoKey, final ScrollAccumulator scroll) {
        this.ui = ui;
        this.picks = picks;
        this.prompts = prompts;
        this.tools = tools;
        this.displays = displays;
        this.spatial = spatial;
        this.objectEditor = objectEditor;
        this.focus = focus;
        this.undoKey = undoKey;
        this.redoKey = redoKey;
        this.scroll = scroll;
    }

    /**
     * Returns the selected object.
     *
     * @return its key, or null
     */
    public @Nullable String selected() {
        return selected;
    }

    /** Clears the selection. */
    public void clearSelection() {
        selected = null;
    }

    /**
     * Returns whether vanilla attack, use and pick-block must not run this tick: a pick, world menu,
     * hovered interactive element or tool owns the mouse.
     *
     * @return true while one of them is active and no screen is open
     */
    public boolean capturesGameInput() {
        final Minecraft minecraft = Minecraft.getInstance();
        return Game.screen() == null && minecraft.player != null
                && (picks.active() || tools.active() != null || displays.menus().active() || displays.hovering()
                        || spatial.capturesClicks() || objectEditor.moving());
    }

    /**
     * Adds the stages to the chain, in pipeline order; extensions add theirs between.
     *
     * @param input the chain
     */
    public void install(final InputPipeline input) {
        input.add(InputPipeline.REQUEST, new InputStage() {
            @Override
            public Outcome key(final KeyEvent event, final String name) {
                if (picks.active() || displays.menus().active()) {
                    final boolean used = picks.active() ? picks.keyPressed(event) : displays.menus().keyPressed(event);
                    final ToolInput role = used ? null : role(event);
                    if (role != null) {
                        requestClick(role);
                    }
                    if (used || role != null) {
                        return Outcome.CONSUMED;
                    }
                    return !picks.active() && prompts.active() != null && prompts.keyPressed(event) ? Outcome.CONSUMED : Outcome.OWNED;
                }
                return consumedIf(prompts.active() != null && prompts.keyPressed(event));
            }

            @Override
            public Outcome button(final InputConstants.Key key, final String name) {
                if (!picks.active() && !displays.menus().active()) {
                    return Outcome.PASS;
                }
                final ToolInput role = role(key);
                if (role == null) {
                    return Outcome.OWNED;
                }
                requestClick(role);
                return Outcome.CONSUMED;
            }

            @Override
            public Outcome scroll(final double amount) {
                if (picks.active()) {
                    picks.scroll(scroll.add(amount));
                    return Outcome.CONSUMED;
                }
                if (displays.menus().active()) {
                    displays.menus().scroll(scroll.add(amount));
                    return Outcome.CONSUMED;
                }
                return Outcome.PASS;
            }
        });
        input.add(InputPipeline.SELECTION, new InputStage() {
            @Override
            public Outcome key(final KeyEvent event, final String name) {
                // Esc puts the move gizmo away, then clears the selection.
                if (!event.isEscape() || !objectEditor.moving() && selected == null) {
                    return Outcome.PASS;
                }
                if (objectEditor.moving()) {
                    objectEditor.cancel();
                } else {
                    selected = null;
                }
                return Outcome.CONSUMED;
            }

            @Override
            public Outcome button(final InputConstants.Key key, final String name) {
                return consumedIf(Game.matches(Minecraft.getInstance().options.keyAttack, key) && traitsClick());
            }

            @Override
            public Outcome scroll(final double amount) {
                return consumedIf(objectEditor.moving() && objectEditor.scroll(scroll.add(amount)));
            }
        });
        input.add(InputPipeline.GIZMO, new InputStage() {
            @Override
            public Outcome button(final InputConstants.Key key, final String name) {
                return consumedIf(spatial.editor().hasSelection() && Game.matches(Minecraft.getInstance().options.keyAttack, key)
                        && spatial.editor().press());
            }

            @Override
            public Outcome scroll(final double amount) {
                if (!spatial.editor().dragging()) {
                    return Outcome.PASS;
                }
                spatial.editor().scroll(scroll.add(amount));
                return Outcome.CONSUMED;
            }
        });
        input.add(InputPipeline.ELEMENTS, new InputStage() {
            @Override
            public Outcome key(final KeyEvent event, final String name) {
                if (!displays.hovering()) {
                    return Outcome.PASS;
                }
                if (displays.key(name)) {
                    return Outcome.CONSUMED;
                }
                final ToolInput role = role(event);
                if (role == null) {
                    return Outcome.PASS;
                }
                displays.press(elementInput(role), name);
                return Outcome.CONSUMED;
            }

            @Override
            public Outcome button(final InputConstants.Key key, final String name) {
                final ToolInput role = displays.hovering() ? role(key) : null;
                if (role == null) {
                    return Outcome.PASS;
                }
                displays.press(elementInput(role), name);
                return Outcome.CONSUMED;
            }

            @Override
            public Outcome scroll(final double amount) {
                if (!displays.takesScroll()) {
                    return Outcome.PASS;
                }
                displays.scroll(scroll.add(amount));
                return Outcome.CONSUMED;
            }
        });
        input.add(InputPipeline.SPATIAL, new InputStage() {
            @Override
            public Outcome key(final KeyEvent event, final String name) {
                return consumedIf(spatialKey(event));
            }

            @Override
            public Outcome button(final InputConstants.Key key, final String name) {
                final ToolInput role = spatial.capturesClicks() || tools.active() != null ? role(key) : null;
                return consumedIf(role != null && spatialClick(role));
            }
        });
        input.add(InputPipeline.TOOLS, new InputStage() {
            @Override
            public Outcome key(final KeyEvent event, final String name) {
                if (tools.active() == null) {
                    return Outcome.PASS;
                }
                if (event.isEscape()) {
                    tools.select(null);
                    return Outcome.CONSUMED;
                }
                final ToolInput role = role(event);
                if (role == null) {
                    return Outcome.PASS;
                }
                tools.fire(role, null);
                return Outcome.CONSUMED;
            }

            @Override
            public Outcome button(final InputConstants.Key key, final String name) {
                final ToolInput role = tools.active() != null ? role(key) : null;
                if (role == null) {
                    return Outcome.PASS;
                }
                tools.fire(role, null);
                return Outcome.CONSUMED;
            }

            @Override
            public Outcome scroll(final double amount) {
                if (!tools.declares(ToolInput.SCROLL)) {
                    return Outcome.PASS;
                }
                tools.scroll(scroll.add(amount));
                return Outcome.CONSUMED;
            }
        });
    }

    /** Gizmo drags start only on a hit against our geometry, then the spatial tool. */
    private boolean spatialClick(final ToolInput role) {
        return switch (role) {
            case PRIMARY -> spatial.primary() || spatial.active() != null;
            case SECONDARY -> spatial.secondary();
            case MIDDLE, SCROLL, KEY -> spatial.active() != null;
        };
    }

    /** Undo and redo, the editor's keys, the ruler, and Esc to leave a spatial tool. */
    private boolean spatialKey(final KeyEvent event) {
        if (!spatial.undoContext()) {
            return false;
        }
        if (Minecraft.getInstance().hasControlDown() && (undoKey.matches(event) || redoKey.matches(event))) {
            spatial.undo(undoKey.matches(event));
            return true;
        }
        if (spatial.keyPressed(event.input())) {
            return true;
        }
        if (spatial.active() != null) {
            if (event.isEscape()) {
                spatial.select(null);
                return true;
            }
            final ToolInput role = role(event);
            return role != null && spatialClick(role);
        }
        return false;
    }

    /** The vanilla role of a key: attack, use or pick-block, or null. */
    private static @Nullable ToolInput role(final KeyEvent event) {
        final Options options = Minecraft.getInstance().options;
        return options.keyAttack.matches(event) ? ToolInput.PRIMARY
                : options.keyUse.matches(event) ? ToolInput.SECONDARY
                : options.keyPickItem.matches(event) ? ToolInput.MIDDLE : null;
    }

    /** The vanilla role of a mouse button: attack, use or pick-block, or null. */
    private static @Nullable ToolInput role(final InputConstants.Key key) {
        final Options options = Minecraft.getInstance().options;
        return Game.matches(options.keyAttack, key) ? ToolInput.PRIMARY
                : Game.matches(options.keyUse, key) ? ToolInput.SECONDARY
                : Game.matches(options.keyPickItem, key) ? ToolInput.MIDDLE : null;
    }

    private static InputStage.Outcome consumedIf(final boolean taken) {
        return taken ? InputStage.Outcome.CONSUMED : InputStage.Outcome.PASS;
    }

    /** Clicks of the active pick or world menu. */
    private void requestClick(final ToolInput role) {
        if (picks.active()) {
            switch (role) {
                case PRIMARY -> picks.primary();
                case SECONDARY -> picks.secondary();
                case MIDDLE, SCROLL, KEY -> {
                }
            }
        } else if (role == ToolInput.PRIMARY || role == ToolInput.SECONDARY) {
            displays.menus().click();
        }
    }

    private static ElementInput elementInput(final ToolInput role) {
        return switch (role) {
            case PRIMARY -> ElementInput.PRIMARY;
            case SECONDARY -> ElementInput.SECONDARY;
            case MIDDLE, SCROLL, KEY -> ElementInput.MIDDLE;
        };
    }

    /**
     * The move gizmo, Shift+click editing, selecting
     * a clicked object and focusing a clicked bot.
     *
     * @return whether the primary press was consumed; a consumed press never reaches vanilla
     */
    private boolean traitsClick() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (objectEditor.moving()) {
            return objectEditor.press();
        }
        final String target = displays.targetKey();
        if (target != null && minecraft.hasShiftDown() && objectEditor.editable(target)) {
            selected = target;
            return objectEditor.open(target);
        }
        if (target != null && (displays.hovering() || objectEditor.editable(target))) {
            selected = target;
        }
        final BridgeCore core = ui.core();
        final Entity entity = minecraft.crosshairPickEntity;
        if (core != null && entity instanceof Player player && target == null) {
            final String owner = Crosshair.botOwner(core, player);
            if (owner != null && !owner.equals(core.focus().focused())) {
                focus.accept(owner);
                return true;
            }
        }
        return false;
    }
}
