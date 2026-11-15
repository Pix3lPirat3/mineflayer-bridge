package io.github.pix3lpirat3.mfbridge.api;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;

/**
 * Live value of a registered {@link Setting}.
 *
 * @param <T> value type
 */
public interface SettingValue<T> {
    /** Returns the setting definition. */
    @AnyThread
    Setting<T> setting();

    /** Returns the current value. */
    @AnyThread
    T get();

    /**
     * Replaces the value.
     *
     * @throws IllegalArgumentException when the value is not of the setting's type
     */
    @ClientThread
    void set(T value);
}
