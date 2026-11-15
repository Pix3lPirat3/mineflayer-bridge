package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.blob.BlobDecoder;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobDecoders;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobRef;
import io.github.pix3lpirat3.mfbridge.core.blob.StreamPayloads;
import io.github.pix3lpirat3.mfbridge.core.util.Encoding;
import io.github.pix3lpirat3.mfbridge.protocol.core.BlockHighlightData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.HighlightBlob;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpened;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionResume;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionResumed;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamAccept;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamChunk;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamEnd;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamHave;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamOffer;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamReject;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamWindow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamsTest {
    static final int CHUNK = 256 << 10;
    static final String P = "bot:miner@example.com/";

    static final BlobDecoder<byte[]> BYTES = new BlobDecoder<>() {
        @Override
        public String id() {
            return "test:bytes";
        }

        @Override
        public byte[] decode(byte[] payload) {
            return payload.clone();
        }
    };

    @TempDir
    Path dir;

    static byte[] payload(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    static void offer(FakePlugin p, int s, int id, byte[] payload, String codec) {
        p.reliable(s, CoreMessages.STREAM_OFFER, new StreamOffer(id, StreamPayloads.sha256(payload), payload.length, codec));
    }

    /** Streams {@code encoded} after an accept, honouring the mod's credit, then ends the stream. */
    static void sendChunks(FakePlugin p, int s, int id, long credit, byte[] encoded, int from) {
        int off = from;
        while (off < encoded.length) {
            while (credit <= off) {
                StreamWindow w = p.await(CoreMessages.STREAM_WINDOW, s);
                assertEquals(id, w.streamId());
                credit += Integer.toUnsignedLong(w.window());
            }
            int n = (int) Math.min(Math.min(CHUNK, encoded.length - off), credit - off);
            p.reliable(s, CoreMessages.STREAM_CHUNK, new StreamChunk(id, off, Arrays.copyOfRange(encoded, off, off + n)));
            off += n;
        }
        p.reliable(s, CoreMessages.STREAM_END, new StreamEnd(id));
    }

    static StreamAccept send(FakePlugin p, int s, int id, byte[] payload, byte[] encoded, String codec) {
        offer(p, s, id, payload, codec);
        StreamAccept a = p.await(CoreMessages.STREAM_ACCEPT, s);
        assertEquals(id, a.streamId());
        sendChunks(p, s, id, Integer.toUnsignedLong(a.window()), encoded, 0);
        return a;
    }

    static BlobRef ref(byte[] payload) {
        return BlobRef.of(StreamPayloads.sha256(payload), payload.length);
    }

    static void awaitBlob(Harness h, byte[] payload) {
        BlobRef ref = ref(payload);
        h.tickUntil("blob cached", () -> h.core.blobs().isAvailable(ref));
    }

    static StreamReject awaitReject(FakePlugin p, int s) {
        return p.await(CoreMessages.STREAM_REJECT, s);
    }

    static byte[] decoded(Harness h, byte[] payload) throws Exception {
        return h.core.blobs().decode(ref(payload), BYTES).get(5, TimeUnit.SECONDS);
    }

    @Test
    void rawTransferWithWindowGrantsLandsInTheCacheAndOnDisk() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            byte[] data = payload(3 * (1 << 20) + 17, 1);
            StreamAccept a = send(p, s, 7, data, data, StreamPayloads.RAW);
            assertEquals(1 << 20, a.window(), "initial credit is one window");
            awaitBlob(h, data);
            assertArrayEquals(data, decoded(h, data));
            Path file = h.configDir.resolve("mineflayer_bridge/blobs/" + StreamPayloads.sha256Hex(data));
            h.tickUntil("written", () -> Files.exists(file));
            assertArrayEquals(data, Files.readAllBytes(file));
            assertFalse(p.poll(f -> f.is(CoreMessages.STREAM_REJECT) || f.is(CoreMessages.ERROR)), p.describe());
        }
    }

    @Test
    void malformedChunksAreRejected() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            byte[] data = payload(2 << 20, 2);
            offer(p, s, 1, data, StreamPayloads.RAW);
            p.await(CoreMessages.STREAM_ACCEPT, s);
            p.reliable(s, CoreMessages.STREAM_CHUNK, new StreamChunk(1, 0, Arrays.copyOfRange(data, 0, CHUNK + 1)));
            assertEquals("too_large", awaitReject(p, s).reason(), "chunk over 256 KiB");
            // Chunks still in flight after a reject are ignored, not answered.
            p.reliable(s, CoreMessages.STREAM_CHUNK, new StreamChunk(1, CHUNK + 1, new byte[10]));

            offer(p, s, 2, data, StreamPayloads.RAW);
            p.await(CoreMessages.STREAM_ACCEPT, s);
            p.reliable(s, CoreMessages.STREAM_CHUNK, new StreamChunk(2, 5, new byte[10]));
            assertEquals("bad_offset", awaitReject(p, s).reason());

            byte[] small = payload(4096, 3);
            offer(p, s, 3, small, StreamPayloads.RAW);
            StreamAccept a3 = p.await(CoreMessages.STREAM_ACCEPT, s);
            assertEquals(4096, a3.window(), "credit never exceeds the payload");
            p.reliable(s, CoreMessages.STREAM_CHUNK, new StreamChunk(3, 0, new byte[4097]));
            assertEquals("too_large", awaitReject(p, s).reason());
        }
    }

    @Test
    void haveDedupesAcrossSessionsAndRestarts() throws Exception {
        byte[] data = payload(100_000, 4);
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            send(p, s, 1, data, data, StreamPayloads.RAW);
            awaitBlob(h, data);
            int s2 = p.openBot("Digger", "example.com").sessionId();
            offer(p, s2, 9, data, StreamPayloads.RAW);
            assertEquals(9, p.await(CoreMessages.STREAM_HAVE, s2).streamId());
        }
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            offer(p, s, 4, data, StreamPayloads.RAW);
            StreamHave have = p.await(CoreMessages.STREAM_HAVE, s);
            assertEquals(4, have.streamId());
            assertArrayEquals(data, decoded(h, data));
        }
    }

    @Test
    void hashMismatchAndCorruptZstdAreRejected() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            byte[] data = payload(5000, 5);
            byte[] other = payload(5000, 6);
            p.reliable(s, CoreMessages.STREAM_OFFER, new StreamOffer(1, StreamPayloads.sha256(other), data.length, "raw"));
            StreamAccept a = p.await(CoreMessages.STREAM_ACCEPT, s);
            sendChunks(p, s, 1, a.window(), data, 0);
            assertEquals("hash_mismatch", awaitReject(p, s).reason());
            assertFalse(h.core.blobs().isAvailable(ref(other)));

            offer(p, s, 2, data, StreamPayloads.ZSTD);
            StreamAccept a2 = p.await(CoreMessages.STREAM_ACCEPT, s);
            sendChunks(p, s, 2, a2.window(), payload(300, 7), 0);
            assertEquals("decode_failed", awaitReject(p, s).reason());

            p.reliable(s, CoreMessages.STREAM_OFFER, new StreamOffer(3, new byte[5], 10, "raw"));
            assertEquals("hash_mismatch", awaitReject(p, s).reason());
            offer(p, s, 4, data, "lz4");
            assertEquals("unsupported_codec", awaitReject(p, s).reason());
        }
    }

    @Test
    void sizeAndInFlightQuotas() throws Exception {
        try (Harness h = new Harness(dir, b -> b.maxBlobBytes(1 << 20).budget(2_000, 8 << 20, 1_500_000),
                DeviceApprover.ALLOW_ONCE); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.reliable(s, CoreMessages.STREAM_OFFER, new StreamOffer(1, new byte[32], (1 << 20) + 1, "raw"));
            assertEquals("too_large", awaitReject(p, s).reason());
            byte[] a = payload(1 << 20, 8);
            byte[] b = payload(1 << 20, 9);
            offer(p, s, 2, a, StreamPayloads.RAW);
            p.await(CoreMessages.STREAM_ACCEPT, s);
            offer(p, s, 3, b, StreamPayloads.RAW);
            assertEquals("quota_exceeded", awaitReject(p, s).reason());
            // Cancelling the open stream frees its share of the quota.
            p.reliable(s, CoreMessages.STREAM_REJECT, new StreamReject(2, "cancelled"));
            offer(p, s, 4, b, StreamPayloads.RAW);
            StreamAccept ok = p.await(CoreMessages.STREAM_ACCEPT, s);
            assertEquals(4, ok.streamId());
        }
    }

    @Test
    void zstdRoundTrip() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            byte[] data = new byte[2_500_000];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i % 251 < 120 ? i % 7 : 0);
            }
            byte[] z = StreamPayloads.zstd(data);
            assertTrue(z.length < data.length / 4, "compressible test data");
            send(p, s, 1, data, z, StreamPayloads.ZSTD);
            awaitBlob(h, data);
            assertArrayEquals(data, decoded(h, data));
        }
    }

    @Test
    void streamContinuesAcrossResume() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            SessionOpened opened = p.openBot("Miner", "example.com");
            int s = opened.sessionId();
            byte[] data = payload(700_000, 10);
            offer(p, s, 5, data, StreamPayloads.RAW);
            StreamAccept a = p.await(CoreMessages.STREAM_ACCEPT, s);
            p.reliable(s, CoreMessages.STREAM_CHUNK, new StreamChunk(5, 0, Arrays.copyOfRange(data, 0, CHUNK)));
            p.sync();
            p.abort();
            h.tickUntil("detached", () -> !h.core.sessionStats().get(0).connected());
            try (FakePlugin p2 = FakePlugin.ready(h, "dev")) {
                long req = p2.request(0, CoreMessages.SESSION_RESUME, new SessionResume(opened.resumeToken(), 0));
                SessionResumed resumed = p2.await("resumed",
                        x -> x.is(CoreMessages.SESSION_RESUMED) && x.env().req() == req).decode(CoreMessages.SESSION_RESUMED);
                assertEquals(2, resumed.lastApplied(), "offer and first chunk were applied");
                int s2 = resumed.sessionId();
                p2.setNextSeq(s2, resumed.lastApplied());
                sendChunks(p2, s2, 5, a.window(), data, CHUNK);
                awaitBlob(h, data);
                assertFalse(p2.poll(f -> f.is(CoreMessages.STREAM_REJECT)), p2.describe());
            }
        }
    }

    @Test
    void objectsWaitForTheirBlob() throws Exception {
        try (Harness h = new Harness(dir, b -> b.attachPolicy(AttachPolicy.ALWAYS), DeviceApprover.ALLOW_ONCE);
                FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            byte[] blob = Encoding.encode(HighlightBlob.CODEC, new HighlightBlob(new int[] {1, 2, 3, 4, 5, 6}));
            BlobRef ref = ref(blob);
            byte[] data = CoreKinds.BLOCK_HIGHLIGHT.encode(
                    new BlockHighlightData(null, StreamPayloads.sha256(blob), (long) blob.length, "outline", null));
            p.upsert(s, FakePlugin.object(P + "viz/marks", CoreKinds.BLOCK_HIGHLIGHT, 1, data));
            h.tickUntil("entry", () -> h.core.renderSnapshot().size() == 1);
            RenderSnapshot before = h.core.renderSnapshot();
            RenderEntry e = before.entries().get(0);
            assertEquals(ref, e.blob());
            assertFalse(before.blobReady(e), "kept but not ready");
            send(p, s, 1, blob, blob, StreamPayloads.RAW);
            h.tickUntil("ready", () -> h.core.renderSnapshot().blobReady(h.core.renderSnapshot().entries().get(0)));
            assertTrue(h.core.renderSnapshot().version() > before.version(), "arrival publishes a new snapshot");
            HighlightBlob hb = h.core.renderSnapshot().blobs().decode(ref, BlobDecoders.HIGHLIGHT).get(5, TimeUnit.SECONDS);
            assertNotNull(hb);
            assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6}, hb.positions());
        }
    }
}
