package io.github.pix3lpirat3.mfbridge.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.protocol.core.BotIdentity;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutboundLogTest {
    static OutFrame frame(long seq, int size) {
        return new OutFrame(0, 0x42, 1, null, seq, Envelope.NONE, new byte[size]);
    }

    @Test
    void trimsOnAckAndReplaysAbove() {
        OutboundLog log = new OutboundLog(1 << 20);
        for (int i = 1; i <= 5; i++) {
            log.append(frame(i, 10));
        }
        assertEquals(5, log.size());
        log.trim(2);
        assertEquals(3, log.size());
        assertEquals(3 * (10 + OutboundLog.ENTRY_OVERHEAD), log.bytes());
        assertEquals(List.of(4L, 5L), log.above(3).stream().map(OutFrame::seq).toList());
        assertTrue(log.canReplayFrom(0));
    }

    @Test
    void overflowBlocksResumeUntilPeerCatchesUp() {
        OutboundLog log = new OutboundLog(100);
        log.append(frame(1, 40));
        log.append(frame(2, 40));
        assertTrue(log.overflowed());
        assertEquals(0, log.size());
        log.append(frame(3, 10));
        assertFalse(log.canReplayFrom(1));
        assertTrue(log.canReplayFrom(2));
        log.trim(2);
        assertFalse(log.overflowed());
        assertEquals(List.of(3L), log.above(0).stream().map(OutFrame::seq).toList());
    }

    @Test
    void identityKeyPrefersUuid() {
        assertEquals("4f1c", SessionLifecycle.identityKey(new BotIdentity("Miner", "4f1c", "x")));
        assertEquals("miner@example.com", SessionLifecycle.identityKey(new BotIdentity("Miner", null, "example.com")));
        assertEquals("miner@unknown", SessionLifecycle.identityKey(new BotIdentity("Miner", null, null)));
    }
}
