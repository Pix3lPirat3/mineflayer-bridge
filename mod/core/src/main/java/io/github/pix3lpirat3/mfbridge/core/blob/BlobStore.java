package io.github.pix3lpirat3.mfbridge.core.blob;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Read access to blobs for renderers. Objects that reference a blob
 * which has not arrived yet stay in the store and in render snapshots; renderers skip them until
 * {@link #isAvailable} turns true. The core publishes a new render snapshot when a blob arrives.
 *
 * <p>All methods are safe from any thread and never block: decoding runs on the blob worker.
 */
@AnyThread
public interface BlobStore {
    /** A store that has no blobs. */
    BlobStore NONE = new BlobStore() {
        @Override
        public boolean isAvailable(BlobRef ref) {
            return false;
        }

        @Override
        public <T> CompletableFuture<T> decode(BlobRef ref, BlobDecoder<T> decoder) {
            return CompletableFuture.failedFuture(new IllegalStateException("blob " + ref.hex() + " is not available"));
        }

        @Override
        public <T> @Nullable T decodedNow(BlobRef ref, BlobDecoder<T> decoder) {
            return null;
        }
    };

    /** Returns whether the blob has arrived and been verified, in memory or in the disk cache. */
    boolean isAvailable(BlobRef ref);

    /**
     * Reads and decodes a blob off the calling thread. Concurrent and repeated calls for the same blob
     * and decoder share one decode while the result stays in the decoded cache.
     *
     * @return completes with the decoded value; exceptionally when the blob is unavailable, unreadable
     *     or malformed
     */
    <T> CompletableFuture<T> decode(BlobRef ref, BlobDecoder<T> decoder);

    /**
     * Returns the decoded value if a decode already finished, otherwise starts one (when the blob is
     * available) and returns null. Suitable for per-frame polling.
     */
    <T> @Nullable T decodedNow(BlobRef ref, BlobDecoder<T> decoder);
}
