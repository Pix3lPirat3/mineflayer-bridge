package io.github.pix3lpirat3.mfbridge.core;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;

/**
 * Time source for protocol timers (TTL, heartbeat, acks, resume window, request timeouts) and
 * wall-clock stamps. Injectable so tests can advance time. Tick time budgets always use {@link
 * System#nanoTime()}, never this clock, because they measure real CPU time.
 */
@AnyThread
public interface BridgeClock {
    /** The system clock. */
    BridgeClock SYSTEM = new BridgeClock() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    };

    /** Returns a monotonic time in nanoseconds with an arbitrary origin. Must be thread-safe. */
    long nanoTime();

    /** Returns wall-clock time in milliseconds since the epoch, used for last-seen stamps. */
    long currentTimeMillis();

    /** Returns {@link #nanoTime()} in milliseconds: the time every protocol timer is measured in. */
    default long millis() {
        return nanoTime() / 1_000_000L;
    }
}
