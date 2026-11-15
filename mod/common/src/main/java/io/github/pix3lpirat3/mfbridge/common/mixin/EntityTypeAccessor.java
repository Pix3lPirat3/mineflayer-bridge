package io.github.pix3lpirat3.mfbridge.common.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the entity type's factory. Display entities are created through it because 26.3
 * {@code EntityType.create} returns null for hostile mobs on peaceful difficulty, and displays are not
 * spawns.
 */
@Mixin(EntityType.class)
public interface EntityTypeAccessor<T extends Entity> {

    /**
     * Returns {@code EntityType.factory}.
     *
     * @return the factory that constructs this type's entities
     */
    @Accessor("factory")
    EntityType.EntityFactory<T> mfbridge$factory();
}
