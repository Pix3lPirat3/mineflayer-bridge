package io.github.pix3lpirat3.mfbridge.common.render;

import io.github.pix3lpirat3.mfbridge.common.render.command.DepthMode;
import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.common.render.command.FrameCommands;
import io.github.pix3lpirat3.mfbridge.common.render.command.WorldRenderSource;
import io.github.pix3lpirat3.mfbridge.common.seam.HudCanvas;
import io.github.pix3lpirat3.mfbridge.common.seam.HudRenderListener;
import io.github.pix3lpirat3.mfbridge.common.seam.RenderFrame;
import io.github.pix3lpirat3.mfbridge.common.seam.Vec3d;
import java.util.ArrayList;
import java.util.List;

/**
 * Development source that draws one of every immediate-tier shape around the player, enabled with
 * {@code -Dmfbridge.demo=true}. The scene is anchored where the camera was on the first frame and
 * re-anchored when the camera moves more than {@value #REANCHOR_DISTANCE} blocks away, so between
 * re-anchors the same list instance is returned and nothing is allocated.
 */
public final class DemoRenderSource implements WorldRenderSource, HudRenderListener {

    private static final double REANCHOR_DISTANCE = 48d;
    private static final int PATH_POINTS = 64;

    private List<DrawCommand> commands = List.of();
    private double anchorX = Double.NaN;
    private double anchorY;
    private double anchorZ;
    private String hudLine = "";

    /** Creates the demo source. */
    public DemoRenderSource() {
    }

    @Override
    public void frameCommands(final RenderFrame frame, final FrameCommands out) {
        final double dx = frame.cameraX() - anchorX;
        final double dz = frame.cameraZ() - anchorZ;
        if (Double.isNaN(anchorX) || dx * dx + dz * dz > REANCHOR_DISTANCE * REANCHOR_DISTANCE) {
            anchorX = Math.floor(frame.cameraX());
            anchorY = Math.floor(frame.cameraY()) - 1d;
            anchorZ = Math.floor(frame.cameraZ());
            commands = build(anchorX, anchorY, anchorZ);
            hudLine = "Mineflayer Bridge demo: " + commands.size() + " shapes at "
                    + (long) anchorX + " " + (long) anchorY + " " + (long) anchorZ;
        }
        out.addStable(commands);
    }

    @Override
    public void onHudRender(final HudCanvas canvas, final float partialTick) {
        if (hudLine.isEmpty()) {
            return;
        }
        final int width = canvas.textWidth(hudLine);
        canvas.fill(4, 4, 12 + width, 18, 0x00000080);
        canvas.text(hudLine, 8, 7, 0xFFFFFFFF);
    }

    private static List<DrawCommand> build(final double x, final double y, final double z) {
        final List<DrawCommand> list = new ArrayList<>();
        final Vec3d origin = new Vec3d(x, y, z);
        list.add(new DrawCommand.Box(origin.add(3, 0, 0), origin.add(4, 1, 1), 0xFF3B30FF, 0.25f, DepthMode.TEST));
        list.add(new DrawCommand.Box(origin.add(-4, 0, 0), origin.add(-3, 2, 1), 0x34C759FF, 0f, DepthMode.XRAY));
        list.add(new DrawCommand.Box(origin.add(0, 0, 3), origin.add(2, 1, 4), 0x3B82F6FF, 0.3f, DepthMode.BOTH));
        list.add(new DrawCommand.Box(origin.add(0, -3, -6), origin.add(1, -2, -5), 0xFFCC00FF, 0.35f, DepthMode.BOTH));
        list.add(new DrawCommand.Line(origin.add(3.5, 1, 0.5), origin.add(-3.5, 2, 0.5), 0xFFFFFFFF, 3f, DepthMode.TEST));
        final List<Vec3d> path = new ArrayList<>(PATH_POINTS);
        for (int i = 0; i < PATH_POINTS; i++) {
            final double t = i / (double) (PATH_POINTS - 1);
            path.add(origin.add(-8 + 16 * t, 1.5 + Math.sin(t * Math.PI * 4) * 0.75, -3));
        }
        list.add(new DrawCommand.Polyline(path, 0xAF52DEFF, 4f, DepthMode.BOTH));
        list.add(new DrawCommand.Label(origin.add(3.5, 1.6, 0.5), "depth-tested box", 1f, 0x00000060, 32d));
        list.add(new DrawCommand.Label(origin.add(-3.5, 2.6, 0.5), "x-ray box", 1f, 0x00000060, 32d));
        list.add(new DrawCommand.Label(origin.add(1, 1.6, 3.5), "both, with fill", 1.5f, 0x00000000, 32d));
        list.add(new DrawCommand.Waypoint(origin.add(0.5, 3, 20.5), "Demo waypoint", 0xFF9500FF));
        return List.copyOf(list);
    }
}
