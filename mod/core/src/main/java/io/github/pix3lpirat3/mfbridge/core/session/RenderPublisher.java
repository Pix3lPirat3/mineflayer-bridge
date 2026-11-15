package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.core.spatial.SpatialIndex;
import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.RenderEntry;
import io.github.pix3lpirat3.mfbridge.core.RenderSnapshot;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobRef;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobStore;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectKeys;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectStore;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.core.world.LayerRegistry;
import io.github.pix3lpirat3.mfbridge.protocol.core.BlockHighlightData;
import io.github.pix3lpirat3.mfbridge.protocol.core.BoxData;
import io.github.pix3lpirat3.mfbridge.protocol.core.EntityTagData;
import io.github.pix3lpirat3.mfbridge.protocol.core.GhostBlocksData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HeatmapData;
import io.github.pix3lpirat3.mfbridge.protocol.core.LabelData;
import io.github.pix3lpirat3.mfbridge.protocol.core.LineData;
import io.github.pix3lpirat3.mfbridge.protocol.core.PathData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SoundEmitterData;
import io.github.pix3lpirat3.mfbridge.protocol.core.StyleData;
import io.github.pix3lpirat3.mfbridge.protocol.core.WaypointData;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the render snapshot in step with the store. Writes mark single objects, and visibility
 * changes mark an owner or everything; {@link #publish} then looks only at what was marked, keeps
 * the entry instances of everything else and records what changed for the renderers.
 */
@ClientThread
final class RenderPublisher {
    private static final String STYLE_ID = "~style/";

    private final ObjectStore store;
    private final LayerRegistry layers;
    private final BlobStore blobs;
    private final Sight sight;

    private final TreeMap<String, RenderEntry> visible = new TreeMap<>();
    private final Int2ObjectOpenHashMap<RenderEntry> byHandle = new Int2ObjectOpenHashMap<>();
    private final IntOpenHashSet dirty = new IntOpenHashSet();
    /** Entries listed as changed even when they are equal, because their blob arrived. */
    private final IntOpenHashSet forced = new IntOpenHashSet();
    private final Set<String> dirtyOwners = new HashSet<>();
    private boolean allDirty = true;
    private final Map<String, Boolean> ownerShown = new HashMap<>();
    private volatile RenderSnapshot snapshot = RenderSnapshot.EMPTY;
    private SpatialIndex spatial = SpatialIndex.EMPTY;
    /** Version of the snapshot the spatial index was last checked against. */
    private long spatialSeen;

    RenderPublisher(ObjectStore store, LayerRegistry layers, BlobStore blobs, Sight sight) {
        this.store = store;
        this.layers = layers;
        this.blobs = blobs;
        this.sight = sight;
    }

    /** Returns the latest published snapshot. */
    @AnyThread
    RenderSnapshot snapshot() {
        return snapshot;
    }

    /**
     * Returns the spatial index of the snapshot's zones, points and routes. The index and its
     * version stay while no zone, point or route changed.
     */
    SpatialIndex spatial() {
        RenderSnapshot s = snapshot;
        if (spatialSeen != s.version()) {
            RenderSnapshot.Change change = s.changesSince(spatialSeen);
            spatialSeen = s.version();
            if (change == null || SpatialIndex.touches(change)) {
                spatial = SpatialIndex.build(s.version(), s.entries());
            }
        }
        return spatial;
    }

    /** Marks one object that was written or removed. */
    void objectChanged(StoredObject o) {
        dirty.add(o.handle());
    }

    /** Marks the objects that use a style which was written or removed. */
    void styleChanged(StoredObject style) {
        String localId = style.parts().localId();
        if (!localId.startsWith(STYLE_ID)) {
            return;
        }
        String name = localId.substring(STYLE_ID.length());
        String prefix = style.parts().owner() + '/' + style.parts().extension() + '/';
        for (StoredObject o : store.under(prefix)) {
            if (name.equals(styleName(o.state().decoded()))) {
                dirty.add(o.handle());
            }
        }
    }

    /** Marks every object of an owner (no trailing slash): its layers or attachment changed. */
    void ownerChanged(String owner) {
        dirtyOwners.add(owner);
    }

    /** Marks everything: the player's world, the focus or the set of sessions changed. */
    void allChanged() {
        allDirty = true;
    }

    /** Marks the entries that wait for a blob, so renderers look at them again. */
    void blobsChanged() {
        for (RenderEntry e : byHandle.values()) {
            if (e.blob() != null) {
                dirty.add(e.handle());
                forced.add(e.handle());
            }
        }
    }

    /**
     * Brings the snapshot up to date and publishes a new one when an entry appeared, changed or
     * left.
     *
     * @param now bridge clock ms, to leave out expired objects
     */
    void publish(long now) {
        if (!allDirty && dirty.isEmpty() && dirtyOwners.isEmpty()) {
            return;
        }
        List<RenderEntry> upserted = new ArrayList<>();
        List<RenderEntry> removed = new ArrayList<>();
        ownerShown.clear();
        if (allDirty) {
            for (StoredObject o : store.all()) {
                refresh(o.handle(), o, now, upserted, removed);
                dirty.remove(o.handle());
            }
        } else {
            for (String owner : dirtyOwners) {
                for (StoredObject o : store.under(owner + '/')) {
                    refresh(o.handle(), o, now, upserted, removed);
                    dirty.remove(o.handle());
                }
            }
        }
        // What is left was written or removed one by one.
        IntIterator it = dirty.iterator();
        while (it.hasNext()) {
            int handle = it.nextInt();
            refresh(handle, store.get(handle), now, upserted, removed);
        }
        allDirty = false;
        dirty.clear();
        forced.clear();
        dirtyOwners.clear();
        if (upserted.isEmpty() && removed.isEmpty()) {
            return;
        }
        RenderSnapshot previous = snapshot;
        snapshot = new RenderSnapshot(previous.version() + 1, visible.values(), blobs,
                new RenderSnapshot.Change(previous.version(), upserted, removed));
    }

    private void refresh(int handle, @Nullable StoredObject o, long now, List<RenderEntry> upserted,
            List<RenderEntry> removed) {
        RenderEntry old = byHandle.get(handle);
        RenderEntry next = o != null && visible(o, now) ? entry(o, old) : null;
        // A key that changes its kind leaves and comes back, so followers drop what they kept for it.
        if (old != null && (next == null || !old.key().equals(next.key()) || old.kind() != next.kind())) {
            byHandle.remove(handle);
            // A key that was removed and written again may already hold its new entry.
            visible.remove(old.key(), old);
            removed.add(old);
            old = null;
        }
        if (next != null && (next != old || forced.remove(handle))) {
            byHandle.put(handle, next);
            visible.put(next.key(), next);
            upserted.add(next);
        }
    }

    /** Returns whether the player sees an object now, in either snapshot. */
    boolean seen(StoredObject o, long now) {
        ownerShown.clear();
        return !KindRoles.metadata(o.state().kind()) && inSight(o, now);
    }

    private boolean visible(StoredObject o, long now) {
        return KindRoles.rendered(o.state().kind()) && inSight(o, now);
    }

    private boolean inSight(StoredObject o, long now) {
        StoredObject.State st = o.state();
        if (st.expiresAtMs() >= 0 && st.expiresAtMs() <= now) {
            return false;
        }
        String owner = o.parts().owner();
        if (!sight.focusAllows(owner, st.traits())) {
            return false;
        }
        Boolean ownerOk = ownerShown.get(owner);
        if (ownerOk == null) {
            ownerOk = sight.ownerVisible(owner);
            ownerShown.put(owner, ownerOk);
        }
        if (!ownerOk) {
            return false;
        }
        if (st.scope() == Scope.WORLD && !sight.worldVisible(st.world())) {
            return false;
        }
        String layerKey = o.layerKey();
        return layerKey == null || layers.visible(layerKey);
    }

    /** Builds the entry of an object, or returns {@code old} when nothing in it would differ. */
    private RenderEntry entry(StoredObject o, @Nullable RenderEntry old) {
        StoredObject.State st = o.state();
        ObjectKeys.Parts parts = o.parts();
        String styleName = styleName(st.decoded());
        StyleData style = null;
        if (styleName != null) {
            // Style references are scope-relative ids under ~style/.
            String styleKey = parts.owner() + '/' + parts.extension() + '/' + STYLE_ID + styleName;
            StoredObject so = store.get(styleKey);
            if (so != null && so.state().decoded() instanceof StyleData sd) {
                style = sd;
            }
        }
        // Every write makes new decoded data, so identity is enough to tell a change.
        if (old != null && old.key().equals(o.key()) && old.data() == st.decoded() && old.style() == style
                && old.traits() == st.traits() && old.rev() == st.rev() && old.kind() == st.kind()
                && old.scope() == st.scope() && Objects.equals(old.layer(), st.layer())
                && Objects.equals(old.world(), st.world())) {
            return old;
        }
        return new RenderEntry(o.handle(), o.key(), parts.owner(), parts.extension(), parts.localId(), st.layer(),
                st.kind(), st.rev(), st.decoded(), style, st.scope(), st.world(), blobOf(st.decoded()), st.traits());
    }

    private static @Nullable String styleName(@Nullable Object decoded) {
        return switch (decoded) {
            case BoxData b -> b.style();
            case LineData l -> l.style();
            case PathData p -> p.style();
            case LabelData l -> l.style();
            case EntityTagData e -> e.style();
            case WaypointData w -> w.style();
            case GhostBlocksData g -> g.style();
            case BlockHighlightData b -> b.style();
            case null, default -> null;
        };
    }

    /** Returns the blob a retained-tier object refers to, or null. */
    static @Nullable BlobRef blobOf(@Nullable Object decoded) {
        return switch (decoded) {
            case GhostBlocksData g -> BlobRef.tryOf(g.blob(), g.size());
            case HeatmapData h -> BlobRef.tryOf(h.blob(), h.size());
            case BlockHighlightData b -> BlobRef.tryOf(b.blob(), b.size());
            case SoundEmitterData e -> BlobRef.tryOf(e.blob(), e.size());
            case null, default -> null;
        };
    }
}
