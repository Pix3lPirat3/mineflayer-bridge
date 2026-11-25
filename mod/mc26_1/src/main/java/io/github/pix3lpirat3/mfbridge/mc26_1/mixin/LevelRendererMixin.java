package io.github.pix3lpirat3.mfbridge.mc26_1.mixin;

import io.github.pix3lpirat3.mfbridge.common.render.retained.RetainedRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.1: retained meshes draw in the main frame pass after translucent terrain, before translucent
 * particles, with no render pass open there. Section changes all go through {@code setSectionDirty}.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {

    @Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderTranslucentParticles()V"))
    private void mfbridge$drawRetained(final CallbackInfo ci) {
        final RetainedRenderer retained = RetainedRenderer.current();
        if (retained != null) {
            retained.draw();
        }
    }

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void mfbridge$sectionDirty(final int sectionX, final int sectionY, final int sectionZ,
            final boolean playerChanged, final CallbackInfo ci) {
        final RetainedRenderer retained = RetainedRenderer.current();
        if (retained != null) {
            retained.sectionDirty(sectionX, sectionY, sectionZ);
        }
    }
}
