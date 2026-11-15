package io.github.pix3lpirat3.mfbridge.core;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import java.util.concurrent.CompletableFuture;

/**
 * Asks the player whether an unknown local device may connect, typically with an in-game toast.
 * The bridge polls the returned future from its tick, so it may complete on any thread and may stay
 * pending for as long as the player takes; the connection waits meanwhile. A future completed
 * exceptionally counts as {@link ApprovalDecision#DENY}.
 */
@FunctionalInterface
public interface DeviceApprover {
    /** Approver that allows every device once, for tests and headless use. */
    DeviceApprover ALLOW_ONCE = request -> CompletableFuture.completedFuture(ApprovalDecision.ALLOW_ONCE);

    /**
     * Starts an approval request. Called on the client thread; must not block.
     *
     * @return the eventual decision
     */
    @ClientThread
    CompletableFuture<ApprovalDecision> requestApproval(DeviceRequest request);
}
