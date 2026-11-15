package io.github.pix3lpirat3.mfbridge.api.event;

/**
 * Implemented by events that listeners may cancel. Once cancelled, the bus stops dispatching the
 * event to lower-priority listeners and the poster decides what cancellation means.
 */
public interface Cancellable {
    /** Returns whether a listener cancelled this event. */
    boolean isCancelled();

    /** Cancels this event. Idempotent. */
    void cancel();
}
