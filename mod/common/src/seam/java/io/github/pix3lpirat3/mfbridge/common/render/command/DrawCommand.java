package io.github.pix3lpirat3.mfbridge.common.render.command;

import io.github.pix3lpirat3.mfbridge.common.seam.Vec3d;
import java.util.List;

/**
 * One immediate-tier shape for one frame. Instances are immutable and safe to share across threads.
 */
public sealed interface DrawCommand {

    /**
     * An axis-aligned box drawn as edges plus an optional translucent fill.
     *
     * @param min minimum corner
     * @param max maximum corner; each coordinate at least the matching {@code min} coordinate
     * @param rgba edge colour
     * @param fillAlpha alpha of the face fill in {@code [0, 1]}, 0 meaning no fill; the fill uses the
     *     edge colour's RGB
     * @param depthMode depth behaviour
     */
    record Box(Vec3d min, Vec3d max, int rgba, float fillAlpha, DepthMode depthMode) implements DrawCommand {

        /**
         * Validates the corners and fill alpha.
         *
         * @throws IllegalArgumentException if {@code max} is below {@code min} or the alpha is out of range
         */
        public Box {
            if (max.x() < min.x() || max.y() < min.y() || max.z() < min.z()) {
                throw new IllegalArgumentException("box max " + max + " is below min " + min);
            }
            if (!(fillAlpha >= 0f && fillAlpha <= 1f)) {
                throw new IllegalArgumentException("fillAlpha must be in [0, 1]: " + fillAlpha);
            }
        }

        /**
         * Creates a cube around a centre.
         *
         * @param x centre x, in blocks
         * @param y centre y, in blocks
         * @param z centre z, in blocks
         * @param half half the edge length, in blocks; not negative
         * @param rgba edge colour
         * @param fillAlpha alpha of the face fill in {@code [0, 1]}
         * @param depthMode depth behaviour
         * @return the box
         */
        public static Box cube(final double x, final double y, final double z, final double half, final int rgba, final float fillAlpha,
                final DepthMode depthMode) {
            return new Box(new Vec3d(x - half, y - half, z - half), new Vec3d(x + half, y + half, z + half), rgba, fillAlpha, depthMode);
        }
    }

    /**
     * A translucent quad, for surfaces that are not whole boxes such as merged zone volumes. A
     * triangle repeats its third corner as {@code d}. The quad is drawn only in the fill passes.
     *
     * @param a first corner
     * @param b second corner
     * @param c third corner
     * @param d fourth corner, in winding order
     * @param rgba colour; its alpha is ignored in favour of {@code fillAlpha}
     * @param fillAlpha alpha in {@code (0, 1]}
     * @param depthMode depth behaviour
     */
    record Quad(Vec3d a, Vec3d b, Vec3d c, Vec3d d, int rgba, float fillAlpha, DepthMode depthMode) implements DrawCommand {

        /**
         * Validates the alpha.
         *
         * @throws IllegalArgumentException if the alpha is out of range
         */
        public Quad {
            if (!(fillAlpha > 0f && fillAlpha <= 1f)) {
                throw new IllegalArgumentException("fillAlpha must be in (0, 1]: " + fillAlpha);
            }
        }
    }

    /**
     * A straight line segment.
     *
     * @param a start point
     * @param b end point
     * @param rgba line colour
     * @param width line width, in screen pixels; positive
     * @param depthMode depth behaviour
     */
    record Line(Vec3d a, Vec3d b, int rgba, float width, DepthMode depthMode) implements DrawCommand {

        /**
         * Validates the width.
         *
         * @throws IllegalArgumentException if the width is not positive
         */
        public Line {
            requirePositiveWidth(width);
        }
    }

    /**
     * Connected line segments through a list of points.
     *
     * @param points at least two points, copied into an immutable list
     * @param rgba line colour
     * @param width line width, in screen pixels; positive
     * @param depthMode depth behaviour
     */
    record Polyline(List<Vec3d> points, int rgba, float width, DepthMode depthMode) implements DrawCommand {

        /**
         * Validates and copies the points.
         *
         * @throws IllegalArgumentException if fewer than two points are given or the width is not positive
         */
        public Polyline {
            points = List.copyOf(points);
            if (points.size() < 2) {
                throw new IllegalArgumentException("a polyline needs at least two points");
            }
            requirePositiveWidth(width);
        }
    }

    /**
     * Billboard text that always faces the camera.
     *
     * @param pos anchor point; the text is centred on it
     * @param text literal text
     * @param scale size multiplier, 1 being the size of a vanilla name tag; positive
     * @param backgroundRgba background colour, alpha 0 for none
     * @param maxDistance beyond this camera distance the label is not drawn, in blocks; positive
     */
    record Label(Vec3d pos, String text, float scale, int backgroundRgba, double maxDistance) implements DrawCommand {

        /**
         * Validates scale and distance.
         *
         * @throws IllegalArgumentException if the scale or distance is not positive
         */
        public Label {
            if (!(scale > 0f)) {
                throw new IllegalArgumentException("scale must be positive: " + scale);
            }
            if (!(maxDistance > 0d)) {
                throw new IllegalArgumentException("maxDistance must be positive: " + maxDistance);
            }
        }
    }

    /**
     * A named point drawn as a through-walls marker with a name label.
     *
     * @param pos marker position
     * @param name label text
     * @param rgba marker colour
     */
    record Waypoint(Vec3d pos, String name, int rgba) implements DrawCommand {
    }

    private static void requirePositiveWidth(final float width) {
        if (!(width > 0f)) {
            throw new IllegalArgumentException("width must be positive: " + width);
        }
    }
}
