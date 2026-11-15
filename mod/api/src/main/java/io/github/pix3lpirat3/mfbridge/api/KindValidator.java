package io.github.pix3lpirat3.mfbridge.api;

import org.jspecify.annotations.Nullable;

/**
 * Validates decoded object data of one kind before it enters the store. Runs on the client thread
 * inside the budgeted intake drain and must be cheap.
 *
 * @param <T> decoded data type
 */
@FunctionalInterface
public interface KindValidator<T> {
    /**
     * Checks one object's data.
     *
     * @param data decoded data
     * @return null when valid, otherwise a short reason; the write is then rejected with {@code
     *     invalid_message}
     */
    @Nullable String validate(T data);

    /** Returns a validator that accepts every value that decodes. */
    static <T> KindValidator<T> acceptAll() {
        return data -> null;
    }
}
