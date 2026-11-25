package io.github.pix3lpirat3.mfbridge.mc26_2;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import io.github.pix3lpirat3.mfbridge.common.render.gpu.Gpu;
import io.github.pix3lpirat3.mfbridge.common.render.gpu.RecordedPass;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** The bridge's GPU calls on 26.2's render API. */
final class Gpu262 implements Gpu {

    private static final int USAGE = GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST;
    private static final Vector3fc NO_OFFSET = new Vector3f();

    private final Pipelines pipelines = new Pipelines();

    @Override
    public Object createVertexBuffer(final String label, final long bytes) {
        return RenderSystem.getDevice().createBuffer(() -> label, USAGE, bytes);
    }

    @Override
    public void write(final Object buffer, final long offset, final ByteBuffer data) {
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(((GpuBuffer) buffer).slice(offset, data.remaining()), data);
    }

    @Override
    public void close(final Object buffer) {
        ((GpuBuffer) buffer).close();
    }

    @Override
    public RenderType renderType(final Pipeline pipeline) {
        final RenderType type = pipelines.types.get(pipeline);
        if (type == null) {
            throw new IllegalArgumentException("no render type for " + pipeline);
        }
        return type;
    }

    @Override
    public int[] vertexSizes() {
        return new int[] {DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize(), DefaultVertexFormat.POSITION_TEX.getVertexSize(),
            DefaultVertexFormat.POSITION_COLOR.getVertexSize(), DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH.getVertexSize()};
    }

    @Override
    public List<?> pipelines() {
        return pipelines.all;
    }

    // LOCATION_BLOCKS is what LevelRenderer binds for terrain.
    @Override
    public Pass begin(final int quadIndices, final int lineIndices, final DynamicTexture colormap, final Matrix4f view) {
        view.set(RenderSystem.getModelViewMatrixCopy());
        return new RecordedPass(r -> replay(r, quadIndices, lineIndices, colormap));
    }

    /** Writes the frame's transforms, then opens the pass and runs the kept draws: 26.2 writes no uniform inside a pass. */
    @SuppressWarnings("deprecation")
    private void replay(final RecordedPass r, final int quadIndices, final int lineIndices, final DynamicTexture colormap) {
        final DynamicUniforms.Transform[] transforms = new DynamicUniforms.Transform[r.transforms().size()];
        for (int i = 0; i < transforms.length; i++) {
            final Transform t = r.transforms().get(i);
            transforms[i] = new DynamicUniforms.Transform(t.modelView(), t.colorModulator(), NO_OFFSET, t.textureMatrix());
        }
        final GpuBufferSlice[] slices = RenderSystem.getDynamicUniforms().writeTransforms(transforms);
        final RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        final RenderSystem.AutoStorageIndexBuffer lines = RenderSystem.getSequentialBuffer(PrimitiveTopology.LINES);
        final GpuBuffer quadBuffer = quads.getBuffer(Math.max(6, quadIndices));
        final GpuBuffer lineBuffer = lines.getBuffer(Math.max(6, lineIndices));
        final Minecraft minecraft = Minecraft.getInstance();
        final GpuTextureView atlas = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        final GpuTextureView colormapView = colormap.getTextureView();
        final GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        final GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        final RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Bridge retained tier", target.getColorTextureView(), Optional.empty(),
                target.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            for (final RecordedPass.Step step : r.steps()) {
                switch (step) {
                    case RecordedPass.Bind b -> {
                        pass.setPipeline(pipelines.pipelines.get(b.pipeline()));
                        if (b.pipeline().lines()) {
                            pass.setIndexBuffer(lineBuffer, lines.type());
                        } else {
                            pass.setIndexBuffer(quadBuffer, quads.type());
                        }
                        switch (b.texture()) {
                            case BLOCKS -> pass.bindTexture("Sampler0", atlas, nearest);
                            case COLORMAP -> pass.bindTexture("Sampler0", colormapView, linear);
                            case NONE -> { }
                        }
                    }
                    case RecordedPass.SetTransform t -> pass.setUniform("DynamicTransforms", slices[t.index()]);
                    case RecordedPass.Draw d -> {
                        pass.setVertexBuffer(0, ((GpuBuffer) d.buffer()).slice());
                        pass.drawIndexed(d.vertices() / 4 * 6, 1, 0, d.firstVertex(), 0);
                    }
                }
            }
        }
    }
}
