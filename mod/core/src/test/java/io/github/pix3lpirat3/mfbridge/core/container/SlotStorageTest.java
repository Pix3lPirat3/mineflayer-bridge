package io.github.pix3lpirat3.mfbridge.core.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.protocol.core.SlotEntry;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.TargetSpec;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SlotStorageTest {
    static final String STONE = "minecraft:stone";
    static final String PEARL = "minecraft:ender_pearl";
    static final String DIRT = "minecraft:dirt";

    static final SlotStorage.Rules RULES = new SlotStorage.Rules() {
        @Override
        public int maxStackSize(SlotItem item) {
            return item.id().equals(PEARL) ? 16 : 64;
        }

        @Override
        public boolean accepts(TargetSpec spec, SlotItem item) {
            return spec.ids() == null || spec.ids().contains(item.id());
        }
    };

    static SlotItem item(String id, int count) {
        return SlotStorage.withCount(new SlotItem(id), count);
    }

    static SlotItem air() {
        return item("minecraft:air", 0);
    }

    static SlotEntry entry(int slot, SlotItem item, String policy) {
        return new SlotEntry(slot, item, policy, null, null);
    }

    static SlotStorage storage(SlotEntry... entries) {
        SlotStorage s = new SlotStorage(RULES);
        s.update(List.of(entries));
        return s;
    }

    static int count(SlotStorage s, int slot) {
        SlotItem i = s.item(slot);
        return i == null ? 0 : i.count();
    }

    static int cursor(SlotStorage s) {
        SlotItem c = s.cursor();
        return c == null ? 0 : c.count();
    }

    @Test
    void sourceSlotsCopyTheirTemplateWithoutRunningOut() {
        SlotStorage s = storage(entry(0, item(PEARL, 1), "source"), entry(1, air(), "place"), entry(2, air(), "place"));
        assertFalse(s.isStorage(0), "a source is not storage");
        assertEquals(SlotStorage.Policy.SOURCE, s.policy(0));
        assertEquals(List.of(1, 2), s.entries().stream().map(SlotEntry::slot).toList(), "sources are never reported");

        SlotStorage.Move m = s.clickSource(0, false);
        assertNotNull(m);
        assertEquals(16, cursor(s), "left takes a full stack");
        assertEquals(SlotStorage.COPY, s.cursorOrigin());
        assertEquals(item(PEARL, 16), m.moved());
        assertEquals(0, m.fromSlot());
        assertTrue(m.changed().isEmpty(), "no storage slot changed");
        assertEquals(item(PEARL, 1), s.template(0), "the template is never decremented");
        assertNull(s.clickSource(0, true), "a full cursor takes no more");

        s.click(1, true);
        assertEquals(1, count(s, 1));
        assertEquals(15, cursor(s));
        assertNotNull(s.clickSource(0, true));
        assertEquals(16, cursor(s), "right tops up by one");
        s.click(2, false);
        assertEquals(16, count(s, 2), "copies place like real items");
        assertNull(s.cursor());

        assertNotNull(s.clickSource(0, true));
        assertEquals(1, cursor(s), "right takes one");
        assertNull(s.click(0, false), "nothing is placed into a source");
        SlotStorage.Move back = s.returnCursor();
        assertNotNull(back);
        assertTrue(back.changed().isEmpty(), "a copy left on the cursor is discarded, not put into storage");
        assertNull(s.cursor());
        assertEquals(1, count(s, 1));
        assertEquals(16, count(s, 2));

        SlotStorage.Move shift = s.quickCopy(s.template(0), 16, 0);
        assertNotNull(shift);
        assertEquals(List.of(1), shift.changed(), "shift-click tops up the matching stack");
        assertEquals(16, count(s, 1));
        assertNull(s.quickCopy(s.template(0), 16, 0), "nothing fits any more");
    }

    @Test
    void playerInventoryIsACopySourceOnlyWhileTheViewHasStorage() {
        SlotItem real = item(STONE, 20);
        SlotStorage buttons = storage(entry(0, item(DIRT, 1), "button"), entry(1, item(PEARL, 1), "source"));
        assertFalse(buttons.hasStorage());
        assertNull(buttons.clickInventory(real, false), "without storage the inventory is only drawn");

        SlotStorage s = storage(entry(0, air(), "place"), entry(1, item(DIRT, 4), "take"),
                new SlotEntry(2, air(), "ask", null, 8));
        assertTrue(s.hasStorage());
        SlotStorage.Move m = s.clickInventory(real, true);
        assertNotNull(m);
        assertEquals(10, cursor(s), "right copies the larger half");
        assertEquals(SlotStorage.COPY, s.cursorOrigin());
        assertEquals(20, real.count(), "the real stack is untouched");
        assertEquals(SlotStorage.CURSOR, m.fromSlot());
        assertNotNull(s.clickInventory(real, false));
        assertEquals(30, cursor(s), "left tops a matching copy up by the whole stack");

        SlotStorage.Move placed = s.click(2, false);
        assertNotNull(placed);
        assertEquals(List.of(2), placed.askSlots(), "a copy placed into an ask slot is confirmed by the bot");
        assertEquals(8, count(s, 2), "maxStack caps the copy");
        assertEquals(22, cursor(s));
        assertNotNull(s.clickInventory(null, false), "an empty inventory slot drops the copy");
        assertNull(s.cursor());
        assertEquals(List.of(0, 1, 2), s.entries().stream().map(SlotEntry::slot).toList());

        s.click(1, false);
        assertEquals(4, cursor(s));
        assertEquals(1, s.cursorOrigin(), "taken from storage");
        assertNull(s.clickInventory(real, false), "a real storage stack on the cursor is never replaced by a copy");
        assertNull(s.clickInventory(null, false), "nor dropped");
        s.returnCursor();
        assertEquals(4, count(s, 1), "it goes back on close");

        SlotStorage.Move shift = s.quickCopy(real, real.count(), SlotStorage.CURSOR);
        assertNotNull(shift);
        assertEquals(20, count(s, 0), "shift-click copies the stack into storage that accepts it");
        assertEquals(List.of(0), shift.changed(), "the take slot accepts nothing and the ask slot is at its cap");
    }

    @Test
    void leftAndRightClicksPickUpPlaceAndSwapLikeVanilla() {
        SlotStorage s = storage(entry(0, item(STONE, 10), "free"), entry(1, air(), "free"), entry(2, item(DIRT, 3), "free"));
        SlotStorage.Move m = s.click(0, true);
        assertNotNull(m);
        assertEquals(5, cursor(s), "right click takes the larger half");
        assertEquals(5, count(s, 0));
        assertEquals(0, s.cursorOrigin());
        assertEquals(List.of(0), m.changed());
        assertTrue(m.cursorChanged());
        assertEquals(item(STONE, 5), m.moved());
        assertEquals(0, m.fromSlot());

        s.click(1, true);
        assertEquals(1, count(s, 1), "right click places one");
        assertEquals(4, cursor(s));
        s.click(1, false);
        assertEquals(5, count(s, 1), "left click places all");
        assertNull(s.cursor());
        assertEquals(SlotStorage.CURSOR, s.cursorOrigin());

        s.click(2, false);
        assertEquals(DIRT, s.cursor().id());
        SlotStorage.Move swap = s.click(0, false);
        assertNotNull(swap);
        assertEquals(DIRT, s.item(0).id(), "different items swap");
        assertEquals(item(STONE, 5), s.cursor());
        assertEquals(0, s.cursorOrigin());
        assertNull(s.click(3, false), "undeclared slots are buttons");
    }

    @Test
    void policiesFiltersAndCapsAreEnforced() {
        TargetSpec onlyPearls = new TargetSpec("item", List.of(PEARL), null);
        SlotStorage s = storage(entry(0, item(STONE, 64), "take"), entry(1, air(), "place"), entry(2, item(DIRT, 1), "locked"),
                new SlotEntry(3, air(), "place", onlyPearls, null), new SlotEntry(4, air(), "free", null, 8),
                entry(5, item(PEARL, 16), "take"), entry(6, item(STONE, 1), "button"));
        s.click(0, false);
        assertEquals(64, cursor(s));
        assertNull(s.click(0, false), "nothing can be placed into a take slot that is now empty");
        assertNull(s.click(2, false), "locked slots never change");
        assertNull(s.click(6, false), "button slots are not storage");
        assertNull(s.click(3, false), "the filter refuses stone");
        s.click(4, false);
        assertEquals(8, count(s, 4), "maxStack caps the slot");
        assertEquals(56, cursor(s));
        s.click(1, false);
        assertEquals(56, count(s, 1));
        assertNull(s.click(1, false), "place slots cannot be taken from");

        s.click(5, false);
        assertEquals(16, cursor(s));
        s.click(3, false);
        assertEquals(16, count(s, 3), "the filter accepts pearls");
    }

    @Test
    void shiftClickMovesIntoSlotsOfAnotherPolicyFirst() {
        SlotStorage s = storage(entry(0, item(STONE, 40), "take"), entry(1, item(STONE, 30), "take"),
                entry(2, item(STONE, 50), "place"), entry(3, air(), "place"), entry(4, air(), "locked"));
        SlotStorage.Move m = s.quickMove(0);
        assertNotNull(m);
        assertEquals(64, count(s, 2), "matching stacks fill first");
        assertEquals(26, count(s, 3));
        assertEquals(0, count(s, 0));
        assertEquals(List.of(0, 2, 3), m.changed());
        assertEquals(item(STONE, 40), m.moved());
        assertNull(s.quickMove(2), "place slots cannot be shift-clicked out");
        assertNull(s.quickMove(4));
    }

    @Test
    void dragSplitsEvenlyAndSkipsSlotsThatRefuse() {
        SlotStorage s = storage(entry(0, item(STONE, 10), "free"), entry(1, air(), "free"), entry(2, air(), "free"),
                entry(3, air(), "locked"), entry(4, item(DIRT, 1), "free"), entry(5, item(STONE, 62), "free"));
        s.click(0, false);
        SlotStorage.Move m = s.drag(List.of(1, 2, 3, 4, 2, 0), false);
        assertNotNull(m);
        assertEquals(3, count(s, 1), "ten split over three slots");
        assertEquals(3, count(s, 2));
        assertEquals(3, count(s, 0));
        assertEquals(0, count(s, 3));
        assertEquals(1, count(s, 4), "another item blocks the slot");
        assertEquals(1, cursor(s), "the remainder stays on the cursor");

        s.click(1, false);
        assertNull(s.cursor(), "a click on the same item puts the remainder down");
        s.click(1, false);
        assertEquals(4, cursor(s));
        s.drag(List.of(1, 2, 5), true);
        assertEquals(1, count(s, 1), "right drag places one per slot");
        assertEquals(4, count(s, 2));
        assertEquals(63, count(s, 5));
        assertEquals(1, cursor(s));
        s.drag(List.of(5), false);
        assertEquals(64, count(s, 5), "the last item tops the stack up");
        assertNull(s.cursor());
        assertNull(s.drag(List.of(1), false), "no cursor, no drag");
    }

    @Test
    void dragUsesNoMoreSlotsThanItems() {
        SlotStorage s = storage(entry(0, item(STONE, 2), "free"), entry(1, air(), "free"), entry(2, air(), "free"),
                entry(3, air(), "free"));
        s.click(0, false);
        s.drag(List.of(1, 2, 3), false);
        assertEquals(1, count(s, 1));
        assertEquals(1, count(s, 2));
        assertEquals(0, count(s, 3));
    }

    @Test
    void doubleClickCollectsPartialStacksFirst() {
        SlotStorage s = storage(entry(0, item(STONE, 10), "free"), entry(1, item(STONE, 64), "take"), entry(2, item(STONE, 20), "free"),
                entry(3, item(STONE, 30), "place"), entry(4, item(DIRT, 5), "free"));
        s.click(0, false);
        SlotStorage.Move m = s.collect();
        assertNotNull(m);
        assertEquals(64, cursor(s));
        assertEquals(0, count(s, 2), "the partial stack went first");
        assertEquals(30, count(s, 1), "then the full stack topped the cursor up");
        assertEquals(30, count(s, 3), "place slots are never taken from");
        assertEquals(5, count(s, 4));
        assertEquals(item(STONE, 54), m.moved());
        assertEquals(2, m.fromSlot());
        assertNull(s.collect(), "a full cursor gathers nothing");
    }

    @Test
    void numberKeysSwapWithinStorage() {
        SlotStorage s = storage(entry(0, item(STONE, 5), "free"), entry(1, air(), "free"), entry(2, item(DIRT, 2), "take"),
                entry(3, item(PEARL, 4), "place"));
        s.swap(0, 1);
        assertEquals(0, count(s, 0));
        assertEquals(5, count(s, 1));
        assertNull(s.swap(2, 1), "a take slot cannot receive the other stack");
        s.swap(1, 0);
        assertEquals(5, count(s, 0));
        assertNull(s.swap(3, 1), "place slots cannot give their item away");
        assertNull(s.swap(1, 1));
    }

    @Test
    void askMovesReportAskSlotsAndRollBack() {
        SlotStorage s = storage(entry(0, item(STONE, 8), "ask"), entry(1, air(), "free"));
        SlotStorage.Move take = s.click(0, false);
        assertNotNull(take);
        assertEquals(List.of(0), take.askSlots());
        SlotStorage.Move put = s.click(1, false);
        assertNotNull(put);
        assertTrue(put.askSlots().isEmpty());
        assertEquals(SlotStorage.CURSOR, put.fromSlot());

        assertEquals(List.of(1), s.rollback(put.undo()));
        assertEquals(8, cursor(s));
        assertEquals(0, count(s, 1));
        assertEquals(List.of(0), s.rollback(take.undo()));
        assertEquals(8, count(s, 0));
        assertNull(s.cursor());
        assertTrue(s.rollback(take.undo()).isEmpty(), "an undo works once");
    }

    @Test
    void botUpdatesKeepSlotsThePlayerChanged() {
        SlotStorage s = storage(entry(0, item(STONE, 8), "free"), entry(1, air(), "free"), entry(2, item(DIRT, 1), "free"));
        s.click(0, false);
        s.click(1, false);
        s.update(List.of(entry(0, item(PEARL, 1), "free"), entry(1, item(PEARL, 2), "free"), entry(2, item(DIRT, 9), "free"),
                entry(3, item(PEARL, 3), "button")));
        assertEquals(0, count(s, 0), "changed by the player");
        assertEquals(STONE, s.item(1).id(), "changed by the player");
        assertEquals(9, count(s, 2), "untouched slots follow the bot");
        assertNull(s.item(3), "button slots hold no storage");
        s.update(List.of(entry(1, item(PEARL, 2), "button")));
        assertFalse(s.isStorage(1));
        assertNull(s.item(1), "a slot that stops being storage drops its contents");
    }

    @Test
    void closingReturnsTheCursorToItsOrigin() {
        SlotStorage s = storage(entry(0, item(STONE, 40), "take"), entry(1, air(), "free"), entry(2, item(STONE, 30), "take"));
        s.click(0, false);
        s.click(2, false);
        assertEquals(64, cursor(s));
        assertEquals(6, count(s, 2));
        SlotStorage.Move m = s.returnCursor();
        assertNotNull(m);
        assertNull(s.cursor());
        assertEquals(70, count(s, 0) + count(s, 1) + count(s, 2), "nothing is lost");
        assertEquals(64, count(s, 0), "the origin takes back what it can hold first");
        assertNull(s.returnCursor());
    }

    @Test
    void entriesListStorageSlotsWithAirForEmptyOnes() {
        SlotStorage s = storage(entry(4, item(STONE, 2), "take"), entry(1, air(), "place"), entry(0, item(DIRT, 1), "button"));
        List<SlotEntry> out = s.entries();
        List<Integer> slots = new ArrayList<>();
        for (SlotEntry e : out) {
            slots.add(e.slot());
        }
        assertEquals(List.of(1, 4), slots);
        assertTrue(SlotStorage.empty(out.getFirst().item()));
        assertEquals("place", out.getFirst().policy());
        assertEquals(item(STONE, 2), out.get(1).item());
        assertEquals(SlotStorage.Policy.BUTTON, SlotStorage.Policy.parse("bogus"));
        assertTrue(SlotStorage.same(item(STONE, 1), item(STONE, 40)));
        assertFalse(SlotStorage.same(item(STONE, 1), named(STONE, "Fancy")));
    }

    static SlotItem named(String id, @Nullable String name) {
        return new SlotItem(id, 1, name, null, false, null, null, null, null, null, null, null, null, 500, false, null, null, null, null);
    }
}
