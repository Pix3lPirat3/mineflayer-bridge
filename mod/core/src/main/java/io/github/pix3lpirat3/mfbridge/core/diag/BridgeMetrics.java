package io.github.pix3lpirat3.mfbridge.core.diag;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;

/**
 * Cost metrics of the bridge per tick and per frame, plus renderer gauges.
 * Every method is allocation free, so the renderer can report each frame.
 *
 * <p>Threading: client thread; in Minecraft 26.3 the render thread is the same thread.
 */
@ClientThread
public final class BridgeMetrics {
    /** Samples kept per metric: a little over two seconds of frames at 120 fps. */
    public static final int SAMPLES = 256;

    private final TimingRing[] rings = new TimingRing[Metric.values().length];
    private final long[] gauges = new long[Gauge.values().length];

    /** Creates empty metrics. */
    public BridgeMetrics() {
        for (int i = 0; i < rings.length; i++) {
            rings[i] = new TimingRing(SAMPLES);
        }
    }

    /**
     * Records one measurement.
     *
     * @param nanos elapsed time in nanoseconds
     */
    public void record(Metric metric, long nanos) {
        rings[metric.ordinal()].record(nanos);
    }

    /** Returns the samples of a metric. */
    public TimingRing ring(Metric metric) {
        return rings[metric.ordinal()];
    }

    /** Sets a gauge to its current value. */
    public void gauge(Gauge gauge, long value) {
        gauges[gauge.ordinal()] = value;
    }

    /** Returns a gauge's last reported value, 0 when never reported. */
    public long gauge(Gauge gauge) {
        return gauges[gauge.ordinal()];
    }

    /** Returns the sum of the average per-frame draw metrics, in nanoseconds. */
    public long averageDrawPhase() {
        long sum = 0;
        for (Metric m : Metric.values()) {
            if (m.perFrame()) {
                sum += rings[m.ordinal()].average();
            }
        }
        return sum;
    }
}
