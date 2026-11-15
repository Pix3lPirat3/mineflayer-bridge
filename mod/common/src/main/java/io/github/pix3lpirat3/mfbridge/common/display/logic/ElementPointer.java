package io.github.pix3lpirat3.mfbridge.common.display.logic;

import io.github.pix3lpirat3.mfbridge.protocol.core.ElementInput;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The pointer state behind element interactions: which element the
 * crosshair is on, hover enter and leave, clicks and drags. Drags start on a primary press and keep
 * their element until release, even when the crosshair leaves it, so sliders can be dragged past
 * their ends. Drag moves are reported only when the point moved by at least {@link #DRAG_EPSILON}.
 *
 * <p>Every event goes to the {@link Sink}; the core drops inputs the element did not declare.
 * Confined to one thread.
 */
public final class ElementPointer {
    /** Smallest drag movement reported, blocks. */
    public static final double DRAG_EPSILON = 0.005d;

    /** Receives element events. */
    @FunctionalInterface
    public interface Sink {
        /**
         * Delivers one event.
         *
         * @param element the element's object key
         * @param input what happened
         * @param delta scroll steps, or null
         * @param key vanilla key name, or null
         * @param local whether {@code lx, ly, lz} carry a local hit point
         * @param lx local hit point, blocks
         * @param ly local hit point, blocks
         * @param lz local hit point, blocks
         */
        void send(String element, ElementInput input, @Nullable Float delta, @Nullable String key, boolean local, double lx,
                double ly, double lz);
    }

    private final Sink sink;
    private @Nullable String hovered;
    private @Nullable String dragging;
    private double lastX;
    private double lastY;
    private double lastZ;

    /**
     * Creates a pointer.
     *
     * @param sink receives the events
     */
    public ElementPointer(final Sink sink) {
        this.sink = sink;
    }

    /**
     * Returns the element under the crosshair, or the one being dragged.
     *
     * @return its key, or null
     */
    public @Nullable String hovered() {
        return dragging != null ? dragging : hovered;
    }

    /**
     * Returns whether a drag is running.
     *
     * @return true between a primary press on an element and its release
     */
    public boolean dragging() {
        return dragging != null;
    }

    /**
     * Reports what the crosshair is on. Changes send hover leave for the old element and hover
     * enter for the new one; while dragging, the drag keeps its element.
     *
     * @param element the element's key, or null for none
     */
    public void hover(final @Nullable String element) {
        if (Objects.equals(element, hovered)) {
            return;
        }
        final String old = hovered;
        hovered = element;
        if (old != null) {
            sink.send(old, ElementInput.HOVER_LEAVE, null, null, false, 0, 0, 0);
        }
        if (element != null) {
            sink.send(element, ElementInput.HOVER_ENTER, null, null, false, 0, 0, 0);
        }
    }

    /**
     * Reports a mouse button press.
     *
     * @param input {@link ElementInput#PRIMARY}, {@link ElementInput#SECONDARY} or {@link ElementInput#MIDDLE}
     * @param lx local hit point, blocks
     * @param ly local hit point, blocks
     * @param lz local hit point, blocks
     * @return whether an element took the press
     */
    public boolean press(final ElementInput input, final double lx, final double ly, final double lz) {
        final String target = hovered;
        if (target == null) {
            return false;
        }
        sink.send(target, input, null, null, true, lx, ly, lz);
        if (input == ElementInput.PRIMARY && dragging == null) {
            dragging = target;
            lastX = lx;
            lastY = ly;
            lastZ = lz;
            sink.send(target, ElementInput.DRAG_START, null, null, true, lx, ly, lz);
        }
        return true;
    }

    /**
     * Reports where the drag point is now; ignored when no drag runs or it barely moved.
     *
     * @param lx local point, blocks
     * @param ly local point, blocks
     * @param lz local point, blocks
     */
    public void move(final double lx, final double ly, final double lz) {
        final String target = dragging;
        if (target == null) {
            return;
        }
        final double dx = lx - lastX;
        final double dy = ly - lastY;
        final double dz = lz - lastZ;
        if (dx * dx + dy * dy + dz * dz < DRAG_EPSILON * DRAG_EPSILON) {
            return;
        }
        lastX = lx;
        lastY = ly;
        lastZ = lz;
        sink.send(target, ElementInput.DRAG, null, null, true, lx, ly, lz);
    }

    /**
     * Reports the primary button's release; ends a running drag at its last point.
     *
     * @return whether a drag ended
     */
    public boolean release() {
        final String target = dragging;
        if (target == null) {
            return false;
        }
        dragging = null;
        sink.send(target, ElementInput.DRAG_END, null, null, true, lastX, lastY, lastZ);
        return true;
    }

    /**
     * Reports scroll steps on the hovered element.
     *
     * @param steps whole scroll steps, positive up
     * @return whether an element took them
     */
    public boolean scroll(final int steps) {
        final String target = hovered();
        if (target == null || steps == 0) {
            return false;
        }
        sink.send(target, ElementInput.SCROLL, (float) steps, null, false, 0, 0, 0);
        return true;
    }

    /**
     * Reports a key press on the hovered element.
     *
     * @param key vanilla key name, for example {@code key.keyboard.q}
     * @return whether an element was hovered
     */
    public boolean key(final String key) {
        final String target = hovered();
        if (target == null) {
            return false;
        }
        sink.send(target, ElementInput.KEY, null, key, false, 0, 0, 0);
        return true;
    }

    /** Ends hover and any drag, sending leave and drag end, for example when displays are hidden. */
    public void reset() {
        release();
        hover(null);
    }
}
