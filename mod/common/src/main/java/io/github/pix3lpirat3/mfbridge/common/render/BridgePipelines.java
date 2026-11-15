package io.github.pix3lpirat3.mfbridge.common.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * The immediate-tier pipelines: {@code lines} and {@code fill}, each depth-tested
 * and through-walls. They reuse vanilla's shaders, so no shader assets ship with the mod.
 *
 * <p>Minecraft 26.3 uses reverse-Z depth, hence {@code GREATER_THAN_OR_EQUAL} for the depth-tested
 * variants. Translucent render types must carry an OIT pipeline set: with improved transparency on,
 * 26.3 routes all translucent submits through order-independent transparency and fails on types without one.
 */
public final class BridgePipelines {

    /** Depth-tested opaque lines with per-vertex screen-space width. */
    public static final RenderPipeline LINES = linesBuilder("lines").withDepthStencilState(DepthStencilState.DEFAULT).build();

    /** Lines drawn through walls. */
    public static final RenderPipeline LINES_XRAY = linesBuilder("lines_xray").withDepthStencilState(Optional.empty()).build();

    /** Depth-tested translucent quads that do not write depth. */
    public static final RenderPipeline FILL = fillBuilder("fill")
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .build();

    /** Translucent quads drawn through walls. */
    public static final RenderPipeline FILL_XRAY = fillBuilder("fill_xray").withDepthStencilState(Optional.empty()).build();

    /** OIT variant of {@link #FILL}. */
    public static final OitPipelineSet OIT_FILL = oitSet("_fill", true);

    /** OIT variant of {@link #FILL_XRAY}. */
    public static final OitPipelineSet OIT_FILL_XRAY = oitSet("_fill_xray", false);

    /** Every pipeline above, for loaders that precompile pipelines at startup. */
    public static final List<RenderPipeline> ALL = List.of(
            LINES,
            LINES_XRAY,
            FILL,
            FILL_XRAY,
            OIT_FILL.depthBoundsPipeline(),
            OIT_FILL.transmittancePipeline(),
            OIT_FILL.accumulatePipeline(),
            OIT_FILL_XRAY.depthBoundsPipeline(),
            OIT_FILL_XRAY.transmittancePipeline(),
            OIT_FILL_XRAY.accumulatePipeline());

    static final RenderType LINES_TYPE = RenderType.create(Constants.MOD_ID + "_lines", RenderSetup.builder(LINES)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .createRenderSetup());

    static final RenderType LINES_XRAY_TYPE = RenderType.create(Constants.MOD_ID + "_lines_xray", RenderSetup.builder(LINES_XRAY)
            .createRenderSetup());

    static final RenderType FILL_TYPE = RenderType.create(Constants.MOD_ID + "_fill", RenderSetup.builder(FILL)
            .setOitPipelines(OIT_FILL)
            .sortOnUpload()
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .createRenderSetup());

    static final RenderType FILL_XRAY_TYPE = RenderType.create(Constants.MOD_ID + "_fill_xray", RenderSetup.builder(FILL_XRAY)
            .setOitPipelines(OIT_FILL_XRAY)
            .sortOnUpload()
            .createRenderSetup());

    private BridgePipelines() {
    }

    private static RenderPipeline.Builder linesBuilder(final String name) {
        return RenderPipeline.builder()
                .withLocation(Constants.id("pipeline/" + name))
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withBindGroupLayout(BindGroupLayouts.FOG)
                .withVertexShader("core/rendertype_lines")
                .withFragmentShader("core/rendertype_lines")
                .withCull(false)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH)
                .withPrimitiveTopology(PrimitiveTopology.LINES)
                .withColorTargetState(ColorTargetState.DEFAULT);
    }

    private static RenderPipeline.Builder fillBuilder(final String name) {
        return RenderPipeline.builder()
                .withLocation(Constants.id("pipeline/" + name))
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false);
    }

    // NeoForge 26.3 deprecates this vanilla overload in favour of an Identifier overload that vanilla
    // lacks, and common must compile against both.
    @SuppressWarnings("deprecation")
    private static OitPipelineSet oitSet(final String suffix, final boolean depthTest) {
        final OitPipelineSet.Builder builder = OitPipelineSet.builder(Constants.MOD_ID + suffix, RenderPipeline.builder()
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false));
        return depthTest ? builder.build() : builder.withoutDepthTest().build();
    }
}
