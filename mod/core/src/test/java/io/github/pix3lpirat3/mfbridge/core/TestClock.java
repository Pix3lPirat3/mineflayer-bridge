package io.github.pix3lpirat3.mfbridge.core;

import java.util.concurrent.atomic.AtomicLong;

/** Manually advanced clock for protocol timers. */
final class TestClock implements BridgeClock {
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final AtomicLong wall = new AtomicLong(1_700_000_000_000L);

    void advanceMs(long ms) {
        nanos.addAndGet(ms * 1_000_000L);
        wall.addAndGet(ms);
    }

    @Override
    public long nanoTime() {
        return nanos.get();
    }

    @Override
    public long currentTimeMillis() {
        return wall.get();
    }
}
