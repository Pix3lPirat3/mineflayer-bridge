package io.github.pix3lpirat3.mfbridge.common.mixin;

import io.github.pix3lpirat3.mfbridge.common.ui.surface.SurfacePackets;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The packet guard: every 26.3 {@code Connection.send}
 * overload, and the queue used before the channel is up, goes through this three-argument overload,
 * so a surface packet sent while a bridge screen is open is dropped here. The integrated server's
 * connections only send clientbound packets, which the guard never matches.
 */
@Mixin(Connection.class)
abstract class ConnectionMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"),
            cancellable = true)
    private void mfbridge$guard(final Packet<?> packet, final @Nullable ChannelFutureListener listener, final boolean flush,
            final CallbackInfo ci) {
        if (SurfacePackets.blocks(packet)) {
            ci.cancel();
        }
    }
}
