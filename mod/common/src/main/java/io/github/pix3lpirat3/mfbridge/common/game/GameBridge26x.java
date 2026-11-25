package io.github.pix3lpirat3.mfbridge.common.game;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.pix3lpirat3.mfbridge.common.seam.CameraState;
import io.github.pix3lpirat3.mfbridge.common.seam.GameBridge;
import io.github.pix3lpirat3.mfbridge.common.seam.HudRenderListener;
import io.github.pix3lpirat3.mfbridge.common.seam.Notification;
import io.github.pix3lpirat3.mfbridge.common.seam.RaycastHit;
import io.github.pix3lpirat3.mfbridge.common.seam.Vec3d;
import io.github.pix3lpirat3.mfbridge.common.seam.WorldRenderListener;
import io.github.pix3lpirat3.mfbridge.common.ui.hud.BridgeToast;
import io.github.pix3lpirat3.mfbridge.common.ui.input.Crosshair;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The {@code game-26x} game bridge for Minecraft 26.x on any loader. */
public final class GameBridge26x implements GameBridge {

    private final List<WorldRenderListener> worldListeners = new ArrayList<>();
    private final List<HudRenderListener> hudListeners = new ArrayList<>();
    private final RenderFrame26x frame = new RenderFrame26x();
    private @Nullable HudCanvas26x hudCanvas;

    /** Creates the bridge. Call on the client thread. */
    public GameBridge26x() {
    }

    @Override
    public String id() {
        return "game-26x";
    }

    @Override
    public void addWorldRenderListener(final WorldRenderListener listener) {
        worldListeners.add(listener);
    }

    @Override
    public void addHudRenderListener(final HudRenderListener listener) {
        hudListeners.add(listener);
    }

    @Override
    public Optional<CameraState> camera() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return Optional.empty();
        }
        final Camera camera = Game.mainCamera();
        final Vec3 pos = camera.position();
        return Optional.of(new CameraState(new Vec3d(pos.x, pos.y, pos.z), camera.yRot(), camera.xRot()));
    }

    @Override
    public Optional<RaycastHit> raycast(final double maxDistance) {
        if (!(maxDistance > 0d)) {
            throw new IllegalArgumentException("maxDistance must be positive: " + maxDistance);
        }
        final Crosshair.Hit hit = Crosshair.hit(maxDistance, entity -> true);
        final Vec3 at = hit.location();
        final Vec3d where = new Vec3d(at.x, at.y, at.z);
        final Entity entity = hit.entity();
        final BlockPos block = hit.block();
        final Direction face = hit.face();
        if (entity != null) {
            return Optional.of(new RaycastHit.Entity(where, entity.getUUID()));
        }
        if (block == null || face == null) {
            return Optional.empty();
        }
        return Optional.of(new RaycastHit.Block(where, block.getX(), block.getY(), block.getZ(),
                face.getSerializedName().toLowerCase(Locale.ROOT)));
    }

    @Override
    public void showToast(final Notification notification) {
        final long displayMs = switch (notification.severity()) {
            case INFO -> 5_000L;
            case WARNING -> 8_000L;
            case ERROR -> 12_000L;
        };
        final boolean error = notification.severity() == Notification.Severity.ERROR;
        Minecraft.getInstance().execute(() -> BridgeToast.show(notification.title(), notification.message(), displayMs, error));
    }

    /**
     * Calls the world render listeners for one frame. Called by loader glue while the game collects
     * level geometry.
     *
     * @param collector the frame's submit collector
     * @param poseStack the level pose stack, at identity
     * @param levelState the frame's level render state
     */
    public void fireWorldRender(final SubmitNodeCollector collector, final PoseStack poseStack, final LevelRenderState levelState) {
        if (worldListeners.isEmpty() || !levelState.cameraRenderState.initialized) {
            return;
        }
        frame.begin(collector, poseStack, levelState.cameraRenderState);
        try {
            for (int i = 0; i < worldListeners.size(); i++) {
                worldListeners.get(i).onWorldRender(frame);
            }
        } finally {
            frame.end();
        }
    }

    /**
     * Calls the HUD render listeners for one frame.
     *
     * @param graphics the GUI extractor for this frame
     * @param deltaTracker the frame's delta tracker
     */
    public void fireHudRender(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
        if (hudListeners.isEmpty()) {
            return;
        }
        HudCanvas26x canvas = hudCanvas;
        if (canvas == null) {
            canvas = new HudCanvas26x(Minecraft.getInstance().font);
            hudCanvas = canvas;
        }
        final float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        canvas.begin(graphics);
        try {
            for (int i = 0; i < hudListeners.size(); i++) {
                hudListeners.get(i).onHudRender(canvas, partialTick);
            }
        } finally {
            canvas.end();
        }
    }
}
