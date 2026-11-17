package io.github.pix3lpirat3.mfbridge.common.spatial.logic;

import io.github.pix3lpirat3.mfbridge.common.render.command.DepthMode;
import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.common.seam.Vec3d;
import io.github.pix3lpirat3.mfbridge.core.spatial.SpatialIndex;
import io.github.pix3lpirat3.mfbridge.core.spatial.ZoneShape;
import io.github.pix3lpirat3.mfbridge.core.spatial.ZoneSurface;
import io.github.pix3lpirat3.mfbridge.protocol.core.PointData;
import io.github.pix3lpirat3.mfbridge.protocol.core.RouteData;
import io.github.pix3lpirat3.mfbridge.protocol.core.RouteNode;
import io.github.pix3lpirat3.mfbridge.protocol.core.ZoneData;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Draw commands for zones, points and routes. Zones become
 * their merged surface as translucent quads, an outline and a name label above the top; points a
 * through-walls marker with a label and a facing arrow; routes their interpolated polyline with a
 * small box per node.
 *
 * <p>Zone surfaces have a face count bounded by the number of boxes (a single box of any size is six
 * quads), so even the largest zones go through the immediate tier; the per-block retained tier
 * would mesh every boundary block instead.
 */
public final class SpatialShapes {

    /** How an object is drawn. */
    public enum Look {
        /** As declared. */
        NORMAL,
        /** Selected for editing: brighter outline. */
        SELECTED,
        /** An edit waiting for its bot owner's answer. */
        PENDING
    }

    /** Colour of pending edits, {@code 0xRRGGBBAA}. */
    public static final int PENDING_RGBA = 0xFFD040FF;

    private static final float EDGE_WIDTH = 2f;
    private static final float SELECTED_WIDTH = 3.5f;
    private static final double NODE_HALF = 0.12d;
    private static final double ARROW_LENGTH = 1.2d;
    private static final double LABEL_DISTANCE = 96d;
    private static final int LABEL_BACKGROUND = 0x00000050;
    private static final float ZONE_LABEL_SCALE = 1.5f;
    private static final float ROUTE_LABEL_SCALE = 1f;
    /** A point's label sits this far above it, drawn by the waypoint marker. */
    private static final double POINT_LABEL_OFFSET = 0.6;
    /** Blocks per font pixel at scale 1, as the renderer draws labels. */
    private static final double TEXT_SCALE = 0.025;

    private SpatialShapes() {
    }

    /** The wire carries {@code 0xAARRGGBB}; draw commands take {@code 0xRRGGBBAA}. */
    public static int rgba(final int argb) {
        return (argb << 8) | (argb >>> 24);
    }

    /**
     * Returns the commands for every indexed object except one.
     *
     * @param index the visible spatial definitions
     * @param hidden keys of objects drawn elsewhere: the one being edited and pending edits
     * @return a new list
     */
    public static List<DrawCommand> all(final SpatialIndex index, final Set<String> hidden) {
        final List<DrawCommand> out = new ArrayList<>();
        for (final SpatialIndex.Item item : index.all()) {
            if (!hidden.contains(item.key())) {
                add(out, item, Look.NORMAL);
            }
        }
        return out;
    }

    /**
     * Adds the commands of one object.
     *
     * @param out the frame's command list
     * @param item the object
     * @param look how to draw it
     */
    public static void add(final List<DrawCommand> out, final SpatialIndex.Item item, final Look look) {
        switch (item.data()) {
            case ZoneData z -> zone(out, item, z, look);
            case PointData p -> point(out, p, look);
            case RouteData r -> route(out, item, r, look);
            default -> {
                // Only the three spatial kinds are indexed.
            }
        }
    }

    private static int lineColor(final int argb, final Look look) {
        return switch (look) {
            case NORMAL, SELECTED -> rgba(argb) | 0xFF;
            case PENDING -> PENDING_RGBA;
        };
    }

    private static float width(final Look look) {
        return look == Look.NORMAL ? EDGE_WIDTH : SELECTED_WIDTH;
    }

    private static void zone(final List<DrawCommand> out, final SpatialIndex.Item item, final ZoneData z, final Look look) {
        final ZoneShape shape = item.zone();
        if (shape == null) {
            return;
        }
        final ZoneSurface surface = ZoneSurface.of(shape);
        final int line = lineColor(z.color(), look);
        final float fill = Float.isNaN(z.fill()) ? 0f : Math.min(1f, z.fill());
        if (fill > 0f) {
            final double[] q = surface.quads();
            for (int i = 0; i + 11 < q.length; i += 12) {
                out.add(new DrawCommand.Quad(new Vec3d(q[i], q[i + 1], q[i + 2]), new Vec3d(q[i + 3], q[i + 4], q[i + 5]),
                        new Vec3d(q[i + 6], q[i + 7], q[i + 8]), new Vec3d(q[i + 9], q[i + 10], q[i + 11]), line, fill,
                        DepthMode.TEST));
            }
        }
        final double[] e = surface.edges();
        final float w = width(look);
        for (int i = 0; i + 5 < e.length; i += 6) {
            out.add(new DrawCommand.Line(new Vec3d(e[i], e[i + 1], e[i + 2]), new Vec3d(e[i + 3], e[i + 4], e[i + 5]), line, w,
                    DepthMode.BOTH));
        }
        final double[] at = labelAt(item);
        if (at != null) {
            out.add(new DrawCommand.Label(new Vec3d(at[0], at[1], at[2]), look == Look.PENDING ? z.name() + " (pending)" : z.name(),
                    ZONE_LABEL_SCALE, LABEL_BACKGROUND, LABEL_DISTANCE));
        }
    }

    private static void point(final List<DrawCommand> out, final PointData p, final Look look) {
        final Vec3d pos = new Vec3d(p.pos().x(), p.pos().y(), p.pos().z());
        final int color = lineColor(p.color(), look);
        out.add(new DrawCommand.Waypoint(pos, look == Look.PENDING ? p.name() + " (pending)" : p.name(), color));
        final Float yaw = p.yaw();
        if (yaw != null) {
            final double r = Math.toRadians(yaw);
            out.add(new DrawCommand.Line(pos, pos.add(-Math.sin(r) * ARROW_LENGTH, 0, Math.cos(r) * ARROW_LENGTH), color,
                    width(look), DepthMode.BOTH));
        }
    }

    private static void route(final List<DrawCommand> out, final SpatialIndex.Item item, final RouteData r, final Look look) {
        final double[] line = item.polyline();
        final int color = lineColor(r.color(), look);
        final float w = look == Look.NORMAL ? (r.width() > 0f ? r.width() : EDGE_WIDTH) : Math.max(SELECTED_WIDTH, r.width());
        if (line != null && line.length >= 6) {
            final List<Vec3d> points = new ArrayList<>(line.length / 3);
            for (int i = 0; i + 2 < line.length; i += 3) {
                points.add(new Vec3d(line[i], line[i + 1], line[i + 2]));
            }
            out.add(new DrawCommand.Polyline(points, color, w, DepthMode.BOTH));
        }
        final List<RouteNode> nodes = r.nodes();
        for (final RouteNode n : nodes) {
            out.add(DrawCommand.Box.cube(n.pos().x(), n.pos().y(), n.pos().z(), NODE_HALF, color, 0.5f, DepthMode.BOTH));
        }
        final double[] at = labelAt(item);
        if (at != null) {
            out.add(new DrawCommand.Label(new Vec3d(at[0], at[1], at[2]), look == Look.PENDING ? r.name() + " (pending)" : r.name(),
                    ROUTE_LABEL_SCALE, LABEL_BACKGROUND, LABEL_DISTANCE));
        }
    }

    /**
     * Where an object's name label is drawn: x, y, z of its centre and its scale. A zone's sits
     * above its top, a route's above its first node, a point's above the marker.
     *
     * @return the label, or null when the object shows none
     */
    public static double @Nullable [] labelAt(final SpatialIndex.Item item) {
        switch (item.data()) {
            case ZoneData z -> {
                if (!z.showLabel() || z.name().isEmpty() || item.zone() == null) {
                    return null;
                }
                return new double[] {(item.minX() + item.maxX()) / 2, Math.min(item.maxY(), ZoneSurface.DRAW_MAX_Y + 1d) + 0.5,
                    (item.minZ() + item.maxZ()) / 2, ZONE_LABEL_SCALE};
            }
            case RouteData r -> {
                if (r.nodes().isEmpty() || r.name().isEmpty()) {
                    return null;
                }
                final RouteNode first = r.nodes().getFirst();
                return new double[] {first.pos().x(), first.pos().y() + 0.6, first.pos().z(), ROUTE_LABEL_SCALE};
            }
            case PointData p -> {
                return p.name().isEmpty() ? null : new double[] {p.pos().x(), p.pos().y() + POINT_LABEL_OFFSET, p.pos().z(), 1d};
            }
            default -> {
                return null;
            }
        }
    }

    /** Half the width of a label in blocks, background included; font glyphs are about 6 pixels wide. */
    public static double labelHalfWidth(final String text, final double scale) {
        return (text.length() * 3 + 2) * TEXT_SCALE * scale + 0.1;
    }

    /** Half the height of a label in blocks, background included. */
    public static double labelHalfHeight(final double scale) {
        return 6 * TEXT_SCALE * scale + 0.1;
    }
}
