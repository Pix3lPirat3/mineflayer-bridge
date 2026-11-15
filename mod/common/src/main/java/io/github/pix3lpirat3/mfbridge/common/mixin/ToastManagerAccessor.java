package io.github.pix3lpirat3.mfbridge.common.mixin;

import java.util.BitSet;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads which toast slots are in use, so top-right HUD panels start below the toasts. */
@Mixin(ToastManager.class)
public interface ToastManagerAccessor {

    /**
     * Returns the occupied toast slots, one bit per {@code Toast.SLOT_HEIGHT} rows from the top.
     *
     * @return the live set; read only
     */
    @Accessor("occupiedSlots")
    BitSet mfbridge$occupiedSlots();
}
