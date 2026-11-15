package io.github.pix3lpirat3.mfbridge.core.retained;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Writes vertices of the retained pipelines into a direct, little-endian buffer that can be uploaded
 * to a GPU buffer as is. The layouts match the game's vertex formats (checked at renderer start):
 *
 * <ul>
 *   <li>{@link Format#TEX_COLOR}: position {@code 3 x f32}, uv {@code 2 x f32}, colour {@code RGBA8}.
 *   <li>{@link Format#TEX}: position, uv.
 *   <li>{@link Format#COLOR}: position, colour.
 *   <li>{@link Format#LINE}: position, colour, normal {@code 3 x s8} plus padding, line width {@code f32};
 *       every vertex is written twice, because the lines shader widens each segment into a quad.
 * </ul>
 *
 * <p>Positions are relative to the section origin, so they stay small and precise in {@code f32}.
 * Not thread-safe: one writer per worker task.
 */
public final class MeshWriter {
    /** Vertex layouts. */
    public enum Format {
        /** Position, uv, colour: ghost blocks with the block atlas. */
        TEX_COLOR(24),
        /** Position, uv: heatmap cells whose uv carries the raw value. */
        TEX(20),
        /** Position, colour: highlight faces. */
        COLOR(16),
        /** Position, colour, normal, width: outlines. */
        LINE(24);

        private final int bytes;

        Format(int bytes) {
            this.bytes = bytes;
        }

        /** Returns the vertex size, in bytes. */
        public int bytes() {
            return bytes;
        }
    }

    private final Format format;
    private ByteBuffer buf;
    private int vertices;

    /**
     * Creates a writer.
     *
     * @param initialVertices capacity hint, in vertices
     */
    public MeshWriter(Format format, int initialVertices) {
        this.format = format;
        this.buf = ByteBuffer.allocateDirect(Math.max(64, initialVertices) * format.bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Returns the layout. */
    public Format format() {
        return format;
    }

    /** Returns the vertices written so far. */
    public int vertices() {
        return vertices;
    }

    /** Returns the bytes written so far. */
    public int bytes() {
        return buf.position();
    }

    private void ensure(int more) {
        if (buf.remaining() < more) {
            ByteBuffer bigger = ByteBuffer.allocateDirect(Math.max(buf.capacity() * 2, buf.position() + more))
                    .order(ByteOrder.LITTLE_ENDIAN);
            buf.flip();
            bigger.put(buf);
            buf = bigger;
        }
    }

    /** Writes one {@link Format#TEX_COLOR} vertex; {@code argb} is the colour as ARGB. */
    public void texColor(float x, float y, float z, float u, float v, int argb) {
        ensure(24);
        buf.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v).putInt(rgbaBytes(argb));
        vertices++;
    }

    /** Writes one {@link Format#TEX} vertex. */
    public void tex(float x, float y, float z, float u, float v) {
        ensure(20);
        buf.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
        vertices++;
    }

    /** Writes one {@link Format#COLOR} vertex. */
    public void color(float x, float y, float z, int argb) {
        ensure(16);
        buf.putFloat(x).putFloat(y).putFloat(z).putInt(rgbaBytes(argb));
        vertices++;
    }

    /**
     * Writes one {@link Format#LINE} segment: four vertices, each endpoint twice, all carrying the
     * segment direction as normal.
     *
     * @param width line width, in screen pixels
     */
    public void line(float ax, float ay, float az, float bx, float by, float bz, int argb, float width) {
        float nx = bx - ax;
        float ny = by - ay;
        float nz = bz - az;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0e-6f) {
            return;
        }
        int normal = snorm(nx / len) | snorm(ny / len) << 8 | snorm(nz / len) << 16;
        int color = rgbaBytes(argb);
        ensure(96);
        for (int i = 0; i < 4; i++) {
            float x = i < 2 ? ax : bx;
            float y = i < 2 ? ay : by;
            float z = i < 2 ? az : bz;
            buf.putFloat(x).putFloat(y).putFloat(z).putInt(color).putInt(normal).putFloat(width);
        }
        vertices += 4;
    }

    /**
     * Returns the written vertices as a read-only view from position 0; the writer can be reset and
     * reused afterwards only if the view is no longer needed.
     */
    public ByteBuffer finish() {
        ByteBuffer out = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        out.flip();
        return out;
    }

    /** Discards everything written. */
    public void reset() {
        buf.clear();
        vertices = 0;
    }

    /** Packs ARGB into the int whose little-endian bytes are R, G, B, A ({@code RGBA8_UNORM}). */
    static int rgbaBytes(int argb) {
        int a = argb >>> 24;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return a << 24 | b << 16 | g << 8 | r;
    }

    /** The game's normal packing: clamp to [-1, 1], scale by 127, keep the low byte. */
    static int snorm(float c) {
        return (int) (Math.max(-1f, Math.min(1f, c)) * 127f) & 0xFF;
    }
}
