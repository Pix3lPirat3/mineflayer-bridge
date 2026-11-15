package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.api.BridgeExtension;
import io.github.pix3lpirat3.mfbridge.api.ExtensionId;
import io.github.pix3lpirat3.mfbridge.api.ExtensionRegistrar;
import io.github.pix3lpirat3.mfbridge.api.Message;
import io.github.pix3lpirat3.mfbridge.api.MessageContext;
import io.github.pix3lpirat3.mfbridge.api.PlainMessages;
import io.github.pix3lpirat3.mfbridge.api.SemVer;
import io.github.pix3lpirat3.mfbridge.api.SessionRef;
import io.github.pix3lpirat3.mfbridge.protocol.core.Welcome;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An extension that declares its messages through the API alone, without naming a wire type. */
class MessageHandleTest {
    static final Message<@Nullable Object> HELLO =
            PlainMessages.message("acme:hello", Message.From.BOTH, Message.Delivery.RELIABLE);
    static final Message<@Nullable Object> STATE =
            PlainMessages.message("acme:state", Message.From.MOD, Message.Delivery.LATEST);
    static final Message<@Nullable Object> QUIET =
            PlainMessages.message("acme:quiet", Message.From.NODE, Message.Delivery.UNRELIABLE);

    static final class Greeter implements BridgeExtension {
        final List<@Nullable Object> got = new ArrayList<>();

        @Override
        public ExtensionId id() {
            return ExtensionId.parse("acme:greeter");
        }

        @Override
        public SemVer version() {
            return SemVer.parse("1.0.0");
        }

        @Override
        public void register(ExtensionRegistrar r) {
            r.message(HELLO, (ctx, msg) -> {
                got.add(msg);
                ctx.send(HELLO, Map.of("hello", ctx.displayName()));
            });
            r.message(STATE, (ctx, msg) -> { });
            r.message(QUIET, (ctx, msg) -> got.add(msg));
        }
    }

    @TempDir
    Path dir;

    @Test
    void messagesDeclaredThroughTheApiAreRegisteredAndSent() throws Exception {
        assertEquals("acme:hello", HELLO.name());
        assertEquals("Message[acme:state]", STATE.toString());
        Greeter greeter = new Greeter();
        try (Harness h = new Harness(dir, b -> b.extension(greeter), DeviceApprover.ALLOW_ONCE)) {
            FakePlugin p = FakePlugin.connect(h, "dev");
            Welcome w = p.handshake(h.localToken(), List.of("acme:hello", "acme:state"), List.of());
            MessageType<@Nullable Object> hello = PlainExtensionTest.wire(w, HELLO.type());
            MessageType<@Nullable Object> state = PlainExtensionTest.wire(w, STATE.type());
            int s = p.openBot("Miner", "example.com").sessionId();

            p.reliable(s, hello, "hi");
            assertEquals(Map.of("hello", "Miner"), p.await(hello, s));
            assertEquals(List.of("hi"), greeter.got);

            MessageContext ctx = h.core.session("bot:miner@example.com");
            assertNotNull(ctx);
            SessionRef ref = ctx.session();
            assertTrue(ref.send(STATE, 7), "a message only the mod sends");
            h.tick();
            assertEquals(7L, p.await(state, s));
            assertFalse(ref.send(QUIET_BACK, 1), "this connection never named it");
            assertThrows(IllegalArgumentException.class, () -> ref.send(QUIET, 1), "only the Node half may send it");
        }
    }

    /** Registered by no extension, so no connection negotiates it. */
    static final Message<@Nullable Object> QUIET_BACK =
            PlainMessages.message("acme:unknown", Message.From.MOD, Message.Delivery.UNRELIABLE);
}
