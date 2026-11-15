package io.github.pix3lpirat3.mfbridge.core.diag;

/**
 * The cost metrics the inspector shows, each with its target cost. Tick
 * metrics are measured by the core; frame metrics are reported by the renderer.
 */
public enum Metric {
    /** Intake of inbound frames within one bridge tick. */
    TICK_INTAKE("Tick intake", 300_000L, false),
    /** The whole bridge tick: intake, timers, interest, snapshots and flush. */
    TICK_TOTAL("Tick total", 300_000L, false),
    /** Immediate-tier shapes submitted in one frame. */
    DRAW_IMMEDIATE("Draw immediate", 500_000L, true),
    /** Displays and world menus submitted in one frame. */
    DRAW_DISPLAYS("Draw displays", 500_000L, true),
    /** Retained-tier upload and draw in one frame. */
    DRAW_RETAINED("Draw retained", 500_000L, true),
    /** Bot HUD panels and overlays drawn in one frame. */
    DRAW_HUD("Draw HUD", 500_000L, true);

    private final String label;
    private final long targetNanos;
    private final boolean frame;

    Metric(String label, long targetNanos, boolean frame) {
        this.label = label;
        this.targetNanos = targetNanos;
        this.frame = frame;
    }

    /** Returns a short label for the inspector. */
    public String label() {
        return label;
    }

    /**
     * Returns the target in nanoseconds: 0.3 ms per tick for the bridge, 0.5 ms per frame
     * for the whole draw phase. Draw metrics share the frame target, so their sum is what counts.
     */
    public long targetNanos() {
        return targetNanos;
    }

    /** Returns whether this is a per-frame metric (otherwise per tick). */
    public boolean perFrame() {
        return frame;
    }
}
