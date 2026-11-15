package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.decl.ScreenDecl;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import io.github.pix3lpirat3.mfbridge.core.persist.PlayerPrefs;
import io.github.pix3lpirat3.mfbridge.core.screen.ScreenControls;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectKeys;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectStore;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenClose;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenEvent;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenRow;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenTab;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/** The declared-screen state behind {@link ScreenControls}. */
@ClientThread
final class Screens implements ScreenControls {
    /** How long a bot's open request may wait for the client to open the screen, in ms. */
    static final long OPEN_REQUEST_TIMEOUT_MS = 30_000;

    private final SessionHost host;
    private final ObjectStore store;
    private final Interactions interactions;
    private final PlayerPrefs prefs;
    /** Requested screen key to request time in bridge clock ms, in arrival order. */
    private final LinkedHashMap<String, Long> requests = new LinkedHashMap<>();
    private final Set<String> open = new LinkedHashSet<>();
    /** Open screens that were opened at their owner's request rather than by the player. */
    private final Set<String> openByBot = new HashSet<>();
    private final SurfaceGate gate;

    Screens(SessionHost host, ObjectStore store, Interactions interactions, PlayerPrefs prefs, SurfaceGate gate) {
        this.host = host;
        this.store = store;
        this.interactions = interactions;
        this.prefs = prefs;
        this.gate = gate;
    }

    /** Returns the owner of a screen the player may see right now, or null. */
    private @Nullable Session reachableOwner(String screenKey) {
        StoredObject o = store.get(screenKey);
        if (o == null || !(o.state().decoded() instanceof ScreenData)) {
            return null;
        }
        try {
            return interactions.reachableOwner(o);
        } catch (BridgeException e) {
            return null;
        }
    }

    /** A bot asked to open one of its screens; it must be its own and visible to the player. */
    void openRequest(Session s, ScreenOpen m) {
        String key = m.screen();
        if (!key.startsWith(s.ownerPrefix)) {
            throw new BridgeException("not_owner", "screen " + key + " belongs to another owner");
        }
        StoredObject o = store.get(key);
        if (o == null || !(o.state().decoded() instanceof ScreenData)) {
            throw new BridgeException("invalid_message", "no core:screen at " + key);
        }
        host.requireVisible(s, o);
        if (!open.contains(key) && !requests.containsKey(key)) {
            gate.check(s.owner, host.nowMs());
            requests.put(key, host.nowMs());
        }
    }

    /** A bot closed one of its screens; nothing is sent back. */
    void closeRequest(Session s, ScreenClose m) {
        String key = m.screen();
        if (key.startsWith(s.ownerPrefix)) {
            requests.remove(key);
            open.remove(key);
            openByBot.remove(key);
        }
    }

    void tick(long now) {
        requests.values().removeIf(at -> now - at > OPEN_REQUEST_TIMEOUT_MS);
    }

    void sessionEnded(Session s) {
        requests.keySet().removeIf(k -> k.startsWith(s.ownerPrefix));
        open.removeIf(k -> k.startsWith(s.ownerPrefix));
        openByBot.removeIf(k -> k.startsWith(s.ownerPrefix));
    }

    @Override
    public @Nullable String requestedOpen() {
        for (Iterator<String> it = requests.keySet().iterator(); it.hasNext(); ) {
            String key = it.next();
            if (reachableOwner(key) != null) {
                return key;
            }
            it.remove();
        }
        return null;
    }

    @Override
    public boolean isOpen(String screenKey) {
        return open.contains(screenKey) && reachableOwner(screenKey) != null;
    }

    @Override
    public void opened(String screenKey) {
        Session s = reachableOwner(screenKey);
        if (s == null) {
            throw new IllegalArgumentException("no attached session declares screen " + screenKey);
        }
        boolean requested = requests.remove(screenKey) != null;
        if (!open.add(screenKey)) {
            return;
        }
        if (requested) {
            openByBot.add(screenKey);
        } else {
            // The player opened it, so what the owner shows next was asked for.
            gate.asked(s.owner, host.nowMs());
            host.sendReliable(s, CoreMessages.SCREEN_OPEN, new ScreenOpen(screenKey), 0, Envelope.NONE);
        }
    }

    @Override
    public boolean askedFor(String screenKey) {
        return gate.askedFor(ownerOf(screenKey), host.nowMs());
    }

    private static String ownerOf(String screenKey) {
        try {
            return ObjectKeys.parse(screenKey).owner();
        } catch (BridgeException e) {
            return "";
        }
    }

    @Override
    public void closed(String screenKey) {
        requests.remove(screenKey);
        if (!open.remove(screenKey)) {
            return;
        }
        if (openByBot.remove(screenKey)) {
            gate.dismissed(ownerOf(screenKey), host.nowMs());
        }
        Session s = liveOwner(screenKey);
        if (s != null) {
            host.sendReliable(s, CoreMessages.SCREEN_CLOSE, new ScreenClose(screenKey), 0, Envelope.NONE);
        }
    }

    @Override
    public CompletableFuture<InvocationResult> event(String screenKey, String item, @Nullable Object value) {
        StoredObject o = interactions.declaration(screenKey, ScreenData.class);
        if (o == null) {
            return failed("invalid_message", "unknown screen '" + screenKey + "'");
        }
        ScreenItem target = find((ScreenData) Objects.requireNonNull(o.state().decoded()), item);
        if (target == null || !target.enabled()) {
            return failed("invalid_message", "screen " + screenKey + " has no enabled item '" + item + "'");
        }
        String problem = valueProblem(target, value);
        if (problem != null) {
            return failed("invalid_message", problem);
        }
        Session s;
        try {
            s = interactions.reachableOwner(o);
        } catch (BridgeException e) {
            return CompletableFuture.failedFuture(e);
        }
        int id = interactions.allocate();
        gate.asked(s.owner, host.nowMs());
        CompletableFuture<InvocationResult> f = interactions.track(s, id);
        host.sendReliable(s, CoreMessages.SCREEN_EVENT, new ScreenEvent(screenKey, item, id, value), 0, Envelope.NONE);
        return f;
    }

    @Override
    public @Nullable String primaryMenu() {
        String key = prefs.primaryMenu();
        if (key == null || reachableOwner(key) == null) {
            return null;
        }
        StoredObject o = store.get(key);
        return o != null && o.state().decoded() instanceof ScreenData d && ScreenDecl.HOME_MENU.equals(d.home()) ? key : null;
    }

    @Override
    public @Nullable String primaryMenuChoice() {
        return prefs.primaryMenu();
    }

    @Override
    public void setPrimaryMenu(@Nullable String screenKey) {
        if (!Objects.equals(screenKey, prefs.primaryMenu())) {
            prefs.primaryMenu(screenKey);
            prefs.save();
        }
    }

    private static @Nullable ScreenItem find(ScreenData data, String id) {
        for (ScreenTab tab : data.tabs()) {
            for (ScreenRow row : tab.rows()) {
                for (ScreenItem it : row.items()) {
                    if (id.equals(it.id())) {
                        return it;
                    }
                }
            }
        }
        return null;
    }

    /** Buttons take no value, toggles a boolean, cycles the index of one of their options. */
    private static @Nullable String valueProblem(ScreenItem it, @Nullable Object value) {
        return switch (it.kind()) {
            case "button" -> value == null ? null : "a button click carries no value";
            case "toggle" -> value instanceof Boolean ? null : "a toggle takes a boolean";
            case "cycle" -> {
                int count = it.options() == null ? 0 : it.options().size();
                yield value instanceof Number n && n.longValue() >= 0 && n.longValue() < count ? null
                        : "a cycle takes an option index below " + count;
            }
            default -> "item '" + it.id() + "' (" + it.kind() + ") takes no input";
        };
    }

    private @Nullable Session liveOwner(String screenKey) {
        try {
            Session s = host.writer(ObjectKeys.parse(screenKey).owner());
            return s == null || s.superseded ? null : s;
        } catch (BridgeException e) {
            return null;
        }
    }

    private static CompletableFuture<InvocationResult> failed(String code, String message) {
        return CompletableFuture.failedFuture(new BridgeException(code, message));
    }
}
