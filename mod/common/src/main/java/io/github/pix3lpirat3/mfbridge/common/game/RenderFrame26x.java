package io.github.pix3lpirat3.mfbridge.common.game;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.pix3lpirat3.mfbridge.common.seam.RenderFrame;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.jspecify.annotations.Nullable;

/**
 * The 26.x render frame: the camera plus the submit collector and pose stack of the level being drawn.
 * One instance is reused for every frame, so it must not be kept beyond the callback.
 */
public final class RenderFrame26x implements RenderFrame {

    private @Nullable SubmitNodeCollector collector;
    private @Nullable PoseStack poseStack;
    private @Nullable CameraRenderState camera;
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private float partialTick;

    RenderFrame26x() {
    }

    void begin(final SubmitNodeCollector newCollector, final PoseStack newPoseStack, final CameraRenderState newCamera) {
        this.collector = newCollector;
        this.poseStack = newPoseStack;
        this.camera = newCamera;
        this.cameraX = newCamera.pos.x;
        this.cameraY = newCamera.pos.y;
        this.cameraZ = newCamera.pos.z;
        this.partialTick = newCamera.cameraEntityPartialTicks;
    }

    void end() {
        this.collector = null;
        this.poseStack = null;
        this.camera = null;
    }

    /**
     * Returns the collector that geometry and text are submitted to this frame.
     *
     * @return the collector
     * @throws IllegalStateException outside a render callback
     */
    public SubmitNodeCollector collector() {
        return Objects.requireNonNull(collector, "frame used outside its render callback");
    }

    /**
     * Returns the level pose stack. It is at identity and coordinates submitted through it are
     * camera-relative. Callers must leave it as they found it.
     *
     * @return the pose stack
     * @throws IllegalStateException outside a render callback
     */
    public PoseStack poseStack() {
        return Objects.requireNonNull(poseStack, "frame used outside its render callback");
    }

    /**
     * Returns the camera render state, including the billboard orientation.
     *
     * @return the camera state
     * @throws IllegalStateException outside a render callback
     */
    public CameraRenderState camera() {
        return Objects.requireNonNull(camera, "frame used outside its render callback");
    }

    @Override
    public double cameraX() {
        return cameraX;
    }

    @Override
    public double cameraY() {
        return cameraY;
    }

    @Override
    public double cameraZ() {
        return cameraZ;
    }

    @Override
    public float partialTick() {
        return partialTick;
    }
}
