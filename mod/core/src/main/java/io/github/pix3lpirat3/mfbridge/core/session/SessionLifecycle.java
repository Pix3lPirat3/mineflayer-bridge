package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.event.BridgeEvents;
import io.github.pix3lpirat3.mfbridge.api.event.EventBus;
import io.github.pix3lpirat3.mfbridge.api.event.EventType;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.BridgeClock;
import io.github.pix3lpirat3.mfbridge.core.BridgeConfig;
import io.github.pix3lpirat3.mfbridge.core.persist.BotSettingsStore;
import io.github.pix3lpirat3.mfbridge.core.remote.InstanceIdentity;
import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectStore;
import io.github.pix3lpirat3.mfbridge.core.util.Encoding;
import io.github.pix3lpirat3.mfbridge.core.world.PresenceRegistry;
import io.github.pix3lpirat3.mfbridge.core.world.ServerKeys;
import io.github.pix3lpirat3.mfbridge.protocol.core.BotIdentity;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpened;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionResume;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionResumed;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldReport;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Opens, resumes, supersedes and ends sessions, and moves them between connections when a
 * connection closes.
 */
@ClientThread
final class SessionLifecycle {
    private final BridgeConfig config;
    private final BridgeClock clock;
    private final EventBus events;
    private final ObjectStore store;
    private final PresenceRegistry presence;
    private final InputQueue input;
    private final Interactions interactions;
    private final Containers containers;
    private final Screens screens;
    private final BotSettings botSettings;
    private final OwnerQuotas ownerQuotas;
    private final BotSettingsStore botStore;
    private final Devices access;
    private final Streams streams;
    private final Recorder recorder;
    private final SessionTable table;
    private final Outbox outbox;
    private final PlayerView view;
    private final WorldStores stores;
    private final SharedObjects shared;

    SessionLifecycle(BridgeConfig config, BridgeClock clock, EventBus events, ObjectStore store,
            PresenceRegistry presence, InputQueue input, Interactions interactions, Containers containers,
            Screens screens, BotSettings botSettings, OwnerQuotas ownerQuotas, BotSettingsStore botStore,
            Devices access, Streams streams, Recorder recorder, SessionTable table, Outbox outbox, PlayerView view,
            WorldStores stores, SharedObjects shared) {
        this.config = config;
        this.clock = clock;
        this.events = events;
        this.store = store;
        this.presence = presence;
        this.input = input;
        this.interactions = interactions;
        this.containers = containers;
        this.screens = screens;
        this.botSettings = botSettings;
        this.ownerQuotas = ownerQuotas;
        this.botStore = botStore;
        this.access = access;
        this.streams = streams;
        this.recorder = recorder;
        this.table = table;
        this.outbox = outbox;
        this.view = view;
        this.stores = stores;
        this.shared = shared;
    }

    void openSession(Connection c, Envelope env, SessionOpen o, long now) {
        long req = Dispatcher.needReq(env);
        if (env.session() != 0) {
            throw new BridgeException("invalid_message", "session.open must be sent on session 0");
        }
        String identity;
        String owner;
        if (o.kind() == SessionKind.BOT) {
            BotIdentity id = o.identity();
            if (id == null || id.username().isEmpty()) {
                throw new BridgeException("invalid_message", "bot session without identity");
            }
            identity = identityKey(id);
            owner = "bot:" + identity;
        } else {
            if (o.name().isEmpty() || o.name().indexOf('/') >= 0) {
                throw new BridgeException("invalid_message", "controller name must be non-empty without '/'");
            }
            String device = Objects.requireNonNull(c.deviceId);
            if (!access.permitted(device, Permission.CONTROLLER)) {
                access.denied(device, o.name(), Permission.CONTROLLER);
                throw Permits.denied(Permission.CONTROLLER);
            }
            identity = null;
            owner = "ctl:" + Objects.requireNonNull(c.deviceId) + '/' + o.name();
        }
        if (identity != null && identity.indexOf('/') >= 0) {
            throw new BridgeException("invalid_message", "bot identity must not contain '/'");
        }
        String device = Objects.requireNonNull(c.deviceId);
        if (table.sessionsOf(device) >= config.maxSessionsPerDevice()) {
            throw new BridgeException("quota_exceeded", "this device already holds " + config.maxSessionsPerDevice()
                    + " sessions");
        }
        if (identity != null) {
            if (!c.replay) {
                claimIdentity(identity, device);
            }
            access.sawBot(device, identity);
        }
        Session writer = table.writers.get(owner + '/');
        if (writer != null && !writer.deviceId.equals(device)) {
            // Objects are counted against the device that wrote them, so they do not change hands.
            store.removePrefix(writer.ownerPrefix);
        }
        ownerQuotas.bind(owner, device);
        byte[] token = new byte[16];
        Handshake.RANDOM.nextBytes(token);
        Session s = new Session(o.kind(), o.name(), identity, owner, Objects.requireNonNull(c.deviceId), token,
                Encoding.base64Url(token), table.nextEpoch++, config.outboundLogCapBytes());
        s.snapshotGen = table.nextGen++;
        s.opened = o;
        attachToConnection(c, s);
        table.byToken.put(s.tokenKey, s);
        Session previous = table.writers.put(s.ownerPrefix, s);
        if (previous != null && previous != s) {
            supersede(previous);
        }
        outbox.sendControl(c, 0, null, CoreMessages.SESSION_OPENED, new SessionOpened(s.id, token), Envelope.RESPONSE, req);
        view.visibilityChanged();
        post(BridgeEvents.SESSION_OPENED, s, "");
    }

    /**
     * A bot identity belongs to the device that first opened a session with it, so another device
     * cannot take the bot over. The claim is saved; it lapses when that device is revoked or
     * forgotten, or the player forgets the bot.
     *
     * @throws BridgeException {@code identity_taken}
     */
    private void claimIdentity(String identity, String device) {
        String holder = botStore.device(identity);
        if (device.equals(holder)) {
            return;
        }
        if (holder != null && access.known(holder)) {
            throw new BridgeException("identity_taken", "this bot belongs to another device; the player can forget it"
                    + " in the device list");
        }
        // Allow-once devices are gone after a restart, so their claims are not saved.
        if (access.remembered(device)) {
            botStore.bind(identity, device);
        } else {
            botStore.bindForRun(identity, device);
        }
    }

    /** UUID when given, otherwise {@code lowercase(username)@server}. */
    static String identityKey(BotIdentity id) {
        String uuid = id.uuid();
        if (uuid != null && !uuid.isEmpty()) {
            return uuid;
        }
        String server = id.server();
        return id.username().toLowerCase(Locale.ROOT) + '@' + (server == null || server.isEmpty() ? "unknown" : server);
    }

    private void attachToConnection(Connection c, Session s) {
        s.conn = c;
        s.lastRegistry = c.registry;
        s.id = c.nextSessionId++;
        s.detachedAtMs = -1;
        c.sessions.put(s.id, s);
        if (s.identity != null) {
            presence.sessionStarted(s.identity, s.displayName, clock.currentTimeMillis());
        }
        s.interestDirty = true;
        s.lastInterest = null;
        s.lastContext = null;
        s.lastSample = null;
    }

    private void detachFromConnection(Session s) {
        Connection c = s.conn;
        if (c == null) {
            return;
        }
        c.sessions.remove(s.id);
        s.conn = null;
        if (s.identity != null) {
            presence.sessionStopped(s.identity, clock.currentTimeMillis());
        }
    }

    /** The newest session becomes the writer; older ones are told and go read-only. */
    private void supersede(Session old) {
        old.superseded = true;
        release(old, "superseded");
        if (old.conn != null) {
            outbox.sendSessionError(old, ObjectWrites.superseded(), CoreMessages.SESSION_OPEN.id());
        } else {
            old.ended = true;
            table.detached.remove(old);
        }
    }

    void resumeSession(Connection c, Envelope env, SessionResume r, long now) {
        long req = Dispatcher.needReq(env);
        Session s = table.byToken.get(Encoding.base64Url(r.resumeToken()));
        if (s == null || s.ended || s.superseded || !s.deviceId.equals(c.deviceId)) {
            // The error catalogue has no resume-specific code; disconnected tells the plugin to open anew.
            throw new BridgeException("disconnected", "unknown or expired resume token");
        }
        if (!s.log.canReplayFrom(r.lastApplied())) {
            endSession(s, "resume_failed");
            throw new BridgeException("disconnected", "outbound log overflowed; open a new session");
        }
        if (s.conn == c) {
            throw new BridgeException("invalid_message", "session is already active on this connection");
        }
        if (s.conn != null) {
            detachFromConnection(s);
        }
        table.detached.remove(s);
        attachToConnection(c, s);
        long maxReq = s.detachedReq;
        for (long pendingReq : s.pending.keySet()) {
            maxReq = Math.max(maxReq, pendingReq);
        }
        c.nextReq = Math.max(c.nextReq, maxReq + 1);
        s.log.trim(r.lastApplied());
        outbox.sendControl(c, 0, null, CoreMessages.SESSION_RESUMED, new SessionResumed(s.id, s.lastAppliedIn),
                Envelope.RESPONSE, req);
        // Replay above the peer's lastApplied with the original seq numbers.
        for (OutFrame f : s.log.above(r.lastApplied())) {
            Outbox.queue(c, f.withSessionId(s.id));
        }
        if (s.lastAppliedIn > 0) {
            s.ackDueSinceMs = now;
        }
        // Latest messages are not logged, so the mod re-sends its current ones.
        botSettings.resumed(s);
        interactions.resumed(s);
        recorder.sessionResumed(c, s);
        post(BridgeEvents.SESSION_RESUMED, s, "");
    }

    void endSession(Session s, String reason) {
        if (s.ended) {
            return;
        }
        s.ended = true;
        detachFromConnection(s);
        table.detached.remove(s);
        release(s, "disconnected");
        if (table.writers.get(s.ownerPrefix) == s) {
            table.writers.remove(s.ownerPrefix);
            store.removePrefix(s.ownerPrefix);
            ownerQuotas.unbind(s.owner);
        }
        view.visibilityChanged();
        post(BridgeEvents.SESSION_ENDED, s, reason);
    }

    /** Drops the session's token, pending requests and per-session state in the services. */
    private void release(Session s, String pendingCode) {
        table.byToken.remove(s.tokenKey);
        rejectPending(s, pendingCode);
        ObjectWrites.discardTx(s);
        input.dropSession(s);
        interactions.sessionEnded(s);
        containers.sessionEnded(s);
        screens.sessionEnded(s);
        streams.sessionEnded(s);
    }

    private void rejectPending(Session s, String code) {
        if (s.pending.isEmpty()) {
            return;
        }
        List<PendingRequest<?>> all = new ArrayList<>(s.pending.values());
        s.pending.clear();
        for (PendingRequest<?> p : all) {
            p.future().completeExceptionally(new BridgeException(code, "request ended: " + code));
        }
    }

    private void post(EventType<BridgeEvents.SessionEvent> type, Session s, String reason) {
        if (events.hasListeners(type)) {
            events.post(type, new BridgeEvents.SessionEvent(s.ownerPrefix, s.displayName,
                    s.kind == SessionKind.CONTROLLER, reason));
        }
    }

    void worldReport(Session s, WorldReport w) {
        WorldKey world = w.world();
        if (Objects.equals(s.world, world)) {
            s.position = w.position();
            return;
        }
        WorldStores.checkWorld(world);
        if (world != null && !stores.ensureLoading(ServerKeys.normalize(world.server()), false)) {
            throw new BridgeException("quota_exceeded", "bots named more than " + WorldStores.MAX_BOT_STORES + " servers");
        }
        s.position = w.position();
        s.world = world;
        if (s.identity != null) {
            presence.world(s.identity, world);
        }
        s.interestDirty = true;
        view.visibilityChanged();
        shared.resyncShared(s);
    }

    /** Fails the requests to sessions that got no answer in time. */
    void expireRequests(long now) {
        for (Connection c : table.connections) {
            for (Session s : c.sessions.values()) {
                expireRequests(s, now);
            }
        }
        for (Session s : table.detached) {
            expireRequests(s, now);
        }
    }

    /** Ends the detached sessions whose resume window has passed. */
    void expireDetached(long now) {
        for (int i = table.detached.size() - 1; i >= 0; i--) {
            Session s = table.detached.get(i);
            if (now - s.detachedAtMs >= config.resumeWindowMs()) {
                endSession(s, "expired");
            }
        }
    }

    private void expireRequests(Session s, long now) {
        if (s.pending.isEmpty()) {
            return;
        }
        Iterator<PendingRequest<?>> it = s.pending.values().iterator();
        while (it.hasNext()) {
            PendingRequest<?> p = it.next();
            if (now >= p.deadlineMs()) {
                it.remove();
                p.future().completeExceptionally(new BridgeException("timeout", "no response within the request timeout"));
            }
        }
    }

    /** Drops closed connections; their sessions wait detached for a resume, or end when superseded. */
    void reap(long now) {
        for (int i = table.connections.size() - 1; i >= 0; i--) {
            Connection c = table.connections.get(i);
            if (!c.net.isClosed() || c.net.queued() > 0) {
                continue;
            }
            table.connections.remove(i);
            table.byNet.remove(c.net);
            recorder.connectionClosed(c);
            for (Session s : new ArrayList<>(c.sessions.values())) {
                if (s.superseded) {
                    endSession(s, "superseded");
                } else {
                    detachFromConnection(s);
                    s.detachedAtMs = now;
                    table.detached.add(s);
                }
            }
        }
    }

    /** Closes the device's connections and ends its sessions. */
    void revoked(String deviceId) {
        for (Connection c : table.connections) {
            if (deviceId.equals(c.deviceId)) {
                outbox.goodbye(c, "revoked", "this device was revoked by the player", false);
            }
        }
        for (Session s : table.liveSessions()) {
            if (s.deviceId.equals(deviceId)) {
                endSession(s, "revoked");
            }
        }
    }

    /** Returns the position an owner's bot last reported with {@code world.report}, or null. */
    @Nullable Vec3d reportedPosition(String owner) {
        Session w = table.writers.get(owner.endsWith("/") ? owner : owner + '/');
        return w == null ? null : w.position;
    }
}
