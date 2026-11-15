package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.ObjectSyncTest.box;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.diag.ConnectionInfo;
import io.github.pix3lpirat3.mfbridge.core.diag.ObjectInfo;
import io.github.pix3lpirat3.mfbridge.core.diag.SessionInfo;
import io.github.pix3lpirat3.mfbridge.core.diag.TrafficEntry;
import io.github.pix3lpirat3.mfbridge.core.record.RecordedFrame;
import io.github.pix3lpirat3.mfbridge.core.record.RecordingFormat;
import io.github.pix3lpirat3.mfbridge.core.record.ReplayHandle;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudAnchor;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudPanelData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudTextData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjRemove;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldReport;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireReader;
import io.netty.buffer.Unpooled;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecorderTest {
    private static final String P = InterestRenderTest.P;

    @TempDir
    Path dir;

    /** The bot's objects as key to revision and decoded text, which is what rendering depends on. */
    private static Map<String, String> state(Harness h) {
        Map<String, String> out = new TreeMap<>();
        for (ObjectInfo o : h.core.inspector().objects()) {
            if (o.key().startsWith(P)) {
                out.put(o.key(), o.kind() + " r" + o.rev() + " " + o.dataText());
            }
        }
        return out;
    }

    private static void scene(FakePlugin p, int s) {
        p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(InterestRenderTest.BOT_WORLD, null));
        p.upsert(s, FakePlugin.object(P + "viz/a", CoreKinds.BOX, 1, box(1)),
                FakePlugin.object(P + "viz/b", CoreKinds.BOX, 1, box(2)),
                FakePlugin.object(P + "vitals/~hud/main", CoreKinds.HUD_PANEL, 1,
                        CoreKinds.HUD_PANEL.encode(new HudPanelData("Vitals", HudAnchor.TOP_LEFT, 0, false))),
                FakePlugin.object(P + "vitals/~hud/main/hp", CoreKinds.HUD_TEXT, 1,
                        CoreKinds.HUD_TEXT.encode(new HudTextData("main", 0, "HP 20", null, null))));
    }

    private static void more(FakePlugin p, int s) {
        p.upsert(s, FakePlugin.object(P + "viz/a", CoreKinds.BOX, 2, box(5)),
                FakePlugin.object(P + "viz/c", CoreKinds.BOX, 1, box(3)));
        p.reliable(s, CoreMessages.OBJ_REMOVE, new ObjRemove(List.of(P + "viz/b"), List.of()));
        p.sync();
    }

    private static Map<String, String> replay(Path configDir, Path file) throws Exception {
        try (Harness h = new Harness(configDir)) {
            ReplayHandle r = h.core.recorder().replay(file, 0);
            h.tickUntil("replay finished", r::finished);
            h.tickFor(50);
            Map<String, String> replayed = state(h);
            boolean onReplay = false;
            for (ConnectionInfo c : h.core.inspector().connections()) {
                onReplay |= c.remoteAddress().startsWith("replay:") && c.state().equals("ready");
            }
            assertTrue(onReplay, "the replay runs on a ready virtual connection");
            List<SessionInfo> sessions = h.core.inspector().sessions();
            assertEquals(1, sessions.size());
            assertEquals("Miner", sessions.get(0).displayName());
            r.stop();
            h.tickUntil("replay connection closed", () -> h.core.inspector().connections().isEmpty());
            return replayed;
        }
    }

    @Test
    void replayOfAWholeConnectionRebuildsTheStore() throws Exception {
        Path file;
        Map<String, String> expected;
        try (Harness h = new Harness(dir.resolve("live"))) {
            h.core.recorder().start();
            assertTrue(h.core.recorder().recording());
            try (FakePlugin p = FakePlugin.ready(h, "dev")) {
                int s = p.openBot("Miner", "example.com").sessionId();
                scene(p, s);
                more(p, s);
                expected = state(h);
                List<Path> files = h.core.recorder().stop();
                assertEquals(1, files.size());
                file = files.get(0);
            }
        }
        assertEquals(4, expected.size(), "a, c, the panel and its widget: " + expected);
        assertFalse(expected.containsKey(P + "viz/b"));

        List<RecordedFrame> frames = RecordingFormat.readAll(file);
        assertEquals(RecordingFormat.FROM_NODE, frames.get(0).direction(), "hello comes first");
        assertTrue(frames.stream().anyMatch(f -> f.direction() == RecordingFormat.FROM_MOD));
        for (RecordedFrame f : frames) {
            int type = Envelope.read(new WireReader(Unpooled.wrappedBuffer(f.bytes()))).type();
            assertTrue(type != CoreMessages.AUTH_RESPONSE.id() && type != CoreMessages.PAIR_RESULT.id(),
                    "credential frames are never recorded");
        }

        assertEquals(expected, replay(dir.resolve("replay"), file));
    }

    @Test
    void recordingStartedMidStreamBeginsWithAPrelude() throws Exception {
        Path file;
        Map<String, String> expected;
        try (Harness h = new Harness(dir.resolve("live")); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            scene(p, s);
            p.sync();
            h.core.recorder().start();
            more(p, s);
            expected = state(h);
            file = h.core.recorder().stop().get(0);
        }
        assertEquals(expected, replay(dir.resolve("replay"), file));
    }

    @Test
    void trafficCaptureDecodesAndExports() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.sync();
            assertTrue(h.core.inspector().traffic().entries().isEmpty(), "no capture while the tab is closed");
            h.core.inspector().traffic().capturing(true);
            scene(p, s);
            p.sync();
            h.core.inspector().traffic().capturing(false);
            List<TrafficEntry> entries = h.core.inspector().traffic().entries();
            TrafficEntry upsert = entries.stream().filter(e -> e.typeName().equals("obj.upsert")).findFirst().orElseThrow();
            assertTrue(upsert.inbound());
            assertTrue(upsert.text().contains("\"" + P + "viz/a\""), upsert.text());
            assertTrue(upsert.text().contains("writes: ["), upsert.text());
            assertTrue(entries.stream().anyMatch(e -> !e.inbound() && e.typeName().equals("pong")));

            List<Path> exported = h.core.recorder().export(entries);
            assertEquals(1, exported.size());
            List<RecordedFrame> frames = RecordingFormat.readAll(exported.get(0));
            assertEquals(entries.size(), frames.size());
            assertTrue(java.util.Arrays.equals(upsert.frameBytes(), frames.get(entries.indexOf(upsert)).bytes()));

            ConnectionInfo c = h.core.inspector().connections().get(0);
            assertTrue(c.framesIn() > 0 && c.bytesIn() > 0 && c.framesOut() > 0 && c.bytesOut() > 0);
            assertEquals(1, c.sessions());
        }
    }
}
