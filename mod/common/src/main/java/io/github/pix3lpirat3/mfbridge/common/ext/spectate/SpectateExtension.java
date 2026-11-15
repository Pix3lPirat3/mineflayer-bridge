package io.github.pix3lpirat3.mfbridge.common.ext.spectate;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pix3lpirat3.mfbridge.api.ExtensionId;
import io.github.pix3lpirat3.mfbridge.api.ExtensionRegistrar;
import io.github.pix3lpirat3.mfbridge.api.SemVer;
import io.github.pix3lpirat3.mfbridge.api.client.BotButton;
import io.github.pix3lpirat3.mfbridge.api.client.ClientBridge;
import io.github.pix3lpirat3.mfbridge.api.client.ClientExtension;
import io.github.pix3lpirat3.mfbridge.api.client.ClientRegistrar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * {@code bridge:spectate}: the camera follows a bot's player entity. Started from the bot's
 * row on Bridge home; Sneak or the stop key returns the camera, and so does the bot leaving the
 * player's view. Only the local camera changes: nothing is sent to the server, and the player keeps
 * control of their own character.
 */
public final class SpectateExtension implements ClientExtension {

    /** The extension id. */
    public static final ExtensionId ID = new ExtensionId("bridge", "spectate");

    private @Nullable ClientBridge bridge;
    private @Nullable String owner;
    private @Nullable String name;
    private @Nullable ClientLevel level;

    @Override
    public ExtensionId id() {
        return ID;
    }

    @Override
    public SemVer version() {
        return SemVer.parse("1.0.0");
    }

    @Override
    public void register(final ExtensionRegistrar registrar) {
        // Client only: no kinds or messages.
    }

    @Override
    public void registerClient(final ClientRegistrar r) {
        this.bridge = r.bridge();
        r.botButton(Component.translatable("mineflayer_bridge_spectate.button"), "minecraft:ender_eye", new BotButton() {
            @Override
            public boolean shows(final String botOwner, final boolean online) {
                return online && !botOwner.equals(owner);
            }

            @Override
            public void press(final String botOwner) {
                start(botOwner);
            }
        });
        r.botButton(Component.translatable("mineflayer_bridge_spectate.button.stop"), "minecraft:ender_pearl", new BotButton() {
            @Override
            public boolean shows(final String botOwner, final boolean online) {
                return botOwner.equals(owner);
            }

            @Override
            public void press(final String botOwner) {
                stop(null);
            }
        });
        r.keybind("stop_spectating", InputConstants.UNKNOWN.getValue(), () -> stop(null));
        r.tick(this::tick);
        r.hud((g, delta) -> {
            final String n = name;
            if (n != null && owner != null) {
                final Minecraft mc = Minecraft.getInstance();
                final String text = I18n.get("mineflayer_bridge_spectate.hud", n);
                g.text(mc.font, text, (g.guiWidth() - mc.font.width(text)) / 2, g.guiHeight() - 72, 0xFFFFFFFF, true);
            }
        });
    }

    /**
     * Moves the camera to a bot's player entity, if the player's client has it loaded.
     *
     * @param botOwner the bot's owner key
     */
    public void start(final String botOwner) {
        final ClientBridge b = bridge;
        final Minecraft mc = Minecraft.getInstance();
        if (b == null || mc.level == null || mc.player == null) {
            return;
        }
        final String botName = b.botName(botOwner);
        final Entity entity = find(mc.level, botName);
        if (entity == null) {
            b.toastError(I18n.get("mineflayer_bridge_spectate.toast"), I18n.get("mineflayer_bridge_spectate.not_in_view", botName));
            return;
        }
        owner = botOwner;
        name = botName;
        level = mc.level;
        mc.gui.setScreen(null);
        mc.setCameraEntity(entity);
    }

    /** Returns the camera to the player; {@code why}, a translation key taking the bot name, becomes a toast when not null. */
    private void stop(final @Nullable String why) {
        if (owner == null) {
            return;
        }
        final String was = name;
        owner = null;
        name = null;
        level = null;
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
        }
        final ClientBridge b = bridge;
        if (why != null && b != null) {
            b.toast(I18n.get("mineflayer_bridge_spectate.toast"), I18n.get(why, was));
        }
    }

    private void tick() {
        final String n = name;
        if (owner == null || n == null) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.level != level) {
            stop(null);
            return;
        }
        if (mc.options.keyShift.isDown() && mc.gui.screen() == null) {
            stop(null);
            return;
        }
        final Entity camera = mc.getCameraEntity();
        if (camera == null || camera.isRemoved() || !(camera instanceof Player p) || !p.getGameProfile().name().equalsIgnoreCase(n)) {
            // The entity was unloaded and may come back as a new instance.
            final Entity again = find(mc.level, n);
            if (again == null) {
                stop("mineflayer_bridge_spectate.left_view");
            } else {
                mc.setCameraEntity(again);
            }
        }
    }

    private static @Nullable Entity find(final ClientLevel level, final String name) {
        for (final Player p : level.players()) {
            if (p.getGameProfile().name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }
}
