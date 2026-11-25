package io.github.pix3lpirat3.mfbridge.common.ui.surface;

import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.PacketGuard;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.BridgeScreen;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;

/**
 * Maps outgoing packets onto the game-free {@link PacketGuard} for the connection mixin. Called from
 * whichever thread sends; reading the open screen from another thread is a benign race, since the
 * bridge's own screens never build these packets in the first place.
 */
public final class SurfacePackets {

    private SurfacePackets() {
    }

    /**
     * Decides whether an outgoing packet must be dropped, and logs when it is.
     *
     * @param packet the packet about to be sent
     * @return true to cancel the send
     */
    public static boolean blocks(final Packet<?> packet) {
        final PacketGuard.Kind kind = kind(packet);
        if (kind == PacketGuard.Kind.OTHER) {
            return false;
        }
        final BridgeUi ui = BridgeUi.current();
        if (ui == null) {
            return false;
        }
        final boolean bridgeScreen = Game.screen() instanceof BridgeScreen;
        final String chat = packet instanceof ServerboundChatPacket c ? c.message() : null;
        final String namespace = packet instanceof ServerboundCustomClickActionPacket c ? c.id().getNamespace() : null;
        final boolean blocked = ui.packetGuard().blocks(kind, bridgeScreen, chat, namespace, System.nanoTime() / 1_000_000L);
        if (blocked) {
            Constants.LOG.warn("Dropped {} while a bridge surface was open (bridge surfaces never send it)",
                    packet.getClass().getSimpleName());
        }
        return blocked;
    }

    private static PacketGuard.Kind kind(final Packet<?> packet) {
        return switch (packet) {
            case ServerboundRenameItemPacket p -> PacketGuard.Kind.RENAME_ITEM;
            case ServerboundSignUpdatePacket p -> PacketGuard.Kind.SIGN_UPDATE;
            case ServerboundEditBookPacket p -> PacketGuard.Kind.EDIT_BOOK;
            case ServerboundContainerClickPacket p -> PacketGuard.Kind.CONTAINER_CLICK;
            case ServerboundContainerClosePacket p -> PacketGuard.Kind.CONTAINER_CLOSE;
            case ServerboundSelectTradePacket p -> PacketGuard.Kind.SELECT_TRADE;
            case ServerboundContainerButtonClickPacket p -> PacketGuard.Kind.CONTAINER_BUTTON;
            case ServerboundPlaceRecipePacket p -> PacketGuard.Kind.PLACE_RECIPE;
            case ServerboundCustomClickActionPacket p -> PacketGuard.Kind.CUSTOM_CLICK_ACTION;
            case ServerboundChatPacket p -> PacketGuard.Kind.CHAT;
            default -> PacketGuard.Kind.OTHER;
        };
    }
}
