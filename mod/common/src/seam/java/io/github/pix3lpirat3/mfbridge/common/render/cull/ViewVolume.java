package io.github.pix3lpirat3.mfbridge.common.render.cull;

/**
 * What the camera can see this frame, as up to six planes built from the corners of the view
 * frustum. Tests take world coordinates and allocate nothing. Until {@link #set} is called with
 * usable corners, everything counts as visible, so a frustum this class cannot read never hides
 * anything.
 *
 * <p>Render thread only; one instance is reused for every frame.
 */
public final class ViewVolume {
    /** Corner indexes of the six faces: near, far, left, right, bottom, top. */
    private static final int[][] FACES = {{0, 1, 2}, {4, 5, 6}, {0, 3, 4}, {1, 2, 5}, {0, 1, 4}, {2, 3, 6}};
    private static final double EPSILON = 1.0e-9;
    private static final double TOLERANCE = 1.0e-3;

    /** Unit normal and offset of each plane; a point is inside when {@code n.p + d >= 0}. */
    private final double[] planes = new double[24];
    private int count;
    private double originX;
    private double originY;
    private double originZ;

    /** Creates a volume that contains everything. */
    public ViewVolume() {
    }

    /** Makes everything visible again. */
    public void clear() {
        count = 0;
    }

    /**
     * Sets the volume from the eight frustum corners.
     *
     * @param corners x, y and z of each corner relative to the origin: the near face at {@code
     *     (-1,-1), (1,-1), (1,1), (-1,1)} of clip space, then the far face in the same order
     * @param newOriginX world x the corners are relative to
     * @param newOriginY world y the corners are relative to
     * @param newOriginZ world z the corners are relative to
     */
    public void set(final float[] corners, final double newOriginX, final double newOriginY, final double newOriginZ) {
        this.originX = newOriginX;
        this.originY = newOriginY;
        this.originZ = newOriginZ;
        count = 0;
        double reach = 0;
        for (int i = 0; i < 24; i++) {
            if (!Float.isFinite(corners[i])) {
                // A far face at infinity gives no usable centre; cull nothing.
                return;
            }
            reach = Math.max(reach, Math.abs(corners[i]));
        }
        double cx = 0;
        double cy = 0;
        double cz = 0;
        for (int i = 0; i < 8; i++) {
            cx += corners[i * 3] / 8d;
            cy += corners[i * 3 + 1] / 8d;
            cz += corners[i * 3 + 2] / 8d;
        }
        // Seen from the origin the far face lies beyond the near face, in the same direction. Corners
        // of a projection this class does not know (far face behind the eye) fail this: cull nothing.
        double nearX = 0;
        double nearY = 0;
        double nearZ = 0;
        double farX = 0;
        double farY = 0;
        double farZ = 0;
        for (int i = 0; i < 4; i++) {
            nearX += corners[i * 3];
            nearY += corners[i * 3 + 1];
            nearZ += corners[i * 3 + 2];
            farX += corners[12 + i * 3];
            farY += corners[12 + i * 3 + 1];
            farZ += corners[12 + i * 3 + 2];
        }
        final double nearSq = nearX * nearX + nearY * nearY + nearZ * nearZ;
        final double farSq = farX * farX + farY * farY + farZ * farZ;
        if (!(nearX * farX + nearY * farY + nearZ * farZ > 0) || !(farSq > nearSq)) {
            return;
        }
        for (final int[] face : FACES) {
            final int a = face[0] * 3;
            final int b = face[1] * 3;
            final int c = face[2] * 3;
            final double ux = corners[b] - corners[a];
            final double uy = corners[b + 1] - corners[a + 1];
            final double uz = corners[b + 2] - corners[a + 2];
            final double vx = corners[c] - corners[a];
            final double vy = corners[c + 1] - corners[a + 1];
            final double vz = corners[c + 2] - corners[a + 2];
            double nx = uy * vz - uz * vy;
            double ny = uz * vx - ux * vz;
            double nz = ux * vy - uy * vx;
            final double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (!(length > EPSILON)) {
                continue;
            }
            nx /= length;
            ny /= length;
            nz /= length;
            double d = -(nx * corners[a] + ny * corners[a + 1] + nz * corners[a + 2]);
            // The centre of the frustum is inside every plane.
            if (nx * cx + ny * cy + nz * cz + d < 0) {
                nx = -nx;
                ny = -ny;
                nz = -nz;
                d = -d;
            }
            final int p = count++ * 4;
            planes[p] = nx;
            planes[p + 1] = ny;
            planes[p + 2] = nz;
            planes[p + 3] = d;
        }
        // Corners that do not form a convex volume cannot be trusted: cull nothing.
        for (int i = 0; i < 8; i++) {
            for (int k = 0; k < count; k++) {
                final int p = k * 4;
                final double side = planes[p] * corners[i * 3] + planes[p + 1] * corners[i * 3 + 1]
                        + planes[p + 2] * corners[i * 3 + 2] + planes[p + 3];
                if (side < -TOLERANCE * (1 + reach)) {
                    count = 0;
                    return;
                }
            }
        }
    }

    /**
     * Returns whether a sphere may be in view.
     *
     * @param x centre, world blocks
     * @param y centre
     * @param z centre
     * @param radius radius, blocks
     * @return {@code false} only when the sphere is wholly outside one plane
     */
    public boolean sphere(final double x, final double y, final double z, final double radius) {
        final double px = x - originX;
        final double py = y - originY;
        final double pz = z - originZ;
        for (int i = 0; i < count; i++) {
            final int p = i * 4;
            if (planes[p] * px + planes[p + 1] * py + planes[p + 2] * pz + planes[p + 3] < -radius) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether an axis-aligned box may be in view.
     *
     * @param minX lower corner, world blocks
     * @param minY lower corner
     * @param minZ lower corner
     * @param maxX upper corner
     * @param maxY upper corner
     * @param maxZ upper corner
     * @return {@code false} only when the box is wholly outside one plane
     */
    public boolean box(final double minX, final double minY, final double minZ, final double maxX, final double maxY,
            final double maxZ) {
        for (int i = 0; i < count; i++) {
            final int p = i * 4;
            final double nx = planes[p];
            final double ny = planes[p + 1];
            final double nz = planes[p + 2];
            // The corner farthest along the normal decides.
            final double x = (nx >= 0 ? maxX : minX) - originX;
            final double y = (ny >= 0 ? maxY : minY) - originY;
            final double z = (nz >= 0 ? maxZ : minZ) - originZ;
            if (nx * x + ny * y + nz * z + planes[p + 3] < 0) {
                return false;
            }
        }
        return true;
    }
}
