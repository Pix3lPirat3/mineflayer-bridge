package io.github.pix3lpirat3.mfbridge.common.seam;

/**
 * Per-frame world render state handed to {@link WorldRenderListener}s and {@link RenderBackend}s.
 *
 * <p>Implementations are created by a {@link GameBridge} and may carry game-specific state; a backend
 * only accepts frames from the bridge it is paired with. A frame is valid only during the callback
 * that received it, on the render thread.
 */
public interface RenderFrame {

    /**
     * Returns the camera x position this frame. Draw coordinates are made camera-relative with it.
     *
     * @return camera x, in blocks
     */
    double cameraX();

    /**
     * Returns the camera y position this frame.
     *
     * @return camera y, in blocks
     */
    double cameraY();

    /**
     * Returns the camera z position this frame.
     *
     * @return camera z, in blocks
     */
    double cameraZ();

    /**
     * Returns the partial tick used to interpolate entities this frame.
     *
     * @return fraction of a tick in {@code [0, 1]}
     */
    float partialTick();
}
