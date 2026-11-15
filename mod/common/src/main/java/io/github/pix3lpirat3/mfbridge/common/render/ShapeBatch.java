package io.github.pix3lpirat3.mfbridge.common.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.pix3lpirat3.mfbridge.common.game.Colors;
import io.github.pix3lpirat3.mfbridge.common.render.command.DepthMode;
import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.common.seam.Vec3d;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * One batch of the immediate tier: every shape that goes through one pipeline, emitted as one submit and
 * so one draw call. Instances are long-lived and reused every frame, so steady-state frames allocate
 * nothing here.
 *
 * <p>The game calls {@link #render} later in the same frame, after collection; the batch therefore keeps
 * the frame's commands and camera until the next {@link #prepare}. Commands that were culled for this
 * frame are skipped before any vertex is written, and for stable lists the batch walks only the
 * commands that belong to it.
 */
final class ShapeBatch implements SubmitNodeCollector.CustomGeometryRenderer {

    /** RGB multiplier for the through-walls half of {@link DepthMode#BOTH}. */
    private static final float XRAY_DIM = 0.45f;

    /** Fill alpha multiplier for the through-walls half of {@link DepthMode#BOTH}. */
    private static final float XRAY_FILL_ALPHA = 0.4f;

    /** Waypoint marker half-size, in blocks. */
    private static final float WAYPOINT_RADIUS = 0.35f;

    private static final float WAYPOINT_LINE_WIDTH = 2.5f;
    private static final float BOX_LINE_WIDTH = 2f;

    private static final boolean[] NONE = new boolean[0];

    private final RenderType renderType;
    private final int index;
    private final boolean fill;
    private final boolean xray;
    private List<PreparedShapes> stable = List.of();
    private List<DrawCommand> extras = List.of();
    private boolean[] extraVisible = NONE;
    private double camX;
    private double camY;
    private double camZ;

    /**
     * Creates a batch.
     *
     * @param index which entry of {@link PreparedShapes#members} this batch draws
     */
    ShapeBatch(final RenderType renderType, final int index, final boolean fill, final boolean xray) {
        this.renderType = renderType;
        this.index = index;
        this.fill = fill;
        this.xray = xray;
    }

    RenderType renderType() {
        return renderType;
    }

    /**
     * Stores this frame's input and reports whether the batch has anything to draw.
     *
     * @param stableLists the prepared stable lists, already culled for this frame
     * @param frameExtras the commands of this frame only
     * @param frameExtraVisible whether each of {@code frameExtras} may be in view
     * @return {@code true} if at least one visible command contributes to this batch
     */
    boolean prepare(final List<PreparedShapes> stableLists, final List<DrawCommand> frameExtras,
            final boolean[] frameExtraVisible, final double cameraX, final double cameraY, final double cameraZ) {
        this.stable = stableLists;
        this.extras = frameExtras;
        this.extraVisible = frameExtraVisible;
        this.camX = cameraX;
        this.camY = cameraY;
        this.camZ = cameraZ;
        for (int a = 0; a < stableLists.size(); a++) {
            final PreparedShapes p = stableLists.get(a);
            final int[] mine = p.members[index];
            for (int k = 0; k < mine.length; k++) {
                if (p.visible[mine[k]]) {
                    return true;
                }
            }
        }
        for (int i = 0; i < frameExtras.size(); i++) {
            if (frameExtraVisible[i] && contributes(frameExtras.get(i), fill, xray)) {
                return true;
            }
        }
        return false;
    }

    /** Forgets the last frame's input. */
    void clear() {
        stable = List.of();
        extras = List.of();
        extraVisible = NONE;
    }

    /** Returns whether a command draws anything in the batch with these two properties. */
    static boolean contributes(final DrawCommand command, final boolean fill, final boolean xray) {
        return switch (command) {
            case DrawCommand.Box box -> (!fill || box.fillAlpha() > 0f) && inPass(box.depthMode(), xray);
            case DrawCommand.Quad quad -> fill && inPass(quad.depthMode(), xray);
            case DrawCommand.Line line -> !fill && inPass(line.depthMode(), xray);
            case DrawCommand.Polyline polyline -> !fill && inPass(polyline.depthMode(), xray);
            case DrawCommand.Waypoint _ -> !fill && xray;
            case DrawCommand.Label _ -> false;
        };
    }

    private static boolean inPass(final DepthMode mode, final boolean xray) {
        return switch (mode) {
            case TEST -> !xray;
            case XRAY -> xray;
            case BOTH -> true;
        };
    }

    @Override
    public void render(final PoseStack.Pose pose, final VertexConsumer buffer) {
        final List<PreparedShapes> lists = stable;
        for (int a = 0; a < lists.size(); a++) {
            final PreparedShapes p = lists.get(a);
            final int[] mine = p.members[index];
            for (int k = 0; k < mine.length; k++) {
                final int i = mine[k];
                if (p.visible[i]) {
                    emit(pose, buffer, p.commands.get(i));
                }
            }
        }
        final List<DrawCommand> frameExtras = extras;
        // The list is reused between frames; never read past the flags that were computed for it.
        final int n = Math.min(frameExtras.size(), extraVisible.length);
        for (int i = 0; i < n; i++) {
            final DrawCommand command = frameExtras.get(i);
            if (extraVisible[i] && contributes(command, fill, xray)) {
                emit(pose, buffer, command);
            }
        }
    }

    private void emit(final PoseStack.Pose pose, final VertexConsumer buffer, final DrawCommand command) {
        switch (command) {
            case DrawCommand.Box box -> emitBox(pose, buffer, box);
            case DrawCommand.Quad quad -> emitQuad(pose, buffer, quad);
            case DrawCommand.Line line -> {
                final int argb = passColor(line.rgba(), line.depthMode());
                segment(pose, buffer, line.a(), line.b(), argb, line.width());
            }
            case DrawCommand.Polyline polyline -> {
                final int argb = passColor(polyline.rgba(), polyline.depthMode());
                final List<Vec3d> points = polyline.points();
                for (int p = 1; p < points.size(); p++) {
                    segment(pose, buffer, points.get(p - 1), points.get(p), argb, polyline.width());
                }
            }
            case DrawCommand.Waypoint waypoint -> emitWaypoint(pose, buffer, waypoint);
            case DrawCommand.Label _ -> {
            }
        }
    }

    private int passColor(final int rgba, final DepthMode mode) {
        final int argb = Colors.rgbaToArgb(rgba) | 0xFF000000;
        return xray && mode == DepthMode.BOTH ? Colors.scaleRgb(argb, XRAY_DIM) : argb;
    }

    private void emitBox(final PoseStack.Pose pose, final VertexConsumer buffer, final DrawCommand.Box box) {
        final float x0 = (float) (box.min().x() - camX);
        final float y0 = (float) (box.min().y() - camY);
        final float z0 = (float) (box.min().z() - camZ);
        final float x1 = (float) (box.max().x() - camX);
        final float y1 = (float) (box.max().y() - camY);
        final float z1 = (float) (box.max().z() - camZ);
        if (fill) {
            float alpha = box.fillAlpha();
            if (xray && box.depthMode() == DepthMode.BOTH) {
                alpha *= XRAY_FILL_ALPHA;
            }
            final int argb = Colors.withAlpha(Colors.rgbaToArgb(box.rgba()), alpha);
            quad(pose, buffer, argb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
            quad(pose, buffer, argb, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
            quad(pose, buffer, argb, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
            quad(pose, buffer, argb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            quad(pose, buffer, argb, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            quad(pose, buffer, argb, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
            return;
        }
        final int argb = passColor(box.rgba(), box.depthMode());
        final float w = BOX_LINE_WIDTH;
        edge(pose, buffer, argb, w, x0, y0, z0, x1, y0, z0);
        edge(pose, buffer, argb, w, x0, y0, z1, x1, y0, z1);
        edge(pose, buffer, argb, w, x0, y1, z0, x1, y1, z0);
        edge(pose, buffer, argb, w, x0, y1, z1, x1, y1, z1);
        edge(pose, buffer, argb, w, x0, y0, z0, x0, y1, z0);
        edge(pose, buffer, argb, w, x1, y0, z0, x1, y1, z0);
        edge(pose, buffer, argb, w, x0, y0, z1, x0, y1, z1);
        edge(pose, buffer, argb, w, x1, y0, z1, x1, y1, z1);
        edge(pose, buffer, argb, w, x0, y0, z0, x0, y0, z1);
        edge(pose, buffer, argb, w, x1, y0, z0, x1, y0, z1);
        edge(pose, buffer, argb, w, x0, y1, z0, x0, y1, z1);
        edge(pose, buffer, argb, w, x1, y1, z0, x1, y1, z1);
    }

    private void emitQuad(final PoseStack.Pose pose, final VertexConsumer buffer, final DrawCommand.Quad q) {
        float alpha = q.fillAlpha();
        if (xray && q.depthMode() == DepthMode.BOTH) {
            alpha *= XRAY_FILL_ALPHA;
        }
        final int argb = Colors.withAlpha(Colors.rgbaToArgb(q.rgba()), alpha);
        quad(pose, buffer, argb,
                (float) (q.a().x() - camX), (float) (q.a().y() - camY), (float) (q.a().z() - camZ),
                (float) (q.b().x() - camX), (float) (q.b().y() - camY), (float) (q.b().z() - camZ),
                (float) (q.c().x() - camX), (float) (q.c().y() - camY), (float) (q.c().z() - camZ),
                (float) (q.d().x() - camX), (float) (q.d().y() - camY), (float) (q.d().z() - camZ));
    }

    private void emitWaypoint(final PoseStack.Pose pose, final VertexConsumer buffer, final DrawCommand.Waypoint waypoint) {
        final int argb = Colors.rgbaToArgb(waypoint.rgba()) | 0xFF000000;
        final float cx = (float) (waypoint.pos().x() - camX);
        final float cy = (float) (waypoint.pos().y() - camY);
        final float cz = (float) (waypoint.pos().z() - camZ);
        final float r = WAYPOINT_RADIUS;
        final float w = WAYPOINT_LINE_WIDTH;
        // Octahedron: four edges around the equator, four to the top and four to the bottom vertex.
        edge(pose, buffer, argb, w, cx + r, cy, cz, cx, cy, cz + r);
        edge(pose, buffer, argb, w, cx, cy, cz + r, cx - r, cy, cz);
        edge(pose, buffer, argb, w, cx - r, cy, cz, cx, cy, cz - r);
        edge(pose, buffer, argb, w, cx, cy, cz - r, cx + r, cy, cz);
        edge(pose, buffer, argb, w, cx + r, cy, cz, cx, cy + r, cz);
        edge(pose, buffer, argb, w, cx - r, cy, cz, cx, cy + r, cz);
        edge(pose, buffer, argb, w, cx, cy, cz + r, cx, cy + r, cz);
        edge(pose, buffer, argb, w, cx, cy, cz - r, cx, cy + r, cz);
        edge(pose, buffer, argb, w, cx + r, cy, cz, cx, cy - r, cz);
        edge(pose, buffer, argb, w, cx - r, cy, cz, cx, cy - r, cz);
        edge(pose, buffer, argb, w, cx, cy, cz + r, cx, cy - r, cz);
        edge(pose, buffer, argb, w, cx, cy, cz - r, cx, cy - r, cz);
    }

    private void segment(final PoseStack.Pose pose, final VertexConsumer buffer, final Vec3d a, final Vec3d b, final int argb, final float width) {
        edge(pose, buffer, argb, width,
                (float) (a.x() - camX), (float) (a.y() - camY), (float) (a.z() - camZ),
                (float) (b.x() - camX), (float) (b.y() - camY), (float) (b.z() - camZ));
    }

    private static void edge(
            final PoseStack.Pose pose,
            final VertexConsumer buffer,
            final int argb,
            final float width,
            final float ax,
            final float ay,
            final float az,
            final float bx,
            final float by,
            final float bz) {
        float nx = bx - ax;
        float ny = by - ay;
        float nz = bz - az;
        final float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0e-6f) {
            return;
        }
        nx /= length;
        ny /= length;
        nz /= length;
        // The lines shader expands each segment to a screen-space quad along the normal, so both
        // vertices carry the segment direction.
        buffer.addVertex(pose, ax, ay, az).setColor(argb).setNormal(pose, nx, ny, nz).setLineWidth(width);
        buffer.addVertex(pose, bx, by, bz).setColor(argb).setNormal(pose, nx, ny, nz).setLineWidth(width);
    }

    private static void quad(
            final PoseStack.Pose pose,
            final VertexConsumer buffer,
            final int argb,
            final float ax, final float ay, final float az,
            final float bx, final float by, final float bz,
            final float cx, final float cy, final float cz,
            final float dx, final float dy, final float dz) {
        buffer.addVertex(pose, ax, ay, az).setColor(argb);
        buffer.addVertex(pose, bx, by, bz).setColor(argb);
        buffer.addVertex(pose, cx, cy, cz).setColor(argb);
        buffer.addVertex(pose, dx, dy, dz).setColor(argb);
    }
}
