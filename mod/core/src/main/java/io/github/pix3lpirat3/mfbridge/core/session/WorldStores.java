package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.ext.ExtensionHost;
import io.github.pix3lpirat3.mfbridge.core.persist.PersistedObject;
import io.github.pix3lpirat3.mfbridge.core.persist.PersistenceService;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectKeys;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectStore;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.core.world.ServerKeys;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpBody;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** The world stores loaded from disk: which are loading, loaded, and may still be loaded. */
@ClientThread
final class WorldStores {
    /** Most world stores loaded for worlds that bots name. */
    static final int MAX_BOT_STORES = 64;
    /** Longest server, backend or dimension name in a world key. */
    static final int MAX_WORLD_NAME = 255;
    private final Map<String, StoreLoad> storeLoads = new HashMap<>();

    private final Logger log;
    private final ExtensionHost extensions;
    private final PersistenceService persistence;
    private final ObjectStore store;

    WorldStores(Logger log, ExtensionHost extensions, PersistenceService persistence, ObjectStore store) {
        this.log = log;
        this.extensions = extensions;
        this.persistence = persistence;
        this.store = store;
    }

    private static final class StoreLoad {
        final CompletableFuture<List<PersistedObject>> future;
        boolean applied;

        StoreLoad(CompletableFuture<List<PersistedObject>> future) {
            this.future = future;
        }
    }

    List<String> storesFor(SharedOpBody body, @Nullable WorldKey context) {
        return switch (body) {
            case SharedOpBody.Create c -> List.of(storeKeyFor(c.value().object().world(), context));
            case SharedOpBody.Update u -> storesForExisting(u.value().key(), context);
            case SharedOpBody.Remove r -> storesForExisting(r.value().key(), context);
        };
    }

    private List<String> storesForExisting(String key, @Nullable WorldKey context) {
        return store.get(key) != null ? List.of() : List.of(storeKeyFor(null, context), ServerKeys.GLOBAL);
    }

    static String storeKeyFor(@Nullable WorldKey world, @Nullable WorldKey context) {
        WorldKey w = world != null ? world : context;
        return w == null ? ServerKeys.GLOBAL : ServerKeys.normalize(w.server());
    }

    boolean isLoaded(String storeKey) {
        StoreLoad l = storeLoads.get(storeKey);
        return l != null && l.applied;
    }

    /**
     * Starts loading a world store unless it is loading or loaded already.
     *
     * @param forPlayer false for a world a bot named: only {@link #MAX_BOT_STORES} stores are loaded
     *     for those
     * @return false when the store may not be loaded
     */
    boolean ensureLoading(String storeKey, boolean forPlayer) {
        if (storeLoads.containsKey(storeKey)) {
            return true;
        }
        if (!forPlayer && storeLoads.size() >= MAX_BOT_STORES) {
            return false;
        }
        storeLoads.put(storeKey, new StoreLoad(persistence.load(storeKey)));
        return true;
    }

    /**
     * Checks the names in a world key a bot sent: 1 to {@link #MAX_WORLD_NAME} characters, the
     * server made of letters, digits and {@code . _ - : [ ]}, the others without control characters.
     *
     * @throws BridgeException {@code invalid_message}
     */
    static void checkWorld(@Nullable WorldKey w) {
        if (w == null) {
            return;
        }
        String server = w.server();
        boolean ok = !server.isEmpty() && server.length() <= MAX_WORLD_NAME;
        for (int i = 0; ok && i < server.length(); i++) {
            char c = server.charAt(i);
            ok = Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' || c == ':' || c == '[' || c == ']';
        }
        if (!ok) {
            throw new BridgeException("invalid_message", "bad server name in a world key");
        }
        checkName(w.dimension(), "dimension");
        String backend = w.backend();
        if (backend != null) {
            checkName(backend, "backend");
        }
    }

    private static void checkName(String name, String what) {
        boolean ok = !name.isEmpty() && name.length() <= MAX_WORLD_NAME;
        for (int i = 0; ok && i < name.length(); i++) {
            ok = !Character.isISOControl(name.charAt(i));
        }
        if (!ok) {
            throw new BridgeException("invalid_message", "bad " + what + " name in a world key");
        }
    }

    /**
     * Puts the objects of every store that finished loading into the object store.
     *
     * @return whether a store was applied, so shared subscriptions need another look
     */
    boolean applyLoads() {
        boolean any = false;
        for (Map.Entry<String, StoreLoad> e : storeLoads.entrySet()) {
            StoreLoad l = e.getValue();
            if (l.applied || !l.future.isDone()) {
                continue;
            }
            l.applied = true;
            any = true;
            if (l.future.isCompletedExceptionally()) {
                continue;
            }
            for (PersistedObject p : l.future.join()) {
                if (store.get(p.key()) != null) {
                    continue;
                }
                ObjectKeys.Parts parts;
                try {
                    parts = ObjectKeys.parse(p.key());
                } catch (BridgeException ex) {
                    log.warn("Skipping stored object with malformed key in store {}", e.getKey());
                    continue;
                }
                KindType<?> kind = extensions.kindByName(p.kindName());
                Object decoded;
                try {
                    decoded = kind.decode(p.data());
                } catch (WireException ex) {
                    decoded = null;
                }
                store.put(p.key(), parts, new StoredObject.State(kind, p.rev(), p.layer(), p.lifetime(), p.scope(),
                        p.world(), null, -1, p.data(), decoded, 0, e.getKey(), p.traits()));
            }
        }
        return any;
    }

    /** Returns whether every world store requested so far has been loaded and applied. */
    boolean loaded() {
        for (StoreLoad l : storeLoads.values()) {
            if (!l.applied) {
                return false;
            }
        }
        return true;
    }

    /** Returns whether a store is loading or loaded. */
    boolean known(String storeKey) {
        return storeLoads.containsKey(storeKey);
    }
}
