package io.github.pix3lpirat3.mfbridge.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.protocol.core.MerchantOffer;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class PromptSurfacesTest {
    static WorldMenuEntry option(String id, @Nullable String item) {
        return new WorldMenuEntry(id, null, item == null ? null : new SlotItem(item), null, null, null);
    }

    static PromptRequest request(@Nullable List<WorldMenuEntry> options, @Nullable List<MerchantOffer> offers) {
        return new PromptRequest(PromptKind.CHOICE, "Pick", null, null, 30_000, null, "stonecutter", null, null, null, null,
                null, options, offers);
    }

    @Test
    void onlyTheNewSurfacesAreCheckedBySurface() {
        assertTrue(PromptSurfaces.handles("crafting"));
        assertFalse(PromptSurfaces.handles("hud"));
        assertFalse(PromptSurfaces.handles(null));
    }

    @Test
    void stonecutterAndEnchantingAnswerOptions() {
        PromptRequest r = request(List.of(option("slab", "minecraft:stone_slab"), option("stairs", null), option("wall", null)), null);
        assertEquals("stairs", PromptSurfaces.value(r, "stonecutter", "stairs"));
        assertThrows(IllegalArgumentException.class, () -> PromptSurfaces.value(r, "stonecutter", "pillar"));
        assertEquals(2L, PromptSurfaces.value(r, "enchanting", 2));
        assertEquals("wall", PromptSurfaces.value(r, "enchanting", "wall"));
        assertThrows(IllegalArgumentException.class, () -> PromptSurfaces.value(r, "enchanting", 3));
    }

    @Test
    void merchantAnswersAnOfferThatIsInStock() {
        SlotItem emerald = new SlotItem("minecraft:emerald");
        PromptRequest r = request(null, List.of(new MerchantOffer("bread", emerald, null, new SlotItem("minecraft:bread")),
                new MerchantOffer("sold-out", emerald, null, emerald, true, null, null)));
        assertEquals("bread", PromptSurfaces.value(r, "merchant", "bread"));
        assertThrows(IllegalArgumentException.class, () -> PromptSurfaces.value(r, "merchant", "sold-out"));
        assertThrows(IllegalArgumentException.class, () -> PromptSurfaces.value(r, "merchant", "cake"));
    }

    @Test
    void craftingAnswersNineCellsFromThePalette() {
        PromptRequest palette = request(List.of(option("plank", "minecraft:oak_planks"), option("stick", "minecraft:stick")), null);
        List<@Nullable String> cells = new ArrayList<>(Arrays.asList(new String[9]));
        cells.set(0, "minecraft:oak_planks");
        cells.set(4, "minecraft:stick");
        assertEquals(cells, PromptSurfaces.value(palette, "crafting", cells));
        cells.set(8, "minecraft:diamond");
        assertThrows(IllegalArgumentException.class, () -> PromptSurfaces.value(palette, "crafting", cells));
        assertEquals(cells, PromptSurfaces.value(request(null, null), "crafting", cells), "without a palette any id goes");
        assertThrows(IllegalArgumentException.class, () -> PromptSurfaces.value(palette, "crafting", List.of("x")));
    }
}
