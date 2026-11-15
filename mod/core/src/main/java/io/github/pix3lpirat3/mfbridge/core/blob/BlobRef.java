package io.github.pix3lpirat3.mfbridge.core.blob;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

/**
 * Reference to a content-addressed blob: the SHA-256 of its payload and the payload size.
 *
 * @param hex lower-case hex SHA-256 of the decoded payload, 64 characters; also the cache file name
 * @param size decoded payload size, in bytes
 */
@AnyThread
public record BlobRef(String hex, long size) {
    /** Length of a SHA-256 hash, in bytes. */
    public static final int HASH_BYTES = 32;

    private static final HexFormat HEX = HexFormat.of();

    /**
     * Validates the hash text and size.
     *
     * @throws IllegalArgumentException when {@code hex} is not 64 lower-case hex digits or the size is negative
     */
    public BlobRef {
        if (!isHex(hex)) {
            throw new IllegalArgumentException("blob hash must be 64 lower-case hex digits");
        }
        if (size < 0) {
            throw new IllegalArgumentException("blob size must not be negative: " + size);
        }
    }

    /**
     * Creates a reference from a raw hash.
     *
     * @param hash SHA-256, exactly {@link #HASH_BYTES} bytes
     * @param size decoded payload size in bytes; the wire carries it as u64
     * @throws IllegalArgumentException when the hash has the wrong length or the size is negative
     */
    public static BlobRef of(byte[] hash, long size) {
        if (hash.length != HASH_BYTES) {
            throw new IllegalArgumentException("blob hash must be " + HASH_BYTES + " bytes, got " + hash.length);
        }
        return new BlobRef(HEX.formatHex(hash), size);
    }

    /** Returns the reference, or null when the hash is malformed; for untrusted object data. */
    public static @Nullable BlobRef tryOf(byte @Nullable [] hash, @Nullable Long size) {
        if (hash == null || hash.length != HASH_BYTES || size == null || size < 0) {
            return null;
        }
        return new BlobRef(HEX.formatHex(hash), size);
    }

    /** Returns a fresh copy of the raw hash. */
    public byte[] hash() {
        return HEX.parseHex(hex);
    }

    /** Returns whether a cache file name is a blob hash. */
    public static boolean isHex(String text) {
        if (text.length() != HASH_BYTES * 2) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }
}
