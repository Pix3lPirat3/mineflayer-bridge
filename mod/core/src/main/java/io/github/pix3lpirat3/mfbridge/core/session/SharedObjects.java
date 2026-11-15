package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.ext.ConnRegistry;
import io.github.pix3lpirat3.mfbridge.core.persist.PersistedObject;
import io.github.pix3lpirat3.mfbridge.core.persist.PersistenceService;
import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import io.github.pix3lpirat3.mfbridge.core.remote.Quotas;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectKeys;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectStore;
import io.github.pix3lpirat3.mfbridge.core.store.PatchApplier;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.core.util.Encoding;
import io.github.pix3lpirat3.mfbridge.core.world.WorldMatchers;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjRemove;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjUpsert;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectFull;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectWrite;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOp;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpBody;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedSubscribe;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireCodec;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireReader;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Shared objects: the ops that create, update and remove them with revision checks, their
 * persistence, and delivery to the sessions that subscribed.
 */
@ClientThread
final class SharedObjects {
    private static final int UPSERT_BATCH = 256;
    private static final WireCodec<@Nullable Object> DYNAMIC = new WireCodec<>() {
        @Override
        public void write(WireWriter w, @Nullable Object v) {
            w.writeAny(v);
        }

        @Override
        public @Nullable Object read(WireReader r) {
            return r.readAny();
        }
    };

    private final WorldMatchers matchers;
    private final PersistenceService persistence;
    private final ObjectStore store;
    private final OwnerQuotas ownerQuotas;
    private final Devices access;
    private final SessionTable table;
    private final Outbox outbox;
    private final Permits permits;
    private final WorldStores stores;
    private final ObjectWrites writes;

    SharedObjects(WorldMatchers matchers, PersistenceService persistence, ObjectStore store, OwnerQuotas ownerQuotas,
            Devices access, SessionTable table, Outbox outbox, Permits permits, WorldStores stores,
            ObjectWrites writes) {
        this.matchers = matchers;
        this.persistence = persistence;
        this.store = store;
        this.ownerQuotas = ownerQuotas;
        this.access = access;
        this.table = table;
        this.outbox = outbox;
        this.permits = permits;
        this.stores = stores;
        this.writes = writes;
    }

    void sharedOp(Connection c, Session s, Envelope env, SharedOp op, long now) {
        long req = Dispatcher.needReq(env);
        ObjectWrites.checkWritable(s);
        permits.requirePermission(s, Permission.SHARED_WRITE);
        ConnRegistry reg = Objects.requireNonNull(c.registry);
        SharedOpResult result = applyShared(op.op(), reg, s.world, s.deviceId, now);
        outbox.sendReliable(s, CoreMessages.SHARED_OP_RESULT, result, Envelope.RESPONSE, req);
    }

    static BridgeException conflict(long rev, @Nullable Object object) {
        Map<String, Object> details = new HashMap<>();
        details.put("rev", rev);
        if (object != null) {
            details.put("object", object);
        }
        return new BridgeException("conflict", "expected revision does not match", false, details);
    }

    /**
     * A conflict's details hold the current object, as {@code {rev, object}} where
     * {@code object} is the {@code ObjectFull} field array, so the proposer can rebase without a read.
     */
    static BridgeException conflict(StoredObject current) {
        StoredObject.State st = current.state();
        ObjectFull full = new ObjectFull(current.key(), st.kind().id(), st.rev(), st.layer(), st.lifetime(), st.scope(),
                st.world(), null, st.data());
        return conflict(st.rev(), Encoding.decode(DYNAMIC, Encoding.encode(ObjectFull.CODEC, full)));
    }

    /**
     * Applies a shared op.
     *
     * @param reg registry to resolve the create's kind id with, or null to accept only core kinds
     * @param context world used to pick the store when the object has none
     * @param device the device behind the op, whose quotas it counts against, or null for the player
     */
    SharedOpResult applyShared(SharedOpBody body, @Nullable ConnRegistry reg, @Nullable WorldKey context,
            @Nullable String device, long now) {
        Quotas limits = device == null ? null : access.quotas(device);
        return switch (body) {
            case SharedOpBody.Create cr -> {
                ObjectFull f = cr.value().object();
                String key = f.key();
                if (!key.startsWith(ObjectKeys.SHARED_PREFIX)) {
                    throw ObjectWrites.notOwner(key);
                }
                ObjectKeys.Parts parts = ObjectKeys.parse(key);
                KindType<?> kind = reg != null ? reg.kind(f.kind()) : CoreKinds.byId(f.kind());
                if (kind == null) {
                    throw ObjectWrites.unknownKind(f.kind());
                }
                if (f.scope() == Scope.WORLD && f.world() == null) {
                    throw new BridgeException("invalid_message", "world-scoped object without world: " + key);
                }
                StoredObject existing = store.get(key);
                if (existing != null) {
                    throw conflict(existing);
                }
                if (device != null) {
                    WorldStores.checkWorld(f.world());
                }
                Object decoded = writes.decodeKind(kind, f.data());
                String storeKey = WorldStores.storeKeyFor(f.world(), context);
                if (!stores.known(storeKey)) {
                    throw new BridgeException("quota_exceeded", "bots named more than " + WorldStores.MAX_BOT_STORES + " servers");
                }
                ownerQuotas.checkShared(device, decoded, f.data().length, null, limits);
                if (device != null) {
                    ownerQuotas.sharedCreatedBy(key, device);
                }
                StoredObject o = store.put(key, parts, new StoredObject.State(kind, 1, f.layer(), f.lifetime(),
                        f.scope(), f.world(), null, -1, f.data(), decoded, 0, storeKey, f.traits()));
                persist(o);
                broadcastUpsert(o);
                yield new SharedOpResult(key, 1);
            }
            case SharedOpBody.Update up -> {
                String key = up.value().key();
                StoredObject existing = sharedTarget(key);
                StoredObject.State st = existing.state();
                if (st.rev() != up.value().expectRev()) {
                    throw conflict(existing);
                }
                byte[] data = PatchApplier.apply(st.data(), up.value().fields());
                Object decoded = writes.decodeKind(st.kind(), data);
                ownerQuotas.checkShared(device, decoded, data.length, st, limits);
                long rev = st.rev() + 1;
                StoredObject o = store.put(key, existing.parts(), new StoredObject.State(st.kind(), rev, st.layer(),
                        st.lifetime(), st.scope(), st.world(), null, -1, data, decoded, 0, st.storeKey(), st.traits()));
                persist(o);
                broadcastUpsert(o);
                yield new SharedOpResult(key, rev);
            }
            case SharedOpBody.Remove rm -> {
                String key = rm.value().key();
                StoredObject existing = sharedTarget(key);
                long rev = existing.state().rev();
                if (rev != rm.value().expectRev()) {
                    throw conflict(existing);
                }
                removeStored(existing);
                broadcastRemove(existing);
                yield new SharedOpResult(key, rev + 1);
            }
        };
    }

    private StoredObject sharedTarget(String key) {
        if (!key.startsWith(ObjectKeys.SHARED_PREFIX)) {
            throw ObjectWrites.notOwner(key);
        }
        StoredObject existing = store.get(key);
        if (existing == null) {
            throw conflict(0, null);
        }
        return existing;
    }

    void persist(StoredObject o) {
        StoredObject.State st = o.state();
        String storeKey = st.storeKey();
        if (storeKey != null) {
            persistence.put(storeKey, new PersistedObject(o.key(), st.kind().name(), st.rev(), st.layer(),
                    st.lifetime(), st.scope(), st.world(), st.data(), st.traits()));
        }
    }

    void subscribe(Session s, SharedSubscribe sub) {
        if (!permits.allowedOrReport(s, Permission.SHARED_READ)) {
            return;
        }
        s.subscription = sub;
        resyncShared(s);
    }

    private boolean wantsShared(Session s, StoredObject o) {
        SharedSubscribe sub = s.subscription;
        if (sub == null || s.ended || !access.permitted(s, Permission.SHARED_READ)) {
            return false;
        }
        StoredObject.State st = o.state();
        if (!sub.kinds().isEmpty() && !sub.kinds().contains(st.kind().name())) {
            return false;
        }
        if (!sub.extensions().isEmpty() && !sub.extensions().contains(o.parts().extension())) {
            return false;
        }
        if (st.scope() == Scope.WORLD) {
            // Only to sessions whose reported world matches.
            WorldKey mine = s.world;
            WorldKey theirs = st.world();
            return mine != null && theirs != null && matchers.matches(mine, theirs);
        }
        return true;
    }

    private void broadcastUpsert(StoredObject o) {
        for (Session s : table.liveSessions()) {
            if (wantsShared(s, o)) {
                s.deliveredShared.add(o.key());
                deliverUpserts(s, List.of(o));
            } else if (s.deliveredShared.remove(o.key())) {
                deliverRemoves(s, List.of(o.key()));
            }
        }
    }

    private void broadcastRemove(StoredObject o) {
        for (Session s : table.liveSessions()) {
            if (s.deliveredShared.remove(o.key())) {
                deliverRemoves(s, List.of(o.key()));
            }
        }
    }

    /** Brings a session's delivered shared set in line with its subscription and world. */
    void resyncShared(Session s) {
        if (s.subscription == null) {
            return;
        }
        Set<String> desired = new HashSet<>();
        List<StoredObject> toSend = new ArrayList<>();
        for (StoredObject o : store.under(ObjectKeys.SHARED_PREFIX)) {
            if (wantsShared(s, o)) {
                desired.add(o.key());
                if (!s.deliveredShared.contains(o.key())) {
                    toSend.add(o);
                }
            }
        }
        List<String> toRemove = new ArrayList<>();
        for (String key : s.deliveredShared) {
            if (!desired.contains(key)) {
                toRemove.add(key);
            }
        }
        s.deliveredShared = desired;
        deliverUpserts(s, toSend);
        deliverRemoves(s, toRemove);
    }

    private void deliverUpserts(Session s, List<StoredObject> objects) {
        ConnRegistry reg = s.registry();
        if (reg == null || objects.isEmpty()) {
            return;
        }
        List<ObjectWrite> batch = new ArrayList<>(Math.min(objects.size(), UPSERT_BATCH));
        for (StoredObject o : objects) {
            StoredObject.State st = o.state();
            int kindId = reg.kindId(st.kind());
            if (kindId < 0) {
                continue;
            }
            batch.add(new ObjectWrite.Full(new ObjectFull(o.key(), kindId, st.rev(), st.layer(), st.lifetime(),
                    st.scope(), st.world(), st.ttlMs(), st.data(), st.traits())));
            if (batch.size() == UPSERT_BATCH) {
                outbox.sendReliable(s, CoreMessages.OBJ_UPSERT, new ObjUpsert(batch), 0, Envelope.NONE);
                batch = new ArrayList<>(UPSERT_BATCH);
            }
        }
        if (!batch.isEmpty()) {
            outbox.sendReliable(s, CoreMessages.OBJ_UPSERT, new ObjUpsert(batch), 0, Envelope.NONE);
        }
    }

    private void deliverRemoves(Session s, List<String> keys) {
        if (!keys.isEmpty()) {
            outbox.sendReliable(s, CoreMessages.OBJ_REMOVE, new ObjRemove(keys, List.of()), 0, Envelope.NONE);
        }
    }

    void removeStored(StoredObject o) {
        store.remove(o.key());
        String storeKey = o.state().storeKey();
        if (storeKey != null) {
            persistence.remove(storeKey, o.key());
        }
    }

    /** Brings every session's delivered shared set in line, after a world store was loaded. */
    void resyncAll() {
        for (Session s : table.liveSessions()) {
            resyncShared(s);
        }
    }
}
