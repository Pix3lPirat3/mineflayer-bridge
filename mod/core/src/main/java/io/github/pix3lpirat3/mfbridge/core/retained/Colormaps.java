package io.github.pix3lpirat3.mfbridge.core.retained;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Heatmap colormaps (style {@code colormap}) as one small texture: {@link #WIDTH} texels per
 * map, one row per map. Cells carry their raw value in the vertex uv; the renderer maps it into the
 * texture with a uv transform uniform built by {@link #uvTransform}, so changing the colormap or the
 * value range never re-meshes.
 *
 * <p>Immutable tables; safe on any thread.
 */
public final class Colormaps {
    /** Texels per colormap row. */
    public static final int WIDTH = 256;

    /** Rows in the texture: one per built-in map, padded to a power of two. */
    public static final int ROWS = 8;

    /** Built-in names in row order; unknown names fall back to row 0. */
    public static final List<String> NAMES = List.of("viridis", "magma", "turbo", "diverging", "grey");

    private static final int[][] STOPS = {
        {0x440154, 0x482878, 0x3e4989, 0x31688e, 0x26828e, 0x1f9e89, 0x35b779, 0x6ece58, 0xb5de2b, 0xfde725},
        {0x000004, 0x180f3d, 0x440f76, 0x721f81, 0x9e2f7f, 0xcd4071, 0xf1605d, 0xfd9668, 0xfeca8d, 0xfcfdbf},
        {0x30123b, 0x4145ab, 0x4675ed, 0x39a2fc, 0x1bcfd4, 0x24eca6, 0x61fc6c, 0xa4fc3b, 0xd1e834, 0xf3c63a,
            0xfe9b2d, 0xf36315, 0xd93806, 0xb11901, 0x7a0402},
        {0x2166ac, 0x4393c3, 0x92c5de, 0xd1e5f0, 0xf7f7f7, 0xfddbc7, 0xf4a582, 0xd6604d, 0xb2182b},
        {0x000000, 0xffffff},
    };

    private static final int[] TEXTURE = build();

    private Colormaps() {}

    /** Returns the row of a colormap name, case-insensitive; unknown names give viridis. */
    public static int row(String name) {
        int i = NAMES.indexOf(name.toLowerCase(Locale.ROOT));
        return Math.max(i, 0);
    }

    /** Returns the opaque ARGB colour of a map at {@code t} in [0, 1]; values outside are clamped, NaN gives 0. */
    public static int argb(int row, float t) {
        float c = Float.isNaN(t) ? 0f : Math.max(0f, Math.min(1f, t));
        int[] stops = STOPS[Math.max(0, Math.min(row, STOPS.length - 1))];
        float pos = c * (stops.length - 1);
        int i = Math.min((int) pos, stops.length - 2);
        float f = pos - i;
        return 0xFF000000 | lerp(stops[i], stops[i + 1], f);
    }

    private static int lerp(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * f);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * f);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * f);
        return r << 16 | g << 8 | bl;
    }

    private static int[] build() {
        int[] out = new int[WIDTH * ROWS];
        for (int row = 0; row < ROWS; row++) {
            int map = row < STOPS.length ? row : 0;
            for (int x = 0; x < WIDTH; x++) {
                out[row * WIDTH + x] = argb(map, x / (float) (WIDTH - 1));
            }
        }
        return out;
    }

    /** Returns the texture as ARGB texels, row-major, {@code WIDTH * ROWS} entries. A fresh copy. */
    public static int[] texture() {
        return TEXTURE.clone();
    }

    /**
     * Returns the uv transform that maps a raw value in vertex u to its texel: {@code u' = scaleU * u +
     * offsetU}, {@code v' = offsetV}. {@code min} lands on the first texel centre and {@code max} on the
     * last. A degenerate range maps every value to the middle of the map.
     *
     * @return {@code {scaleU, offsetU, offsetV}}
     */
    public static float[] uvTransform(float min, float max, int row) {
        float offsetV = (row + 0.5f) / ROWS;
        float span = max - min;
        if (!(span > 0f) || Float.isInfinite(span)) {
            return new float[] {0f, 0.5f, offsetV};
        }
        float scale = (WIDTH - 1f) / WIDTH / span;
        return new float[] {scale, 0.5f / WIDTH - min * scale, offsetV};
    }

    /**
     * Returns the value range to normalise by: the given bounds where present, otherwise the finite
     * minimum and maximum of the data; reversed bounds are swapped.
     *
     * @return {@code {min, max}}; {@code {0, 0}} when nothing is finite
     */
    public static float[] range(float[] values, @Nullable Float min,
            @Nullable Float max) {
        float lo = Float.POSITIVE_INFINITY;
        float hi = Float.NEGATIVE_INFINITY;
        if (min == null || max == null) {
            for (float v : values) {
                if (Float.isFinite(v)) {
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                }
            }
        }
        float a = min != null ? min : lo;
        float b = max != null ? max : hi;
        if (!Float.isFinite(a) || !Float.isFinite(b)) {
            return new float[] {0f, 0f};
        }
        return a <= b ? new float[] {a, b} : new float[] {b, a};
    }
}
