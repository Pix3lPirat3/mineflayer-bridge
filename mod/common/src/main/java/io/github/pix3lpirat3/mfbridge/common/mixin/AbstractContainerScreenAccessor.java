package io.github.pix3lpirat3.mfbridge.common.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the slot under the mouse, which item picks answer with. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    /**
     * Returns the slot under the mouse, as of the last frame.
     *
     * @return the slot, or null when the mouse is not over one
     */
    @Accessor("hoveredSlot")
    @Nullable Slot mfbridge$hoveredSlot();
}
