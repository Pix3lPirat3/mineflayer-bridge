package io.github.pix3lpirat3.mfbridge.core.store;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * The objects that expire, soonest first. Each object is in the heap at most once and knows its
 * slot, so a refreshed TTL moves the entry instead of adding one.
 */
final class ExpiryHeap {
    private @Nullable StoredObject[] heap = new StoredObject[16];
    private int size;

    int size() {
        return size;
    }

    /** Returns the object that expires first, or null. */
    @Nullable StoredObject peek() {
        return size == 0 ? null : heap[0];
    }

    /** Puts the object where its current expiry belongs, or takes it out when it has none. */
    void update(StoredObject o) {
        if (o.state().expiresAtMs() < 0) {
            remove(o);
            return;
        }
        if (o.heapSlot < 0) {
            if (size == heap.length) {
                heap = Arrays.copyOf(heap, size * 2);
            }
            place(o, size++);
        }
        settle(o.heapSlot);
    }

    void remove(StoredObject o) {
        int slot = o.heapSlot;
        if (slot < 0) {
            return;
        }
        o.heapSlot = -1;
        StoredObject last = get(--size);
        heap[size] = null;
        if (last != o) {
            place(last, slot);
            settle(slot);
        }
    }

    private StoredObject get(int slot) {
        StoredObject o = heap[slot];
        if (o == null) {
            throw new IllegalStateException("empty heap slot " + slot);
        }
        return o;
    }

    private static long at(StoredObject o) {
        return o.state().expiresAtMs();
    }

    private void place(StoredObject o, int slot) {
        heap[slot] = o;
        o.heapSlot = slot;
    }

    /** Moves the object in a slot up or down until the order holds again. */
    private void settle(int slot) {
        StoredObject o = get(slot);
        int start = slot;
        while (slot > 0) {
            int parent = (slot - 1) >>> 1;
            StoredObject p = get(parent);
            if (at(p) <= at(o)) {
                break;
            }
            place(p, slot);
            slot = parent;
        }
        if (slot == start) {
            while (true) {
                int child = slot * 2 + 1;
                if (child >= size) {
                    break;
                }
                if (child + 1 < size && at(get(child + 1)) < at(get(child))) {
                    child++;
                }
                StoredObject c = get(child);
                if (at(o) <= at(c)) {
                    break;
                }
                place(c, slot);
                slot = child;
            }
        }
        place(o, slot);
    }
}
