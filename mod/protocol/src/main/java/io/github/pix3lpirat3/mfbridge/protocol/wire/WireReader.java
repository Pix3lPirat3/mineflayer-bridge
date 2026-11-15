package io.github.pix3lpirat3.mfbridge.protocol.wire;

import io.netty.buffer.ByteBuf;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * MessagePack reader over the readable bytes of a {@link ByteBuf}.
 *
 * <p>Every read validates the type tag and bounds and throws {@link WireException} with code {@code
 * invalid_message} on mismatch. Not thread-safe.
 */
public final class WireReader {
    /** Upper bound on container nesting, so hostile input cannot overflow the stack. */
    private static final int MAX_DEPTH = 64;

    private ByteBuf buf;
    /** Scratch out-parameter of {@link #readRawInteger}. */
    private final boolean[] overflow = new boolean[1];

    /** Creates a reader that consumes the readable bytes of {@code buf}. */
    public WireReader(ByteBuf buf) {
        this.buf = buf;
    }

    /** Switches to another buffer, so one reader can be reused; returns this reader. */
    public WireReader source(ByteBuf buf) {
        this.buf = buf;
        return this;
    }

    /** Returns the current buffer. */
    public ByteBuf buffer() {
        return buf;
    }

    /** Returns the number of unread bytes. */
    public int remaining() {
        return buf.readableBytes();
    }

    private void need(int n) {
        if (buf.readableBytes() < n) {
            throw WireException.invalid("unexpected end of message");
        }
    }

    private int u8() {
        need(1);
        return buf.readUnsignedByte();
    }

    private int u16() {
        need(2);
        return buf.readUnsignedShort();
    }

    /** Reads a u32 length and checks it against the unread bytes. */
    private int u32Length() {
        need(4);
        return checkedLength(buf.readUnsignedInt());
    }

    private int peek() {
        need(1);
        return buf.getUnsignedByte(buf.readerIndex());
    }

    /** Reads an unsigned LEB128 varint of at most 63 bits (the envelope encoding, not MessagePack). */
    public long readVarInt() {
        long result = 0;
        for (int shift = 0; shift < 63; shift += 7) {
            int b = u8();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw WireException.invalid("varint too long");
    }

    /** Consumes a nil if one is next. */
    public boolean tryReadNil() {
        if (peek() == 0xC0) {
            buf.skipBytes(1);
            return true;
        }
        return false;
    }

    /** Reads a bool. */
    public boolean readBool() {
        int t = u8();
        if (t == 0xC2) {
            return false;
        }
        if (t == 0xC3) {
            return true;
        }
        throw typeError("bool", t);
    }

    /**
     * Reads any integer encoding. Values of uint64 above {@link Long#MAX_VALUE} are returned with
     * their raw bit pattern; {@code unsignedOverflow} reports that case to callers.
     */
    private long readRawInteger(boolean[] unsignedOverflow) {
        int t = u8();
        if (t < 0x80) {
            return t;
        }
        if (t >= 0xE0) {
            return t - 0x100;
        }
        return switch (t) {
            case 0xCC -> u8();
            case 0xCD -> u16();
            case 0xCE -> {
                need(4);
                yield buf.readUnsignedInt();
            }
            case 0xCF -> {
                need(8);
                long v = buf.readLong();
                unsignedOverflow[0] = v < 0;
                yield v;
            }
            case 0xD0 -> {
                need(1);
                yield buf.readByte();
            }
            case 0xD1 -> {
                need(2);
                yield buf.readShort();
            }
            case 0xD2 -> {
                need(4);
                yield buf.readInt();
            }
            case 0xD3 -> {
                need(8);
                yield buf.readLong();
            }
            default -> throw typeError("int", t);
        };
    }

    /** Reads any integer encoding that fits a signed 64-bit value. */
    public long readLong() {
        overflow[0] = false;
        long v = readRawInteger(overflow);
        if (overflow[0]) {
            throw WireException.invalid("integer exceeds signed 64-bit range");
        }
        return v;
    }

    /** Unsigned 64-bit; values at or above 2^63 are returned as negative longs (raw bits). */
    public long readULong() {
        overflow[0] = false;
        long v = readRawInteger(overflow);
        if (v < 0 && !overflow[0]) {
            throw WireException.invalid("expected unsigned integer, got " + v);
        }
        return v;
    }

    /** Reads a signed 32-bit integer. */
    public int readInt() {
        long v = readLong();
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw WireException.invalid("integer exceeds 32-bit range: " + v);
        }
        return (int) v;
    }

    /** Unsigned 32-bit; values above {@link Integer#MAX_VALUE} are returned as negative ints. */
    public int readUInt() {
        long v = readLong();
        if (v < 0 || v > 0xFFFF_FFFFL) {
            throw WireException.invalid("expected unsigned 32-bit integer, got " + v);
        }
        return (int) v;
    }

    /** Reads a float; integer encodings are accepted for robustness against dynamic encoders. */
    public double readDouble() {
        int t = peek();
        if (t == 0xCA) {
            need(5);
            buf.skipBytes(1);
            return buf.readFloat();
        }
        if (t == 0xCB) {
            need(9);
            buf.skipBytes(1);
            return buf.readDouble();
        }
        return readLong();
    }

    /** Reads a float as {@link #readDouble} does, narrowed to {@code float}. */
    public float readFloat() {
        return (float) readDouble();
    }

    private int readStringLength() {
        int t = u8();
        if ((t & 0xE0) == 0xA0) {
            return t & 0x1F;
        }
        return switch (t) {
            case 0xD9 -> u8();
            case 0xDA -> u16();
            case 0xDB -> u32Length();
            default -> throw typeError("str", t);
        };
    }

    /** Reads a UTF-8 string. */
    public String readString() {
        int len = readStringLength();
        need(len);
        return buf.readCharSequence(len, StandardCharsets.UTF_8).toString();
    }

    private int readBinLength() {
        int t = u8();
        return switch (t) {
            case 0xC4 -> u8();
            case 0xC5 -> u16();
            case 0xC6 -> u32Length();
            default -> throw typeError("bin", t);
        };
    }

    /** Reads a bin value into a new array. */
    public byte[] readBytes() {
        int len = readBinLength();
        need(len);
        byte[] out = new byte[len];
        buf.readBytes(out);
        return out;
    }

    private int readPackedCount(int width) {
        int len = readBinLength();
        if (len % width != 0) {
            throw WireException.invalid("packed array length " + len + " not a multiple of " + width);
        }
        need(len);
        return len / width;
    }

    /** Reads a bin of packed little-endian float64 values. */
    public double[] readDoubleArray() {
        double[] out = new double[readPackedCount(Double.BYTES)];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.readDoubleLE();
        }
        return out;
    }

    /** Reads a bin of packed little-endian float32 values. */
    public float[] readFloatArray() {
        float[] out = new float[readPackedCount(Float.BYTES)];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.readFloatLE();
        }
        return out;
    }

    /** Reads a bin of packed little-endian int32 values. */
    public int[] readIntArray() {
        int[] out = new int[readPackedCount(Integer.BYTES)];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.readIntLE();
        }
        return out;
    }

    /** Reads a bin of packed little-endian u16 values, stored in {@code short}. */
    public short[] readU16Array() {
        short[] out = new short[readPackedCount(Short.BYTES)];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.readShortLE();
        }
        return out;
    }

    /** Reads an array header and returns its element count. */
    public int readArrayHeader() {
        int t = u8();
        if ((t & 0xF0) == 0x90) {
            return t & 0x0F;
        }
        return switch (t) {
            case 0xDC -> u16();
            case 0xDD -> u32Length();
            default -> throw typeError("array", t);
        };
    }

    /** Reads a map header and returns its entry count. */
    public int readMapHeader() {
        int t = u8();
        if ((t & 0xF0) == 0x80) {
            return t & 0x0F;
        }
        return switch (t) {
            case 0xDE -> u16();
            case 0xDF -> u32Length();
            default -> throw typeError("map", t);
        };
    }

    /**
     * Decodes a dynamic value: null, Boolean, Long (BigInteger above 2^63), Double, String, byte[],
     * List of values, or Map with String keys (insertion ordered).
     */
    public @Nullable Object readAny() {
        return readAny(0);
    }

    private @Nullable Object readAny(int depth) {
        if (depth > MAX_DEPTH) {
            throw WireException.invalid("nesting deeper than " + MAX_DEPTH);
        }
        int t = peek();
        if (t == 0xC0) {
            buf.skipBytes(1);
            return null;
        }
        if (t == 0xC2 || t == 0xC3) {
            return readBool();
        }
        if (t < 0x80 || t >= 0xE0 || (t >= 0xCC && t <= 0xD3)) {
            overflow[0] = false;
            long v = readRawInteger(overflow);
            return overflow[0] ? new BigInteger(Long.toUnsignedString(v)) : (Object) v;
        }
        if (t == 0xCA || t == 0xCB) {
            return readDouble();
        }
        if ((t & 0xE0) == 0xA0 || t == 0xD9 || t == 0xDA || t == 0xDB) {
            return readString();
        }
        if (t == 0xC4 || t == 0xC5 || t == 0xC6) {
            return readBytes();
        }
        if ((t & 0xF0) == 0x90 || t == 0xDC || t == 0xDD) {
            int n = readArrayHeader();
            List<@Nullable Object> list = new ArrayList<>(Math.min(n, 1024));
            for (int i = 0; i < n; i++) {
                list.add(readAny(depth + 1));
            }
            return list;
        }
        if ((t & 0xF0) == 0x80 || t == 0xDE || t == 0xDF) {
            int n = readMapHeader();
            Map<String, @Nullable Object> map = new LinkedHashMap<>(Math.min(n, 1024) * 2);
            for (int i = 0; i < n; i++) {
                String key = readString();
                map.put(key, readAny(depth + 1));
            }
            return map;
        }
        throw typeError("value", t);
    }

    /** Skips {@code count} values without allocating. Non-positive counts are a no-op. */
    public void skipValues(int count) {
        for (int i = 0; i < count; i++) {
            skipValue(0);
        }
    }

    private void skipValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw WireException.invalid("nesting deeper than " + MAX_DEPTH);
        }
        int t = u8();
        if (t < 0x80 || t >= 0xE0 || t == 0xC0 || t == 0xC2 || t == 0xC3) {
            return;
        }
        if ((t & 0xE0) == 0xA0) {
            skip(t & 0x1F);
            return;
        }
        if ((t & 0xF0) == 0x90) {
            skipContainer(t & 0x0F, depth);
            return;
        }
        if ((t & 0xF0) == 0x80) {
            skipContainer((t & 0x0F) * 2, depth);
            return;
        }
        switch (t) {
            case 0xCC, 0xD0 -> skip(1);
            case 0xCD, 0xD1 -> skip(2);
            case 0xCE, 0xD2, 0xCA -> skip(4);
            case 0xCF, 0xD3, 0xCB -> skip(8);
            case 0xD9, 0xC4 -> skip(u8());
            case 0xDA, 0xC5 -> skip(u16());
            case 0xDB, 0xC6 -> skip(u32Length());
            case 0xDC -> skipContainer(u16(), depth);
            case 0xDD -> skipContainer(u32Length(), depth);
            case 0xDE -> skipContainer(u16() * 2, depth);
            case 0xDF -> skipContainer(Math.multiplyExact(u32Length(), 2), depth);
            default -> throw typeError("value", t);
        }
    }

    private void skipContainer(int items, int depth) {
        for (int i = 0; i < items; i++) {
            skipValue(depth + 1);
        }
    }

    private void skip(int n) {
        need(n);
        buf.skipBytes(n);
    }

    private int checkedLength(long len) {
        if (len > buf.readableBytes()) {
            throw WireException.invalid("declared length " + len + " exceeds message");
        }
        return (int) len;
    }

    private WireException typeError(String expected, int tag) {
        return WireException.invalid(
                "expected " + expected + ", found tag 0x" + Integer.toHexString(tag) + " at " + (buf.readerIndex() - 1));
    }
}
