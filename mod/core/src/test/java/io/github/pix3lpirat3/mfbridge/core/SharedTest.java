package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.ObjectSyncTest.box;
import static io.github.pix3lpirat3.mfbridge.core.ObjectSyncTest.keys;
import static io.github.pix3lpirat3.mfbridge.core.ObjectSyncTest.label;
import static io.github.pix3lpirat3.mfbridge.core.ObjectSyncTest.patch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.world.ServerKeys;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjRemove;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjUpsert;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectFull;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectWrite;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedCreate;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOp;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpBody;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedRemove;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedSubscribe;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedUpdate;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.WaypointData;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldReport;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedTest {
    static final WorldKey W = new WorldKey("example.com", null, "minecraft:overworld");
    static final WorldKey NETHER = new WorldKey("example.com", null, "minecraft:the_nether");

    @TempDir
    Path dir;

    static SharedOp create(ObjectFull o) {
        return new SharedOp(new SharedOpBody.Create(new SharedCreate(o)));
    }

    static String upsertKey(FakePlugin.Frame f) {
        return ((ObjectWrite.Full) f.decode(CoreMessages.OBJ_UPSERT).writes().get(0)).value().key();
    }

    static FakePlugin.Frame awaitUpsert(FakePlugin p, int s, String key) {
        return p.await("upsert " + key + " on " + s, f -> f.is(CoreMessages.OBJ_UPSERT) && f.env().session() == s
                && f.decode(CoreMessages.OBJ_UPSERT).writes().stream()
                        .anyMatch(w -> w instanceof ObjectWrite.Full full && full.value().key().equals(key)));
    }

    static SharedOpResult result(FakePlugin p, int s, long req) {
        FakePlugin.Frame f = p.await("result " + req, x -> x.env().session() == s && x.env().req() == req
                && (x.is(CoreMessages.SHARED_OP_RESULT) || x.is(CoreMessages.ERROR)));
        return f.decode(CoreMessages.SHARED_OP_RESULT);
    }

    static ErrorMessage error(FakePlugin p, int s, long req) {
        FakePlugin.Frame f = p.await("error " + req, x -> x.env().session() == s && x.env().req() == req
                && x.is(CoreMessages.ERROR));
        assertTrue(f.env().has(Envelope.RESPONSE) && f.env().has(Envelope.ERROR));
        return f.decode(CoreMessages.ERROR);
    }

    @Test
    void sharedOpsConflictAndBroadcastByWorld() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "swarm")) {
            int a = p.openBot("Alpha", "example.com").sessionId();
            int b = p.openBot("Beta", "example.com").sessionId();
            p.control(a, CoreMessages.WORLD_REPORT, new WorldReport(W, null));
            p.control(b, CoreMessages.WORLD_REPORT, new WorldReport(NETHER, null));
            p.reliable(a, CoreMessages.SHARED_SUBSCRIBE, new SharedSubscribe(List.of(), List.of()));
            p.reliable(b, CoreMessages.SHARED_SUBSCRIBE, new SharedSubscribe(List.of(), List.of()));

            long r1 = p.reliableRequest(a, CoreMessages.SHARED_OP, create(
                    FakePlugin.worldObject("shared/zones/z1", CoreKinds.LABEL, 0, W, label("zone"))));
            assertEquals(new SharedOpResult("shared/zones/z1", 1), result(p, a, r1));
            awaitUpsert(p, a, "shared/zones/z1");

            long r2 = p.reliableRequest(a, CoreMessages.SHARED_OP, create(
                    FakePlugin.object("shared/zones/g", CoreKinds.LABEL, 0, label("global"))));
            assertEquals(1, result(p, a, r2).rev());
            awaitUpsert(p, a, "shared/zones/g");
            awaitUpsert(p, b, "shared/zones/g");
            p.sync();
            assertFalse(p.poll(f -> f.env().session() == b && f.is(CoreMessages.OBJ_UPSERT)
                    && upsertKey(f).equals("shared/zones/z1")), "other world does not get world-scoped objects");

            long r3 = p.reliableRequest(a, CoreMessages.SHARED_OP, create(
                    FakePlugin.object("shared/zones/g", CoreKinds.LABEL, 0, label("again"))));
            ErrorMessage dup = error(p, a, r3);
            assertEquals("conflict", dup.error().code());
            Map<?, ?> details = (Map<?, ?>) dup.error().details();
            assertEquals(1L, details.get("rev"));
            // The current object travels as its ObjectFull field array.
            List<?> current = (List<?>) details.get("object");
            assertEquals("shared/zones/g", current.get(0));
            assertEquals((long) CoreKinds.LABEL.id(), current.get(1));
            assertEquals(1L, current.get(2));
            assertEquals("global", CoreKinds.LABEL.decode((byte[]) current.get(8)).text());
            assertEquals(CoreMessages.SHARED_OP.id(), dup.relatesTo());

            long r4 = p.reliableRequest(b, CoreMessages.SHARED_OP, new SharedOp(new SharedOpBody.Update(
                    new SharedUpdate("shared/zones/g", 5, patch(1, "x")))));
            assertEquals(1L, ((Map<?, ?>) error(p, b, r4).error().details()).get("rev"));

            long r5 = p.reliableRequest(b, CoreMessages.SHARED_OP, new SharedOp(new SharedOpBody.Update(
                    new SharedUpdate("shared/zones/g", 1, patch(1, "patched")))));
            assertEquals(2, result(p, b, r5).rev());
            FakePlugin.Frame up = awaitUpsert(p, a, "shared/zones/g");
            ObjectFull full = ((ObjectWrite.Full) up.decode(CoreMessages.OBJ_UPSERT).writes().get(0)).value();
            assertEquals(2, full.rev());
            assertEquals("patched", CoreKinds.LABEL.decode(full.data()).text());
            awaitUpsert(p, b, "shared/zones/g");

            long r6 = p.reliableRequest(a, CoreMessages.SHARED_OP, new SharedOp(new SharedOpBody.Remove(
                    new SharedRemove("shared/zones/g", 2))));
            assertEquals(3, result(p, a, r6).rev());
            for (int s : new int[] {a, b}) {
                ObjRemove rm = p.await("remove on " + s, f -> f.is(CoreMessages.OBJ_REMOVE) && f.env().session() == s)
                        .decode(CoreMessages.OBJ_REMOVE);
                assertEquals(List.of("shared/zones/g"), rm.keys());
            }

            // Moving into the zone's world delivers it.
            p.control(b, CoreMessages.WORLD_REPORT, new WorldReport(W, null));
            awaitUpsert(p, b, "shared/zones/z1");

            h.core.setPlayerContext(W);
            h.tick();
            assertTrue(keys(h).contains("shared/zones/z1"), "shared objects render without an attached owner");
        }
    }

    @Test
    void kindFilterLimitsBroadcasts() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Alpha", "example.com").sessionId();
            p.reliable(s, CoreMessages.SHARED_SUBSCRIBE, new SharedSubscribe(List.of("core:box"), List.of()));
            long r1 = p.reliableRequest(s, CoreMessages.SHARED_OP, create(
                    FakePlugin.object("shared/z/label", CoreKinds.LABEL, 0, label("l"))));
            long r2 = p.reliableRequest(s, CoreMessages.SHARED_OP, create(
                    FakePlugin.object("shared/z/box", CoreKinds.BOX, 0, box(1))));
            result(p, s, r1);
            result(p, s, r2);
            awaitUpsert(p, s, "shared/z/box");
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.OBJ_UPSERT) && upsertKey(f).equals("shared/z/label")));
        }
    }

    @Test
    void sharedAndPlayerObjectsSurviveRestart() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Alpha", "example.com").sessionId();
            p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(W, null));
            long r1 = p.reliableRequest(s, CoreMessages.SHARED_OP, create(
                    FakePlugin.worldObject("shared/zones/z1", CoreKinds.LABEL, 0, W, label("zone"))));
            result(p, s, r1);
            long r2 = p.reliableRequest(s, CoreMessages.SHARED_OP, new SharedOp(new SharedOpBody.Update(
                    new SharedUpdate("shared/zones/z1", 1, patch(1, "zone v2")))));
            assertEquals(2, result(p, s, r2).rev());
            long r3 = p.reliableRequest(s, CoreMessages.SHARED_OP, create(
                    FakePlugin.object("shared/zones/tmp", CoreKinds.LABEL, 0, label("tmp"))));
            result(p, s, r3);
            long r4 = p.reliableRequest(s, CoreMessages.SHARED_OP, new SharedOp(new SharedOpBody.Remove(
                    new SharedRemove("shared/zones/tmp", 1))));
            result(p, s, r4);
            h.core.putPlayerObject("player/waypoints/home", CoreKinds.WAYPOINT, null, Scope.GLOBAL, null,
                    CoreKinds.WAYPOINT.encode(new WaypointData(new Vec3d(1, 2, 3), "Home").withBeam(true)));
        }
        assertTrue(Files.exists(dir.resolve("mineflayer_bridge/data").resolve(ServerKeys.directoryName("example.com:25565"))
                .resolve("ops.log")));

        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Alpha", "example.com").sessionId();
            p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(W, null));
            p.reliable(s, CoreMessages.SHARED_SUBSCRIBE, new SharedSubscribe(List.of(), List.of()));
            FakePlugin.Frame f = awaitUpsert(p, s, "shared/zones/z1");
            ObjUpsert u = f.decode(CoreMessages.OBJ_UPSERT);
            ObjectFull z1 = u.writes().stream().map(w -> ((ObjectWrite.Full) w).value())
                    .filter(o -> o.key().equals("shared/zones/z1")).findFirst().orElseThrow();
            assertEquals(2, z1.rev());
            assertEquals("zone v2", CoreKinds.LABEL.decode(z1.data()).text());
            assertEquals(W, z1.world());
            p.sync();
            assertFalse(p.poll(x -> x.is(CoreMessages.OBJ_UPSERT) && upsertKey(x).equals("shared/zones/tmp")));

            h.tickUntil("player store loaded", () -> h.core.storesLoaded() && keys(h).contains("player/waypoints/home"));
            RenderEntry home = ObjectSyncTest.entry(h, "player/waypoints/home");
            assertEquals("Home", ((WaypointData) home.data()).name());

            long r = p.reliableRequest(s, CoreMessages.SHARED_OP, create(
                    FakePlugin.worldObject("shared/zones/z1", CoreKinds.LABEL, 0, W, label("dup"))));
            assertEquals("conflict", error(p, s, r).error().code());
            assertTrue(h.core.removePlayerObject("player/waypoints/home"));
        }
    }

    @Test
    void budgetedTickLeavesWorkForLaterTicks() throws Exception {
        try (Harness h = new Harness(dir, b -> b.attachPolicy(AttachPolicy.ALWAYS), DeviceApprover.ALLOW_ONCE);
                FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Alpha", "example.com").sessionId();
            p.sync();
            for (int i = 0; i < 50; i++) {
                p.upsert(s, FakePlugin.object("bot:alpha@example.com/e/" + i, CoreKinds.BOX, 1, box(i)));
            }
            long deadline = System.currentTimeMillis() + Harness.TIMEOUT_MS;
            while (h.core.queuedInboundFrames() < 50 && System.currentTimeMillis() < deadline) {
                Harness.sleep();
            }
            assertEquals(50, h.core.queuedInboundFrames());
            h.core.tick(0);
            assertEquals(1, keys(h).size(), "a zero budget still makes progress, one frame per tick");
            assertEquals(49, h.core.queuedInboundFrames());
            h.core.tick(0);
            assertEquals(2, keys(h).size());
            h.core.tick(10_000_000_000L);
            assertEquals(50, keys(h).size());
            assertEquals(0, h.core.queuedInboundFrames());
        }
    }
}
