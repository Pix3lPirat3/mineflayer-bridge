package io.github.pix3lpirat3.mfbridge.common.ui.container;

import io.github.pix3lpirat3.mfbridge.common.ui.logic.TargetMatch;
import io.github.pix3lpirat3.mfbridge.core.container.SlotStorage;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.TargetSpec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * Item rules for a view's virtual storage, from the game's item registry: the default stack size of
 * the item type, and id and tag filters for {@code accepts}. Unknown items
 * stack to 64 and carry no tags.
 */
public final class StorageRules implements SlotStorage.Rules {

    /** The shared instance; it holds no state. */
    public static final StorageRules INSTANCE = new StorageRules();

    private StorageRules() {
    }

    private static @Nullable Item item(final SlotItem item) {
        return SlotItems.item(item.id());
    }

    @Override
    public int maxStackSize(final SlotItem item) {
        final Item type = item(item);
        return type == null ? 64 : type.getDefaultMaxStackSize();
    }

    @Override
    public boolean accepts(final TargetSpec spec, final SlotItem item) {
        final Item type = item(item);
        return TargetMatch.matchesFilter(spec.ids(), spec.tags(), item.id(), tag -> {
            final Identifier id = Identifier.tryParse(tag);
            return type != null && id != null && BuiltInRegistries.ITEM.wrapAsHolder(type).is(TagKey.create(Registries.ITEM, id));
        });
    }
}
