package io.github.pix3lpirat3.mfbridge.common.seam;

/** Called once per frame while the HUD is drawn. */
@FunctionalInterface
public interface HudRenderListener {

    /**
     * Draws HUD content. Runs on the render thread.
     *
     * @param canvas the drawing surface, valid only during this call
     * @param partialTick fraction of a tick in {@code [0, 1]}
     */
    void onHudRender(HudCanvas canvas, float partialTick);
}
