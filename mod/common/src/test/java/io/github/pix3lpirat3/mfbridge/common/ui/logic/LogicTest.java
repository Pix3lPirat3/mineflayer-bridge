package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.protocol.core.FieldType;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormField;
import io.github.pix3lpirat3.mfbridge.protocol.core.KeyActivation;
import io.github.pix3lpirat3.mfbridge.protocol.core.TargetSpec;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class LogicTest {
    private static final Text EN = EnglishText.INSTANCE;

    @Test
    void fuzzyPrefersPrefixesAndWordStarts() {
        assertEquals(-1, FuzzyMatch.score("xyz", "mine"));
        assertTrue(FuzzyMatch.score("mine", "mine") > FuzzyMatch.score("mine", "mine-everything"));
        assertTrue(FuzzyMatch.score("gh", "go-home") > FuzzyMatch.score("gh", "laugh"));
        assertTrue(FuzzyMatch.score("dep", "deposit") > FuzzyMatch.score("dep", "auto-deposit"));
        assertEquals(FuzzyMatch.EMPTY_QUERY_SCORE, FuzzyMatch.score("  ", "anything"));
        assertTrue(FuzzyMatch.score("MINE", "mine") > 0);
    }

    @Test
    void targetSpecsFilterByIdAndTag() {
        final Set<String> chestTags = Set.of("c:chests", "minecraft:mineable/axe");
        final TargetSpec chests = new TargetSpec("block", null, List.of("#c:chests"));
        assertTrue(TargetMatch.matches(chests, "block", "minecraft:chest", chestTags::contains));
        assertFalse(TargetMatch.matches(chests, "block", "minecraft:stone", Set.<String>of()::contains));
        assertFalse(TargetMatch.matches(chests, "entity", "minecraft:chest", chestTags::contains));
        final TargetSpec cows = new TargetSpec("entity", List.of("cow"), null);
        assertTrue(TargetMatch.matches(cows, "entity", "minecraft:cow", t -> false));
        assertFalse(TargetMatch.matches(cows, "entity", "minecraft:pig", t -> false));
        final TargetSpec both = new TargetSpec("block", List.of("minecraft:chest"), List.of("c:chests"));
        assertFalse(TargetMatch.matches(both, "block", "minecraft:chest", t -> false));
        assertTrue(TargetMatch.matches(new TargetSpec("air", null, null), "air", null, t -> false));
    }

    @Test
    void regionMath() {
        final Vec3i a = new Vec3i(0, 0, 0);
        final Vec3i b = new Vec3i(-2, 3, 4);
        assertEquals(3L * 4 * 5, PickGeometry.volume(a, b));
        assertEquals(new Vec3i(-2, 0, 0), PickGeometry.min(a, b));
        assertEquals(new Vec3i(0, 3, 4), PickGeometry.max(a, b));
        assertEquals(new Vec3i(0, -1, 0), PickGeometry.dominantAxis(0.1, -0.9, 0.3));
        assertEquals(new Vec3i(1, 0, 0), PickGeometry.dominantAxis(0.8, 0.1, -0.5));
        assertEquals(new Vec3i(0, 0, -1), PickGeometry.dominantAxis(0.2, 0.1, -0.5));
        assertEquals(new Vec3i(0, -3, 0), PickGeometry.offset(a, new Vec3i(0, -1, 0), 3));
        assertEquals(1, PickGeometry.minY(List.of(new Vec3i(0, 5, 0), new Vec3i(1, 1, 1))));
        assertEquals(5, PickGeometry.maxY(List.of(new Vec3i(0, 5, 0), new Vec3i(1, 1, 1))));
    }

    @Test
    void directions() {
        assertEquals("south", PickGeometry.cardinal(10f));
        assertEquals("west", PickGeometry.cardinal(95f));
        assertEquals("north", PickGeometry.cardinal(-170f));
        assertEquals("east", PickGeometry.cardinal(-80f));
        assertEquals("east", PickGeometry.cardinal(280f));
        assertEquals(45f, PickGeometry.snap(52f, 15f));
        assertEquals(-170f, PickGeometry.wrapDegrees(190f));
        assertEquals(10f, PickGeometry.wrapDegrees(370f));
    }

    @Test
    void scrollAccumulatesAndResetsOnFlip() {
        final ScrollAccumulator scroll = new ScrollAccumulator();
        assertEquals(0, scroll.add(0.4));
        assertEquals(0, scroll.add(0.4));
        assertEquals(1, scroll.add(0.4));
        assertEquals(0, scroll.add(-0.6));
        assertEquals(-1, scroll.add(-0.6));
        assertEquals(3, scroll.add(3.2));
    }

    private static KeybindIndex.Bind bind(final String key, final String context, final int priority, final int order, final String... keys) {
        return new KeybindIndex.Bind(key, List.of(keys), context, KeyActivation.PRESS, priority, true, order);
    }

    @Test
    void keybindsResolveByPriorityChordAndContext() {
        final KeybindIndex.Bind g = bind("a/x/~key/g", "ingame", 0, 0, "key.keyboard.g");
        final KeybindIndex.Bind ctrlG = bind("a/x/~key/cg", "ingame", 0, 1, "key.keyboard.left.control", "key.keyboard.g");
        final KeybindIndex.Bind gui = bind("b/x/~key/g", "gui", 5, 2, "key.keyboard.g");
        final KeybindIndex index = KeybindIndex.build(List.of(g, ctrlG, gui));
        final Set<String> none = Set.of();
        final Set<String> ctrl = Set.of("key.keyboard.left.control");
        assertEquals(g, index.match("key.keyboard.g", none::contains, b -> b.context().equals("ingame")));
        assertEquals(ctrlG, index.match("key.keyboard.g", ctrl::contains, b -> b.context().equals("ingame")));
        assertEquals(gui, index.match("key.keyboard.g", none::contains, c -> true));
        assertNull(index.match("key.keyboard.h", none::contains, c -> true));
        assertTrue(index.uses("key.keyboard.left.control"));
        assertTrue(index.conflicts().isEmpty());
    }

    @Test
    void keybindConflictsAreReported() {
        final KeybindIndex.Bind low = bind("a/x/~key/k", "ingame", 0, 0, "key.keyboard.k");
        final KeybindIndex.Bind high = bind("b/x/~key/k", "any", 3, 1, "key.keyboard.k");
        final KeybindIndex.Bind tie = bind("c/x/~key/k", "ingame", 0, 2, "key.keyboard.k");
        final KeybindIndex.Bind other = bind("d/x/~key/k", "tool:mark", 9, 3, "key.keyboard.k");
        final KeybindIndex index = KeybindIndex.build(List.of(low, high, tie, other));
        assertEquals(other, index.match("key.keyboard.k", s -> false, c -> true));
        assertEquals(high, index.match("key.keyboard.k", s -> false, b -> b.context().equals("any")));
        final List<KeybindIndex.Conflict> conflicts = new ArrayList<>(index.conflicts());
        assertTrue(conflicts.contains(new KeybindIndex.Conflict(tie, low, true)));
        assertTrue(conflicts.contains(new KeybindIndex.Conflict(high, low, false)));
        assertFalse(KeybindIndex.overlap("ingame", "gui"));
        assertFalse(KeybindIndex.overlap("tool:a", "tool:b"));
        assertTrue(KeybindIndex.overlap("tool:a", "ingame"));
    }

    @Test
    void formValidation() {
        final List<FieldSpec> fields = List.of(
                FieldSpec.of(new FormField("crop", FieldType.SELECT, null, null, null, null, null, List.of("wheat", "carrots"), true)),
                FieldSpec.of(new FormField("radius", FieldType.SLIDER, "Radius", 16, 4d, 64d, 1d, null, false)),
                FieldSpec.of(new FormField("count", FieldType.NUMBER, null, null, 1d, 10d, null, null, false)),
                FieldSpec.of(new FormField("at", FieldType.BLOCK_POS, null, null, null, null, null, null, true)),
                FieldSpec.of(new FormField("tags", FieldType.MULTI_SELECT, null, null, null, null, null, List.of("a", "b"), false)));
        assertEquals(FieldSpec.Kind.SLIDER, fields.get(1).kind());
        assertEquals(16d, FormValues.initial(fields.get(1)));
        assertEquals("wheat", FormValues.initial(fields.get(0)));
        final FormValues.Result ok = FormValues.validate(EN, fields, Map.of(
                "crop", "carrots", "radius", 20d, "count", "3", "at", List.of("1", "2", "-3"), "tags", List.of("b")));
        assertTrue(ok.ok(), String.valueOf(ok.error()));
        assertEquals(Map.of("crop", "carrots", "radius", 20d, "count", 3L, "at", new Vec3i(1, 2, -3), "tags", List.of("b")), ok.values());
        final FormValues.Result high = FormValues.validate(EN, fields, Map.of("crop", "wheat", "radius", 20d, "count", "11",
                "at", List.of("1", "2", "3")));
        assertEquals("count", high.fieldId());
        assertEquals("'count' must be at most 10", high.error());
        final FormValues.Result missing = FormValues.validate(EN, fields, Map.of("crop", "wheat", "radius", 20d,
                "at", List.of("", "", "")));
        assertEquals("'at' is required", missing.error());
    }

    @Test
    void formNumbersAreRoundedForDisplay() {
        assertEquals("20.666", FormValues.number(20.665790257d));
        assertEquals("-0.997", FormValues.number(-0.997371262d));
        assertEquals("1.5", FormValues.number(1.5d));
        assertEquals("-58", FormValues.number(-58d));
    }
}
