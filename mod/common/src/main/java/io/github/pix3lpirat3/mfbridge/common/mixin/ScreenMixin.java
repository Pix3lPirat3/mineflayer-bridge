package io.github.pix3lpirat3.mfbridge.common.mixin;

import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the item pick hint over the inventory screen, which covers the HUD. */
@Mixin(Screen.class)
abstract class ScreenMixin {

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("TAIL"))
    private void mfbridge$afterRender(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a,
            final CallbackInfo ci) {
        final BridgeUi ui = BridgeUi.current();
        if (ui != null) {
            ui.renderScreenOverlay(graphics, (Screen) (Object) this);
        }
    }
}
