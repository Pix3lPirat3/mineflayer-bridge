package io.github.pix3lpirat3.mfbridge.common.mixin;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the vanilla boss bars, so bot boss bars are drawn below them. */
@Mixin(BossHealthOverlay.class)
public interface BossHealthOverlayAccessor {

    /**
     * Returns the vanilla boss bars currently shown.
     *
     * @return the live map; read only
     */
    @Accessor("events")
    Map<UUID, LerpingBossEvent> mfbridge$events();
}
