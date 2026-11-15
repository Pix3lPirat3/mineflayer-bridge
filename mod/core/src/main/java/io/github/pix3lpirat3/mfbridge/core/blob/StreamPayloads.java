package io.github.pix3lpirat3.mfbridge.core.blob;

import io.airlift.compress.zstd.ZstdCompressor;
import io.airlift.compress.zstd.ZstdInputStream;
import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

/**
 * Stream codecs and hashing: {@code raw}, or {@code zstd} through aircompressor,
 * which is pure Java.
 */
@AnyThread
public final class StreamPayloads {
    /** Codec name for uncompressed payloads. */
    public static final String RAW = "raw";

    /** Codec name for zstd frames. */
    public static final String ZSTD = "zstd";

    private StreamPayloads() {}

    /** Returns whether the codec name is supported. */
    public static boolean supported(String codec) {
        return RAW.equals(codec) || ZSTD.equals(codec);
    }

    /**
     * Largest encoded size accepted for a payload, in bytes. Senders use zstd only when it is smaller
     * than the payload, so the transferred bytes never exceed the decoded size for either codec.
     */
    public static long maxEncodedSize(String codec, long size) {
        return size;
    }

    /**
     * Decodes a received stream into its payload.
     *
     * @param encoded received bytes; only the first {@code length} are used
     * @param size expected decoded size, in bytes
     * @return the payload, or null when it does not decode to exactly {@code size} bytes
     */
    public static byte @Nullable [] decode(String codec, byte[] encoded, int length, int size) {
        if (RAW.equals(codec)) {
            return length == size ? (encoded.length == size ? encoded : Arrays.copyOf(encoded, size)) : null;
        }
        byte[] out = new byte[size];
        try (InputStream in = new ZstdInputStream(new ByteArrayInputStream(encoded, 0, length))) {
            int n = in.readNBytes(out, 0, size);
            if (n != size || in.read() != -1) {
                return null;
            }
            return out;
        } catch (IOException | RuntimeException e) {
            // aircompressor reports corrupt frames with MalformedInputException, a RuntimeException.
            return null;
        }
    }

    /** Compresses a payload as one zstd frame; used by tests and future mod-to-node streams. */
    public static byte[] zstd(byte[] payload) {
        ZstdCompressor c = new ZstdCompressor();
        byte[] out = new byte[c.maxCompressedLength(payload.length)];
        int n = c.compress(payload, 0, payload.length, out, 0, out.length);
        return Arrays.copyOf(out, n);
    }

    /** Returns the SHA-256 of the payload. */
    public static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime provides SHA-256", e);
        }
    }

    /** Returns the lower-case hex SHA-256 of the payload. */
    public static String sha256Hex(byte[] payload) {
        return HexFormat.of().formatHex(sha256(payload));
    }
}
