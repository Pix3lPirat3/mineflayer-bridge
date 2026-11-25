package io.github.pix3lpirat3.mfbridge.mc26_2;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.render.gpu.Gpu;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

/** The bridge's pipelines on 26.2: the shaders of 26.3 under their older names, its bind groups, reverse-Z, no OIT sets. */
final class Pipelines {

    private static final DepthStencilState TEST = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false);

    final Map<Gpu.Pipeline, RenderPipeline> pipelines = new EnumMap<>(Gpu.Pipeline.class);
    final Map<Gpu.Pipeline, RenderType> types = new EnumMap<>(Gpu.Pipeline.class);
    final List<RenderPipeline> all = new ArrayList<>();

    Pipelines() {
        for (final Gpu.Pipeline p : Gpu.Pipeline.values()) {
            final String name = p.name().toLowerCase(Locale.ROOT);
            final RenderPipeline.Builder b = switch (p) {
                case LINES, LINES_XRAY -> builder(name, "core/rendertype_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH,
                        PrimitiveTopology.LINES, BindGroupLayouts.FOG).withColorTargetState(ColorTargetState.DEFAULT);
                case FILL, FILL_XRAY -> builder(name, "core/position_color", DefaultVertexFormat.POSITION_COLOR, PrimitiveTopology.QUADS)
                        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT));
                case GHOST, GHOST_XRAY -> builder(name, "core/position_tex_color", DefaultVertexFormat.POSITION_TEX_COLOR,
                        PrimitiveTopology.QUADS, BindGroupLayouts.SAMPLER0)
                        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).withCull(true);
                case HEAT, HEAT_XRAY -> builder(name, "core/rendertype_world_border", DefaultVertexFormat.POSITION_TEX,
                        PrimitiveTopology.QUADS, BindGroupLayouts.SAMPLER0).withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT));
            };
            final DepthStencilState state = p == Gpu.Pipeline.LINES ? DepthStencilState.DEFAULT : TEST;
            final RenderPipeline pipeline = (name.endsWith("_xray") ? b.withDepthStencilState(Optional.empty())
                    : b.withDepthStencilState(state)).build();
            pipelines.put(p, pipeline);
            all.add(pipeline);
        }
        types.put(Gpu.Pipeline.LINES, RenderType.create(Constants.MOD_ID + "_lines", RenderSetup.builder(pipelines.get(Gpu.Pipeline.LINES))
                .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING).createRenderSetup()));
        types.put(Gpu.Pipeline.LINES_XRAY, RenderType.create(Constants.MOD_ID + "_lines_xray",
                RenderSetup.builder(pipelines.get(Gpu.Pipeline.LINES_XRAY)).createRenderSetup()));
        types.put(Gpu.Pipeline.FILL, RenderType.create(Constants.MOD_ID + "_fill", RenderSetup.builder(pipelines.get(Gpu.Pipeline.FILL))
                .sortOnUpload().setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING).createRenderSetup()));
        types.put(Gpu.Pipeline.FILL_XRAY, RenderType.create(Constants.MOD_ID + "_fill_xray",
                RenderSetup.builder(pipelines.get(Gpu.Pipeline.FILL_XRAY)).sortOnUpload().createRenderSetup()));
    }

    private static RenderPipeline.Builder builder(final String name, final String shader, final VertexFormat format,
            final PrimitiveTopology topology, final BindGroupLayout... extra) {
        final RenderPipeline.Builder b = RenderPipeline.builder()
                .withLocation(Constants.id("pipeline/" + name))
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION);
        for (final BindGroupLayout layout : extra) {
            b.withBindGroupLayout(layout);
        }
        return b.withVertexShader(shader)
                .withFragmentShader(shader)
                .withVertexBinding(0, format)
                .withPrimitiveTopology(topology)
                .withCull(false);
    }
}
