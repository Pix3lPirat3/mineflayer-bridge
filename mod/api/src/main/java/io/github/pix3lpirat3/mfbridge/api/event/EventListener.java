package io.github.pix3lpirat3.mfbridge.api.event;

/**
 * Receives events of one type on the posting thread (the client thread for bridge events).
 *
 * @param <E> event payload type
 */
@FunctionalInterface
public interface EventListener<E> {
    /** Handles one event. Must not block. */
    void on(E event);
}
