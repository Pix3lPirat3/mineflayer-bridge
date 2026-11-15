package io.github.pix3lpirat3.mfbridge.core.persist;

import io.github.pix3lpirat3.mfbridge.core.util.SecureFiles;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One world store directory: {@code ops.log} plus {@code snapshot.bin}. Confined to the I/O thread.
 * Keeps the current records in memory so compaction never reads the log back. Reading a store
 * that was never written creates nothing on disk; the directory and the log appear with the first
 * record.
 */
final class StoreFiles {
    static final String LOG = "ops.log";
    static final String SNAPSHOT = "snapshot.bin";

    private final Path dir;
    private final long compactFloorBytes;
    private final Map<String, byte[]> state = new LinkedHashMap<>();
    private @Nullable FileChannel log;
    private boolean loaded;
    private long logSize;
    private long snapshotSize;

    StoreFiles(Path dir, long compactFloorBytes) {
        this.dir = dir;
        this.compactFloorBytes = compactFloorBytes;
    }

    /** Loads the snapshot, replays the log and returns the resulting objects. Idempotent. */
    List<PersistedObject> load() throws IOException {
        if (!loaded) {
            RecordFile.Contents snap = RecordFile.read(dir.resolve(SNAPSHOT));
            for (byte[] r : snap.records()) {
                apply(r);
            }
            snapshotSize = snap.validLength();
            RecordFile.Contents ops = RecordFile.read(dir.resolve(LOG));
            for (byte[] r : ops.records()) {
                apply(r);
            }
            logSize = ops.validLength();
            loaded = true;
        }
        List<PersistedObject> out = new ArrayList<>(state.size());
        for (byte[] r : state.values()) {
            out.add(PersistedObject.decodePut(r));
        }
        return out;
    }

    private void apply(byte[] record) {
        String key = PersistedObject.keyOf(record);
        if (PersistedObject.isPut(record)) {
            state.put(key, record);
        } else {
            state.remove(key);
        }
    }

    /** Appends one record, then compacts when the log exceeds 4x the snapshot. */
    void append(byte[] record) throws IOException {
        load();
        apply(record);
        FileChannel ch = log;
        if (ch == null) {
            Files.createDirectories(dir);
            // Opening at the valid length drops a torn tail, so new appends never follow garbage.
            ch = RecordFile.openAppend(dir.resolve(LOG), logSize);
            log = ch;
        }
        logSize += RecordFile.append(ch, record);
        if (logSize > 4 * Math.max(snapshotSize, compactFloorBytes)) {
            compact();
        }
    }

    /** Rewrites the snapshot from memory and truncates the log. */
    void compact() throws IOException {
        Path tmp = dir.resolve(SNAPSHOT + ".tmp");
        long size = 0;
        try (FileChannel out = FileChannel.open(
                tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (byte[] r : state.values()) {
                ByteBuffer b = RecordFile.frame(r);
                size += b.remaining();
                while (b.hasRemaining()) {
                    out.write(b);
                }
            }
            out.force(true);
        }
        SecureFiles.replace(tmp, dir.resolve(SNAPSHOT));
        snapshotSize = size;
        FileChannel ch = log;
        if (ch != null) {
            ch.truncate(0);
            ch.position(0);
        }
        logSize = 0;
    }

    long logSize() {
        return logSize;
    }

    long snapshotSize() {
        return snapshotSize;
    }

    void close() throws IOException {
        FileChannel ch = log;
        log = null;
        if (ch != null) {
            ch.force(true);
            ch.close();
        }
    }
}
