package io.github.pix3lpirat3.mfbridge.core.history;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.core.store.PatchApplier;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.Lifetime;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjEditResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectFull;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedCreate;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpBody;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedRemove;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedUpdate;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.PointData;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class EditHistoryTest {
    static final WorldKey W = new WorldKey("example.com:25565", null, "minecraft:overworld");

    /** An in-memory store with the engine's shared op rules and a scripted bot. */
    static final class FakeTarget implements EditHistory.Target {
        final Map<String, EditHistory.Image> objects = new HashMap<>();
        final List<CompletableFuture<ObjEditResult>> proposals = new ArrayList<>();
        final List<byte[]> proposed = new ArrayList<>();

        @Override
        public EditHistory.@Nullable Image current(String key) {
            return objects.get(key);
        }

        @Override
        public SharedOpResult sharedOp(SharedOpBody op) {
            return switch (op) {
                case SharedOpBody.Create c -> {
                    ObjectFull f = c.value().object();
                    if (objects.containsKey(f.key())) {
                        throw new BridgeException("conflict", "exists");
                    }
                    objects.put(f.key(), new EditHistory.Image(CoreKinds.byId(f.kind()).name(), f.layer(), f.lifetime(),
                            f.scope(), f.world(), f.data(), 1));
                    yield new SharedOpResult(f.key(), 1);
                }
                case SharedOpBody.Update u -> {
                    EditHistory.Image cur = objects.get(u.value().key());
                    if (cur == null || cur.rev() != u.value().expectRev()) {
                        throw new BridgeException("conflict", "rev");
                    }
                    EditHistory.Image next = new EditHistory.Image(cur.kindName(), cur.layer(), cur.lifetime(), cur.scope(),
                            cur.world(), PatchApplier.apply(cur.data(), u.value().fields()), cur.rev() + 1);
                    objects.put(u.value().key(), next);
                    yield new SharedOpResult(u.value().key(), next.rev());
                }
                case SharedOpBody.Remove r -> {
                    EditHistory.Image cur = objects.get(r.value().key());
                    if (cur == null || cur.rev() != r.value().expectRev()) {
                        throw new BridgeException("conflict", "rev");
                    }
                    objects.remove(r.value().key());
                    yield new SharedOpResult(r.value().key(), cur.rev() + 1);
                }
            };
        }

        @Override
        public int kindId(String kindName) {
            return CoreKinds.POINT.name().equals(kindName) ? CoreKinds.POINT.id() : -1;
        }

        @Override
        public void putPlayer(String key, EditHistory.Image image) {
            EditHistory.Image cur = objects.get(key);
            objects.put(key, new EditHistory.Image(image.kindName(), image.layer(), image.lifetime(), image.scope(),
                    image.world(), image.data(), cur == null ? 1 : cur.rev() + 1));
        }

        @Override
        public boolean removePlayer(String key) {
            return objects.remove(key) != null;
        }

        @Override
        public CompletableFuture<ObjEditResult> propose(String key, long baseRev, byte[] fields) {
            CompletableFuture<ObjEditResult> f = new CompletableFuture<>();
            proposals.add(f);
            proposed.add(fields);
            return f;
        }

        /** The bot applies the newest proposal and answers. */
        void botAnswers(String key, boolean accept) {
            if (accept) {
                EditHistory.Image cur = objects.get(key);
                objects.put(key, new EditHistory.Image(cur.kindName(), cur.layer(), cur.lifetime(), cur.scope(), cur.world(),
                        PatchApplier.apply(cur.data(), proposed.getLast()), cur.rev() + 1));
            }
            proposals.getLast().complete(new ObjEditResult(accept, accept ? null : "busy"));
        }
    }

    static final class MemoryStorage implements EditHistory.Storage {
        final Map<String, byte[]> files = new HashMap<>();

        @Override
        public CompletableFuture<byte @Nullable []> load(String storeKey) {
            return CompletableFuture.completedFuture(files.get(storeKey));
        }

        @Override
        public void save(String storeKey, byte[] bytes) {
            files.put(storeKey, bytes);
        }
    }

    static byte[] point(String name, double x) {
        return CoreKinds.POINT.encode(new PointData(name, new Vec3d(x, 64, 0)));
    }

    static String name(FakeTarget t, String key) {
        EditHistory.Image i = t.objects.get(key);
        return i == null ? "<none>" : CoreKinds.POINT.decode(i.data()).name();
    }

    static SharedOpBody create(String key, byte[] data) {
        return new SharedOpBody.Create(new SharedCreate(new ObjectFull(key, CoreKinds.POINT.id(), 0, null, Lifetime.PERSISTENT,
                Scope.WORLD, W, null, data)));
    }

    static SharedOpBody update(String key, long rev, byte[] data) {
        return new SharedOpBody.Update(new SharedUpdate(key, rev, PatchApplier.fullPatch(data)));
    }

    static <T> T join(CompletableFuture<T> f) {
        assertTrue(f.isDone());
        return f.join();
    }

    static BridgeException failure(CompletableFuture<?> f) {
        assertTrue(f.isCompletedExceptionally());
        try {
            f.get();
            throw new AssertionError("not failed");
        } catch (ExecutionException e) {
            return (BridgeException) e.getCause();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void sharedCreateUpdateRemoveUndoAndRedo() {
        FakeTarget t = new FakeTarget();
        EditHistory h = new EditHistory(t, new MemoryStorage(), () -> 1000L, 500);
        String k = "shared/bridge:zones/p";
        h.sharedOp("Create point", create(k, point("A", 1)));
        h.sharedOp("Rename point", update(k, 1, point("B", 1)));
        h.sharedOp("Move point", update(k, 2, point("B", 5)));
        assertEquals(3, h.entries().size());
        assertTrue(h.canUndo());
        assertFalse(h.canRedo());

        HistoryEntry moved = join(h.undo());
        assertEquals("Move point", moved.label());
        assertFalse(moved.applied());
        assertEquals(1.0, CoreKinds.POINT.decode(t.objects.get(k).data()).pos().x());
        assertEquals(4, t.objects.get(k).rev(), "an undo is a new write, never a rewind of the revision");
        join(h.undo());
        assertEquals("A", name(t, k));
        join(h.undo());
        assertNull(t.objects.get(k), "undoing a create removes the object");
        assertFalse(h.canUndo());
        assertEquals("unsupported", failure(h.undo()).code());

        join(h.redo());
        assertEquals("A", name(t, k));
        assertEquals(1, t.objects.get(k).rev());
        join(h.redo());
        join(h.redo());
        assertEquals(5.0, CoreKinds.POINT.decode(t.objects.get(k).data()).pos().x());
        assertEquals("B", name(t, k));

        h.sharedOp("Delete point", new SharedOpBody.Remove(new SharedRemove(k, t.objects.get(k).rev())));
        assertNull(t.objects.get(k));
        join(h.undo());
        assertEquals("B", name(t, k), "undoing a remove recreates the object");
        assertEquals(Scope.WORLD, t.objects.get(k).scope());
        assertEquals(W, t.objects.get(k).world());
    }

    @Test
    void conflictDropsTheStaleEntry() {
        FakeTarget t = new FakeTarget();
        EditHistory h = new EditHistory(t, new MemoryStorage(), () -> 0L, 500);
        String k = "shared/bridge:zones/p";
        h.sharedOp("Create", create(k, point("A", 1)));
        h.sharedOp("Rename", update(k, 1, point("B", 1)));
        // A bot changes the point behind the player's back.
        t.sharedOp(update(k, 2, point("Bot", 1)));
        assertEquals("conflict", failure(h.undo()).code());
        assertEquals("Bot", name(t, k));
        assertEquals(1, h.entries().size());
        assertFalse(h.canRedo());
    }

    @Test
    void newEditClearsRedoAndCapacityDropsTheOldest() {
        FakeTarget t = new FakeTarget();
        EditHistory h = new EditHistory(t, new MemoryStorage(), () -> 0L, 3);
        for (int i = 0; i < 5; i++) {
            h.putPlayerObject("Edit " + i, "player/bridge:waypoints/w", CoreKinds.POINT, null, Scope.GLOBAL, null,
                    point("v" + i, i));
        }
        List<HistoryEntry> e = h.entries();
        assertEquals(List.of("Edit 2", "Edit 3", "Edit 4"), e.stream().map(HistoryEntry::label).toList());
        join(h.undo());
        assertEquals("v3", name(t, "player/bridge:waypoints/w"));
        assertTrue(h.canRedo());
        h.putPlayerObject("Edit 5", "player/bridge:waypoints/w", CoreKinds.POINT, null, Scope.GLOBAL, null, point("v5", 5));
        assertFalse(h.canRedo());
        assertTrue(h.removePlayerObject("Delete", "player/bridge:waypoints/w"));
        assertFalse(h.removePlayerObject("Delete again", "player/bridge:waypoints/w"));
        join(h.undo());
        assertEquals("v5", name(t, "player/bridge:waypoints/w"));
        join(h.undo());
        join(h.undo());
        assertEquals("v2", name(t, "player/bridge:waypoints/w"));
        assertFalse(h.canUndo(), "only three entries are kept");
    }

    @Test
    void botEditsRecordOnAcceptAndUndoByProposal() {
        FakeTarget t = new FakeTarget();
        EditHistory h = new EditHistory(t, new MemoryStorage(), () -> 0L, 500);
        String k = "bot:alpha@example.com/bridge:zones/p";
        t.objects.put(k, new EditHistory.Image(CoreKinds.POINT.name(), null, Lifetime.SESSION, Scope.GLOBAL, null,
                point("Old", 1), 7));
        CompletableFuture<ObjEditResult> rejected = h.proposeEdit("Rename", k, 7, PatchApplier.fullPatch(point("Nope", 1)));
        t.botAnswers(k, false);
        assertFalse(join(rejected).accepted());
        assertFalse(h.canUndo(), "a rejected proposal is not recorded");

        CompletableFuture<ObjEditResult> accepted = h.proposeEdit("Rename", k, 7, PatchApplier.fullPatch(point("New", 1)));
        assertFalse(h.canUndo(), "pending until the bot answers");
        t.botAnswers(k, true);
        assertTrue(join(accepted).accepted());
        assertEquals("New", name(t, k));
        assertTrue(h.canUndo());

        CompletableFuture<HistoryEntry> undo = h.undo();
        assertFalse(undo.isDone());
        assertFalse(h.canUndo(), "busy while the bot decides");
        assertEquals("unsupported", failure(h.redo()).code());
        assertArrayEquals(PatchApplier.fullPatch(point("Old", 1)), t.proposed.getLast());
        t.botAnswers(k, true);
        assertFalse(join(undo).applied());
        assertEquals("Old", name(t, k));

        CompletableFuture<HistoryEntry> redo = h.redo();
        t.botAnswers(k, false);
        assertEquals("cancelled", failure(redo).code());
        assertEquals("Old", name(t, k));
    }

    @Test
    void historyPersistsPerStoreAndMergesEditsMadeWhileLoading() {
        FakeTarget t = new FakeTarget();
        MemoryStorage storage = new MemoryStorage();
        EditHistory h = new EditHistory(t, storage, () -> 42L, 500);
        h.switchStore("example.com_25565");
        h.poll();
        String k = "shared/bridge:zones/p";
        h.sharedOp("Create", create(k, point("A", 1)));
        h.sharedOp("Rename", update(k, 1, point("B", 1)));
        join(h.undo());
        assertNotNull(storage.files.get("example.com_25565"));

        h.switchStore("other.net_25565");
        h.poll();
        assertTrue(h.entries().isEmpty(), "each world store has its own history");
        h.switchStore("example.com_25565");
        h.poll();
        List<HistoryEntry> back = h.entries();
        assertEquals(2, back.size());
        assertTrue(back.get(0).applied());
        assertFalse(back.get(1).applied());
        assertEquals(42L, back.get(0).timeMs());
        assertEquals(List.of(k), back.get(0).keys());
        join(h.redo());
        assertEquals("B", name(t, k));

        CompletableFuture<byte @Nullable []> slow = new CompletableFuture<>();
        EditHistory.Storage delayed = new EditHistory.Storage() {
            @Override
            public CompletableFuture<byte @Nullable []> load(String storeKey) {
                return slow;
            }

            @Override
            public void save(String storeKey, byte[] bytes) {
                storage.save(storeKey, bytes);
            }
        };
        byte[] saved = storage.files.get("example.com_25565");
        EditHistory restarted = new EditHistory(t, delayed, () -> 0L, 500);
        restarted.switchStore("example.com_25565");
        restarted.sharedOp("Rename again", update(k, t.objects.get(k).rev(), point("C", 1)));
        slow.complete(saved);
        restarted.poll();
        assertEquals(List.of("Create", "Rename", "Rename again"),
                restarted.entries().stream().map(HistoryEntry::label).toList());
        join(restarted.undo());
        join(restarted.undo());
        assertEquals("A", name(t, k));
        assertEquals(3, EditHistory.decode(storage.files.get("example.com_25565")).done().size()
                + EditHistory.decode(storage.files.get("example.com_25565")).undone().size());
    }
}
