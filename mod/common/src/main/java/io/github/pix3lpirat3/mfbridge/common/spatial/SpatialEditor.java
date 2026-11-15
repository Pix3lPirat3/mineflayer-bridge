package io.github.pix3lpirat3.mfbridge.common.spatial;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pix3lpirat3.mfbridge.common.render.command.DepthMode;
import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.common.seam.Vec3d;
import io.github.pix3lpirat3.mfbridge.common.spatial.logic.Gizmo;
import io.github.pix3lpirat3.mfbridge.common.spatial.logic.SpatialEdits;
import io.github.pix3lpirat3.mfbridge.common.spatial.logic.SpatialShapes;
import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.common.ui.input.Crosshair;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.RenderEntry;
import io.github.pix3lpirat3.mfbridge.core.spatial.GizmoMath;
import io.github.pix3lpirat3.mfbridge.core.spatial.SpatialIndex;
import io.github.pix3lpirat3.mfbridge.protocol.core.RouteData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.ZoneData;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Gizmo editing of one selected zone, point or route. Handles are hit-tested
 * before anything else gets the click; a drag previews locally and writes once on release, which is
 * one undo step. Arrow keys and Page Up and Down nudge relative to the camera; Backspace deletes the
 * active route node or polygon vertex; Delete deletes the whole object. Client thread only.
 */
public final class SpatialEditor {

    /** Handle ray length, in blocks. */
    public static final double REACH = 64d;

    private static final int HANDLE_RGBA = 0xFFFFFFFF;
    private static final int HOVER_RGBA = 0xFFE040FF;
    private static final int ACTIVE_RGBA = 0x40C0FFFF;
    private static final int AXIS_RGBA = 0xC0C0C0FF;

    private final UiContext ui;
    private final SpatialWrites writes;
    private @Nullable String selected;
    private SpatialIndex.@Nullable Item base;
    private @Nullable Object working;
    private int active = -1;
    private Gizmo.@Nullable Drag drag;
    private Gizmo.@Nullable Hit hover;
    private List<DrawCommand> staticCommands = List.of();
    private long staticVersion = Long.MIN_VALUE;
    private Set<String> staticHidden = Set.of();

    /**
     * Creates the editor.
     *
     * @param ui the UI services
     * @param writes the edit writer
     */
    public SpatialEditor(final UiContext ui, final SpatialWrites writes) {
        this.ui = ui;
        this.writes = writes;
    }

    /** Returns whether an object is selected for editing. */
    public boolean hasSelection() {
        return selected != null;
    }

    /** Returns whether a drag is in progress. */
    public boolean dragging() {
        return drag != null;
    }

    /** Returns the selected object as last seen, or null. */
    public SpatialIndex.@Nullable Item selection() {
        return base;
    }

    /**
     * Selects an object for editing.
     *
     * @param item the object; objects whose data is not editable are refused with a toast
     */
    public void select(final SpatialIndex.Item item) {
        if (!item.editable()) {
            ui.toastError(I18n.get("mineflayer_bridge.spatial.title"), I18n.get("mineflayer_bridge.spatial.not_editable", item.name()));
            return;
        }
        selected = item.key();
        base = item;
        working = item.data();
        active = item.kind() == SpatialIndex.Kind.ZONE ? 0 : -1;
        drag = null;
    }

    /** Ends editing; an unfinished drag is dropped without writing. */
    public void deselect() {
        selected = null;
        base = null;
        working = null;
        drag = null;
        hover = null;
        active = -1;
    }

    private static @Nullable SpatialIndex index(final @Nullable BridgeCore core) {
        return core == null ? null : core.spatial();
    }

    /** Follows the latest objects: a removed selection ends, a newer revision replaces the copy when idle. */
    public void tick() {
        final String key = selected;
        final SpatialIndex index = index(ui.core());
        if (key == null) {
            return;
        }
        final SpatialIndex.Item item = index == null ? null : index.get(key);
        if (item == null) {
            if (!writes.pending().containsKey(key)) {
                deselect();
            }
            return;
        }
        final SpatialIndex.Item b = base;
        if (drag == null && (b == null || item.rev() != b.rev() || !Objects.equals(item.data(), b.data()))) {
            base = item;
            working = item.data();
        }
    }

    /**
     * Returns the ray of the camera.
     *
     * @return the eye and look direction
     */
    public static Gizmo.Ray ray() {
        final Vec3 eye = Crosshair.eye();
        final Vec3 look = Crosshair.look();
        return new Gizmo.Ray(eye.x, eye.y, eye.z, look.x, look.y, look.z);
    }

    /**
     * Returns the snap mode of the held modifiers: Ctrl, Shift, Alt.
     *
     * @return the snap mode
     */
    public static GizmoMath.Snap snap() {
        final Minecraft minecraft = Minecraft.getInstance();
        return GizmoMath.Snap.of(minecraft.hasControlDown(), minecraft.hasShiftDown(), minecraft.hasAltDown());
    }

    /**
     * Handles a primary press: a hit on a handle starts a drag; Ctrl on a route segment inserts a
     * node there.
     *
     * @return whether the press was used; false lets it fall through to tools and vanilla
     */
    public boolean press() {
        final Object w = working;
        if (w == null || drag != null) {
            return drag != null;
        }
        final Gizmo.Ray ray = ray();
        if (Minecraft.getInstance().hasControlDown() && w instanceof RouteData route) {
            final SpatialEdits.Insertion at = SpatialEdits.insertion(route, ray);
            if (at != null) {
                active = at.index();
                commit(I18n.get("mineflayer_bridge.spatial.op.insert_node"), SpatialEdits.withInsertedNode(route, at.index(), at.pos()));
                return true;
            }
        }
        final Gizmo.Hit hit = Gizmo.hit(Gizmo.handles(w, active), ray, REACH);
        if (hit == null) {
            return false;
        }
        final Gizmo.Handle h = hit.handle();
        if (h.index() >= 0) {
            active = h.index();
        }
        drag = new Gizmo.Drag(hit, w, ray);
        return true;
    }

    /** Handles the primary release: a finished drag writes once. */
    public void release() {
        final Gizmo.Drag d = drag;
        if (d == null) {
            return;
        }
        final Object result = d.update(ray(), snap());
        drag = null;
        if (!result.equals(d.start())) {
            working = result;
            commit(label(d.handle()), result);
        }
    }

    /**
     * Changes the grab distance of the drag in progress.
     *
     * @return whether a drag took the scroll
     */
    public boolean scroll(final int steps) {
        final Gizmo.Drag d = drag;
        if (d == null) {
            return false;
        }
        d.scroll(steps);
        return true;
    }

    private static String label(final Gizmo.Handle h) {
        return I18n.get(switch (h.type()) {
            case CORNER, FACE, TOP, BOTTOM -> "mineflayer_bridge.spatial.op.resize_zone";
            case BODY, MOVE, AXIS -> "mineflayer_bridge.spatial.op.move";
            case VERTEX -> "mineflayer_bridge.spatial.op.move_vertex";
            case YAW -> "mineflayer_bridge.spatial.op.turn_point";
            case NODE -> "mineflayer_bridge.spatial.op.move_node";
        });
    }

    private void commit(final String label, final Object data) {
        final SpatialIndex.Item b = base;
        if (b == null) {
            return;
        }
        working = data;
        if (!writes.update(b, label + " (" + b.name() + ")", data)) {
            // On conflict the latest object replaces the local copy.
            working = b.data();
            base = null;
            tick();
        }
    }

    /**
     * Handles a key press while an object is selected.
     *
     * @param key the GLFW key code
     * @return whether the key was used
     */
    public boolean keyPressed(final int key) {
        final Object w = working;
        final SpatialIndex.Item b = base;
        if (w == null || b == null) {
            return false;
        }
        final GizmoMath.Nudge nudge = switch (key) {
            case InputConstants.KEY_UP -> GizmoMath.Nudge.FORWARD;
            case InputConstants.KEY_DOWN -> GizmoMath.Nudge.BACK;
            case InputConstants.KEY_LEFT -> GizmoMath.Nudge.LEFT;
            case InputConstants.KEY_RIGHT -> GizmoMath.Nudge.RIGHT;
            case InputConstants.KEY_PAGEUP -> GizmoMath.Nudge.UP;
            case InputConstants.KEY_PAGEDOWN -> GizmoMath.Nudge.DOWN;
            default -> null;
        };
        if (nudge != null) {
            if (drag == null) {
                final float yaw = Minecraft.getInstance().gameRenderer.mainCamera().yRot();
                commit(I18n.get("mineflayer_bridge.spatial.op.nudge"), SpatialEdits.nudge(w, active, GizmoMath.nudgeAxis(yaw, nudge), snap().nudge()));
            }
            return true;
        }
        if (key == InputConstants.KEY_BACKSPACE) {
            if (drag == null && w instanceof RouteData r && active >= 0) {
                final RouteData next = SpatialEdits.withoutNode(r, active);
                if (next != r) {
                    active = Math.min(active, next.nodes().size() - 1);
                    commit(I18n.get("mineflayer_bridge.spatial.op.delete_node"), next);
                }
            } else if (drag == null && w instanceof ZoneData z && z.polygon() != null && active >= 0) {
                final ZoneData next = SpatialEdits.withoutVertex(z, active);
                if (next != z) {
                    active = -1;
                    commit(I18n.get("mineflayer_bridge.spatial.op.delete_vertex"), next);
                }
            }
            return true;
        }
        if (key == InputConstants.KEY_DELETE) {
            if (drag == null && writes.delete(b)) {
                deselect();
            }
            return true;
        }
        if (key == InputConstants.KEY_ESCAPE) {
            if (drag != null) {
                drag = null;
                working = b.data();
            } else {
                deselect();
            }
            return true;
        }
        return false;
    }

    /**
     * Returns the draw commands of every visible spatial object except the one being edited and those
     * with a pending bot edit, rebuilt only when the index or that set changes.
     *
     * @return an immutable list
     */
    public List<DrawCommand> staticCommands() {
        final SpatialIndex index = index(ui.core());
        if (index == null) {
            return List.of();
        }
        // Checked without building a set, since this runs every frame.
        final Set<String> pending = writes.pending().keySet();
        final String sel = selected;
        final boolean extra = sel != null && !pending.contains(sel);
        final boolean sameHidden = staticHidden.size() == pending.size() + (extra ? 1 : 0)
                && staticHidden.containsAll(pending) && (sel == null || staticHidden.contains(sel));
        if (index.version() != staticVersion || !sameHidden) {
            final Set<String> hidden = new HashSet<>(pending);
            if (sel != null) {
                hidden.add(sel);
            }
            staticVersion = index.version();
            staticHidden = Set.copyOf(hidden);
            staticCommands = List.copyOf(SpatialShapes.all(index, staticHidden));
        }
        return staticCommands;
    }

    /**
     * Adds this frame's preview: the edited object as it would be after the drag, its handles, and
     * pending bot edits.
     *
     * @param out the frame's command list
     */
    public void preview(final List<DrawCommand> out) {
        for (final SpatialIndex.Item p : writes.pending().values()) {
            SpatialShapes.add(out, p, SpatialShapes.Look.PENDING);
        }
        final SpatialIndex.Item b = base;
        Object w = working;
        if (b == null || w == null || Minecraft.getInstance().gui.screen() != null) {
            return;
        }
        final Gizmo.Ray ray = ray();
        final Gizmo.Drag d = drag;
        if (d != null) {
            w = d.update(ray, snap());
            working = w;
        }
        final SpatialIndex.Item shown = SpatialIndex.item(new RenderEntry(0, b.key(), b.owner(), "", "", null,
                SpatialWrites.kindOf(w), b.rev(), w, null, Scope.GLOBAL, null));
        if (shown != null) {
            SpatialShapes.add(out, shown, writes.pending().containsKey(b.key()) ? SpatialShapes.Look.PENDING
                    : SpatialShapes.Look.SELECTED);
        }
        final List<Gizmo.Handle> handles = Gizmo.handles(w, active);
        hover = d == null ? Gizmo.hit(handles, ray, REACH) : null;
        handles(out, handles, d != null ? d.handle() : hover == null ? null : hover.handle(), active);
        if (active >= 0 && w instanceof RouteData r && active < r.nodes().size()) {
            final var p = r.nodes().get(active).pos();
            out.add(DrawCommand.Box.cube(p.x(), p.y(), p.z(), 0.3, ACTIVE_RGBA, 0.25f, DepthMode.BOTH));
        }
    }

    /**
     * Draws gizmo handles: boxes, axis lines and the yaw ring, the hot one highlighted.
     *
     * @param out the frame's command list
     * @param handles the handles
     * @param hot the hovered or dragged handle, or null
     * @param active the focused node or vertex index, or -1
     */
    public static void handles(final List<DrawCommand> out, final List<Gizmo.Handle> handles, final Gizmo.@Nullable Handle hot,
            final int active) {
        for (final Gizmo.Handle h : handles) {
            final boolean isHot = hot != null && hot.type() == h.type() && hot.index() == h.index() && hot.sub() == h.sub();
            final int rgba = isHot ? HOVER_RGBA : h.type() == Gizmo.Type.AXIS ? AXIS_RGBA : HANDLE_RGBA;
            if (h.type() == Gizmo.Type.YAW) {
                ring(out, h, rgba);
                continue;
            }
            if (h.type() == Gizmo.Type.AXIS) {
                final double o = Gizmo.AXIS_OFFSET;
                final Vec3d tip = new Vec3d(h.x(), h.y(), h.z());
                final Vec3d root = tip.add(h.sub() == 0 ? -o : 0, h.sub() == 1 ? -o : 0, h.sub() == 2 ? -o : 0);
                out.add(new DrawCommand.Line(root, tip, rgba, 2f, DepthMode.BOTH));
            }
            final double s = h.half();
            final boolean focus = h.index() == active && h.index() >= 0;
            out.add(DrawCommand.Box.cube(h.x(), h.y(), h.z(), s, rgba, focus || isHot ? 0.6f : 0.3f, DepthMode.BOTH));
        }
    }

    private static void ring(final List<DrawCommand> out, final Gizmo.Handle h, final int rgba) {
        final int segments = 32;
        final double r = h.half();
        Vec3d prev = new Vec3d(h.x(), h.y(), h.z() + r);
        for (int i = 1; i <= segments; i++) {
            final double a = Math.PI * 2 * i / segments;
            final Vec3d next = new Vec3d(h.x() - Math.sin(a) * r, h.y(), h.z() + Math.cos(a) * r);
            out.add(new DrawCommand.Line(prev, next, rgba, 2f, DepthMode.BOTH));
            prev = next;
        }
    }

    /**
     * Returns the hint for the HUD while an object is selected.
     *
     * @return the keys line, or empty without a selection
     */
    public String hint() {
        final SpatialIndex.Item b = base;
        if (b == null) {
            return "";
        }
        final String name = writes.pending().containsKey(b.key()) ? I18n.get("mineflayer_bridge.spatial.editing.waiting", b.name()) : b.name();
        final String extra = switch (b.kind()) {
            case ROUTE -> "mineflayer_bridge.spatial.editing.route";
            case ZONE -> b.data() instanceof ZoneData z && z.polygon() != null && (z.boxes() == null || z.boxes().isEmpty())
                    ? "mineflayer_bridge.spatial.editing.polygon" : null;
            case POINT -> "mineflayer_bridge.spatial.editing.point";
        };
        return extra == null ? I18n.get("mineflayer_bridge.spatial.editing", name)
                : I18n.get("mineflayer_bridge.spatial.editing.extra", name, I18n.get(extra));
    }
}
