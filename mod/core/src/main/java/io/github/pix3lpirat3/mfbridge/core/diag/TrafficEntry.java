package io.github.pix3lpirat3.mfbridge.core.diag;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.core.util.Encoding;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import org.jspecify.annotations.Nullable;

/**
 * One captured frame of the traffic log.
 *
 * @param index position in the capture, counting from 1 since the bridge started
 * @param connectionId the connection's diagnostic id
 * @param device the device name the connection announced, or {@code unknown} before {@code hello}
 * @param inbound true for a frame sent by Node, false for one sent by the mod
 * @param timeMs wall-clock time of capture, epoch milliseconds
 * @param envelope the frame header as on the wire
 * @param payload the payload bytes; shared with the engine and never modified
 * @param type the message type, resolved through the connection's registry for extension
 *     messages; null when unknown
 */
@AnyThread
public record TrafficEntry(long index, long connectionId, String device, boolean inbound, long timeMs, Envelope envelope,
        byte[] payload, @Nullable MessageType<?> type) {

    /** Returns the message name, or the hex type id when unknown. */
    public String typeName() {
        MessageType<?> t = type;
        return t != null ? t.name() : "0x" + Integer.toHexString(envelope.type());
    }

    /** Returns the payload decoded through the schema codec, as readable text. */
    public String text() {
        return text(MessageText.DEFAULT_LIMIT);
    }

    /**
     * Returns the payload decoded through the schema codec as readable text, or a hex dump when the
     * type is unknown or the payload does not decode.
     *
     * @param limit most characters returned
     */
    public String text(int limit) {
        MessageType<?> t = type;
        if (t == null) {
            return MessageText.render(payload, limit);
        }
        try {
            return MessageText.render(Encoding.decode(t.codec(), payload), limit);
        } catch (RuntimeException e) {
            return "undecodable (" + e.getMessage() + ") " + MessageText.render(payload, limit);
        }
    }

    /** Returns a one-line header summary: direction, session, seq, ack and request ids. */
    public String header() {
        StringBuilder sb = new StringBuilder(32);
        sb.append(inbound ? "node>mod" : "mod>node").append(" s").append(envelope.session());
        if (envelope.seq() != Envelope.NONE) {
            sb.append(" seq ").append(envelope.seq());
        }
        if (envelope.ack() != Envelope.NONE) {
            sb.append(" ack ").append(envelope.ack());
        }
        if (envelope.req() != Envelope.NONE) {
            sb.append(" req ").append(envelope.req());
        }
        if (envelope.has(Envelope.ERROR)) {
            sb.append(" error");
        }
        return sb.toString();
    }

    /** Returns the whole frame, header plus payload, exactly as it is written on the wire. */
    public byte[] frameBytes() {
        return frameBytes(envelope, payload);
    }

    /** Encodes a header and payload into one frame. */
    public static byte[] frameBytes(Envelope envelope, byte[] payload) {
        return Encoding.write(16 + payload.length, w -> {
            envelope.write(w);
            w.buffer().writeBytes(payload);
        });
    }
}
