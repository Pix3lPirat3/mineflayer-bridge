package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.PlainExtensionTest.wire;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.api.BridgeExtension;
import io.github.pix3lpirat3.mfbridge.api.ExtensionId;
import io.github.pix3lpirat3.mfbridge.api.ExtensionRegistrar;
import io.github.pix3lpirat3.mfbridge.api.MessageAccess;
import io.github.pix3lpirat3.mfbridge.api.PlainMessages;
import io.github.pix3lpirat3.mfbridge.api.SemVer;
import io.github.pix3lpirat3.mfbridge.core.remote.PairingSession;
import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.Welcome;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldReport;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Delivery;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Direction;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An extension says who may send each of its messages, and the bridge holds sessions to it. */
class ExtensionAccessTest {
    static final MessageType<@Nullable Object> LOG = PlainMessages.type("acme:log", Direction.NODE, Delivery.RELIABLE);
    static final MessageType<@Nullable Object> SAY = PlainMessages.type("acme:say", Direction.NODE, Delivery.RELIABLE);
    static final MessageType<@Nullable Object> FREE = PlainMessages.type("acme:free", Direction.NODE, Delivery.RELIABLE);
    static final List<String> NAMES = List.of("acme:log", "acme:say", "acme:free");

    static final class Guarded implements BridgeExtension {
        final List<String> got = new ArrayList<>();
        final String permission;

        Guarded(String permission) {
            this.permission = permission;
        }

        @Override
        public ExtensionId id() {
            return ExtensionId.parse("acme:guarded");
        }

        @Override
        public SemVer version() {
            return SemVer.parse("1.0.0");
        }

        @Override
        public void register(ExtensionRegistrar r) {
            r.message(LOG, MessageAccess.ATTACHED, (ctx, msg) -> got.add("log " + msg));
            r.message(SAY, MessageAccess.OPEN.withPermission(permission), (ctx, msg) -> got.add("say " + msg));
            r.message(FREE, (ctx, msg) -> got.add("free " + msg));
        }
    }

    @TempDir
    Path dir;

    @Test
    void aMessageThatNeedsAnAttachedSessionIsRefusedFromOthers() throws Exception {
        Guarded ext = new Guarded("chat");
        try (Harness h = new Harness(dir, b -> b.extension(ext), DeviceApprover.ALLOW_ONCE);
                FakePlugin p = FakePlugin.connect(h, "dev")) {
            Welcome w = p.handshake(h.localToken(), NAMES, List.of());
            int s = p.openBot("Miner", "example.com").sessionId();

            // The player is not in the bot's world, so it is not attached.
            p.reliable(s, wire(w, LOG), "one");
            ErrorMessage e = p.await(CoreMessages.ERROR, s);
            assertEquals("not_attached", e.error().code());
            assertEquals(wire(w, LOG).id(), e.relatesTo());
            p.reliable(s, wire(w, FREE), "anyone");
            p.sync();
            assertEquals(List.of("free anyone"), ext.got, "a message without rules is open to every session");

            p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(InputTestSupport.BOT_WORLD, null));
            h.core.setPlayerContext(InputTestSupport.PLAYER_WORLD);
            p.reliable(s, wire(w, LOG), "two");
            p.sync();
            assertEquals(List.of("free anyone", "log two"), ext.got);
        }
    }

    @Test
    void aMessageThatNeedsAPermissionIsRefusedWithoutIt() throws Exception {
        Guarded ext = new Guarded("chat");
        try (Harness h = new Harness(dir, b -> b.extension(ext), DeviceApprover.ALLOW_ONCE)) {
            PairingSession session = h.core.pairing().begin("bots.example");
            try (FakePlugin p = RemoteTest.tls(h, "remote-1")) {
                p.control(0, CoreMessages.HELLO, new io.github.pix3lpirat3.mfbridge.protocol.core.Hello(1, 0, "test-plugin",
                        "test-device", NAMES, List.of()));
                var ch = p.await(CoreMessages.AUTH_CHALLENGE);
                assertTrue(ch.methods().contains(io.github.pix3lpirat3.mfbridge.protocol.core.AuthMethod.PAIR));
                p.control(0, CoreMessages.AUTH_RESPONSE, new io.github.pix3lpirat3.mfbridge.protocol.core.AuthResponse(
                        io.github.pix3lpirat3.mfbridge.protocol.core.AuthMethod.PAIR, p.deviceId,
                        session.code().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                p.await(CoreMessages.PAIR_RESULT);
                Welcome w = p.await(CoreMessages.WELCOME);
                int s = p.openBot("Miner", "example.com").sessionId();

                // Remote devices do not have chat unless the player grants it.
                p.reliable(s, wire(w, SAY), "hello");
                assertEquals("permission_denied", p.await(CoreMessages.ERROR, s).error().code());
                assertTrue(ext.got.isEmpty());

                h.core.devices().setPermission("remote-1", Permission.CHAT, true);
                p.reliable(s, wire(w, SAY), "hello again");
                p.sync();
                assertEquals(List.of("say hello again"), ext.got);
            }
        }
    }

    @Test
    void anUnknownPermissionNameFailsAtRegistration() {
        assertThrows(IllegalArgumentException.class,
                () -> new Harness(dir, b -> b.extension(new Guarded("no.such.permission")), DeviceApprover.ALLOW_ONCE).close());
    }
}
