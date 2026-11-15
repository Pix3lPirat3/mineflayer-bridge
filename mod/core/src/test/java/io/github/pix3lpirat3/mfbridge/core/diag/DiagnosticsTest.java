package io.github.pix3lpirat3.mfbridge.core.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.protocol.core.BoxData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudPanelData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudAnchor;
import io.github.pix3lpirat3.mfbridge.protocol.core.Ping;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnosticsTest {
    @Test
    void messageTextReadsLikeJson() {
        assertEquals("{time: 42}", MessageText.render(new Ping(42)));
        assertEquals("{min: {x: 0, y: 1.5, z: -2}, size: {x: 1, y: 2, z: 3}}",
                MessageText.render(new BoxData(new Vec3d(0, 1.5, -2), new Vec3d(1, 2, 3), null)));
        assertEquals("{title: \"a \\\"b\\\"\\n\", anchor: \"top_left\", order: 0, collapsed: false}",
                MessageText.render(new HudPanelData("a \"b\"\n", HudAnchor.TOP_LEFT, 0, false)));
        assertEquals("<3 bytes 010203>", MessageText.render(new byte[] {1, 2, 3}));
        String cut = MessageText.render(java.util.Collections.nCopies(10_000, "xxxxxxxx"), 100);
        assertEquals(100, cut.length());
        assertTrue(cut.endsWith("..."));
    }

    @Test
    void boundsCoverPositionsAndWholeBlocks() {
        ObjectBounds.Box b = ObjectBounds.of(new BoxData(new Vec3d(0, 1, 2), new Vec3d(4, 5, 6), null));
        assertEquals(new ObjectBounds.Box(0, 1, 2, 4, 6, 8), b);
        assertEquals(new ObjectBounds.Box(3, 64, -1, 4, 65, 0), ObjectBounds.of(List.of(new Vec3i(3, 64, -1))));
        assertNull(ObjectBounds.of(new HudPanelData("t", HudAnchor.TOP_LEFT, 0, false)));
        assertNull(ObjectBounds.of(null));
    }

    @Test
    void ringsAndLogsKeepTheNewest() {
        TimingRing ring = new TimingRing(3);
        assertEquals(0, ring.average());
        for (long v : new long[] {10, 20, 30, 40}) {
            ring.record(v);
        }
        assertEquals(3, ring.count());
        assertEquals(40, ring.last());
        assertEquals(30, ring.average());
        assertEquals(40, ring.max());

        TrafficLog log = new TrafficLog();
        assertFalse(log.capturing());
        for (int i = 0; i < TrafficLog.CAPACITY + 5; i++) {
            log.add(1, "dev", true, i, Envelope.control(0x01, 0), new byte[0], null);
        }
        List<TrafficEntry> entries = log.entries();
        assertEquals(TrafficLog.CAPACITY, entries.size());
        assertEquals(6, entries.get(0).index());
        assertEquals(TrafficLog.CAPACITY + 5, log.total());
        log.clear();
        assertTrue(log.entries().isEmpty());
    }

    @Test
    void objectSearchMatchesEveryTerm() {
        ObjectInfo o = new ObjectInfo("bot:miner@x/viz/route", "bot:miner@x", "viz", "paths", "core:path", 3, Scope.GLOBAL,
                null, 10, true, null);
        assertTrue(o.matches(""));
        assertTrue(o.matches("MINER path"));
        assertFalse(o.matches("miner box"));
        assertEquals("<10 bytes, not decoded>", o.dataText());
    }
}
