package io.github.pix3lpirat3.mfbridge.core.remote;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A minimal DER encoder (ITU-T X.690) for the handful of ASN.1 types an X.509 v3 certificate needs.
 * Every method returns a complete tag-length-value encoding.
 */
@AnyThread
final class Der {
    static final int INTEGER = 0x02;
    static final int BIT_STRING = 0x03;
    static final int OBJECT_IDENTIFIER = 0x06;
    static final int UTF8_STRING = 0x0c;
    static final int UTC_TIME = 0x17;
    static final int GENERALIZED_TIME = 0x18;
    static final int SEQUENCE = 0x30;
    static final int SET = 0x31;

    private static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter GENERALIZED =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private Der() {}

    /** Encodes one value with a tag and definite length; lengths of 128 and more use the long form. */
    static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        int n = content.length;
        if (n < 0x80) {
            out.write(n);
        } else {
            int bytes = (32 - Integer.numberOfLeadingZeros(n) + 7) / 8;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) {
                out.write((n >>> (8 * i)) & 0xff);
            }
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    /** A SEQUENCE of already encoded elements. */
    static byte[] sequence(byte[]... elements) {
        return tlv(SEQUENCE, concat(elements));
    }

    /** A SET of already encoded elements. Callers pass one element, so no DER sorting is needed. */
    static byte[] set(byte[] element) {
        return tlv(SET, element);
    }

    /** An INTEGER in minimal two's complement, as {@link BigInteger#toByteArray()} produces. */
    static byte[] integer(BigInteger value) {
        return tlv(INTEGER, value.toByteArray());
    }

    /** An INTEGER. */
    static byte[] integer(long value) {
        return integer(BigInteger.valueOf(value));
    }

    /**
     * An OBJECT IDENTIFIER from dotted notation.
     *
     * @throws IllegalArgumentException when the text has fewer than two arcs or a bad arc
     */
    static byte[] oid(String dotted) {
        String[] parts = dotted.split("\\.", -1);
        if (parts.length < 2) {
            throw new IllegalArgumentException("an OID needs at least two arcs: " + dotted);
        }
        long first = Long.parseLong(parts[0]);
        long second = Long.parseLong(parts[1]);
        if (first < 0 || first > 2 || second < 0 || (first < 2 && second > 39)) {
            throw new IllegalArgumentException("bad leading arcs: " + dotted);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        base128(out, first * 40 + second);
        for (int i = 2; i < parts.length; i++) {
            long arc = Long.parseLong(parts[i]);
            if (arc < 0) {
                throw new IllegalArgumentException("negative arc: " + dotted);
            }
            base128(out, arc);
        }
        return tlv(OBJECT_IDENTIFIER, out.toByteArray());
    }

    private static void base128(ByteArrayOutputStream out, long value) {
        int groups = Math.max(1, (64 - Long.numberOfLeadingZeros(value) + 6) / 7);
        for (int i = groups - 1; i >= 0; i--) {
            int b = (int) ((value >>> (7 * i)) & 0x7f);
            out.write(i == 0 ? b : b | 0x80);
        }
    }

    /** A UTF8String. */
    static byte[] utf8(String text) {
        return tlv(UTF8_STRING, text.getBytes(StandardCharsets.UTF_8));
    }

    /** A BIT STRING whose content is whole bytes (zero unused bits). */
    static byte[] bitString(byte[] bits) {
        byte[] content = new byte[bits.length + 1];
        System.arraycopy(bits, 0, content, 1, bits.length);
        return tlv(BIT_STRING, content);
    }

    /**
     * An X.509 Time (RFC 5280 4.1.2.5): UTCTime for years 1950 through 2049, GeneralizedTime
     * otherwise, at whole-second precision.
     */
    static byte[] time(Instant at) {
        int year = ZonedDateTime.ofInstant(at, ZoneOffset.UTC).getYear();
        if (year >= 1950 && year <= 2049) {
            return tlv(UTC_TIME, UTC.format(at).getBytes(StandardCharsets.US_ASCII));
        }
        return tlv(GENERALIZED_TIME, GENERALIZED.format(at).getBytes(StandardCharsets.US_ASCII));
    }

    /** An EXPLICIT context-specific constructed tag {@code [n]} around an encoded element. */
    static byte[] explicit(int n, byte[] element) {
        return tlv(0xa0 | n, element);
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
