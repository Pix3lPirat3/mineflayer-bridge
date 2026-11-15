package io.github.pix3lpirat3.mfbridge.common.mixin;

import io.github.pix3lpirat3.mfbridge.common.ui.chat.ChatBridgeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat bridge: clickable spans of client-only bot lines run on the client. In 26.3 {@code
 * ChatScreen} hands custom click events to {@code Screen.defaultHandleGameClickEvent}, which would send
 * {@code ServerboundCustomClickActionPacket}; bridge clicks are handled and stopped here instead.
 */
@Mixin(Screen.class)
abstract class ScreenClickMixin {

    @Inject(method = "defaultHandleGameClickEvent", at = @At("HEAD"), cancellable = true)
    private static void mfbridge$bridgeClick(final ClickEvent event, final Minecraft minecraft, final @Nullable Screen activeScreen,
            final CallbackInfo ci) {
        final ChatBridgeClient chat = ChatBridgeClient.current();
        if (chat != null && chat.onClick(event)) {
            ci.cancel();
        }
    }
}
