package io.github.pix3lpirat3.mfbridge.common.seam;

/**
 * An immutable point or vector in world space.
 *
 * @param x east-west coordinate, in blocks
 * @param y vertical coordinate, in blocks
 * @param z north-south coordinate, in blocks
 */
public record Vec3d(double x, double y, double z) {

    /**
     * Returns this point moved by the given offsets.
     *
     * @param dx offset along x, in blocks
     * @param dy offset along y, in blocks
     * @param dz offset along z, in blocks
     * @return a new point
     */
    public Vec3d add(final double dx, final double dy, final double dz) {
        return new Vec3d(x + dx, y + dy, z + dz);
    }

    /**
     * Returns the squared distance to a point.
     *
     * @param px other x, in blocks
     * @param py other y, in blocks
     * @param pz other z, in blocks
     * @return squared distance, in blocks squared
     */
    public double distanceSquared(final double px, final double py, final double pz) {
        final double dx = x - px;
        final double dy = y - py;
        final double dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }
}
