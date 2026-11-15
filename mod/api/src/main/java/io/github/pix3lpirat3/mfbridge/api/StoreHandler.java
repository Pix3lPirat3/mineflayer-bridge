package io.github.pix3lpirat3.mfbridge.api;

/**
 * Observes store changes for one object kind. Callbacks run on the client thread after the store
 * has applied the change and must not block or write to the store.
 *
 * @param <T> decoded data type
 */
public interface StoreHandler<T> {
    /**
     * Called after an object of this kind was inserted or replaced.
     *
     * @param key full object key
     * @param rev new revision
     * @param data decoded data
     */
    default void upserted(String key, long rev, T data) {}

    /**
     * Called after an object of this kind left the store (removed, expired or dropped with its
     * session).
     *
     * @param key full object key
     */
    default void removed(String key) {}
}
