package io.github.pix3lpirat3.mfbridge.core.store;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * All objects. Each object has an integer handle while it is in the store; a removed object's
 * handle is given to a later object once {@link #recycleHandles()} is called. A sorted index makes
 * prefix removal (an owner, extension or layer) a range operation. Confined to the client thread.
 */
@ClientThread
public final class ObjectStore {
    /** Observes changes after they are applied. */
    public interface Listener {
        /**
         * An object was created or replaced.
         *
         * @param previous the content before the write, or null when the object is new
         */
        void upserted(StoredObject object, StoredObject.@Nullable State previous);

        /** An object left the store. */
        void removed(StoredObject object);
    }

    private final Object2IntOpenHashMap<String> handles = new Object2IntOpenHashMap<>();
    private final Int2ObjectOpenHashMap<StoredObject> byHandle = new Int2ObjectOpenHashMap<>();
    /** Handles of removed objects, free for new keys. */
    private final IntArrayList free = new IntArrayList();
    /** Handles removed since the last {@link #recycleHandles()}; not handed out yet. */
    private final IntArrayList released = new IntArrayList();
    private int nextHandle;
    private final NavigableMap<String, StoredObject> sorted = new TreeMap<>();
    private final Object2IntOpenHashMap<String> countByOwner = new Object2IntOpenHashMap<>();
    private final ExpiryHeap expiries = new ExpiryHeap();
    private final Listener listener;
    private long version;

    /** Creates an empty store reporting to {@code listener}. */
    public ObjectStore(Listener listener) {
        this.listener = listener;
        handles.defaultReturnValue(-1);
    }

    private int assign(String key) {
        int h = free.isEmpty() ? nextHandle++ : free.popInt();
        handles.put(key, h);
        return h;
    }

    /**
     * Lets the handles of objects removed so far be given to new objects. Called once whoever keeps
     * handles (the render snapshot) has seen the removals, so a handle never changes its key in
     * between.
     */
    public void recycleHandles() {
        if (!released.isEmpty()) {
            free.addAll(released);
            released.clear();
        }
    }

    /** Returns how many handles were ever created; it stops growing once removed ones are reused. */
    public int handleCount() {
        return nextHandle;
    }

    /** Returns the object with this key, or null. */
    public @Nullable StoredObject get(String key) {
        int h = handles.getInt(key);
        return h < 0 ? null : byHandle.get(h);
    }

    /** Returns the object with this handle, or null. */
    public @Nullable StoredObject get(int handle) {
        return byHandle.get(handle);
    }

    /**
     * Inserts or replaces an object.
     *
     * @return the stored object
     */
    public StoredObject put(String key, ObjectKeys.Parts parts, StoredObject.State state) {
        StoredObject existing = get(key);
        StoredObject.State previous = null;
        StoredObject obj;
        if (existing == null) {
            obj = new StoredObject(assign(key), key, parts, state);
            byHandle.put(obj.handle(), obj);
            sorted.put(key, obj);
            countByOwner.addTo(parts.owner(), 1);
        } else {
            previous = existing.state();
            existing.assign(state);
            obj = existing;
        }
        // One heap entry per object: a TTL refresh moves it.
        expiries.update(obj);
        version++;
        listener.upserted(obj, previous);
        return obj;
    }

    /**
     * Removes one object.
     *
     * @return whether it existed
     */
    public boolean remove(String key) {
        StoredObject obj = get(key);
        if (obj == null) {
            return false;
        }
        removeObject(obj);
        return true;
    }

    private void removeObject(StoredObject obj) {
        byHandle.remove(obj.handle());
        handles.removeInt(obj.key());
        released.add(obj.handle());
        expiries.remove(obj);
        sorted.remove(obj.key());
        String owner = obj.parts().owner();
        if (countByOwner.addTo(owner, -1) <= 1) {
            countByOwner.removeInt(owner);
        }
        obj.kill();
        version++;
        listener.removed(obj);
    }

    /** Returns a copy of the objects whose key starts with {@code prefix}, in key order. */
    public List<StoredObject> under(String prefix) {
        return new ArrayList<>(sorted.subMap(prefix, true, prefix + Character.MAX_VALUE, false).values());
    }

    /**
     * Removes every object whose key starts with {@code prefix}.
     *
     * @return number removed
     */
    public int removePrefix(String prefix) {
        List<StoredObject> victims = under(prefix);
        for (StoredObject o : victims) {
            removeObject(o);
        }
        return victims.size();
    }

    /**
     * Removes every object under {@code prefix} not declared by snapshot generation {@code gen}.
     *
     * @return number removed
     */
    public int removeStale(String prefix, int gen) {
        int n = 0;
        for (StoredObject o : under(prefix)) {
            if (o.snapshotGen() != gen) {
                removeObject(o);
                n++;
            }
        }
        return n;
    }

    /** Returns the number of objects owned by {@code owner} (no trailing slash). */
    public int countOwner(String owner) {
        return countByOwner.getInt(owner);
    }

    /**
     * Removes objects whose TTL has passed. TTL counts from receipt.
     *
     * @return number removed
     */
    public int expire(long nowMs) {
        int n = 0;
        StoredObject o;
        while ((o = expiries.peek()) != null && o.state().expiresAtMs() <= nowMs) {
            removeObject(o);
            n++;
        }
        return n;
    }

    /** Returns every object in key order. The view is live; do not modify the store while iterating. */
    public Collection<StoredObject> all() {
        return sorted.values();
    }

    /** Returns the number of objects. */
    public int size() {
        return sorted.size();
    }

    /** Returns a counter that increases on every change. */
    public long version() {
        return version;
    }

    /** Returns the key of the object that holds a handle now, or null. */
    public @Nullable String keyOf(int handle) {
        StoredObject o = byHandle.get(handle);
        return o == null ? null : o.key();
    }

    /** Returns how many objects wait to expire. */
    public int expiring() {
        return expiries.size();
    }
}
