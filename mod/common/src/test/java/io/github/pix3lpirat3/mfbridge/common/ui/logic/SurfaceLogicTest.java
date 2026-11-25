package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pix3lpirat3.mfbridge.protocol.core.FieldType;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormField;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.MerchantOffer;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotClick;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuEntry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SurfaceLogicTest {
    private static final Text EN = EnglishText.INSTANCE;

    static PromptRequest prompt(final PromptKind kind, final String surface, final List<String> choices, final Double min,
            final Double max) {
        return new PromptRequest(kind, "Question?", choices, null, 30_000, null, surface, null, null, min, max, null, null, null);
    }

    static PromptRequest prompt(final PromptKind kind, final String surface) {
        return prompt(kind, surface, null, null, null);
    }

    @Test
    void mouseAndKeyGesturesMapLikeVanillaInventories() {
        final int left = SlotGestures.BUTTON_LEFT;
        final int right = SlotGestures.BUTTON_RIGHT;
        assertEquals(new SlotGestures.Gesture(SlotClick.LEFT, null), SlotGestures.mouse(left, false, false, false));
        assertEquals(SlotClick.SHIFT_LEFT, SlotGestures.mouse(left, true, false, false).click());
        assertEquals(SlotClick.DOUBLE, SlotGestures.mouse(left, false, true, false).click());
        assertEquals(SlotClick.SHIFT_LEFT, SlotGestures.mouse(left, true, true, false).click(), "shift wins over a double click");
        assertEquals(SlotClick.RIGHT, SlotGestures.mouse(right, false, true, false).click());
        assertEquals(SlotClick.SHIFT_RIGHT, SlotGestures.mouse(right, true, false, false).click());
        assertEquals(SlotClick.MIDDLE, SlotGestures.mouse(SlotGestures.BUTTON_MIDDLE, false, false, false).click());
        assertEquals(SlotClick.MIDDLE, SlotGestures.mouse(-1, false, false, true).click(), "a rebound pick key is a middle click");
        assertNull(SlotGestures.mouse(-1, false, false, false));

        assertEquals(new SlotGestures.Gesture(SlotClick.DROP, null), SlotGestures.key(true, false, -1));
        assertEquals(new SlotGestures.Gesture(SlotClick.DROP_ALL, null), SlotGestures.key(true, true, 3));
        assertEquals(new SlotGestures.Gesture(SlotClick.NUMBER, 4), SlotGestures.key(false, false, 4));
        assertNull(SlotGestures.key(false, true, -1));
        assertNull(SlotGestures.key(false, false, 9));

        assertTrue(SlotGestures.closesMenu(SlotClick.LEFT));
        assertFalse(SlotGestures.closesMenu(SlotClick.DROP));
        assertFalse(SlotGestures.closesMenu(SlotClick.NUMBER));
    }

    @Test
    void layoutsMatchVanillaMenuGeometry() {
        final ContainerLayout chest = ContainerLayout.parse("chest:6");
        assertEquals(ContainerLayout.Background.GENERIC_54, chest.background());
        assertEquals(222, chest.height());
        assertEquals(6, chest.chestRows());
        assertEquals(54, chest.slots().size());
        assertEquals(new ContainerLayout.Slot(10, 26, 36), chest.slots().get(10));
        assertEquals(36, chest.playerSlots().size());
        assertEquals(new ContainerLayout.Slot(0, 8, 197), chest.playerSlots().get(27), "hotbar 58 px below the main inventory");
        assertEquals(128, chest.inventoryLabelY());
        assertEquals(10, chest.slotAt(26, 36).index());
        assertEquals(10, chest.slotAt(25, 35).index(), "one-pixel margin");
        assertNull(chest.slotAt(4, 4));
        assertEquals(0, chest.playerSlotAt(9, 199).index());

        assertEquals(ContainerLayout.parse("chest:3"), ContainerLayout.parse("chest:9"), "out of range falls back to chest:3");
        assertEquals(ContainerLayout.parse("chest:3"), ContainerLayout.parse("nonsense"));
        assertEquals(5, ContainerLayout.parse("hopper").slots().size());
        assertEquals(new ContainerLayout.Slot(4, 80, 35), ContainerLayout.parse("dispenser").slots().get(4));

        final ContainerLayout inv = ContainerLayout.parse("player-inventory");
        assertEquals(ContainerLayout.parse("player_inventory"), inv);
        assertEquals(46, inv.slots().size());
        assertTrue(inv.playerSlots().isEmpty());
        assertEquals(new ContainerLayout.Slot(36, 8, 142), inv.slots().get(36), "hotbar is window slot 36");
        assertEquals(new ContainerLayout.Slot(45, 77, 62), inv.slots().get(45), "offhand is window slot 45");

        final ContainerLayout grid = ContainerLayout.parse("grid:4x2");
        assertEquals(ContainerLayout.Background.PANEL, grid.background());
        assertEquals(8, grid.slots().size());
        assertEquals(176, grid.width());
        assertEquals(ContainerLayout.parse("chest:3"), ContainerLayout.parse("grid:0x2"));
        assertTrue(grid.inside(0, 0));
        assertFalse(grid.inside(-1, 0));
    }

    @Test
    void surfacesFallBackToTheHudWhenTheyDoNotFitTheKind() {
        assertEquals(Surfaces.Surface.ANVIL, Surfaces.forPrompt(prompt(PromptKind.TEXT, "anvil")));
        assertEquals(Surfaces.Surface.HUD, Surfaces.forPrompt(prompt(PromptKind.NUMBER, "anvil")));
        assertEquals(Surfaces.Surface.SIGN, Surfaces.forPrompt(prompt(PromptKind.LINES, "Sign")));
        assertEquals(Surfaces.Surface.BOOK, Surfaces.forPrompt(prompt(PromptKind.PAGES, "book")));
        assertEquals(Surfaces.Surface.HUD, Surfaces.forPrompt(prompt(PromptKind.LINES, "book")));
        assertEquals(Surfaces.Surface.CHAT, Surfaces.forPrompt(prompt(PromptKind.CONFIRM, "chat")));
        assertEquals(Surfaces.Surface.HUD, Surfaces.forPrompt(prompt(PromptKind.CHOICE, "chat")));
        assertEquals(Surfaces.Surface.DIALOG, Surfaces.forPrompt(prompt(PromptKind.CHOICE, "dialog")));
        assertEquals(Surfaces.Surface.WORLD, Surfaces.forPrompt(prompt(PromptKind.CONFIRM, "world")));
        assertEquals(Surfaces.Surface.WORLD, Surfaces.forPrompt(prompt(PromptKind.CHOICE, "world")));
        assertEquals(Surfaces.Surface.HUD, Surfaces.forPrompt(prompt(PromptKind.TEXT, "world")), "world menus only offer entries");
        assertFalse(Surfaces.opensScreen(Surfaces.Surface.WORLD));
        assertEquals("world", Surfaces.wireName(Surfaces.Surface.WORLD));
        assertEquals("hud", Surfaces.wireName(Surfaces.Surface.HUD));
        assertEquals(Surfaces.Surface.HUD, Surfaces.forPrompt(prompt(PromptKind.TEXT, "hologram")));
        assertTrue(Surfaces.opensScreen(Surfaces.Surface.SIGN));
        assertFalse(Surfaces.opensScreen(Surfaces.Surface.CHAT));
        assertEquals(Surfaces.Surface.DIALOG, Surfaces.forForm(new FormOpen("F", List.of(), null, "dialog", 1000, null)));
        assertEquals(Surfaces.Surface.HUD, Surfaces.forForm(new FormOpen("F", List.of(), null, "screen", 1000, null)));
    }

    @Test
    void dialogPayloadsDecodeIntoPromptAnswers() {
        assertEquals(true, DialogValues.promptAnswer(EN, prompt(PromptKind.CONFIRM, "dialog"), Map.of("button", "yes")));
        assertEquals(false, DialogValues.promptAnswer(EN, prompt(PromptKind.CONFIRM, "dialog"), Map.of("button", "no")));
        assertThrows(IllegalArgumentException.class,
                () -> DialogValues.promptAnswer(EN, prompt(PromptKind.CONFIRM, "dialog"), Map.of("button", "submit")));
        final PromptRequest choice = prompt(PromptKind.CHOICE, "dialog", List.of("pickaxe", "shovel"), null, null);
        assertEquals("shovel", DialogValues.promptAnswer(EN, choice, Map.of("button", "choice1")));
        assertThrows(IllegalArgumentException.class, () -> DialogValues.promptAnswer(EN, choice, Map.of("button", "choice2")));
        assertEquals("hut", DialogValues.promptAnswer(EN, prompt(PromptKind.TEXT, "dialog"), Map.of("button", "submit", "value", "hut")));
        assertEquals(12L, DialogValues.promptAnswer(EN, prompt(PromptKind.NUMBER, "dialog"), Map.of("value", " 12 ")));
        assertEquals(2.5, DialogValues.promptAnswer(EN, prompt(PromptKind.NUMBER, "dialog"), Map.of("value", 2.5f)));
        assertThrows(IllegalArgumentException.class, () -> DialogValues.promptAnswer(EN, prompt(PromptKind.NUMBER, "dialog"), Map.of("value", "x")));
        assertEquals(List.of("a", "", "b"), DialogValues.promptAnswer(EN, prompt(PromptKind.LINES, "dialog"), Map.of("value", "a\n\nb\n\n")));
        assertEquals(List.of("one page"), DialogValues.promptAnswer(EN, prompt(PromptKind.PAGES, "dialog"), Map.of("value", "one page")));
        assertTrue(DialogValues.slider(prompt(PromptKind.NUMBER, "dialog", null, 1.0, 9.0)));
        assertFalse(DialogValues.slider(prompt(PromptKind.NUMBER, "dialog", null, 1.0, null)));
    }

    @Test
    void dialogPayloadsDecodeIntoValidFormValues() {
        final List<FieldSpec> fields = List.of(
                FieldSpec.of(new FormField("crop", FieldType.SELECT, null, null, null, null, null, List.of("wheat", "carrots"), true)),
                FieldSpec.of(new FormField("radius", FieldType.SLIDER, "Radius", 16, 4.0, 64.0, 1.0, null, false)),
                FieldSpec.of(new FormField("replant", FieldType.TOGGLE, null, true, null, null, null, null, false)),
                FieldSpec.of(new FormField("extras", FieldType.MULTI_SELECT, null, null, null, null, null, List.of("bonemeal", "torches"),
                        false)),
                FieldSpec.of(new FormField("chest", FieldType.BLOCK_POS, null, null, null, null, null, null, false)),
                FieldSpec.of(new FormField("note", FieldType.TEXT, null, null, null, null, null, null, false)));
        final List<DialogValues.Input> inputs = DialogValues.formInputs(fields);
        assertEquals(List.of("f0", "f1", "f2", "f3_0", "f3_1", "f4", "f5"), inputs.stream().map(DialogValues.Input::key).toList());
        assertEquals(DialogValues.Control.OPTIONS, inputs.get(0).control());
        assertEquals(DialogValues.Control.SLIDER, inputs.get(1).control());
        assertEquals("torches", inputs.get(4).option());

        final Map<String, Object> payload = new HashMap<>();
        payload.put("button", "submit");
        payload.put("f0", "carrots");
        payload.put("f1", 20.0f);
        payload.put("f2", false);
        payload.put("f3_0", false);
        payload.put("f3_1", true);
        payload.put("f4", "1, 64 -2");
        payload.put("f5", "");
        final FormValues.Result result = FormValues.validate(EN, fields, DialogValues.formRaw(inputs, payload));
        assertTrue(result.ok(), result.error());
        assertEquals("carrots", result.values().get("crop"));
        assertEquals(false, result.values().get("replant"));
        assertEquals(List.of("torches"), result.values().get("extras"));
        assertFalse(result.values().containsKey("note"), "blank optional fields are absent");

        payload.put("f4", "1 two 3");
        assertFalse(FormValues.validate(EN, fields, DialogValues.formRaw(inputs, payload)).ok());
        assertEquals(List.of("", "", ""), DialogValues.coordinates("  "));
        assertEquals("16", DialogValues.initialText(fields.get(1)).replace(".0", ""));
    }

    @Test
    void guardDropsSurfacePacketsOnlyWhileABridgeScreenIsOpen() {
        final PacketGuard guard = new PacketGuard("mineflayer_bridge");
        for (final PacketGuard.Kind kind : List.of(PacketGuard.Kind.RENAME_ITEM, PacketGuard.Kind.SIGN_UPDATE, PacketGuard.Kind.EDIT_BOOK,
                PacketGuard.Kind.CONTAINER_CLICK, PacketGuard.Kind.CONTAINER_CLOSE, PacketGuard.Kind.CUSTOM_CLICK_ACTION,
                PacketGuard.Kind.SELECT_TRADE, PacketGuard.Kind.CONTAINER_BUTTON, PacketGuard.Kind.PLACE_RECIPE)) {
            assertTrue(guard.blocks(kind, true, null, "minecraft", 0), kind + " with a bridge screen");
            assertFalse(guard.blocks(kind, false, null, "minecraft", 0), kind + " without one reaches the server");
        }
        assertTrue(guard.blocks(PacketGuard.Kind.CUSTOM_CLICK_ACTION, false, null, "mineflayer_bridge", 0),
                "bridge click actions never reach a server");
        assertFalse(guard.blocks(PacketGuard.Kind.OTHER, true, null, null, 0));
    }

    @Test
    void guardDropsCapturedChatOnceAndForgetsReleasedLines() {
        final PacketGuard guard = new PacketGuard("mineflayer_bridge");
        assertFalse(guard.blocks(PacketGuard.Kind.CHAT, false, "@miner hi", null, 0));
        guard.captured("@miner hi", 100);
        assertFalse(guard.blocks(PacketGuard.Kind.CHAT, false, "hello", null, 150));
        assertTrue(guard.blocks(PacketGuard.Kind.CHAT, false, "@miner hi", null, 200));
        assertFalse(guard.blocks(PacketGuard.Kind.CHAT, false, "@miner hi", null, 250), "each capture blocks one packet");

        guard.captured("@miner hi", 300);
        guard.released("@miner hi");
        assertFalse(guard.blocks(PacketGuard.Kind.CHAT, false, "@miner hi", null, 350), "a line let through is not blocked");

        guard.captured("@miner bye", 400);
        assertFalse(guard.blocks(PacketGuard.Kind.CHAT, false, "@miner bye", null, 401 + PacketGuard.CAPTURE_TTL_MS),
                "captures expire");
    }

    static PromptRequest menuPrompt(final String surface, final List<WorldMenuEntry> options, final List<MerchantOffer> offers) {
        return new PromptRequest(PromptKind.CHOICE, "Pick", null, null, 30_000, null, surface, null, null, null, null, null,
                options, offers);
    }

    @Test
    void menuSurfacesNeedTheirDataAndCraftingAndMerchantNeverFallBack() {
        final List<WorldMenuEntry> options = List.of(new WorldMenuEntry("slab"));
        final MerchantOffer bread = new MerchantOffer("bread", new SlotItem("minecraft:emerald"), null, new SlotItem("minecraft:bread"));
        assertEquals(Surfaces.Surface.STONECUTTER, Surfaces.forPrompt(menuPrompt("stonecutter", options, List.of())));
        assertEquals(Surfaces.Surface.HUD, Surfaces.forPrompt(menuPrompt("stonecutter", List.of(), List.of())));
        assertEquals(Surfaces.Surface.ENCHANTING, Surfaces.forPrompt(menuPrompt("enchanting", options, List.of())));
        assertEquals(Surfaces.Surface.CRAFTING, Surfaces.forPrompt(menuPrompt("crafting", List.of(), List.of())));
        assertEquals(Surfaces.Surface.MERCHANT, Surfaces.forPrompt(menuPrompt("merchant", List.of(), List.of(bread))));
        assertNull(Surfaces.unavailable(menuPrompt("merchant", List.of(), List.of(bread))));
        assertTrue(Surfaces.unavailable(menuPrompt("merchant", List.of(), List.of())).contains("offer"), "rejected with unsupported");
        assertTrue(Surfaces.rejectsFallback(Surfaces.Surface.CRAFTING));
        assertFalse(Surfaces.rejectsFallback(Surfaces.Surface.STONECUTTER));
        assertTrue(Surfaces.opensScreen(Surfaces.Surface.MERCHANT));
        assertTrue(Surfaces.menuStyled(Surfaces.Surface.ENCHANTING));
        assertFalse(Surfaces.menuStyled(Surfaces.Surface.ANVIL));
        assertEquals("crafting", Surfaces.wireName(Surfaces.Surface.CRAFTING));
    }

    @Test
    void furnaceBrewingAndLecternLayoutsMatchVanilla() {
        final ContainerLayout furnace = ContainerLayout.parse("furnace");
        assertEquals(ContainerLayout.Background.FURNACE, furnace.background());
        assertEquals(new ContainerLayout.Slot(2, 116, 35), furnace.slots().get(2));
        assertEquals(36, furnace.playerSlots().size());
        final ContainerLayout brewing = ContainerLayout.parse("brewing");
        assertEquals(5, brewing.slots().size());
        assertEquals(new ContainerLayout.Slot(4, 17, 17), brewing.slots().get(4));
        final ContainerLayout lectern = ContainerLayout.parse("lectern");
        assertEquals(ContainerLayout.Background.LECTERN, lectern.background());
        assertTrue(lectern.slots().isEmpty());
        assertEquals(-1, lectern.inventoryLabelY());
    }
}
