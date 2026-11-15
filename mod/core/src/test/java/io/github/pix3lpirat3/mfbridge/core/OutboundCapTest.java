package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.remote.DeviceInfo;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.PathData;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedCreate;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpBody;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedSubscribe;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A peer that stops reading is disconnected instead of being queued for without end. */
class OutboundCapTest {
    @TempDir
    Path dir;

    static int connections(Harness h) {
        DeviceInfo d = h.core.devices().get("dev");
        return d == null ? 0 : d.connections();
    }

    @Test
    void aPeerThatStopsReadingIsDisconnected() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> b.maxOutboundBytes(1 << 20).sharedLimits(10_000, 1L << 30));
                FakePlugin p = FakePlugin.ready(h, "dev")) {
            h.tickUntil("global store", h.core::storesLoaded);
            int s = p.openBot("Miner", "example.com").sessionId();
            p.reliable(s, CoreMessages.SHARED_SUBSCRIBE, new SharedSubscribe(List.of(), List.of()));
            p.sync();
            assertEquals(1, connections(h));
            p.stopReading();

            // Every shared path is sent to the subscriber: about 440 KiB each, and it reads none of them.
            byte[] path = CoreKinds.PATH.encode(new PathData(new double[16_384 * 3], null, null, null));
            int sent = 0;
            while (connections(h) == 1 && sent < 600) {
                h.core.sharedOp(new SharedOpBody.Create(new SharedCreate(
                        FakePlugin.object("shared/paths/" + sent, CoreKinds.PATH, 0, path))));
                sent++;
                h.tick();
            }
            assertEquals(0, connections(h), "still connected after " + sent + " paths went unread");
            assertTrue(sent > 2, "the first megabyte is queued without complaint");
        }
    }
}
