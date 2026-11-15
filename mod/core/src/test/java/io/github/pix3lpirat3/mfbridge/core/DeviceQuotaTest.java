package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.ObjectSyncTest.box;
import static io.github.pix3lpirat3.mfbridge.core.ObjectSyncTest.keys;
import static io.github.pix3lpirat3.mfbridge.core.ObjectSyncTest.label;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.core.remote.Quota;
import io.github.pix3lpirat3.mfbridge.protocol.core.BotIdentity;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormField;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.FieldType;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjRemove;
import io.github.pix3lpirat3.mfbridge.protocol.core.PathData;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedCreate;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpBody;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Quotas are counted per device, so more sessions do not buy more. */
class DeviceQuotaTest {
    static final String A = "bot:alpha@example.com/";
    static final String B = "bot:beta@example.com/";

    @TempDir
    Path dir;

    static String quotaError(FakePlugin p, int s) {
        ErrorMessage e = p.await(CoreMessages.ERROR, s);
        assertEquals("quota_exceeded", e.error().code(), e.error().message());
        return e.error().message();
    }

    @Test
    void aDeviceHoldsOnlySoManySessions() throws Exception {
        try (Harness h = new Harness(dir, b -> b.maxSessionsPerDevice(2), DeviceApprover.ALLOW_ONCE);
                FakePlugin p = FakePlugin.ready(h, "dev"); FakePlugin same = FakePlugin.ready(h, "dev");
                FakePlugin other = FakePlugin.ready(h, "dev-2")) {
            p.openBot("Alpha", "example.com");
            same.openBot("Beta", "example.com");
            for (FakePlugin conn : List.of(p, same)) {
                long req = conn.request(0, CoreMessages.SESSION_OPEN, new SessionOpen(SessionKind.BOT, "Gamma",
                        new BotIdentity("Gamma", null, "example.com"), List.of()));
                FakePlugin.Frame f = conn.await("error", x -> x.is(CoreMessages.ERROR) && x.env().req() == req);
                assertEquals("quota_exceeded", f.decode(CoreMessages.ERROR).error().code(), "on any of its connections");
            }
            other.openBot("Gamma", "example.com");
        }
    }

    @Test
    void objectsAreCountedOverAllSessionsOfADevice() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> b.maxObjectsPerSession(3)); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int a = p.openBot("Alpha", "example.com").sessionId();
            int b = p.openBot("Beta", "example.com").sessionId();
            p.upsert(a, FakePlugin.object(A + "e/1", CoreKinds.BOX, 1, box(1)), FakePlugin.object(A + "e/2", CoreKinds.BOX, 1, box(2)));
            p.upsert(b, FakePlugin.object(B + "e/1", CoreKinds.BOX, 1, box(3)));
            p.upsert(b, FakePlugin.object(B + "e/2", CoreKinds.BOX, 1, box(4)));
            assertTrue(quotaError(p, b).contains("object quota of 3"), "the second session shares the first one's quota");

            // Replacing an object is not a new one, and removing one makes room.
            p.upsert(b, FakePlugin.object(B + "e/1", CoreKinds.BOX, 2, box(5)));
            p.reliable(a, CoreMessages.OBJ_REMOVE, new ObjRemove(List.of(A + "e/1"), List.of()));
            p.upsert(b, FakePlugin.object(B + "e/2", CoreKinds.BOX, 2, box(4)));
            p.sync();
            h.tick();
            assertFalse(p.poll(f -> f.is(CoreMessages.ERROR)));
            assertEquals(Set.of(A + "e/2", B + "e/1", B + "e/2"), keys(h));

            // A session that ends gives its objects' room back.
            p.control(a, CoreMessages.SESSION_CLOSE, new io.github.pix3lpirat3.mfbridge.protocol.core.SessionClose("done"));
            p.upsert(b, FakePlugin.object(B + "e/3", CoreKinds.BOX, 1, box(6)));
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.ERROR)));
        }
    }

    @Test
    void objectDataIsCappedPerSessionAndPerDevice() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            h.core.devices().setQuota("dev", Quota.OWNER_BYTES, 3_000);
            h.core.devices().setQuota("dev", Quota.DEVICE_BYTES, 5_000);
            int a = p.openBot("Alpha", "example.com").sessionId();
            int b = p.openBot("Beta", "example.com").sessionId();
            p.upsert(a, FakePlugin.object(A + "e/1", CoreKinds.LABEL, 1, label("x".repeat(2_000))));
            p.upsert(a, FakePlugin.object(A + "e/2", CoreKinds.LABEL, 1, label("x".repeat(2_000))));
            assertTrue(quotaError(p, a).contains("per session"));

            p.upsert(b, FakePlugin.object(B + "e/1", CoreKinds.LABEL, 1, label("x".repeat(2_000))));
            p.upsert(b, FakePlugin.object(B + "e/2", CoreKinds.LABEL, 1, label("x".repeat(900))));
            assertTrue(quotaError(p, b).contains("per device"), "under its own cap, over the device's");

            // A smaller replacement gives bytes back.
            p.upsert(a, FakePlugin.object(A + "e/1", CoreKinds.LABEL, 2, label("x")));
            p.upsert(b, FakePlugin.object(B + "e/2", CoreKinds.LABEL, 2, label("x".repeat(900))));
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.ERROR)));
        }
    }

    @Test
    void oneObjectOrRequestHoldsOnlySoMuch() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int a = p.openBot("Alpha", "example.com").sessionId();
            p.upsert(a, FakePlugin.object(A + "e/long", CoreKinds.LABEL, 1, label("x".repeat(4_097))));
            assertTrue(quotaError(p, a).contains("4096 characters"));
            p.upsert(a, FakePlugin.object(A + "e/path", CoreKinds.PATH, 1,
                    CoreKinds.PATH.encode(new PathData(new double[(16_384 + 1) * 3], null, null, null))));
            assertTrue(quotaError(p, a).contains("16384 points"));
            p.upsert(a, FakePlugin.object(A + "e/fits", CoreKinds.LABEL, 1, label("x".repeat(4_096))));
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.ERROR)));

            List<FormField> fields = new ArrayList<>();
            for (int i = 0; i < 65; i++) {
                fields.add(new FormField("f" + i, FieldType.TOGGLE, null, true, null, null, null, null, false));
            }
            long req = p.reliableRequest(a, CoreMessages.FORM_OPEN, new FormOpen("Big", fields, null, "screen", 30_000, null));
            InputTestSupport.errorResponse(p, req, "quota_exceeded", CoreMessages.FORM_OPEN);
        }
    }

    @Test
    void sharedObjectsCountAgainstTheDeviceThatCreatesThem() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> b.maxObjectsPerSession(2)); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int a = p.openBot("Alpha", "example.com").sessionId();
            p.upsert(a, FakePlugin.object(A + "e/1", CoreKinds.BOX, 1, box(1)));
            long r1 = p.reliableRequest(a, CoreMessages.SHARED_OP,
                    SharedTest.create(FakePlugin.object("shared/zones/1", CoreKinds.LABEL, 0, label("one"))));
            assertEquals(new SharedOpResult("shared/zones/1", 1), SharedTest.result(p, a, r1));
            long r2 = p.reliableRequest(a, CoreMessages.SHARED_OP,
                    SharedTest.create(FakePlugin.object("shared/zones/2", CoreKinds.LABEL, 0, label("two"))));
            assertEquals("quota_exceeded", SharedTest.error(p, a, r2).error().code(), "one own object and one shared make two");

            // The player removing the shared object gives the device its room back.
            h.core.sharedOp(new SharedOpBody.Remove(new io.github.pix3lpirat3.mfbridge.protocol.core.SharedRemove("shared/zones/1", 1)));
            long r3 = p.reliableRequest(a, CoreMessages.SHARED_OP,
                    SharedTest.create(FakePlugin.object("shared/zones/3", CoreKinds.LABEL, 0, label("three"))));
            assertEquals(1, SharedTest.result(p, a, r3).rev());
        }
    }

    @Test
    void sharedObjectsHaveATotalCap() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> b.sharedLimits(2, 400)); FakePlugin p = FakePlugin.ready(h, "dev")) {
            h.tickUntil("global store", h.core::storesLoaded);
            int a = p.openBot("Alpha", "example.com").sessionId();
            long r1 = p.reliableRequest(a, CoreMessages.SHARED_OP,
                    SharedTest.create(FakePlugin.object("shared/zones/1", CoreKinds.LABEL, 0, label("one"))));
            assertEquals(1, SharedTest.result(p, a, r1).rev());
            long big = p.reliableRequest(a, CoreMessages.SHARED_OP,
                    SharedTest.create(FakePlugin.object("shared/zones/big", CoreKinds.LABEL, 0, label("x".repeat(500)))));
            assertTrue(SharedTest.error(p, a, big).error().message().contains("shared data limit"));
            h.core.sharedOp(new SharedOpBody.Create(new SharedCreate(
                    FakePlugin.object("shared/zones/2", CoreKinds.LABEL, 0, label("two")))));
            long r3 = p.reliableRequest(a, CoreMessages.SHARED_OP,
                    SharedTest.create(FakePlugin.object("shared/zones/3", CoreKinds.LABEL, 0, label("three"))));
            assertTrue(SharedTest.error(p, a, r3).error().message().contains("shared object limit of 2"));
            BridgeException e = assertThrows(BridgeException.class, () -> h.core.sharedOp(new SharedOpBody.Create(
                    new SharedCreate(FakePlugin.object("shared/zones/4", CoreKinds.LABEL, 0, label("four"))))));
            assertEquals("quota_exceeded", e.code());
        }
    }
}
