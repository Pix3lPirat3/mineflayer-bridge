package io.github.pix3lpirat3.mfbridge.common.ui.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ItemComponentsTest {
    private static HolderLookup.Provider registries;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createReloadableLookup(VanillaRegistries.createWorldLookup());
        // Items get their default components once a world's registries load.
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(p -> p.apply());
    }

    @Test
    void appliesWhatThePluginSendsAndSkipsWhatItCannotRead() {
        final ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        ItemComponents.apply(sword, Map.of(
                "minecraft:damage", 100L,
                "minecraft:enchantments", Map.of("minecraft:sharpness", 5L, "minecraft:unbreaking", 3L),
                "minecraft:tooltip_display", Map.of("hide_tooltip", false, "hidden_components", List.of("minecraft:attribute_modifiers")),
                "minecraft:trim", Map.of("material", "minecraft:gold", "pattern", "minecraft:nope"),
                "minecraft:not_a_component", 1L,
                "!minecraft:attribute_modifiers", Map.of()), registries);
        assertEquals(100, sword.getDamageValue());
        assertTrue(sword.isBarVisible());
        assertTrue(sword.isEnchanted());
        assertEquals(2, sword.getEnchantments().size());
        assertTrue(sword.get(DataComponents.TOOLTIP_DISPLAY).hiddenComponents().contains(DataComponents.ATTRIBUTE_MODIFIERS));
        assertFalse(sword.has(DataComponents.TRIM));
        assertFalse(sword.has(DataComponents.ATTRIBUTE_MODIFIERS));

        final ItemStack potion = new ItemStack(Items.POTION);
        ItemComponents.apply(potion, Map.of("minecraft:potion_contents", Map.of("potion", "minecraft:swiftness",
                "custom_effects", List.of(Map.of("id", "minecraft:slowness", "amplifier", 1L, "duration", 100L, "ambient", false, "show_particles", true, "show_icon", true)))), registries);
        assertEquals(1, potion.get(DataComponents.POTION_CONTENTS).customEffects().size());

        final ItemStack bundle = new ItemStack(Items.BUNDLE);
        ItemComponents.apply(bundle, Map.of("minecraft:bundle_contents", List.of(Map.of("id", "minecraft:dirt", "count", 32L))), registries);
        assertFalse(bundle.get(DataComponents.BUNDLE_CONTENTS).isEmpty());

        final ItemStack armor = new ItemStack(Items.IRON_CHESTPLATE);
        ItemComponents.apply(armor, Map.of("minecraft:trim", Map.of("material", "minecraft:gold", "pattern", "minecraft:coast"),
                "minecraft:attribute_modifiers", List.of(Map.of("type", "minecraft:armor", "id", "minecraft:x", "amount", 2.5, "operation", "add_value", "slot", "chest"))), registries);
        assertTrue(armor.has(DataComponents.TRIM));
        assertEquals(1, armor.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().size());
    }
}
