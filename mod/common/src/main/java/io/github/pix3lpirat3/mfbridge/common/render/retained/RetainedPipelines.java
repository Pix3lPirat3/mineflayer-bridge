package io.github.pix3lpirat3.mfbridge.common.render.retained;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.render.BridgePipelines;
import io.github.pix3lpirat3.mfbridge.core.retained.MeshWriter;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.renderer.BindGroupLayouts;

/**
 * Pipelines of the retained tier, each depth-tested and through-walls. They reuse
 * vanilla shaders, so nothing ships as a shader asset:
 *
 * <ul>
 *   <li>{@code ghost}: {@code core/position_tex_color} over the block atlas. Overlay classes tint
 *       through the vertex colour, the object's alpha through {@code ColorModulator}.
 *   <li>{@code heat}: {@code core/world_border}, the one vanilla shader that applies {@code TextureMat}
 *       to its uv. Cells carry their raw value in u and the colormap is a small texture, so colormap
 *       and value range are pure uniforms.
 *   <li>Outlines and highlight faces use the immediate tier's {@code lines} and {@code fill}
 *       pipelines with white vertices, coloured by {@code ColorModulator}.
 * </ul>
 *
 * <p>Retained meshes are drawn in their own render pass after vanilla's translucent pass (and after
 * OIT composition), so these are classic blended pipelines without OIT sets. 26.3 uses reverse-Z depth.
 */
public final class RetainedPipelines {

    /** Depth-tested without depth writes, so translucent retained meshes never hide each other. */
    private static final DepthStencilState TEST = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false);

    /** Depth-tested ghost blocks. */
    public static final RenderPipeline GHOST = ghost("ghost", true);

    /** Ghost blocks through walls. */
    public static final RenderPipeline GHOST_XRAY = ghost("ghost_xray", false);

    /** Depth-tested heatmap cells. */
    public static final RenderPipeline HEAT = heat("heat", true);

    /** Heatmap cells through walls. */
    public static final RenderPipeline HEAT_XRAY = heat("heat_xray", false);

    /** Every pipeline above, for loaders that precompile pipelines at startup. */
    public static final List<RenderPipeline> ALL = List.of(GHOST, GHOST_XRAY, HEAT, HEAT_XRAY);

    private RetainedPipelines() {
    }

    /** Returns the line pipeline for a depth pass. */
    static RenderPipeline lines(final boolean depthTest) {
        return depthTest ? BridgePipelines.LINES : BridgePipelines.LINES_XRAY;
    }

    /** Returns the face pipeline for a depth pass. */
    static RenderPipeline faces(final boolean depthTest) {
        return depthTest ? BridgePipelines.FILL : BridgePipelines.FILL_XRAY;
    }

    /**
     * Checks that the core's vertex layouts match the game's formats, so raw buffers can be uploaded
     * unconverted.
     *
     * @return null when they match, otherwise what differs
     */
    static String layoutProblem() {
        final int texColor = DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize();
        final int tex = DefaultVertexFormat.POSITION_TEX.getVertexSize();
        final int color = DefaultVertexFormat.POSITION_COLOR.getVertexSize();
        final int line = DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH.getVertexSize();
        if (texColor != MeshWriter.Format.TEX_COLOR.bytes() || tex != MeshWriter.Format.TEX.bytes()
                || color != MeshWriter.Format.COLOR.bytes() || line != MeshWriter.Format.LINE.bytes()) {
            return "vertex sizes " + texColor + "/" + tex + "/" + color + "/" + line;
        }
        return null;
    }

    private static RenderPipeline ghost(final String name, final boolean depthTest) {
        final RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Constants.id("pipeline/" + name))
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withVertexShader("core/position_tex_color")
                .withFragmentShader("core/position_tex_color")
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(true);
        return (depthTest ? b.withDepthStencilState(TEST) : b.withDepthStencilState(Optional.empty())).build();
    }

    private static RenderPipeline heat(final String name, final boolean depthTest) {
        final RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Constants.id("pipeline/" + name))
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withVertexShader("core/world_border")
                .withFragmentShader("core/world_border")
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false);
        return (depthTest ? b.withDepthStencilState(TEST) : b.withDepthStencilState(Optional.empty())).build();
    }
}
