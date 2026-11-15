package io.github.pix3lpirat3.mfbridge.common.seam;

import java.util.Optional;

/**
 * Everything features need from the running game: render and HUD hooks, camera, raycasts and
 * notifications. Implemented once per supported game version.
 *
 * <p>Listeners are registered on the client thread, normally during client start. Queries run on the
 * client thread unless stated otherwise.
 */
public interface GameBridge {

    /**
     * Returns the implementation id, such as {@code game-26x}.
     *
     * @return a stable, lower-case identifier
     */
    String id();

    /**
     * Registers a listener called after translucent world geometry every frame.
     *
     * @param listener the listener; called on the render thread
     */
    void addWorldRenderListener(WorldRenderListener listener);

    /**
     * Registers a listener called while the HUD is drawn every frame.
     *
     * @param listener the listener; called on the render thread
     */
    void addHudRenderListener(HudRenderListener listener);

    /**
     * Returns the current camera, or empty when no world is loaded.
     *
     * @return the camera snapshot
     */
    Optional<CameraState> camera();

    /**
     * Casts a ray from the camera along the view direction against blocks and entities.
     *
     * @param maxDistance maximum ray length, in blocks; must be positive
     * @return the closest hit, or empty when nothing was hit or no world is loaded
     * @throws IllegalArgumentException if {@code maxDistance} is not positive
     */
    Optional<RaycastHit> raycast(double maxDistance);

    /**
     * Shows a toast notification. Safe to call from any thread; the toast is queued on the client thread.
     *
     * @param notification what to show
     */
    void showToast(Notification notification);
}
