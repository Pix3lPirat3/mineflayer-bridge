package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import io.github.pix3lpirat3.mfbridge.core.remote.Quotas;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectStore;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.Permissions;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Permission checks for sessions: what a missing permission answers, and what happens to a
 * device's objects when the player changes its permissions.
 */
@ClientThread
final class Permits {
    /** The permission each core kind needs; kinds without an entry need none. */
    static final Map<KindType<?>, Permission> KIND_PERMISSIONS = kindPermissions();

    private final ObjectStore store;
    private final Devices access;
    private final SessionTable table;
    private final Outbox outbox;

    Permits(ObjectStore store, Devices access, SessionTable table, Outbox outbox) {
        this.store = store;
        this.access = access;
        this.table = table;
        this.outbox = outbox;
    }

    private static Map<KindType<?>, Permission> kindPermissions() {
        Map<KindType<?>, Permission> m = new IdentityHashMap<>();
        for (KindType<?> k : List.of(CoreKinds.BOX, CoreKinds.LINE, CoreKinds.PATH, CoreKinds.LABEL,
                CoreKinds.ENTITY_TAG, CoreKinds.WAYPOINT, CoreKinds.GHOST_BLOCKS, CoreKinds.HEATMAP,
                CoreKinds.BLOCK_HIGHLIGHT, CoreKinds.ZONE, CoreKinds.POINT, CoreKinds.ROUTE)) {
            m.put(k, Permission.RENDER_WORLD);
        }
        for (KindType<?> k : List.of(CoreKinds.HUD_PANEL, CoreKinds.HUD_TEXT, CoreKinds.HUD_KV, CoreKinds.HUD_BAR,
                CoreKinds.HUD_LIST, CoreKinds.HUD_BADGE, CoreKinds.HUD_SPARKLINE, CoreKinds.HUD_ITEM)) {
            m.put(k, Permission.RENDER_HUD);
        }
        for (KindType<?> k : List.of(CoreKinds.DISPLAY_TEXT, CoreKinds.DISPLAY_ITEM, CoreKinds.DISPLAY_BLOCK,
                CoreKinds.DISPLAY_ENTITY, CoreKinds.GROUP)) {
            m.put(k, Permission.RENDER_DISPLAY);
        }
        m.put(CoreKinds.COMMAND, Permission.COMMANDS);
        m.put(CoreKinds.ACTION, Permission.COMMANDS);
        m.put(CoreKinds.KEYBIND, Permission.INPUT_KEYBIND);
        m.put(CoreKinds.TOOL, Permission.INPUT_TOOL);
        m.put(CoreKinds.INTERCEPT, Permission.INPUT_INTERCEPT);
        m.put(CoreKinds.SETTINGS, Permission.SETTINGS);
        m.put(CoreKinds.CONTAINER_VIEW, Permission.UI_CONTAINER);
        m.put(CoreKinds.SCREEN, Permission.UI_CONTAINER);
        m.put(CoreKinds.PARTICLE_EMITTER, Permission.EFFECTS_PARTICLES);
        m.put(CoreKinds.SOUND_EMITTER, Permission.EFFECTS_SOUND);
        m.put(CoreKinds.BOSSBAR, Permission.EFFECTS_TITLE);
        return m;
    }

    /** Returns the effective quotas of a device. */
    Quotas quotasOf(String deviceId) {
        return access.quotas(deviceId);
    }

    /**
     * For a latest-wins message that is not a request, a missing permission queues the
     * one-time prompt and reports {@code permission_denied} once per session and permission,
     * without failing the message.
     */
    void reportMissing(Session s, Permission p) {
        if (access.permitted(s, p)) {
            return;
        }
        access.denied(s.deviceId, s.displayName, p);
        if (s.deniedReported.add(p)) {
            outbox.sendSessionError(s, denied(p), CoreMessages.SAMPLE_REQUEST.id());
        }
    }

    /** Returns whether a session holds a permission (its device, with the override for its bot). */
    boolean permitted(Session s, Permission p) {
        return access.permitted(s, p);
    }

    /** Returns whether a device authenticated with the local token, so it holds every permission by default. */
    boolean localDevice(String deviceId) {
        return access.local(deviceId);
    }

    /** Returns the effective quotas of a session's device. */
    Quotas quotas(Session s) {
        return access.quotas(s.deviceId);
    }

    /** The refusal for a missing permission; its details name the permission, so a bot can tell which one. */
    static BridgeException denied(Permission p) {
        return new BridgeException("permission_denied", p.key() + " is not granted to this device", false,
                Map.of("permission", p.key()));
    }

    /**
     * Rejects a request with {@code permission_denied} and queues the
     * one-time prompt.
     *
     * @throws BridgeException {@code permission_denied} when the permission is missing
     */
    void requirePermission(Session s, Permission p) {
        if (!access.permitted(s, p)) {
            access.denied(s.deviceId, s.displayName, p);
            throw denied(p);
        }
    }

    /**
     * For messages that are not requests: returns true when the permission is held; otherwise queues
     * the prompt and reports {@code permission_denied} once per session and permission, so a
     * snapshot of thousands of objects does not answer with thousands of errors.
     */
    boolean allowedOrReport(Session s, Permission p) {
        if (access.permitted(s, p)) {
            return true;
        }
        access.denied(s.deviceId, s.displayName, p);
        if (s.deniedReported.add(p)) {
            throw denied(p);
        }
        return false;
    }

    /**
     * Applies a permission change of a device: objects whose kind is no longer permitted are removed,
     * a shared subscription without {@code shared.read} is dropped, and every open connection gets
     * the new {@code permissions} set.
     */
    void permissionsChanged(String deviceId) {
        for (Session s : table.liveSessions()) {
            if (!s.deviceId.equals(deviceId)) {
                continue;
            }
            s.deniedReported.clear();
            if (!access.permitted(s, Permission.SHARED_READ)) {
                s.subscription = null;
            }
            if (table.writers.get(s.ownerPrefix) != s) {
                continue;
            }
            for (StoredObject o : store.under(s.ownerPrefix)) {
                Permission p = KIND_PERMISSIONS.get(o.state().kind());
                if (p != null && !access.permitted(s, p)) {
                    store.remove(o.key());
                }
            }
        }
        Permissions msg = new Permissions(access.deviceSet(deviceId).keys());
        for (Connection c : table.connectionsOf(deviceId)) {
            outbox.sendControl(c, 0, null, CoreMessages.PERMISSIONS, msg, 0, Envelope.NONE);
        }
    }
}
