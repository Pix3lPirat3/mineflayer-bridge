package io.github.pix3lpirat3.mfbridge.api.event;

/** Handle returned by {@link EventBus#subscribe}; closing it removes the listener. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {
    /** Removes the listener. Idempotent and thread-safe. */
    @Override
    void close();
}
