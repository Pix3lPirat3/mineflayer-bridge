package io.github.pix3lpirat3.mfbridge.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The inbound queue's caps, on a channel without a socket. */
class NetConnectionTest {
    static InboundFrame frame(int bytes) {
        return new InboundFrame(null, new byte[bytes], "test");
    }

    static NetConnection connection(EmbeddedChannel ch, TransportLimits limits, Runnable onAuthenticated) {
        return new NetConnection(1, ch, true, false, "test:1", limits, onAuthenticated);
    }

    @Test
    void readingPausesAtTheFrameCapAndResumesHalfDrained() {
        EmbeddedChannel ch = new EmbeddedChannel();
        NetConnection c = connection(ch, new TransportLimits(1 << 20, 4, 1 << 20, 8, 1 << 16, 16), () -> {});
        c.authenticated();
        for (int i = 0; i < 3; i++) {
            c.offer(frame(10));
        }
        assertTrue(ch.config().isAutoRead());
        c.offer(frame(10));
        assertFalse(ch.config().isAutoRead(), "four frames queued");
        assertNotNull(c.poll());
        ch.runPendingTasks();
        assertFalse(ch.config().isAutoRead(), "three left is more than half");
        assertNotNull(c.poll());
        ch.runPendingTasks();
        assertTrue(ch.config().isAutoRead(), "two left: reading again");
        assertEquals(2, c.queued());
        assertEquals(20, c.queuedBytes());
    }

    @Test
    void readingPausesAtTheByteCapToo() {
        EmbeddedChannel ch = new EmbeddedChannel();
        NetConnection c = connection(ch, new TransportLimits(1 << 20, 1000, 1000, 8, 1 << 16, 16), () -> {});
        c.authenticated();
        c.offer(frame(600));
        assertTrue(ch.config().isAutoRead());
        c.offer(frame(600));
        assertFalse(ch.config().isAutoRead(), "two frames, but 1200 bytes");
        c.poll();
        ch.runPendingTasks();
        assertFalse(ch.config().isAutoRead(), "600 bytes is over half");
        c.poll();
        ch.runPendingTasks();
        assertTrue(ch.config().isAutoRead());
        assertNull(c.poll());
        assertEquals(0, c.queuedBytes());
    }

    @Test
    void aQueueDrainedBeforeThePauseLandsStillReadsAgain() {
        // The poll that empties the queue runs before reading is switched off; nothing may stay paused.
        EmbeddedChannel ch = new EmbeddedChannel();
        NetConnection c = connection(ch, new TransportLimits(1 << 20, 1, 1 << 20, 8, 1 << 16, 16), () -> {});
        c.authenticated();
        c.offer(frame(10));
        assertFalse(ch.config().isAutoRead());
        assertNotNull(c.poll());
        ch.runPendingTasks();
        assertTrue(ch.config().isAutoRead());
        for (int i = 0; i < 100; i++) {
            c.offer(frame(10));
            assertNotNull(c.poll());
            ch.runPendingTasks();
            assertTrue(ch.config().isAutoRead(), "round " + i);
        }
    }

    @Test
    void anUnknownPeerGetsAFewSmallFramesAndIsThenCutOff() {
        EmbeddedChannel ch = new EmbeddedChannel();
        NetConnection c = connection(ch, new TransportLimits(1 << 20, 1000, 1 << 20, 3, 100, 16), () -> {});
        c.offer(frame(10));
        c.offer(frame(10));
        c.offer(frame(10));
        assertTrue(ch.isOpen());
        c.offer(frame(10));
        assertFalse(ch.isOpen(), "the fourth frame before authentication closes it");
        assertEquals(3, c.queued(), "and is not queued");

        EmbeddedChannel big = new EmbeddedChannel();
        NetConnection d = connection(big, new TransportLimits(1 << 20, 1000, 1 << 20, 3, 100, 16), () -> {});
        d.offer(frame(101));
        assertFalse(big.isOpen(), "over the byte allowance in one frame");
        assertEquals(0, d.queued());
    }

    @Test
    void authenticationLiftsTheAllowanceAndIsReportedOnce() {
        EmbeddedChannel ch = new EmbeddedChannel();
        AtomicInteger reported = new AtomicInteger();
        NetConnection c = connection(ch, new TransportLimits(1 << 20, 1000, 1 << 20, 2, 100, 16), reported::incrementAndGet);
        c.offer(frame(10));
        c.authenticated();
        c.authenticated();
        assertEquals(1, reported.get());
        for (int i = 0; i < 50; i++) {
            c.offer(frame(500));
        }
        assertTrue(ch.isOpen());
        assertEquals(51, c.queued());
    }
}
