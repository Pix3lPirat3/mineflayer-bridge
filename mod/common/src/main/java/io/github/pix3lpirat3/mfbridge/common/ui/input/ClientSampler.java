package io.github.pix3lpirat3.mfbridge.common.ui.input;

import io.github.pix3lpirat3.mfbridge.common.ui.container.SlotItems;
import io.github.pix3lpirat3.mfbridge.core.sample.ClientSampleControls;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Feeds client samples to the core every tick, computing only what some
 * permitted, attached bot wants: no ray cast unless the target is wanted, no item conversion unless
 * the held item is. Client thread only.
 */
public final class ClientSampler {
    /** A sample target is a block or an entity; looking at the sky shares no target. */
    private static final List<String> TARGET_TYPES = List.of("block", "entity");

    private ClientSampler() {
    }

    /**
     * Feeds the current player state when any bot wants it.
     *
     * @param samples the core's sample controls
     */
    public static void tick(final ClientSampleControls samples) {
        final boolean pos = samples.wantsPosition();
        final boolean target = samples.wantsTarget();
        final boolean held = samples.wantsHeldItem();
        if (!pos && !target && !held) {
            return;
        }
        final LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        final Vec3d position = pos ? new Vec3d(player.getX(), player.getY(), player.getZ()) : null;
        final Target t = target
                ? Crosshair.firstTarget(Crosshair.hit(Crosshair.ACTION_RANGE, e -> true), TARGET_TYPES)
                : null;
        samples.feed(position, player.getYRot(), player.getXRot(), t, held ? slotItem(player.getMainHandItem()) : null);
    }

    /**
     * Converts an item stack to the wire form a bot can read: id, count, custom name, glint and
     * durability as a bar fraction.
     *
     * @param stack the stack
     * @return the item, or null for an empty stack
     */
    public static @Nullable SlotItem slotItem(final ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        final String id = SlotItems.id(stack);
        final Component custom = stack.getCustomName();
        final Float bar = stack.isDamaged() && stack.getMaxDamage() > 0
                ? 1f - (float) stack.getDamageValue() / stack.getMaxDamage()
                : null;
        return new SlotItem(id, stack.getCount(), custom == null ? null : custom.getString(), null, stack.hasFoil(), null, null,
                null, bar, null, null, null, null, 0, false, null, null, null);
    }
}
