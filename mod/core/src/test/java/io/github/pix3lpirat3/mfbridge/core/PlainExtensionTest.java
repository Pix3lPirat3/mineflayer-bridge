package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.api.BridgeExtension;
import io.github.pix3lpirat3.mfbridge.api.ExtensionId;
import io.github.pix3lpirat3.mfbridge.api.ExtensionRegistrar;
import io.github.pix3lpirat3.mfbridge.api.MessageContext;
import io.github.pix3lpirat3.mfbridge.api.PlainMessages;
import io.github.pix3lpirat3.mfbridge.api.SemVer;
import io.github.pix3lpirat3.mfbridge.api.SessionRef;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.RegistryEntry;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionClose;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpened;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionResume;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionResumed;
import io.github.pix3lpirat3.mfbridge.protocol.core.Welcome;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Delivery;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Direction;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireCodecs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A third-party extension using plain-value messages and a kept {@link SessionRef}. */
class PlainExtensionTest {
    static final MessageType<@Nullable Object> NOTE = PlainMessages.type("acme:note", Direction.BOTH, Delivery.RELIABLE);
    static final MessageType<@Nullable Object> PUSH = PlainMessages.type("acme:push", Direction.MOD, Delivery.RELIABLE);
    static final MessageType<@Nullable Object> UNHEARD = PlainMessages.type("acme:unheard", Direction.MOD, Delivery.RELIABLE);
    static final MessageType<@Nullable Object> ASK = PlainMessages.type("acme:ask", Direction.NODE, Delivery.RELIABLE);
    static final String OWNER = "bot:miner@example.com";

    static final class Notes implements BridgeExtension {
        final List<@Nullable Object> got = new ArrayList<>();
        @Nullable SessionRef ref;

        @Override
        public ExtensionId id() {
            return ExtensionId.parse("acme:notes");
        }

        @Override
        public SemVer version() {
            return SemVer.parse("1.0.0");
        }

        @Override
        public void register(ExtensionRegistrar r) {
            r.message(NOTE, (ctx, msg) -> {
                got.add(msg);
                ref = ctx.session();
                Map<String, @Nullable Object> reply = new LinkedHashMap<>();
                reply.put("echo", msg);
                reply.put("from", ctx.displayName());
                ctx.send(NOTE, reply);
            });
            r.message(PUSH, (ctx, msg) -> { });
            r.message(UNHEARD, (ctx, msg) -> { });
            r.message(ASK, (ctx, msg) -> { });
        }
    }

    @TempDir
    Path dir;

    /** The message as the plugin sees it on this connection, with the id from {@code welcome}. */
    static MessageType<@Nullable Object> wire(Welcome w, MessageType<@Nullable Object> type) {
        for (RegistryEntry e : w.registry()) {
            if (e.name().equals(type.name())) {
                return new MessageType<>(e.id(), type.name(), type.from(), type.delivery(), WireCodecs.ANY, null, 0);
            }
        }
        throw new AssertionError(type.name() + " not in welcome");
    }

    static SessionRef ref(Harness h) {
        MessageContext ctx = h.core.session(OWNER);
        assertNotNull(ctx);
        return ctx.session();
    }

    static boolean connected(Harness h) {
        return h.core.sessionStats().get(0).connected();
    }

    @Test
    void receivesRepliesAndSendsLaterThroughTheRef() throws Exception {
        Notes notes = new Notes();
        List<String> names = List.of("acme:note", "acme:push", "acme:ask");
        try (Harness h = new Harness(dir, b -> b.extension(notes), DeviceApprover.ALLOW_ONCE)) {
            FakePlugin p = FakePlugin.connect(h, "dev");
            Welcome w = p.handshake(h.localToken(), names, List.of());
            MessageType<@Nullable Object> note = wire(w, NOTE);
            MessageType<@Nullable Object> push = wire(w, PUSH);
            SessionOpened opened = p.openBot("Miner", "example.com");
            int s = opened.sessionId();

            Map<String, @Nullable Object> sent = new LinkedHashMap<>();
            sent.put("n", 3);
            sent.put("pos", List.of(1.5, -2, 0));
            sent.put("ok", true);
            p.reliable(s, note, sent);
            Object reply = p.await(note, s);
            Map<String, @Nullable Object> decoded = new LinkedHashMap<>();
            decoded.put("n", 3L);
            decoded.put("pos", List.of(1.5, -2L, 0L));
            decoded.put("ok", true);
            assertEquals(List.of(decoded), notes.got);
            assertEquals(Map.of("echo", decoded, "from", "Miner"), reply);

            SessionRef ref = notes.ref;
            assertNotNull(ref);
            assertTrue(ref.isOpen());
            assertEquals(s, ref.sessionId());
            assertEquals(OWNER + "/", ref.ownerPrefix());
            assertEquals("Miner", ref.displayName());
            assertSame(ref, ref(h));

            // After the handler returned.
            assertTrue(ref.send(PUSH, "later"));
            assertEquals("later", p.await(push, s));
            assertFalse(ref.send(UNHEARD, "nobody listed it"));
            assertThrows(IllegalArgumentException.class, () -> ref.send(ASK, "node only"));

            // Detached: reliable sends are logged and replayed on resume, through the same ref.
            p.sync();
            p.abort();
            h.tickUntil("detached", () -> !connected(h));
            assertTrue(ref.isOpen());
            assertTrue(ref.send(PUSH, List.of("while", "away")));

            try (FakePlugin p2 = FakePlugin.connect(h, "dev")) {
                Welcome w2 = p2.handshake(h.localToken(), names, List.of());
                long req = p2.request(0, CoreMessages.SESSION_RESUME, new SessionResume(opened.resumeToken(), 0));
                SessionResumed resumed = p2.await("resumed",
                        x -> x.is(CoreMessages.SESSION_RESUMED) && x.env().req() == req).decode(CoreMessages.SESSION_RESUMED);
                int s2 = resumed.sessionId();
                MessageType<@Nullable Object> push2 = wire(w2, PUSH);
                assertEquals(List.of("while", "away"), p2.await("replayed push",
                        x -> x.is(push2) && x.env().session() == s2 && x.decode(push2) instanceof List<?>).decode(push2));
                assertTrue(ref.isOpen());
                assertEquals(s2, ref.sessionId());
                assertSame(ref, ref(h));
                assertTrue(ref.send(PUSH, 5));
                assertEquals(5L, p2.await("push 5", x -> x.is(push2) && Long.valueOf(5).equals(x.decode(push2))).decode(push2));

                p2.control(s2, CoreMessages.SESSION_CLOSE, new SessionClose("bye"));
                h.tickUntil("closed", () -> !ref.isOpen());
                assertFalse(ref.send(PUSH, "gone"));
            }
        }
    }

    @Test
    void aSupersededSessionRefStopsSending() throws Exception {
        Notes notes = new Notes();
        try (Harness h = new Harness(dir, b -> b.extension(notes), DeviceApprover.ALLOW_ONCE);
                FakePlugin p = FakePlugin.connect(h, "dev-1");
                FakePlugin p2 = FakePlugin.connect(h, "dev-1")) {
            p.handshake(h.localToken(), List.of("acme:push"), List.of());
            p.openBot("Miner", "example.com");
            SessionRef old = ref(h);
            assertTrue(old.isOpen());

            Welcome w2 = p2.handshake(h.localToken(), List.of("acme:push"), List.of());
            SessionOpened newer = p2.openBot("Miner", "example.com");
            assertFalse(old.isOpen());
            assertFalse(old.send(PUSH, "stale"));

            SessionRef current = ref(h);
            assertTrue(current.isOpen());
            assertTrue(current.send(PUSH, "fresh"));
            assertEquals("fresh", p2.await(wire(w2, PUSH), newer.sessionId()));
        }
    }
}
