package io.github.pix3lpirat3.mfbridge.mc26_3;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.render.gpu.Gpu;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * The bridge's pipelines on 26.3. They reuse vanilla shaders, so no shader assets ship: lines and
 * fill use {@code rendertype_lines} and {@code position_color}; ghost blocks {@code position_tex_color}
 * over the atlas; heatmap cells {@code world_border}, the one vanilla shader that applies
 * {@code TextureMat} to its uv. 26.3 uses reverse-Z depth, and its translucent render types need an
 * OIT pipeline set: with improved transparency on, every translucent submit goes through it.
 */
final class Pipelines {

    /** Depth-tested without depth writes. */
    private static final DepthStencilState TEST = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false);

    final Map<Gpu.Pipeline, RenderPipeline> pipelines = new EnumMap<>(Gpu.Pipeline.class);
    final Map<Gpu.Pipeline, RenderType> types = new EnumMap<>(Gpu.Pipeline.class);
    final List<RenderPipeline> all = new ArrayList<>();

    Pipelines() {
        for (final Gpu.Pipeline p : Gpu.Pipeline.values()) {
            final String name = p.name().toLowerCase(java.util.Locale.ROOT);
            final boolean depth = !name.endsWith("_xray");
            final RenderPipeline.Builder b = switch (p) {
                case LINES, LINES_XRAY -> builder(name, "core/rendertype_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH,
                        PrimitiveTopology.LINES, BindGroupLayouts.FOG).withColorTargetState(ColorTargetState.DEFAULT);
                case FILL, FILL_XRAY -> builder(name, "core/position_color", DefaultVertexFormat.POSITION_COLOR, PrimitiveTopology.QUADS)
                        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT));
                case GHOST, GHOST_XRAY -> builder(name, "core/position_tex_color", DefaultVertexFormat.POSITION_TEX_COLOR,
                        PrimitiveTopology.QUADS, BindGroupLayouts.SAMPLER0)
                        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).withCull(true);
                case HEAT, HEAT_XRAY -> builder(name, "core/world_border", DefaultVertexFormat.POSITION_TEX, PrimitiveTopology.QUADS,
                        BindGroupLayouts.SAMPLER0).withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT));
            };
            final DepthStencilState state = p == Gpu.Pipeline.LINES ? DepthStencilState.DEFAULT : TEST;
            final RenderPipeline pipeline = (depth ? b.withDepthStencilState(state) : b.withDepthStencilState(Optional.empty())).build();
            pipelines.put(p, pipeline);
            all.add(pipeline);
        }
        types.put(Gpu.Pipeline.LINES, RenderType.create(Constants.MOD_ID + "_lines", RenderSetup.builder(pipelines.get(Gpu.Pipeline.LINES))
                .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                .createRenderSetup()));
        types.put(Gpu.Pipeline.LINES_XRAY, RenderType.create(Constants.MOD_ID + "_lines_xray",
                RenderSetup.builder(pipelines.get(Gpu.Pipeline.LINES_XRAY)).createRenderSetup()));
        types.put(Gpu.Pipeline.FILL, RenderType.create(Constants.MOD_ID + "_fill", RenderSetup.builder(pipelines.get(Gpu.Pipeline.FILL))
                .setOitPipelines(oitSet("_fill", true))
                .sortOnUpload()
                .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                .createRenderSetup()));
        types.put(Gpu.Pipeline.FILL_XRAY, RenderType.create(Constants.MOD_ID + "_fill_xray",
                RenderSetup.builder(pipelines.get(Gpu.Pipeline.FILL_XRAY))
                .setOitPipelines(oitSet("_fill_xray", false))
                .sortOnUpload()
                .createRenderSetup()));
    }

    private static RenderPipeline.Builder builder(final String name, final String shader, final VertexFormat format,
            final PrimitiveTopology topology, final BindGroupLayout... extra) {
        final RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Constants.id("pipeline/" + name))
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS);
        for (final BindGroupLayout layout : extra) {
            b.withBindGroupLayout(layout);
        }
        return b.withVertexShader(shader)
                .withFragmentShader(shader)
                .withVertexBinding(0, format)
                .withPrimitiveTopology(topology)
                .withCull(false);
    }

    // NeoForge 26.3 deprecates this vanilla overload in favour of an Identifier overload that vanilla
    // lacks, and this must compile against both.
    @SuppressWarnings("deprecation")
    private OitPipelineSet oitSet(final String suffix, final boolean depthTest) {
        final OitPipelineSet.Builder builder = OitPipelineSet.builder(Constants.MOD_ID + suffix, RenderPipeline.builder()
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false));
        final OitPipelineSet set = depthTest ? builder.build() : builder.withoutDepthTest().build();
        all.add(set.depthBoundsPipeline());
        all.add(set.transmittancePipeline());
        all.add(set.accumulatePipeline());
        return set;
    }
}
