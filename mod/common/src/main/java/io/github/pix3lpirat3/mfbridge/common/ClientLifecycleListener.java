package io.github.pix3lpirat3.mfbridge.common;

import io.github.pix3lpirat3.mfbridge.common.settings.BridgeSettings;

/**
 * Receives client lifecycle callbacks from {@link BridgeClient}. This is the seam where the bridge core
 * attaches: it learns about ticks, world changes and settings changes through this interface.
 *
 * <p>All methods are called on the client thread and must not block. Every method has an empty default.
 */
public interface ClientLifecycleListener {

    /** A listener that ignores every callback. */
    ClientLifecycleListener NONE = new ClientLifecycleListener() {
    };

    /**
     * Called once after the client finished starting.
     *
     * @param settings the settings loaded from disk
     */
    default void onClientStarted(final BridgeSettings settings) {
    }

    /** Called once when the client begins shutting down. */
    default void onClientStopping() {
    }

    /** Called at the end of every client tick, 20 times per second, in and out of worlds. */
    default void onTick() {
    }

    /**
     * Called when the player has joined a world.
     *
     * @param serverKey {@code host:port} for remote servers, or {@code singleplayer:<save folder>} for the
     *     integrated server
     * @param dimension the dimension id, such as {@code minecraft:overworld}
     */
    default void onWorldJoin(final String serverKey, final String dimension) {
    }

    /**
     * Called when the player changed dimension without leaving the server.
     *
     * @param serverKey the same key passed to {@link #onWorldJoin}
     * @param dimension the new dimension id
     */
    default void onDimensionChange(final String serverKey, final String dimension) {
    }

    /** Called when the player has left the world, before any later {@link #onWorldJoin}. */
    default void onWorldLeave() {
    }

    /**
     * Called after the player saved new settings.
     *
     * @param settings the new settings
     */
    default void onSettingsChanged(final BridgeSettings settings) {
    }
}
