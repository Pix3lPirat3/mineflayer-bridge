package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.protocol.core.BoxData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.Interest;
import io.github.pix3lpirat3.mfbridge.protocol.core.LabelData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Lifetime;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjRemove;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjSnapshotBegin;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjSnapshotEnd;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjUpsert;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectFull;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectPatch;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectWrite;
import io.github.pix3lpirat3.mfbridge.protocol.core.PlayerContext;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObjectSyncTest {
    static final String P = "bot:miner@example.com/";

    @TempDir
    Path dir;

    static byte[] box(double x) {
        return CoreKinds.BOX.encode(new BoxData(new Vec3d(x, 0, 0), new Vec3d(1, 1, 1), null));
    }

    static byte[] label(String text) {
        return CoreKinds.LABEL.encode(new LabelData(new Vec3d(0, 64, 0), text, 1, 0x40000000, 64, null));
    }

    static byte[] patch(int index, String value) {
        ByteBuf buf = Unpooled.buffer();
        WireWriter w = new WireWriter(buf);
        w.writeMapHeader(1);
        w.writeUInt(index);
        w.writeString(value);
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    static Set<String> keys(Harness h) {
        Set<String> out = new TreeSet<>();
        for (RenderEntry e : h.core.renderSnapshot().entries()) {
            out.add(e.key());
        }
        return out;
    }

    static RenderEntry entry(Harness h, String key) {
        for (RenderEntry e : h.core.renderSnapshot().entries()) {
            if (e.key().equals(key)) {
                return e;
            }
        }
        throw new AssertionError("no entry " + key + " in " + keys(h));
    }

    static Harness always(Path dir) throws Exception {
        return new Harness(dir, b -> b.attachPolicy(AttachPolicy.ALWAYS), DeviceApprover.ALLOW_ONCE);
    }

    @Test
    void sessionOpenSendsPlayerContextAndInterest() throws Exception {
        try (Harness h = always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            assertEquals(1, s);
            PlayerContext pc = p.await(CoreMessages.PLAYER_CONTEXT, s);
            assertFalse(pc.inWorld());
            assertNull(pc.world());
            assertTrue(pc.attached());
            Interest in = p.await(CoreMessages.INTEREST, s);
            assertTrue(in.attached());
            assertFalse(in.worldMatched());
            assertEquals(List.of(), in.layers());
            assertEquals(P, h.core.sessionStats().get(0).ownerPrefix());
        }
    }

    @Test
    void snapshotEndRemovesStaleObjects() throws Exception {
        try (Harness h = always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.reliable(s, CoreMessages.OBJ_SNAPSHOT_BEGIN, new ObjSnapshotBegin());
            p.upsert(s, FakePlugin.object(P + "e/a", CoreKinds.BOX, 5, box(1)),
                    FakePlugin.object(P + "e/b", CoreKinds.BOX, 5, box(2)));
            p.reliable(s, CoreMessages.OBJ_SNAPSHOT_END, new ObjSnapshotEnd());
            h.tickUntil("two objects", () -> keys(h).size() == 2);

            p.reliable(s, CoreMessages.OBJ_SNAPSHOT_BEGIN, new ObjSnapshotBegin());
            p.upsert(s, FakePlugin.object(P + "e/a", CoreKinds.BOX, 5, box(1)));
            p.reliable(s, CoreMessages.OBJ_SNAPSHOT_END, new ObjSnapshotEnd());
            h.tickUntil("b removed", () -> keys(h).equals(Set.of(P + "e/a")));
        }
        Path dir2 = dir.resolve("second");
        try (Harness h = always(dir2); FakePlugin p1 = FakePlugin.ready(h, "dev")) {
            int s1 = p1.openBot("Miner", "example.com").sessionId();
            p1.upsert(s1, FakePlugin.object(P + "e/old", CoreKinds.BOX, 9, box(1)));
            h.tickUntil("old object", () -> keys(h).contains(P + "e/old"));
            p1.abort();
            try (FakePlugin p2 = FakePlugin.ready(h, "dev")) {
                int s2 = p2.openBot("Miner", "example.com").sessionId();
                p2.reliable(s2, CoreMessages.OBJ_SNAPSHOT_BEGIN, new ObjSnapshotBegin());
                p2.upsert(s2, FakePlugin.object(P + "e/new", CoreKinds.BOX, 1, box(3)),
                        FakePlugin.object(P + "e/old", CoreKinds.BOX, 1, box(4)));
                p2.reliable(s2, CoreMessages.OBJ_SNAPSHOT_END, new ObjSnapshotEnd());
                h.tickUntil("new snapshot replaces old state",
                        () -> keys(h).equals(Set.of(P + "e/new", P + "e/old")) && entry(h, P + "e/old").rev() == 1);
                assertEquals(4.0, ((BoxData) entry(h, P + "e/old").data()).min().x());
            }
        }
    }

    @Test
    void fullWritesRequireIncreasingRev() throws Exception {
        try (Harness h = always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.upsert(s, FakePlugin.object(P + "e/x", CoreKinds.BOX, 2, box(1)));
            p.upsert(s, FakePlugin.object(P + "e/x", CoreKinds.BOX, 1, box(2)));
            p.upsert(s, FakePlugin.object(P + "e/x", CoreKinds.BOX, 2, box(3)));
            p.sync();
            assertEquals(1.0, ((BoxData) entry(h, P + "e/x").data()).min().x());
            p.upsert(s, FakePlugin.object(P + "e/x", CoreKinds.BOX, 3, box(4)));
            p.sync();
            assertEquals(4.0, ((BoxData) entry(h, P + "e/x").data()).min().x());
            assertEquals(3, entry(h, P + "e/x").rev());
        }
    }

    @Test
    void patchReplacesFieldsAndMissingKeyIsAnError() throws Exception {
        try (Harness h = always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.upsert(s, FakePlugin.object(P + "e/l", CoreKinds.LABEL, 1, label("hi")));
            p.reliable(s, CoreMessages.OBJ_UPSERT, new ObjUpsert(List.of(
                    new ObjectWrite.Patch(new ObjectPatch(P + "e/l", 2, patch(1, "bye"))))));
            p.sync();
            RenderEntry e = entry(h, P + "e/l");
            assertEquals("bye", ((LabelData) e.data()).text());
            assertEquals(2, e.rev());

            p.reliable(s, CoreMessages.OBJ_UPSERT, new ObjUpsert(List.of(
                    new ObjectWrite.Patch(new ObjectPatch(P + "e/l", 2, patch(1, "stale"))))));
            p.reliable(s, CoreMessages.OBJ_UPSERT, new ObjUpsert(List.of(
                    new ObjectWrite.Patch(new ObjectPatch(P + "e/missing", 1, patch(0, "x"))))));
            ErrorMessage err = p.await(CoreMessages.ERROR, s);
            assertEquals("invalid_message", err.error().code());
            assertEquals(CoreMessages.OBJ_UPSERT.id(), err.relatesTo());
            assertEquals("bye", ((LabelData) entry(h, P + "e/l").data()).text());
        }
    }

    @Test
    void removeByKeysAndPrefixes() throws Exception {
        try (Harness h = always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.upsert(s, FakePlugin.object(P + "ext/a", CoreKinds.BOX, 1, box(1)),
                    FakePlugin.object(P + "ext/b", CoreKinds.BOX, 1, box(1)),
                    FakePlugin.object(P + "other/c", CoreKinds.BOX, 1, box(1)),
                    FakePlugin.object(P + "other/d", CoreKinds.BOX, 1, box(1)));
            h.tickUntil("four", () -> keys(h).size() == 4);
            p.reliable(s, CoreMessages.OBJ_REMOVE, new ObjRemove(List.of(P + "other/d"), List.of(P + "ext/")));
            h.tickUntil("only c left", () -> keys(h).equals(Set.of(P + "other/c")));
        }
    }

    @Test
    void writesOutsideOwnedPrefixAreNotOwner() throws Exception {
        try (Harness h = always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.upsert(s, FakePlugin.object("bot:other@example.com/e/a", CoreKinds.BOX, 1, box(1)));
            ErrorMessage e1 = p.await(CoreMessages.ERROR, s);
            assertEquals("not_owner", e1.error().code());
            p.upsert(s, FakePlugin.object("shared/e/a", CoreKinds.BOX, 1, box(1)));
            assertEquals("not_owner", p.await(CoreMessages.ERROR, s).error().code());
            p.reliable(s, CoreMessages.OBJ_REMOVE, new ObjRemove(List.of(), List.of("bot:")));
            ErrorMessage e2 = p.await(CoreMessages.ERROR, s);
            assertEquals("not_owner", e2.error().code());
            assertEquals(CoreMessages.OBJ_REMOVE.id(), e2.relatesTo());
            assertTrue(keys(h).isEmpty());
        }
    }

    @Test
    void ttlCountsFromReceiptAndWritesRefreshIt() throws Exception {
        try (Harness h = always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            ObjectFull ttl = new ObjectFull(P + "e/t", CoreKinds.BOX.id(), 1, null, Lifetime.SESSION, Scope.GLOBAL,
                    null, 1000, box(1));
            p.upsert(s, ttl);
            p.sync();
            assertTrue(keys(h).contains(P + "e/t"));
            h.clock.advanceMs(600);
            p.upsert(s, new ObjectFull(P + "e/t", CoreKinds.BOX.id(), 2, null, Lifetime.SESSION, Scope.GLOBAL,
                    null, 1000, box(2)));
            p.sync();
            h.clock.advanceMs(600);
            h.tick();
            assertTrue(keys(h).contains(P + "e/t"), "refreshed by the second write");
            h.clock.advanceMs(401);
            h.tick();
            assertFalse(keys(h).contains(P + "e/t"));
        }
    }

    @Test
    void controllersOwnTheirDevicePrefix() throws Exception {
        try (Harness h = always(dir); FakePlugin p = FakePlugin.ready(h, "dev-9")) {
            long req = p.request(0, CoreMessages.SESSION_OPEN, new SessionOpen(SessionKind.CONTROLLER, "mgr", null, List.of()));
            int s = p.await("opened", f -> f.is(CoreMessages.SESSION_OPENED) && f.env().req() == req)
                    .decode(CoreMessages.SESSION_OPENED).sessionId();
            assertTrue(p.await(CoreMessages.INTEREST, s).attached(), "controllers are always attached");
            p.upsert(s, FakePlugin.object("ctl:dev-9/mgr/dash/a", CoreKinds.BOX, 1, box(1)));
            p.upsert(s, FakePlugin.object("ctl:dev-9/other/dash/a", CoreKinds.BOX, 1, box(1)));
            assertEquals("not_owner", p.await(CoreMessages.ERROR, s).error().code());
            assertEquals(Set.of("ctl:dev-9/mgr/dash/a"), keys(h));
        }
    }

    @Test
    void newerSessionSupersedesOlderOne() throws Exception {
        // The same device on a second connection; another device would be refused the identity.
        try (Harness h = always(dir); FakePlugin old = FakePlugin.ready(h, "a"); FakePlugin neu = FakePlugin.ready(h, "a")) {
            int s1 = old.openBot("Miner", "example.com").sessionId();
            old.upsert(s1, FakePlugin.object(P + "e/a", CoreKinds.BOX, 1, box(1)));
            old.sync();
            int s2 = neu.openBot("Miner", "example.com").sessionId();
            ErrorMessage told = old.await(CoreMessages.ERROR, s1);
            assertEquals("superseded", told.error().code());
            old.upsert(s1, FakePlugin.object(P + "e/b", CoreKinds.BOX, 1, box(1)));
            assertEquals("superseded", old.await(CoreMessages.ERROR, s1).error().code());
            neu.upsert(s2, FakePlugin.object(P + "e/c", CoreKinds.BOX, 1, box(1)));
            neu.sync();
            assertEquals(Set.of(P + "e/a", P + "e/c"), keys(h));
            old.abort();
            h.tickUntil("old session gone", () -> h.core.sessionStats().size() == 1);
            assertEquals(Set.of(P + "e/a", P + "e/c"), keys(h), "a superseded session never drops the writer's objects");
        }
    }

    @Test
    void sequenceGapClosesTheSession() throws Exception {
        try (Harness h = always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.upsert(s, FakePlugin.object(P + "e/a", CoreKinds.BOX, 1, box(1)));
            p.setNextSeq(s, 5);
            p.upsert(s, FakePlugin.object(P + "e/b", CoreKinds.BOX, 1, box(1)));
            assertEquals("invalid_message", p.await(CoreMessages.ERROR, s).error().code());
            assertEquals("invalid_message", p.await(CoreMessages.SESSION_CLOSE, s).reason());
            h.tickUntil("objects dropped with the session", () -> keys(h).isEmpty());
            assertTrue(h.core.sessionStats().isEmpty());
        }
    }
}
