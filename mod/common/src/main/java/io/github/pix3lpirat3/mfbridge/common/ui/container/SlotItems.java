package io.github.pix3lpirat3.mfbridge.common.ui.container;

import io.github.pix3lpirat3.mfbridge.core.container.SlotStorage;
import io.github.pix3lpirat3.mfbridge.protocol.core.ChatSpan;
import io.github.pix3lpirat3.mfbridge.protocol.core.LoreLine;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.TooltipDisplay;
import org.jspecify.annotations.Nullable;

/**
 * Builds client-only display stacks for bot slot items: name and
 * lore (plain or as styled spans), glint, custom model, player head skin and colour. The stacks are
 * never put into a real menu or sent anywhere. Counts, bars and animation frames are drawn by {@link
 * SlotVisual}.
 */
public final class SlotItems {

    private static final int MAX_COUNT = 99;

    private SlotItems() {
    }

    /**
     * Returns the registry id of a stack's item.
     *
     * @param stack the stack
     * @return the id, such as {@code minecraft:stone}
     */
    public static String id(final ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /**
     * Looks up an item by registry id.
     *
     * @param id the id, such as {@code minecraft:stone}
     * @return the item, or null when the id is malformed, unknown or air
     */
    public static @Nullable Item item(final String id) {
        final Identifier parsed = Identifier.tryParse(id);
        final Item type = parsed == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(parsed);
        return type == Items.AIR ? null : type;
    }

    /**
     * Describes a real stack of the player's inventory as a slot item, for a virtual copy in a
     * container view. Only the item id and count are carried; components such as names and
     * enchantments are not, since bots read storage by id.
     *
     * @param stack a non-empty stack
     * @return the slot item
     */
    public static SlotItem of(final ItemStack stack) {
        return SlotStorage.withCount(new SlotItem(id(stack)), stack.getCount());
    }

    /**
     * Converts a slot item. Unknown item ids show as a barrier so the slot stays visible; {@code
     * components} are not applied. A {@code head} turns the item into a player head with that skin.
     *
     * @param item the bot's item
     * @return a new stack, with the count clamped to 1 to 99
     */
    public static ItemStack stack(final SlotItem item) {
        final Item known = item(item.id());
        final String head = item.head();
        final String texture = item.headTexture();
        final Item type = head != null || texture != null ? Items.PLAYER_HEAD : known == null ? Items.BARRIER : known;
        final ItemStack stack = new ItemStack(type, Math.clamp(Integer.toUnsignedLong(item.count()), 1, MAX_COUNT));
        final List<ChatSpan> nameSpans = item.nameSpans();
        if (nameSpans != null && !nameSpans.isEmpty()) {
            stack.set(DataComponents.CUSTOM_NAME, spans(nameSpans));
        } else if (item.name() != null) {
            // Server GUI plugins show names upright; vanilla italicises custom names.
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(item.name()).withStyle(s -> s.withItalic(false)));
        }
        final List<Component> lines = lore(item);
        if (!lines.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }
        if (item.glint()) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);
        }
        final String model = item.customModel();
        if (model != null) {
            final Identifier modelId = Identifier.tryParse(model);
            if (modelId != null) {
                stack.set(DataComponents.ITEM_MODEL, modelId);
            }
        }
        if (texture != null) {
            stack.set(DataComponents.PROFILE, textured(texture));
        } else if (head != null) {
            stack.set(DataComponents.PROFILE, profile(head));
        }
        final Integer color = item.color();
        if (color != null) {
            final int rgb = color & 0xFFFFFF;
            if (stack.has(DataComponents.POTION_CONTENTS)) {
                stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), Optional.of(rgb), List.of(), Optional.empty()));
            } else {
                stack.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb));
            }
        }
        if (item.hideTooltip()) {
            stack.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, TooltipDisplay.DEFAULT.hiddenComponents()));
        }
        return stack;
    }

    private static List<Component> lore(final SlotItem item) {
        final List<LoreLine> styled = item.loreLines();
        if (styled != null && !styled.isEmpty()) {
            final List<Component> out = new ArrayList<>(styled.size());
            for (final LoreLine line : styled) {
                out.add(spans(line.spans()));
            }
            return out;
        }
        final List<String> lore = item.lore();
        if (lore == null || lore.isEmpty()) {
            return List.of();
        }
        final List<Component> out = new ArrayList<>(lore.size());
        for (final String line : lore) {
            out.add(Component.literal(line));
        }
        return out;
    }

    /**
     * Converts styled spans to one component, upright unless a span asks for italics.
     *
     * @param spans the spans
     * @return the component
     */
    public static MutableComponent spans(final List<ChatSpan> spans) {
        final MutableComponent out = Component.empty().withStyle(s -> s.withItalic(false));
        for (final ChatSpan span : spans) {
            Style style = Style.EMPTY.withBold(span.bold()).withItalic(span.italic()).withUnderlined(span.underlined())
                    .withStrikethrough(span.strikethrough()).withObfuscated(span.obfuscated());
            if (span.color() != null) {
                style = style.withColor(span.color() & 0xFFFFFF);
            }
            out.append(Component.literal(span.text()).withStyle(style));
        }
        return out;
    }

    /**
     * Returns a profile that carries a skin texture as server menus send it, the base64 value of a
     * {@code textures} property. The id comes from the texture, so equal skins share one cache entry.
     *
     * @param texture the base64 textures value
     * @return the profile
     */
    public static ResolvableProfile textured(final String texture) {
        final UUID id = UUID.nameUUIDFromBytes(texture.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final com.mojang.authlib.properties.PropertyMap properties = new com.mojang.authlib.properties.PropertyMap(
                com.google.common.collect.ImmutableMultimap.of("textures", new com.mojang.authlib.properties.Property("textures", texture)));
        return ResolvableProfile.createResolved(new com.mojang.authlib.GameProfile(id, "head", properties));
    }

    /**
     * Returns a profile for a head skin: a UUID, a player name, or a bot identity
     * {@code name@server}, whose name part is used. A player on the current server gets their
     * profile from the tab list, skin included; anyone else an unresolved one that loads in the
     * background.
     *
     * @param head the skin owner
     * @return the profile
     */
    public static ResolvableProfile profile(final String head) {
        // Someone on the server: their profile, skin included, is already in the tab list, so the
        // head shows at once with no lookup at Mojang.
        final net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.client.multiplayer.ClientPacketListener connection = minecraft == null ? null : minecraft.getConnection();
        if (connection != null) {
            final String name = head.indexOf('@') > 0 ? head.substring(0, head.indexOf('@')) : head;
            net.minecraft.client.multiplayer.PlayerInfo info;
            try {
                info = connection.getPlayerInfo(UUID.fromString(head));
            } catch (final IllegalArgumentException notUuid) {
                info = connection.getPlayerInfoIgnoreCase(name);
            }
            if (info != null) {
                return ResolvableProfile.createResolved(info.getProfile());
            }
        }
        try {
            return ResolvableProfile.createUnresolved(UUID.fromString(head));
        } catch (final IllegalArgumentException e) {
            final int at = head.indexOf('@');
            return ResolvableProfile.createUnresolved(at > 0 ? head.substring(0, at) : head);
        }
    }
}
