package io.github.pix3lpirat3.mfbridge.common.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the creeper's private charged flag, which 26.3 only sets from a lightning strike. */
@Mixin(Creeper.class)
public interface CreeperAccessor {

    /**
     * Returns {@code Creeper.DATA_IS_POWERED}.
     *
     * @return the synched data key of the charged flag
     */
    @Accessor("DATA_IS_POWERED")
    static EntityDataAccessor<Boolean> mfbridge$poweredKey() {
        throw new AssertionError("mixin accessor");
    }
}
