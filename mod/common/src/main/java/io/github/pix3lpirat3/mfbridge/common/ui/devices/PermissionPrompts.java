package io.github.pix3lpirat3.mfbridge.common.ui.devices;

import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.remote.DevicePaired;
import io.github.pix3lpirat3.mfbridge.core.remote.PermissionBatch;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import org.jspecify.annotations.Nullable;

/**
 * Denials of a remote device are collected for {@link
 * io.github.pix3lpirat3.mfbridge.core.remote.DeviceControls#PROMPT_COLLECT_MS} and then asked on
 * one screen, announced by a toast, when the player is in game with no other screen open. Local
 * devices are trusted and never prompted. Also toasts successful pairings.
 */
public final class PermissionPrompts {
    private final Supplier<@Nullable BridgeCore> core;
    private final BiConsumer<String, String> toast;
    private @Nullable BridgeCore subscribed;
    private @Nullable PermissionBatch asking;
    private @Nullable PermissionBatch toasted;

    /**
     * Creates the prompts.
     *
     * @param core the running core, or null while the endpoint is down
     * @param toast shows an info toast with a title and a message
     */
    public PermissionPrompts(final Supplier<@Nullable BridgeCore> core, final BiConsumer<String, String> toast) {
        this.core = core;
        this.toast = toast;
    }

    /** Client tick: shows the next batch when nothing else is on screen. */
    public void tick() {
        final BridgeCore c = core.get();
        if (c != subscribed) {
            subscribed = c;
            asking = null;
            if (c != null) {
                c.events().subscribe(DevicePaired.TYPE,
                        e -> toast.accept(Constants.MOD_NAME, I18n.get("mineflayer_bridge.pairing.paired", e.deviceName(), e.remoteAddress())));
            }
        }
        if (c == null || asking != null) {
            return;
        }
        final PermissionBatch batch = c.devices().permissionBatch();
        if (batch == null) {
            return;
        }
        if (toasted == null || !toasted.deviceId().equals(batch.deviceId())) {
            toasted = batch;
            final int n = batch.items().size();
            toast.accept(batch.deviceName(), n == 1 ? I18n.get("mineflayer_bridge.permission_prompt.one", PermissionText.item(batch.items().getFirst()).getString())
                    : I18n.get("mineflayer_bridge.permission_prompt.other", n));
        }
        if (!UiContext.inPlay()) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        asking = batch;
        minecraft.gui.setScreen(new PermissionBatchScreen(batch, allowed -> {
            asking = null;
            toasted = null;
            if (core.get() == c) {
                c.devices().answerPermissions(batch, allowed);
            }
            minecraft.gui.setScreen(null);
        }));
    }
}
