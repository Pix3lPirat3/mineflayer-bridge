package io.github.pix3lpirat3.mfbridge.core.persist;

import io.github.pix3lpirat3.mfbridge.protocol.core.Lifetime;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectTraits;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireException;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireReader;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jspecify.annotations.Nullable;

/**
 * A stored shared or player object. The kind is kept by name because extension kind ids are only
 * valid per connection.
 *
 * @param key full object key
 * @param kindName kind name such as {@code core:waypoint}
 * @param rev revision
 * @param layer layer name or null
 * @param lifetime lifetime
 * @param scope scope
 * @param world world key or null
 * @param data encoded kind data
 * @param traits object traits, or null
 */
public record PersistedObject(
        String key,
        String kindName,
        long rev,
        @Nullable String layer,
        Lifetime lifetime,
        Scope scope,
        @Nullable WorldKey world,
        byte[] data,
        @Nullable ObjectTraits traits) {

    /** Creates a record without traits. */
    public PersistedObject(String key, String kindName, long rev, @Nullable String layer, Lifetime lifetime, Scope scope,
            @Nullable WorldKey world, byte[] data) {
        this(key, kindName, rev, layer, lifetime, scope, world, data, null);
    }

    private static final int PUT = 0;
    private static final int REMOVE = 1;

    /**
     * Encodes a put record: {@code [0, key, kind, rev, layer, lifetime, scope, world, data, traits]}.
     */
    public byte[] encodePut() {
        ByteBuf buf = Unpooled.buffer(64 + data.length);
        try {
            WireWriter w = new WireWriter(buf);
            w.writeArrayHeader(10);
            w.writeUInt(PUT);
            w.writeString(key);
            w.writeString(kindName);
            w.writeULong(rev);
            if (layer == null) {
                w.writeNil();
            } else {
                w.writeString(layer);
            }
            Lifetime.CODEC.write(w, lifetime);
            Scope.CODEC.write(w, scope);
            if (world == null) {
                w.writeNil();
            } else {
                WorldKey.CODEC.write(w, world);
            }
            w.writeBytes(data);
            if (traits == null) {
                w.writeNil();
            } else {
                ObjectTraits.CODEC.write(w, traits);
            }
            return toArray(buf);
        } finally {
            buf.release();
        }
    }

    /** Encodes a remove record: {@code [1, key]}. */
    public static byte[] encodeRemove(String key) {
        ByteBuf buf = Unpooled.buffer(16 + key.length());
        try {
            WireWriter w = new WireWriter(buf);
            w.writeArrayHeader(2);
            w.writeUInt(REMOVE);
            w.writeString(key);
            return toArray(buf);
        } finally {
            buf.release();
        }
    }

    private static byte[] toArray(ByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    /** Returns the key of an encoded record. */
    public static String keyOf(byte[] record) {
        WireReader r = new WireReader(Unpooled.wrappedBuffer(record));
        r.readArrayHeader();
        r.readUInt();
        return r.readString();
    }

    /** Returns whether an encoded record is a put. */
    public static boolean isPut(byte[] record) {
        WireReader r = new WireReader(Unpooled.wrappedBuffer(record));
        r.readArrayHeader();
        return r.readUInt() == PUT;
    }

    /**
     * Decodes a put record.
     *
     * @throws WireException when malformed or not a put
     */
    public static PersistedObject decodePut(byte[] record) {
        WireReader r = new WireReader(Unpooled.wrappedBuffer(record));
        int n = r.readArrayHeader();
        if (n < 10) {
            throw WireException.invalid("put record has " + n + " fields, expected 10");
        }
        if (r.readUInt() != PUT) {
            throw WireException.invalid("not a put record");
        }
        String key = r.readString();
        String kind = r.readString();
        long rev = r.readULong();
        @Nullable String layer = r.tryReadNil() ? null : r.readString();
        Lifetime lifetime = Lifetime.CODEC.read(r);
        Scope scope = Scope.CODEC.read(r);
        @Nullable WorldKey world = r.tryReadNil() ? null : WorldKey.CODEC.read(r);
        byte[] data = r.readBytes();
        @Nullable ObjectTraits traits = r.tryReadNil() ? null : ObjectTraits.CODEC.read(r);
        r.skipValues(n - 10);
        return new PersistedObject(key, kind, rev, layer, lifetime, scope, world, data, traits);
    }
}
