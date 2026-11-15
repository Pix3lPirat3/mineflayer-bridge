package io.github.pix3lpirat3.mfbridge.common.mixin;

import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Offers mouse buttons and scrolling to the bridge before vanilla. A
 * consumed click never becomes a key mapping click, so vanilla never attacks or uses with it.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void mfbridge$onButton(final long handle, final MouseButtonInfo info, final int action, final CallbackInfo ci) {
        final BridgeUi ui = BridgeUi.current();
        if (ui != null && handle == Minecraft.getInstance().getWindow().handle() && ui.onMouseButton(info, action)) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void mfbridge$onScroll(final long handle, final double xoffset, final double yoffset, final CallbackInfo ci) {
        final BridgeUi ui = BridgeUi.current();
        if (ui != null && handle == Minecraft.getInstance().getWindow().handle() && ui.onScroll(yoffset)) {
            ci.cancel();
        }
    }
}
