package io.github.pix3lpirat3.mfbridge.core.util;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireCodec;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireException;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireReader;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Base64;
import java.util.function.Consumer;

/** Encoding helpers: base64url and one-shot MessagePack encode and strict decode. */
@AnyThread
public final class Encoding {
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DECODER = Base64.getUrlDecoder();

    private Encoding() {}

    /** Encodes bytes as unpadded base64url. */
    public static String base64Url(byte[] bytes) {
        return B64URL.encodeToString(bytes);
    }

    /**
     * Decodes base64url, padded or not.
     *
     * @throws IllegalArgumentException when the text is not base64url
     */
    public static byte[] fromBase64Url(String text) {
        return B64URL_DECODER.decode(text);
    }

    /** Encodes bytes as unpadded upper-case RFC 4648 base32. */
    public static String base32(byte[] bytes) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(alphabet.charAt((buffer >>> bits) & 0x1f));
            }
        }
        if (bits > 0) {
            out.append(alphabet.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return out.toString();
    }

    /** Encodes one value with a codec into a fresh array. Allocates; not for per-frame paths. */
    public static <T> byte[] encode(WireCodec<T> codec, T value) {
        return write(64, w -> codec.write(w, value));
    }

    /**
     * Runs {@code body} against a writer over a temporary buffer and returns what it wrote.
     * Allocates; not for per-frame paths.
     *
     * @param capacity initial buffer size in bytes; the buffer grows as needed
     */
    public static byte[] write(int capacity, Consumer<WireWriter> body) {
        ByteBuf buf = Unpooled.buffer(capacity);
        try {
            body.accept(new WireWriter(buf));
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    /**
     * Decodes exactly one value; trailing bytes are an error.
     *
     * @throws WireException with {@code invalid_message} on any mismatch
     */
    public static <T> T decode(WireCodec<T> codec, byte[] bytes) {
        WireReader r = new WireReader(Unpooled.wrappedBuffer(bytes));
        T value = codec.read(r);
        if (r.remaining() != 0) {
            throw WireException.invalid("trailing bytes after payload");
        }
        return value;
    }
}
