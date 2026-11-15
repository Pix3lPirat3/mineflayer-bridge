package io.github.pix3lpirat3.mfbridge.common.ui.logic;

/**
 * Turns fractional scroll input into whole steps: the fraction carries
 * over between events and resets when the direction flips. Confined to one thread.
 */
public final class ScrollAccumulator {

    private double sum;

    /** Creates an accumulator with nothing carried over. */
    public ScrollAccumulator() {
    }

    /**
     * Adds scroll input.
     *
     * @param delta the scroll amount, positive up
     * @return the whole steps completed by this input, signed
     */
    public int add(final double delta) {
        if (delta == 0d || Double.isNaN(delta)) {
            return 0;
        }
        if (sum != 0d && Math.signum(sum) != Math.signum(delta)) {
            sum = 0d;
        }
        sum += delta;
        final int steps = (int) sum;
        sum -= steps;
        return steps;
    }

    /** Drops the carried fraction. */
    public void reset() {
        sum = 0d;
    }
}
