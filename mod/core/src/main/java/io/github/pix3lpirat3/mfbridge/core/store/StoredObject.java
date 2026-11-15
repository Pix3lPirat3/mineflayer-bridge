package io.github.pix3lpirat3.mfbridge.core.store;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.Lifetime;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectTraits;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import org.jspecify.annotations.Nullable;

/** One object in the store. Mutable; confined to the client thread. */
@ClientThread
public final class StoredObject {
    /**
     * Content of a write.
     *
     * @param kind object kind
     * @param rev revision
     * @param layer local layer name or null
     * @param lifetime lifetime
     * @param scope scope
     * @param world world key or null
     * @param ttlMs time to live in ms, or null for none
     * @param expiresAtMs clock ms at which the object expires, or -1
     * @param data encoded kind data
     * @param decoded decoded kind data, or null when the kind is unknown here
     * @param epoch writer epoch: the session epoch, or 0 for mod-written objects
     * @param storeKey world store the object persists in, or null when not persisted
     * @param traits object traits, or null
     */
    public record State(
            KindType<?> kind,
            long rev,
            @Nullable String layer,
            Lifetime lifetime,
            Scope scope,
            @Nullable WorldKey world,
            @Nullable Integer ttlMs,
            long expiresAtMs,
            byte[] data,
            @Nullable Object decoded,
            int epoch,
            @Nullable String storeKey,
            @Nullable ObjectTraits traits) {

        /** Creates a state without traits. */
        public State(KindType<?> kind, long rev, @Nullable String layer, Lifetime lifetime, Scope scope,
                @Nullable WorldKey world, @Nullable Integer ttlMs, long expiresAtMs, byte[] data, @Nullable Object decoded,
                int epoch, @Nullable String storeKey) {
            this(kind, rev, layer, lifetime, scope, world, ttlMs, expiresAtMs, data, decoded, epoch, storeKey, null);
        }
    }

    private final int handle;
    private final String key;
    private final ObjectKeys.Parts parts;
    private State state;
    private @Nullable String layerKey;
    private int snapshotGen;
    private boolean live = true;
    /** Position in the store's expiry heap, or -1. */
    int heapSlot = -1;

    StoredObject(int handle, String key, ObjectKeys.Parts parts, State state) {
        this.handle = handle;
        this.key = key;
        this.parts = parts;
        assign(state);
    }

    void assign(State next) {
        state = next;
        layerKey = next.layer() == null
                ? null
                : parts.owner() + '/' + parts.extension() + '/' + next.layer();
    }

    void kill() {
        live = false;
    }

    /** Returns the interned key handle. */
    public int handle() {
        return handle;
    }

    /** Returns the full key. */
    public String key() {
        return key;
    }

    /** Returns the parsed key. */
    public ObjectKeys.Parts parts() {
        return parts;
    }

    /** Returns the current content. */
    public State state() {
        return state;
    }

    /** Returns {@code <owner>/<extension>/<layer>}, or null when the object is in no layer. */
    public @Nullable String layerKey() {
        return layerKey;
    }

    /** Returns the generation of the last snapshot that declared this object. */
    public int snapshotGen() {
        return snapshotGen;
    }

    /** Marks this object as declared by the snapshot generation {@code gen}. */
    public void snapshotGen(int gen) {
        snapshotGen = gen;
    }

    /** Returns false once the object has left the store. */
    public boolean live() {
        return live;
    }
}
