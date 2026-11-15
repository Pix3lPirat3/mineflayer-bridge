package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.event.EventBus;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.container.ContainerCloseRequested;
import io.github.pix3lpirat3.mfbridge.core.container.ContainerControls;
import io.github.pix3lpirat3.mfbridge.core.container.ContainerOpenRequested;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectKeys;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectStore;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerAck;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerClose;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerStorage;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerViewData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotClick;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotEntry;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/** The container view state behind {@link ContainerControls}. */
@ClientThread
final class Containers implements ContainerControls {
    private static final int OUTSIDE = -999;
    /** How long a bot's open request may wait for the client to open the view, in ms. */
    static final long OPEN_REQUEST_TIMEOUT_MS = 30_000;

    private final SessionHost host;
    private final ObjectStore store;
    private final EventBus events;
    /** Requested view key to request time in bridge clock ms, in arrival order. */
    private final LinkedHashMap<String, Long> requests = new LinkedHashMap<>();
    private final PendingAnswers<ContainerAck> pending = new PendingAnswers<>(ACK_TIMEOUT_MS);
    private final SurfaceGate gate;
    private @Nullable String open;
    /** Whether the open view was opened at its owner's request rather than by the player. */
    private boolean openByBot;

    Containers(SessionHost host, ObjectStore store, EventBus events, SurfaceGate gate) {
        this.host = host;
        this.store = store;
        this.events = events;
        this.gate = gate;
    }

    /** Returns the owner of a view the player may see right now, or null. */
    private @Nullable Session reachableOwner(String viewKey) {
        StoredObject o = store.get(viewKey);
        if (o == null || !(o.state().decoded() instanceof ContainerViewData)) {
            return null;
        }
        long expires = o.state().expiresAtMs();
        if (expires >= 0 && expires <= host.nowMs()) {
            return null;
        }
        Session s = host.writer(o.parts().owner());
        if (s == null || s.superseded || !host.attachedNow(s)) {
            return null;
        }
        if (o.state().scope() == Scope.WORLD && !host.worldVisible(o.state().world())) {
            return null;
        }
        return s;
    }

    private @Nullable Session liveOwner(String viewKey) {
        try {
            Session s = host.writer(ObjectKeys.parse(viewKey).owner());
            return s == null || s.superseded ? null : s;
        } catch (BridgeException e) {
            return null;
        }
    }

    /**
     * A bot asked for one of its views to open. {@code ui.container} is granted by default, but the session
     * must be attached and the view its own.
     */
    void openRequest(Session s, ContainerOpen m) {
        String key = m.view();
        if (!key.startsWith(s.ownerPrefix)) {
            throw new BridgeException("not_owner", "container view " + key + " belongs to another owner");
        }
        StoredObject o = store.get(key);
        if (o == null || !(o.state().decoded() instanceof ContainerViewData)) {
            throw new BridgeException("invalid_message", "no core:container-view at " + key);
        }
        host.requireVisible(s, o);
        if (key.equals(open) || requests.containsKey(key)) {
            return;
        }
        gate.check(s.owner, host.nowMs());
        requests.put(key, host.nowMs());
        if (events.hasListeners(ContainerOpenRequested.TYPE)) {
            events.post(ContainerOpenRequested.TYPE, new ContainerOpenRequested(key));
        }
    }

    /** A bot closed one of its views; nothing is sent back. */
    void closeRequest(Session s, ContainerClose m) {
        String key = m.view();
        if (!key.startsWith(s.ownerPrefix)) {
            return;
        }
        boolean was = requests.remove(key) != null;
        if (key.equals(open)) {
            open = null;
            was = true;
        }
        if (was) {
            announceClose(key);
        }
    }

    private void announceClose(String key) {
        if (events.hasListeners(ContainerCloseRequested.TYPE)) {
            events.post(ContainerCloseRequested.TYPE, new ContainerCloseRequested(key));
        }
    }

    /** Completes the matching input; acks from another session or for another view are ignored. */
    void ack(Session s, ContainerAck a) {
        pending.complete(a.input(), s, a.view(), a);
    }

    @Override
    public @Nullable String requestedOpen() {
        Iterator<String> it = requests.keySet().iterator();
        return it.hasNext() ? it.next() : null;
    }

    @Override
    public @Nullable String open() {
        return open;
    }

    @Override
    public void opened(String viewKey) {
        Session s = reachableOwner(viewKey);
        if (s == null) {
            throw new IllegalArgumentException("no attached bot declares container view " + viewKey);
        }
        String previous = open;
        if (viewKey.equals(previous)) {
            return;
        }
        if (previous != null) {
            close(previous, false);
        }
        boolean requested = requests.remove(viewKey) != null;
        open = viewKey;
        openByBot = requested;
        if (!requested) {
            // The player opened it, so what the owner shows next was asked for.
            gate.asked(s.owner, host.nowMs());
            host.sendReliable(s, CoreMessages.CONTAINER_OPEN, new ContainerOpen(viewKey), 0, Envelope.NONE);
        }
    }

    @Override
    public void closed(String viewKey) {
        if (viewKey.equals(open) && openByBot) {
            gate.dismissed(ownerOf(viewKey), host.nowMs());
        }
        close(viewKey, false);
    }

    @Override
    public boolean askedFor(String viewKey) {
        return gate.askedFor(ownerOf(viewKey), host.nowMs());
    }

    private static String ownerOf(String viewKey) {
        try {
            return ObjectKeys.parse(viewKey).owner();
        } catch (BridgeException e) {
            return "";
        }
    }

    /** Forgets a view that closed and tells its live owner; {@code lost} when the player did not close it. */
    private void close(String viewKey, boolean lost) {
        boolean was = requests.remove(viewKey) != null;
        if (viewKey.equals(open)) {
            open = null;
            was = true;
        }
        if (!was) {
            return;
        }
        Session s = liveOwner(viewKey);
        if (s != null) {
            host.sendReliable(s, CoreMessages.CONTAINER_CLOSE, new ContainerClose(viewKey, lost), 0, Envelope.NONE);
        }
    }

    @Override
    public CompletableFuture<ContainerAck> input(String viewKey, int slot, SlotClick click, @Nullable Integer hotbar,
            @Nullable SlotItem moved, @Nullable Integer fromSlot) {
        if (!viewKey.equals(open)) {
            return failed("invalid_message", "container view " + viewKey + " is not open");
        }
        if ((click == SlotClick.NUMBER) != (hotbar != null) || (hotbar != null && (hotbar < 0 || hotbar > 8))) {
            return failed("invalid_message", "a number click needs a hotbar slot 0-8, other clicks none");
        }
        if (slot < 0 && slot != OUTSIDE) {
            return failed("invalid_message", "slot " + slot + " is not in the view");
        }
        if ((moved == null) != (fromSlot == null) || (fromSlot != null && fromSlot < -1)) {
            return failed("invalid_message", "moved and fromSlot go together; fromSlot is a slot or -1 for the cursor");
        }
        Session s = liveOwner(viewKey);
        if (s == null) {
            return failed("disconnected", "no session owns " + viewKey);
        }
        if (!host.attachedNow(s)) {
            return failed("not_attached", "the player has not attached " + s.displayName);
        }
        int id = pending.allocate();
        gate.asked(s.owner, host.nowMs());
        CompletableFuture<ContainerAck> f = pending.track(s, id, viewKey, host.nowMs());
        host.sendReliable(s, CoreMessages.CONTAINER_INPUT, new ContainerInput(viewKey, slot, click, hotbar, id, moved, fromSlot), 0,
                Envelope.NONE);
        return f;
    }

    @Override
    public void storageChanged(String viewKey, List<SlotEntry> slots) {
        if (!viewKey.equals(open)) {
            return;
        }
        Session s = liveOwner(viewKey);
        if (s != null && host.attachedNow(s)) {
            host.sendReliable(s, CoreMessages.CONTAINER_STORAGE, new ContainerStorage(viewKey, List.copyOf(slots)), 0,
                    Envelope.NONE);
        }
    }

    private static <T> CompletableFuture<T> failed(String code, String message) {
        return CompletableFuture.failedFuture(new BridgeException(code, message));
    }

    /**
     * Times out acks and drops requests and the open view once they are no longer reachable: the
     * object was removed or expired, the owner was detached, left the player's world or ended, or a
     * request did not open within {@link #OPEN_REQUEST_TIMEOUT_MS} (the client could not show it, so
     * a stale request must not pop up later). A still-live owner is told with {@code container.close}.
     */
    void tick(long now) {
        pending.expire(now);
        String o = open;
        if (o != null && reachableOwner(o) == null) {
            close(o, true);
            announceClose(o);
        }
        if (!requests.isEmpty()) {
            for (Map.Entry<String, Long> r : List.copyOf(requests.entrySet())) {
                String key = r.getKey();
                if (now - r.getValue() >= OPEN_REQUEST_TIMEOUT_MS || reachableOwner(key) == null) {
                    close(key, true);
                    announceClose(key);
                }
            }
        }
    }

    /** Fails the inputs of a session that ended or was superseded; its views lapse on the next tick. */
    void sessionEnded(Session s) {
        pending.sessionEnded(s);
    }
}
