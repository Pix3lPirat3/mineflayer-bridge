package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.remote.PairingSession;
import io.github.pix3lpirat3.mfbridge.protocol.core.BotIdentity;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionClose;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpen;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A bot identity belongs to the device that first used it. */
class IdentityBindingTest {
    static final String IDENTITY = "miner@example.com";

    @TempDir
    Path dir;

    /** Tries to open the Miner bot and returns the error code it is refused with. */
    static String refused(FakePlugin p) {
        long req = p.request(0, CoreMessages.SESSION_OPEN, new SessionOpen(SessionKind.BOT, "Miner",
                new BotIdentity("Miner", null, "example.com"), List.of()));
        FakePlugin.Frame f = p.await("error for session.open", x -> x.is(CoreMessages.ERROR) && x.env().req() == req);
        assertTrue(f.env().has(Envelope.RESPONSE) && f.env().has(Envelope.ERROR));
        ErrorMessage m = f.decode(CoreMessages.ERROR);
        assertEquals(CoreMessages.SESSION_OPEN.id(), m.relatesTo());
        return m.error().code();
    }

    @Test
    void anotherDeviceCannotTakeABotOver() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin first = FakePlugin.ready(h, "dev-1");
                FakePlugin other = FakePlugin.ready(h, "dev-2")) {
            first.openBot("Miner", "example.com");
            assertEquals("dev-1", h.core.devices().botDevice(IDENTITY));

            assertEquals("identity_taken", refused(other));
            assertEquals(1, h.core.sessionStats().size());
            assertFalse(h.core.sessionStats().get(0).superseded(), "the owner's session is untouched");
            other.openBot("Smelter", "example.com");

            // The same device may open it again, on this connection or another.
            try (FakePlugin again = FakePlugin.ready(h, "dev-1")) {
                again.openBot("Miner", "example.com");
                assertEquals("superseded", first.await(CoreMessages.ERROR).error().code());
            }
            assertTrue(h.core.devices().get("dev-1").bots().contains(IDENTITY));
        }
    }

    @Test
    void thePlayerCanGiveABotToAnotherDevice() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin first = FakePlugin.ready(h, "dev-1");
                FakePlugin other = FakePlugin.ready(h, "dev-2")) {
            first.openBot("Miner", "example.com");
            assertEquals("identity_taken", refused(other));

            assertTrue(h.core.devices().forgetBot(IDENTITY));
            assertNull(h.core.devices().botDevice(IDENTITY));
            other.openBot("Miner", "example.com");
            assertEquals("dev-2", h.core.devices().botDevice(IDENTITY));
            assertEquals("identity_taken", refused(first), "now it is the second device's");
            assertFalse(h.core.devices().forgetBot("nobody@example.com"));
        }
    }

    @Test
    void revokingADeviceFreesItsBots() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin first = FakePlugin.ready(h, "dev-1");
                FakePlugin other = FakePlugin.ready(h, "dev-2")) {
            first.openBot("Miner", "example.com");
            assertEquals("identity_taken", refused(other));
            h.core.devices().revoke("dev-1");
            other.openBot("Miner", "example.com");
            assertEquals("dev-2", h.core.devices().botDevice(IDENTITY));
        }
    }

    @Test
    void theClaimSurvivesARestart() throws Exception {
        // Without the approval prompt local devices are remembered, as in the game.
        try (Harness h = new Harness(dir, b -> b.askLocalApproval(false), DeviceApprover.ALLOW_ONCE);
                FakePlugin first = FakePlugin.ready(h, "dev-1")) {
            first.openBot("Miner", "example.com");
        }
        try (Harness h = new Harness(dir, b -> b.askLocalApproval(false), DeviceApprover.ALLOW_ONCE);
                FakePlugin other = FakePlugin.ready(h, "dev-2"); FakePlugin first = FakePlugin.ready(h, "dev-1")) {
            assertEquals("dev-1", h.core.devices().botDevice(IDENTITY));
            assertEquals("identity_taken", refused(other));
            first.openBot("Miner", "example.com");
        }
    }

    @Test
    void aBotOfTheSameNameOnAnotherServerReplacesTheOldOne() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev-1"); FakePlugin other = FakePlugin.ready(h, "dev-2")) {
            p.openBot("Miner", "example.com");
            other.openBot("Smelter", "example.com");
            p.openBot("Miner", "other.example.com");
            SessionClose closed = p.await(CoreMessages.SESSION_CLOSE);
            assertEquals("replaced", closed.reason());
            assertEquals(List.of("miner@other.example.com", "smelter@example.com"),
                    h.core.presence().all().stream().map(Presence::identity).toList(), "one entry per bot name");
            assertEquals(2, h.core.sessionStats().size());
        }
    }

    @Test
    void aRemoteDeviceNeverSupersedesALocalOne() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin local = FakePlugin.ready(h, "dev-1")) {
            local.openBot("Miner", "example.com");
            PairingSession session = h.core.pairing().begin("bots.example");
            try (FakePlugin remote = RemoteTest.tls(h, "remote-1")) {
                RemoteTest.pair(h, remote, session.code());
                assertEquals("identity_taken", refused(remote));
            }
            assertFalse(h.core.sessionStats().get(0).superseded());
        }
    }
}
