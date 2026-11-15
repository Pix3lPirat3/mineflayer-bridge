package io.github.pix3lpirat3.mfbridge.protocol.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * MessagePack writer appending to a {@link ByteBuf}.
 *
 * <p>Implements the subset the protocol uses. Integers always use the smallest encoding so the
 * output is byte-identical to the TypeScript writer. Not thread-safe; reuse one instance per thread via {@link
 * #target(ByteBuf)}.
 */
public final class WireWriter {
    private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);
    /** Largest integer a float64 represents exactly, 2^53 - 1. */
    private static final double MAX_SAFE = 9007199254740991d;

    private ByteBuf buf;

    /** Creates a writer that appends to {@code buf}. */
    public WireWriter(ByteBuf buf) {
        this.buf = buf;
    }

    /** Redirects subsequent writes to {@code buf}. */
    public WireWriter target(ByteBuf buf) {
        this.buf = buf;
        return this;
    }

    /** Returns the current target buffer. */
    public ByteBuf buffer() {
        return buf;
    }

    /** Unsigned LEB128; used only by the envelope header. */
    public void writeVarInt(long value) {
        if (value < 0) {
            throw WireException.invalid("varint out of range: " + value);
        }
        long v = value;
        while (v >= 0x80) {
            buf.writeByte((int) (v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf.writeByte((int) v);
    }

    /** Writes nil. */
    public void writeNil() {
        buf.writeByte(0xC0);
    }

    /** Writes a bool. */
    public void writeBool(boolean v) {
        buf.writeByte(v ? 0xC3 : 0xC2);
    }

    /** Signed integer in its smallest encoding. */
    public void writeInt(long v) {
        if (v >= 0) {
            writeUInt(v);
        } else if (v >= -32) {
            buf.writeByte((int) v);
        } else if (v >= Byte.MIN_VALUE) {
            buf.writeByte(0xD0).writeByte((int) v);
        } else if (v >= Short.MIN_VALUE) {
            buf.writeByte(0xD1).writeShort((int) v);
        } else if (v >= Integer.MIN_VALUE) {
            buf.writeByte(0xD2).writeInt((int) v);
        } else {
            buf.writeByte(0xD3).writeLong(v);
        }
    }

    /** Non-negative integer in its smallest encoding. */
    public void writeUInt(long v) {
        if (v < 0) {
            throw WireException.invalid("unsigned integer out of range: " + v);
        }
        if (v < 0x80) {
            buf.writeByte((int) v);
        } else if (v < 0x100) {
            buf.writeByte(0xCC).writeByte((int) v);
        } else if (v < 0x1_0000) {
            buf.writeByte(0xCD).writeShort((int) v);
        } else if (v < 0x1_0000_0000L) {
            buf.writeByte(0xCE).writeInt((int) v);
        } else {
            buf.writeByte(0xCF).writeLong(v);
        }
    }

    /** Treats {@code v} as unsigned 64-bit, so negative values encode as values at or above 2^63. */
    public void writeULong(long v) {
        if (v >= 0) {
            writeUInt(v);
        } else {
            buf.writeByte(0xCF).writeLong(v);
        }
    }

    /** Writes a float32. */
    public void writeFloat(float v) {
        buf.writeByte(0xCA).writeFloat(v);
    }

    /** Writes a float64. */
    public void writeDouble(double v) {
        buf.writeByte(0xCB).writeDouble(v);
    }

    /** UTF-8 string. Strings must be well-formed UTF-16; lone surrogates are not portable. */
    public void writeString(String v) {
        int len = ByteBufUtil.utf8Bytes(v);
        if (len < 32) {
            buf.writeByte(0xA0 | len);
        } else if (len < 0x100) {
            buf.writeByte(0xD9).writeByte(len);
        } else if (len < 0x1_0000) {
            buf.writeByte(0xDA).writeShort(len);
        } else {
            buf.writeByte(0xDB).writeInt(len);
        }
        buf.writeCharSequence(v, StandardCharsets.UTF_8);
    }

    private void writeBinHeader(int len) {
        if (len < 0x100) {
            buf.writeByte(0xC4).writeByte(len);
        } else if (len < 0x1_0000) {
            buf.writeByte(0xC5).writeShort(len);
        } else {
            buf.writeByte(0xC6).writeInt(len);
        }
    }

    /** Writes a bin value. */
    public void writeBytes(byte[] v) {
        writeBinHeader(v.length);
        buf.writeBytes(v);
    }

    /** Writes float64 values packed little-endian as {@code bin}. */
    public void writeDoubleArray(double[] v) {
        writeBinHeader(Math.multiplyExact(v.length, Double.BYTES));
        buf.ensureWritable(v.length * Double.BYTES);
        for (double d : v) {
            buf.writeDoubleLE(d);
        }
    }

    /** Writes float32 values packed little-endian as {@code bin}. */
    public void writeFloatArray(float[] v) {
        writeBinHeader(Math.multiplyExact(v.length, Float.BYTES));
        buf.ensureWritable(v.length * Float.BYTES);
        for (float f : v) {
            buf.writeFloatLE(f);
        }
    }

    /** Writes int32 values packed little-endian as {@code bin}. */
    public void writeIntArray(int[] v) {
        writeBinHeader(Math.multiplyExact(v.length, Integer.BYTES));
        buf.ensureWritable(v.length * Integer.BYTES);
        for (int i : v) {
            buf.writeIntLE(i);
        }
    }

    /** Writes u16 values, stored in {@code short}, packed little-endian as {@code bin}. */
    public void writeU16Array(short[] v) {
        writeBinHeader(Math.multiplyExact(v.length, Short.BYTES));
        buf.ensureWritable(v.length * Short.BYTES);
        for (short s : v) {
            buf.writeShortLE(s);
        }
    }

    /** Writes an array header for {@code n} elements. */
    public void writeArrayHeader(int n) {
        if (n < 0) {
            throw WireException.invalid("negative array length");
        }
        if (n < 16) {
            buf.writeByte(0x90 | n);
        } else if (n < 0x1_0000) {
            buf.writeByte(0xDC).writeShort(n);
        } else {
            buf.writeByte(0xDD).writeInt(n);
        }
    }

    /** Writes a map header for {@code n} entries. */
    public void writeMapHeader(int n) {
        if (n < 0) {
            throw WireException.invalid("negative map length");
        }
        if (n < 16) {
            buf.writeByte(0x80 | n);
        } else if (n < 0x1_0000) {
            buf.writeByte(0xDE).writeShort(n);
        } else {
            buf.writeByte(0xDF).writeInt(n);
        }
    }

    /**
     * Writes a dynamic value using the same mapping as the TypeScript writer: integral numbers within
     * the IEEE-754 safe range encode as integers, other numbers as float64, maps need string keys.
     */
    public void writeAny(@Nullable Object v) {
        switch (v) {
            case null -> writeNil();
            case Boolean b -> writeBool(b);
            case Byte b -> writeInt(b);
            case Short s -> writeInt(s);
            case Integer i -> writeInt(i);
            case Long l -> writeInt(l);
            case BigInteger big -> writeBigInteger(big);
            case Float f -> writeNumber(f.doubleValue());
            case Double d -> writeNumber(d);
            case String s -> writeString(s);
            case byte[] bytes -> writeBytes(bytes);
            case List<?> list -> {
                writeArrayHeader(list.size());
                for (Object item : list) {
                    writeAny(item);
                }
            }
            case Map<?, ?> map -> {
                writeMapHeader(map.size());
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!(e.getKey() instanceof String key)) {
                        throw WireException.invalid("map keys must be strings");
                    }
                    writeString(key);
                    writeAny(e.getValue());
                }
            }
            default -> throw WireException.invalid("cannot encode " + v.getClass().getName());
        }
    }

    private void writeNumber(double d) {
        if (d == Math.rint(d) && Math.abs(d) <= MAX_SAFE) {
            writeInt((long) d);
        } else {
            writeDouble(d);
        }
    }

    private void writeBigInteger(BigInteger v) {
        if (v.bitLength() <= 63) {
            writeInt(v.longValue());
        } else if (v.signum() > 0 && v.compareTo(TWO_64) < 0) {
            buf.writeByte(0xCF).writeLong(v.longValue());
        } else {
            throw WireException.invalid("integer out of 64-bit range: " + v);
        }
    }
}
