package io.github.pix3lpirat3.mfbridge.core.input;

import org.jspecify.annotations.Nullable;

/**
 * A bot's answer to an invoked context action or command ({@code invocation.result}).
 *
 * @param ok whether the handler succeeded
 * @param message the bot's message, for example the rejection reason to show as a toast, or null
 * @param quiet whether to show nothing: the player cancelled a form the handler waited on
 */
public record InvocationResult(boolean ok, @Nullable String message, boolean quiet) {

    /**
     * An answer the player should hear about.
     *
     * @param ok whether the handler succeeded
     * @param message the bot's message, or null
     */
    public InvocationResult(final boolean ok, final @Nullable String message) {
        this(ok, message, false);
    }
}
