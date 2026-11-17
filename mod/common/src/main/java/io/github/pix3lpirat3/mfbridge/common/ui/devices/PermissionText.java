package io.github.pix3lpirat3.mfbridge.common.ui.devices;

import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import io.github.pix3lpirat3.mfbridge.core.remote.PermissionBatch;
import io.github.pix3lpirat3.mfbridge.core.remote.Quota;
import net.minecraft.network.chat.Component;

/** Translated names of the core's permissions and quotas; the core keeps English labels for logs. */
public final class PermissionText {

    private PermissionText() {}

    /**
     * Returns a permission's name.
     *
     * @param p the permission
     * @return its translated name
     */
    public static Component permission(final Permission p) {
        return Component.translatable(switch (p) {
            case RENDER_WORLD -> "mineflayer_bridge.permission.render.world";
            case RENDER_HUD -> "mineflayer_bridge.permission.render.hud";
            case NOTIFY -> "mineflayer_bridge.permission.notify";
            case INPUT_PICK -> "mineflayer_bridge.permission.input.pick";
            case INPUT_PICK_URGENT -> "mineflayer_bridge.permission.input.pick.urgent";
            case INPUT_KEYBIND -> "mineflayer_bridge.permission.input.keybind";
            case INPUT_TOOL -> "mineflayer_bridge.permission.input.tool";
            case INPUT_INTERCEPT -> "mineflayer_bridge.permission.input.intercept";
            case COMMANDS -> "mineflayer_bridge.permission.commands";
            case FORM -> "mineflayer_bridge.permission.form";
            case UI_CONTAINER -> "mineflayer_bridge.permission.ui.container";
            case RENDER_DISPLAY -> "mineflayer_bridge.permission.render.display";
            case EFFECTS_SOUND -> "mineflayer_bridge.permission.effects.sound";
            case EFFECTS_PARTICLES -> "mineflayer_bridge.permission.effects.particles";
            case EFFECTS_TITLE -> "mineflayer_bridge.permission.effects.title";
            case SETTINGS -> "mineflayer_bridge.permission.settings";
            case CHAT -> "mineflayer_bridge.permission.chat";
            case SHARED_READ -> "mineflayer_bridge.permission.shared.read";
            case SHARED_WRITE -> "mineflayer_bridge.permission.shared.write";
            case CLIENT_SAMPLE_POSITION -> "mineflayer_bridge.permission.client.sample.position";
            case CLIENT_SAMPLE_TARGET -> "mineflayer_bridge.permission.client.sample.target";
            case CLIENT_SAMPLE_INVENTORY -> "mineflayer_bridge.permission.client.sample.inventory";
            case CLIENT_QUERY -> "mineflayer_bridge.permission.client.query";
            case CONTROLLER -> "mineflayer_bridge.permission.controller";
        });
    }

    /**
     * Returns a quota's name.
     *
     * @param q the quota
     * @return its translated name
     */
    public static Component quota(final Quota q) {
        return Component.translatable(switch (q) {
            case OBJECTS -> "mineflayer_bridge.quota.objects";
            case OWNER_BYTES -> "mineflayer_bridge.quota.object_bytes";
            case DEVICE_BYTES -> "mineflayer_bridge.quota.device_object_bytes";
            case DISPLAYS -> "mineflayer_bridge.quota.displays";
            case ENTITY_DISPLAYS -> "mineflayer_bridge.quota.entity_displays";
            case EMITTERS -> "mineflayer_bridge.quota.emitters";
            case SOUND_EMITTERS -> "mineflayer_bridge.quota.sound_emitters";
            case INTERACTIVE -> "mineflayer_bridge.quota.interactive";
            case DECLARATIONS -> "mineflayer_bridge.quota.declarations";
            case COMMANDS -> "mineflayer_bridge.quota.commands";
            case EFFECTS_PER_SECOND -> "mineflayer_bridge.quota.effects_per_second";
            case BLOB_BYTES -> "mineflayer_bridge.quota.blob_bytes";
            case RETAINED_BYTES -> "mineflayer_bridge.quota.retained_bytes";
            case BLOB_IN_FLIGHT -> "mineflayer_bridge.quota.blob_in_flight_bytes";
            case MESSAGES_PER_SECOND -> "mineflayer_bridge.quota.messages_per_second";
            case BYTES_PER_SECOND -> "mineflayer_bridge.quota.bytes_per_second";
        });
    }

    /**
     * Returns a permission prompt item's name.
     *
     * @param item the item
     * @return its translated name
     */
    public static Component item(final PermissionBatch.Item item) {
        return switch (item) {
            case PermissionBatch.Grant g -> permission(g.permission());
            case PermissionBatch.InterceptCancel c -> Component.translatable("mineflayer_bridge.permission.intercept_cancel", c.detail());
        };
    }
}
