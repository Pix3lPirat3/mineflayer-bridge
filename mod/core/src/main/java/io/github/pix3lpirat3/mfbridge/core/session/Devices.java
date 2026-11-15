package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.core.persist.BotSettingsStore;
import io.github.pix3lpirat3.mfbridge.core.BridgeClock;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.ApprovalDecision;
import io.github.pix3lpirat3.mfbridge.core.auth.DeviceRegistry;
import io.github.pix3lpirat3.mfbridge.core.decl.InterceptDecl;
import io.github.pix3lpirat3.mfbridge.core.remote.DeviceControls;
import io.github.pix3lpirat3.mfbridge.core.remote.DeviceInfo;
import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import io.github.pix3lpirat3.mfbridge.core.remote.PermissionBatch;
import io.github.pix3lpirat3.mfbridge.core.remote.PermissionSet;
import io.github.pix3lpirat3.mfbridge.core.remote.Quota;
import io.github.pix3lpirat3.mfbridge.core.remote.Quotas;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Device permissions and quotas behind {@link DeviceControls}. Effective values are cached
 * per device and rebuilt when its record changes, so checks on the intake path do not allocate.
 */
@ClientThread
final class Devices implements DeviceControls {
    /** Cached effective values of one device. */
    private record Access(DeviceRegistry.Device record, PermissionSet device, Map<String, PermissionSet> bots,
            Quotas quotas) {
        PermissionSet forBot(@Nullable String identity) {
            if (identity == null) {
                return device;
            }
            PermissionSet b = bots.get(identity);
            return b != null ? b : device;
        }
    }

    private final BridgeClock clock;
    private final SessionTable table;
    private final BotSettingsStore botStore;
    private @Nullable Permits permits;
    private @Nullable SessionLifecycle lifecycle;
    private @Nullable Intercepts intercepts;
    private final DeviceRegistry registry;
    private final Quotas defaultQuotas;
    /** Local devices the player allowed once: never written to disk. */
    private final Map<String, DeviceRegistry.Device> transientDevices = new HashMap<>();
    private final Map<String, Access> cache = new HashMap<>();
    private final Map<String, Set<String>> botsSeen = new HashMap<>();
    /** Requests collected per remote device for the batched prompt, in arrival order. */
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    /** {@code deviceId + '\n' + permission} already queued or answered in this run. */
    private final Set<String> asked = new HashSet<>();

    /** Requests of one device waiting for the batched prompt. */
    private static final class Pending {
        /** Monotonic ms of the first request. */
        final long firstMs;
        /** By wire name, or {@code "cancel\n" + identity} for an intercept cancel. */
        final Map<String, PermissionBatch.Item> items = new LinkedHashMap<>();

        Pending(long firstMs) {
            this.firstMs = firstMs;
        }
    }

    Devices(BridgeClock clock, SessionTable table, BotSettingsStore botStore, DeviceRegistry registry,
            Quotas defaultQuotas) {
        this.clock = clock;
        this.table = table;
        this.botStore = botStore;
        this.registry = registry;
        this.defaultQuotas = defaultQuotas;
    }

    /** Sets the parts a permission change or a revoke acts on; they are built after this one. */
    void bind(Permits permits, SessionLifecycle lifecycle, Intercepts intercepts) {
        this.permits = permits;
        this.lifecycle = lifecycle;
        this.intercepts = intercepts;
    }

    DeviceRegistry registry() {
        return registry;
    }

    /** Remembers a local device for this run only (approval answered with allow once). */
    void allowOnce(String id, String name, String address, long nowMillis) {
        transientDevices.put(id, new DeviceRegistry.Device(id, name, DeviceRegistry.LOCAL, null, nowMillis, nowMillis,
                address));
        cache.remove(id);
    }

    /** Stores a new or approved device. */
    void put(DeviceRegistry.Device d) {
        transientDevices.remove(d.id());
        registry.put(d);
        cache.remove(d.id());
    }

    void touch(String id, String name, String address, long nowMillis) {
        DeviceRegistry.Device t = transientDevices.get(id);
        if (t != null) {
            transientDevices.put(id, t.seen(name, address, nowMillis));
        } else {
            registry.touch(id, name, address, nowMillis);
        }
        cache.remove(id);
    }

    void sawBot(String deviceId, String identity) {
        botsSeen.computeIfAbsent(deviceId, k -> new TreeSet<>()).add(identity);
    }

    private DeviceRegistry.@Nullable Device record(String id) {
        DeviceRegistry.Device d = registry.get(id);
        return d != null ? d : transientDevices.get(id);
    }

    private @Nullable Access access(String id) {
        Access a = cache.get(id);
        if (a != null) {
            return a;
        }
        DeviceRegistry.Device d = record(id);
        if (d == null) {
            return null;
        }
        PermissionSet device = PermissionSet.defaults(d.local()).with(d.grants());
        Map<String, PermissionSet> bots = new HashMap<>();
        for (Map.Entry<String, Map<String, Boolean>> e : d.bots().entrySet()) {
            bots.put(e.getKey(), device.with(e.getValue()));
        }
        a = new Access(d, device, bots, defaultQuotas.with(d.quotas()));
        cache.put(id, a);
        return a;
    }

    /** Returns the device-level granted set; a device that is gone has nothing. */
    PermissionSet deviceSet(String deviceId) {
        Access a = access(deviceId);
        return a == null ? new PermissionSet(Set.of()) : a.device();
    }

    /** Returns whether a session's device, with the override for its bot identity, grants {@code p}. */
    boolean permitted(Session s, Permission p) {
        Access a = access(s.deviceId);
        return a != null && a.forBot(s.identity).has(p);
    }

    /** Returns whether a device grants {@code p} at device level. */
    boolean permitted(String deviceId, Permission p) {
        Access a = access(deviceId);
        return a != null && a.device().has(p);
    }

    /** Returns the effective quotas of a device; defaults for one that is gone. */
    Quotas quotas(String deviceId) {
        Access a = access(deviceId);
        return a == null ? defaultQuotas : a.quotas();
    }

    /** Returns whether a device is known: remembered, or allowed once in this run. */
    boolean known(String deviceId) {
        return record(deviceId) != null;
    }

    /** Returns whether a device is saved in the registry. */
    boolean remembered(String deviceId) {
        return registry.get(deviceId) != null;
    }

    /** Returns whether a known device authenticated with the local token. */
    boolean local(String deviceId) {
        DeviceRegistry.Device d = record(deviceId);
        return d != null && d.local();
    }

    /**
     * Collects the first denial of a permission on a remote device for
     * the batched prompt, unless the player said never to ask. Local devices are never prompted.
     */
    void denied(String deviceId, String sessionName, Permission p) {
        Access a = access(deviceId);
        if (a == null || a.record().local() || a.record().neverAsk().contains(p.key())
                || !asked.add(deviceId + '\n' + p.key())) {
            return;
        }
        pendingOf(deviceId).items.put(p.key(), new PermissionBatch.Grant(p, sessionName));
    }

    private Pending pendingOf(String deviceId) {
        Pending p = pending.get(deviceId);
        if (p == null) {
            p = new Pending(clock.millis());
            pending.put(deviceId, p);
        }
        return p;
    }

    private static String displayName(DeviceRegistry.Device d) {
        String label = d.label();
        return label != null && !label.isBlank() ? label : d.name();
    }

    @Override
    public List<DeviceInfo> list() {
        Set<String> ids = new TreeSet<>();
        for (DeviceRegistry.Device d : registry.all()) {
            ids.add(d.id());
        }
        ids.addAll(transientDevices.keySet());
        List<DeviceInfo> out = new ArrayList<>();
        for (String id : ids) {
            DeviceInfo info = get(id);
            if (info != null) {
                out.add(info);
            }
        }
        out.sort(Comparator.comparing((DeviceInfo i) -> i.name().toLowerCase(Locale.ROOT))
                .thenComparing(DeviceInfo::id));
        return List.copyOf(out);
    }

    @Override
    public @Nullable DeviceInfo get(String deviceId) {
        Access a = access(deviceId);
        if (a == null) {
            return null;
        }
        DeviceRegistry.Device d = a.record();
        int connections = 0;
        List<String> sessions = new ArrayList<>();
        for (Connection c : table.connectionsOf(deviceId)) {
            connections++;
            for (Session s : c.sessions.values()) {
                sessions.add(s.displayName);
            }
        }
        Set<String> bots = new TreeSet<>(botsSeen.getOrDefault(deviceId, Set.of()));
        bots.addAll(d.bots().keySet());
        bots.addAll(botStore.identitiesOf(deviceId));
        Map<String, Map<Permission, Boolean>> overrides = new TreeMap<>();
        for (Map.Entry<String, Map<String, Boolean>> e : d.bots().entrySet()) {
            Map<Permission, Boolean> m = new EnumMap<>(Permission.class);
            for (Map.Entry<String, Boolean> g : e.getValue().entrySet()) {
                Permission p = Permission.byKey(g.getKey());
                if (p != null) {
                    m.put(p, g.getValue());
                }
            }
            overrides.put(e.getKey(), Map.copyOf(m));
        }
        return new DeviceInfo(d.id(), displayName(d), d.name(), d.local(), registry.get(deviceId) != null,
                d.firstSeen(), d.lastSeen(), d.lastAddress(), connections, sessions, List.copyOf(bots), a.device(),
                overrides, a.quotas());
    }

    @Override
    public boolean revoke(String deviceId) {
        boolean known = registry.remove(deviceId) | transientDevices.remove(deviceId) != null;
        cache.remove(deviceId);
        botsSeen.remove(deviceId);
        pending.remove(deviceId);
        for (String identity : botStore.identitiesOf(deviceId)) {
            botStore.unbind(identity);
        }
        Objects.requireNonNull(lifecycle).revoked(deviceId);
        return known;
    }

    @Override
    public @Nullable String botDevice(String identity) {
        return botStore.device(identity);
    }

    @Override
    public boolean forgetBot(String identity) {
        return botStore.unbind(identity);
    }

    @Override
    public void rename(String deviceId, @Nullable String label) {
        DeviceRegistry.Device d = record(deviceId);
        if (d == null) {
            return;
        }
        update(d.withLabel(label == null || label.isBlank() ? null : label.strip()));
    }

    @Override
    public void setPermission(String deviceId, Permission permission, boolean granted) {
        DeviceRegistry.Device d = record(deviceId);
        if (d == null) {
            return;
        }
        Map<String, Boolean> grants = new LinkedHashMap<>(d.grants());
        if (granted == permission.defaultFor(d.local())) {
            grants.remove(permission.key());
        } else {
            grants.put(permission.key(), granted);
        }
        List<String> neverAsk = new ArrayList<>(d.neverAsk());
        neverAsk.remove(permission.key());
        update(d.withPermissions(grants, d.bots(), neverAsk));
        if (granted) {
            asked.remove(deviceId + '\n' + permission.key());
        }
        Objects.requireNonNull(permits).permissionsChanged(deviceId);
    }

    @Override
    public void setBotPermission(String deviceId, String identity, Permission permission, @Nullable Boolean granted) {
        DeviceRegistry.Device d = record(deviceId);
        if (d == null) {
            return;
        }
        Map<String, Map<String, Boolean>> bots = new LinkedHashMap<>(d.bots());
        Map<String, Boolean> forBot = new LinkedHashMap<>(bots.getOrDefault(identity, Map.of()));
        if (granted == null) {
            forBot.remove(permission.key());
        } else {
            forBot.put(permission.key(), granted);
        }
        if (forBot.isEmpty()) {
            bots.remove(identity);
        } else {
            bots.put(identity, forBot);
        }
        update(d.withPermissions(d.grants(), bots, d.neverAsk()));
        Objects.requireNonNull(permits).permissionsChanged(deviceId);
    }

    @Override
    public void resetPermissions(String deviceId) {
        DeviceRegistry.Device d = record(deviceId);
        if (d == null) {
            return;
        }
        update(d.withPermissions(Map.of(), Map.of(), List.of()));
        asked.removeIf(k -> k.startsWith(deviceId + '\n'));
        Objects.requireNonNull(permits).permissionsChanged(deviceId);
    }

    @Override
    public void setQuota(String deviceId, Quota quota, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(quota.label() + " must not be negative");
        }
        DeviceRegistry.Device d = record(deviceId);
        if (d == null) {
            return;
        }
        Map<String, Integer> quotas = new LinkedHashMap<>(d.quotas());
        if (value == defaultQuotas.get(quota)) {
            quotas.remove(quota.key());
        } else {
            quotas.put(quota.key(), value);
        }
        update(d.withQuotas(quotas));
    }

    @Override
    public void resetQuotas(String deviceId) {
        DeviceRegistry.Device d = record(deviceId);
        if (d != null) {
            update(d.withQuotas(Map.of()));
        }
    }

    @Override
    public @Nullable PermissionBatch permissionBatch() {
        for (Map.Entry<Session, InterceptDecl> e : Objects.requireNonNull(intercepts).unansweredCancels()) {
            InterceptDecl d = e.getValue();
            String identity = Objects.requireNonNull(d.ownerIdentity());
            Pending p = pendingOf(e.getKey().deviceId);
            p.items.putIfAbsent("cancel\n" + identity, new PermissionBatch.InterceptCancel(identity, d.ownerName(),
                    d.data().input() + " on " + d.data().target().type()));
        }
        long now = clock.millis();
        for (Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Pending> e = it.next();
            String deviceId = e.getKey();
            Pending p = e.getValue();
            DeviceRegistry.Device d = record(deviceId);
            if (d == null || d.local()) {
                it.remove();
                continue;
            }
            p.items.values().removeIf(i -> switch (i) {
                case PermissionBatch.Grant g -> permitted(deviceId, g.permission());
                case PermissionBatch.InterceptCancel c -> Objects.requireNonNull(intercepts).decided(c.identity());
            });
            if (p.items.isEmpty()) {
                it.remove();
                continue;
            }
            if (now - p.firstMs >= PROMPT_COLLECT_MS) {
                return new PermissionBatch(deviceId, displayName(d), List.copyOf(p.items.values()));
            }
        }
        return null;
    }

    @Override
    public void answerPermissions(PermissionBatch batch, Collection<? extends PermissionBatch.Item> allowed) {
        Pending p = pending.get(batch.deviceId());
        if (p != null) {
            p.items.values().removeAll(batch.items());
            if (p.items.isEmpty()) {
                pending.remove(batch.deviceId());
            }
        }
        List<String> never = new ArrayList<>();
        for (PermissionBatch.Item item : batch.items()) {
            boolean allow = allowed.contains(item);
            switch (item) {
                case PermissionBatch.Grant g -> {
                    asked.add(batch.deviceId() + '\n' + g.permission().key());
                    if (allow) {
                        setPermission(batch.deviceId(), g.permission(), true);
                    } else {
                        never.add(g.permission().key());
                    }
                }
                case PermissionBatch.InterceptCancel c -> Objects.requireNonNull(intercepts).answerPermission(c.identity(),
                        allow ? ApprovalDecision.ALLOW : ApprovalDecision.DENY);
            }
        }
        DeviceRegistry.Device d = record(batch.deviceId());
        if (d == null || never.isEmpty()) {
            return;
        }
        List<String> neverAsk = new ArrayList<>(d.neverAsk());
        neverAsk.addAll(never);
        update(d.withPermissions(d.grants(), d.bots(), neverAsk));
    }

    private void update(DeviceRegistry.Device d) {
        if (registry.get(d.id()) != null) {
            registry.put(d);
        } else {
            transientDevices.put(d.id(), d);
        }
        cache.remove(d.id());
    }
}
