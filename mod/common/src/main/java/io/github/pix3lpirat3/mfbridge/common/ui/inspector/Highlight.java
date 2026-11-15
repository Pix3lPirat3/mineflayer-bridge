package io.github.pix3lpirat3.mfbridge.common.ui.inspector;

import io.github.pix3lpirat3.mfbridge.common.render.command.DepthMode;
import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.common.seam.Vec3d;
import io.github.pix3lpirat3.mfbridge.core.diag.ObjectBounds;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The world highlight of the object selected in the inspector: a pulsing immediate-tier box drawn
 * through walls around the object's bounds, for a few seconds. Client and render thread (the same
 * thread in 26.3).
 */
public final class Highlight {
    /** How long a highlight stays, in milliseconds. */
    public static final long DURATION_MS = 4_000;
    private static final int RGBA = 0xFFD84AFF;
    private static final double INFLATE = 0.05;

    private ObjectBounds.@Nullable Box box;
    private long untilMs;

    /** Creates an inactive highlight. */
    public Highlight() {
    }

    /**
     * Highlights a box.
     *
     * @param bounds world bounds of the object, in blocks
     * @param nowMs wall-clock time, milliseconds
     */
    public void show(final ObjectBounds.Box bounds, final long nowMs) {
        box = bounds.inflate(INFLATE);
        untilMs = nowMs + DURATION_MS;
    }

    /** Ends the highlight. */
    public void clear() {
        box = null;
    }

    /**
     * Returns the draw commands for this frame: empty when inactive, so the steady state allocates
     * nothing.
     *
     * @param nowMs wall-clock time, milliseconds
     * @return the commands. Immutable.
     */
    public List<DrawCommand> commands(final long nowMs) {
        final ObjectBounds.Box b = box;
        if (b == null) {
            return List.of();
        }
        if (nowMs >= untilMs) {
            box = null;
            return List.of();
        }
        final float phase = (float) ((untilMs - nowMs) % 1000) / 1000f;
        final float fill = 0.08f + 0.12f * (float) Math.abs(Math.sin(phase * Math.PI));
        return List.of(new DrawCommand.Box(new Vec3d(b.minX(), b.minY(), b.minZ()), new Vec3d(b.maxX(), b.maxY(), b.maxZ()),
                RGBA, fill, DepthMode.XRAY));
    }
}
