package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.core.store.ObjectStore;
import io.github.pix3lpirat3.mfbridge.core.BridgeClock;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.diag.BridgeMetrics;
import io.github.pix3lpirat3.mfbridge.core.diag.ConnectionInfo;
import io.github.pix3lpirat3.mfbridge.core.diag.InspectorControls;
import io.github.pix3lpirat3.mfbridge.core.diag.ObjectInfo;
import io.github.pix3lpirat3.mfbridge.core.diag.SessionInfo;
import io.github.pix3lpirat3.mfbridge.core.diag.TrafficLog;
import io.github.pix3lpirat3.mfbridge.core.remote.Quota;
import io.github.pix3lpirat3.mfbridge.core.remote.Quotas;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectKeys;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.protocol.core.ExtensionRef;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpen;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** The inspector's views of the engine, built on demand. */
@ClientThread
final class Inspector implements InspectorControls {
    /** A rate window older than this means the connection has been silent. */
    private static final long RATE_STALE_MS = 2_000;

    private final BridgeClock clock;
    private final SessionTable table;
    private final Devices access;
    private final PlayerView view;
    private final ObjectStore store;
    private final RenderPublisher render;
    private final TrafficLog traffic;
    private final BridgeMetrics metrics = new BridgeMetrics();

    Inspector(BridgeClock clock, SessionTable table, Devices access, PlayerView view, ObjectStore store,
            RenderPublisher render, TrafficLog traffic) {
        this.traffic = traffic;
        this.clock = clock;
        this.table = table;
        this.access = access;
        this.view = view;
        this.store = store;
        this.render = render;
    }

    @Override
    public List<ConnectionInfo> connections() {
        long now = clock.millis();
        List<ConnectionInfo> out = new ArrayList<>();
        for (Connection c : table.connections) {
            boolean fresh = now - c.rateWindowStartMs < RATE_STALE_MS;
            String device = c.deviceId;
            Quotas q = device == null ? null : access.quotas(device);
            out.add(new ConnectionInfo(c.net.id(), c.deviceName(), device, c.net.remoteAddress(), c.net.secure(),
                    c.state.name().toLowerCase(Locale.ROOT), c.rttMs, c.bytesIn, c.bytesOut, c.framesIn, c.framesOut,
                    fresh ? c.lastSecondFrames : 0, fresh ? c.lastSecondBytes : 0,
                    q == null ? 0 : q.get(Quota.MESSAGES_PER_SECOND), q == null ? 0 : q.get(Quota.BYTES_PER_SECOND),
                    c.net.queued(), c.sessions.size(), c.recording != null));
        }
        return List.copyOf(out);
    }

    @Override
    public List<SessionInfo> sessions() {
        long now = clock.millis();
        List<SessionInfo> out = new ArrayList<>();
        for (Session s : table.liveSessions()) {
            List<String> extensions = new ArrayList<>();
            SessionOpen o = s.opened;
            if (o != null) {
                for (ExtensionRef e : o.extensions()) {
                    extensions.add(e.id() + '@' + e.version());
                }
            }
            Connection c = s.conn;
            boolean matched = view.worldMatched(s);
            out.add(new SessionInfo(s.id, c == null ? 0 : c.net.id(), s.kind.name().toLowerCase(Locale.ROOT),
                    s.displayName, s.identity, s.owner, s.world, !s.ended && view.attachedNow(s), matched, c != null,
                    s.superseded, s.detachedAtMs < 0 ? -1 : now - s.detachedAtMs, s.lastInterest, extensions,
                    store.under(s.ownerPrefix).size(), s.sampleRequest, s.log.size()));
        }
        out.sort(Comparator.comparing(SessionInfo::owner));
        return List.copyOf(out);
    }

    @Override
    public List<ObjectInfo> objects() {
        List<ObjectInfo> out = new ArrayList<>(store.size());
        for (StoredObject o : store.all()) {
            StoredObject.State st = o.state();
            ObjectKeys.Parts p = o.parts();
            out.add(new ObjectInfo(o.key(), p.owner(), p.extension(), st.layer(), st.kind().name(), st.rev(), st.scope(),
                    st.world(), st.data().length, render.seen(o, clock.millis()), st.decoded()));
        }
        out.sort(Comparator.comparing(ObjectInfo::key));
        return List.copyOf(out);
    }

    @Override
    public TrafficLog traffic() {
        return traffic;
    }

    @Override
    public BridgeMetrics metrics() {
        return metrics;
    }
}
