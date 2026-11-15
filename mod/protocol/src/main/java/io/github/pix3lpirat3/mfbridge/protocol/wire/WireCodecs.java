package io.github.pix3lpirat3.mfbridge.protocol.wire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Codecs for primitives and containers, used by generated code for list elements and map values. */
public final class WireCodecs {
    private WireCodecs() {}

    public static final WireCodec<Boolean> BOOL = of(WireWriter::writeBool, WireReader::readBool);
    public static final WireCodec<Integer> I32 = of((w, v) -> w.writeInt(v), WireReader::readInt);
    public static final WireCodec<Integer> U32 =
            of((w, v) -> w.writeUInt(Integer.toUnsignedLong(v)), WireReader::readUInt);
    public static final WireCodec<Long> I64 = of(WireWriter::writeInt, WireReader::readLong);
    public static final WireCodec<Long> U64 = of(WireWriter::writeULong, WireReader::readULong);
    public static final WireCodec<Float> F32 = of(WireWriter::writeFloat, WireReader::readFloat);
    public static final WireCodec<Double> F64 = of(WireWriter::writeDouble, WireReader::readDouble);
    public static final WireCodec<String> STRING = of(WireWriter::writeString, WireReader::readString);
    public static final WireCodec<byte[]> BYTES = of(WireWriter::writeBytes, WireReader::readBytes);
    public static final WireCodec<@Nullable Object> ANY = of(WireWriter::writeAny, WireReader::readAny);
    public static final WireCodec<double[]> F64_ARRAY = of(WireWriter::writeDoubleArray, WireReader::readDoubleArray);
    public static final WireCodec<float[]> F32_ARRAY = of(WireWriter::writeFloatArray, WireReader::readFloatArray);
    public static final WireCodec<int[]> I32_ARRAY = of(WireWriter::writeIntArray, WireReader::readIntArray);
    public static final WireCodec<short[]> U16_ARRAY = of(WireWriter::writeU16Array, WireReader::readU16Array);

    /** Lists decode as mutable {@link ArrayList}s and are not defensively copied. */
    public static <T> WireCodec<List<T>> list(WireCodec<T> element) {
        return new WireCodec<>() {
            @Override
            public void write(WireWriter w, List<T> value) {
                w.writeArrayHeader(value.size());
                for (T item : value) {
                    element.write(w, item);
                }
            }

            @Override
            public List<T> read(WireReader r) {
                int n = r.readArrayHeader();
                List<T> out = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    out.add(element.read(r));
                }
                return out;
            }
        };
    }

    /** Maps have string keys and decode in wire order. */
    public static <T> WireCodec<Map<String, T>> map(WireCodec<T> value) {
        return new WireCodec<>() {
            @Override
            public void write(WireWriter w, Map<String, T> map) {
                w.writeMapHeader(map.size());
                for (Map.Entry<String, T> e : map.entrySet()) {
                    w.writeString(e.getKey());
                    value.write(w, e.getValue());
                }
            }

            @Override
            public Map<String, T> read(WireReader r) {
                int n = r.readMapHeader();
                Map<String, T> out = new LinkedHashMap<>(n * 2);
                for (int i = 0; i < n; i++) {
                    String key = r.readString();
                    out.put(key, value.read(r));
                }
                return out;
            }
        };
    }

    private interface Writer<T> {
        void write(WireWriter w, T value);
    }

    private interface Reader<T> {
        T read(WireReader r);
    }

    private static <T> WireCodec<T> of(Writer<T> writer, Reader<T> reader) {
        return new WireCodec<>() {
            @Override
            public void write(WireWriter w, T value) {
                writer.write(w, value);
            }

            @Override
            public T read(WireReader r) {
                return reader.read(r);
            }
        };
    }
}
