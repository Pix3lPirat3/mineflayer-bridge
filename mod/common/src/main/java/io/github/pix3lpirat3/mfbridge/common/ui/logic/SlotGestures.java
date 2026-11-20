package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import io.github.pix3lpirat3.mfbridge.protocol.core.SlotClick;
import org.jspecify.annotations.Nullable;

/**
 * Maps inventory gestures to {@link SlotClick}s the way vanilla container screens read them;
 * every gesture is reported. Game-free.
 */
public final class SlotGestures {

    // Minecraft's button numbers as MouseButtonEvent gives them (InputConstants.MOUSE_BUTTON_*),
    // which start at 1; GLFW's start at 0.

    /** The left button. */
    public static final int BUTTON_LEFT = 1;
    /** The middle button. */
    public static final int BUTTON_MIDDLE = 2;
    /** The right button. */
    public static final int BUTTON_RIGHT = 3;

    private SlotGestures() {
    }

    /**
     * One gesture on a slot.
     *
     * @param click the reported gesture
     * @param hotbar the hotbar slot 0 to 8 for {@link SlotClick#NUMBER}, else null
     */
    public record Gesture(SlotClick click, @Nullable Integer hotbar) {
    }

    /**
     * Reads a mouse press.
     *
     * @param button the GLFW button
     * @param shift whether Shift is held
     * @param doubleClick whether vanilla reported this press as the second of a double click on the same slot
     * @param pickKey whether the button is bound to pick block, which vanilla treats as a middle click
     * @return the gesture, or null for buttons inventories ignore
     */
    public static @Nullable Gesture mouse(final int button, final boolean shift, final boolean doubleClick, final boolean pickKey) {
        if (pickKey || button == BUTTON_MIDDLE) {
            return new Gesture(SlotClick.MIDDLE, null);
        }
        return switch (button) {
            case BUTTON_LEFT -> new Gesture(doubleClick && !shift ? SlotClick.DOUBLE : shift ? SlotClick.SHIFT_LEFT : SlotClick.LEFT, null);
            case BUTTON_RIGHT -> new Gesture(shift ? SlotClick.SHIFT_RIGHT : SlotClick.RIGHT, null);
            default -> null;
        };
    }

    /**
     * Reads a key press over a slot.
     *
     * @param dropKey whether the key is bound to drop
     * @param control whether Ctrl (Cmd on macOS) is held, which drops the whole stack
     * @param hotbarIndex the hotbar slot 0 to 8 whose key this is, or -1
     * @return the gesture, or null when the key is not an inventory gesture
     */
    public static @Nullable Gesture key(final boolean dropKey, final boolean control, final int hotbarIndex) {
        if (dropKey) {
            return new Gesture(control ? SlotClick.DROP_ALL : SlotClick.DROP, null);
        }
        if (hotbarIndex >= 0 && hotbarIndex <= 8) {
            return new Gesture(SlotClick.NUMBER, hotbarIndex);
        }
        return null;
    }

    /**
     * Returns whether a gesture closes a view declared with {@code closeOnClick}. Drops
     * and number keys are not clicks, so they keep the menu open.
     *
     * @param click the gesture
     * @return true for mouse clicks
     */
    public static boolean closesMenu(final SlotClick click) {
        return switch (click) {
            case LEFT, RIGHT, MIDDLE, SHIFT_LEFT, SHIFT_RIGHT, DOUBLE -> true;
            case DROP, DROP_ALL, NUMBER -> false;
        };
    }
}
