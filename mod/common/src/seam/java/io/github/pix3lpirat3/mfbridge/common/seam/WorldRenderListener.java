package io.github.pix3lpirat3.mfbridge.common.seam;

/** Called once per frame after the game has drawn translucent world geometry. */
@FunctionalInterface
public interface WorldRenderListener {

    /**
     * Draws world-space content for this frame. Runs on the render thread.
     *
     * @param frame the current frame, valid only during this call
     */
    void onWorldRender(RenderFrame frame);
}
