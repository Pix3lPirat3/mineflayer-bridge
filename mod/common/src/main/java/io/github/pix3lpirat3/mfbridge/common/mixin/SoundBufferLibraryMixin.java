package io.github.pix3lpirat3.mfbridge.common.mixin;

import io.github.pix3lpirat3.mfbridge.common.sound.BridgeAudio;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Serves custom bot audio to the sound engine: a streamed sound under the
 * bridge's {@code sounds/blob/} path opens from {@link BridgeAudio} instead of the resource packs. Every
 * loader streams through this method, Fabric's {@code FabricSoundInstance} included.
 */
@Mixin(SoundBufferLibrary.class)
abstract class SoundBufferLibraryMixin {

    @Inject(method = "getStream", at = @At("HEAD"), cancellable = true)
    private void mfbridge$blobStream(final Identifier path, final boolean looping, final CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
        final CompletableFuture<AudioStream> stream = BridgeAudio.open(path, looping);
        if (stream != null) {
            cir.setReturnValue(stream);
        }
    }
}
