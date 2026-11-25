package io.github.pix3lpirat3.mfbridge.common.render.gpu;

import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4fc;

/**
 * The game's GPU calls behind the bridge's own drawing. Each game version's render API has its own
 * implementation; everything else draws through this, so it names no type that moved between versions.
 */
public interface Gpu {

    /** The bridge's pipelines, each depth-tested or drawn through walls. */
    enum Pipeline {
        /** Lines with per-vertex width, depth-tested. */
        LINES,
        /** Lines through walls. */
        LINES_XRAY,
        /** Translucent quads, depth-tested without depth writes. */
        FILL,
        /** Translucent quads through walls. */
        FILL_XRAY,
        /** Ghost blocks over the block atlas, depth-tested. */
        GHOST,
        /** Ghost blocks through walls. */
        GHOST_XRAY,
        /** Heatmap cells over the colormap, depth-tested. */
        HEAT,
        /** Heatmap cells through walls. */
        HEAT_XRAY;

        /** Returns whether it draws lines rather than quads. */
        public boolean lines() {
            return this == LINES || this == LINES_XRAY;
        }
    }

    /** What a pipeline samples. */
    enum Texture {
        /** Nothing. */
        NONE,
        /** The block atlas, nearest. */
        BLOCKS,
        /** The heatmap colormap, linear. */
        COLORMAP
    }

    /**
     * One draw's uniforms. The game's uniform storage keeps the last one by reference and reuses its
     * slice when the next is equal, so each must be a fresh value.
     *
     * @param modelView model view of the mesh
     * @param colorModulator colour multiplied into every vertex
     * @param textureMatrix uv transform
     */
    record Transform(Matrix4fc modelView, Vector4fc colorModulator, Matrix4fc textureMatrix) {}

    /**
     * Creates a vertex buffer that {@link #write} fills.
     *
     * @param label debug name
     * @param bytes size
     * @return the buffer, only for this interface
     */
    Object createVertexBuffer(String label, long bytes);

    /** Writes bytes, position to limit, at an offset of a buffer from {@link #createVertexBuffer}. */
    void write(Object buffer, long offset, ByteBuffer data);

    /** Releases a buffer from {@link #createVertexBuffer}. */
    void close(Object buffer);

    /** Returns the immediate tier's render type of a lines or fill pipeline. */
    RenderType renderType(Pipeline pipeline);

    /** Returns the game's vertex sizes of position-tex-colour, position-tex, position-colour and lines, in bytes. */
    int[] vertexSizes();

    /** Returns the game's pipeline objects, for loaders that compile pipelines at startup. */
    List<?> pipelines();

    /**
     * Opens the retained tier's pass over the main target, with index buffers for at least the given
     * index counts.
     *
     * @param quadIndices indices the largest quad draw needs
     * @param lineIndices indices the largest line draw needs
     * @param colormap the heatmap colormap texture
     * @param view set to the camera's model view
     * @return the pass, closed by the caller
     */
    Pass begin(int quadIndices, int lineIndices, DynamicTexture colormap, Matrix4f view);

    /** An open render pass. */
    interface Pass extends AutoCloseable {

        /** Binds a pipeline, its index buffer and what it samples. */
        void bind(Pipeline pipeline, Texture texture);

        /** Sets the uniforms of the next draws. */
        void transform(Transform transform);

        /**
         * Draws quads (four vertices each) from a buffer of {@link Gpu#createVertexBuffer}.
         *
         * @param buffer the buffer
         * @param firstVertex index of the first vertex in the buffer
         * @param vertices vertex count, a multiple of four
         */
        void draw(Object buffer, int firstVertex, int vertices);

        @Override
        void close();
    }
}
