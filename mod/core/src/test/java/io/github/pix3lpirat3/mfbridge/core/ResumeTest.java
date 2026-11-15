package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.ObjectSyncTest.box;
import static io.github.pix3lpirat3.mfbridge.core.ObjectSyncTest.keys;
import static io.github.pix3lpirat3.mfbridge.core.ObjectSyncTest.label;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.protocol.core.Ack;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.Goodbye;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjEditResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectWrite;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpened;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionResume;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionResumed;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedCreate;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOp;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpBody;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedSubscribe;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResumeTest {
    static final String P = "bot:miner@example.com/";

    @TempDir
    Path dir;

    static Harness always(Path dir, Consumer<BridgeConfig.Builder> more) throws Exception {
        return new Harness(dir, b -> {
            b.attachPolicy(AttachPolicy.ALWAYS);
            more.accept(b);
        }, DeviceApprover.ALLOW_ONCE);
    }

    static SharedOpBody create(String key, byte[] data) {
        return new SharedOpBody.Create(new SharedCreate(FakePlugin.object(key, CoreKinds.LABEL, 0, data)));
    }

    static SessionStats stats(Harness h) {
        return h.core.sessionStats().get(0);
    }

    @Test
    void resumeReplaysLoggedMessagesInBothDirections() throws Exception {
        try (Harness h = always(dir, b -> {})) {
            h.tickUntil("global store", h.core::storesLoaded);
            FakePlugin p = FakePlugin.ready(h, "dev");
            SessionOpened opened = p.openBot("Miner", "example.com");
            int s = opened.sessionId();
            p.reliable(s, CoreMessages.SHARED_SUBSCRIBE, new SharedSubscribe(List.of(), List.of()));
            p.reliableRequest(s, CoreMessages.SHARED_OP, new SharedOp(create("shared/z/one", label("one"))));
            p.upsert(s, FakePlugin.object(P + "e/a", CoreKinds.BOX, 1, box(1)));
            p.await(CoreMessages.SHARED_OP_RESULT, s);
            p.await(CoreMessages.OBJ_UPSERT, s);
            p.sync();
            p.abort();
            h.tickUntil("detached", () -> !stats(h).connected());

            // The mod keeps logging reliable traffic for the detached session.
            h.core.sharedOp(create("shared/z/two", label("two")));
            assertEquals(3, stats(h).lastOutboundSeq());

            try (FakePlugin p2 = FakePlugin.ready(h, "dev")) {
                long req = p2.request(0, CoreMessages.SESSION_RESUME, new SessionResume(opened.resumeToken(), 0));
                FakePlugin.Frame f = p2.await("resumed", x -> x.is(CoreMessages.SESSION_RESUMED) && x.env().req() == req);
                assertTrue(f.env().has(Envelope.RESPONSE));
                SessionResumed resumed = f.decode(CoreMessages.SESSION_RESUMED);
                assertEquals(3, resumed.lastApplied());
                int s2 = resumed.sessionId();
                assertEquals(1, s2, "first session id on the new connection");

                FakePlugin.Frame r1 = p2.await("replay 1", x -> x.env().session() == s2 && x.env().seq() == 1);
                FakePlugin.Frame r2 = p2.await("replay 2", x -> x.env().session() == s2 && x.env().seq() == 2);
                FakePlugin.Frame r3 = p2.await("replay 3", x -> x.env().session() == s2 && x.env().seq() == 3);
                assertEquals(CoreMessages.OBJ_UPSERT.id(), r1.env().type());
                assertEquals(CoreMessages.SHARED_OP_RESULT.id(), r2.env().type());
                assertEquals("shared/z/two", ((ObjectWrite.Full) r3.decode(CoreMessages.OBJ_UPSERT).writes().get(0))
                        .value().key());

                // Plugin side: replay above the mod's lastApplied keeping original seq; duplicates are ignored.
                p2.setNextSeq(s2, 2);
                p2.upsert(s2, FakePlugin.object(P + "e/a", CoreKinds.BOX, 1, box(1)));
                p2.upsert(s2, FakePlugin.object(P + "e/b", CoreKinds.BOX, 1, box(2)));
                p2.sync();
                assertTrue(keys(h).containsAll(List.of(P + "e/a", P + "e/b")));
                assertFalse(p2.poll(x -> x.is(CoreMessages.ERROR)), "no errors: " + p2.describe());
                assertEquals(4, stats(h).lastAppliedInbound());
                assertTrue(stats(h).connected());
            }
        }
    }

    @Test
    void resumeWindowExpiryDropsObjectsAndRejectsResume() throws Exception {
        try (Harness h = always(dir, b -> {})) {
            FakePlugin p = FakePlugin.ready(h, "dev");
            SessionOpened opened = p.openBot("Miner", "example.com");
            p.upsert(opened.sessionId(), FakePlugin.object(P + "e/a", CoreKinds.BOX, 1, box(1)));
            p.sync();
            p.abort();
            h.tickUntil("detached", () -> !stats(h).connected());
            assertEquals(1, keys(h).size(), "objects kept during the resume window");
            h.clock.advanceMs(29_000);
            h.tick();
            assertEquals(1, keys(h).size());
            h.clock.advanceMs(1_001);
            h.tick();
            assertTrue(keys(h).isEmpty());
            assertTrue(h.core.sessionStats().isEmpty());

            try (FakePlugin p2 = FakePlugin.ready(h, "dev")) {
                long req = p2.request(0, CoreMessages.SESSION_RESUME, new SessionResume(opened.resumeToken(), 0));
                FakePlugin.Frame f = p2.await("resume error", x -> x.is(CoreMessages.ERROR) && x.env().req() == req);
                assertTrue(f.env().has(Envelope.RESPONSE) && f.env().has(Envelope.ERROR));
                ErrorMessage e = f.decode(CoreMessages.ERROR);
                assertEquals("disconnected", e.error().code());
                assertEquals(CoreMessages.SESSION_RESUME.id(), e.relatesTo());
            }
        }
    }

    @Test
    void outboundLogCapMakesResumeImpossible() throws Exception {
        try (Harness h = always(dir, b -> b.outboundLogCapBytes(2048))) {
            h.tickUntil("global store", h.core::storesLoaded);
            FakePlugin p = FakePlugin.ready(h, "dev");
            SessionOpened opened = p.openBot("Miner", "example.com");
            int s = opened.sessionId();
            p.reliable(s, CoreMessages.SHARED_SUBSCRIBE, new SharedSubscribe(List.of(), List.of()));
            p.upsert(s, FakePlugin.object(P + "e/a", CoreKinds.BOX, 1, box(1)));
            p.sync();
            String big = "x".repeat(700);
            for (int i = 0; i < 4; i++) {
                h.core.sharedOp(create("shared/z/" + i, label(big)));
            }
            h.tick();
            assertTrue(stats(h).logOverflowed());
            assertTrue(stats(h).outboundLogBytes() <= 2048);
            p.abort();
            h.tickUntil("detached", () -> !stats(h).connected());

            try (FakePlugin p2 = FakePlugin.ready(h, "dev")) {
                long req = p2.request(0, CoreMessages.SESSION_RESUME, new SessionResume(opened.resumeToken(), 0));
                ErrorMessage e = p2.await("resume error", x -> x.is(CoreMessages.ERROR) && x.env().req() == req)
                        .decode(CoreMessages.ERROR);
                assertEquals("disconnected", e.error().code());
                h.tick();
                assertFalse(keys(h).contains(P + "e/a"), "a failed resume drops the session's objects");
                assertTrue(h.core.sessionStats().isEmpty());
            }
        }
    }

    @Test
    void acksTrimTheLogAndIdleInputGetsAnEmptyAck() throws Exception {
        try (Harness h = always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            h.tickUntil("global store", h.core::storesLoaded);
            int s = p.openBot("Miner", "example.com").sessionId();
            p.reliable(s, CoreMessages.SHARED_SUBSCRIBE, new SharedSubscribe(List.of(), List.of()));
            p.sync();
            for (int i = 0; i < 3; i++) {
                h.core.sharedOp(create("shared/z/" + i, label("v" + i)));
            }
            for (int i = 0; i < 3; i++) {
                p.await(CoreMessages.OBJ_UPSERT, s);
            }
            assertEquals(3, stats(h).outboundLogEntries());
            p.send(s, CoreMessages.ACK, new Ack(), 0, Envelope.NONE, 2, Envelope.NONE);
            p.sync();
            assertEquals(1, stats(h).outboundLogEntries());

            long seq = p.upsert(s, FakePlugin.object(P + "e/a", CoreKinds.BOX, 1, box(1)));
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.ACK)), "not before the ack delay");
            h.clock.advanceMs(300);
            FakePlugin.Frame ack = p.await("empty ack", f -> f.is(CoreMessages.ACK) && f.env().session() == s);
            assertEquals(seq, ack.env().ack());
        }
    }

    @Test
    void heartbeatPingsWhenIdleAndDropsDeadPeers() throws Exception {
        try (Harness h = always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            p.openBot("Miner", "example.com");
            h.tick();
            h.clock.advanceMs(5_001);
            p.await(CoreMessages.PING, 0);
            h.clock.advanceMs(10_000);
            Goodbye g = p.await(CoreMessages.GOODBYE);
            assertEquals("timeout", g.reason());
            assertTrue(g.retry());
            h.tickUntil("closed", p.closed::isDone);
            h.tickUntil("session detached for resume", () -> !stats(h).connected());
        }
    }

    @Test
    void editProposalsResolveOrTimeOut() throws Exception {
        try (Harness h = always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.upsert(s, FakePlugin.object(P + "e/a", CoreKinds.BOX, 1, box(1)));
            p.sync();

            CompletableFuture<ObjEditResult> accepted = h.core.proposeEdit(P + "e/a", 1, new byte[] {(byte) 0x80});
            FakePlugin.Frame f = p.await("propose", x -> x.is(CoreMessages.OBJ_EDIT_PROPOSE));
            assertEquals(P + "e/a", f.decode(CoreMessages.OBJ_EDIT_PROPOSE).key());
            assertTrue(f.env().seq() > 0 && f.env().req() > 0);
            p.send(s, CoreMessages.OBJ_EDIT_RESULT, new ObjEditResult(true, null), Envelope.RESPONSE,
                    p.nextSeq(s), Envelope.NONE, f.env().req());
            h.tickUntil("accepted", accepted::isDone);
            assertTrue(accepted.get().accepted());

            CompletableFuture<ObjEditResult> ignored = h.core.proposeEdit(P + "e/a", 1, new byte[] {(byte) 0x80});
            p.await("second propose", x -> x.is(CoreMessages.OBJ_EDIT_PROPOSE));
            h.clock.advanceMs(10_001);
            h.tick();
            ExecutionException e = assertThrows(ExecutionException.class, ignored::get);
            assertEquals("timeout", assertInstanceOf(BridgeException.class, e.getCause()).code());

            assertTrue(h.core.proposeEdit("bot:nobody@x/e/a", 1, new byte[0]).isCompletedExceptionally());
        }
    }
}
