package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.core.history.PlayerObjects;
import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.BridgeClock;
import io.github.pix3lpirat3.mfbridge.core.ext.ExtensionHost;
import io.github.pix3lpirat3.mfbridge.core.history.EditHistory;
import io.github.pix3lpirat3.mfbridge.core.persist.PersistenceService;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectKeys;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectStore;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.Lifetime;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjEditPropose;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjEditResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpBody;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * What the player writes: shared ops, player objects and edit proposals to bots, and the undo
 * history's view of them.
 */
@ClientThread
final class PlayerEdits implements PlayerObjects {
    private final BridgeClock clock;
    private final ExtensionHost extensions;
    private final PersistenceService persistence;
    private final ObjectStore store;
    private final SessionTable table;
    private final Outbox outbox;
    private final PlayerView view;
    private final WorldStores stores;
    private final ObjectWrites writes;
    private final SharedObjects shared;

    PlayerEdits(BridgeClock clock, ExtensionHost extensions, PersistenceService persistence, ObjectStore store,
            SessionTable table, Outbox outbox, PlayerView view, WorldStores stores, ObjectWrites writes,
            SharedObjects shared) {
        this.clock = clock;
        this.extensions = extensions;
        this.persistence = persistence;
        this.store = store;
        this.table = table;
        this.outbox = outbox;
        this.view = view;
        this.stores = stores;
        this.writes = writes;
        this.shared = shared;
    }

    /** Returns the edit history's view of the store and sessions. */
    EditHistory.Target target() {
        return new HistoryTarget();
    }

    /** Returns where the edit history is saved. */
    EditHistory.Storage storage() {
        return new HistoryStorage();
    }

    @Override
    public CompletableFuture<ObjEditResult> proposeEdit(String key, long baseRev, byte[] fields) {
        ObjectKeys.Parts parts;
        try {
            parts = ObjectKeys.parse(key);
        } catch (BridgeException e) {
            return CompletableFuture.failedFuture(e);
        }
        Session w = table.writer(parts.owner());
        if (w == null || w.superseded) {
            return CompletableFuture.failedFuture(new BridgeException("disconnected", "no session owns " + key));
        }
        return outbox.request(w, CoreMessages.OBJ_EDIT_PROPOSE, new ObjEditPropose(key, baseRev, fields),
                CoreMessages.OBJ_EDIT_RESULT);
    }

    @Override
    public SharedOpResult sharedOp(SharedOpBody body) {
        for (String key : stores.storesFor(body, view.playerWorld())) {
            if (!stores.isLoaded(key)) {
                stores.ensureLoading(key, true);
                throw new BridgeException("unsupported", "world store is still loading", true, null);
            }
        }
        return shared.applyShared(body, null, view.playerWorld(), null, clock.millis());
    }

    @Override
    public void put(String key, KindType<?> kind, @Nullable String layer, Scope scope,
            @Nullable WorldKey world, byte[] data) {
        if (!key.startsWith(ObjectKeys.PLAYER_PREFIX)) {
            throw new IllegalArgumentException("player objects must be under player/: " + key);
        }
        if (scope == Scope.WORLD && world == null) {
            throw new IllegalArgumentException("world-scoped object without world");
        }
        ObjectKeys.Parts parts = ObjectKeys.parse(key);
        Object decoded;
        try {
            decoded = writes.decodeKind(kind, data);
        } catch (WireException | BridgeException e) {
            throw new IllegalArgumentException("data does not decode as " + kind.name(), e);
        }
        StoredObject existing = store.get(key);
        long rev = existing == null ? 1 : existing.state().rev() + 1;
        StoredObject o = store.put(key, parts, new StoredObject.State(kind, rev, layer,
                Lifetime.PERSISTENT, scope, world, null, -1, data,
                decoded, 0, WorldStores.storeKeyFor(world, view.playerWorld()), existing == null ? null : existing.state().traits()));
        shared.persist(o);
    }

    @Override
    public boolean remove(String key) {
        StoredObject existing = key.startsWith(ObjectKeys.PLAYER_PREFIX) ? store.get(key) : null;
        if (existing == null) {
            return false;
        }
        shared.removeStored(existing);
        return true;
    }

    /** The history's view of the store and sessions; every call runs on the client thread. */
    private final class HistoryTarget implements EditHistory.Target {
        @Override
        public EditHistory.@Nullable Image current(String key) {
            StoredObject o = store.get(key);
            if (o == null) {
                return null;
            }
            StoredObject.State st = o.state();
            return new EditHistory.Image(st.kind().name(), st.layer(), st.lifetime(), st.scope(), st.world(), st.data(),
                    st.rev());
        }

        @Override
        public SharedOpResult sharedOp(SharedOpBody op) {
            return PlayerEdits.this.sharedOp(op);
        }

        @Override
        public int kindId(String kindName) {
            KindType<?> kind = extensions.kindByName(kindName);
            if (kind.id() < 0) {
                throw new BridgeException("unknown_kind", "kind " + kindName + " is unknown");
            }
            return kind.id();
        }

        @Override
        public void putPlayer(String key, EditHistory.Image image) {
            put(key, extensions.kindByName(image.kindName()), image.layer(), image.scope(), image.world(),
                    image.data());
        }

        @Override
        public boolean removePlayer(String key) {
            return remove(key);
        }

        @Override
        public CompletableFuture<ObjEditResult> propose(String key, long baseRev, byte[] fields) {
            return proposeEdit(key, baseRev, fields);
        }
    }

    /** Saves each world store's history next to its op log, through the I/O thread. */
    private final class HistoryStorage implements EditHistory.Storage {
        private static final String FILE = "history.bin";

        @Override
        public CompletableFuture<byte @Nullable []> load(String storeKey) {
            Path file = persistence.storeDir(storeKey).resolve(FILE);
            return persistence.submitIo(() -> Files.exists(file) ? Files.readAllBytes(file) : null);
        }

        @Override
        public void save(String storeKey, byte[] bytes) {
            persistence.writeFile(persistence.storeDir(storeKey).resolve(FILE), bytes, false);
        }
    }
}
