package io.github.pix3lpirat3.mfbridge.core.persist;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.core.util.SecureFiles;
import io.github.pix3lpirat3.mfbridge.core.world.ServerKeys;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Runs all bridge file I/O on one dedicated daemon thread.
 * Tasks run in submission order, so a load always sees every earlier write.
 * Callers encode on their own thread and only hand over bytes.
 */
@AnyThread
public final class PersistenceService implements AutoCloseable {
    /** Smallest snapshot size assumed by the compaction rule, so tiny stores do not compact on every write. */
    public static final long DEFAULT_COMPACT_FLOOR = 16 * 1024;

    /** World stores kept open unless the caller says otherwise. */
    public static final int DEFAULT_OPEN_STORES = 8;

    private final Path dataDir;
    private final long compactFloorBytes;
    private final int maxOpenStores;
    private final Logger log;
    private final ExecutorService io;
    // Confined to the I/O thread; least recently used first.
    private final LinkedHashMap<String, StoreFiles> stores = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * Starts the I/O thread, keeping {@link #DEFAULT_OPEN_STORES} world stores open.
     *
     * @param dataDir {@code <config>/mineflayer_bridge/data}
     * @param compactFloorBytes minimum snapshot size used by the 4x compaction rule, in bytes
     */
    public PersistenceService(Path dataDir, long compactFloorBytes, Logger log) {
        this(dataDir, compactFloorBytes, DEFAULT_OPEN_STORES, log);
    }

    /**
     * Starts the I/O thread.
     *
     * @param dataDir {@code <config>/mineflayer_bridge/data}
     * @param compactFloorBytes minimum snapshot size used by the 4x compaction rule, in bytes
     * @param maxOpenStores world stores kept open and in memory; beyond it the least recently used
     *     one is closed and read again from disk when it is next needed
     */
    public PersistenceService(Path dataDir, long compactFloorBytes, int maxOpenStores, Logger log) {
        this.dataDir = dataDir;
        this.compactFloorBytes = compactFloorBytes;
        this.maxOpenStores = Math.max(1, maxOpenStores);
        this.log = log;
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mfbridge-io");
            t.setDaemon(true);
            return t;
        });
    }

    /** Returns the directory of one world store. */
    public Path storeDir(String serverKey) {
        return dataDir.resolve(ServerKeys.directoryName(serverKey));
    }

    private StoreFiles store(String serverKey) {
        StoreFiles s = stores.get(serverKey);
        if (s != null) {
            return s;
        }
        while (stores.size() >= maxOpenStores) {
            Iterator<StoreFiles> oldest = stores.values().iterator();
            StoreFiles gone = oldest.next();
            oldest.remove();
            try {
                gone.close();
            } catch (IOException e) {
                log.warn("Cannot close bridge store: {}", e.toString());
            }
        }
        s = new StoreFiles(storeDir(serverKey), compactFloorBytes);
        stores.put(serverKey, s);
        return s;
    }

    /**
     * Loads a world store: snapshot plus replayed log.
     *
     * @return the stored objects; completes exceptionally when the files cannot be read
     */
    public CompletableFuture<List<PersistedObject>> load(String serverKey) {
        CompletableFuture<List<PersistedObject>> result = new CompletableFuture<>();
        submit(() -> {
            try {
                result.complete(store(serverKey).load());
            } catch (IOException | RuntimeException e) {
                log.warn("Cannot load bridge store {}: {}", serverKey, e.toString());
                result.completeExceptionally(e);
            }
        }, result);
        return result;
    }

    /** Appends a put record for an object. */
    public void put(String serverKey, PersistedObject object) {
        append(serverKey, object.encodePut());
    }

    /** Appends a remove record for a key. */
    public void remove(String serverKey, String key) {
        append(serverKey, PersistedObject.encodeRemove(key));
    }

    private void append(String serverKey, byte[] record) {
        submit(() -> {
            try {
                store(serverKey).append(record);
            } catch (IOException | RuntimeException e) {
                log.warn("Cannot write bridge store {}: {}", serverKey, e.toString());
            }
        }, null);
    }

    /** Writes a whole file atomically on the I/O thread. */
    public void writeFile(Path file, byte[] data, boolean ownerOnly) {
        submit(() -> {
            try {
                SecureFiles.writeAtomically(file, data, ownerOnly, log);
            } catch (IOException e) {
                log.warn("Cannot write {}: {}", file, e.toString());
            }
        }, null);
    }

    /** One unit of file I/O run on the I/O thread. */
    @FunctionalInterface
    public interface IoTask<T> {
        /**
         * Runs the I/O.
         *
         * @return the result, handed to the returned future
         * @throws IOException when the I/O fails; the future completes exceptionally
         */
        T run() throws IOException;
    }

    /**
     * Runs arbitrary file I/O on the I/O thread, in submission order with every other task.
     *
     * @return completes with the task's result, or exceptionally when it throws or the thread stopped
     */
    public <T> CompletableFuture<T> submitIo(IoTask<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        submit(() -> {
            try {
                result.complete(task.run());
            } catch (IOException | RuntimeException e) {
                result.completeExceptionally(e);
            }
        }, result);
        return result;
    }

    /** Returns a future that completes once every task submitted before it has run. */
    public CompletableFuture<Void> flush() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        submit(() -> done.complete(null), done);
        return done;
    }

    private void submit(Runnable task, @Nullable CompletableFuture<?> onReject) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("Bridge I/O thread stopped; dropping a task");
            if (onReject != null) {
                onReject.completeExceptionally(e);
            }
        }
    }

    /** Runs pending writes, closes files and stops the thread, waiting up to 5 seconds. */
    @Override
    public void close() {
        submit(() -> {
            for (StoreFiles s : stores.values()) {
                try {
                    s.close();
                } catch (IOException e) {
                    log.warn("Cannot close bridge store: {}", e.toString());
                }
            }
            stores.clear();
        }, null);
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Bridge I/O thread did not finish within 5 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
