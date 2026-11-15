package io.github.pix3lpirat3.mfbridge.core.diag;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The live traffic log of the inspector: the last {@link #CAPACITY}
 * frames of every connection. Capture is off by default and costs one boolean check per frame;
 * while on, each frame costs one small entry (payloads are shared, not copied).
 */
@ClientThread
public final class TrafficLog {
    /** Frames kept. */
    public static final int CAPACITY = 2_048;

    private final @Nullable TrafficEntry[] ring = new TrafficEntry[CAPACITY];
    private int next;
    private int count;
    private long total;
    private boolean capturing;

    /** Creates an empty log that is not capturing. */
    public TrafficLog() {}

    /** Returns whether frames are being captured. */
    public boolean capturing() {
        return capturing;
    }

    /** Starts or stops capture; the entries already captured are kept. */
    public void capturing(boolean on) {
        capturing = on;
    }

    /**
     * Captures a frame. Called by the engine only while {@link #capturing()}.
     *
     * @param payload shared, never modified afterwards
     */
    public void add(long connectionId, String device, boolean inbound, long timeMs, Envelope envelope, byte[] payload,
            @Nullable MessageType<?> type) {
        total++;
        ring[next] = new TrafficEntry(total, connectionId, device, inbound, timeMs, envelope, payload, type);
        next = (next + 1) % CAPACITY;
        if (count < CAPACITY) {
            count++;
        }
    }

    /** Returns the number of frames captured since the bridge started, including dropped ones. */
    public long total() {
        return total;
    }

    /** Returns the held entries, oldest first. The list is a copy. */
    public List<TrafficEntry> entries() {
        List<TrafficEntry> out = new ArrayList<>(count);
        int start = (next - count + CAPACITY) % CAPACITY;
        for (int i = 0; i < count; i++) {
            TrafficEntry e = ring[(start + i) % CAPACITY];
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    /** Drops every held entry. */
    public void clear() {
        Arrays.fill(ring, null);
        next = 0;
        count = 0;
    }
}
