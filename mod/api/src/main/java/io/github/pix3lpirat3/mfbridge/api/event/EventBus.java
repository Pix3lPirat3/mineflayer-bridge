package io.github.pix3lpirat3.mfbridge.api.event;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import java.util.Arrays;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

/**
 * Typed synchronous event bus. Listeners live in copy-on-write arrays sorted by
 * priority, so {@link #post} allocates nothing and uses no reflection. Subscribing and unsubscribing
 * are thread-safe; posting runs listeners on the posting thread.
 */
public final class EventBus {
    private static final Registration[] EMPTY = new Registration[0];

    private final BiConsumer<EventType<?>, RuntimeException> failureHandler;
    private final Object lock = new Object();

    // Indexed by EventType.index(); replaced wholesale under lock, read without locking.
    private volatile Registration[][] listeners = new Registration[0][];

    private long nextOrder;

    /**
     * Creates a bus.
     *
     * @param failureHandler receives exceptions thrown by listeners; dispatch continues with the next
     *     listener
     */
    public EventBus(BiConsumer<EventType<?>, RuntimeException> failureHandler) {
        this.failureHandler = failureHandler;
    }

    /**
     * Adds a listener.
     *
     * @return a handle that removes the listener when closed
     */
    @AnyThread
    public <E> Subscription subscribe(EventType<E> type, EventPriority priority, EventListener<? super E> listener) {
        Registration reg;
        synchronized (lock) {
            reg = new Registration(type, priority, nextOrder++, listener);
            int index = type.index();
            Registration[][] all = listeners;
            if (index >= all.length) {
                all = Arrays.copyOf(all, Math.max(index + 1, all.length * 2));
            } else {
                all = all.clone();
            }
            Registration[] current = all[index] == null ? EMPTY : all[index];
            Registration[] next = Arrays.copyOf(current, current.length + 1);
            next[current.length] = reg;
            Arrays.sort(next, (a, b) -> a.priority != b.priority
                    ? Integer.compare(a.priority.ordinal(), b.priority.ordinal())
                    : Long.compare(a.order, b.order));
            all[index] = next;
            listeners = all;
        }
        return reg;
    }

    /** Adds a listener with {@link EventPriority#NORMAL}. */
    @AnyThread
    public <E> Subscription subscribe(EventType<E> type, EventListener<? super E> listener) {
        return subscribe(type, EventPriority.NORMAL, listener);
    }

    private void remove(Registration reg) {
        synchronized (lock) {
            int index = reg.type.index();
            Registration[][] all = listeners;
            if (index >= all.length || all[index] == null) {
                return;
            }
            Registration[] current = all[index];
            int at = -1;
            for (int i = 0; i < current.length; i++) {
                if (current[i] == reg) {
                    at = i;
                    break;
                }
            }
            if (at < 0) {
                return;
            }
            Registration[] next = new Registration[current.length - 1];
            System.arraycopy(current, 0, next, 0, at);
            System.arraycopy(current, at + 1, next, at, current.length - at - 1);
            all = all.clone();
            all[index] = next;
            listeners = all;
        }
    }

    /**
     * Dispatches an event to its listeners in priority order on the calling thread. For {@link
     * Cancellable} events, dispatch stops once the event is cancelled.
     *
     * @return the same event, so callers can inspect cancellation
     */
    @AnyThread
    @SuppressWarnings("unchecked")
    public <E> E post(EventType<E> type, E event) {
        Registration[][] all = listeners;
        int index = type.index();
        if (index >= all.length) {
            return event;
        }
        Registration[] regs = all[index];
        if (regs == null) {
            return event;
        }
        @Nullable Cancellable cancellable = event instanceof Cancellable c ? c : null;
        for (Registration reg : regs) {
            if (cancellable != null && cancellable.isCancelled()) {
                break;
            }
            try {
                ((EventListener<Object>) reg.listener).on(event);
            } catch (RuntimeException e) {
                failureHandler.accept(type, e);
            }
        }
        return event;
    }

    /** Returns whether any listener is subscribed to {@code type}, so posters can skip building events. */
    @AnyThread
    public boolean hasListeners(EventType<?> type) {
        Registration[][] all = listeners;
        int index = type.index();
        return index < all.length && all[index] != null && all[index].length > 0;
    }

    private final class Registration implements Subscription {
        final EventType<?> type;
        final EventPriority priority;
        final long order;
        final EventListener<?> listener;

        Registration(EventType<?> type, EventPriority priority, long order, EventListener<?> listener) {
            this.type = type;
            this.priority = priority;
            this.order = order;
            this.listener = listener;
        }

        @Override
        public void close() {
            remove(this);
        }
    }
}
