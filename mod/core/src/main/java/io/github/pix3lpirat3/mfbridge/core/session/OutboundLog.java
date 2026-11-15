package io.github.pix3lpirat3.mfbridge.core.session;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Unacknowledged reliable messages of one session in their encoded form.
 * Entries are dropped at or below each ack.
 *
 * <p>Capped by payload bytes. Exceeding the cap is not an error: the log is
 * cleared and remembers the highest sequence it could not keep. Resume is impossible until the peer
 * has applied that sequence, after which the log is complete again.
 */
final class OutboundLog {
    /** Rough per-entry overhead counted against the cap, in bytes. */
    static final int ENTRY_OVERHEAD = 32;

    private final ArrayDeque<OutFrame> entries = new ArrayDeque<>();
    private final long capBytes;
    private long bytes;
    private long overflowSeq;

    OutboundLog(long capBytes) {
        this.capBytes = capBytes;
    }

    void append(OutFrame frame) {
        long size = frame.payload().length + ENTRY_OVERHEAD;
        if (bytes + size > capBytes) {
            entries.clear();
            bytes = 0;
            overflowSeq = frame.seq();
            return;
        }
        entries.addLast(frame);
        bytes += size;
    }

    /** Drops entries at or below {@code ack}. */
    void trim(long ack) {
        while (!entries.isEmpty() && entries.peekFirst().seq() <= ack) {
            bytes -= entries.pollFirst().payload().length + ENTRY_OVERHEAD;
        }
        if (overflowSeq != 0 && ack >= overflowSeq) {
            overflowSeq = 0;
        }
    }

    /** Returns whether every message above {@code peerLastApplied} is still in the log. */
    boolean canReplayFrom(long peerLastApplied) {
        return overflowSeq == 0 || peerLastApplied >= overflowSeq;
    }

    /** Returns logged entries above {@code seq}, oldest first. */
    List<OutFrame> above(long seq) {
        return entries.stream().filter(f -> f.seq() > seq).toList();
    }

    int size() {
        return entries.size();
    }

    long bytes() {
        return bytes;
    }

    boolean overflowed() {
        return overflowSeq != 0;
    }
}
