package io.github.pix3lpirat3.mfbridge.core.world;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.Presence;
import io.github.pix3lpirat3.mfbridge.core.PresenceView;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** Presence per bot identity. Records outlive sessions. */
@ClientThread
public final class PresenceRegistry implements PresenceView {
    private static final class Entry {
        String displayName;
        int sessions;
        @Nullable WorldKey lastWorld;
        long lastSeen;
        @Nullable Object status;

        Entry(String displayName) {
            this.displayName = displayName;
        }
    }

    private final Map<String, Entry> entries = new TreeMap<>();

    /** Records a new or resumed session for an identity. */
    public void sessionStarted(String identity, String displayName, long nowMillis) {
        Entry e = entries.computeIfAbsent(identity, k -> new Entry(displayName));
        e.displayName = displayName;
        e.sessions++;
        e.lastSeen = nowMillis;
    }

    /** Records that one session of an identity is no longer connected. */
    public void sessionStopped(String identity, long nowMillis) {
        Entry e = entries.get(identity);
        if (e != null) {
            e.sessions = Math.max(0, e.sessions - 1);
            e.lastSeen = nowMillis;
        }
    }

    /** Records inbound traffic. */
    public void seen(String identity, long nowMillis) {
        Entry e = entries.get(identity);
        if (e != null) {
            e.lastSeen = nowMillis;
        }
    }

    /** Records a world report; null worlds keep the last known world. */
    public void world(String identity, @Nullable WorldKey world) {
        Entry e = entries.get(identity);
        if (e != null && world != null) {
            e.lastWorld = world;
        }
    }

    /** Records a {@code presence.status} value. */
    public void status(String identity, @Nullable Object status) {
        Entry e = entries.get(identity);
        if (e != null) {
            e.status = status;
        }
    }

    @Override
    public List<Presence> all() {
        List<Presence> out = new ArrayList<>(entries.size());
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            out.add(view(e.getKey(), e.getValue()));
        }
        return List.copyOf(out);
    }

    @Override
    public @Nullable Presence get(String identity) {
        Entry e = entries.get(identity);
        return e == null ? null : view(identity, e);
    }

    @Override
    public boolean forget(String identity) {
        Entry e = entries.get(identity);
        if (e == null || e.sessions > 0) {
            return false;
        }
        entries.remove(identity);
        return true;
    }

    private static Presence view(String identity, Entry e) {
        return new Presence(identity, e.displayName, e.sessions > 0, e.lastWorld, e.lastSeen, e.status);
    }
}
