package io.github.pix3lpirat3.mfbridge.common.render.cull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.common.render.command.DepthMode;
import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.common.seam.Vec3d;
import java.util.List;
import java.util.Random;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class ViewVolumeTest {
    private static final double CAM_X = 1000.5;
    private static final double CAM_Y = 70;
    private static final double CAM_Z = -250.25;

    /** The corners the way the game's frustum reports them: clip space corners through the inverse matrix. */
    private static float[] corners(final Matrix4f viewProjection) {
        final float[][] clip = {
            {-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1}, {-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1}};
        final Matrix4f inverse = viewProjection.invert(new Matrix4f());
        final float[] out = new float[24];
        for (int i = 0; i < 8; i++) {
            final Vector4f p = inverse.transform(new Vector4f(clip[i][0], clip[i][1], clip[i][2], 1f));
            p.div(p.w());
            out[i * 3] = p.x();
            out[i * 3 + 1] = p.y();
            out[i * 3 + 2] = p.z();
        }
        return out;
    }

    private static Matrix4f lookingNorth() {
        // A camera at the origin looking along -z, 70 degrees, 0.05 to 512 blocks.
        return new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 512f);
    }

    @Test
    void everythingIsVisibleUntilSet() {
        final ViewVolume v = new ViewVolume();
        assertTrue(v.sphere(1e9, 0, 0, 0));
        assertTrue(v.box(5, 5, 5, 6, 6, 6));
    }

    @Test
    void spheresAndBoxesAreCulledByEachPlane() {
        final ViewVolume v = new ViewVolume();
        v.set(corners(lookingNorth()), CAM_X, CAM_Y, CAM_Z);
        assertTrue(v.sphere(CAM_X, CAM_Y, CAM_Z - 10, 0.5), "straight ahead");
        assertFalse(v.sphere(CAM_X, CAM_Y, CAM_Z + 10, 0.5), "behind the camera");
        assertTrue(v.sphere(CAM_X, CAM_Y, CAM_Z + 10, 11), "behind, but large enough to reach in");
        assertFalse(v.sphere(CAM_X, CAM_Y, CAM_Z - 600, 1), "past the far plane");
        assertFalse(v.sphere(CAM_X + 100, CAM_Y, CAM_Z - 10, 1), "far to the right");
        assertFalse(v.sphere(CAM_X - 100, CAM_Y, CAM_Z - 10, 1), "far to the left");
        assertFalse(v.sphere(CAM_X, CAM_Y + 100, CAM_Z - 10, 1), "far above");
        assertFalse(v.sphere(CAM_X, CAM_Y - 100, CAM_Z - 10, 1), "far below");

        assertTrue(v.box(CAM_X - 1, CAM_Y - 1, CAM_Z - 20, CAM_X + 1, CAM_Y + 1, CAM_Z - 18));
        assertFalse(v.box(CAM_X - 1, CAM_Y - 1, CAM_Z + 18, CAM_X + 1, CAM_Y + 1, CAM_Z + 20));
        assertTrue(v.box(CAM_X - 8, CAM_Y - 8, CAM_Z - 8, CAM_X + 8, CAM_Y + 8, CAM_Z + 8), "the section around the camera");
        assertFalse(v.box(CAM_X + 200, CAM_Y, CAM_Z - 32, CAM_X + 216, CAM_Y + 16, CAM_Z - 16));
    }

    @Test
    void agreesWithJomlAndNeverHidesWhatJomlShows() {
        final Matrix4f m = lookingNorth().rotateX(0.3f).rotateY(-1.1f);
        final ViewVolume v = new ViewVolume();
        v.set(corners(m), CAM_X, CAM_Y, CAM_Z);
        final FrustumIntersection joml = new FrustumIntersection(m);
        final Random random = new Random(42);
        int agreed = 0;
        for (int i = 0; i < 20_000; i++) {
            final float x = (random.nextFloat() - 0.5f) * 800;
            final float y = (random.nextFloat() - 0.5f) * 800;
            final float z = (random.nextFloat() - 0.5f) * 800;
            final float r = random.nextFloat() * 20;
            final boolean expected = joml.testSphere(x, y, z, r);
            final boolean actual = v.sphere(CAM_X + x, CAM_Y + y, CAM_Z + z, r);
            if (expected) {
                // Float error on the far corners may let a sphere on a plane pass, never the reverse.
                assertTrue(actual || !joml.testSphere(x, y, z, r - 0.05f), "sphere " + i + " was hidden");
            }
            if (expected == actual) {
                agreed++;
            }
            final boolean boxExpected = joml.testAab(x, y, z, x + 16, y + 16, z + 16);
            final boolean boxActual = v.box(CAM_X + x, CAM_Y + y, CAM_Z + z, CAM_X + x + 16, CAM_Y + y + 16, CAM_Z + z + 16);
            if (boxExpected) {
                assertTrue(boxActual || !joml.testAab(x + 0.05f, y + 0.05f, z + 0.05f, x + 15.95f, y + 15.95f, z + 15.95f),
                        "box " + i + " was hidden");
            }
        }
        assertTrue(agreed > 19_900, "agreement with joml: " + agreed);
    }

    @Test
    void cornersThatCannotBeTrustedCullNothing() {
        final ViewVolume v = new ViewVolume();
        final float[] infinite = corners(lookingNorth());
        infinite[23] = Float.POSITIVE_INFINITY;
        v.set(infinite, 0, 0, 0);
        assertTrue(v.sphere(0, 0, 100, 1), "a far face at infinity");

        final float[] mirrored = corners(lookingNorth());
        // A far face behind the eye, as a projection with another depth range would give.
        for (int k = 12; k < 24; k++) {
            mirrored[k] = -mirrored[k];
        }
        v.set(mirrored, 0, 0, 0);
        assertTrue(v.sphere(0, 0, -100, 1), "far face behind the eye");
        assertTrue(v.sphere(0, 0, 100, 1));

        final float[] flat = new float[24];
        v.set(flat, 0, 0, 0);
        assertTrue(v.sphere(0, 0, 100, 1), "no volume at all");

        v.set(corners(lookingNorth()), 0, 0, 0);
        assertFalse(v.sphere(0, 0, 100, 1));
        v.clear();
        assertTrue(v.sphere(0, 0, 100, 1));
    }

    @Test
    void boundingSpheresHoldTheirShapes() {
        final double[] s = new double[8];
        ShapeBounds.sphere(new DrawCommand.Box(new Vec3d(0, 0, 0), new Vec3d(2, 4, 4), 0xFFFFFFFF, 0f, DepthMode.TEST), s, 4);
        assertEquals(1, s[4]);
        assertEquals(2, s[5]);
        assertEquals(2, s[6]);
        assertEquals(3, s[7], 1e-9);
        ShapeBounds.sphere(new DrawCommand.Polyline(List.of(new Vec3d(0, 0, 0), new Vec3d(10, 0, 0), new Vec3d(10, 0, 10)),
                0xFFFFFFFF, 2f, DepthMode.TEST), s, 0);
        assertEquals(5, s[0]);
        assertEquals(5, s[2]);
        for (final Vec3d p : List.of(new Vec3d(0, 0, 0), new Vec3d(10, 0, 0), new Vec3d(10, 0, 10))) {
            assertTrue(Math.sqrt(p.distanceSquared(s[0], s[1], s[2])) <= s[3] + 1e-9);
        }
        ShapeBounds.sphere(new DrawCommand.Line(new Vec3d(Double.NaN, 0, 0), new Vec3d(1, 1, 1), 0xFFFFFFFF, 2f, DepthMode.TEST), s, 0);
        assertEquals(Double.POSITIVE_INFINITY, s[3], "a shape that cannot be placed is never culled");
        ShapeBounds.sphere(new DrawCommand.Label(new Vec3d(1, 2, 3), "hi", 1f, 0, 64), s, 0);
        assertEquals(0, s[3]);
        ShapeBounds.sphere(new DrawCommand.Waypoint(new Vec3d(1, 2, 3), "home", 0xFFFFFFFF), s, 0);
        assertEquals(ShapeBounds.WAYPOINT_RADIUS, s[3]);
    }
}
