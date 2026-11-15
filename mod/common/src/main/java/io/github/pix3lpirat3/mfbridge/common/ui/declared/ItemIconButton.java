package io.github.pix3lpirat3.mfbridge.common.ui.declared;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * A vanilla button with an item icon left of its label, for actions that declare an {@code icon},
 * such as {@code icon: 'minecraft:chest'}.
 */
public final class ItemIconButton extends Button {

    private static final int ICON = 16;

    private final ItemStack icon;

    private ItemIconButton(final int width, final Component message, final OnPress onPress, final ItemStack icon) {
        super(0, 0, width, DEFAULT_HEIGHT, message, onPress, DEFAULT_NARRATION);
        this.icon = icon;
    }

    /**
     * Creates a button, with an icon when the item id names a known item.
     *
     * @param width the width in GUI pixels
     * @param message the label
     * @param itemId an item id such as {@code minecraft:chest}, or null for a plain button
     * @param onPress what a press does
     * @return the button
     */
    public static Button create(final int width, final Component message, final @Nullable String itemId, final OnPress onPress) {
        final ItemStack stack = stack(itemId);
        if (stack.isEmpty()) {
            return Button.builder(message, onPress).width(width).build();
        }
        return new ItemIconButton(width, message, onPress, stack);
    }

    static ItemStack stack(final @Nullable String itemId) {
        final Identifier id = itemId == null ? null : Identifier.tryParse(itemId);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        // 26.3 binds item components only once a world's registries load, so on the title screen an
        // ItemStack cannot be built yet and the button falls back to plain text.
        return BuiltInRegistries.ITEM.get(id)
                .filter(holder -> holder.value() != Items.AIR && holder.areComponentsBound())
                .map(holder -> new ItemStack(holder))
                .orElse(ItemStack.EMPTY);
    }

    @Override
    protected void extractContents(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        extractDefaultSprite(graphics);
        graphics.item(icon, getX() + 2, getY() + (getHeight() - ICON) / 2);
        graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE)
                .acceptScrollingWithDefaultCenter(getMessage(), getX() + ICON + 4, getRight() - 2, getY(), getBottom());
    }
}
