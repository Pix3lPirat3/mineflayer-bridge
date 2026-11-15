package io.github.pix3lpirat3.mfbridge.common.ui.devices;

import io.github.pix3lpirat3.mfbridge.common.ui.screen.RowScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.remote.DeviceInfo;
import io.github.pix3lpirat3.mfbridge.core.remote.Quota;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Quotas of one device: one field per quota, applied on Save. Enforcement changes
 * at once; the device sees the new values in its next {@code welcome}.
 */
final class DeviceQuotasScreen extends RowScreen {
    private static final int LABEL_WIDTH = 190;

    private final Supplier<@Nullable BridgeCore> core;
    private final String deviceId;
    private final Map<Quota, String> pending = new EnumMap<>(Quota.class);

    DeviceQuotasScreen(final Screen parent, final Supplier<@Nullable BridgeCore> core, final String deviceId) {
        super(Component.translatable("screen.mineflayer_bridge.quotas.title"), parent);
        this.core = core;
        this.deviceId = deviceId;
    }

    @Override
    protected int rows(final int left, final int top) {
        final BridgeCore c = core.get();
        final DeviceInfo d = c == null ? null : c.devices().get(deviceId);
        if (d == null) {
            label(Component.translatable("mineflayer_bridge.device.gone"), left, top + 6, UiColors.ERROR);
            return top + ROW_STEP;
        }
        int y = top;
        if (visible(y)) {
            label(Component.translatable("screen.mineflayer_bridge.quotas.header", d.name()), left, y + 6, UiColors.HEADER);
        }
        y += ROW_STEP;
        for (final Quota q : Quota.values()) {
            if (visible(y)) {
                label(PermissionText.quota(q), left, y + 6, UiColors.LABEL);
                final EditBox box = new EditBox(font, left + LABEL_WIDTH, y, columnWidth - LABEL_WIDTH, ROW_HEIGHT,
                        PermissionText.quota(q));
                box.setMaxLength(10);
                box.setValue(pending.getOrDefault(q, Integer.toString(d.quotas().get(q))));
                box.setResponder(v -> pending.put(q, v));
                addRenderableWidget(box);
            }
            y += ROW_STEP;
        }
        return y;
    }

    @Override
    protected void footer(final int y) {
        addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.quotas.save"), b -> save())
                .bounds(width / 2 - 154, y, 100, ROW_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.quotas.defaults"), b -> {
            final BridgeCore c = core.get();
            if (c != null) {
                c.devices().resetQuotas(deviceId);
                pending.clear();
                status(Component.translatable("screen.mineflayer_bridge.quotas.reset"), UiColors.TEXT);
                rebuildWidgets();
            }
        }).bounds(width / 2 - 50, y, 100, ROW_HEIGHT).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 + 54, y, 100, ROW_HEIGHT).build());
    }

    private void save() {
        final BridgeCore c = core.get();
        if (c == null) {
            return;
        }
        final Map<Quota, Integer> parsed = new EnumMap<>(Quota.class);
        for (final Map.Entry<Quota, String> e : pending.entrySet()) {
            try {
                final int v = Integer.parseInt(e.getValue().strip());
                if (v < 0) {
                    throw new NumberFormatException();
                }
                parsed.put(e.getKey(), v);
            } catch (final NumberFormatException ex) {
                status(Component.translatable("screen.mineflayer_bridge.quotas.error", PermissionText.quota(e.getKey())), UiColors.ERROR);
                return;
            }
        }
        for (final Map.Entry<Quota, Integer> e : parsed.entrySet()) {
            c.devices().setQuota(deviceId, e.getKey(), e.getValue());
        }
        pending.clear();
        status(Component.translatable("screen.mineflayer_bridge.quotas.saved"), UiColors.TEXT);
        rebuildWidgets();
    }
}
