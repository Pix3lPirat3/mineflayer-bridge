package io.github.pix3lpirat3.mfbridge.common.game;

/** Converts between the bridge's {@code 0xRRGGBBAA} colours and Minecraft's {@code 0xAARRGGBB}. */
public final class Colors {

    private Colors() {
    }

    /**
     * Converts a bridge colour to a Minecraft colour.
     *
     * @param rgba colour packed {@code 0xRRGGBBAA}
     * @return the same colour packed {@code 0xAARRGGBB}
     */
    public static int rgbaToArgb(final int rgba) {
        return (rgba >>> 8) | (rgba << 24);
    }

    /**
     * Scales the RGB channels of a Minecraft colour, keeping alpha.
     *
     * @param argb colour packed {@code 0xAARRGGBB}
     * @param factor multiplier in {@code [0, 1]}
     * @return the darkened colour
     */
    public static int scaleRgb(final int argb, final float factor) {
        final int r = (int) (((argb >>> 16) & 0xFF) * factor);
        final int g = (int) (((argb >>> 8) & 0xFF) * factor);
        final int b = (int) ((argb & 0xFF) * factor);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /**
     * Replaces the alpha of a Minecraft colour.
     *
     * @param argb colour packed {@code 0xAARRGGBB}
     * @param alpha new alpha in {@code [0, 1]}
     * @return the colour with the new alpha
     */
    public static int withAlpha(final int argb, final float alpha) {
        final int a = Math.round(Math.clamp(alpha, 0f, 1f) * 255f);
        return (argb & 0x00FFFFFF) | (a << 24);
    }
}
