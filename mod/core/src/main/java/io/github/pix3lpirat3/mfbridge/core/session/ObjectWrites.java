package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.KindValidator;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.ext.ConnRegistry;
import io.github.pix3lpirat3.mfbridge.core.ext.ExtensionHost;
import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import io.github.pix3lpirat3.mfbridge.core.remote.Quotas;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectKeys;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectStore;
import io.github.pix3lpirat3.mfbridge.core.store.PatchApplier;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.core.vars.Variables;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjRemove;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjUpsert;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectFull;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectPatch;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectTraits;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectWrite;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Writes to a session's own objects: snapshots, transactions, full writes, patches and removals,
 * each checked against ownership, permissions and quotas before it reaches the store.
 */
@ClientThread
final class ObjectWrites {
    /** An open transaction is discarded after this long, ms. */
    static final long TX_TIMEOUT_MS = 10_000L;
    /** Most obj.upsert and obj.remove messages one transaction buffers. */
    static final int MAX_TX_WRITES = 4096;

    private final ExtensionHost extensions;
    private final ObjectStore store;
    private final OwnerQuotas ownerQuotas;
    private final Devices access;
    private final SessionTable table;
    private final Outbox outbox;
    private final Permits permits;

    ObjectWrites(ExtensionHost extensions, ObjectStore store, OwnerQuotas ownerQuotas, Devices access,
            SessionTable table, Outbox outbox, Permits permits) {
        this.extensions = extensions;
        this.store = store;
        this.ownerQuotas = ownerQuotas;
        this.access = access;
        this.table = table;
        this.outbox = outbox;
        this.permits = permits;
    }

    static void checkWritable(Session s) {
        if (s.superseded) {
            throw superseded();
        }
    }

    static BridgeException superseded() {
        return new BridgeException("superseded", "a newer session took over this identity");
    }

    static BridgeException unknownKind(int kindId) {
        return new BridgeException("unknown_kind", "kind id " + Integer.toUnsignedString(kindId) + " is unknown");
    }

    void snapshotBegin(Session s) {
        checkWritable(s);
        s.snapshotGen = table.nextGen++;
        s.inSnapshot = true;
    }

    void snapshotEnd(Session s) {
        checkWritable(s);
        if (!s.inSnapshot) {
            return;
        }
        s.inSnapshot = false;
        // Drop everything under the prefix that this snapshot did not write.
        store.removeStale(s.ownerPrefix, s.snapshotGen);
    }

    /** Opens a transaction; one may be open per session at a time. */
    void beginTx(Session s, int tx, long now) {
        checkWritable(s);
        if (s.txId >= 0) {
            throw new BridgeException("invalid_message", "obj.begin " + Integer.toUnsignedString(tx)
                    + " while transaction " + s.txId + " is open");
        }
        s.txId = Integer.toUnsignedLong(tx);
        s.txStartedMs = now;
    }

    /** Buffers an object write while a transaction is open; returns false when none is. */
    boolean buffered(Session s, Object write) {
        if (s.txId < 0) {
            return false;
        }
        checkWritable(s);
        if (s.txWrites.size() >= MAX_TX_WRITES) {
            long tx = s.txId;
            discardTx(s);
            throw new BridgeException("quota_exceeded", "transaction " + tx + " exceeds " + MAX_TX_WRITES + " writes");
        }
        s.txWrites.add(write);
        return true;
    }

    /** Applies the buffered writes together, so no snapshot sees a part of them. */
    void commitTx(Connection c, Session s, int tx, long now) {
        long id = Integer.toUnsignedLong(tx);
        if (s.txId != id) {
            throw new BridgeException("invalid_message", s.txId < 0
                    ? "obj.commit " + id + " without obj.begin"
                    : "obj.commit " + id + " while transaction " + s.txId + " is open");
        }
        List<Object> writes = new ArrayList<>(s.txWrites);
        discardTx(s);
        for (Object w : writes) {
            switch (w) {
                case ObjUpsert u -> upsert(c, s, u, now);
                case ObjRemove r -> remove(s, r);
                default -> throw new IllegalStateException("unexpected buffered write " + w.getClass().getName());
            }
        }
    }

    static void discardTx(Session s) {
        s.txId = -1;
        s.txWrites.clear();
    }

    /** Discards the transactions that were left open too long. */
    void expireTransactions(SessionTable table, long now) {
        for (Connection c : table.connections) {
            for (Session s : c.sessions.values()) {
                expireTx(s, now);
            }
        }
    }

    private void expireTx(Session s, long now) {
        if (s.txId >= 0 && now - s.txStartedMs >= TX_TIMEOUT_MS) {
            long tx = s.txId;
            discardTx(s);
            outbox.sendSessionError(s, new BridgeException("timeout", "transaction " + tx + " was not committed within "
                    + TX_TIMEOUT_MS + " ms and was discarded"), CoreMessages.OBJ_BEGIN.id());
        }
    }

    void upsert(Connection c, Session s, ObjUpsert u, long now) {
        checkWritable(s);
        ConnRegistry reg = Objects.requireNonNull(c.registry);
        for (ObjectWrite w : u.writes()) {
            try {
                switch (w) {
                    case ObjectWrite.Full f -> applyFull(reg, s, f.value(), now);
                    case ObjectWrite.Patch p -> applyPatch(s, p.value(), now);
                }
            } catch (BridgeException e) {
                outbox.sendSessionError(s, e, CoreMessages.OBJ_UPSERT.id());
            } catch (WireException e) {
                outbox.sendSessionError(s, new BridgeException(e.code(), String.valueOf(e.getMessage())),
                        CoreMessages.OBJ_UPSERT.id());
            }
        }
    }

    static BridgeException notOwner(String key) {
        return new BridgeException("not_owner", "'" + key + "' is outside the session's owned prefix");
    }

    private void applyFull(ConnRegistry reg, Session s, ObjectFull f, long now) {
        String key = f.key();
        if (!key.startsWith(s.ownerPrefix)) {
            throw notOwner(key);
        }
        ObjectKeys.Parts parts = ObjectKeys.parse(key);
        KindType<?> kind = reg.kind(f.kind());
        if (kind == null) {
            throw unknownKind(f.kind());
        }
        if (f.scope() == Scope.WORLD && f.world() == null) {
            throw new BridgeException("invalid_message", "world-scoped object without world: " + key);
        }
        WorldStores.checkWorld(f.world());
        StoredObject existing = store.get(key);
        // Rev must increase within one writer, so replays are harmless.
        if (existing != null && existing.state().epoch() == s.epoch
                && Long.compareUnsigned(f.rev(), existing.state().rev()) <= 0) {
            existing.snapshotGen(s.snapshotGen);
            return;
        }
        Permission needed = Permits.KIND_PERMISSIONS.get(kind);
        if (needed != null && !permits.allowedOrReport(s, needed)) {
            return;
        }
        Quotas q = access.quotas(s.deviceId);
        if (kind == CoreKinds.VARS && !parts.localId().equals(Variables.LOCAL_ID)) {
            // One map per owner and extension, always at ~vars.
            throw new BridgeException("invalid_message", "core:vars must use the local id " + Variables.LOCAL_ID + ": " + key);
        }
        Object decoded = decodeKind(kind, f.data());
        ownerQuotas.check(parts.owner(), s.deviceId, kind, decoded, f.traits(), f.data().length,
                existing == null ? null : existing.state(), q);
        StoredObject o = store.put(key, parts, new StoredObject.State(kind, f.rev(), f.layer(), f.lifetime(),
                f.scope(), f.world(), f.ttlMs(), expiry(f.ttlMs(), now), f.data(), decoded, s.epoch, null, f.traits()));
        o.snapshotGen(s.snapshotGen);
    }

    private static long expiry(@Nullable Integer ttlMs, long now) {
        return ttlMs == null ? -1 : now + Integer.toUnsignedLong(ttlMs);
    }

    private void applyPatch(Session s, ObjectPatch p, long now) {
        String key = p.key();
        if (!key.startsWith(s.ownerPrefix)) {
            throw notOwner(key);
        }
        StoredObject existing = store.get(key);
        if (existing == null) {
            throw new BridgeException("invalid_message", "patch to missing key '" + key + "'");
        }
        StoredObject.State st = existing.state();
        if (st.epoch() == s.epoch && Long.compareUnsigned(p.rev(), st.rev()) <= 0) {
            existing.snapshotGen(s.snapshotGen);
            return;
        }
        byte[] data = PatchApplier.apply(st.data(), p.fields());
        Object decoded = decodeKind(st.kind(), data);
        Integer ttl = p.ttlMs() != null ? p.ttlMs() : st.ttlMs();
        // A patch replaces the traits only when it carries them.
        ObjectTraits traits = p.traits() != null ? p.traits() : st.traits();
        ownerQuotas.check(existing.parts().owner(), s.deviceId, st.kind(), decoded, traits, data.length, st,
                access.quotas(s.deviceId));
        StoredObject o = store.put(key, existing.parts(), new StoredObject.State(st.kind(), p.rev(), st.layer(),
                st.lifetime(), st.scope(), st.world(), ttl, expiry(ttl, now), data, decoded, s.epoch, null, traits));
        o.snapshotGen(s.snapshotGen);
    }

    /** Decodes and validates kind data; core kinds are checked against their size caps. */
    @Nullable Object decodeKind(KindType<?> kind, byte[] data) {
        Object value = kind.decode(data);
        KindCaps.check(kind, value);
        ExtensionHost.RegisteredKind<?> ext = extensions.registered(kind);
        if (ext != null && value != null) {
            String problem = validate(ext.kind().validator(), value);
            if (problem != null) {
                throw new BridgeException("invalid_message", kind.name() + ": " + problem);
            }
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable String validate(KindValidator<?> validator, Object value) {
        return ((KindValidator<Object>) validator).validate(value);
    }

    void remove(Session s, ObjRemove r) {
        checkWritable(s);
        for (String key : r.keys()) {
            if (key.startsWith(s.ownerPrefix)) {
                store.remove(key);
            } else {
                outbox.sendSessionError(s, notOwner(key), CoreMessages.OBJ_REMOVE.id());
            }
        }
        for (String prefix : r.prefixes()) {
            if (prefix.startsWith(s.ownerPrefix)) {
                store.removePrefix(prefix);
            } else {
                outbox.sendSessionError(s, notOwner(prefix), CoreMessages.OBJ_REMOVE.id());
            }
        }
    }
}
