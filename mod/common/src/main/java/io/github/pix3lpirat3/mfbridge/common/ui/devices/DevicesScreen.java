package io.github.pix3lpirat3.mfbridge.common.ui.devices;

import io.github.pix3lpirat3.mfbridge.common.ui.logic.DisplayText;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.RowScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.remote.DeviceInfo;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The devices screen: every remembered or connected device
 * with its method, last seen time, address and live sessions, and buttons to rename, edit
 * permissions and quotas, and revoke. "Pair new device" opens {@link PairScreen}.
 */
public final class DevicesScreen extends RowScreen {
    private static final int BUTTON_WIDTH = 72;
    private static final long REFRESH_MS = 1000;

    private final Supplier<@Nullable BridgeCore> core;
    private List<DeviceInfo> shown = List.of();
    private long refreshedAt;

    /**
     * Creates the screen.
     *
     * @param parent the screen to return to, or null to close to the game
     * @param core the running core, or null while the endpoint is down
     */
    public DevicesScreen(final @Nullable Screen parent, final Supplier<@Nullable BridgeCore> core) {
        super(Component.translatable("screen.mineflayer_bridge.devices.title"), parent);
        this.core = core;
    }

    @Override
    protected int rows(final int left, final int top) {
        final BridgeCore c = core.get();
        if (c == null) {
            label(Component.translatable("mineflayer_bridge.endpoint.not_running"), left, top + 6, UiColors.ERROR);
            return top + ROW_STEP;
        }
        shown = c.devices().list();
        refreshedAt = System.currentTimeMillis();
        int y = top;
        if (visible(y)) {
            label(Component.translatable("screen.mineflayer_bridge.devices.listening", c.config().bindAddress(), c.boundPort(),
                    c.identity().fingerprint().substring(0, 23)), left, y + 6, UiColors.LABEL);
        }
        y += ROW_STEP;
        if (shown.isEmpty() && visible(y)) {
            label(Component.translatable("screen.mineflayer_bridge.devices.empty"),
                    left, y + 6, UiColors.LABEL);
            y += ROW_STEP;
        }
        final long now = System.currentTimeMillis();
        for (final DeviceInfo d : shown) {
            if (visible(y)) {
                final Component method = Component.translatable(d.local()
                        ? (d.remembered() ? "screen.mineflayer_bridge.devices.method.local" : "screen.mineflayer_bridge.devices.method.local_once")
                        : "screen.mineflayer_bridge.devices.method.paired");
                final Component seen = d.connections() > 0 ? Component.translatable("screen.mineflayer_bridge.devices.connected")
                        : Component.translatable("screen.mineflayer_bridge.devices.seen", DisplayText.age(I18n::get, d.lastSeenMs(), now));
                label(Component.literal(d.name()), left, y + 2, UiColors.HEADER);
                label(d.lastAddress() == null ? Component.translatable("screen.mineflayer_bridge.devices.status", method, seen)
                        : Component.translatable("screen.mineflayer_bridge.devices.status.from", method, seen, d.lastAddress()),
                        left, y + 12, UiColors.LABEL);
            }
            y += ROW_STEP;
            if (visible(y)) {
                final Component sessions = d.sessions().isEmpty() ? Component.translatable("screen.mineflayer_bridge.devices.no_sessions")
                        : Component.literal(String.join(", ", d.sessions()));
                label(Component.translatable("screen.mineflayer_bridge.devices.connections", d.connections(), sessions),
                        left, y + 6, UiColors.TEXT);
            }
            y += ROW_STEP;
            if (visible(y)) {
                deviceButtons(c, d, left, y);
            }
            y += ROW_STEP + 6;
        }
        return y;
    }

    private void deviceButtons(final BridgeCore c, final DeviceInfo d, final int left, final int y) {
        final int step = BUTTON_WIDTH + 4;
        addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.devices.rename"), b -> Game.setScreen(
                        new RenameScreen(this, d.name(), label -> {
                            c.devices().rename(d.id(), label);
                            rebuildWidgets();
                        })))
                .bounds(left, y, BUTTON_WIDTH, ROW_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.devices.permissions"),
                        b -> Game.setScreen(new DevicePermissionsScreen(this, core, d.id())))
                .bounds(left + step, y, BUTTON_WIDTH, ROW_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.devices.quotas"),
                        b -> Game.setScreen(new DeviceQuotasScreen(this, core, d.id())))
                .bounds(left + 2 * step, y, BUTTON_WIDTH, ROW_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.devices.revoke"), b -> confirmRevoke(c, d))
                .bounds(left + 3 * step, y, BUTTON_WIDTH, ROW_HEIGHT).build());
    }

    private void confirmRevoke(final BridgeCore c, final DeviceInfo d) {
        Game.setScreen(new ConfirmScreen(
                yes -> {
                    if (yes && core.get() == c) {
                        c.devices().revoke(d.id());
                        status(Component.translatable("screen.mineflayer_bridge.devices.revoked", d.name()), UiColors.WARNING);
                    }
                    Game.setScreen(this);
                },
                Component.translatable("screen.mineflayer_bridge.devices.revoke.title", d.name()),
                Component.translatable(d.local()
                        ? "screen.mineflayer_bridge.devices.revoke.local"
                        : "screen.mineflayer_bridge.devices.revoke.remote"),
                Component.translatable("screen.mineflayer_bridge.devices.revoke"),
                CommonComponents.GUI_CANCEL));
    }

    @Override
    protected void footer(final int y) {
        addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.devices.pair"),
                        b -> Game.setScreen(new PairScreen(this, core)))
                .bounds(width / 2 - 152, y, 150, ROW_HEIGHT).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 + 2, y, 150, ROW_HEIGHT).build());
    }

    @Override
    public void tick() {
        super.tick();
        final BridgeCore c = core.get();
        // Connections and last-seen times change while the screen is open.
        if (c != null && System.currentTimeMillis() - refreshedAt >= REFRESH_MS && !c.devices().list().equals(shown)) {
            rebuildWidgets();
        }
    }
}
