package io.github.pix3lpirat3.mfbridge.common.ui.screen;

/**
 * Text colours shared by the bridge's vanilla-styled screens, packed {@code 0xAARRGGBB}. One place so
 * every screen uses the same tones for labels, headers and problems.
 */
public final class UiColors {

    /** Primary text. */
    public static final int TEXT = 0xFFFFFFFF;
    /** Secondary text: field labels, details and hints. */
    public static final int LABEL = 0xFFA0A0A0;
    /** Section headers and names. */
    public static final int HEADER = 0xFF9FC2FF;
    /** Healthy or active state. */
    public static final int GOOD = 0xFF7CDB7C;
    /** Something the player should notice. */
    public static final int WARNING = 0xFFFFC857;
    /** Errors and failures. */
    public static final int ERROR = 0xFFFF5555;
    /** Titles drawn on vanilla container textures, without shadow. */
    public static final int CONTAINER_LABEL = 0xFF404040;

    private UiColors() {
    }
}
