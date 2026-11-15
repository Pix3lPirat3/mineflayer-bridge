package io.github.pix3lpirat3.mfbridge.core;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobRef;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobStore;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Immutable view of every object the renderer should draw. Contains only objects that
 * are visible: the owning session is attached, the object is world-matched or global, its layer is
 * visible and it has not expired. Metadata ({@code core:layer}, {@code core:style}, {@code
 * core:vars}) and declarations (commands, actions, keybinds, tools, intercepts, settings, screens,
 * container views, crews, HUD panels and widgets, boss bars) never appear as entries; declarations
 * are in the {@code DeclarationSnapshot}.
 *
 * <p>Retained-tier objects ({@code core:ghost-blocks}, {@code core:heatmap}, {@code
 * core:block-highlight}) carry a {@link RenderEntry#blob()} reference. They appear even while their
 * blob has not arrived; renderers check {@link #blobs()} and skip them until the blob is available.
 * A blob's arrival publishes a new snapshot that lists the waiting entries as changed.
 *
 * <p>The core publishes a new instance only when an entry appeared, changed or left; otherwise
 * {@link BridgeCore#renderSnapshot()} keeps returning the same instance. An entry that did not
 * change is the same {@link RenderEntry} instance in the next snapshot, and {@link
 * #changesSince(long)} lists what differs from the snapshot before, so a renderer that keeps state
 * per object updates only those.
 */
@AnyThread
public final class RenderSnapshot {
    /** Snapshot with no entries and version 0. */
    public static final RenderSnapshot EMPTY = new RenderSnapshot(0, List.of());

    /**
     * What differs between two snapshots that follow each other. Apply {@code removed} first: an
     * object whose key was removed and written again is in both lists.
     *
     * @param from version of the snapshot this change starts at
     * @param upserted entries that are new or replace the entry with the same key
     * @param removed entries of the earlier snapshot that are gone
     */
    public record Change(long from, List<RenderEntry> upserted, List<RenderEntry> removed) {
        /** Copies the lists. */
        public Change {
            upserted = List.copyOf(upserted);
            removed = List.copyOf(removed);
        }
    }

    private final long version;
    private final List<RenderEntry> entries;
    private final BlobStore blobs;
    private final @Nullable Change change;

    /**
     * Creates a snapshot without blob access.
     *
     * @param version monotonically increasing content version
     * @param entries visible entries; copied
     */
    public RenderSnapshot(long version, List<RenderEntry> entries) {
        this(version, entries, BlobStore.NONE, null);
    }

    /**
     * Creates a snapshot.
     *
     * @param version monotonically increasing content version
     * @param entries visible entries in key order; copied
     * @param blobs blob availability and lazy decoding for the entries' blob references
     * @param change what differs from the snapshot before, or null when that is not known
     */
    public RenderSnapshot(long version, Collection<RenderEntry> entries, BlobStore blobs, @Nullable Change change) {
        this.version = version;
        this.entries = List.copyOf(entries);
        this.blobs = blobs;
        this.change = change;
    }

    /** Returns the content version; a larger version means newer content. */
    public long version() {
        return version;
    }

    /** Returns the visible entries in key order. The list is immutable. */
    public List<RenderEntry> entries() {
        return entries;
    }

    /** Returns the number of entries. */
    public int size() {
        return entries.size();
    }

    /**
     * Returns what changed since the snapshot with version {@code since}.
     *
     * @param since the version the caller last followed
     * @return the change, or null when {@code since} is not the snapshot right before this one; the
     *     caller then starts over from {@link #entries()}
     */
    public @Nullable Change changesSince(long since) {
        Change c = change;
        return c != null && c.from() == since ? c : null;
    }

    /**
     * Returns the entry with a key, found by binary search.
     *
     * @param key full object key
     * @return the entry, or null when the object is not visible
     */
    public @Nullable RenderEntry find(String key) {
        int lo = 0;
        int hi = entries.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            RenderEntry e = entries.get(mid);
            int c = e.key().compareTo(key);
            if (c == 0) {
                return e;
            }
            if (c < 0) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return null;
    }

    /**
     * Returns blob access for the entries' {@link RenderEntry#blob()} references: availability and
     * decoding on a worker thread, never on the caller's.
     */
    public BlobStore blobs() {
        return blobs;
    }

    /** Returns whether an entry's blob is available; true for entries without a blob. */
    public boolean blobReady(RenderEntry entry) {
        BlobRef ref = entry.blob();
        return ref == null || blobs.isAvailable(ref);
    }
}
