package io.github.pix3lpirat3.mfbridge.common.mixin;

import com.mojang.brigadier.CommandDispatcher;
import io.github.pix3lpirat3.mfbridge.common.BridgeClient;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.seam.Notification;
import io.github.pix3lpirat3.mfbridge.common.ui.chat.ChatBridgeClient;
import io.github.pix3lpirat3.mfbridge.common.ui.command.BridgeCommands;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat bridge: a captured chat line is cancelled before any chat packet is built. 26.3 {@code
 * sendChat} signs the message and advances the last-seen tracker before sending, so the line is
 * offered to the bridge at the head of the method, on both loaders. An escaped line ({@code @@...})
 * is re-sent with one prefix removed.
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {

    @Shadow
    private CommandDispatcher<ClientSuggestionProvider> commands;

    /**
     * Right after vanilla builds the server's command tree, and before the loader
     * adds client commands to it, warn when the server has a command with the bridge command's name.
     */
    @Inject(method = "handleCommands", at = @At(value = "FIELD", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;commands:Lcom/mojang/brigadier/CommandDispatcher;", opcode = 181 /* PUTFIELD */, shift = At.Shift.AFTER))
    private void mfbridge$serverCommands(final ClientboundCommandsPacket packet, final CallbackInfo ci) {
        final Set<String> roots = new HashSet<>();
        commands.getRoot().getChildren().forEach(n -> roots.add(n.getName()));
        final String warning = BridgeCommands.serverCommands(roots);
        if (warning != null) {
            Constants.LOG.warn(warning);
            try {
                BridgeClient.get().notify(new Notification(Constants.MOD_NAME, warning, Notification.Severity.WARNING));
            } catch (final IllegalStateException e) {
                // not started
            }
        }
    }

    @Unique
    private static boolean mfbridge$resending;

    @Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
    private void mfbridge$routeChat(final String content, final CallbackInfo ci) {
        final ChatBridgeClient chat = ChatBridgeClient.current();
        if (mfbridge$resending || chat == null) {
            return;
        }
        final String forServer = chat.filterOutgoing(content);
        if (forServer == null) {
            ci.cancel();
            return;
        }
        if (!forServer.equals(content)) {
            ci.cancel();
            mfbridge$resending = true;
            try {
                ((ClientPacketListener) (Object) this).sendChat(forServer);
            } finally {
                mfbridge$resending = false;
            }
        }
    }
}
