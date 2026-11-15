package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import io.github.pix3lpirat3.mfbridge.core.sample.ClientSampleControls;
import io.github.pix3lpirat3.mfbridge.protocol.core.ClientSample;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.SampleRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import org.jspecify.annotations.Nullable;

/**
 * Tracks each session's {@code sample.request}, and sends {@code
 * client.sample} (latest-wins) to attached, world-matched bots with the fields they asked for and
 * are permitted to see. Position, yaw and pitch need {@code client.sample.position}; the crosshair
 * target needs {@code client.sample.target}; the held item needs {@code client.sample.inventory}.
 */
@ClientThread
final class Samples implements ClientSampleControls {
    /** Fed values older than this are stale and not sent. */
    private static final long STALE_MS = 1_000;
    private static final float MIN_RATE_HZ = 0.1f;

    private final SessionHost host;
    private final SessionTable table;
    private float maxRateHz = DEFAULT_MAX_RATE_HZ;
    private @Nullable Vec3d position;
    private float yaw;
    private float pitch;
    private @Nullable Target target;
    private @Nullable SlotItem heldItem;
    private long fedAtMs = Long.MIN_VALUE / 2;
    private boolean wantPosition;
    private boolean wantTarget;
    private boolean wantHeld;

    Samples(SessionHost host, SessionTable table) {
        this.host = host;
        this.table = table;
    }

    /**
     * Stores a session's request. Requested fields the session may not see trigger the one-time
     * permission prompt and are reported once as {@code permission_denied}; they are left out of
     * the samples until granted.
     */
    void request(Session s, SampleRequest r) {
        boolean any = r.position() || r.target() || r.heldItem();
        if (!any || !(r.rateHz() > 0f) || s.kind != SessionKind.BOT) {
            s.sampleRequest = null;
            s.lastSample = null;
            return;
        }
        s.sampleRequest = r;
        s.lastSample = null;
        s.nextSampleMs = 0;
        if (r.position()) {
            host.reportMissing(s, Permission.CLIENT_SAMPLE_POSITION);
        }
        if (r.target()) {
            host.reportMissing(s, Permission.CLIENT_SAMPLE_TARGET);
        }
        if (r.heldItem()) {
            host.reportMissing(s, Permission.CLIENT_SAMPLE_INVENTORY);
        }
    }

    /** Recomputes what is wanted and sends due samples. Called once per tick before the flush. */
    void tick(long now) {
        boolean pos = false;
        boolean tgt = false;
        boolean held = false;
        boolean fresh = now - fedAtMs <= STALE_MS;
        for (Session s : table.connectedSessions()) {
            SampleRequest r = s.sampleRequest;
            if (r == null) {
                continue;
            }
            if (!eligible(s)) {
                // A bot that attaches again gets a fresh sample, even if nothing changed meanwhile.
                s.lastSample = null;
                continue;
            }
            boolean p = r.position() && host.permitted(s, Permission.CLIENT_SAMPLE_POSITION);
            boolean t = r.target() && host.permitted(s, Permission.CLIENT_SAMPLE_TARGET);
            boolean h = r.heldItem() && host.permitted(s, Permission.CLIENT_SAMPLE_INVENTORY);
            pos |= p;
            tgt |= t;
            held |= h;
            if (!fresh || now < s.nextSampleMs || !(p || t || h)) {
                continue;
            }
            float rate = Math.max(MIN_RATE_HZ, Math.min(r.rateHz(), maxRateHz));
            s.nextSampleMs = now + (long) Math.ceil(1000.0 / rate);
            ClientSample sample = new ClientSample(p ? position : null, p ? yaw : null, p ? pitch : null,
                    t ? target : null, h ? heldItem : null);
            if (sample.equals(s.lastSample)) {
                continue;
            }
            s.lastSample = sample;
            host.sendLatest(s, CoreMessages.CLIENT_SAMPLE, sample, null);
        }
        wantPosition = pos;
        wantTarget = tgt;
        wantHeld = held;
    }

    private boolean eligible(Session s) {
        return s.kind == SessionKind.BOT && !s.ended && !s.superseded && s.conn != null && host.worldMatched(s)
                && host.attachedNow(s);
    }

    @Override
    public boolean wantsPosition() {
        return wantPosition;
    }

    @Override
    public boolean wantsTarget() {
        return wantTarget;
    }

    @Override
    public boolean wantsHeldItem() {
        return wantHeld;
    }

    @Override
    public void feed(@Nullable Vec3d position, float yaw, float pitch, @Nullable Target target,
            @Nullable SlotItem heldItem) {
        this.position = position;
        this.yaw = yaw;
        this.pitch = pitch;
        this.target = target;
        this.heldItem = heldItem;
        fedAtMs = host.nowMs();
    }

    @Override
    public float maxRateHz() {
        return maxRateHz;
    }

    @Override
    public void setMaxRateHz(float hz) {
        maxRateHz = Float.isFinite(hz) ? Math.clamp(hz, MIN_RATE_HZ, MAX_RATE_HZ) : DEFAULT_MAX_RATE_HZ;
    }
}
