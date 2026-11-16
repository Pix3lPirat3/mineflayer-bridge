package io.github.pix3lpirat3.mfbridge.core.container;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotEntry;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.TargetSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The client-side virtual storage of one open container view. Slots with
 * policy {@code take}, {@code place}, {@code free} or {@code ask} are storage owned by the view;
 * moving items in or out never touches the player's real inventory. The cursor stack is local to the
 * view. Gestures follow vanilla container rules (26.3 {@code AbstractContainerMenu.doClick}), but only
 * across storage slots: {@code button} and {@code locked} slots are never filled or emptied here.
 *
 * <p>Storage needs an item source, since real items cannot move without packets. Two kinds of copy
 * source fill it: {@code source} slots, infinite templates whose declared item is copied onto the
 * cursor and never decremented, and, while the view has storage slots, the player's real inventory,
 * whose stacks are copied onto the cursor while the real inventory stays untouched. A cursor stack
 * that started as a copy ({@link #COPY} origin) is discarded rather than returned on close.
 *
 * <p>Every gesture method returns a {@link Move} describing what changed, or null when the gesture
 * did nothing. A move that touched an {@code ask} slot can be undone with {@link #rollback} when the
 * bot rejects it. Contents start from the declared slots; {@link #update} keeps the contents of
 * storage slots the player changed and takes the bot's contents for every other slot.
 *
 * <p>An empty storage slot is declared as an item with id {@code minecraft:air} or count 0.
 * Game-free; client thread only.
 */
@ClientThread
public final class SlotStorage {
    /** The view slot of the cursor in {@link Move#fromSlot()}. */
    public static final int CURSOR = -1;

    /** The {@link #cursorOrigin()} of a cursor stack copied from a source slot or the player's inventory. */
    public static final int COPY = -2;

    private static final String AIR = "minecraft:air";

    /** A slot's click policy. */
    public enum Policy {
        /** Clicks go to the bot as {@code container.input}; the default. */
        BUTTON,
        /** Clicks do nothing and nothing is sent. */
        LOCKED,
        /** Storage the player may take from. */
        TAKE,
        /** Storage the player may place into. */
        PLACE,
        /** Storage the player may take from and place into. */
        FREE,
        /** Like {@link #FREE}, but the bot confirms each move with {@code container.ack}. */
        ASK,
        /** An infinite template: clicks copy its declared item onto the cursor; nothing is placed into it. */
        SOURCE;

        /**
         * Parses a wire policy name; unknown names are {@link #BUTTON}.
         *
         * @param name the declared policy, such as {@code free}
         * @return the policy
         */
        public static Policy parse(final String name) {
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "locked" -> LOCKED;
                case "take" -> TAKE;
                case "place" -> PLACE;
                case "free" -> FREE;
                case "ask" -> ASK;
                case "source" -> SOURCE;
                default -> BUTTON;
            };
        }

        /**
         * Returns the wire name.
         *
         * @return the lower-case name
         */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Returns whether slots with this policy are client-side storage. Source slots are not: their
         * item is always the bot's, and they are never reported.
         *
         * @return true for take, place, free and ask
         */
        public boolean storage() {
            return this == TAKE || this == PLACE || this == FREE || this == ASK;
        }

        /**
         * Returns whether the player may take items out.
         *
         * @return true for take, free and ask
         */
        public boolean canTake() {
            return this == TAKE || this == FREE || this == ASK;
        }

        /**
         * Returns whether the player may put items in.
         *
         * @return true for place, free and ask
         */
        public boolean canPlace() {
            return this == PLACE || this == FREE || this == ASK;
        }
    }

    /** What the storage needs to know about items from the game. */
    public interface Rules {
        /**
         * Returns how many of an item fit in one stack, as the real item would allow.
         *
         * @param item the item
         * @return the stack limit, at least 1
         */
        int maxStackSize(SlotItem item);

        /**
         * Returns whether an item passes a slot's {@code accepts} filter (ids and tags).
         *
         * @param spec the filter
         * @param item the item
         * @return true when the item may be placed
         */
        boolean accepts(TargetSpec spec, SlotItem item);
    }

    /**
     * What one gesture changed.
     *
     * @param changed the storage slots whose contents changed, ascending. Immutable.
     * @param cursorChanged whether the cursor stack changed
     * @param askSlots the changed slots with policy {@code ask}, ascending; the bot must confirm the
     *     move when not empty. Immutable.
     * @param moved the stack that moved (with the count that moved), for {@code container.input}
     * @param fromSlot where {@code moved} came from: a view slot or {@link #CURSOR}
     * @param undo restores the state before the move with {@link #rollback}
     */
    public record Move(List<Integer> changed, boolean cursorChanged, List<Integer> askSlots, @Nullable SlotItem moved, int fromSlot,
            Undo undo) {
        /** Copies the lists. */
        public Move {
            changed = List.copyOf(changed);
            askSlots = List.copyOf(askSlots);
        }
    }

    /** The state a {@link Move} replaced; usable once. */
    public static final class Undo {
        private final Map<Integer, @Nullable SlotItem> slots;
        private final @Nullable SlotItem cursor;
        private final int cursorOrigin;
        private boolean used;

        private Undo(final Map<Integer, @Nullable SlotItem> slots, final @Nullable SlotItem cursor, final int cursorOrigin) {
            this.slots = slots;
            this.cursor = cursor;
            this.cursorOrigin = cursorOrigin;
        }
    }

    private record SlotRule(Policy policy, @Nullable TargetSpec accepts, @Nullable Integer maxStack) {}

    private final Rules rules;
    private final TreeMap<Integer, SlotRule> slots = new TreeMap<>();
    private final HashMap<Integer, SlotItem> items = new HashMap<>();
    private final HashMap<Integer, SlotItem> templates = new HashMap<>();
    private final Set<Integer> playerChanged = new HashSet<>();
    private @Nullable SlotItem cursor;
    private int cursorOrigin = CURSOR;
    private final LinkedHashMap<Integer, @Nullable SlotItem> before = new LinkedHashMap<>();
    private @Nullable SlotItem cursorBefore;
    private int originBefore;
    private boolean cursorTouched;

    /**
     * Creates empty storage.
     *
     * @param rules item stack limits and filters
     */
    public SlotStorage(final Rules rules) {
        this.rules = rules;
    }

    /**
     * Takes the bot's declared slots. Storage slots the player changed keep their contents; every
     * other storage slot takes the declared item. Slots that are no longer storage are dropped.
     *
     * @param declared the view's slots
     */
    public void update(final List<SlotEntry> declared) {
        final Map<Integer, SlotEntry> bySlot = new HashMap<>();
        slots.clear();
        for (final SlotEntry e : declared) {
            final Policy p = Policy.parse(e.policy());
            bySlot.put(e.slot(), e);
            slots.put(e.slot(), new SlotRule(p, e.accepts(), e.maxStack()));
        }
        items.keySet().removeIf(s -> !isStorage(s));
        playerChanged.removeIf(s -> !isStorage(s));
        templates.clear();
        for (final Map.Entry<Integer, SlotRule> e : slots.entrySet()) {
            final int slot = e.getKey();
            if (e.getValue().policy() == Policy.SOURCE) {
                final SlotItem template = bySlot.get(slot).item();
                if (!empty(template)) {
                    templates.put(slot, template);
                }
                continue;
            }
            if (!e.getValue().policy().storage() || playerChanged.contains(slot)) {
                continue;
            }
            final SlotItem item = bySlot.get(slot).item();
            if (empty(item)) {
                items.remove(slot);
            } else {
                items.put(slot, item);
            }
        }
    }

    /**
     * Returns a slot's policy.
     *
     * @param slot view slot index
     * @return the declared policy, or {@link Policy#BUTTON} for undeclared slots
     */
    public Policy policy(final int slot) {
        final SlotRule r = slots.get(slot);
        return r == null ? Policy.BUTTON : r.policy();
    }

    /**
     * Returns whether a slot is client-side storage.
     *
     * @param slot view slot index
     * @return true for take, place, free and ask slots
     */
    public boolean isStorage(final int slot) {
        return policy(slot).storage();
    }

    /**
     * Returns whether the view has any storage slot, which makes the player's inventory a copy
     * source.
     *
     * @return true when at least one slot is take, place, free or ask
     */
    public boolean hasStorage() {
        for (final SlotRule r : slots.values()) {
            if (r.policy().storage()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the storage slots in index order.
     *
     * @return slot indices. Immutable.
     */
    public List<Integer> storageSlots() {
        final List<Integer> out = new ArrayList<>();
        for (final Map.Entry<Integer, SlotRule> e : slots.entrySet()) {
            if (e.getValue().policy().storage()) {
                out.add(e.getKey());
            }
        }
        return List.copyOf(out);
    }

    /**
     * Returns the contents of a storage slot.
     *
     * @param slot view slot index
     * @return the item, or null when the slot is empty or not storage
     */
    public @Nullable SlotItem item(final int slot) {
        return items.get(slot);
    }

    /**
     * Returns the cursor stack.
     *
     * @return the item on the cursor, or null
     */
    public @Nullable SlotItem cursor() {
        return cursor;
    }

    /**
     * Returns the storage slot the cursor stack was first taken from.
     *
     * @return the slot, {@link #COPY} for a copy, or {@link #CURSOR} when the cursor is empty
     */
    public int cursorOrigin() {
        return cursorOrigin;
    }

    /**
     * Returns the storage slots as {@code container.storage} reports them: every storage slot in
     * index order with its policy, filter and cap, empty ones as {@code minecraft:air} with count 0.
     *
     * @return the entries. Immutable.
     */
    public List<SlotEntry> entries() {
        final List<SlotEntry> out = new ArrayList<>();
        for (final Map.Entry<Integer, SlotRule> e : slots.entrySet()) {
            final SlotRule r = e.getValue();
            if (r.policy().storage()) {
                final SlotItem item = items.get(e.getKey());
                out.add(new SlotEntry(e.getKey(), item == null ? withCount(new SlotItem(AIR), 0) : item, r.policy().wireName(),
                        r.accepts(), r.maxStack()));
            }
        }
        return List.copyOf(out);
    }

    /**
     * A left or right click on a slot: pick up all or half, place all or one, merge, or swap with the
     * cursor.
     *
     * @param slot view slot index
     * @param right whether it was the right button
     * @return the move, or null when nothing changed
     */
    public @Nullable Move click(final int slot, final boolean right) {
        final SlotRule r = slots.get(slot);
        if (r == null || !r.policy().storage()) {
            return null;
        }
        begin();
        final SlotItem have = items.get(slot);
        final SlotItem carried = cursor;
        if (carried == null) {
            if (have == null || !r.policy().canTake()) {
                return end(null, CURSOR);
            }
            final int take = right ? (have.count() + 1) / 2 : have.count();
            set(slot, have, have.count() - take);
            setCursor(have, take, slot);
            return end(withCount(have, take), slot);
        }
        if (have == null) {
            if (!canPlace(r, carried)) {
                return end(null, CURSOR);
            }
            final int put = right ? 1 : Math.min(carried.count(), limit(r, carried));
            set(slot, carried, put);
            setCursor(carried, carried.count() - put, cursorOrigin);
            return end(withCount(carried, put), CURSOR);
        }
        if (same(have, carried)) {
            if (canPlace(r, carried)) {
                final int room = limit(r, carried) - have.count();
                if (room <= 0) {
                    return end(null, CURSOR);
                }
                final int put = right ? 1 : Math.min(carried.count(), room);
                set(slot, have, have.count() + put);
                setCursor(carried, carried.count() - put, cursorOrigin);
                return end(withCount(carried, put), CURSOR);
            }
            if (r.policy().canTake()) {
                // Vanilla: a slot that refuses the cursor stack tops the cursor up from itself instead.
                final int take = Math.min(have.count(), rules.maxStackSize(carried) - carried.count());
                if (take <= 0) {
                    return end(null, CURSOR);
                }
                set(slot, have, have.count() - take);
                setCursor(carried, carried.count() + take, cursorOrigin);
                return end(withCount(have, take), slot);
            }
            return end(null, CURSOR);
        }
        if (r.policy().canTake() && canPlace(r, carried) && carried.count() <= limit(r, carried)) {
            set(slot, carried, carried.count());
            setCursor(have, have.count(), slot);
            return end(carried, CURSOR);
        }
        return end(null, CURSOR);
    }

    /**
     * A left or right click on a {@code source} slot: with an empty cursor, left puts a full stack of
     * copies of the template on the cursor and right puts one; with copies of the same item on the
     * cursor, it tops them up by that much, up to the stack limit; a copy of another item is
     * replaced. The template is never decremented, and a cursor stack taken from storage is kept.
     *
     * @param slot view slot index
     * @param right whether it was the right button
     * @return the move, or null when nothing changed or the slot is not a source
     */
    public @Nullable Move clickSource(final int slot, final boolean right) {
        final SlotItem template = templates.get(slot);
        if (template == null || policy(slot) != Policy.SOURCE) {
            return null;
        }
        return copyToCursor(template, right ? 1 : Math.max(1, rules.maxStackSize(template)), slot);
    }

    /**
     * A left or right click on a stack of the player's real inventory below a view with storage
     * slots: puts a virtual copy on the cursor, the whole stack for left and the larger half for
     * right, by the rules of {@link #clickSource}. The real inventory is never changed. With an empty
     * stack under the mouse, a copy on the cursor is dropped, as if put back.
     *
     * @param stack the real stack under the mouse, as an item; null or {@link #empty} for an empty slot
     * @param right whether it was the right button
     * @return the move, or null when nothing changed or the view has no storage
     */
    public @Nullable Move clickInventory(final @Nullable SlotItem stack, final boolean right) {
        if (!hasStorage()) {
            return null;
        }
        if (stack == null || empty(stack)) {
            if (cursor == null || cursorOrigin != COPY) {
                return null;
            }
            begin();
            final SlotItem dropped = cursor;
            setCursor(dropped, 0, CURSOR);
            return end(null, CURSOR);
        }
        return copyToCursor(stack, right ? (stack.count() + 1) / 2 : stack.count(), CURSOR);
    }

    /**
     * A shift-click on a copy source: copies of the item go into the storage slots that accept it,
     * matching stacks first, then empty slots, as many as {@code count}. The source is unchanged.
     *
     * @param item the item to copy: a source slot's template or a real inventory stack
     * @param count how many copies, for example the stack limit for a source slot
     * @param fromSlot the source slot, or {@link #CURSOR} for the player's inventory
     * @return the move, or null when nothing fit
     */
    public @Nullable Move quickCopy(final SlotItem item, final int count, final int fromSlot) {
        if (empty(item) || count <= 0) {
            return null;
        }
        begin();
        int left = count;
        for (final boolean merge : new boolean[] {true, false}) {
            for (final Map.Entry<Integer, SlotRule> e : slots.entrySet()) {
                if (left == 0) {
                    break;
                }
                final SlotItem there = items.get(e.getKey());
                if (!canPlace(e.getValue(), item) || (merge ? there == null || !same(there, item) : there != null)) {
                    continue;
                }
                final int current = there == null ? 0 : there.count();
                final int put = Math.min(left, limit(e.getValue(), item) - current);
                if (put > 0) {
                    set(e.getKey(), item, current + put);
                    left -= put;
                }
            }
        }
        return end(left == count ? null : withCount(item, count - left), fromSlot);
    }

    /**
     * Returns the template of a {@code source} slot.
     *
     * @param slot view slot index
     * @return the declared item, or null when the slot is not a source or declares an empty item
     */
    public @Nullable SlotItem template(final int slot) {
        return templates.get(slot);
    }

    private @Nullable Move copyToCursor(final SlotItem item, final int count, final int from) {
        begin();
        final SlotItem carried = cursor;
        if (carried == null || (cursorOrigin == COPY && !same(carried, item))) {
            setCursor(item, count, COPY);
            return end(withCount(item, count), from);
        }
        if (!same(carried, item)) {
            return end(null, CURSOR);
        }
        final int add = Math.min(count, Math.max(1, rules.maxStackSize(carried)) - carried.count());
        if (add <= 0) {
            return end(null, CURSOR);
        }
        setCursor(carried, carried.count() + add, cursorOrigin);
        return end(withCount(item, add), from);
    }

    /**
     * A shift-click: moves the slot's stack into the other storage slots that accept it, filling
     * matching stacks first, then empty slots. Slots with a different policy come first, so items
     * shift from {@code take} slots into {@code place} slots and back.
     *
     * @param slot view slot index
     * @return the move, or null when nothing moved
     */
    public @Nullable Move quickMove(final int slot) {
        final SlotRule source = slots.get(slot);
        final SlotItem have = items.get(slot);
        if (source == null || have == null || !source.policy().canTake()) {
            return null;
        }
        begin();
        final List<Integer> targets = new ArrayList<>();
        for (final boolean other : new boolean[] {true, false}) {
            for (final Map.Entry<Integer, SlotRule> e : slots.entrySet()) {
                final int t = e.getKey();
                if (t != slot && (e.getValue().policy() != source.policy()) == other && canPlace(e.getValue(), have)) {
                    targets.add(t);
                }
            }
        }
        int left = have.count();
        for (final boolean merge : new boolean[] {true, false}) {
            for (final int t : targets) {
                if (left == 0) {
                    break;
                }
                final SlotItem there = items.get(t);
                if (merge ? there == null || !same(there, have) : there != null) {
                    continue;
                }
                final int current = there == null ? 0 : there.count();
                final int put = Math.min(left, limit(slots.get(t), have) - current);
                if (put > 0) {
                    set(t, have, current + put);
                    left -= put;
                }
            }
        }
        if (left == have.count()) {
            return end(null, CURSOR);
        }
        set(slot, have, left);
        return end(withCount(have, have.count() - left), slot);
    }

    /**
     * A double click: gathers stacks of the cursor's item from storage slots onto the cursor, up to
     * the item's stack limit; partial stacks go first (vanilla {@code PICKUP_ALL}).
     *
     * @return the move, or null when nothing was gathered
     */
    public @Nullable Move collect() {
        final SlotItem carried = cursor;
        if (carried == null) {
            return null;
        }
        begin();
        final int max = rules.maxStackSize(carried);
        int count = carried.count();
        int from = CURSOR;
        for (int pass = 0; pass < 2 && count < max; pass++) {
            for (final Map.Entry<Integer, SlotRule> e : slots.entrySet()) {
                if (count >= max) {
                    break;
                }
                final int s = e.getKey();
                final SlotItem there = items.get(s);
                if (there == null || !e.getValue().policy().canTake() || !same(there, carried)) {
                    continue;
                }
                if (pass == 0 && there.count() >= limit(e.getValue(), there)) {
                    continue;
                }
                final int take = Math.min(there.count(), max - count);
                set(s, there, there.count() - take);
                count += take;
                if (from == CURSOR) {
                    from = s;
                }
            }
        }
        if (count == carried.count()) {
            return end(null, CURSOR);
        }
        setCursor(carried, count, cursorOrigin);
        return end(withCount(carried, count - carried.count()), from);
    }

    /**
     * A number key over a slot: moves or swaps its stack with a second storage slot that stands in
     * for the hotbar slot.
     *
     * @param slot the hovered view slot
     * @param target the storage slot bound to the number key
     * @return the move, or null when nothing changed
     */
    public @Nullable Move swap(final int slot, final int target) {
        final SlotRule a = slots.get(slot);
        final SlotRule b = slots.get(target);
        if (slot == target || a == null || b == null || !a.policy().storage() || !b.policy().storage()) {
            return null;
        }
        begin();
        final SlotItem x = items.get(slot);
        final SlotItem y = items.get(target);
        if (x != null && y == null) {
            if (!a.policy().canTake() || !canPlace(b, x)) {
                return end(null, CURSOR);
            }
            final int put = Math.min(x.count(), limit(b, x));
            set(target, x, put);
            set(slot, x, x.count() - put);
            return end(withCount(x, put), slot);
        }
        if (x == null && y != null) {
            if (!b.policy().canTake() || !canPlace(a, y)) {
                return end(null, CURSOR);
            }
            final int put = Math.min(y.count(), limit(a, y));
            set(slot, y, put);
            set(target, y, y.count() - put);
            return end(withCount(y, put), target);
        }
        if (x != null && y != null && a.policy().canTake() && b.policy().canTake() && canPlace(a, y) && canPlace(b, x)
                && x.count() <= limit(b, x) && y.count() <= limit(a, y)) {
            set(slot, y, y.count());
            set(target, x, x.count());
            return end(x, slot);
        }
        return end(null, CURSOR);
    }

    /**
     * A drag across slots with a stack on the cursor (vanilla quick craft): the left button splits
     * the stack evenly, the right button places one item per slot. Slots that refuse the item, hold
     * another item, or are not storage are skipped; no more slots take part than there are items.
     * What does not fit stays on the cursor.
     *
     * @param dragged slots in the order the mouse crossed them
     * @param right whether it was the right button
     * @return the move, or null when nothing was placed
     */
    public @Nullable Move drag(final List<Integer> dragged, final boolean right) {
        final SlotItem carried = cursor;
        if (carried == null) {
            return null;
        }
        final List<Integer> eligible = new ArrayList<>();
        for (final int s : dragged) {
            final SlotRule r = slots.get(s);
            final SlotItem there = items.get(s);
            if (r != null && r.policy().storage() && canPlace(r, carried) && (there == null || same(there, carried))
                    && !eligible.contains(s) && eligible.size() < carried.count()) {
                eligible.add(s);
            }
        }
        if (eligible.isEmpty()) {
            return null;
        }
        begin();
        final int each = right ? 1 : carried.count() / eligible.size();
        int left = carried.count();
        for (final int s : eligible) {
            final SlotItem there = items.get(s);
            final int current = there == null ? 0 : there.count();
            final int put = Math.min(each, Math.max(0, limit(slots.get(s), carried) - current));
            if (put > 0) {
                set(s, carried, current + put);
                left -= put;
            }
        }
        if (left == carried.count()) {
            return end(null, CURSOR);
        }
        setCursor(carried, left, cursorOrigin);
        return end(withCount(carried, carried.count() - left), CURSOR);
    }

    /**
     * Puts the cursor stack back, as when the view closes: into its origin slot first, then into
     * other storage slots that accept it, and whatever still does not fit into the origin regardless
     * of its cap, or into any empty storage slot. A copy ({@link #COPY} origin) is discarded instead,
     * since it never came out of the view.
     *
     * @return the move, or null when the cursor was empty
     */
    public @Nullable Move returnCursor() {
        final SlotItem carried = cursor;
        if (carried == null) {
            return null;
        }
        begin();
        if (cursorOrigin == COPY) {
            setCursor(carried, 0, CURSOR);
            return end(null, CURSOR);
        }
        int left = carried.count();
        final int origin = cursorOrigin;
        final SlotRule originRule = slots.get(origin);
        if (originRule != null && originRule.policy().storage()) {
            left = putBack(origin, originRule, carried, left);
        }
        for (final boolean merge : new boolean[] {true, false}) {
            for (final Map.Entry<Integer, SlotRule> e : slots.entrySet()) {
                if (left == 0) {
                    break;
                }
                final SlotItem there = items.get(e.getKey());
                if (e.getKey() != origin && canPlace(e.getValue(), carried) && (merge ? there != null && same(there, carried) : there == null)) {
                    left = putBack(e.getKey(), e.getValue(), carried, left);
                }
            }
        }
        if (left > 0) {
            // The items came out of the view, so they always go back in, even past a cap or filter.
            final SlotItem there = originRule == null ? null : items.get(origin);
            if (originRule != null && originRule.policy().storage() && (there == null || same(there, carried))) {
                set(origin, carried, (there == null ? 0 : there.count()) + left);
                left = 0;
            } else {
                for (final Map.Entry<Integer, SlotRule> e : slots.entrySet()) {
                    if (e.getValue().policy().storage() && items.get(e.getKey()) == null) {
                        set(e.getKey(), carried, left);
                        left = 0;
                        break;
                    }
                }
            }
        }
        setCursor(carried, 0, CURSOR);
        return end(withCount(carried, carried.count() - left), CURSOR);
    }

    private int putBack(final int slot, final SlotRule r, final SlotItem carried, final int left) {
        final SlotItem there = items.get(slot);
        if (there != null && !same(there, carried)) {
            return left;
        }
        final int current = there == null ? 0 : there.count();
        final int put = Math.min(left, Math.max(0, limit(r, carried) - current));
        if (put > 0) {
            set(slot, carried, current + put);
        }
        return left - put;
    }

    /**
     * Undoes a move the bot rejected: the slots and cursor it changed get their earlier contents.
     *
     * @param undo the move's undo record
     * @return the slots restored, ascending; empty when the undo was already used. Immutable.
     */
    public List<Integer> rollback(final Undo undo) {
        if (undo.used) {
            return List.of();
        }
        undo.used = true;
        for (final Map.Entry<Integer, @Nullable SlotItem> e : undo.slots.entrySet()) {
            final SlotItem item = e.getValue();
            if (item == null) {
                items.remove(e.getKey());
            } else {
                items.put(e.getKey(), item);
            }
        }
        cursor = undo.cursor;
        cursorOrigin = undo.cursorOrigin;
        return List.copyOf(new TreeMap<>(undo.slots).keySet());
    }

    private void begin() {
        before.clear();
        cursorBefore = cursor;
        originBefore = cursorOrigin;
        cursorTouched = false;
    }

    private @Nullable Move end(final @Nullable SlotItem moved, final int from) {
        final List<Integer> changed = new ArrayList<>();
        final List<Integer> ask = new ArrayList<>();
        final Map<Integer, @Nullable SlotItem> previous = new HashMap<>();
        for (final Map.Entry<Integer, @Nullable SlotItem> e : before.entrySet()) {
            final int s = e.getKey();
            final SlotItem was = e.getValue();
            final SlotItem now = items.get(s);
            if (was == null ? now == null : was.equals(now)) {
                continue;
            }
            changed.add(s);
            previous.put(s, was);
            playerChanged.add(s);
            if (policy(s) == Policy.ASK) {
                ask.add(s);
            }
        }
        final boolean cursorChanged = cursorTouched && !(cursorBefore == null ? cursor == null : cursorBefore.equals(cursor));
        if (changed.isEmpty() && !cursorChanged) {
            return null;
        }
        changed.sort(null);
        ask.sort(null);
        return new Move(changed, cursorChanged, ask, moved, from, new Undo(previous, cursorBefore, originBefore));
    }

    private void set(final int slot, final SlotItem like, final int count) {
        if (!before.containsKey(slot)) {
            before.put(slot, items.get(slot));
        }
        if (count <= 0) {
            items.remove(slot);
        } else {
            items.put(slot, withCount(like, count));
        }
    }

    private void setCursor(final SlotItem like, final int count, final int origin) {
        cursorTouched = true;
        if (count <= 0) {
            cursor = null;
            cursorOrigin = CURSOR;
        } else {
            cursor = withCount(like, count);
            cursorOrigin = origin;
        }
    }

    private boolean canPlace(final SlotRule r, final SlotItem item) {
        final TargetSpec spec = r.accepts();
        return r.policy().canPlace() && (spec == null || rules.accepts(spec, item));
    }

    private int limit(final SlotRule r, final SlotItem item) {
        final int max = Math.max(1, rules.maxStackSize(item));
        final Integer cap = r.maxStack();
        return cap == null ? max : Math.max(1, Math.min(max, cap));
    }

    /**
     * Returns whether two items stack: equal in everything but the count.
     *
     * @param a one item
     * @param b another item
     * @return true when they stack
     */
    public static boolean same(final SlotItem a, final SlotItem b) {
        return withCount(a, 1).equals(withCount(b, 1));
    }

    /**
     * Returns whether a declared item means an empty slot: {@code minecraft:air} (or {@code air}) or
     * count 0.
     *
     * @param item the item
     * @return true for an empty slot
     */
    public static boolean empty(final SlotItem item) {
        return item.count() == 0 || item.id().equals(AIR) || item.id().equals("air");
    }

    /**
     * Returns a copy of an item with another count.
     *
     * @param item the item
     * @param count the new count
     * @return the copy, or the item itself when the count is unchanged
     */
    public static SlotItem withCount(final SlotItem item, final int count) {
        if (item.count() == count) {
            return item;
        }
        return new SlotItem(item.id(), count, item.name(), item.lore(), item.glint(), item.customModel(), item.components(),
                item.countText(), item.bar(), item.barColor(), item.head(), item.color(), item.frames(), item.frameMs(),
                item.hideTooltip(), item.clickSound(), item.nameSpans(), item.loreLines(), item.headTexture());
    }
}
