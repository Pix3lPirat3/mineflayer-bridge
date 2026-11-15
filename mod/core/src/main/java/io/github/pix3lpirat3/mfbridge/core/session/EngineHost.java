package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.BridgeClock;
import io.github.pix3lpirat3.mfbridge.core.decl.DeclarationSnapshot;
import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import io.github.pix3lpirat3.mfbridge.core.remote.Quotas;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectTraits;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/** The {@link SessionHost} of the running engine: each call goes to the part that owns it. */
@ClientThread
final class EngineHost implements SessionHost {
    private final BridgeClock clock;
    private final SessionTable table;
    private final Outbox outbox;
    private final PlayerView view;
    private final Permits permits;
    private @Nullable DeclarationPublisher declarations;

    EngineHost(BridgeClock clock, SessionTable table, Outbox outbox, PlayerView view, Permits permits) {
        this.clock = clock;
        this.table = table;
        this.outbox = outbox;
        this.view = view;
        this.permits = permits;
    }

    /** Sets where declarations come from; the publisher is built after the services it reads. */
    void declarations(DeclarationPublisher publisher) {
        declarations = publisher;
    }

    @Override
    public long nowMs() {
        return clock.millis();
    }

    @Override
    public long wallMs() {
        return clock.currentTimeMillis();
    }

    @Override
    public <T> void sendReliable(Session s, MessageType<T> type, T msg, int flags, long req) {
        outbox.sendReliable(s, type, msg, flags, req);
    }

    @Override
    public <T> void sendLatest(Session s, MessageType<T> type, T msg, @Nullable String subject) {
        outbox.sendLatest(s, type, msg, subject);
    }

    @Override
    public <T> void respond(Session s, MessageType<T> type, T msg, long req) {
        outbox.respond(s, type, msg, req);
    }

    @Override
    public void respondError(Session s, String code, String message, int relatesTo, long req) {
        outbox.respondError(s, code, message, relatesTo, req);
    }

    @Override
    public void respondError(Session s, BridgeException error, int relatesTo, long req) {
        outbox.sendSessionError(s, error, relatesTo, req);
    }

    @Override
    public <Q, R> CompletableFuture<R> request(Session w, MessageType<Q> type, Q msg, MessageType<R> response) {
        return outbox.request(w, type, msg, response);
    }

    @Override
    public @Nullable Session writer(String owner) {
        return table.writer(owner);
    }

    @Override
    public List<Session> attachedBots() {
        return view.attachedBots();
    }

    @Override
    public List<Session> liveControllers() {
        return view.liveControllers();
    }

    @Override
    public boolean attachedNow(Session s) {
        return view.attachedNow(s);
    }

    @Override
    public boolean worldMatched(Session s) {
        return view.worldMatched(s);
    }

    @Override
    public boolean worldVisible(@Nullable WorldKey w) {
        return view.worldVisible(w);
    }

    @Override
    public void requireVisible(Session s, StoredObject o) {
        view.requireVisible(s, o);
    }

    @Override
    public boolean focusAllows(String owner, @Nullable ObjectTraits traits) {
        return view.focusAllows(owner, traits);
    }

    @Override
    public boolean permitted(Session s, Permission p) {
        return permits.permitted(s, p);
    }

    @Override
    public void requirePermission(Session s, Permission p) {
        permits.requirePermission(s, p);
    }

    @Override
    public void reportMissing(Session s, Permission p) {
        permits.reportMissing(s, p);
    }

    @Override
    public boolean localDevice(String deviceId) {
        return permits.localDevice(deviceId);
    }

    @Override
    public Quotas quotas(Session s) {
        return permits.quotas(s);
    }

    @Override
    public DeclarationSnapshot declarations() {
        DeclarationPublisher p = declarations;
        return p == null ? DeclarationSnapshot.EMPTY : p.snapshot();
    }
}
