package io.github.pix3lpirat3.mfbridge.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventBusTest {
    static final class Ping implements Cancellable {
        boolean cancelled;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    static final EventType<String> TEXT = EventType.create("test:text", String.class);
    static final EventType<Ping> PING = EventType.create("test:ping", Ping.class);

    @Test
    void dispatchesInPriorityThenSubscriptionOrder() {
        List<String> seen = new ArrayList<>();
        EventBus bus = new EventBus((t, e) -> seen.add("fail"));
        bus.subscribe(TEXT, EventPriority.LOW, s -> seen.add("low:" + s));
        bus.subscribe(TEXT, s -> seen.add("normal1:" + s));
        bus.subscribe(TEXT, EventPriority.HIGHEST, s -> seen.add("highest:" + s));
        bus.subscribe(TEXT, s -> seen.add("normal2:" + s));
        assertSame("x", bus.post(TEXT, "x"));
        assertEquals(List.of("highest:x", "normal1:x", "normal2:x", "low:x"), seen);
    }

    @Test
    void cancellationStopsLowerPriorityListeners() {
        List<String> seen = new ArrayList<>();
        EventBus bus = new EventBus((t, e) -> {});
        bus.subscribe(PING, EventPriority.HIGH, p -> {
            seen.add("high");
            p.cancel();
        });
        bus.subscribe(PING, EventPriority.LOW, p -> seen.add("low"));
        assertTrue(bus.post(PING, new Ping()).isCancelled());
        assertEquals(List.of("high"), seen);
    }

    @Test
    void unsubscribeAndFailureIsolation() {
        List<String> seen = new ArrayList<>();
        List<RuntimeException> failures = new ArrayList<>();
        EventBus bus = new EventBus((t, e) -> failures.add(e));
        assertFalse(bus.hasListeners(TEXT));
        Subscription a = bus.subscribe(TEXT, s -> {
            throw new IllegalStateException("boom");
        });
        Subscription b = bus.subscribe(TEXT, seen::add);
        assertTrue(bus.hasListeners(TEXT));
        bus.post(TEXT, "1");
        assertEquals(1, failures.size());
        a.close();
        a.close();
        bus.post(TEXT, "2");
        b.close();
        bus.post(TEXT, "3");
        assertEquals(List.of("1", "2"), seen);
        assertEquals(1, failures.size());
        assertFalse(bus.hasListeners(TEXT));
    }

    @Test
    void postWithoutListenersIsNoOp() {
        EventBus bus = new EventBus((t, e) -> {});
        Ping p = new Ping();
        assertSame(p, bus.post(PING, p));
        assertFalse(p.isCancelled());
    }
}
