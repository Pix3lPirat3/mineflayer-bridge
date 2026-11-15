package io.github.pix3lpirat3.mfbridge.common.display.logic;

/**
 * Turns a particle emitter's rate into whole bursts per tick ({@code
 * core:particle-emitter}). The fraction carries over between ticks, so low rates still fire, and a
 * long pause fires at most {@link #MAX_BURSTS_PER_STEP} bursts instead of a flood. Confined to one
 * thread.
 */
public final class BurstClock {
    /** Most bursts one {@link #advance} returns. */
    public static final int MAX_BURSTS_PER_STEP = 8;

    private double carry;

    /** Creates a clock with nothing carried over. */
    public BurstClock() {
    }

    /**
     * Advances the clock.
     *
     * @param ratePerSecond bursts per second; zero, negative or non-finite rates never fire
     * @param elapsedMs time since the last call, ms
     * @return whole bursts due now, at most {@link #MAX_BURSTS_PER_STEP}
     */
    public int advance(final double ratePerSecond, final long elapsedMs) {
        if (!(ratePerSecond > 0d) || !Double.isFinite(ratePerSecond) || elapsedMs <= 0L) {
            return 0;
        }
        carry += ratePerSecond * elapsedMs / 1000d;
        final int bursts = (int) Math.min(MAX_BURSTS_PER_STEP, Math.floor(carry));
        carry = Math.min(carry - bursts, MAX_BURSTS_PER_STEP);
        return bursts;
    }

    /** Drops the carried fraction. */
    public void reset() {
        carry = 0d;
    }
}
