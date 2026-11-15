package io.github.pix3lpirat3.mfbridge.core.history;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectKeys;
import io.github.pix3lpirat3.mfbridge.core.store.PatchApplier;
import io.github.pix3lpirat3.mfbridge.core.util.Encoding;
import io.github.pix3lpirat3.mfbridge.protocol.core.Lifetime;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjEditResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectFull;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedCreate;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpBody;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedRemove;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedUpdate;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireException;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireReader;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireWriter;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The {@link HistoryControls} implementation: entries hold each change as the object's state before
 * and after, so undo writes the before state (the inverse op) and redo the after state. The store and
 * the bots are reached through a {@link Target}, which keeps this class free of the engine and unit
 * testable. Client thread only.
 */
@ClientThread
public final class EditHistory implements HistoryControls {
    private static final int FORMAT = 1;

    /**
     * An object's state as the history needs it to restore the object.
     *
     * @param kindName object kind name
     * @param layer local layer name, or null
     * @param lifetime lifetime
     * @param scope scope
     * @param world world key of world-scoped objects
     * @param data encoded kind data
     * @param rev revision the object had in this state
     */
    public record Image(String kindName, @Nullable String layer, Lifetime lifetime, Scope scope, @Nullable WorldKey world,
            byte[] data, long rev) {
        Image withRev(long r) {
            return new Image(kindName, layer, lifetime, scope, world, data, r);
        }

        Image withData(byte[] d) {
            return new Image(kindName, layer, lifetime, scope, world, d, rev);
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof Image i && kindName.equals(i.kindName) && Objects.equals(layer, i.layer)
                    && lifetime == i.lifetime && scope == i.scope && Objects.equals(world, i.world)
                    && Arrays.equals(data, i.data) && rev == i.rev;
        }

        @Override
        public int hashCode() {
            return Objects.hash(kindName, layer, lifetime, scope, world, rev) * 31 + Arrays.hashCode(data);
        }

        @Override
        public String toString() {
            return "Image[" + kindName + " rev " + rev + ", " + data.length + " bytes]";
        }
    }

    /** What the history writes through: the object store and the owning sessions. Client thread. */
    public interface Target {
        /** Returns the current state of an object, or null when it does not exist. */
        @Nullable Image current(String key);

        /** Applies a shared op on behalf of the player; throws as {@code BridgeCore.sharedOp}. */
        SharedOpResult sharedOp(SharedOpBody op);

        /** Returns the wire id of a kind, or throws {@code BridgeException unknown_kind}. */
        int kindId(String kindName);

        /** Writes a player object in a state, letting the revision increment. */
        void putPlayer(String key, Image image);

        /** Removes a player object, returning whether it existed. */
        boolean removePlayer(String key);

        /** Proposes an edit to an object's owning session. */
        CompletableFuture<ObjEditResult> propose(String key, long baseRev, byte[] fields);
    }

    /** Loads and saves the encoded history of a world store. */
    public interface Storage {
        /** Loads the bytes last saved for a store; completes with null when there are none. */
        CompletableFuture<byte @Nullable []> load(String storeKey);

        /** Saves the encoded history of a store, replacing what was saved before. */
        void save(String storeKey, byte[] bytes);
    }

    /** One changed object. {@code before} and {@code after} are null when the object did not exist. */
    record Change(String key, @Nullable Image before, @Nullable Image after) {}

    /** One recorded edit; {@code changes} is replaced as undo and redo update the revisions. */
    static final class Entry {
        final long id;
        final String label;
        final long timeMs;
        List<Change> changes;

        Entry(long id, String label, long timeMs, List<Change> changes) {
            this.id = id;
            this.label = label;
            this.timeMs = timeMs;
            this.changes = List.copyOf(changes);
        }

        HistoryEntry view(boolean applied) {
            List<String> keys = new ArrayList<>(changes.size());
            for (Change c : changes) {
                keys.add(c.key());
            }
            return new HistoryEntry(id, label, timeMs, keys, applied);
        }
    }

    private final Target target;
    private final Storage storage;
    private final LongSupplier wallClock;
    private final int capacity;
    private final ArrayList<Entry> done = new ArrayList<>();
    private final ArrayList<Entry> undone = new ArrayList<>();
    private @Nullable String storeKey;
    private @Nullable CompletableFuture<byte @Nullable []> loading;
    private long nextId = 1;
    private long version;
    private boolean busy;

    /**
     * Creates an empty history with no store selected; edits are recorded but not saved until
     * {@link #switchStore} selects one.
     *
     * @param wallClock wall-clock milliseconds for entry times
     * @param capacity most entries kept, applied and undone together
     */
    public EditHistory(Target target, Storage storage, LongSupplier wallClock, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.target = target;
        this.storage = storage;
        this.wallClock = wallClock;
        this.capacity = capacity;
    }

    /**
     * Makes another world store's history current: the current one stays saved, the other loads in
     * the background and is merged in by {@link #poll()}. Entries recorded while it loads are kept as
     * the newest.
     */
    public void switchStore(String key) {
        if (key.equals(storeKey)) {
            return;
        }
        storeKey = key;
        done.clear();
        undone.clear();
        nextId = 1;
        busy = false;
        version++;
        loading = storage.load(key);
    }

    /** Returns the world store whose history is current, or null before the first switch. */
    public @Nullable String storeKey() {
        return storeKey;
    }

    /** Applies a finished background load. Call once per client tick. */
    public void poll() {
        CompletableFuture<byte @Nullable []> l = loading;
        if (l == null || !l.isDone()) {
            return;
        }
        loading = null;
        if (l.isCompletedExceptionally()) {
            return;
        }
        byte[] bytes = l.join();
        if (bytes == null) {
            return;
        }
        Loaded loaded;
        try {
            loaded = decode(bytes);
        } catch (WireException | IllegalArgumentException e) {
            return;
        }
        List<Entry> recent = new ArrayList<>(done);
        done.clear();
        done.addAll(loaded.done);
        long offset = loaded.nextId - 1;
        for (Entry e : recent) {
            done.add(new Entry(e.id + offset, e.label, e.timeMs, e.changes));
        }
        if (undone.isEmpty() && recent.isEmpty()) {
            undone.addAll(loaded.undone);
        } else {
            List<Entry> keep = new ArrayList<>(undone);
            undone.clear();
            for (Entry e : keep) {
                undone.add(new Entry(e.id + offset, e.label, e.timeMs, e.changes));
            }
        }
        nextId = nextId + offset;
        trim();
        version++;
        if (!recent.isEmpty()) {
            save();
        }
    }

    @Override
    public SharedOpResult sharedOp(String label, SharedOpBody op) {
        String key = keyOf(op);
        Image before = target.current(key);
        SharedOpResult result = target.sharedOp(op);
        record(label, new Change(key, before, target.current(key)));
        return result;
    }

    @Override
    public void putPlayerObject(String label, String key, KindType<?> kind, @Nullable String layer, Scope scope,
            @Nullable WorldKey world, byte[] data) {
        Image before = target.current(key);
        target.putPlayer(key, new Image(kind.name(), layer, Lifetime.PERSISTENT, scope, world, data, 0));
        record(label, new Change(key, before, target.current(key)));
    }

    @Override
    public boolean removePlayerObject(String label, String key) {
        Image before = target.current(key);
        if (!target.removePlayer(key)) {
            return false;
        }
        record(label, new Change(key, before, null));
        return true;
    }

    @Override
    public CompletableFuture<ObjEditResult> proposeEdit(String label, String key, long baseRev, byte[] fields) {
        Image before = target.current(key);
        CompletableFuture<ObjEditResult> answer = target.propose(key, baseRev, fields);
        if (before == null) {
            return answer;
        }
        return answer.thenApply(r -> {
            if (r.accepted()) {
                byte[] patched;
                try {
                    patched = PatchApplier.apply(before.data(), fields);
                } catch (WireException e) {
                    return r;
                }
                record(label, new Change(key, before, before.withData(patched).withRev(baseRev + 1)));
            }
            return r;
        });
    }

    private static String keyOf(SharedOpBody op) {
        return switch (op) {
            case SharedOpBody.Create c -> c.value().object().key();
            case SharedOpBody.Update u -> u.value().key();
            case SharedOpBody.Remove r -> r.value().key();
        };
    }

    private void record(String label, Change change) {
        done.add(new Entry(nextId++, label, wallClock.getAsLong(), List.of(change)));
        undone.clear();
        trim();
        version++;
        save();
    }

    private void trim() {
        while (done.size() + undone.size() > capacity && !done.isEmpty()) {
            done.removeFirst();
        }
        while (undone.size() > capacity) {
            undone.removeFirst();
        }
    }

    private void save() {
        String key = storeKey;
        if (key != null) {
            storage.save(key, encode());
        }
    }

    @Override
    public CompletableFuture<HistoryEntry> undo() {
        return step(true);
    }

    @Override
    public CompletableFuture<HistoryEntry> redo() {
        return step(false);
    }

    private CompletableFuture<HistoryEntry> step(boolean undo) {
        ArrayList<Entry> from = undo ? done : undone;
        ArrayList<Entry> to = undo ? undone : done;
        if (busy) {
            return CompletableFuture.failedFuture(
                    new BridgeException("unsupported", "the last undo or redo is still waiting for a bot", true, null));
        }
        if (from.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new BridgeException("unsupported", undo ? "nothing to undo" : "nothing to redo"));
        }
        Entry entry = from.removeLast();
        version++;
        busy = true;
        CompletableFuture<HistoryEntry> out = new CompletableFuture<>();
        applyAll(entry.changes, 0, new ArrayList<>(), undo).whenComplete((changes, error) -> {
            busy = false;
            if (error != null) {
                // The entry no longer matches the objects; keeping it would fail the same way again.
                version++;
                save();
                out.completeExceptionally(error instanceof CompletionException c
                        && c.getCause() != null ? c.getCause() : error);
                return;
            }
            List<Change> original = entry.changes;
            entry.changes = List.copyOf(changes);
            for (int i = 0; i < original.size(); i++) {
                Image wanted = undo ? original.get(i).before() : original.get(i).after();
                Image written = undo ? changes.get(i).before() : changes.get(i).after();
                if (wanted != null && written != null && wanted.rev() != written.rev()) {
                    renameRev(original.get(i).key(), wanted.rev(), written.rev());
                }
            }
            to.add(entry);
            trim();
            version++;
            save();
            out.complete(entry.view(!undo));
        });
        return out;
    }

    private CompletableFuture<List<Change>> applyAll(List<Change> changes, int index, List<Change> updated,
            boolean undo) {
        if (index >= changes.size()) {
            return CompletableFuture.completedFuture(updated);
        }
        CompletableFuture<Change> one;
        try {
            one = apply(changes.get(index), undo);
        } catch (BridgeException | IllegalArgumentException | WireException e) {
            return CompletableFuture.failedFuture(e);
        }
        return one.thenCompose(c -> {
            updated.add(c);
            return applyAll(changes, index + 1, updated, undo);
        });
    }

    /** Writes the change's other state, returning the change with the written state's new revision. */
    private CompletableFuture<Change> apply(Change c, boolean undo) {
        Image expected = undo ? c.after() : c.before();
        Image wanted = undo ? c.before() : c.after();
        String key = c.key();
        Image cur = target.current(key);
        if (key.startsWith(ObjectKeys.SHARED_PREFIX)) {
            boolean matches = expected == null ? cur == null : cur != null && cur.rev() == expected.rev();
            if (!matches) {
                throw new BridgeException("conflict", key + " changed since this edit", false,
                        Map.of("rev", cur == null ? 0L : cur.rev()));
            }
            if (wanted == null) {
                target.sharedOp(new SharedOpBody.Remove(new SharedRemove(key, Objects.requireNonNull(cur).rev())));
            } else if (cur == null) {
                target.sharedOp(new SharedOpBody.Create(new SharedCreate(new ObjectFull(key, target.kindId(wanted.kindName()),
                        1, wanted.layer(), wanted.lifetime(), wanted.scope(), wanted.world(), null, wanted.data()))));
            } else {
                target.sharedOp(new SharedOpBody.Update(new SharedUpdate(key, cur.rev(), PatchApplier.fullPatch(wanted.data()))));
            }
            return CompletableFuture.completedFuture(updated(c, undo, target.current(key)));
        }
        if (key.startsWith(ObjectKeys.PLAYER_PREFIX)) {
            if (wanted == null) {
                target.removePlayer(key);
            } else {
                target.putPlayer(key, wanted);
            }
            return CompletableFuture.completedFuture(updated(c, undo, target.current(key)));
        }
        if (wanted == null || cur == null) {
            throw new BridgeException("unsupported", "a bot's object can only be edited, not created or removed");
        }
        long base = cur.rev();
        return target.propose(key, base, PatchApplier.fullPatch(wanted.data())).thenApply(r -> {
            if (!r.accepted()) {
                String reason = r.reason();
                throw new BridgeException("cancelled", reason == null || reason.isEmpty()
                        ? "the bot rejected the edit" : "the bot rejected the edit: " + reason);
            }
            return updated(c, undo, wanted.withRev(base + 1));
        });
    }

    /**
     * Restoring a state writes it at a new revision. Neighbouring entries refer to that state by its
     * old revision (the previous entry's after state is this entry's before state), so every image of
     * the key at the old revision is renamed; otherwise the next undo would see a false conflict.
     */
    private void renameRev(String key, long from, long to) {
        renameRev(done, key, from, to);
        renameRev(undone, key, from, to);
    }

    private static void renameRev(List<Entry> entries, String key, long from, long to) {
        for (Entry e : entries) {
            List<Change> next = null;
            for (int i = 0; i < e.changes.size(); i++) {
                Change c = e.changes.get(i);
                if (!c.key().equals(key)) {
                    continue;
                }
                Image before = c.before() != null && c.before().rev() == from ? c.before().withRev(to) : c.before();
                Image after = c.after() != null && c.after().rev() == from ? c.after().withRev(to) : c.after();
                if (before != c.before() || after != c.after()) {
                    if (next == null) {
                        next = new ArrayList<>(e.changes);
                    }
                    next.set(i, new Change(key, before, after));
                }
            }
            if (next != null) {
                e.changes = List.copyOf(next);
            }
        }
    }

    private static Change updated(Change c, boolean undo, @Nullable Image written) {
        return undo ? new Change(c.key(), written, c.after()) : new Change(c.key(), c.before(), written);
    }

    @Override
    public boolean canUndo() {
        return !done.isEmpty() && !busy;
    }

    @Override
    public boolean canRedo() {
        return !undone.isEmpty() && !busy;
    }

    @Override
    public List<HistoryEntry> entries() {
        List<HistoryEntry> out = new ArrayList<>(done.size() + undone.size());
        for (Entry e : done) {
            out.add(e.view(true));
        }
        for (int i = undone.size() - 1; i >= 0; i--) {
            out.add(undone.get(i).view(false));
        }
        return List.copyOf(out);
    }

    @Override
    public long version() {
        return version;
    }

    record Loaded(long nextId, List<Entry> done, List<Entry> undone) {}

    /** Encodes the current history: {@code [format, nextId, done[], undone[]]} in MessagePack. */
    byte[] encode() {
        return Encoding.write(1024, w -> {
            w.writeArrayHeader(4);
            w.writeUInt(FORMAT);
            w.writeInt(nextId);
            writeEntries(w, done);
            writeEntries(w, undone);
        });
    }

    private static void writeEntries(WireWriter w, List<Entry> entries) {
        w.writeArrayHeader(entries.size());
        for (Entry e : entries) {
            w.writeArrayHeader(4);
            w.writeInt(e.id);
            w.writeString(e.label);
            w.writeInt(e.timeMs);
            w.writeArrayHeader(e.changes.size());
            for (Change c : e.changes) {
                w.writeArrayHeader(3);
                w.writeString(c.key());
                writeImage(w, c.before());
                writeImage(w, c.after());
            }
        }
    }

    private static void writeImage(WireWriter w, @Nullable Image i) {
        if (i == null) {
            w.writeNil();
            return;
        }
        w.writeArrayHeader(7);
        w.writeString(i.kindName());
        if (i.layer() == null) {
            w.writeNil();
        } else {
            w.writeString(i.layer());
        }
        Lifetime.CODEC.write(w, i.lifetime());
        Scope.CODEC.write(w, i.scope());
        if (i.world() == null) {
            w.writeNil();
        } else {
            WorldKey.CODEC.write(w, i.world());
        }
        w.writeBytes(i.data());
        w.writeInt(i.rev());
    }

    /**
     * Decodes a saved history.
     *
     * @throws WireException when the bytes are malformed
     * @throws IllegalArgumentException when the format is unknown
     */
    static Loaded decode(byte[] bytes) {
        WireReader r = new WireReader(Unpooled.wrappedBuffer(bytes));
        int n = r.readArrayHeader();
        if (n < 4) {
            throw WireException.invalid("history: expected 4 fields");
        }
        int format = r.readUInt();
        if (format != FORMAT) {
            throw new IllegalArgumentException("unknown history format " + format);
        }
        long nextId = r.readLong();
        List<Entry> done = readEntries(r);
        List<Entry> undone = readEntries(r);
        r.skipValues(n - 4);
        return new Loaded(nextId, done, undone);
    }

    private static List<Entry> readEntries(WireReader r) {
        int n = r.readArrayHeader();
        List<Entry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int f = r.readArrayHeader();
            long id = r.readLong();
            String label = r.readString();
            long time = r.readLong();
            int m = r.readArrayHeader();
            List<Change> changes = new ArrayList<>(m);
            for (int j = 0; j < m; j++) {
                int cf = r.readArrayHeader();
                String key = r.readString();
                Image before = readImage(r);
                Image after = readImage(r);
                r.skipValues(cf - 3);
                changes.add(new Change(key, before, after));
            }
            r.skipValues(f - 4);
            out.add(new Entry(id, label, time, changes));
        }
        return out;
    }

    private static @Nullable Image readImage(WireReader r) {
        if (r.tryReadNil()) {
            return null;
        }
        int n = r.readArrayHeader();
        String kind = r.readString();
        String layer = r.tryReadNil() ? null : r.readString();
        Lifetime lifetime = Lifetime.CODEC.read(r);
        Scope scope = Scope.CODEC.read(r);
        WorldKey world = r.tryReadNil() ? null : WorldKey.CODEC.read(r);
        byte[] data = r.readBytes();
        long rev = r.readLong();
        r.skipValues(n - 7);
        return new Image(kind, layer, lifetime, scope, world, data, rev);
    }
}
