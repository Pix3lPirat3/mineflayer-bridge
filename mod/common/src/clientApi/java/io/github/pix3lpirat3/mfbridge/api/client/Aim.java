package io.github.pix3lpirat3.mfbridge.api.client;

import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * What the player's crosshair is on.
 *
 * @param target the entity or block as a bot understands it, ready to put in a message
 * @param entity the entity, or null when the crosshair is on a block
 * @param entityType the entity's type id, such as {@code minecraft:cow}, or null for a block
 * @param block the block's id, such as {@code minecraft:stone}, or null when no block was hit
 */
public record Aim(Target target, @Nullable Entity entity, @Nullable String entityType, @Nullable String block) {
}
