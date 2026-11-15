package io.github.pix3lpirat3.mfbridge.protocol.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Registered object kind. Object data travels as encoded bytes inside {@code ObjectFull}. */
public record KindType<T>(int id, String name, WireCodec<T> codec) {

    /** Encodes kind data for {@code ObjectFull.data}. */
    public byte[] encode(T value) {
        ByteBuf buf = Unpooled.buffer(64);
        try {
            codec.write(new WireWriter(buf), value);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    /** Decodes {@code ObjectFull.data}; trailing bytes are an error. */
    public T decode(byte[] data) {
        WireReader r = new WireReader(Unpooled.wrappedBuffer(data));
        T value = codec.read(r);
        if (r.remaining() != 0) {
            throw WireException.invalid(name + ": trailing bytes in object data");
        }
        return value;
    }
}
