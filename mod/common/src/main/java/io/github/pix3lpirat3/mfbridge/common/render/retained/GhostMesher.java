package io.github.pix3lpirat3.mfbridge.common.render.retained;

import io.github.pix3lpirat3.mfbridge.core.retained.CellPredicate;
import io.github.pix3lpirat3.mfbridge.core.retained.DiffClassifier;
import io.github.pix3lpirat3.mfbridge.core.retained.GhostVolume;
import io.github.pix3lpirat3.mfbridge.core.retained.MeshWriter;
import io.github.pix3lpirat3.mfbridge.core.retained.SectionKeys;
import io.github.pix3lpirat3.mfbridge.core.retained.SectionMeshing;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Meshes one 16x16x16 section of a ghost volume from vanilla block models:
 * the baked quads of each block's model, tinted by its overlay class, plus merged outlines for
 * {@code extra} blocks and, for the far level of detail, of every drawn block.
 *
 * <p>Runs on mesh workers. Model sets and block colours are read-only after resource loading, which
 * is what the game's own section compiler relies on as well (26.3).
 */
final class GhostMesher {
    /** Outline width of ghost and diff outlines, in pixels. */
    static final float OUTLINE_WIDTH = 2f;

    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * Ghost models are scaled by this about each block centre, pushing full-block faces out by
     * {@link SectionMeshing#FACE_MARGIN}. A ghost whose state differs from the world block would
     * otherwise share every face plane with it and z-fight. Faces between neighbouring ghosts are
     * culled before scaling, so the overlap this creates is never drawn.
     */
    private static final float SCALE = 1f + 2f * SectionMeshing.FACE_MARGIN;

    /** Meshes of one section. */
    record Meshes(MeshWriter faces, MeshWriter near, MeshWriter far) {}

    private GhostMesher() {
    }

    /**
     * Meshes a section.
     *
     * @param classes this section's overlay classes, 4096 entries
     * @param classesOf overlay classes of any section key, or null when not classified yet; used to cull
     *     faces against neighbouring ghosts across section borders
     */
    // Mth.getSeed is deprecated in 26.3 but is still the seed vanilla's section compiler picks model variants with.
    @SuppressWarnings("deprecation")
    static Meshes mesh(final GhostVolume volume, final GhostStates states, final long key, final byte[] classes,
            final LongFunction<byte @Nullable []> classesOf, final boolean hideMatching, final BlockStateModelSet models,
            final BlockColors colors) {
        final int ox = SectionKeys.x(key) << 4;
        final int oy = SectionKeys.y(key) << 4;
        final int oz = SectionKeys.z(key) << 4;
        final short[] ids = volume.section(key);
        final MeshWriter faces = new MeshWriter(MeshWriter.Format.TEX_COLOR, 1024);
        final MeshWriter near = new MeshWriter(MeshWriter.Format.LINE, 64);
        final MeshWriter far = new MeshWriter(MeshWriter.Format.LINE, 256);
        if (ids == null) {
            return new Meshes(faces, near, far);
        }
        final RandomSource random = RandomSource.create();
        final List<BlockStateModelPart> parts = new ArrayList<>();
        for (int i = 0; i < 4096; i++) {
            final byte cls = classes[i];
            if (!DiffClassifier.facesDrawn(cls, hideMatching)) {
                continue;
            }
            final BlockState state = states.state(Short.toUnsignedInt(ids[i]));
            if (state == null || state.isAir()) {
                continue;
            }
            final int lx = i & 15;
            final int ly = i >> 8;
            final int lz = (i >> 4) & 15;
            parts.clear();
            random.setSeed(Mth.getSeed(ox + lx, oy + ly, oz + lz));
            models.get(state).collectParts(random, parts);
            final int tint = DiffClassifier.tint(cls);
            for (final BlockStateModelPart part : parts) {
                for (final Direction dir : DIRECTIONS) {
                    if (occluded(volume, states, classesOf, hideMatching, ox + lx + dir.getStepX(),
                            oy + ly + dir.getStepY(), oz + lz + dir.getStepZ())) {
                        continue;
                    }
                    emit(part.getQuads(dir), state, colors, tint, lx, ly, lz, faces);
                }
                emit(part.getQuads(null), state, colors, tint, lx, ly, lz, faces);
            }
        }
        final CellPredicate extra = (x, y, z) -> classAt(classesOf, x, y, z) == DiffClassifier.EXTRA;
        SectionMeshing.outline(key, extra, DiffClassifier.outline(DiffClassifier.EXTRA), OUTLINE_WIDTH, near);
        for (byte cls = DiffClassifier.PLAIN; cls < DiffClassifier.CLASSES; cls++) {
            if (!DiffClassifier.outlined(cls, hideMatching, true)) {
                continue;
            }
            final byte want = cls;
            SectionMeshing.outline(key, (x, y, z) -> classAt(classesOf, x, y, z) == want, DiffClassifier.outline(cls),
                    OUTLINE_WIDTH, far);
        }
        return new Meshes(faces, near, far);
    }

    private static byte classAt(final LongFunction<byte @Nullable []> classesOf, final int x, final int y, final int z) {
        final byte[] c = classesOf.apply(SectionKeys.ofBlock(x, y, z));
        return c == null ? DiffClassifier.HIDDEN : c[SectionKeys.index(x & 15, y & 15, z & 15)];
    }

    /** A face is culled when the neighbour is a drawn ghost of a full opaque block. */
    private static boolean occluded(final GhostVolume volume, final GhostStates states,
            final LongFunction<byte @Nullable []> classesOf, final boolean hideMatching, final int x, final int y,
            final int z) {
        if (!DiffClassifier.facesDrawn(classAt(classesOf, x, y, z), hideMatching)) {
            return false;
        }
        final BlockState n = states.state(volume.get(x, y, z));
        return n != null && n.isSolidRender();
    }

    private static void emit(final List<BakedQuad> quads, final BlockState state, final BlockColors colors,
            final int classTint, final int lx, final int ly, final int lz, final MeshWriter out) {
        for (int q = 0; q < quads.size(); q++) {
            final BakedQuad quad = quads.get(q);
            int argb = classTint;
            final int tintIndex = quad.materialInfo().tintIndex();
            if (tintIndex >= 0) {
                final BlockTintSource source = colors.getTintSource(state, tintIndex);
                if (source != null) {
                    argb = multiply(argb, source.color(state) | 0xFF000000);
                }
            }
            for (int v = 0; v < 4; v++) {
                final Vector3fc p = quad.position(v);
                final long uv = quad.packedUV(v);
                out.texColor(lx + 0.5f + (p.x() - 0.5f) * SCALE, ly + 0.5f + (p.y() - 0.5f) * SCALE,
                        lz + 0.5f + (p.z() - 0.5f) * SCALE, UVPair.unpackU(uv), UVPair.unpackV(uv), argb);
            }
        }
    }

    private static int multiply(final int a, final int b) {
        final int r = ((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) / 255;
        final int g = ((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) / 255;
        final int bl = (a & 0xFF) * (b & 0xFF) / 255;
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }
}
