package io.github.pix3lpirat3.mfbridge.common.seam;

import io.github.pix3lpirat3.mfbridge.common.render.command.FrameCommands;

/**
 * Turns draw commands into GPU work for one graphics API family.
 *
 * <p>All methods run on the render thread.
 */
public interface RenderBackend {

    /**
     * Returns the implementation id, such as {@code blaze3d}.
     *
     * @return a stable, lower-case identifier
     */
    String id();

    /**
     * Draws the immediate-tier commands for one frame.
     *
     * @param frame the current frame, created by the paired {@link GameBridge}
     * @param commands the commands to draw. Stable lists may be remembered by identity to reuse what
     *     was prepared for them; the extras are read during the call and not retained
     * @throws IllegalArgumentException if the frame was not created by the paired game bridge
     */
    void drawImmediate(RenderFrame frame, FrameCommands commands);

    /**
     * Sets whether text labels, including waypoint names, are drawn.
     *
     * @param visible {@code true} to draw labels
     */
    void setLabelsVisible(boolean visible);

    /** Releases GPU resources. The backend must not be used afterwards. */
    void close();
}
