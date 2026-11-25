package io.github.pix3lpirat3.mfbridge.common.spatial;

import io.github.pix3lpirat3.mfbridge.common.render.command.DepthMode;
import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.common.seam.Vec3d;
import io.github.pix3lpirat3.mfbridge.common.spatial.logic.Gizmo;
import io.github.pix3lpirat3.mfbridge.common.spatial.logic.Ruler;
import io.github.pix3lpirat3.mfbridge.common.spatial.logic.SpatialEdits;
import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.common.ui.container.SlotItems;
import io.github.pix3lpirat3.mfbridge.common.ui.input.Crosshair;
import io.github.pix3lpirat3.mfbridge.common.ui.input.PickController;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.history.HistoryControls;
import io.github.pix3lpirat3.mfbridge.core.history.HistoryEntry;
import io.github.pix3lpirat3.mfbridge.core.spatial.SpatialIndex;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.PointData;
import io.github.pix3lpirat3.mfbridge.protocol.core.RouteData;
import io.github.pix3lpirat3.mfbridge.protocol.core.RouteNode;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import io.github.pix3lpirat3.mfbridge.protocol.core.ZoneData;
import io.github.pix3lpirat3.mfbridge.common.version.Keys;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The first-party spatial tools on the tool wheel ({@code bridge:zones}, {@code
 * bridge:path-tool}, {@code bridge:ruler}). Zone, Point and Path select an editable object when the
 * click points at one; otherwise they create a shared object. Zone picks a region (or, with the use
 * button, a polygon), Point uses the block under the crosshair, Path collects nodes until Enter;
 * each then asks for a name. The Ruler measures between two clicked blocks until Esc. Client thread
 * only.
 */
public final class SpatialTools {

    /** The tools. */
    public enum Tool {
        /** {@code bridge:zones}: create and edit zones. */
        ZONE("mineflayer_bridge.spatial.tool.zone"),
        /** {@code bridge:zones}: create and edit points. */
        POINT("mineflayer_bridge.spatial.tool.point"),
        /** {@code bridge:path-tool}: create and edit routes. */
        PATH("mineflayer_bridge.spatial.tool.path"),
        /** {@code bridge:ruler}: measure. */
        RULER("mineflayer_bridge.spatial.tool.ruler");

        private final String labelKey;

        Tool(final String labelKey) {
            this.labelKey = labelKey;
        }

        /** Returns the wheel label. */
        public String label() {
            return I18n.get(labelKey);
        }
    }

    /** Owner shown for the first-party tools. */
    public static final String OWNER = "Bridge";

    private static final int RULER_RGBA = 0xFFE040FF;
    private static final String TITLE = "mineflayer_bridge.spatial.title";

    private final UiContext ui;
    private final PickController picks;
    private final SpatialWrites writes;
    private final SpatialEditor editor;
    private @Nullable Tool active;
    private @Nullable Vec3i rulerA;
    private @Nullable Vec3i rulerB;
    private int created;
    private @Nullable String selectWhenIndexed;

    /**
     * Creates the tools.
     *
     * @param ui the UI services
     * @param picks the pick controller for region, polygon and path picks
     */
    public SpatialTools(final UiContext ui, final PickController picks) {
        this.ui = ui;
        this.picks = picks;
        this.writes = new SpatialWrites(ui);
        this.editor = new SpatialEditor(ui, writes);
    }

    /** Returns the gizmo editor. */
    public SpatialEditor editor() {
        return editor;
    }

    /** Returns the edit writer, shared with the object editor. */
    public SpatialWrites writes() {
        return writes;
    }

    /** Returns the active tool, or null. */
    public @Nullable Tool active() {
        return active;
    }

    /**
     * Selects a tool or turns the spatial tools off. Turning them off ends editing and clears the
     * ruler.
     */
    public void select(final @Nullable Tool tool) {
        active = tool;
        rulerA = null;
        rulerB = null;
        if (tool == null) {
            editor.deselect();
        }
    }

    /** Returns whether clicks belong to the spatial tools: a tool is active or an object is selected. */
    public boolean capturesClicks() {
        return active != null || editor.dragging();
    }

    /** Returns whether undo and redo keys apply: a tool is active or an object is being edited. */
    public boolean undoContext() {
        return active != null || editor.hasSelection();
    }

    /** Client tick. */
    public void tick() {
        final String key = selectWhenIndexed;
        final BridgeCore core = ui.core();
        if (key != null && core != null) {
            final SpatialIndex.Item item = core.spatial().get(key);
            if (item != null) {
                selectWhenIndexed = null;
                editor.select(item);
            }
        }
        editor.tick();
    }

    private static double blockDistance() {
        final BlockHitResult hit = Crosshair.blockHit(SpatialEditor.REACH);
        return hit == null ? SpatialEditor.REACH : hit.getLocation().distanceTo(Crosshair.eye());
    }

    /**
     * Handles a primary press: gizmo handles first, then selecting an object, then the tool.
     *
     * @return whether the press was used
     */
    public boolean primary() {
        if (editor.press()) {
            return true;
        }
        final Tool tool = active;
        if (tool == null) {
            return false;
        }
        if (tool != Tool.RULER && selectUnderCrosshair(tool)) {
            return true;
        }
        switch (tool) {
            case ZONE -> picks.beginLocal(OWNER, I18n.get("mineflayer_bridge.spatial.pick.corners"), PickKind.REGION, this::regionPicked);
            case POINT -> createPoint();
            case PATH -> picks.beginLocal(OWNER, I18n.get("mineflayer_bridge.spatial.pick.route"), PickKind.PATH, this::pathPicked);
            case RULER -> ruler();
        }
        return true;
    }

    /**
     * Handles a secondary press: the zone tool draws a polygon zone, the others end editing.
     *
     * @return whether the press was used
     */
    public boolean secondary() {
        final Tool tool = active;
        if (tool == Tool.ZONE && !editor.hasSelection()) {
            picks.beginLocal(OWNER, I18n.get("mineflayer_bridge.spatial.pick.outline"), PickKind.POLYGON, this::polygonPicked);
            return true;
        }
        if (editor.hasSelection()) {
            editor.deselect();
            return true;
        }
        return tool != null;
    }

    private boolean selectUnderCrosshair(final Tool tool) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return false;
        }
        final SpatialIndex.Item item = Gizmo.pick(core.spatial().all(), SpatialEditor.ray(), blockDistance() + 0.01);
        if (item == null) {
            return false;
        }
        final boolean fits = switch (tool) {
            case ZONE -> item.kind() == SpatialIndex.Kind.ZONE;
            case POINT -> item.kind() == SpatialIndex.Kind.POINT;
            case PATH -> item.kind() == SpatialIndex.Kind.ROUTE;
            case RULER -> false;
        };
        if (!fits) {
            return false;
        }
        editor.select(item);
        return true;
    }

    /** A default name from a key with the running number as its argument. */
    private String nextName(final String key) {
        created++;
        return I18n.get(key, created);
    }

    private void regionPicked(final @Nullable PickResult result) {
        if (!(result instanceof PickResult.Region r)) {
            return;
        }
        name(Component.translatable("screen.mineflayer_bridge.name_prompt.new_zone"), nextName("mineflayer_bridge.spatial.name.zone"),
                n -> select(writes.create(I18n.get("mineflayer_bridge.spatial.op.create_zone", n.name()), SpatialWrites.ZONES,
                SpatialEdits.boxZone(n.name(), n.tags(), r.value().min(), r.value().max(), new ZoneData("").color()))));
    }

    private void polygonPicked(final @Nullable PickResult result) {
        if (!(result instanceof PickResult.Polygon p)) {
            return;
        }
        name(Component.translatable("screen.mineflayer_bridge.name_prompt.new_zone"), nextName("mineflayer_bridge.spatial.name.zone"),
                n -> select(writes.create(I18n.get("mineflayer_bridge.spatial.op.create_zone", n.name()), SpatialWrites.ZONES,
                SpatialEdits.polygonZone(n.name(), n.tags(), p.value().vertices(), p.value().minY(), p.value().maxY(),
                        new ZoneData("").color()))));
    }

    private void pathPicked(final @Nullable PickResult result) {
        if (!(result instanceof PickResult.Path p)) {
            return;
        }
        if (p.value().points().size() < 2) {
            ui.toastError(I18n.get(TITLE), I18n.get("mineflayer_bridge.spatial.route.too_short"));
            return;
        }
        final List<RouteNode> nodes = new ArrayList<>(p.value().points().size());
        for (final var v : p.value().points()) {
            nodes.add(new RouteNode(v));
        }
        name(Component.translatable("screen.mineflayer_bridge.name_prompt.new_route"), nextName("mineflayer_bridge.spatial.name.route"), n -> {
            final RouteData base = new RouteData(n.name(), List.copyOf(nodes));
            select(writes.create(I18n.get("mineflayer_bridge.spatial.op.create_route", n.name()), SpatialWrites.PATHS, new RouteData(n.name(), base.nodes(),
                    false, "linear", n.tags().isEmpty() ? null : n.tags(), base.color(), base.width(), true)));
        });
    }

    private void createPoint() {
        final BlockHitResult hit = Crosshair.blockHit(SpatialEditor.REACH);
        if (hit == null) {
            ui.toastError(I18n.get(TITLE), I18n.get("mineflayer_bridge.spatial.point.no_block"));
            return;
        }
        // The point stands on the clicked face: the bottom centre of the block in front of it.
        final BlockPos at = hit.getBlockPos().relative(hit.getDirection());
        final var pos = new io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        final var player = Minecraft.getInstance().player;
        final ItemStack held = player == null ? ItemStack.EMPTY : player.getMainHandItem();
        final String icon = held.isEmpty() ? null : SlotItems.id(held);
        final float yaw = player == null ? 0f : (float) Math.round(player.getYRot());
        final Float pitch = player == null ? null : (float) Math.round(player.getXRot());
        name(Component.translatable("screen.mineflayer_bridge.name_prompt.new_point"), nextName("mineflayer_bridge.spatial.name.point"), n -> {
            final PointData base = new PointData(n.name(), pos);
            select(writes.create(I18n.get("mineflayer_bridge.spatial.op.create_point", n.name()), SpatialWrites.ZONES, new PointData(n.name(), pos, yaw,
                    n.tags().isEmpty() ? null : n.tags(), base.color(), icon, true, pitch)));
        });
    }

    private void name(final Component title, final String initial, final Consumer<NamePromptScreen.Result> then) {
        Game.setScreen(new NamePromptScreen(title, initial, then));
    }

    /** Selects a freshly created object for editing once the next render snapshot indexes it. */
    private void select(final @Nullable String key) {
        selectWhenIndexed = key;
    }

    private void ruler() {
        final BlockHitResult hit = Crosshair.blockHit(SpatialEditor.REACH);
        if (hit == null) {
            return;
        }
        final Vec3i at = Crosshair.vec3i(hit.getBlockPos());
        if (rulerA == null || rulerB != null) {
            rulerA = at;
            rulerB = null;
        } else {
            rulerB = at;
        }
    }

    /**
     * Handles a key press while a spatial tool is active or an object is selected.
     *
     * @param key the key code
     * @return whether the key was used
     */
    public boolean keyPressed(final int key) {
        if (editor.hasSelection() && editor.keyPressed(key)) {
            return true;
        }
        if (active == Tool.RULER && key == Keys.KEY_ESCAPE && rulerA != null) {
            rulerA = null;
            rulerB = null;
            return true;
        }
        return false;
    }

    /**
     * Undoes or redoes the newest spatial edit, with a toast for the result.
     *
     * @param undo true to undo, false to redo
     */
    public void undo(final boolean undo) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return;
        }
        final HistoryControls history = core.history();
        final CompletableFuture<HistoryEntry> result = undo ? history.undo() : history.redo();
        result.whenComplete((entry, error) -> {
            if (error != null) {
                ui.toastError(I18n.get(TITLE), undo ? I18n.get("mineflayer_bridge.spatial.undo.failed", BridgeUi.describe(error))
                        : I18n.get("mineflayer_bridge.spatial.redo.failed", BridgeUi.describe(error)));
            } else {
                ui.toastInfo(I18n.get(TITLE), undo ? I18n.get("mineflayer_bridge.spatial.undo.done", entry.label())
                        : I18n.get("mineflayer_bridge.spatial.redo.done", entry.label()));
            }
        });
    }

    /**
     * Returns the static spatial shapes for this frame.
     *
     * @return the cached commands
     */
    public List<DrawCommand> staticCommands() {
        return editor.staticCommands();
    }

    /**
     * Adds this frame's previews: gizmos, pending edits and the ruler.
     *
     * @param out the frame's command list
     */
    public void preview(final List<DrawCommand> out) {
        editor.preview(out);
        final Vec3i a = rulerA;
        if (active != Tool.RULER || a == null) {
            return;
        }
        Vec3i b = rulerB;
        if (b == null) {
            final BlockHitResult hit = Crosshair.blockHit(SpatialEditor.REACH);
            if (hit == null) {
                return;
            }
            b = Crosshair.vec3i(hit.getBlockPos());
        }
        final Vec3d pa = new Vec3d(a.x() + 0.5, a.y() + 0.5, a.z() + 0.5);
        final Vec3d pb = new Vec3d(b.x() + 0.5, b.y() + 0.5, b.z() + 0.5);
        out.add(new DrawCommand.Box(new Vec3d(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z())),
                new Vec3d(Math.max(a.x(), b.x()) + 1d, Math.max(a.y(), b.y()) + 1d, Math.max(a.z(), b.z()) + 1d), RULER_RGBA, 0.05f,
                DepthMode.BOTH));
        out.add(new DrawCommand.Line(pa, pb, RULER_RGBA, 3f, DepthMode.BOTH));
        out.add(new DrawCommand.Label(new Vec3d((pa.x() + pb.x()) / 2, (pa.y() + pb.y()) / 2 + 0.6, (pa.z() + pb.z()) / 2),
                Ruler.measure(a, b).label(), 1.2f, 0x00000080, 256d));
    }

    /**
     * Returns the HUD hint for the active tool or edit.
     *
     * @return the hint, or empty
     */
    public String hint() {
        final String edit = editor.hint();
        if (!edit.isEmpty()) {
            return edit;
        }
        final Tool tool = active;
        if (tool == null) {
            return "";
        }
        return I18n.get(switch (tool) {
            case ZONE -> "mineflayer_bridge.spatial.hint.zone";
            case POINT -> "mineflayer_bridge.spatial.hint.point";
            case PATH -> "mineflayer_bridge.spatial.hint.path";
            case RULER -> rulerA == null ? "mineflayer_bridge.spatial.hint.ruler" : rulerB == null ? "mineflayer_bridge.spatial.hint.ruler.second"
                    : "mineflayer_bridge.spatial.hint.ruler.again";
        });
    }
}
