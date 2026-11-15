package io.github.pix3lpirat3.mfbridge.common.render.retained;

import net.minecraft.client.renderer.DynamicGpuData;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Builds the per-draw {@code DynamicTransforms} value of one retained section.
 *
 * <p>The game's mapped uniform storage (26.3 {@code DynamicGpuDataStorageMapped.writeData}) keeps
 * the last written {@link DynamicGpuData.Transform} by reference and returns the previous slice when
 * the next value {@code equals} it. A transform built over reused mutable matrices therefore equals
 * its predecessor after every mutation, and each section after the first in a run would draw with
 * the first section's origin and colour. Every transform returned here owns fresh copies, so
 * consecutive draws differ whenever their values do.
 */
final class SectionTransforms {
    private static final Vector3fc NO_OFFSET = new Vector3f();

    private SectionTransforms() {
    }

    /**
     * Returns the transform of a section whose mesh positions are relative to its origin.
     *
     * @param view the camera's model view; copied, not retained
     * @param dx section origin minus camera x, in blocks
     * @param dy section origin minus camera y, in blocks
     * @param dz section origin minus camera z, in blocks
     * @param uv heatmap colormap transform {@code [scale, offset, row v]}, or null for identity
     */
    static DynamicGpuData.Transform section(final Matrix4fc view, final float dx, final float dy, final float dz,
            final float r, final float g, final float b, final float alpha, final float @Nullable [] uv) {
        final Matrix4f modelView = new Matrix4f(view).translate(dx, dy, dz);
        final Matrix4f texture = new Matrix4f();
        if (uv != null) {
            // u' = scale * value + offset; v' = the colormap row, whatever the vertex v.
            texture.m00(uv[0]).m30(uv[1]).m11(0f).m31(uv[2]);
        }
        return new DynamicGpuData.Transform(modelView, new Vector4f(r, g, b, alpha), NO_OFFSET, texture);
    }
}
