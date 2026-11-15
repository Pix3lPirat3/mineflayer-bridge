package io.github.pix3lpirat3.mfbridge.common.spatial.logic;

import io.github.pix3lpirat3.mfbridge.core.spatial.GizmoMath;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import java.util.Locale;

/**
 * The measurements of {@code bridge:ruler} between two blocks: straight-line distance
 * between their centres, Manhattan distance, the size of the box they span, and the yaw and pitch
 * from the first to the second in Minecraft's convention (pitch positive looking down).
 *
 * @param euclidean distance between the block centres, in blocks
 * @param manhattan sum of the per-axis block differences
 * @param sizeX width of the spanned box, both blocks included
 * @param sizeY height of the spanned box
 * @param sizeZ depth of the spanned box
 * @param yaw yaw from the first block towards the second, in degrees within {@code (-180, 180]}
 * @param pitch pitch from the first block towards the second, in degrees
 */
public record Ruler(double euclidean, long manhattan, int sizeX, int sizeY, int sizeZ, double yaw, double pitch) {

    /** Measures from block {@code a} to block {@code b}. */
    public static Ruler measure(final Vec3i a, final Vec3i b) {
        final long dx = (long) b.x() - a.x();
        final long dy = (long) b.y() - a.y();
        final long dz = (long) b.z() - a.z();
        final double horizontal = Math.sqrt((double) dx * dx + (double) dz * dz);
        double yaw = dx == 0 && dz == 0 ? 0 : GizmoMath.yawTowards(0, 0, dx, dz);
        if (yaw <= -180d) {
            yaw += 360d;
        }
        // Adding 0 turns a negative zero into a positive one, so labels never read "-0.0".
        yaw += 0d;
        final double pitch = (dx == 0 && dy == 0 && dz == 0 ? 0 : -Math.toDegrees(Math.atan2(dy, horizontal))) + 0d;
        return new Ruler(Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz), Math.abs(dx) + Math.abs(dy) + Math.abs(dz),
                (int) Math.abs(dx) + 1, (int) Math.abs(dy) + 1, (int) Math.abs(dz) + 1, yaw, pitch);
    }

    /** Returns the label text, for example {@code "12.4 m | 17 manhattan | 9x3x8 | yaw 45.0 pitch -10.2"}. */
    public String label() {
        return String.format(Locale.ROOT, "%.1f m | %d manhattan | %dx%dx%d | yaw %.1f pitch %.1f", euclidean, manhattan, sizeX,
                sizeY, sizeZ, yaw, pitch);
    }
}
