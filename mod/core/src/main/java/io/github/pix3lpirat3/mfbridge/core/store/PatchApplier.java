package io.github.pix3lpirat3.mfbridge.core.store;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireException;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireReader;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Arrays;

/**
 * Generic field patches: the stored data is a MessagePack array, the
 * patch a map from field index to new value. Works on raw value slices, so no value is decoded.
 */
@AnyThread
public final class PatchApplier {
    /** Largest field index a patch may name; bounds the nil padding a peer can force. */
    public static final int MAX_FIELD_INDEX = 4095;

    private PatchApplier() {}

    /**
     * Applies a patch.
     *
     * @param data stored data, a MessagePack array
     * @param fields MessagePack map from non-negative integer index to value
     * @return the patched array, padded with nil up to the highest patched index
     * @throws WireException with {@code invalid_message} when either input is malformed
     */
    public static byte[] apply(byte[] data, byte[] fields) {
        int[] at = fieldOffsets(data);
        int n = at.length - 1;

        ByteBuf p = Unpooled.wrappedBuffer(fields);
        WireReader pr = new WireReader(p);
        int m = pr.readMapHeader();
        int length = n;
        int[] index = new int[m];
        int[] pStarts = new int[m];
        int[] pEnds = new int[m];
        for (int j = 0; j < m; j++) {
            int idx = pr.readUInt();
            if (idx < 0 || idx > MAX_FIELD_INDEX) {
                throw WireException.invalid("patch field index out of range: " + Integer.toUnsignedString(idx));
            }
            index[j] = idx;
            pStarts[j] = p.readerIndex();
            pr.skipValues(1);
            pEnds[j] = p.readerIndex();
            length = Math.max(length, idx + 1);
        }
        if (pr.remaining() != 0) {
            throw WireException.invalid("trailing bytes after patch fields");
        }

        int[] patchFor = new int[length];
        Arrays.fill(patchFor, -1);
        for (int j = 0; j < m; j++) {
            // Later entries win when a peer repeats an index.
            patchFor[index[j]] = j;
        }
        ByteBuf out = Unpooled.buffer(data.length + fields.length + 4);
        try {
            WireWriter w = new WireWriter(out);
            w.writeArrayHeader(length);
            for (int i = 0; i < length; i++) {
                int j = patchFor[i];
                if (j >= 0) {
                    out.writeBytes(fields, pStarts[j], pEnds[j] - pStarts[j]);
                } else if (i < n) {
                    out.writeBytes(data, at[i], at[i + 1] - at[i]);
                } else {
                    w.writeNil();
                }
            }
            return toArray(out);
        } finally {
            out.release();
        }
    }

    /**
     * Returns a patch that replaces every field of an object with the fields of {@code data}, for
     * whole-value edits sent as {@code obj.edit.propose} or {@code shared.op} updates.
     *
     * @param data a MessagePack array, the object's new data
     * @return a MessagePack map from each index of {@code data} to its value
     * @throws WireException with {@code invalid_message} when {@code data} is not one array
     */
    public static byte[] fullPatch(byte[] data) {
        int[] at = fieldOffsets(data);
        int[] all = new int[at.length - 1];
        for (int i = 0; i < all.length; i++) {
            all[i] = i;
        }
        return patch(data, at, all);
    }

    /**
     * Returns a patch that sets only some fields to their values in {@code data}, for field edits
     * (Shift+click editing) sent as {@code obj.edit.propose} or {@code
     * shared.op} updates.
     *
     * @param data a MessagePack array, the object's new data
     * @param indices the field indices to include, each below the array's length
     * @return a MessagePack map from each index to its value in {@code data}
     * @throws WireException with {@code invalid_message} when {@code data} is not one array or an
     *     index is out of range
     */
    public static byte[] fieldsPatch(byte[] data, int... indices) {
        int[] at = fieldOffsets(data);
        for (int i : indices) {
            if (i < 0 || i >= at.length - 1) {
                throw WireException.invalid("field index out of range: " + i);
            }
        }
        return patch(data, at, indices);
    }

    private static byte[] patch(byte[] data, int[] at, int[] indices) {
        ByteBuf out = Unpooled.buffer(data.length + indices.length * 2 + 4);
        try {
            WireWriter w = new WireWriter(out);
            w.writeMapHeader(indices.length);
            for (int i : indices) {
                w.writeUInt(i);
                out.writeBytes(data, at[i], at[i + 1] - at[i]);
            }
            return toArray(out);
        } finally {
            out.release();
        }
    }

    /** Returns the offset of each element of the array in {@code data}, then the end offset. */
    private static int[] fieldOffsets(byte[] data) {
        ByteBuf d = Unpooled.wrappedBuffer(data);
        WireReader dr = new WireReader(d);
        int n = dr.readArrayHeader();
        int[] at = new int[n + 1];
        for (int i = 0; i < n; i++) {
            at[i] = d.readerIndex();
            dr.skipValues(1);
        }
        at[n] = d.readerIndex();
        if (dr.remaining() != 0) {
            throw WireException.invalid("trailing bytes after object data");
        }
        return at;
    }

    private static byte[] toArray(ByteBuf buf) {
        byte[] result = new byte[buf.readableBytes()];
        buf.readBytes(result);
        return result;
    }
}
