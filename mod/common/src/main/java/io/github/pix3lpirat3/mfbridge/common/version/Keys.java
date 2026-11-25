package io.github.pix3lpirat3.mfbridge.common.version;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.SlotGestures;

/**
 * The game's key and mouse button numbers, read from the running version's {@link InputConstants}.
 * They changed in 26.3 (SDL codes instead of GLFW ones), and javac copies a constant into every
 * class that names it, so shared code must never name {@code InputConstants.KEY_*} or
 * {@code MOUSE_BUTTON_*} directly. These are set at startup, so they are not copied, and as static
 * finals they still cost nothing.
 */
public final class Keys {

    public static final int KEY_A = read("KEY_A");
    public static final int KEY_B = read("KEY_B");
    public static final int KEY_C = read("KEY_C");
    public static final int KEY_D = read("KEY_D");
    public static final int KEY_K = read("KEY_K");
    public static final int KEY_R = read("KEY_R");
    public static final int KEY_S = read("KEY_S");
    public static final int KEY_V = read("KEY_V");
    public static final int KEY_W = read("KEY_W");
    public static final int KEY_Y = read("KEY_Y");
    public static final int KEY_Z = read("KEY_Z");
    public static final int KEY_COMMA = read("KEY_COMMA");
    public static final int KEY_ESCAPE = read("KEY_ESCAPE");
    public static final int KEY_BACKSPACE = read("KEY_BACKSPACE");
    public static final int KEY_DELETE = read("KEY_DELETE");
    public static final int KEY_UP = read("KEY_UP");
    public static final int KEY_DOWN = read("KEY_DOWN");
    public static final int KEY_LEFT = read("KEY_LEFT");
    public static final int KEY_RIGHT = read("KEY_RIGHT");
    public static final int KEY_PAGEUP = read("KEY_PAGEUP");
    public static final int KEY_PAGEDOWN = read("KEY_PAGEDOWN");
    public static final int KEY_LSHIFT = read("KEY_LSHIFT");
    public static final int KEY_RSHIFT = read("KEY_RSHIFT");
    public static final int KEY_LCONTROL = read("KEY_LCONTROL");
    public static final int KEY_RCONTROL = read("KEY_RCONTROL");
    public static final int KEY_LALT = read("KEY_LALT");
    public static final int KEY_RALT = read("KEY_RALT");
    public static final int MOUSE_BUTTON_LEFT = read("MOUSE_BUTTON_LEFT");
    public static final int MOUSE_BUTTON_MIDDLE = read("MOUSE_BUTTON_MIDDLE");
    public static final int MOUSE_BUTTON_RIGHT = read("MOUSE_BUTTON_RIGHT");

    private Keys() {
    }

    private static int read(final String name) {
        try {
            return InputConstants.class.getField(name).getInt(null);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Mineflayer Bridge: this game version has no InputConstants." + name, e);
        }
    }

    /**
     * Returns a mouse event's button as {@link SlotGestures} counts them, or -1 for any other.
     *
     * @param button the game's button number
     * @return {@link SlotGestures#BUTTON_LEFT}, {@code BUTTON_MIDDLE}, {@code BUTTON_RIGHT} or -1
     */
    public static int button(final int button) {
        if (button == MOUSE_BUTTON_LEFT) {
            return SlotGestures.BUTTON_LEFT;
        }
        if (button == MOUSE_BUTTON_MIDDLE) {
            return SlotGestures.BUTTON_MIDDLE;
        }
        return button == MOUSE_BUTTON_RIGHT ? SlotGestures.BUTTON_RIGHT : -1;
    }
}
