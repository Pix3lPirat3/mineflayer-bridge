package io.github.pix3lpirat3.mfbridge.common.ui.input;

import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.common.ui.container.SlotItems;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.input.PickInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickResult;
import io.github.pix3lpirat3.mfbridge.common.version.Keys;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Runs the active pick: one bot pick from the input queue, or a local pick started by
 * a form's "Pick in world" button. While a pick is active it owns clicks, scroll, Enter, Backspace
 * and Esc in the world; none of them reach vanilla. Client thread only.
 */
public final class PickController {

    /** Ray length for point, block, region, polygon and path picks without {@code maxDistance}, in blocks. */
    public static final double DEFAULT_RANGE = 128d;

    /** Range for entity picks without {@code maxDistance}, in blocks. */
    public static final double DEFAULT_ENTITY_RANGE = 32d;

    /** How long the hint stays red after a rejected confirm, in milliseconds. */
    private static final long REJECT_FLASH_MS = 600L;

    private final UiContext ui;
    private final List<DrawCommand> preview = new ArrayList<>();
    private @Nullable PickMode mode;
    private @Nullable PickKind kind;
    private @Nullable PickInput input;
    private @Nullable Consumer<@Nullable PickResult> local;
    private String owner = "";
    private String prompt = "";
    private long rejectedAtMs = Long.MIN_VALUE;

    /**
     * Creates the controller.
     *
     * @param ui the UI services
     */
    public PickController(final UiContext ui) {
        this.ui = ui;
    }

    /**
     * Returns whether a pick is active.
     *
     * @return true while a bot or local pick runs
     */
    public boolean active() {
        return mode != null;
    }

    /**
     * Returns the kind of the active pick.
     *
     * @return the kind, or null when none is active
     */
    public @Nullable PickKind kind() {
        return kind;
    }

    /**
     * Returns whether the active pick is a local one, started by a form.
     *
     * @return true for a local pick
     */
    public boolean isLocal() {
        return local != null;
    }

    /**
     * Starts a bot pick, replacing any active pick without answering it. An item pick opens the
     * inventory screen when no screen is open.
     *
     * @param request the pick from the input queue
     */
    public void begin(final PickInput request) {
        endLocal();
        final PickRequest r = request.request();
        input = request;
        local = null;
        owner = request.ownerName();
        prompt = r.prompt() == null ? defaultPrompt(r.kind()) : r.prompt();
        start(r.kind(), r);
    }

    /**
     * Starts a local block pick for a form field; it does not touch the input queue.
     *
     * @param title text for the hint
     * @param callback receives the result, or null when the player cancels
     */
    public void beginLocal(final String title, final Consumer<@Nullable PickResult> callback) {
        input = null;
        local = callback;
        owner = I18n.get("mineflayer_bridge.pick.form_owner");
        prompt = title;
        start(PickKind.BLOCK, null);
    }

    /**
     * Starts a local pick of any kind for a first-party tool; it does not touch the input queue.
     *
     * @param owner name shown with the hint, for example the tool's name
     * @param title text for the hint
     * @param kind the pick kind
     * @param callback receives the result, or null when the player cancels or a bot pick replaces it
     */
    public void beginLocal(final String owner, final String title, final PickKind kind,
            final Consumer<@Nullable PickResult> callback) {
        endLocal();
        input = null;
        local = callback;
        this.owner = owner;
        prompt = title;
        start(kind, null);
    }

    private void start(final PickKind k, final @Nullable PickRequest r) {
        kind = k;
        final double range = r != null && r.maxDistance() != null ? r.maxDistance() : DEFAULT_RANGE;
        mode = switch (k) {
            case POINT -> new PickModes.Point(range);
            case BLOCK -> new PickModes.Block(range, r == null ? null : r.filter());
            case REGION -> new PickModes.Region(range, r == null ? null : r.maxVolume());
            case POLYGON -> new PickModes.Polygon(range);
            case ENTITY -> new PickModes.EntityPick(r != null && r.maxDistance() != null ? r.maxDistance() : DEFAULT_ENTITY_RANGE,
                    r == null ? null : r.filter());
            case PATH -> new PickModes.Path(range);
            case DIRECTION -> new PickModes.DirectionPick();
            case ITEM -> new PickModes.Item();
        };
        rejectedAtMs = Long.MIN_VALUE;
        final Minecraft minecraft = Minecraft.getInstance();
        if (k == PickKind.ITEM && Game.screen() == null && minecraft.player != null) {
            Game.setScreen(new InventoryScreen(minecraft.player));
        }
    }

    /** Drops the active pick without answering it, for example when it timed out or was cancelled by the bot. */
    public void end() {
        endLocal();
        clear();
    }

    private void endLocal() {
        final Consumer<@Nullable PickResult> callback = local;
        local = null;
        if (callback != null) {
            clear();
            callback.accept(null);
        }
    }

    private void clear() {
        mode = null;
        kind = null;
        input = null;
        local = null;
        preview.clear();
    }

    /** Ticks slower state such as entity candidates. */
    public void tick() {
        final PickMode m = mode;
        if (m != null) {
            m.tick();
        }
    }

    /** The player clicked the attack button. */
    public void primary() {
        final PickMode m = mode;
        if (m == null) {
            return;
        }
        m.update();
        if (m.click()) {
            confirm();
        }
    }

    /** The player clicked the use button: undo the last step. */
    public void secondary() {
        final PickMode m = mode;
        if (m != null) {
            m.undo();
        }
    }

    /**
     * The player scrolled.
     *
     * @param steps whole steps, positive up
     */
    public void scroll(final int steps) {
        final PickMode m = mode;
        if (m != null && steps != 0) {
            m.scroll(steps);
        }
    }

    /**
     * Handles a key press in the world: Enter confirms, Esc cancels, Backspace undoes.
     *
     * @param event the key
     * @return whether the key was used
     */
    public boolean keyPressed(final KeyEvent event) {
        if (mode == null) {
            return false;
        }
        if (event.isEscape()) {
            cancel();
            return true;
        }
        if (event.isConfirmation()) {
            if (kind == PickKind.ITEM) {
                final ItemStack held = Crosshair.heldItem();
                if (held == null) {
                    rejectedAtMs = ui.nowMs();
                } else {
                    pickStack(held);
                }
            } else {
                confirm();
            }
            return true;
        }
        if (event.input() == Keys.KEY_BACKSPACE) {
            secondary();
            return true;
        }
        return false;
    }

    /** Confirms with the current candidate, or flashes the hint red when it is not acceptable. */
    public void confirm() {
        final PickMode m = mode;
        if (m == null) {
            return;
        }
        m.update();
        final PickResult result = m.result();
        if (result == null) {
            rejectedAtMs = ui.nowMs();
            return;
        }
        resolve(result);
    }

    /**
     * Answers an item pick with a stack.
     *
     * @param stack the clicked or held stack, not empty
     */
    public void pickStack(final ItemStack stack) {
        if (kind != PickKind.ITEM) {
            return;
        }
        resolve(new PickResult.Item(new PickItem(SlotItems.id(stack), stack.getCount(), null)));
    }

    /** Rejects the pick with {@code cancelled}, or returns to the form for a local pick. */
    public void cancel() {
        final Consumer<@Nullable PickResult> callback = local;
        final PickInput in = input;
        clear();
        if (callback != null) {
            callback.accept(null);
            return;
        }
        final BridgeCore core = ui.core();
        if (in != null && core != null) {
            ui.markHandled(in.id());
            core.input().cancel(in.id());
        }
    }

    private void resolve(final PickResult result) {
        final Consumer<@Nullable PickResult> callback = local;
        final PickInput in = input;
        clear();
        if (callback != null) {
            callback.accept(result);
            return;
        }
        final BridgeCore core = ui.core();
        if (in == null || core == null) {
            return;
        }
        ui.markHandled(in.id());
        try {
            core.input().resolvePick(in.id(), result);
        } catch (final IllegalArgumentException e) {
            ui.toastError(in.ownerName(), I18n.get("mineflayer_bridge.pick.failed", e.getMessage()));
        }
    }

    /**
     * Returns the preview geometry for this frame, sampling the crosshair first.
     *
     * @return the commands; the list is reused and valid until the next call
     */
    public List<DrawCommand> preview() {
        preview.clear();
        final PickMode m = mode;
        if (m != null && Game.screen() == null) {
            m.update();
            m.draw(preview);
        }
        return preview;
    }

    /**
     * Returns the name shown with the hint: the requesting bot, or "Form" for a local pick.
     *
     * @return the owner name
     */
    public String owner() {
        return owner;
    }

    /**
     * Returns the prompt text.
     *
     * @return the bot's prompt, or a default for the kind
     */
    public String prompt() {
        return prompt;
    }

    /**
     * Returns the candidate description.
     *
     * @return the status of the active mode, or empty
     */
    public String status() {
        final PickMode m = mode;
        return m == null ? "" : m.status();
    }

    /**
     * Returns whether the hint should be red: the candidate breaks a constraint, or a confirm was just rejected.
     *
     * @return whether the pick is shown as invalid
     */
    public boolean showInvalid() {
        final PickMode m = mode;
        return m != null && (!m.valid() || ui.nowMs() - rejectedAtMs < REJECT_FLASH_MS);
    }

    /**
     * Returns the time left before the bot's timeout.
     *
     * @return milliseconds, or -1 for a local pick
     */
    public long remainingMs() {
        final PickInput in = input;
        return in == null ? -1L : Math.max(0L, in.deadlineMs() - ui.nowMs());
    }

    /**
     * Returns the key help for the hint.
     *
     * @return the keys line
     */
    public String keysHelp() {
        final PickKind k = kind;
        if (k == null) {
            return "";
        }
        return I18n.get(switch (k) {
            case POINT -> "mineflayer_bridge.pick.keys.point";
            case BLOCK, ENTITY, DIRECTION -> "mineflayer_bridge.pick.keys.confirm";
            case REGION -> "mineflayer_bridge.pick.keys.region";
            case POLYGON -> "mineflayer_bridge.pick.keys.polygon";
            case PATH -> "mineflayer_bridge.pick.keys.path";
            case ITEM -> "mineflayer_bridge.pick.keys.item";
        });
    }

    private static String defaultPrompt(final PickKind kind) {
        return I18n.get(switch (kind) {
            case POINT -> "mineflayer_bridge.pick.prompt.point";
            case BLOCK -> "mineflayer_bridge.pick.prompt.block";
            case REGION -> "mineflayer_bridge.pick.prompt.region";
            case POLYGON -> "mineflayer_bridge.pick.prompt.polygon";
            case ENTITY -> "mineflayer_bridge.pick.prompt.entity";
            case PATH -> "mineflayer_bridge.pick.prompt.path";
            case DIRECTION -> "mineflayer_bridge.pick.prompt.direction";
            case ITEM -> "mineflayer_bridge.pick.prompt.item";
        });
    }
}
