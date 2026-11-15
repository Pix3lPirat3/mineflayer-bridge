package io.github.pix3lpirat3.mfbridge.common.mixin;

import io.github.pix3lpirat3.mfbridge.common.render.retained.RetainedRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Retained meshes draw after the translucent pass so they compose with water and
 * glass. In 26.3 the main frame pass runs solid terrain, classic translucency or OIT composition, then
 * {@code executeOutline}, with no render pass open at its head; the retained tier opens its own pass
 * there, on both loaders.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {

    @Inject(method = "executeOutline", at = @At("HEAD"))
    private void mfbridge$drawRetained(final CallbackInfo ci) {
        final RetainedRenderer retained = RetainedRenderer.current();
        if (retained != null) {
            retained.draw();
        }
    }
}
