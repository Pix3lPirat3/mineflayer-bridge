package io.github.pix3lpirat3.mfbridge.common.mixin;

import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Offers every key event to the bridge before vanilla. */
@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void mfbridge$keyPress(final long handle, final int action, final KeyEvent event, final CallbackInfo ci) {
        final BridgeUi ui = BridgeUi.current();
        if (ui != null && handle == Minecraft.getInstance().getWindow().handle() && ui.onKey(action, event)) {
            ci.cancel();
        }
    }
}
