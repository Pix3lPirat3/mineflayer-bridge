package io.github.pix3lpirat3.mfbridge.core.diag;

/**
 * The most recent samples of one cost metric, in nanoseconds. Recording never allocates.
 *
 * <p>Threading: one writer and readers on the same thread (the client thread, which in Minecraft
 * 26.3 is also the render thread).
 */
public final class TimingRing {
    private final long[] samples;
    private int next;
    private int count;

    /**
     * Creates a ring.
     *
     * @param capacity number of samples kept, at least 1
     * @throws IllegalArgumentException when {@code capacity} is below 1
     */
    public TimingRing(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
        }
        samples = new long[capacity];
    }

    /** Records one sample, replacing the oldest once the ring is full. */
    public void record(long nanos) {
        samples[next] = nanos;
        next = (next + 1) % samples.length;
        if (count < samples.length) {
            count++;
        }
    }

    /** Returns the number of samples held. */
    public int count() {
        return count;
    }

    /** Returns the newest sample, or 0 when empty. */
    public long last() {
        return count == 0 ? 0 : samples[(next - 1 + samples.length) % samples.length];
    }

    /** Returns the mean of the held samples, or 0 when empty. */
    public long average() {
        if (count == 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += samples[i];
        }
        return sum / count;
    }

    /** Returns the largest held sample, or 0 when empty. */
    public long max() {
        long max = 0;
        for (int i = 0; i < count; i++) {
            max = Math.max(max, samples[i]);
        }
        return max;
    }

    /** Forgets every sample. */
    public void clear() {
        next = 0;
        count = 0;
    }
}
