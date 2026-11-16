package io.github.pix3lpirat3.mfbridge.common.display;

import io.github.pix3lpirat3.mfbridge.common.display.logic.ElementGrid;
import io.github.pix3lpirat3.mfbridge.common.display.logic.WorldMenuLayout;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.core.input.WorldMenuInput;
import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayBlockData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayEntityData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayItemData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayTextData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayTransform;
import io.github.pix3lpirat3.mfbridge.protocol.core.HoverStyle;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuEntry;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Runs the active world menu and prompts shown with surface {@code world}:
 * entries are drawn as displays around the anchor in the requested
 * layout, facing the player. Looking at an entry highlights it (it grows and glows and shows its
 * label), scroll cycles through entries and pages, a click selects and Esc cancels. Like a pick, an
 * active menu owns the mouse, so its clicks never reach vanilla. Client thread only.
 */
public final class WorldMenus {
    /** Key prefix of the menu's own display nodes; they are never reported to the core. */
    static final String KEY_PREFIX = "~menu/";

    private static final HoverStyle HIGHLIGHT = new HoverStyle(1.3f, 0xFFFFFFFF, null);
    private static final double PROMPT_DISTANCE = 2d;
    private static final double LABEL_DROP = 0.45d;
    private static final double PROMPT_RISE = 0.6d;

    /** One selectable entry: what it shows and what selecting it answers. */
    private record Entry(String id, @Nullable String label, Object answer, DisplayNode node, @Nullable DisplayNode labelNode) {}

    private final UiContext ui;
    private final List<Entry> entries = new ArrayList<>();
    private final ElementGrid grid = new ElementGrid();
    private final int[] gridEntries = new int[WorldMenuLayout.GRID.pageSize() + 8];
    private final double[] offset = new double[2];
    private final double[] quaternion = new double[4];
    private final Vector3f centre = new Vector3f();
    private long id = -1L;
    private boolean prompt;
    private String owner = "";
    private String promptText = "";
    private WorldMenuLayout layout = WorldMenuLayout.RING;
    private double radius = 1.5d;
    private double atX;
    private double atY;
    private double atZ;
    private int highlight = -1;
    private int page;
    private @Nullable DisplayNode promptNode;
    private String promptShown = "";

    WorldMenus(final UiContext ui) {
        this.ui = ui;
    }

    /**
     * Returns whether a world menu or world prompt is showing.
     *
     * @return true while it owns the mouse
     */
    public boolean active() {
        return id >= 0;
    }

    /**
     * Shows a world menu request.
     *
     * @param input the request from the input queue
     */
    public void begin(final WorldMenuInput input) {
        final WorldMenuRequest r = input.request();
        start(input.id(), false, input.ownerName(), r.prompt(), WorldMenuLayout.of(r.layout()), r.radius(), r.at());
        for (final WorldMenuEntry e : r.entries()) {
            final String label = e.label() != null ? e.label() : e.text();
            entries.add(entry(e, label, e.id()));
        }
    }

    /**
     * Shows a confirm or choice prompt as a world menu with text entries (surface {@code
     * world}), at the prompt's {@code at} or two blocks in front of the player.
     *
     * @param input the prompt from the input queue
     */
    public void beginPrompt(final PromptInput input) {
        final PromptRequest r = input.request();
        start(input.id(), true, input.ownerName(), r.text(), WorldMenuLayout.ROW, 1.5f, r.at() != null ? r.at() : inFront());
        if (r.kind() == PromptKind.CONFIRM) {
            entries.add(textEntry("yes", I18n.get("gui.yes"), Boolean.TRUE));
            entries.add(textEntry("no", I18n.get("gui.no"), Boolean.FALSE));
            highlight = Boolean.FALSE.equals(r.defaultValue()) ? 1 : 0;
        } else {
            final List<String> choices = r.choices() == null ? List.of() : r.choices();
            for (int i = 0; i < choices.size(); i++) {
                entries.add(textEntry(String.valueOf(i), choices.get(i), choices.get(i)));
            }
            if (choices.size() > WorldMenuLayout.ROW.pageSize()) {
                layout = WorldMenuLayout.GRID;
            }
            final int def = r.defaultValue() == null ? -1 : choices.indexOf(r.defaultValue().toString());
            highlight = Math.max(def, 0);
        }
        final BridgeCore core = ui.core();
        if (core != null) {
            core.input().surfaceShown(input.id(), "world");
        }
    }

    private static Vec3d inFront() {
        final Minecraft minecraft = Minecraft.getInstance();
        final LocalPlayer player = minecraft.player;
        if (player == null) {
            return new Vec3d(0, 0, 0);
        }
        final Vec3 eye = player.getEyePosition();
        final Vec3 look = player.getLookAngle();
        final double len = Math.max(1e-6, Math.sqrt(look.x * look.x + look.z * look.z));
        return new Vec3d(eye.x + look.x / len * PROMPT_DISTANCE, eye.y, eye.z + look.z / len * PROMPT_DISTANCE);
    }

    private void start(final long newId, final boolean isPrompt, final String newOwner, final @Nullable String text,
            final WorldMenuLayout newLayout, final float newRadius, final Vec3d at) {
        end();
        id = newId;
        prompt = isPrompt;
        owner = newOwner;
        promptText = text == null ? "" : text;
        layout = newLayout;
        radius = newRadius > 0f ? newRadius : 1.5d;
        atX = at.x();
        atY = at.y();
        atZ = at.z();
        highlight = -1;
        page = 0;
        promptShown = "";
        promptNode = null;
    }

    /** Hides the menu without answering it. */
    public void end() {
        id = -1L;
        entries.clear();
        promptNode = null;
    }

    /** Entries light up when looked at. */
    private static final io.github.pix3lpirat3.mfbridge.protocol.core.ObjectTraits HIGHLIGHTED =
            new io.github.pix3lpirat3.mfbridge.protocol.core.ObjectTraits(null, HIGHLIGHT, null, null, null);

    /** World menus must stay readable at night and underground, so entries ignore world light. */
    private static final Integer FULL_BRIGHT = 15;

    private Entry entry(final WorldMenuEntry e, final @Nullable String label, final Object answer) {
        final int index = entries.size();
        final String key = KEY_PREFIX + index;
        final Vec3d origin = new Vec3d(0, 0, 0);
        final DisplayNode node;
        if (e.item() != null) {
            node = node(key, new DisplayItemData(origin, e.item(), "fixed", 0.75f, null, null, "vertical", 0, null, 0f, 2000, FULL_BRIGHT,
                    null, 64));
        } else if (e.block() != null) {
            node = node(key, new DisplayBlockData(origin, e.block(), 0.4f, null, new DisplayTransform(null, null,
                    new Vec3d(-0.2, -0.2, -0.2)), "vertical", 0, null, 0f, 2000, FULL_BRIGHT, null, 64));
        } else if (e.entity() != null) {
            node = node(key, new DisplayEntityData(origin, e.entity(), 0f, 0f, null, null, null, false, false, null, null, 0.45f,
                    null, null, "vertical", 0, null, 0f, 2000, FULL_BRIGHT, null, 64, false, false, false, null, null,
                    null, false, false, null, null, null));
        } else {
            return textEntry(e.id(), e.text() != null ? e.text() : label != null ? label : e.id(), answer);
        }
        final DisplayNode labelNode = label == null ? null : node(key + "/label", text(label, 0.6f));
        return new Entry(e.id(), label, answer, node, labelNode);
    }

    private Entry textEntry(final String entryId, final String label, final Object answer) {
        return new Entry(entryId, null, answer, node(KEY_PREFIX + entries.size(), text(label, 1f)), null);
    }

    private static DisplayTextData text(final String text, final float scale) {
        return new DisplayTextData(new Vec3d(0, 0, 0), text, 200, 0x80000000, true, false, "center", scale, null, null, "center",
                0, null, 0f, 2000, 15, null, 64);
    }

    private static DisplayNode node(final String key, final Object data) {
        final DisplayNode.Kind kind = Objects.requireNonNull(DisplayNode.kindOf(data));
        final DisplayNode n = new DisplayNode(key, "", null, kind, data);
        n.update(data, HIGHLIGHTED, 0L, 0L);
        return n;
    }

    /** Places this frame's entries around the anchor, facing the camera, and adds them to the scene. */
    void frame(final DisplayScene scene, final Vec3 camera, final long nowMs) {
        scene.clearExtra();
        if (!active() || entries.isEmpty()) {
            return;
        }
        double dx = atX - camera.x;
        double dz = atZ - camera.z;
        final double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) {
            dx = 0d;
            dz = 1d;
        } else {
            dx /= len;
            dz /= len;
        }
        // The viewer's right when looking from the camera towards the anchor.
        final double rightX = -dz;
        final double rightZ = dx;
        if (highlight >= 0) {
            page = highlight / layout.pageSize();
        }
        final int first = page * layout.pageSize();
        final int count = layout.countOnPage(first, entries.size());
        double top = 0d;
        for (int slot = 0; slot < count; slot++) {
            final Entry e = entries.get(first + slot);
            layout.place(slot, count, radius, offset);
            final double x = atX + rightX * offset[0];
            final double y = atY + offset[1];
            final double z = atZ + rightZ * offset[0];
            top = Math.max(top, offset[1]);
            e.node().place(x, y, z, 1d);
            e.node().hovered = first + slot == highlight;
            scene.addExtra(e.node());
            final DisplayNode label = e.labelNode();
            if (label != null && e.node().hovered) {
                label.place(x, y - LABEL_DROP, z, 1d);
                scene.addExtra(label);
            }
        }
        final int pages = layout.pages(entries.size());
        final String header = (owner.isEmpty() ? "" : owner + ": ") + promptText + (pages > 1 ? "  (" + (page + 1) + "/" + pages + ")" : "");
        DisplayNode p = promptNode;
        if (p == null || !header.equals(promptShown)) {
            p = node(KEY_PREFIX + "prompt", text(header, 0.8f));
            promptNode = p;
            promptShown = header;
        }
        p.place(atX, atY + top + PROMPT_RISE, atZ, 1d);
        scene.addExtra(p);
    }

    /**
     * Highlights the entry under the crosshair.
     *
     * @param drawer the drawer that knows each node's hit box
     */
    void pick(final Vec3 eye, final Vector3fc look, final DisplayDrawer drawer) {
        if (!active()) {
            return;
        }
        grid.clear();
        int n = 0;
        final int first = page * layout.pageSize();
        final int count = layout.countOnPage(first, entries.size());
        for (int slot = 0; slot < count && n < gridEntries.length; slot++) {
            final DisplayNode node = entries.get(first + slot).node();
            if (node.resolvedFrame < 0) {
                continue;
            }
            drawer.boxCentre(node, centre);
            quaternion[0] = node.rotation.x;
            quaternion[1] = node.rotation.y;
            quaternion[2] = node.rotation.z;
            quaternion[3] = node.rotation.w;
            // Entries get a forgiving box so small items are easy to aim at.
            grid.add(node.wx + centre.x, node.wy + centre.y, node.wz + centre.z, quaternion,
                    Math.max(0.25d, node.halfX * Math.abs(node.scaleX)), Math.max(0.25d, node.halfY * Math.abs(node.scaleY)),
                    Math.max(0.25d, node.halfZ * Math.abs(node.scaleZ)), 0d, 0d, 0d);
            gridEntries[n++] = first + slot;
        }
        final int hit = grid.raycast(eye.x, eye.y, eye.z, look.x(), look.y(), look.z(), 64d);
        if (hit >= 0) {
            highlight = gridEntries[hit];
        }
    }

    /**
     * Cycles the highlight: scroll cycles entries and pages.
     *
     * @param steps scroll steps, positive up; scrolling down moves forward
     */
    public void scroll(final int steps) {
        if (active()) {
            highlight = WorldMenuLayout.cycle(highlight, -steps, entries.size());
        }
    }

    /** A click selects the highlighted entry; clicks with nothing highlighted are swallowed. */
    public void click() {
        if (active() && highlight >= 0 && highlight < entries.size()) {
            select(entries.get(highlight));
        }
    }

    /**
     * Handles a key press: Esc cancels, Enter selects.
     *
     * @param event the key
     * @return whether the key was used
     */
    public boolean keyPressed(final KeyEvent event) {
        if (!active()) {
            return false;
        }
        if (event.isEscape()) {
            cancel();
            return true;
        }
        if (event.isConfirmation()) {
            click();
            return true;
        }
        return false;
    }

    private void select(final Entry e) {
        final BridgeCore core = ui.core();
        final long requestId = id;
        end();
        if (core == null) {
            return;
        }
        ui.markHandled(requestId);
        try {
            if (prompt) {
                core.input().resolvePrompt(requestId, e.answer(), "world");
            } else {
                core.input().resolveWorldMenu(requestId, e.id());
            }
        } catch (final IllegalArgumentException ex) {
            ui.toastError(owner, I18n.get("mineflayer_bridge.prompt.rejected", ex.getMessage()));
        }
    }

    /** Dismisses the menu; the bot gets {@code cancelled}. */
    public void cancel() {
        final BridgeCore core = ui.core();
        final long requestId = id;
        end();
        if (core != null && requestId >= 0) {
            ui.markHandled(requestId);
            core.input().cancel(requestId);
        }
    }

    /**
     * Returns the HUD hint for the active menu.
     *
     * @return the hint, or null when no menu is showing
     */
    public @Nullable String hint() {
        if (!active()) {
            return null;
        }
        return I18n.get("mineflayer_bridge.world_menu.hint", owner);
    }
}
