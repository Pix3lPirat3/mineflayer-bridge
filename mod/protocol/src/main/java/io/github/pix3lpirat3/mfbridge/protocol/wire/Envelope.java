package io.github.pix3lpirat3.mfbridge.protocol.wire;

/**
 * Frame header. Absent sequence, ack and request ids are {@link #NONE}; the
 * matching flag bits are derived from presence when writing.
 */
public record Envelope(int flags, int type, int session, long seq, long ack, long req) {
    /** Flag: the frame carries a sequence number. */
    public static final int RELIABLE = 1;
    /** Flag: the frame answers the request in {@code req}. */
    public static final int RESPONSE = 1 << 1;
    /** Flag: the frame is a {@code core:Error}. */
    public static final int ERROR = 1 << 2;
    /** Flag: the frame carries an ack. */
    public static final int HAS_ACK = 1 << 3;
    /** Flag: the frame carries a request id. */
    public static final int HAS_REQ = 1 << 4;
    /** Marks an absent sequence, ack or request id. */
    public static final long NONE = -1;

    private static final int RESERVED_MASK = 0xE0;
    private static final int DERIVED = RELIABLE | HAS_ACK | HAS_REQ;

    /**
     * Validates the header.
     *
     * @throws WireException when {@link #RESPONSE} is set without a request id
     */
    public Envelope {
        if ((flags & RESPONSE) != 0 && req == NONE) {
            throw WireException.invalid("response without request id");
        }
    }

    /** Returns a header without sequence, ack or request id. */
    public static Envelope control(int type, int session) {
        return new Envelope(0, type, session, NONE, NONE, NONE);
    }

    /** Returns whether {@code flag} is set in {@link #flags}. */
    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    /** Writes the header, deriving the presence flags from the ids. */
    public void write(WireWriter w) {
        w.writeVarInt(wireFlags());
        w.writeVarInt(type);
        w.writeVarInt(session);
        if (seq != NONE) {
            w.writeVarInt(seq);
        }
        if (ack != NONE) {
            w.writeVarInt(ack);
        }
        if (req != NONE) {
            w.writeVarInt(req);
        }
    }

    /** Reads the header; the reader is left at the payload. */
    public static Envelope read(WireReader r) {
        long flags = r.readVarInt();
        if (flags > 0xFF || (flags & RESERVED_MASK) != 0) {
            throw WireException.invalid("reserved flag bits set: " + flags);
        }
        int type = toInt(r.readVarInt(), "type");
        int session = toInt(r.readVarInt(), "session");
        long seq = (flags & RELIABLE) != 0 ? r.readVarInt() : NONE;
        long ack = (flags & HAS_ACK) != 0 ? r.readVarInt() : NONE;
        long req = (flags & HAS_REQ) != 0 ? r.readVarInt() : NONE;
        return new Envelope((int) flags, type, session, seq, ack, req);
    }

    /** Returns the number of bytes {@link #write} produces. */
    public int encodedSize() {
        int n = varIntSize(wireFlags()) + varIntSize(type) + varIntSize(session);
        if (seq != NONE) {
            n += varIntSize(seq);
        }
        if (ack != NONE) {
            n += varIntSize(ack);
        }
        if (req != NONE) {
            n += varIntSize(req);
        }
        return n;
    }

    private int wireFlags() {
        int f = flags & ~DERIVED;
        if (seq != NONE) {
            f |= RELIABLE;
        }
        if (ack != NONE) {
            f |= HAS_ACK;
        }
        if (req != NONE) {
            f |= HAS_REQ;
        }
        return f;
    }

    private static int varIntSize(long v) {
        int n = 1;
        for (long x = v; x >= 0x80; x >>>= 7) {
            n++;
        }
        return n;
    }

    private static int toInt(long v, String what) {
        if (v > Integer.MAX_VALUE) {
            throw WireException.invalid(what + " out of range: " + v);
        }
        return (int) v;
    }
}
