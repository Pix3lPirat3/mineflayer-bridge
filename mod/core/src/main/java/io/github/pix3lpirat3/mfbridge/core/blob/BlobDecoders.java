package io.github.pix3lpirat3.mfbridge.core.blob;

import io.github.pix3lpirat3.mfbridge.core.util.Encoding;
import io.github.pix3lpirat3.mfbridge.protocol.core.HeatmapBlob;
import io.github.pix3lpirat3.mfbridge.protocol.core.HighlightBlob;
import io.github.pix3lpirat3.mfbridge.protocol.core.VolumeBlob;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireCodec;

/** Decoders for the core blob payloads, which are MessagePack-encoded schema structs. */
public final class BlobDecoders {
    /** {@code VolumeBlob} of {@code core:ghost-blocks}. */
    public static final BlobDecoder<VolumeBlob> VOLUME = of("core:volume", VolumeBlob.CODEC);

    /** {@code HeatmapBlob} of {@code core:heatmap}. */
    public static final BlobDecoder<HeatmapBlob> HEATMAP = of("core:heatmap", HeatmapBlob.CODEC);

    /** {@code HighlightBlob} of {@code core:block-highlight}. */
    public static final BlobDecoder<HighlightBlob> HIGHLIGHT = of("core:highlight", HighlightBlob.CODEC);

    /** An Ogg Vorbis file of {@code core:sound-emitter}, as a copy of its bytes; anything else is rejected. Callers must not modify it. */
    public static final BlobDecoder<byte[]> OGG = new BlobDecoder<>() {
        @Override
        public String id() {
            return "core:ogg";
        }

        @Override
        public byte[] decode(byte[] payload) {
            if (payload.length < 4 || payload[0] != 'O' || payload[1] != 'g' || payload[2] != 'g' || payload[3] != 'S') {
                throw new IllegalArgumentException("not an Ogg file");
            }
            return payload.clone();
        }
    };

    private BlobDecoders() {}

    /** Returns a decoder that decodes exactly one value with a generated codec. */
    public static <T> BlobDecoder<T> of(String id, WireCodec<T> codec) {
        return new BlobDecoder<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public T decode(byte[] payload) {
                return Encoding.decode(codec, payload);
            }
        };
    }
}
