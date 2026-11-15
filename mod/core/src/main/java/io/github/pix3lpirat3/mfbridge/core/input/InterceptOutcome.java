package io.github.pix3lpirat3.mfbridge.core.input;

import io.github.pix3lpirat3.mfbridge.core.decl.InterceptDecl;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * What {@link InterceptControls#dispatch} did with one interaction.
 *
 * @param cancelledBy the rule that cancelled the vanilla interaction, or null when vanilla proceeds
 * @param result the cancelling bot's {@code invocation.result}, completing on the client thread, or
 *     failing with a {@code BridgeException} ({@code timeout}, {@code disconnected}); null when
 *     nothing cancelled
 * @param delivered how many rules were sent {@code intercept.event}
 */
public record InterceptOutcome(
        @Nullable InterceptDecl cancelledBy, @Nullable CompletableFuture<InvocationResult> result, int delivered) {
    /** Outcome of an interaction no rule heard about. */
    public static final InterceptOutcome NONE = new InterceptOutcome(null, null, 0);

    /**
     * Returns whether the vanilla interaction must not run.
     *
     * @return true when a rule cancelled it
     */
    public boolean cancelled() {
        return cancelledBy != null;
    }
}
