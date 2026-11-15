package io.github.pix3lpirat3.mfbridge.core.sample;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import org.jspecify.annotations.Nullable;

/**
 * Client samples. The client asks what is wanted, then feeds the current
 * values every tick while in a world; the core sends {@code client.sample} to each bot that asked,
 * holds the permission for each field ({@code client.sample.position}, {@code .target},
 * {@code .inventory}), is attached and is in the player's world, at no more than the lower of the
 * bot's rate and the player's cap. A sample equal to the previous one sent is skipped.
 */
@ClientThread
public interface ClientSampleControls {
    /** The player's default cap, in samples per second. */
    float DEFAULT_MAX_RATE_HZ = 4f;
    /** The highest cap the player can set, in samples per second. */
    float MAX_RATE_HZ = 20f;

    /** Returns whether any eligible bot wants the position, yaw and pitch. */
    boolean wantsPosition();

    /** Returns whether any eligible bot wants the crosshair target. */
    boolean wantsTarget();

    /** Returns whether any eligible bot wants the held item. */
    boolean wantsHeldItem();

    /**
     * Feeds the current values. Values older than one second are not sent, so the client simply
     * stops feeding when the player leaves the world.
     *
     * @param position the player's position in blocks, or null when unknown
     * @param yaw view yaw in degrees (Minecraft convention)
     * @param pitch view pitch in degrees, positive looking down
     * @param target the block or entity under the crosshair, or null when nothing is in reach
     * @param heldItem the main-hand item, or null when the hand is empty
     */
    void feed(@Nullable Vec3d position, float yaw, float pitch, @Nullable Target target, @Nullable SlotItem heldItem);

    /** Returns the player's cap in samples per second. */
    float maxRateHz();

    /**
     * Sets the player's cap.
     *
     * @param hz samples per second, clamped to 0.1..{@link #MAX_RATE_HZ}
     */
    void setMaxRateHz(float hz);
}
