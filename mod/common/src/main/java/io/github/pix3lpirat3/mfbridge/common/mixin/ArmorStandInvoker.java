package io.github.pix3lpirat3.mfbridge.common.mixin;

import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reaches the private armor stand flags that display entities set (26.3 {@code ArmorStand}). */
@Mixin(ArmorStand.class)
public interface ArmorStandInvoker {

    /**
     * Calls {@code ArmorStand.setSmall}.
     *
     * @param value whether the stand is small
     */
    @Invoker("setSmall")
    void mfbridge$setSmall(boolean value);

    /**
     * Calls {@code ArmorStand.setMarker}.
     *
     * @param value whether the stand is a marker
     */
    @Invoker("setMarker")
    void mfbridge$setMarker(boolean value);
}
