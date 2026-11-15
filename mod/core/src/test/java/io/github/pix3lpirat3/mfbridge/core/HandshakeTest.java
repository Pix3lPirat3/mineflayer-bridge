package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.api.BridgeExtension;
import io.github.pix3lpirat3.mfbridge.api.ExtensionId;
import io.github.pix3lpirat3.mfbridge.api.ExtensionRegistrar;
import io.github.pix3lpirat3.mfbridge.api.ObjectKind;
import io.github.pix3lpirat3.mfbridge.api.SemVer;
import io.github.pix3lpirat3.mfbridge.core.auth.Proofs;
import io.github.pix3lpirat3.mfbridge.protocol.core.AuthChallenge;
import io.github.pix3lpirat3.mfbridge.protocol.core.AuthMethod;
import io.github.pix3lpirat3.mfbridge.protocol.core.AuthResponse;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ExtensionRef;
import io.github.pix3lpirat3.mfbridge.protocol.core.Goodbye;
import io.github.pix3lpirat3.mfbridge.protocol.core.Hello;
import io.github.pix3lpirat3.mfbridge.protocol.core.LabelData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Lifetime;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectFull;
import io.github.pix3lpirat3.mfbridge.protocol.core.Ping;
import io.github.pix3lpirat3.mfbridge.protocol.core.RegistryEntry;
import io.github.pix3lpirat3.mfbridge.protocol.core.RegistrySpace;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpened;
import io.github.pix3lpirat3.mfbridge.protocol.core.Welcome;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Delivery;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Direction;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HandshakeTest {
    static final MessageType<Ping> ECHO =
            new MessageType<>(0, "acme:echo", Direction.BOTH, Delivery.CONTROL, Ping.CODEC, null, 0);
    static final KindType<LabelData> BLIP = new KindType<>(0, "acme:blip", LabelData.CODEC);

    static final class Acme implements BridgeExtension {
        final AtomicInteger echoes = new AtomicInteger();

        @Override
        public ExtensionId id() {
            return ExtensionId.parse("acme:radar");
        }

        @Override
        public SemVer version() {
            return SemVer.parse("1.2.0");
        }

        @Override
        public void register(ExtensionRegistrar r) {
            r.kind(ObjectKind.of(BLIP, SemVer.parse("1.0.0")));
            r.message(ECHO, (ctx, msg) -> {
                echoes.incrementAndGet();
                ctx.send(ECHO, new Ping(msg.time() + 1));
            });
        }
    }

    @TempDir
    Path dir;

    @Test
    void handshakeSucceedsWithRegistryForSupportedNames() throws Exception {
        Acme acme = new Acme();
        try (Harness h = new Harness(dir, b -> b.extension(acme), DeviceApprover.ALLOW_ONCE);
                FakePlugin p = FakePlugin.connect(h, "dev-1")) {
            Welcome w = p.handshake(h.localToken(), List.of("acme:echo", "acme:missing"), List.of("acme:blip", "x:y"));
            assertEquals(1, w.protocolMajor());
            assertEquals("0.1.0-test", w.modVersion());
            assertEquals("26.3", w.gameVersion());
            assertEquals(List.of(new RegistryEntry("acme:echo", 0x100, RegistrySpace.MESSAGE),
                    new RegistryEntry("acme:blip", 0x101, RegistrySpace.KIND)), w.registry());
            assertEquals(List.of(new ExtensionRef("acme:radar", "1.2.0")), w.extensions());
            assertEquals(1 << 20, w.limits().maxFrameBytes());
            assertEquals(30_000, w.limits().resumeWindowMs());
            assertEquals(5_000, w.limits().heartbeatMs());
            assertEquals(2_000, w.budget().messagesPerSecond());

            SessionOpened opened = p.openBot("Miner", "example.com");
            MessageType<Ping> wireEcho =
                    new MessageType<>(0x100, "acme:echo", Direction.BOTH, Delivery.CONTROL, Ping.CODEC, null, 0);
            p.control(opened.sessionId(), wireEcho, new Ping(41));
            Ping back = p.await(wireEcho, opened.sessionId());
            assertEquals(42, back.time());
            assertEquals(1, acme.echoes.get());
        }
    }

    @Test
    void challengeOffersLocalAndDeviceOnLoopback() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.connect(h, "dev")) {
            p.control(0, CoreMessages.HELLO, new Hello(1, 0, "t", "d", List.of(), List.of()));
            AuthChallenge ch = p.await(CoreMessages.AUTH_CHALLENGE);
            assertEquals(List.of(AuthMethod.LOCAL, AuthMethod.DEVICE), ch.methods());
            assertEquals(32, ch.nonce().length);
            assertTrue(ch.connectionId() >= 0 && ch.connectionId() < (1L << 53));
        }
    }

    @Test
    void extensionCanMessageASessionFirst() throws Exception {
        MessageType<Ping> poke = new MessageType<>(0, "acme:poke", Direction.MOD, Delivery.RELIABLE, Ping.CODEC, null, 0);
        BridgeExtension ext = new BridgeExtension() {
            @Override
            public ExtensionId id() {
                return ExtensionId.parse("acme:poker");
            }

            @Override
            public SemVer version() {
                return SemVer.parse("1.0.0");
            }

            @Override
            public void register(ExtensionRegistrar r) {
                r.message(poke, (ctx, msg) -> { });
            }
        };
        try (Harness h = new Harness(dir, b -> b.extension(ext), DeviceApprover.ALLOW_ONCE);
                FakePlugin p = FakePlugin.connect(h, "dev-1")) {
            p.handshake(h.localToken(), List.of("acme:poke"), List.of());
            SessionOpened opened = p.openBot("Miner", "example.com");
            assertNull(h.core.session("bot:nobody@example.com"));
            var session = h.core.session("bot:miner@example.com");
            assertNotNull(session);
            session.send(poke, new Ping(7));
            Ping got = p.await(new MessageType<>(0x100, "acme:poke", Direction.MOD, Delivery.RELIABLE, Ping.CODEC, null, 0), opened.sessionId());
            assertEquals(7, got.time());
        }
    }

    @Test
    void badProofGetsUnauthorizedGoodbye() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.connect(h, "dev")) {
            p.control(0, CoreMessages.HELLO, new Hello(1, 0, "t", "d", List.of(), List.of()));
            p.await(CoreMessages.AUTH_CHALLENGE);
            p.control(0, CoreMessages.AUTH_RESPONSE, new AuthResponse(AuthMethod.LOCAL, "dev", new byte[32]));
            Goodbye g = p.await(CoreMessages.GOODBYE);
            assertEquals("unauthorized", g.reason());
            assertFalse(g.retry());
            h.tickUntil("close", p.closed::isDone);
        }
    }

    @Test
    void approvalDenialGetsUnauthorizedGoodbye() throws Exception {
        CompletableFuture<ApprovalDecision> decision = new CompletableFuture<>();
        try (Harness h = new Harness(dir, b -> {}, req -> decision); FakePlugin p = FakePlugin.connect(h, "dev")) {
            p.control(0, CoreMessages.HELLO, new Hello(1, 0, "t", "d", List.of(), List.of()));
            AuthChallenge ch = p.await(CoreMessages.AUTH_CHALLENGE);
            p.control(0, CoreMessages.AUTH_RESPONSE, new AuthResponse(AuthMethod.LOCAL, "dev",
                    Proofs.proof(h.localToken(), ch.nonce(), ch.connectionId(), "dev")));
            h.tickFor(50);
            assertFalse(p.poll(f -> f.is(CoreMessages.WELCOME)), "waits for the player");
            decision.complete(ApprovalDecision.DENY);
            Goodbye g = p.await(CoreMessages.GOODBYE);
            assertEquals("unauthorized", g.reason());
            assertFalse(g.retry());
        }
    }

    @Test
    void allowedDeviceIsRememberedAcrossConnections() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        DeviceApprover approver = req -> {
            asked.incrementAndGet();
            return CompletableFuture.completedFuture(ApprovalDecision.ALLOW);
        };
        try (Harness h = new Harness(dir, b -> {}, approver)) {
            try (FakePlugin p = FakePlugin.ready(h, "dev-x")) {
                assertTrue(p.welcome != null);
                assertEquals(1, asked.get());
            }
            try (FakePlugin p = FakePlugin.ready(h, "dev-x")) {
                assertTrue(p.welcome != null);
                assertEquals(1, asked.get(), "known device is not asked again");
            }
        }
        String devices = Files.readString(dir.resolve("mineflayer_bridge").resolve("devices.json"));
        assertTrue(devices.contains("\"dev-x\""), devices);
    }

    @Test
    void messagesBeforeWelcomeAreRejected() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.connect(h, "dev")) {
            p.send(0, CoreMessages.SESSION_OPEN, new SessionOpen(
                    SessionKind.CONTROLLER, "c", null, List.of()),
                    0, Envelope.NONE, Envelope.NONE, 1);
            Goodbye g = p.await(CoreMessages.GOODBYE);
            assertEquals("invalid_message", g.reason());
        }
    }

    @Test
    void protocolMajorMismatchSaysGoodbye() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.connect(h, "dev")) {
            p.control(0, CoreMessages.HELLO, new Hello(2, 0, "t", "d", List.of(), List.of()));
            Goodbye g = p.await(CoreMessages.GOODBYE);
            assertEquals("protocol_mismatch", g.reason());
        }
    }

    @Test
    void unknownKindIsRejected() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.upsert(s, new ObjectFull("bot:miner@example.com/e/x", 0x150, 1,
                    null, Lifetime.SESSION,
                    Scope.GLOBAL, null, null, new byte[] {(byte) 0x90}));
            var err = p.await(CoreMessages.ERROR, s);
            assertEquals("unknown_kind", err.error().code());
            assertEquals(CoreMessages.OBJ_UPSERT.id(), err.relatesTo());
        }
    }
}
