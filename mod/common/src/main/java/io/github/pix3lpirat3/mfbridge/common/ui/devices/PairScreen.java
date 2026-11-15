package io.github.pix3lpirat3.mfbridge.common.ui.devices;

import io.github.pix3lpirat3.mfbridge.common.ui.screen.RowScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.remote.PairingSession;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Bridge home > Tools > Pair new device: shows the {@code mfb://} URI in a
 * selectable field with a Copy button, the code and its countdown, and lets the player change the
 * advertised host. Leaving the screen keeps the code valid until it expires or is used.
 */
public final class PairScreen extends RowScreen {
    private final Supplier<@Nullable BridgeCore> core;
    private String host = "";
    private @Nullable PairingSession shown;

    /**
     * Creates the screen.
     *
     * @param parent the screen to return to
     * @param core the running core, or null while the endpoint is down
     */
    public PairScreen(final Screen parent, final Supplier<@Nullable BridgeCore> core) {
        super(Component.translatable("screen.mineflayer_bridge.pair.title"), parent);
        this.core = core;
        final BridgeCore c = core.get();
        if (c != null) {
            final PairingSession active = c.pairing().active();
            host = active != null ? active.host() : c.pairing().defaultAdvertiseHost();
        }
    }

    @Override
    protected int rows(final int left, final int top) {
        final BridgeCore c = core.get();
        if (c == null) {
            label(Component.translatable("mineflayer_bridge.endpoint.not_running"), left, top + 6, UiColors.ERROR);
            return top + ROW_STEP;
        }
        int y = top;
        if (loopbackOnly(c.config().bindAddress())) {
            label(Component.translatable("screen.mineflayer_bridge.pair.loopback_only.line1", c.config().bindAddress()),
                    left, y + 2, UiColors.WARNING);
            label(Component.translatable("screen.mineflayer_bridge.pair.loopback_only.line2"), left, y + 12, UiColors.WARNING);
            y += ROW_STEP;
        }
        label(Component.translatable("screen.mineflayer_bridge.pair.host"), left, y + 6, UiColors.LABEL);
        final EditBox hostBox = new EditBox(font, left + 100, y, columnWidth - 100, ROW_HEIGHT,
                Component.translatable("screen.mineflayer_bridge.pair.host_box"));
        hostBox.setMaxLength(253);
        hostBox.setValue(host);
        hostBox.setResponder(v -> host = v);
        addRenderableWidget(hostBox);
        y += ROW_STEP;
        addRenderableWidget(Button.builder(Component.translatable(c.pairing().active() == null
                        ? "screen.mineflayer_bridge.pair.create" : "screen.mineflayer_bridge.pair.new_code"),
                b -> {
                    c.pairing().begin(host);
                    rebuildWidgets();
                }).bounds(left, y, columnWidth, ROW_HEIGHT).build());
        y += ROW_STEP + 6;

        final PairingSession s = c.pairing().active();
        shown = s;
        if (s == null) {
            label(Component.translatable("screen.mineflayer_bridge.pair.validity"), left, y + 6, UiColors.LABEL);
            return y + ROW_STEP;
        }
        label(Component.translatable("screen.mineflayer_bridge.pair.give_link"), left, y + 6, UiColors.LABEL);
        y += ROW_STEP - 6;
        final EditBox uri = new EditBox(font, left, y, columnWidth - 54, ROW_HEIGHT, Component.translatable("screen.mineflayer_bridge.pair.link"));
        uri.setMaxLength(512);
        uri.setValue(s.uri());
        uri.setEditable(false);
        addRenderableWidget(uri);
        addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.pair.copy"), b -> {
            minecraft.keyboardHandler.setClipboard(s.uri());
            status(Component.translatable("screen.mineflayer_bridge.pair.copied"), UiColors.TEXT);
        }).bounds(left + columnWidth - 50, y, 50, ROW_HEIGHT).build());
        y += ROW_STEP + 4;
        label(Component.translatable("screen.mineflayer_bridge.pair.code", s.code()), left, y + 6, UiColors.HEADER);
        y += ROW_STEP;
        addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.pair.cancel"), b -> {
            c.pairing().cancel();
            rebuildWidgets();
        }).bounds(left, y, 100, ROW_HEIGHT).build());
        return y + ROW_STEP;
    }

    private static boolean loopbackOnly(final String bind) {
        try {
            return InetAddress.getByName(bind).isLoopbackAddress();
        } catch (final UnknownHostException e) {
            return false;
        }
    }

    @Override
    protected void footer(final int y) {
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 - 75, y, 150, ROW_HEIGHT).build());
    }

    @Override
    public void tick() {
        super.tick();
        final BridgeCore c = core.get();
        final PairingSession now = c == null ? null : c.pairing().active();
        if (now != shown) {
            if (shown != null && now == null) {
                status(Component.translatable("screen.mineflayer_bridge.pair.code_gone"), UiColors.WARNING);
            }
            rebuildWidgets();
            return;
        }
        if (now != null) {
            final long left = Math.max(0, now.expiresAtMs() - System.currentTimeMillis()) / 1000;
            status(Component.translatable("screen.mineflayer_bridge.pair.expires", left / 60,
                    String.format(Locale.ROOT, "%02d", left % 60)), UiColors.TEXT);
        }
    }
}
