package io.github.pix3lpirat3.mfbridge.common.seam;

/**
 * A snapshot of the main camera.
 *
 * @param position eye position in world space
 * @param yaw yaw in degrees, Minecraft convention (0 faces south, positive turns west)
 * @param pitch pitch in degrees, positive looks down
 */
public record CameraState(Vec3d position, float yaw, float pitch) {
}
