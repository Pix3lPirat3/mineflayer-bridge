package io.github.pix3lpirat3.mfbridge.common.render.command;

import io.github.pix3lpirat3.mfbridge.common.seam.RenderFrame;

/**
 * Supplies the immediate-tier draw commands for each frame.
 *
 * <p>Called on the render thread once per frame. Implementations add what does not change as stable
 * lists, the same instance each frame, so the steady state allocates nothing.
 */
@FunctionalInterface
public interface WorldRenderSource {

    /** A source that never draws anything. */
    WorldRenderSource EMPTY = (frame, out) -> {
    };

    /**
     * Adds the commands to draw this frame.
     *
     * @param frame the current frame
     * @param out where the commands go; empty when called
     */
    void frameCommands(RenderFrame frame, FrameCommands out);
}
