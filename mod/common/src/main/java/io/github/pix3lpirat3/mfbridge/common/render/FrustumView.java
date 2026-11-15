package io.github.pix3lpirat3.mfbridge.common.render;

import io.github.pix3lpirat3.mfbridge.common.render.cull.ViewVolume;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Reads the game's cull frustum into a {@link ViewVolume} once per frame, so the many tests of a
 * frame run on plain numbers and allocate nothing.
 */
public final class FrustumView {
    private final ViewVolume volume = new ViewVolume();
    private final float[] corners = new float[24];

    /** Creates a view that shows everything until {@link #look} is called. */
    public FrustumView() {
    }

    /**
     * Takes the frustum of this frame.
     *
     * @param frustum the game's cull frustum, or null to cull nothing
     * @return the volume to test against, valid until the next call
     */
    public ViewVolume look(final @Nullable Frustum frustum) {
        if (frustum == null) {
            volume.clear();
            return volume;
        }
        final Vector4f[] points = frustum.getFrustumPoints();
        for (int i = 0; i < 8; i++) {
            corners[i * 3] = points[i].x();
            corners[i * 3 + 1] = points[i].y();
            corners[i * 3 + 2] = points[i].z();
        }
        volume.set(corners, frustum.getCamX(), frustum.getCamY(), frustum.getCamZ());
        return volume;
    }
}
