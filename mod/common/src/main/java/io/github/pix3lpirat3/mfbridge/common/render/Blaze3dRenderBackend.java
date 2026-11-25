package io.github.pix3lpirat3.mfbridge.common.render;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.pix3lpirat3.mfbridge.common.game.Colors;
import io.github.pix3lpirat3.mfbridge.common.game.RenderFrame26x;
import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.common.render.gpu.Gpu;
import io.github.pix3lpirat3.mfbridge.common.render.command.FrameCommands;
import io.github.pix3lpirat3.mfbridge.common.render.cull.ShapeBounds;
import io.github.pix3lpirat3.mfbridge.common.render.cull.ViewVolume;
import io.github.pix3lpirat3.mfbridge.common.seam.RenderBackend;
import io.github.pix3lpirat3.mfbridge.common.seam.RenderFrame;
import io.github.pix3lpirat3.mfbridge.common.seam.Vec3d;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.common.version.Versions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import org.jspecify.annotations.Nullable;

/**
 * The {@code blaze3d} render backend for 26.x.
 *
 * <p>Shapes are batched into four submits per frame (lines and fill, each depth-tested and through-walls),
 * which the game turns into one draw call per pipeline in its feature phases: opaque lines with the solid
 * features, fills with the translucent features or through OIT when improved transparency is on. Labels
 * are billboard text submitted to the game's text feature, so fonts and resource packs behave as in vanilla.
 *
 * <p>A stable command list is prepared once ({@link PreparedShapes}) and kept while it is drawn. Every
 * frame each command is tested against the view once, and only what may be in view is tessellated or
 * submitted as text. Glyph sequences and widths are kept per text, so they are built only when a text
 * is new.
 */
public final class Blaze3dRenderBackend implements RenderBackend {

    /** Vanilla name tag text scale: one font pixel is 1/40 of a block. */
    private static final float TEXT_SCALE = 0.025f;

    /** Height of the waypoint label above the marker, in blocks. */
    private static final double WAYPOINT_LABEL_OFFSET = 0.6;

    private static final int LABEL_TEXT_ARGB = 0xFFFFFFFF;
    private static final int WAYPOINT_BACKGROUND_ARGB = 0x60000000;

    /** Frames between sweeps of the text cache, and how long an unused text is kept. */
    private static final int TEXT_SWEEP_FRAMES = 600;

    /** One text, laid out. */
    static final class TextRun {
        final FormattedCharSequence sequence;
        final int width;
        long usedFrame;

        TextRun(final FormattedCharSequence sequence, final int width) {
            this.sequence = sequence;
            this.width = width;
        }
    }

    private final ShapeBatch[] batches = {
        new ShapeBatch(Versions.CURRENT.gpu().renderType(Gpu.Pipeline.LINES_XRAY), 0, false, true),
        new ShapeBatch(Versions.CURRENT.gpu().renderType(Gpu.Pipeline.LINES), 1, false, false),
        new ShapeBatch(Versions.CURRENT.gpu().renderType(Gpu.Pipeline.FILL_XRAY), 2, true, true),
        new ShapeBatch(Versions.CURRENT.gpu().renderType(Gpu.Pipeline.FILL), 3, true, false),
    };
    private boolean labelsVisible = true;
    private final Map<List<DrawCommand>, PreparedShapes> prepared = new IdentityHashMap<>();
    private final ArrayList<PreparedShapes> active = new ArrayList<>();
    private final Map<String, TextRun> texts = new HashMap<>();
    private final FrustumView frustumView = new FrustumView();
    private ViewVolume view = new ViewVolume();
    private final double[] sphere = new double[4];
    private boolean[] extraVisible = new boolean[64];
    private long frameCount;

    /** Creates the backend. Call on the render thread after the game has initialised rendering. */
    public Blaze3dRenderBackend() {
    }

    @Override
    public String id() {
        return "blaze3d";
    }

    @Override
    public void drawImmediate(final RenderFrame frame, final FrameCommands commands) {
        if (!(frame instanceof RenderFrame26x frame26x)) {
            throw new IllegalArgumentException("frame was not created by game-26x: " + frame.getClass().getName());
        }
        frameCount++;
        if (commands.isEmpty()) {
            release();
            return;
        }
        view = frustumView.look(frame26x.camera().cullFrustum);
        active.clear();
        final List<List<DrawCommand>> stable = commands.stable();
        for (int i = 0; i < stable.size(); i++) {
            final List<DrawCommand> list = stable.get(i);
            PreparedShapes p = prepared.get(list);
            if (p == null) {
                p = prepare(list);
                prepared.put(list, p);
            }
            p.usedFrame = frameCount;
            p.cull(view);
            active.add(p);
        }
        final List<DrawCommand> extras = commands.extras();
        cullExtras(extras);
        final SubmitNodeCollector collector = frame26x.collector();
        final PoseStack poseStack = frame26x.poseStack();
        for (final ShapeBatch batch : batches) {
            if (batch.prepare(active, extras, extraVisible, frame.cameraX(), frame.cameraY(), frame.cameraZ())) {
                collector.submitCustomGeometry(poseStack, batch.renderType(), batch);
            }
        }
        if (labelsVisible) {
            submitLabels(frame26x, extras);
        }
        release();
    }

    @Override
    public void setLabelsVisible(final boolean visible) {
        this.labelsVisible = visible;
    }

    @Override
    public void close() {
        prepared.clear();
        active.clear();
        texts.clear();
        for (final ShapeBatch batch : batches) {
            batch.clear();
        }
    }

    /** Drops what was prepared for lists that are no longer drawn, and now and then unused texts. */
    private void release() {
        if (prepared.size() > active.size() || active.isEmpty() && !prepared.isEmpty()) {
            prepared.values().removeIf(p -> p.usedFrame != frameCount);
        }
        if (frameCount % TEXT_SWEEP_FRAMES == 0 && !texts.isEmpty()) {
            texts.values().removeIf(t -> frameCount - t.usedFrame > TEXT_SWEEP_FRAMES);
        }
    }

    private PreparedShapes prepare(final List<DrawCommand> list) {
        final PreparedShapes p = new PreparedShapes(list);
        for (int k = 0; k < p.labels.length; k++) {
            final int i = p.labels[k];
            final DrawCommand command = list.get(i);
            final TextRun run = text(textOf(command));
            p.text[k] = run;
            p.spheres[i * 4 + 3] = textRadius(command, run.width);
        }
        return p;
    }

    private void cullExtras(final List<DrawCommand> extras) {
        final int n = extras.size();
        if (extraVisible.length < n) {
            extraVisible = new boolean[Math.max(n, extraVisible.length * 2)];
        }
        for (int i = 0; i < n; i++) {
            final DrawCommand command = extras.get(i);
            ShapeBounds.sphere(command, sphere, 0);
            double radius = sphere[3];
            final String text = textOf(command);
            if (text != null) {
                radius = textRadius(command, text(text).width);
            }
            extraVisible[i] = view.sphere(sphere[0], sphere[1], sphere[2], radius);
        }
    }

    private static @Nullable String textOf(final DrawCommand command) {
        final String text = switch (command) {
            case DrawCommand.Label label -> label.text();
            case DrawCommand.Waypoint waypoint -> waypoint.name();
            case DrawCommand.Box _ -> null;
            case DrawCommand.Quad _ -> null;
            case DrawCommand.Line _ -> null;
            case DrawCommand.Polyline _ -> null;
        };
        return text == null || text.isEmpty() ? null : text;
    }

    /** Radius around the anchor that holds the text, and for a waypoint its marker too. */
    private static double textRadius(final DrawCommand command, final int width) {
        if (command instanceof DrawCommand.Label label) {
            return TEXT_SCALE * label.scale() * (width / 2d + 8d);
        }
        return Math.max(ShapeBounds.WAYPOINT_RADIUS, WAYPOINT_LABEL_OFFSET + TEXT_SCALE * (width / 2d + 8d));
    }

    /** Returns the laid out form of a text, building it the first time the text is seen. */
    private TextRun text(final @Nullable String text) {
        final String key = text == null ? "" : text;
        TextRun run = texts.get(key);
        if (run == null) {
            run = new TextRun(FormattedCharSequence.forward(key, Style.EMPTY), Minecraft.getInstance().font.width(key));
            texts.put(key, run);
        }
        run.usedFrame = frameCount;
        return run;
    }

    private void submitLabels(final RenderFrame26x frame, final List<DrawCommand> extras) {
        for (int a = 0; a < active.size(); a++) {
            final PreparedShapes p = active.get(a);
            for (int k = 0; k < p.labels.length; k++) {
                final int i = p.labels[k];
                if (p.visible[i]) {
                    final TextRun run = p.text[k];
                    run.usedFrame = frameCount;
                    submitLabel(frame, p.commands.get(i), run);
                }
            }
        }
        for (int i = 0; i < extras.size(); i++) {
            if (!extraVisible[i]) {
                continue;
            }
            final DrawCommand command = extras.get(i);
            final String text = textOf(command);
            if (text != null) {
                submitLabel(frame, command, text(text));
            }
        }
    }

    private static void submitLabel(final RenderFrame26x frame, final DrawCommand command, final TextRun run) {
        if (command instanceof DrawCommand.Label label) {
            if (label.pos().distanceSquared(frame.cameraX(), frame.cameraY(), frame.cameraZ())
                    <= label.maxDistance() * label.maxDistance()) {
                billboard(frame, label.pos(), 0d, label.scale(), run.sequence, run.width,
                        Colors.rgbaToArgb(label.backgroundRgba()), Font.DisplayMode.NORMAL);
            }
        } else if (command instanceof DrawCommand.Waypoint waypoint) {
            billboard(frame, waypoint.pos(), WAYPOINT_LABEL_OFFSET, 1f, run.sequence, run.width,
                    WAYPOINT_BACKGROUND_ARGB, Font.DisplayMode.SEE_THROUGH);
        }
    }

    private static void billboard(
            final RenderFrame26x frame,
            final Vec3d pos,
            final double yOffset,
            final float scale,
            final FormattedCharSequence text,
            final int width,
            final int backgroundArgb,
            final Font.DisplayMode mode) {
        final PoseStack poseStack = frame.poseStack();
        poseStack.pushPose();
        poseStack.translate(pos.x() - frame.cameraX(), pos.y() + yOffset - frame.cameraY(), pos.z() - frame.cameraZ());
        Game.rotate(poseStack, frame.camera().orientation);
        final float s = TEXT_SCALE * scale;
        poseStack.scale(s, -s, s);
        frame.collector().submitText(poseStack, -width / 2f, -4f, text, false, mode, LightCoordsUtil.FULL_BRIGHT,
                LABEL_TEXT_ARGB, backgroundArgb, 0);
        poseStack.popPose();
    }
}
