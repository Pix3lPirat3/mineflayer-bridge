package io.github.pix3lpirat3.mfbridge.common.render.retained;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.minecraft.client.renderer.DynamicGpuData;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class SectionTransformsTest {

    /**
     * The game's uniform storage reuses the previous slice when the next transform equals the last
     * one it kept by reference; two sections of one object drawn in a row must not compare equal.
     */
    @Test
    void consecutiveSectionsKeepTheirOwnOrigins() {
        final Matrix4f view = new Matrix4f().rotateY(1f);
        final DynamicGpuData.Transform first = SectionTransforms.section(view, 0f - 4.5f, -64f, 0f, 1f, 1f, 1f, 1f, null);
        final Vector3f firstOrigin = first.modelView().transformPosition(new Vector3f());
        final DynamicGpuData.Transform second = SectionTransforms.section(view, -16f - 4.5f, -64f, 0f, 1f, 1f, 1f, 1f,
                null);
        assertNotEquals(first, second, "the storage would hand the second section the first one's slice");
        assertEquals(firstOrigin, first.modelView().transformPosition(new Vector3f()),
                "building the next transform must not change one already written");
        view.identity();
        assertEquals(firstOrigin, first.modelView().transformPosition(new Vector3f()), "the view is copied");
    }

    @Test
    void colourAndColormapAreSnapshots() {
        final Matrix4f view = new Matrix4f();
        final DynamicGpuData.Transform dim = SectionTransforms.section(view, 0f, 0f, 0f, 1f, 1f, 1f, 0.4f, null);
        final DynamicGpuData.Transform full = SectionTransforms.section(view, 0f, 0f, 0f, 1f, 1f, 1f, 1f, null);
        assertNotEquals(dim, full);
        assertEquals(0.4f, dim.colorModulator().w());
        final DynamicGpuData.Transform heat = SectionTransforms.section(view, 0f, 0f, 0f, 1f, 1f, 1f, 1f,
                new float[] {2f, 0.5f, 0.25f});
        assertEquals(2f, heat.textureMatrix().m00());
        assertEquals(0.5f, heat.textureMatrix().m30());
        assertEquals(0f, heat.textureMatrix().m11());
        assertEquals(0.25f, heat.textureMatrix().m31());
        assertEquals(new Matrix4f(), full.textureMatrix());
    }
}
