package io.github.pix3lpirat3.mfbridge.mc26_2.mixin;

import io.github.pix3lpirat3.mfbridge.common.render.retained.RetainedRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Retained meshes draw after translucent terrain so they compose with water and glass. In 26.2 the
 * main frame pass is a lambda that ends with the translucent features drawn after terrain, with no
 * render pass open between them; the retained tier opens its own pass there.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {

    @Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucentAfterTerrain()V"))
    private void mfbridge$drawRetained(final CallbackInfo ci) {
        final RetainedRenderer retained = RetainedRenderer.current();
        if (retained != null) {
            retained.draw();
        }
    }
}
