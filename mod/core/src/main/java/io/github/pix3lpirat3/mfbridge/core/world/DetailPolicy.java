package io.github.pix3lpirat3.mfbridge.core.world;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;

/**
 * Decides when a bot gets {@code interest.detail} {@code reduced}. Every attached bot is
 * reduced while more than {@link #MANY_BOTS} are attached; otherwise a bot is reduced while it is far
 * from the player, with hysteresis so a bot walking along the boundary does not flap.
 */
@AnyThread
public final class DetailPolicy {
    /** Attached bots beyond which every attached bot gets reduced detail. */
    public static final int MANY_BOTS = 20;

    /** Distance from the player beyond which a bot becomes reduced, in blocks. */
    public static final double FAR_BLOCKS = 256;

    /** Distance from the player within which a reduced bot returns to full detail, in blocks. */
    public static final double NEAR_BLOCKS = 224;

    private DetailPolicy() {}

    /**
     * Decides one session's detail.
     *
     * @param wasReduced the session's current detail
     * @param attachedBots number of attached bot sessions
     * @param attached whether this session is attached
     * @param distanceSq squared distance between the player and the bot, in blocks squared; NaN when
     *     unknown (no position reported, not in the same world)
     * @return whether the detail should be {@code reduced}
     */
    public static boolean reduced(boolean wasReduced, int attachedBots, boolean attached, double distanceSq) {
        if (!attached) {
            return false;
        }
        if (attachedBots > MANY_BOTS) {
            return true;
        }
        if (Double.isNaN(distanceSq)) {
            return false;
        }
        double limit = wasReduced ? NEAR_BLOCKS : FAR_BLOCKS;
        return distanceSq > limit * limit;
    }
}
