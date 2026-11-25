package io.github.pix3lpirat3.mfbridge.mc26_3;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.pix3lpirat3.mfbridge.common.render.gpu.Gpu;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** The bridge's GPU calls on 26.3's render API. */
final class Gpu263 implements Gpu {

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

    // LOCATION_BLOCKS is deprecated in 26.3, yet it is what LevelRenderer binds for terrain in the same version.
    @SuppressWarnings("deprecation")
    @Override
    public Pass begin(final int quadIndices, final int lineIndices, final DynamicTexture colormap, final Matrix4f view) {
        final RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        final RenderSystem.AutoStorageIndexBuffer lines = RenderSystem.getSequentialBuffer(PrimitiveTopology.LINES);
        // Index buffers grow by writing, which must happen before the pass opens.
        final GpuBuffer quadBuffer = quads.getBuffer(Math.max(6, quadIndices));
        final GpuBuffer lineBuffer = lines.getBuffer(Math.max(6, lineIndices));
        view.set(RenderSystem.getModelViewMatrixCopy());
        final Minecraft minecraft = Minecraft.getInstance();
        final RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
        final RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Bridge retained tier", target.getColorTextureView(), Optional.empty(),
                target.getDepthTextureView(), OptionalDouble.empty());
        RenderSystem.bindDefaultUniforms(pass);
        return new Open(pass, quads, quadBuffer, lines, lineBuffer,
                minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(),
                colormap.getTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
    }

    private final class Open implements Pass {
        private final RenderPass pass;
        private final RenderSystem.AutoStorageIndexBuffer quads;
        private final GpuBuffer quadIndices;
        private final RenderSystem.AutoStorageIndexBuffer lines;
        private final GpuBuffer lineIndices;
        private final GpuTextureView atlas;
        private final GpuTextureView colormap;
        private final GpuSampler nearest;
        private final GpuSampler linear;

        Open(final RenderPass pass, final RenderSystem.AutoStorageIndexBuffer quads, final GpuBuffer quadIndices,
                final RenderSystem.AutoStorageIndexBuffer lines, final GpuBuffer lineIndices, final GpuTextureView atlas,
                final GpuTextureView colormap, final GpuSampler nearest, final GpuSampler linear) {
            this.pass = pass;
            this.quads = quads;
            this.quadIndices = quadIndices;
            this.lines = lines;
            this.lineIndices = lineIndices;
            this.atlas = atlas;
            this.colormap = colormap;
            this.nearest = nearest;
            this.linear = linear;
        }

        @Override
        public void bind(final Pipeline pipeline, final Texture texture) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipelines.pipelines.get(pipeline)));
            if (pipeline.lines()) {
                pass.setIndexBuffer(lineIndices, lines.type());
            } else {
                pass.setIndexBuffer(quadIndices, quads.type());
            }
            switch (texture) {
                case BLOCKS -> pass.setUniform("Sampler0", atlas, nearest);
                case COLORMAP -> pass.setUniform("Sampler0", colormap, linear);
                case NONE -> { }
            }
        }

        @Override
        public void transform(final Transform t) {
            pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(
                    new DynamicGpuData.Transform(t.modelView(), t.colorModulator(), NO_OFFSET, t.textureMatrix())));
        }

        @Override
        public void draw(final Object buffer, final int firstVertex, final int vertices) {
            pass.setVertexBuffer(0, ((GpuBuffer) buffer).slice());
            pass.drawIndexed(vertices / 4 * 6, 1, 0, firstVertex, 0);
        }

        @Override
        public void close() {
            pass.close();
        }
    }
}
