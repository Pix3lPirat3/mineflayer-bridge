package io.github.pix3lpirat3.mfbridge.api;

import org.jspecify.annotations.Nullable;

/**
 * Failure carrying an error code (for example {@code not_owner} or {@code
 * conflict}). The bridge turns it into a {@code core:Error} on the wire; stack traces never cross the
 * wire.
 */
public final class BridgeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final boolean retryable;
    private final transient @Nullable Object details;

    /**
     * Creates an exception.
     *
     * @param code error code, such as {@code not_owner}
     * @param message human readable message, sent to the peer
     * @param retryable whether retrying unchanged may succeed
     * @param details MessagePack-encodable details (maps with string keys, lists, numbers, strings)
     *     or null
     */
    public BridgeException(String code, String message, boolean retryable, @Nullable Object details) {
        super(message, null, false, false);
        this.code = code;
        this.retryable = retryable;
        this.details = details;
    }

    /**
     * Creates a non-retryable exception without details.
     *
     * @param code error code, such as {@code not_owner}
     * @param message human readable message
     */
    public BridgeException(String code, String message) {
        this(code, message, false, null);
    }

    /** Returns the error code. */
    public String code() {
        return code;
    }

    /** Returns whether retrying unchanged may succeed. */
    public boolean retryable() {
        return retryable;
    }

    /** Returns the encodable details, or null. */
    public @Nullable Object details() {
        return details;
    }
}
