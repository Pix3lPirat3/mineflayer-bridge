package io.github.pix3lpirat3.mfbridge.core.blob;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.core.persist.PersistenceService;
import io.github.pix3lpirat3.mfbridge.core.util.SecureFiles;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The content-addressed blob cache: files named by their hex SHA-256
 * under {@code <config>/mineflayer_bridge/blobs/}, bounded by total size with least-recently-used
 * eviction, plus an in-memory index of what is available.
 *
 * <p>All file I/O runs on the persistence I/O thread; decoding runs on the given worker executor.
 * The index is loaded asynchronously at construction: until {@link #isIndexLoaded()} turns true the
 * cache answers {@link #contains} with false, so callers that must not miss a cached blob (stream
 * offers) wait for the load first. Recency survives restarts through file modification times, which
 * {@link #touch} refreshes.
 *
 * <p>Pinned blobs (referenced by live objects) are never evicted, so the bound can be exceeded while
 * more than its worth of blobs is in use. Thread-safe.
 */
@AnyThread
public final class BlobCache implements BlobStore {
    /** Default disk bound, in bytes (LRU, 256 MiB by default). */
    public static final long DEFAULT_MAX_BYTES = 256L << 20;

    /** Decoded values kept for reuse; renderers hold their own references beyond this. */
    private static final int DECODED_CAPACITY = 32;

    private final Path dir;
    private final long maxBytes;
    private final PersistenceService io;
    private final Executor worker;
    private final Logger log;
    private final CompletableFuture<Void> indexLoaded;

    private final Object lock = new Object();
    // Guarded by lock. Access order: the first entry is the least recently used.
    private final LinkedHashMap<String, Long> index = new LinkedHashMap<>(64, 0.75f, true);
    // Guarded by lock. Payloads accepted but not yet on disk, so reads never race the write.
    private final Map<String, byte[]> unwritten = new HashMap<>();
    // Guarded by lock. Reference counts of blobs that must not be evicted.
    private final Map<String, Integer> pins = new HashMap<>();
    // Guarded by lock.
    private final LinkedHashMap<String, CompletableFuture<?>> decoded = new LinkedHashMap<>(16, 0.75f, true);
    // Guarded by lock.
    private long totalBytes;
    private final AtomicLong version = new AtomicLong();

    /**
     * Creates the cache and starts loading its index on the I/O thread.
     *
     * @param dir {@code <config>/mineflayer_bridge/blobs}
     * @param maxBytes disk bound in bytes; at least 1
     * @param worker executor for decoding, off the client and render threads
     */
    public BlobCache(Path dir, long maxBytes, PersistenceService io, Executor worker, Logger log) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.dir = dir;
        this.maxBytes = maxBytes;
        this.io = io;
        this.worker = worker;
        this.log = log;
        this.indexLoaded = io.submitIo(this::scan).handle((files, error) -> {
            if (error != null) {
                log.warn("Cannot read the blob cache {}: {}", dir, error.toString());
            } else {
                adopt(files);
            }
            return null;
        });
    }

    private record CachedFile(String hex, long size, long modifiedMs) {}

    private List<CachedFile> scan() throws IOException {
        Files.createDirectories(dir);
        List<CachedFile> out = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                String name = f.getFileName().toString();
                if (BlobRef.isHex(name) && Files.isRegularFile(f)) {
                    out.add(new CachedFile(name, Files.size(f), Files.getLastModifiedTime(f).toMillis()));
                } else if (name.endsWith(".tmp")) {
                    // Left behind by a crash during an atomic write.
                    Files.deleteIfExists(f);
                }
            }
        }
        out.sort((a, b) -> Long.compare(a.modifiedMs(), b.modifiedMs()));
        return out;
    }

    private void adopt(List<CachedFile> files) {
        List<String> evicted;
        synchronized (lock) {
            for (CachedFile f : files) {
                if (!index.containsKey(f.hex())) {
                    // Inserted oldest first, so access order starts out as file age.
                    index.put(f.hex(), f.size());
                    totalBytes += f.size();
                }
            }
            evicted = evictLocked();
        }
        deleteFiles(evicted);
        version.incrementAndGet();
    }

    /** Returns a future that completes when the on-disk index has been read (or failed to). */
    public CompletableFuture<Void> indexLoaded() {
        return indexLoaded;
    }

    /** Returns whether the on-disk index has been read. */
    public boolean isIndexLoaded() {
        return indexLoaded.isDone();
    }

    /**
     * Returns a counter that changes whenever the set of available blobs changes: index load,
     * {@link #put} or eviction.
     */
    public long version() {
        return version.get();
    }

    /** Returns whether a blob with this hex hash is cached. */
    public boolean contains(String hex) {
        synchronized (lock) {
            return index.containsKey(hex);
        }
    }

    @Override
    public boolean isAvailable(BlobRef ref) {
        return contains(ref.hex());
    }

    /** Returns the cached bytes on disk or pending write, in bytes. */
    public long totalBytes() {
        synchronized (lock) {
            return totalBytes;
        }
    }

    /** Returns the cached hex hashes from least to most recently used. */
    public List<String> lruOrder() {
        synchronized (lock) {
            return List.copyOf(index.keySet());
        }
    }

    /**
     * Adds a verified payload: available at once, written to disk on the I/O thread, and older
     * unpinned blobs are evicted while the total exceeds the bound.
     *
     * @param hex hex SHA-256 of {@code payload}; the caller has verified it
     * @param payload the decoded payload; the cache takes ownership and never modifies it
     */
    public void put(String hex, byte[] payload) {
        List<String> evicted;
        synchronized (lock) {
            Long previous = index.put(hex, (long) payload.length);
            if (previous != null) {
                return;
            }
            totalBytes += payload.length;
            unwritten.put(hex, payload);
            evicted = evictLocked();
        }
        Path file = dir.resolve(hex);
        io.submitIo(() -> {
            Files.createDirectories(dir);
            SecureFiles.writeAtomically(file, payload, false, log);
            return null;
        }).whenComplete((ok, error) -> {
            if (error != null) {
                log.warn("Cannot write blob {}: {}", hex, error.toString());
            }
            synchronized (lock) {
                unwritten.remove(hex);
            }
        });
        deleteFiles(evicted);
        version.incrementAndGet();
    }

    /** Marks a blob as recently used, in memory and through its file modification time. */
    public void touch(String hex) {
        synchronized (lock) {
            if (index.get(hex) == null) {
                return;
            }
        }
        Path file = dir.resolve(hex);
        io.submitIo(() -> {
            try {
                Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
            } catch (NoSuchFileException e) {
                // Still pending its first write, which stamps the time anyway.
            }
            return null;
        });
    }

    /** Protects a blob from eviction until a matching {@link #unpin}. */
    public void pin(String hex) {
        synchronized (lock) {
            pins.merge(hex, 1, Integer::sum);
        }
    }

    /** Releases one {@link #pin}; the blob becomes evictable when no pin is left. */
    public void unpin(String hex) {
        List<String> evicted;
        synchronized (lock) {
            Integer n = pins.get(hex);
            if (n == null) {
                return;
            }
            if (n <= 1) {
                pins.remove(hex);
            } else {
                pins.put(hex, n - 1);
            }
            evicted = evictLocked();
        }
        deleteFiles(evicted);
        if (!evicted.isEmpty()) {
            version.incrementAndGet();
        }
    }

    private List<String> evictLocked() {
        if (totalBytes <= maxBytes) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Iterator<Map.Entry<String, Long>> it = index.entrySet().iterator();
        while (totalBytes > maxBytes && it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (pins.containsKey(e.getKey())) {
                continue;
            }
            it.remove();
            totalBytes -= e.getValue();
            unwritten.remove(e.getKey());
            decoded.keySet().removeIf(k -> k.startsWith(e.getKey()));
            out.add(e.getKey());
        }
        return out;
    }

    private void deleteFiles(List<String> hexes) {
        for (String hex : hexes) {
            Path file = dir.resolve(hex);
            io.submitIo(() -> Files.deleteIfExists(file));
        }
    }

    /**
     * Reads a payload: from memory while its write is pending, otherwise from disk on the I/O thread.
     *
     * @return completes with the payload; exceptionally when the blob is not cached or unreadable
     */
    public CompletableFuture<byte[]> read(String hex) {
        synchronized (lock) {
            if (!index.containsKey(hex)) {
                return CompletableFuture.failedFuture(new IllegalStateException("blob " + hex + " is not cached"));
            }
            byte[] pending = unwritten.get(hex);
            if (pending != null) {
                return CompletableFuture.completedFuture(pending);
            }
        }
        Path file = dir.resolve(hex);
        CompletableFuture<byte[]> f = io.submitIo(() -> Files.readAllBytes(file));
        f.whenComplete((bytes, error) -> {
            if (error != null) {
                forget(hex);
            }
        });
        return f;
    }

    /** Drops an index entry whose file vanished, so the plugin is asked for it again on its next offer. */
    private void forget(String hex) {
        synchronized (lock) {
            Long size = index.remove(hex);
            if (size != null) {
                totalBytes -= size;
            }
        }
        version.incrementAndGet();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> decode(BlobRef ref, BlobDecoder<T> decoder) {
        String key = ref.hex() + '/' + decoder.id();
        synchronized (lock) {
            CompletableFuture<?> existing = decoded.get(key);
            if (existing != null && !existing.isCompletedExceptionally()) {
                return (CompletableFuture<T>) existing;
            }
        }
        CompletableFuture<T> f = read(ref.hex()).thenApplyAsync(decoder::decode, worker);
        synchronized (lock) {
            decoded.put(key, f);
            while (decoded.size() > DECODED_CAPACITY) {
                Iterator<String> it = decoded.keySet().iterator();
                it.next();
                it.remove();
            }
        }
        return f;
    }

    @Override
    public <T> @Nullable T decodedNow(BlobRef ref, BlobDecoder<T> decoder) {
        if (!isAvailable(ref)) {
            return null;
        }
        CompletableFuture<T> f = decode(ref, decoder);
        return f.isDone() && !f.isCompletedExceptionally() ? f.join() : null;
    }
}
