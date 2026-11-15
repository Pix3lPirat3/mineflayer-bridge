package io.github.pix3lpirat3.mfbridge.core.blob;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.persist.PersistenceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class BlobCacheTest {
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

    private PersistenceService io;
    private ExecutorService worker;

    @BeforeEach
    void start() {
        io = new PersistenceService(dir.resolve("data"), 1024, LoggerFactory.getLogger("t"));
        worker = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stop() {
        io.close();
        worker.shutdownNow();
    }

    private BlobCache open(long max) throws Exception {
        BlobCache c = new BlobCache(dir.resolve("blobs"), max, io, worker, LoggerFactory.getLogger("t"));
        c.indexLoaded().get(5, TimeUnit.SECONDS);
        return c;
    }

    static byte[] blob(int fill) {
        byte[] b = new byte[100];
        Arrays.fill(b, (byte) fill);
        return b;
    }

    static String hex(byte[] b) {
        return StreamPayloads.sha256Hex(b);
    }

    @Test
    void leastRecentlyUsedIsEvictedAndPinsAreKept() throws Exception {
        BlobCache c = open(250);
        byte[] a = blob(1);
        byte[] b = blob(2);
        byte[] d = blob(3);
        byte[] e = blob(4);
        c.put(hex(a), a);
        c.put(hex(b), b);
        c.touch(hex(a));
        c.put(hex(d), d);
        assertEquals(List.of(hex(a), hex(d)), c.lruOrder(), "b was least recently used");
        assertFalse(c.contains(hex(b)));
        c.pin(hex(a));
        c.put(hex(e), e);
        assertEquals(List.of(hex(a), hex(e)), c.lruOrder(), "pinned a survives although older than d");
        assertEquals(200, c.totalBytes());
        assertArrayEquals(a, c.decode(new BlobRef(hex(a), 100), BYTES).get(5, TimeUnit.SECONDS));
        io.flush().get(5, TimeUnit.SECONDS);
        assertFalse(Files.exists(dir.resolve("blobs").resolve(hex(b))), "evicted files are deleted");
        assertTrue(Files.exists(dir.resolve("blobs").resolve(hex(e))));
    }

    @Test
    void indexSurvivesRestartAndBoundAppliesOnLoad() throws Exception {
        BlobCache c = open(1000);
        byte[] a = blob(1);
        byte[] b = blob(2);
        c.put(hex(a), a);
        io.flush().get(5, TimeUnit.SECONDS);
        // Distinct modification times give a stable recency order after the restart.
        Files.setLastModifiedTime(dir.resolve("blobs").resolve(hex(a)), java.nio.file.attribute.FileTime.fromMillis(1000));
        c.put(hex(b), b);
        io.flush().get(5, TimeUnit.SECONDS);
        Files.writeString(dir.resolve("blobs").resolve("stray.tmp"), "x");

        BlobCache again = open(1000);
        assertEquals(List.of(hex(a), hex(b)), again.lruOrder());
        assertArrayEquals(b, again.read(hex(b)).get(5, TimeUnit.SECONDS));
        assertFalse(Files.exists(dir.resolve("blobs").resolve("stray.tmp")));

        BlobCache small = open(150);
        assertEquals(List.of(hex(b)), small.lruOrder(), "the oldest file goes when the bound shrinks");
    }

    @Test
    void decodedNowPollsWithoutBlocking() throws Exception {
        BlobCache c = open(1000);
        byte[] a = blob(7);
        BlobRef ref = new BlobRef(hex(a), 100);
        assertNull(c.decodedNow(ref, BYTES), "not available yet");
        long v = c.version();
        c.put(hex(a), a);
        assertTrue(c.version() != v, "availability bumps the version");
        byte[] got = null;
        for (int i = 0; i < 500 && got == null; i++) {
            got = c.decodedNow(ref, BYTES);
            Thread.sleep(2);
        }
        assertArrayEquals(a, got);
    }
}
