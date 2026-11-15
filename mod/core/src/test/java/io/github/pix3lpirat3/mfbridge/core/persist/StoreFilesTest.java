package io.github.pix3lpirat3.mfbridge.core.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.protocol.core.Lifetime;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class StoreFilesTest {
    @TempDir
    Path dir;

    static PersistedObject obj(String key, long rev, int size) {
        return new PersistedObject(key, "core:label", rev, null, Lifetime.PERSISTENT, Scope.WORLD,
                new WorldKey("example.com:25565", null, "minecraft:overworld"), new byte[size]);
    }

    static TreeMap<String, Long> revs(List<PersistedObject> list) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (PersistedObject o : list) {
            out.put(o.key(), o.rev());
        }
        return out;
    }

    @Test
    void replaysLogAndDiscardsTornTail() throws Exception {
        Path store = dir.resolve("s");
        StoreFiles files = new StoreFiles(store, 1 << 20);
        files.load();
        files.append(obj("shared/z/a", 1, 10).encodePut());
        files.append(obj("shared/z/b", 1, 10).encodePut());
        files.append(obj("shared/z/a", 2, 10).encodePut());
        files.append(PersistedObject.encodeRemove("shared/z/b"));
        files.close();
        long intact = Files.size(store.resolve(StoreFiles.LOG));
        // A crash mid-write leaves a partial record.
        Files.write(store.resolve(StoreFiles.LOG), new byte[] {0, 0, 0, 50, 1, 2, 3, 4, 5}, StandardOpenOption.APPEND);

        StoreFiles reopened = new StoreFiles(store, 1 << 20);
        assertEquals(new TreeMap<>(Map.of("shared/z/a", 2L)), revs(reopened.load()));
        byte[] added = obj("shared/z/c", 1, 10).encodePut();
        reopened.append(added);
        reopened.close();
        assertEquals(intact + RecordFile.frame(added).remaining(), Files.size(store.resolve(StoreFiles.LOG)),
                "the torn tail is dropped before the next record");
        assertEquals(Set.of("shared/z/a", "shared/z/c"), revs(new StoreFiles(store, 1 << 20).load()).keySet());
    }

    @Test
    void corruptChecksumEndsReplay() throws Exception {
        Path store = dir.resolve("s");
        StoreFiles files = new StoreFiles(store, 1 << 20);
        files.append(obj("shared/z/a", 1, 4).encodePut());
        files.append(obj("shared/z/b", 1, 4).encodePut());
        files.close();
        byte[] all = Files.readAllBytes(store.resolve(StoreFiles.LOG));
        all[all.length - 1] ^= 0x55;
        Files.write(store.resolve(StoreFiles.LOG), all);
        assertEquals(Set.of("shared/z/a"), revs(new StoreFiles(store, 1 << 20).load()).keySet());
    }

    @Test
    void compactsWhenLogExceedsFourTimesSnapshot() throws Exception {
        Path store = dir.resolve("s");
        StoreFiles files = new StoreFiles(store, 256);
        for (int i = 0; i < 40; i++) {
            files.append(obj("shared/z/k", i + 1, 100).encodePut());
        }
        assertTrue(Files.exists(store.resolve(StoreFiles.SNAPSHOT)), "compaction wrote a snapshot");
        assertTrue(files.logSize() <= 4 * Math.max(files.snapshotSize(), 256));
        files.close();
        assertEquals(new TreeMap<>(Map.of("shared/z/k", 40L)), revs(new StoreFiles(store, 256).load()));
    }

    @Test
    void serviceLoadsAfterEarlierWrites() throws Exception {
        PersistenceService service = new PersistenceService(dir, 1024, LoggerFactory.getLogger("t"));
        service.put("example.com:25565", obj("shared/z/a", 3, 8));
        assertEquals(3L, revs(service.load("example.com:25565").get()).get("shared/z/a"));
        assertTrue(Files.exists(service.storeDir("example.com:25565").resolve(StoreFiles.LOG)));
        service.close();
    }

    @Test
    void readingAStoreThatWasNeverWrittenCreatesNothing() throws Exception {
        PersistenceService service = new PersistenceService(dir, 1024, LoggerFactory.getLogger("t"));
        assertTrue(service.load("nowhere.example:25565").get().isEmpty());
        service.close();
        assertFalse(Files.exists(service.storeDir("nowhere.example:25565")));
    }

    @Test
    void onlySoManyStoresStayOpenAndClosedOnesComeBack() throws Exception {
        PersistenceService service = new PersistenceService(dir, 1024, 2, LoggerFactory.getLogger("t"));
        for (int round = 1; round <= 3; round++) {
            for (String key : List.of("a.example:1", "b.example:1", "c.example:1", "d.example:1")) {
                service.put(key, obj("shared/z/" + key.charAt(0) + round, round, 8));
            }
        }
        // Each store was closed and reopened several times; nothing was lost on the way.
        for (String key : List.of("a.example:1", "b.example:1", "c.example:1", "d.example:1")) {
            String k = "shared/z/" + key.charAt(0);
            assertEquals(new TreeMap<>(Map.of(k + 1, 1L, k + 2, 2L, k + 3, 3L)), revs(service.load(key).get()), key);
        }
        service.close();
    }
}
