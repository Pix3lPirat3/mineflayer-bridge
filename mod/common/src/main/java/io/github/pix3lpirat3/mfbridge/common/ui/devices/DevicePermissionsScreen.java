package io.github.pix3lpirat3.mfbridge.common.ui.devices;

import io.github.pix3lpirat3.mfbridge.common.ui.screen.RowScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.remote.DeviceInfo;
import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Permissions of one device: device-level
 * toggles, then per-bot overrides for the bots seen on the device. Each change applies at once and
 * is pushed to the device as {@code permissions}. The bot view also holds the intercept cancel
 * grant, which can only be granted per bot.
 */
public final class DevicePermissionsScreen extends RowScreen {
    /** The value of a per-bot override. */
    private enum BotValue {
        INHERIT("screen.mineflayer_bridge.permissions.inherit"),
        ON("screen.mineflayer_bridge.permissions.on"),
        OFF("screen.mineflayer_bridge.permissions.off");

        /** Lang key; INHERIT's takes the device value. */
        final String key;

        BotValue(final String key) {
            this.key = key;
        }
    }

    private static final String DEVICE_SCOPE = "";

    private final Supplier<@Nullable BridgeCore> core;
    private final String deviceId;
    /** {@link #DEVICE_SCOPE} or a bot identity. */
    private String scope = DEVICE_SCOPE;

    /**
     * Creates the screen.
     *
     * @param parent the screen to return to
     * @param core the running core, or null while the endpoint is down
     * @param deviceId the device whose permissions are edited
     */
    public DevicePermissionsScreen(final Screen parent, final Supplier<@Nullable BridgeCore> core, final String deviceId) {
        super(Component.translatable("screen.mineflayer_bridge.permissions.title"), parent);
        this.core = core;
        this.deviceId = deviceId;
    }

    @Override
    protected int rows(final int left, final int top) {
        final BridgeCore c = core.get();
        final DeviceInfo d = c == null ? null : c.devices().get(deviceId);
        if (c == null || d == null) {
            label(Component.translatable("mineflayer_bridge.device.gone"), left, top + 6, UiColors.ERROR);
            return top + ROW_STEP;
        }
        int y = top;
        if (visible(y)) {
            label(Component.translatable(d.local()
                    ? "screen.mineflayer_bridge.permissions.local_defaults" : "screen.mineflayer_bridge.permissions.remote_defaults", d.name()),
                    left, y + 6, UiColors.HEADER);
        }
        y += ROW_STEP;
        final List<String> scopes = new ArrayList<>();
        scopes.add(DEVICE_SCOPE);
        scopes.addAll(d.bots());
        if (!scopes.contains(scope)) {
            scope = DEVICE_SCOPE;
        }
        if (visible(y)) {
            addRenderableWidget(CycleButton.builder((String s) -> s.isEmpty()
                    ? Component.translatable("screen.mineflayer_bridge.permissions.whole_device")
                    : Component.translatable("screen.mineflayer_bridge.permissions.bot", s), scope)
                    .withValues(scopes)
                    .create(left, y, columnWidth, ROW_HEIGHT, Component.translatable("screen.mineflayer_bridge.permissions.applies_to"), (b, v) -> {
                        scope = v;
                        rebuildWidgets();
                    }));
        }
        y += ROW_STEP + 4;
        if (scope.equals(DEVICE_SCOPE)) {
            for (final Permission p : Permission.values()) {
                if (visible(y)) {
                    addRenderableWidget(CycleButton.onOffBuilder(d.permissions().has(p)).create(left, y, columnWidth, ROW_HEIGHT,
                            PermissionText.permission(p).copy().append(" (" + p.key() + ")"),
                            (b, v) -> c.devices().setPermission(deviceId, p, v)));
                }
                y += ROW_STEP;
            }
            return y;
        }
        final String bot = scope;
        if (visible(y)) {
            final Boolean cancel = c.intercepts().cancelPermission(bot);
            addRenderableWidget(CycleButton.onOffBuilder(cancel != null ? cancel : d.local()).create(left, y, columnWidth, ROW_HEIGHT,
                    Component.translatable("screen.mineflayer_bridge.permissions.cancel_interactions"),
                    (b, v) -> c.intercepts().setCancelPermission(bot, v)));
        }
        y += ROW_STEP;
        // The bot belongs to the device that first ran it; this lets another device take it.
        if (deviceId.equals(c.devices().botDevice(bot))) {
            if (visible(y)) {
                addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.permissions.forget_bot"), b -> {
                    c.devices().forgetBot(bot);
                    status(Component.translatable("screen.mineflayer_bridge.permissions.forget_bot_done"), UiColors.TEXT);
                    rebuildWidgets();
                }).bounds(left, y, columnWidth, ROW_HEIGHT).build());
            }
            y += ROW_STEP;
        }
        final Map<Permission, Boolean> overrides = d.botOverrides().getOrDefault(bot, Map.of());
        for (final Permission p : Permission.values()) {
            if (visible(y)) {
                final Boolean o = overrides.get(p);
                final BotValue current = o == null ? BotValue.INHERIT : o ? BotValue.ON : BotValue.OFF;
                final boolean inherited = d.permissions().has(p);
                addRenderableWidget(CycleButton.builder((BotValue v) -> v == BotValue.INHERIT
                                ? Component.translatable(v.key, Component.translatable(inherited
                                        ? "screen.mineflayer_bridge.permissions.inherit.on" : "screen.mineflayer_bridge.permissions.inherit.off"))
                                : Component.translatable(v.key), current)
                        .withValues(BotValue.values())
                        .create(left, y, columnWidth, ROW_HEIGHT, PermissionText.permission(p),
                                (b, v) -> c.devices().setBotPermission(deviceId, bot, p,
                                        v == BotValue.INHERIT ? null : v == BotValue.ON)));
            }
            y += ROW_STEP;
        }
        return y;
    }

    @Override
    protected void footer(final int y) {
        addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.permissions.reset"), b -> {
            final BridgeCore c = core.get();
            if (c != null) {
                c.devices().resetPermissions(deviceId);
                status(Component.translatable("screen.mineflayer_bridge.permissions.reset_done"), UiColors.TEXT);
                rebuildWidgets();
            }
        }).bounds(width / 2 - 152, y, 150, ROW_HEIGHT).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 + 2, y, 150, ROW_HEIGHT).build());
    }
}
