package io.github.pix3lpirat3.mfbridge.protocol.wire;

/** Encoder and decoder for one schema type. Generated records expose theirs as {@code CODEC}. */
public interface WireCodec<T> {
    /** Appends {@code value} to {@code w}. */
    void write(WireWriter w, T value);

    /**
     * Reads one value from {@code r}.
     *
     * @throws WireException when the bytes do not match the type
     */
    T read(WireReader r);
}
