package io.github.pix3lpirat3.mfbridge.common.display.logic;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Client-side animation loops of a display ({@code animate}): computed from the
 * frame time, so they need no traffic while they run.
 *
 * <ul>
 *   <li>{@link #BOB}: moves up and down by {@code amplitude} blocks.
 *   <li>{@link #SPIN}: turns around the vertical axis once per period; the amplitude is ignored.
 *   <li>{@link #PULSE}: scales by {@code 1 +/- amplitude}.
 *   <li>{@link #SWAY}: tilts side to side by {@code amplitude} radians.
 * </ul>
 */
public enum Animation {
    /** No animation. */
    NONE,
    /** Vertical bobbing. */
    BOB,
    /** Rotation around the vertical axis. */
    SPIN,
    /** Uniform scale pulsing. */
    PULSE,
    /** Tilting around the forward axis. */
    SWAY;

    /** One animation sample; reused between frames so sampling allocates nothing. */
    public static final class Sample {
        /** Vertical offset, blocks. */
        public double dy;
        /** Rotation around the vertical axis, radians. */
        public double yaw;
        /** Rotation around the forward axis, radians. */
        public double roll;
        /** Scale multiplier. */
        public double scale = 1d;

        /** Creates a neutral sample. */
        public Sample() {
        }

        /** Resets to the neutral sample. */
        public void reset() {
            dy = 0d;
            yaw = 0d;
            roll = 0d;
            scale = 1d;
        }
    }

    /**
     * Parses a wire name; unknown names and null mean {@link #NONE}.
     *
     * @param name {@code bob}, {@code spin}, {@code pulse} or {@code sway}
     * @return the animation
     */
    public static Animation of(final @Nullable String name) {
        if (name == null) {
            return NONE;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "bob" -> BOB;
            case "spin" -> SPIN;
            case "pulse" -> PULSE;
            case "sway" -> SWAY;
            default -> NONE;
        };
    }

    /**
     * Samples the loop at a time.
     *
     * @param amplitude blocks for bob, a fraction for pulse, radians for sway
     * @param periodMs loop period in ms; values below 1 are treated as 1
     * @param timeMs monotonic time in ms
     * @param out receives the sample; reset first
     */
    public void sample(final double amplitude, final long periodMs, final long timeMs, final Sample out) {
        out.reset();
        if (this == NONE) {
            return;
        }
        final long period = Math.max(1L, periodMs);
        final double phase = (double) Math.floorMod(timeMs, period) / period * (Math.PI * 2d);
        final double wave = Math.sin(phase);
        switch (this) {
            case BOB -> out.dy = amplitude * wave;
            case SPIN -> out.yaw = phase;
            case PULSE -> out.scale = Math.max(0d, 1d + amplitude * wave);
            case SWAY -> out.roll = amplitude * wave;
            case NONE -> {
            }
        }
    }
}
