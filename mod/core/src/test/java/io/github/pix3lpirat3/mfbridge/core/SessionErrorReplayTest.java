package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.input.InputRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpened;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionResume;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionResumed;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldReport;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Error responses inside a session are sequenced and survive a resume. */
final class SessionErrorReplayTest {
    @TempDir
    Path dir;

    @Test
    void errorProducedWhileDetachedIsReplayedAfterResume() throws Exception {
        try (Harness h = new Harness(dir)) {
            FakePlugin p = FakePlugin.ready(h, "dev");
            SessionOpened opened = p.openBot("Miner", "example.com");
            int s = opened.sessionId();
            p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(InputTestSupport.BOT_WORLD, null));
            h.core.setPlayerContext(InputTestSupport.PLAYER_WORLD);
            p.sync();
            long req = p.reliableRequest(s, CoreMessages.PICK_REQUEST,
                    new PickRequest(PickKind.POINT, "where?", 60_000, null, null, null, false));
            h.tickUntil("pick active", () -> h.core.input().active() != null);
            InputRequest active = h.core.input().active();
            assertNotNull(active);

            p.abort();
            h.tickUntil("detached", () -> !h.core.sessionStats().get(0).connected());
            h.core.input().cancel(active.id());
            h.tick();

            try (FakePlugin p2 = FakePlugin.ready(h, "dev")) {
                long resumeReq = p2.request(0, CoreMessages.SESSION_RESUME, new SessionResume(opened.resumeToken(), 0));
                SessionResumed resumed = p2.await("resumed",
                                x -> x.is(CoreMessages.SESSION_RESUMED) && x.env().req() == resumeReq)
                        .decode(CoreMessages.SESSION_RESUMED);
                FakePlugin.Frame f = p2.await("replayed cancel",
                        x -> x.is(CoreMessages.ERROR) && x.env().session() == resumed.sessionId() && x.env().req() == req);
                assertTrue(f.env().has(Envelope.RESPONSE) && f.env().has(Envelope.ERROR));
                assertTrue(f.env().seq() != Envelope.NONE, "error responses are sequenced");
                ErrorMessage m = f.decode(CoreMessages.ERROR);
                assertEquals("cancelled", m.error().code());
                assertEquals(CoreMessages.PICK_REQUEST.id(), m.relatesTo());
            }
        }
    }
}
