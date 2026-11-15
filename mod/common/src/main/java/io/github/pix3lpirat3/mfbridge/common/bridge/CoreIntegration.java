package io.github.pix3lpirat3.mfbridge.common.bridge;

import io.github.pix3lpirat3.mfbridge.api.BridgeExtension;
import io.github.pix3lpirat3.mfbridge.common.BridgeClient;
import io.github.pix3lpirat3.mfbridge.common.ClientLifecycleListener;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.render.command.DepthMode;
import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.common.render.command.FrameCommands;
import io.github.pix3lpirat3.mfbridge.common.render.command.WorldRenderSource;
import io.github.pix3lpirat3.mfbridge.common.seam.Notification;
import io.github.pix3lpirat3.mfbridge.common.seam.RenderFrame;
import io.github.pix3lpirat3.mfbridge.common.seam.Vec3d;
import io.github.pix3lpirat3.mfbridge.common.settings.BridgeSettings;
import io.github.pix3lpirat3.mfbridge.common.ui.input.ClientSampler;
import io.github.pix3lpirat3.mfbridge.core.ApprovalDecision;
import io.github.pix3lpirat3.mfbridge.core.AttachPolicy;
import io.github.pix3lpirat3.mfbridge.core.BridgeConfig;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.DeviceRequest;
import io.github.pix3lpirat3.mfbridge.core.HostEnvironment;
import io.github.pix3lpirat3.mfbridge.core.Notifier;
import io.github.pix3lpirat3.mfbridge.core.traits.Traits;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Runs a {@link BridgeCore} inside the game client and feeds its render snapshots to the renderer.
 *
 * <p>Lifecycle callbacks and {@link #frameCommands} run on the client thread; the core is only touched
 * from that thread.
 */
public final class CoreIntegration implements ClientLifecycleListener, WorldRenderSource {
    /** Client-thread time the core may spend per tick on intake. */
    private static final long TICK_BUDGET_NANOS = 2_000_000L;
    private static final float TAG_LABEL_SCALE = 1f;
    /** Tracers: where the line starts in front of the camera, blocks, and its width. */
    private static final double TRACER_START = 0.5;
    private static final float TRACER_WIDTH = 1.5f;
    private static final double TAG_LABEL_DISTANCE = 64d;

    private final BridgeClient client;
    private @Nullable BridgeCore core;
    private @Nullable BridgeSettings running;
    private final SnapshotCommands commands = new SnapshotCommands();
    private @Nullable WorldKey playerWorld;

    public CoreIntegration(BridgeClient client) {
        this.client = client;
    }

    @Override
    public void onClientStarted(BridgeSettings settings) {
        start(settings);
    }

    @Override
    public void onClientStopping() {
        stop();
    }

    @Override
    public void onSettingsChanged(BridgeSettings settings) {
        BridgeSettings current = running;
        if (current != null && !current.transportDiffers(settings)) {
            if (core != null) {
                core.attach().setPolicy(policy(settings));
                core.samples().setMaxRateHz(settings.sampleRateHz());
                applyFocusAndApproval(core, settings);
            }
            running = settings;
            return;
        }
        stop();
        start(settings);
    }

    @Override
    public void onTick() {
        BridgeCore c = core;
        if (c != null) {
            // Bots far from the player get reduced detail.
            Entity camera = Minecraft.getInstance().getCameraEntity();
            if (camera != null && playerWorld != null) {
                c.setPlayerPosition(camera.getX(), camera.getY(), camera.getZ());
            } else {
                c.setPlayerPosition(Double.NaN, Double.NaN, Double.NaN);
            }
            if (playerWorld != null) {
                // Fed only while in a world; the core sends nothing on stale data.
                ClientSampler.tick(c.samples());
            }
            BridgeSettings r = running;
            client.ui().presence().tick(c, r == null ? BridgeSettings.DEFAULTS.awayMinutes() : r.awayMinutes());
            c.tick(TICK_BUDGET_NANOS);
        }
    }

    @Override
    public void onWorldJoin(String serverKey, String dimension) {
        setWorld(new WorldKey(serverKey, null, dimension));
    }

    @Override
    public void onDimensionChange(String serverKey, String dimension) {
        setWorld(new WorldKey(serverKey, null, dimension));
    }

    @Override
    public void onWorldLeave() {
        setWorld(null);
    }

    private void setWorld(@Nullable WorldKey world) {
        playerWorld = world;
        BridgeCore c = core;
        if (c != null) {
            c.setPlayerContext(world);
        }
    }

    /**
     * Returns the running core, for the UI layer.
     *
     * @return the core, or null while the endpoint is not running
     */
    public @Nullable BridgeCore core() {
        return core;
    }

    @Override
    public void frameCommands(RenderFrame frame, FrameCommands out) {
        BridgeCore c = core;
        if (c == null) {
            return;
        }
        SnapshotCommands.Converted converted = commands.update(c.renderSnapshot(), c.variables());
        out.addStable(converted.commands());
        if (!converted.entityTags().isEmpty()) {
            addEntityTags(out, converted);
        }
        if (!converted.tracers().isEmpty() || converted.tagTracers()) {
            addTracers(out, converted, frame);
        }
        if (!converted.conditional().isEmpty()) {
            addConditional(out, converted.conditional(), frame);
        }
        // Zones, points and routes, minus the one the gizmo editor draws itself.
        out.addStable(client.ui().spatialCommands());
        // The inspector highlight joins for a few seconds after an object is selected.
        out.addAll(client.ui().highlightCommands());
        // Pick previews exist only while a pick runs.
        out.addAll(client.ui().previewCommands());
    }

    /** {@code visibleWhen}: distance ranges and lookedAt are checked every frame. */
    private void addConditional(FrameCommands out, List<SnapshotCommands.Conditional> conditional, RenderFrame frame) {
        String looked = client.ui().displays().targetKey();
        for (int i = 0; i < conditional.size(); i++) {
            SnapshotCommands.Conditional cd = conditional.get(i);
            double dx = cd.x() - frame.cameraX();
            double dy = cd.y() - frame.cameraY();
            double dz = cd.z() - frame.cameraZ();
            if (Traits.shown(cd.visibility(), dx * dx + dy * dy + dz * dz, cd.key().equals(looked))) {
                out.addAll(cd.commands());
            }
        }
    }

    /**
     * Tracers: a line from just in front of the camera, along the view direction, to each
     * traced waypoint and entity, drawn through walls.
     */
    private static void addTracers(FrameCommands out, SnapshotCommands.Converted c, RenderFrame frame) {
        Minecraft mc = Minecraft.getInstance();
        Entity camera = mc.getCameraEntity();
        if (camera == null || mc.level == null) {
            return;
        }
        Vec3 look = camera.getViewVector(1f);
        Vec3d from = new Vec3d(frame.cameraX() + look.x * TRACER_START, frame.cameraY() + look.y * TRACER_START,
                frame.cameraZ() + look.z * TRACER_START);
        for (SnapshotCommands.Tracer t : c.tracers()) {
            out.add(new DrawCommand.Line(from, new Vec3d(t.x(), t.y(), t.z()), t.rgba(), TRACER_WIDTH, DepthMode.XRAY));
        }
        if (!c.tagTracers()) {
            return;
        }
        for (SnapshotCommands.EntityTag tag : c.entityTags()) {
            Entity entity = tag.tracer() ? mc.level.getEntity(tag.entityId()) : null;
            if (entity != null) {
                AABB box = entity.getBoundingBox();
                out.add(new DrawCommand.Line(from, new Vec3d((box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2),
                        tag.outlineRgba(), TRACER_WIDTH, DepthMode.XRAY));
            }
        }
    }

    /** Entity tags follow live entities, so they are resolved every frame. */
    private static void addEntityTags(FrameCommands out, SnapshotCommands.Converted c) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        for (SnapshotCommands.EntityTag tag : c.entityTags()) {
            Entity entity = level.getEntity(tag.entityId());
            if (entity == null) {
                continue;
            }
            AABB box = entity.getBoundingBox();
            out.add(new DrawCommand.Box(new Vec3d(box.minX, box.minY, box.minZ), new Vec3d(box.maxX, box.maxY, box.maxZ),
                    tag.outlineRgba(), 0f, DepthMode.BOTH));
            String text = tag.text();
            if (text != null && !text.isEmpty()) {
                out.add(new DrawCommand.Label(new Vec3d((box.minX + box.maxX) / 2, box.maxY + 0.5, (box.minZ + box.maxZ) / 2),
                        text, TAG_LABEL_SCALE, 0x00000040, TAG_LABEL_DISTANCE));
            }
        }
    }

    private void start(BridgeSettings settings) {
        BridgeConfig.Builder builder = BridgeConfig.builder(client.platform().configDir())
                .bindAddress(settings.bindAddress())
                .port(settings.port())
                .allowPlainLoopback(!settings.requireTlsOnLoopback())
                .advertiseHost(settings.advertiseHost())
                .attachPolicy(policy(settings))
                .askLocalApproval(settings.askLocalApproval());
        for (BridgeExtension e : client.extensions().loaded()) {
            builder.extension(e);
        }
        BridgeConfig config = builder.build();
        HostEnvironment host = new HostEnvironment(
                client.platform().modVersion(),
                SharedConstants.getCurrentVersion().name(),
                client.platform().loaderName(),
                this::requestApproval,
                this::showNotification,
                Constants.LOG);
        try {
            BridgeCore started = BridgeCore.start(config, host);
            core = started;
            running = settings;
            commands.clear();
            started.setPlayer(Minecraft.getInstance().getUser().getName(), Minecraft.getInstance().getUser().getProfileId().toString());
            started.setPlayerContext(playerWorld);
            started.setClientQueries(new GameQueries());
            started.samples().setMaxRateHz(settings.sampleRateHz());
            applyFocusAndApproval(started, settings);
            Constants.LOG.info("Bridge endpoint listening on {}:{}", settings.bindAddress(), started.boundPort());
        } catch (IOException | RuntimeException e) {
            Constants.LOG.error("Bridge endpoint failed to start on {}:{}", settings.bindAddress(), settings.port(), e);
            client.notify(new Notification(Constants.MOD_NAME,
                    I18n.get("mineflayer_bridge.endpoint.listen_failed", settings.port(), e.getMessage()), Notification.Severity.ERROR));
        }
    }

    private void stop() {
        BridgeCore c = core;
        core = null;
        running = null;
        commands.clear();
        if (c != null) {
            c.close();
        }
    }

    private static void applyFocusAndApproval(BridgeCore core, BridgeSettings settings) {
        core.focus().setAllowBotRequests(settings.allowBotFocus());
        core.setAskLocalApproval(settings.askLocalApproval());
        core.chat().setPrefix(settings.chatPrefix());
        core.commands().engine().setShowAliases(settings.showAliases());
        io.github.pix3lpirat3.mfbridge.common.ui.command.BridgeCommands.setName(settings.commandName());
    }

    private static AttachPolicy policy(BridgeSettings settings) {
        return switch (settings.attachPolicy()) {
            case WORLD_MATCH -> AttachPolicy.AUTO;
            case ALWAYS_PAIRED -> AttachPolicy.ALWAYS;
            case MANUAL -> AttachPolicy.MANUAL;
        };
    }

    /** New local devices ask the player once, on top of whatever screen is open. */
    private CompletableFuture<ApprovalDecision> requestApproval(DeviceRequest request) {
        CompletableFuture<ApprovalDecision> result = new CompletableFuture<>();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            Screen previous = minecraft.gui.screen();
            minecraft.gui.setScreen(new ConfirmScreen(
                    allowed -> {
                        result.complete(allowed ? ApprovalDecision.ALLOW : ApprovalDecision.DENY);
                        minecraft.gui.setScreen(previous);
                    },
                    Component.translatable("mineflayer_bridge.connect.title"),
                    Component.translatable("mineflayer_bridge.connect.message", request.deviceName(), request.method(),
                            request.remoteAddress()),
                    Component.translatable("mineflayer_bridge.connect.allow"),
                    Component.translatable("mineflayer_bridge.connect.deny")));
        });
        return result;
    }

    private void showNotification(Notifier.Notification n) {
        client.ui().notice(n);
    }
}
