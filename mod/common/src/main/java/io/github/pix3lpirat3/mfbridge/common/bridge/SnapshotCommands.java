package io.github.pix3lpirat3.mfbridge.common.bridge;

import io.github.pix3lpirat3.mfbridge.common.render.command.DepthMode;
import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.common.seam.Vec3d;
import io.github.pix3lpirat3.mfbridge.core.RenderEntry;
import io.github.pix3lpirat3.mfbridge.core.RenderSnapshot;
import io.github.pix3lpirat3.mfbridge.core.diag.ObjectBounds;
import io.github.pix3lpirat3.mfbridge.core.traits.Traits;
import io.github.pix3lpirat3.mfbridge.core.vars.TemplateCache;
import io.github.pix3lpirat3.mfbridge.core.vars.Templates;
import io.github.pix3lpirat3.mfbridge.core.vars.Variables;
import io.github.pix3lpirat3.mfbridge.protocol.core.BoxData;
import io.github.pix3lpirat3.mfbridge.protocol.core.EntityTagData;
import io.github.pix3lpirat3.mfbridge.protocol.core.LabelData;
import io.github.pix3lpirat3.mfbridge.protocol.core.LineData;
import io.github.pix3lpirat3.mfbridge.protocol.core.PathData;
import io.github.pix3lpirat3.mfbridge.protocol.core.StyleData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Visibility;
import io.github.pix3lpirat3.mfbridge.protocol.core.WaypointData;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Follows the core's render snapshots and keeps the immediate-tier draw commands of their entries.
 *
 * <p>Each entry is converted once and converted again only when the snapshot lists it as changed,
 * or, for text with placeholders, when a variable changed and the text reads differently. The
 * command list keeps its identity while no command changed, so the renderer reuses what it prepared.
 * Entity tags follow live entities, so they are kept apart and resolved every frame.
 */
final class SnapshotCommands {
    /** Style used when a shape names no style or a style that does not exist (core:style defaults). */
    private static final StyleData DEFAULT_STYLE = new StyleData(0xFFFFFFFF, 0f, 2f,
            io.github.pix3lpirat3.mfbridge.protocol.core.DepthMode.TEST, 0.35f, false);

    /**
     * What the current snapshot draws.
     *
     * @param commands the commands that are always drawn, in key order
     * @param entityTags tags to resolve against live entities every frame
     * @param conditional commands whose visibility is decided every frame
     * @param tracers tracers to fixed points
     * @param tagTracers whether any entity tag asks for a tracer
     */
    record Converted(List<DrawCommand> commands, List<EntityTag> entityTags, List<Conditional> conditional,
            List<Tracer> tracers, boolean tagTracers) {
        static final Converted EMPTY = new Converted(List.of(), List.of(), List.of(), List.of(), false);
    }

    /** A tracer to a fixed point, drawn from the crosshair every frame. */
    record Tracer(double x, double y, double z, int rgba) {}

    /**
     * Commands of an object whose {@code visibleWhen} has a distance range or {@code lookedAt},
     * checked every frame.
     */
    record Conditional(String key, double x, double y, double z, Visibility visibility, List<DrawCommand> commands) {}

    /** An entity tag waiting for its entity to be looked up this frame. */
    record EntityTag(int entityId, @Nullable String text, int outlineRgba, boolean tracer) {}

    /** What one entry draws; at least one of the four parts is set. */
    private record Item(RenderEntry entry, @Nullable DrawCommand command, @Nullable EntityTag tag,
            @Nullable Conditional conditional, @Nullable Tracer tracer, boolean templated) {
        String key() {
            return entry.key();
        }
    }

    private final TemplateCache templates = new TemplateCache();
    /** Sorted by key, like the snapshot's entries. */
    private final ArrayList<Item> items = new ArrayList<>();
    private long version = -1;
    private long varsVersion = -1;
    private int templated;
    private Converted converted = Converted.EMPTY;

    /** Forgets everything, for example when the core stops. */
    void clear() {
        items.clear();
        version = -1;
        varsVersion = -1;
        templated = 0;
        converted = Converted.EMPTY;
    }

    /**
     * Brings the commands up to date with a snapshot and the variables.
     *
     * @return what to draw; the same instance, with the same lists, while nothing drawn changed
     */
    Converted update(final RenderSnapshot snapshot, final Variables vars) {
        if (snapshot.version() == version && vars.version() == varsVersion) {
            return converted;
        }
        boolean changed = false;
        if (snapshot.version() != version) {
            final RenderSnapshot.Change change = snapshot.changesSince(version);
            if (change == null) {
                changed = !items.isEmpty();
                items.clear();
                templated = 0;
                for (final RenderEntry e : snapshot.entries()) {
                    final Item item = convert(e, vars);
                    if (item != null) {
                        add(items.size(), item);
                        changed = true;
                    }
                }
            } else {
                for (final RenderEntry e : change.removed()) {
                    changed |= put(e.key(), null);
                }
                for (final RenderEntry e : change.upserted()) {
                    changed |= put(e.key(), convert(e, vars));
                }
            }
            version = snapshot.version();
        }
        if (vars.version() != varsVersion) {
            varsVersion = vars.version();
            if (templated > 0) {
                changed |= resolveTemplates(vars);
            }
        }
        if (changed) {
            converted = flatten();
        }
        return converted;
    }

    /** Converts the entries with placeholders again; only those whose text now reads differently count. */
    private boolean resolveTemplates(final Variables vars) {
        boolean changed = false;
        for (int i = 0; i < items.size(); i++) {
            final Item old = items.get(i);
            if (!old.templated()) {
                continue;
            }
            final Item next = convert(old.entry(), vars);
            if (next != null && !sameText(old, next)) {
                items.set(i, next);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean sameText(final Item a, final Item b) {
        return textOf(a).equals(textOf(b));
    }

    private static String textOf(final Item item) {
        final DrawCommand c = item.command() != null ? item.command()
                : item.conditional() != null && !item.conditional().commands().isEmpty()
                        ? item.conditional().commands().get(0) : null;
        return switch (c) {
            case DrawCommand.Label l -> l.text();
            case DrawCommand.Waypoint w -> w.name();
            case null, default -> "";
        };
    }

    /** Replaces, inserts or removes the item of a key; returns whether anything changed. */
    private boolean put(final String key, final @Nullable Item item) {
        int lo = 0;
        int hi = items.size() - 1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            final int c = items.get(mid).key().compareTo(key);
            if (c == 0) {
                if (items.get(mid).templated()) {
                    templated--;
                }
                if (item == null) {
                    items.remove(mid);
                } else {
                    items.set(mid, item);
                    if (item.templated()) {
                        templated++;
                    }
                }
                return true;
            }
            if (c < 0) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (item == null) {
            return false;
        }
        add(lo, item);
        return true;
    }

    private void add(final int index, final Item item) {
        items.add(index, item);
        if (item.templated()) {
            templated++;
        }
    }

    private Converted flatten() {
        final List<DrawCommand> commands = new ArrayList<>(items.size());
        final List<EntityTag> tags = new ArrayList<>(0);
        final List<Conditional> conditional = new ArrayList<>(0);
        final List<Tracer> tracers = new ArrayList<>(0);
        boolean tagTracers = false;
        for (int i = 0; i < items.size(); i++) {
            final Item item = items.get(i);
            if (item.command() != null) {
                commands.add(item.command());
            }
            if (item.tag() != null) {
                tags.add(item.tag());
                tagTracers |= item.tag().tracer();
            }
            if (item.conditional() != null) {
                conditional.add(item.conditional());
            }
            if (item.tracer() != null) {
                tracers.add(item.tracer());
            }
        }
        // The command list keeps its identity when only the per-frame parts changed.
        final List<DrawCommand> list = sameInstances(commands, converted.commands())
                ? converted.commands() : List.copyOf(commands);
        return new Converted(list, List.copyOf(tags), List.copyOf(conditional), List.copyOf(tracers), tagTracers);
    }

    private static boolean sameInstances(final List<DrawCommand> a, final List<DrawCommand> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    /** Converts one entry, or returns null when it draws nothing in the immediate tier. */
    private @Nullable Item convert(final RenderEntry entry, final Variables vars) {
        final StyleData style = entry.style() != null ? entry.style() : DEFAULT_STYLE;
        DrawCommand command = null;
        EntityTag tag = null;
        Tracer tracer = null;
        boolean hasTemplate = false;
        switch (entry.data()) {
            case BoxData b -> command = new DrawCommand.Box(
                    vec(b.min()),
                    new Vec3d(b.min().x() + Math.max(0, b.size().x()),
                            b.min().y() + Math.max(0, b.size().y()),
                            b.min().z() + Math.max(0, b.size().z())),
                    rgba(style.color()), clamp01(style.fill()), depth(style));
            case LineData l -> command = new DrawCommand.Line(vec(l.from()), vec(l.to()), rgba(style.color()),
                    width(style), depth(style));
            case PathData p -> command = path(p, style);
            case LabelData l -> {
                hasTemplate = Templates.templated(l.text());
                command = new DrawCommand.Label(vec(l.pos()), templates.resolve(l.text(), entry.key(), vars),
                        l.scale() > 0 ? l.scale() : 1f, rgba(l.background()), l.maxDistance() > 0 ? l.maxDistance() : 64d);
            }
            case WaypointData w -> {
                hasTemplate = Templates.templated(w.name());
                command = new DrawCommand.Waypoint(vec(w.pos()), templates.resolve(w.name(), entry.key(), vars),
                        rgba(style.color()));
                if (w.tracer()) {
                    tracer = new Tracer(w.pos().x(), w.pos().y(), w.pos().z(), rgba(style.color()));
                }
            }
            case EntityTagData e -> tag = new EntityTag(e.entity(), e.text(),
                    rgba(e.outline() != null ? e.outline() : style.color()), e.tracer());
            case null, default -> {
                // Kinds without an immediate-tier renderer (extension kinds, later tiers) draw nothing here.
            }
        }
        Conditional conditional = null;
        final Visibility v = Traits.visibleWhen(entry.traits());
        if (v != null && Traits.perFrame(v) && command != null) {
            final ObjectBounds.Box b = ObjectBounds.of(entry.data());
            if (b != null) {
                conditional = new Conditional(entry.key(), (b.minX() + b.maxX()) / 2, (b.minY() + b.maxY()) / 2,
                        (b.minZ() + b.maxZ()) / 2, v, List.of(command));
            }
            command = null;
        }
        if (command == null && tag == null && conditional == null && tracer == null) {
            return null;
        }
        return new Item(entry, command, tag, conditional, tracer, hasTemplate);
    }

    private static @Nullable DrawCommand path(final PathData p, final StyleData style) {
        final double[] xyz = p.points();
        final int count = xyz.length / 3;
        if (count < 2) {
            return null;
        }
        final List<Vec3d> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(new Vec3d(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]));
        }
        return new DrawCommand.Polyline(points, rgba(style.color()), width(style), depth(style));
    }

    private static Vec3d vec(final io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d v) {
        return new Vec3d(v.x(), v.y(), v.z());
    }

    /** The wire carries 0xAARRGGBB; draw commands take 0xRRGGBBAA. */
    static int rgba(final int argb) {
        return (argb << 8) | (argb >>> 24);
    }

    private static float width(final StyleData style) {
        return style.lineWidth() > 0 ? style.lineWidth() : 2f;
    }

    private static float clamp01(final float v) {
        return Float.isNaN(v) ? 0f : Math.max(0f, Math.min(1f, v));
    }

    private static DepthMode depth(final StyleData style) {
        return switch (style.depth()) {
            case TEST -> DepthMode.TEST;
            case XRAY -> DepthMode.XRAY;
            case BOTH -> DepthMode.BOTH;
        };
    }
}
