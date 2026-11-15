package io.github.pix3lpirat3.mfbridge.common.mixin;

import io.github.pix3lpirat3.mfbridge.common.render.retained.RetainedRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code diffAgainstWorld}: ghost volumes are compared again whenever an affected section
 * changes. In 26.3 every block update, block state change and chunk load funnels through this private
 * {@code setSectionDirty} on the client thread, so it is the one place to listen.
 */
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
