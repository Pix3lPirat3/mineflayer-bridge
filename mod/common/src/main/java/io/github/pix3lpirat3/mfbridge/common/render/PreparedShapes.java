package io.github.pix3lpirat3.mfbridge.common.render;

import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.common.render.cull.ShapeBounds;
import io.github.pix3lpirat3.mfbridge.common.render.cull.ViewVolume;
import java.util.List;

/**
 * What is worked out once for a stable command list and reused every frame it is drawn: each
 * command's bounding sphere, which batch it goes to, and the text of its labels. Per frame only the
 * visibility flags change.
 */
final class PreparedShapes {
    /** Batches: lines through walls, lines, fill through walls, fill. */
    static final int BATCHES = 4;

    final List<DrawCommand> commands;
    /** Centre x, y, z and radius of each command. */
    final double[] spheres;
    /** For each batch, the indexes of the commands it draws. */
    final int[][] members = new int[BATCHES][];
    /** Indexes of the labels and waypoints that have text. */
    final int[] labels;
    /** The text of each entry of {@link #labels}. */
    final Blaze3dRenderBackend.TextRun[] text;
    /** Whether each command may be in view this frame. */
    final boolean[] visible;
    long usedFrame;

    PreparedShapes(final List<DrawCommand> commands) {
        this.commands = commands;
        final int n = commands.size();
        this.spheres = new double[n * 4];
        this.visible = new boolean[n];
        final int[] counts = new int[BATCHES];
        int texts = 0;
        for (int i = 0; i < n; i++) {
            final DrawCommand command = commands.get(i);
            ShapeBounds.sphere(command, spheres, i * 4);
            for (int b = 0; b < BATCHES; b++) {
                if (ShapeBatch.contributes(command, b >= 2, (b & 1) == 0)) {
                    counts[b]++;
                }
            }
            if (hasText(command)) {
                texts++;
            }
        }
        for (int b = 0; b < BATCHES; b++) {
            members[b] = new int[counts[b]];
            counts[b] = 0;
        }
        this.labels = new int[texts];
        this.text = new Blaze3dRenderBackend.TextRun[texts];
        texts = 0;
        for (int i = 0; i < n; i++) {
            final DrawCommand command = commands.get(i);
            for (int b = 0; b < BATCHES; b++) {
                if (ShapeBatch.contributes(command, b >= 2, (b & 1) == 0)) {
                    members[b][counts[b]++] = i;
                }
            }
            if (hasText(command)) {
                labels[texts++] = i;
            }
        }
    }

    private static boolean hasText(final DrawCommand command) {
        return command instanceof DrawCommand.Label label && !label.text().isEmpty()
                || command instanceof DrawCommand.Waypoint waypoint && !waypoint.name().isEmpty();
    }

    /** Tests every command against the view, once per frame. */
    void cull(final ViewVolume view) {
        for (int i = 0; i < visible.length; i++) {
            final int s = i * 4;
            visible[i] = view.sphere(spheres[s], spheres[s + 1], spheres[s + 2], spheres[s + 3]);
        }
    }
}
