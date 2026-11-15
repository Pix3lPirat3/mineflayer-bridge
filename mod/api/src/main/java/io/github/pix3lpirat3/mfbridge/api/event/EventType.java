package io.github.pix3lpirat3.mfbridge.api.event;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Identity of one event type. Instances are compared by identity and are meant to be held in
 * {@code static final} fields; each gets a dense index so the bus can find listeners without
 * hashing.
 *
 * @param <E> event payload type
 */
public final class EventType<E> {
    private static final AtomicInteger NEXT_INDEX = new AtomicInteger();

    private final String name;
    private final Class<E> payload;
    private final int index;

    private EventType(String name, Class<E> payload) {
        this.name = name;
        this.payload = payload;
        this.index = NEXT_INDEX.getAndIncrement();
    }

    /**
     * Creates a new event type. Thread-safe.
     *
     * @param name descriptive name such as {@code core:session.opened}, used only for diagnostics
     * @param payload class of the event payload
     */
    public static <E> EventType<E> create(String name, Class<E> payload) {
        return new EventType<>(name, payload);
    }

    /** Returns the descriptive name. */
    public String name() {
        return name;
    }

    /** Returns the payload class. */
    public Class<E> payload() {
        return payload;
    }

    int index() {
        return index;
    }

    /** Returns the descriptive name. */
    @Override
    public String toString() {
        return name;
    }
}
