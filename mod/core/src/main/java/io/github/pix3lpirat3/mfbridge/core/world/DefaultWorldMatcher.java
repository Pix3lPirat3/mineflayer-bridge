package io.github.pix3lpirat3.mfbridge.core.world;

import io.github.pix3lpirat3.mfbridge.api.WorldMatcher;
import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import java.util.Objects;

/**
 * The default matcher: normalized {@code server}, then {@code backend} only
 * when both sides have one, then {@code dimension}. Always gives a definite answer.
 */
@AnyThread
public final class DefaultWorldMatcher implements WorldMatcher {
    /** Shared instance; the matcher is stateless. */
    public static final DefaultWorldMatcher INSTANCE = new DefaultWorldMatcher();

    private DefaultWorldMatcher() {}

    /** Returns {@link Result#MATCH} or {@link Result#MISMATCH}, never {@link Result#ABSTAIN}. */
    @Override
    public Result match(WorldKey player, WorldKey other) {
        return sameServer(player, other) && player.dimension().equals(other.dimension()) ? Result.MATCH : Result.MISMATCH;
    }

    /**
     * Returns whether both keys name the same server (and backend, when both have one) but different
     * dimensions, for cross-dimension features such as waypoints.
     */
    public static boolean sameServerOtherDimension(WorldKey a, WorldKey b) {
        return sameServer(a, b) && !a.dimension().equals(b.dimension());
    }

    /** Returns whether both keys name the same server, and the same backend when both have one. */
    public static boolean sameServer(WorldKey a, WorldKey b) {
        if (!ServerKeys.normalize(a.server()).equals(ServerKeys.normalize(b.server()))) {
            return false;
        }
        return a.backend() == null || b.backend() == null || Objects.equals(a.backend(), b.backend());
    }
}
