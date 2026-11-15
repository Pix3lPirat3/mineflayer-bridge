package io.github.pix3lpirat3.mfbridge.common.render.cull;

import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.common.seam.Vec3d;
import java.util.List;

/** Bounding spheres of draw commands, for culling before any vertex is written. */
public final class ShapeBounds {
    /** Radius around a waypoint that holds its marker, in blocks. */
    public static final double WAYPOINT_RADIUS = 0.5;

    private ShapeBounds() {
    }

    /**
     * Writes the bounding sphere of a command.
     *
     * @param command the command
     * @param out receives centre x, y, z and the radius at {@code offset} to {@code offset + 3}. A
     *     label gets its anchor with radius 0, since only the caller knows how wide the text is
     * @param offset first index to write
     */
    public static void sphere(final DrawCommand command, final double[] out, final int offset) {
        switch (command) {
            case DrawCommand.Box box -> between(box.min(), box.max(), out, offset);
            case DrawCommand.Line line -> between(line.a(), line.b(), out, offset);
            case DrawCommand.Quad quad -> {
                final double x = (quad.a().x() + quad.b().x() + quad.c().x() + quad.d().x()) / 4;
                final double y = (quad.a().y() + quad.b().y() + quad.c().y() + quad.d().y()) / 4;
                final double z = (quad.a().z() + quad.b().z() + quad.c().z() + quad.d().z()) / 4;
                final double r2 = Math.max(Math.max(quad.a().distanceSquared(x, y, z), quad.b().distanceSquared(x, y, z)),
                        Math.max(quad.c().distanceSquared(x, y, z), quad.d().distanceSquared(x, y, z)));
                put(out, offset, x, y, z, Math.sqrt(r2));
            }
            case DrawCommand.Polyline polyline -> {
                final List<Vec3d> points = polyline.points();
                double minX = Double.POSITIVE_INFINITY;
                double minY = Double.POSITIVE_INFINITY;
                double minZ = Double.POSITIVE_INFINITY;
                double maxX = Double.NEGATIVE_INFINITY;
                double maxY = Double.NEGATIVE_INFINITY;
                double maxZ = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < points.size(); i++) {
                    final Vec3d p = points.get(i);
                    minX = Math.min(minX, p.x());
                    minY = Math.min(minY, p.y());
                    minZ = Math.min(minZ, p.z());
                    maxX = Math.max(maxX, p.x());
                    maxY = Math.max(maxY, p.y());
                    maxZ = Math.max(maxZ, p.z());
                }
                final double dx = maxX - minX;
                final double dy = maxY - minY;
                final double dz = maxZ - minZ;
                put(out, offset, (minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2,
                        Math.sqrt(dx * dx + dy * dy + dz * dz) / 2);
            }
            case DrawCommand.Label label -> put(out, offset, label.pos().x(), label.pos().y(), label.pos().z(), 0);
            case DrawCommand.Waypoint waypoint -> put(out, offset, waypoint.pos().x(), waypoint.pos().y(),
                    waypoint.pos().z(), WAYPOINT_RADIUS);
        }
    }

    private static void between(final Vec3d a, final Vec3d b, final double[] out, final int offset) {
        put(out, offset, (a.x() + b.x()) / 2, (a.y() + b.y()) / 2, (a.z() + b.z()) / 2,
                Math.sqrt(a.distanceSquared(b.x(), b.y(), b.z())) / 2);
    }

    private static void put(final double[] out, final int offset, final double x, final double y, final double z,
            final double radius) {
        out[offset] = x;
        out[offset + 1] = y;
        out[offset + 2] = z;
        // A shape with a coordinate that is not a number is never culled.
        out[offset + 3] = Double.isFinite(x + y + z + radius) ? radius : Double.POSITIVE_INFINITY;
    }
}
