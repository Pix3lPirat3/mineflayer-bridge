package io.github.pix3lpirat3.mfbridge.core.blob;

/**
 * Turns a verified blob payload into the value a renderer works with. Decoders run on the blob
 * worker thread, never on the client or render thread, and their results are cached by blob and
 * decoder id, so a decoder must be stateless and its results immutable.
 *
 * @param <T> decoded type
 */
public interface BlobDecoder<T> {
    /** Returns a stable id that distinguishes this decoder's results in the decoded cache. */
    String id();

    /**
     * Decodes a payload.
     *
     * @param payload the verified payload; the decoder must not keep or modify it
     * @return the decoded value
     * @throws RuntimeException when the payload is malformed; the decode future completes exceptionally
     */
    T decode(byte[] payload);
}
