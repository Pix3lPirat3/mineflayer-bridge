package io.github.pix3lpirat3.mfbridge.mc26_2.mixin;

import io.github.pix3lpirat3.mfbridge.common.render.retained.RetainedRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ghost volumes are compared again when a section changes; in 26.2 every change goes through this. */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void mfbridge$sectionDirty(final int sectionX, final int sectionY, final int sectionZ,
            final boolean playerChanged, final CallbackInfo ci) {
        final RetainedRenderer retained = RetainedRenderer.current();
        if (retained != null) {
            retained.sectionDirty(sectionX, sectionY, sectionZ);
        }
    }
}
