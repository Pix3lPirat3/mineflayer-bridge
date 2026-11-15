package io.github.pix3lpirat3.mfbridge.common.ui.input;

import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickResult;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The interaction of one pick kind. Client thread only. */
interface PickMode {

    /** Samples the crosshair. Called every frame and before confirming. */
    void update();

    /** Refreshes slower state such as nearby candidates. Called once per client tick. */
    default void tick() {
    }

    /**
     * Handles a primary click.
     *
     * @return true when the click should confirm the pick
     */
    boolean click();

    /**
     * Undoes the last step: Backspace or a secondary click.
     *
     * @return whether anything was undone
     */
    boolean undo();

    /**
     * Handles scroll input.
     *
     * @param steps whole scroll steps, positive away from the player or up
     */
    void scroll(int steps);

    /**
     * Returns the result the pick would confirm with now.
     *
     * @return the result, or null when the current candidate is missing or breaks a constraint
     */
    @Nullable PickResult result();

    /**
     * Returns a one-line description of the current candidate for the hint.
     *
     * @return the status text
     */
    String status();

    /**
     * Returns whether the current candidate satisfies the constraints.
     *
     * @return false when the preview is drawn red
     */
    boolean valid();

    /**
     * Adds the preview geometry for this frame.
     *
     * @param out the frame's command list
     */
    void draw(List<DrawCommand> out);
}
