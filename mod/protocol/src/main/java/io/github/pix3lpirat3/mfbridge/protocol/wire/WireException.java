package io.github.pix3lpirat3.mfbridge.protocol.wire;

/** Thrown when bytes do not match the schema. {@link #code()} is a protocol error code. */
public final class WireException extends RuntimeException {
    private final String code;

    /** Creates an exception with a protocol error code and a message for the peer. */
    public WireException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    /** Returns an {@code invalid_message} exception. */
    public static WireException invalid(String message) {
        return new WireException("invalid_message", message);
    }

    /** Returns the protocol error code. */
    public String code() {
        return code;
    }
}
