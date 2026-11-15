package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.BridgeClock;
import io.github.pix3lpirat3.mfbridge.core.diag.TrafficLog;
import io.github.pix3lpirat3.mfbridge.core.remote.Quota;
import io.github.pix3lpirat3.mfbridge.core.remote.Quotas;
import io.github.pix3lpirat3.mfbridge.core.transport.BridgeServer;
import io.github.pix3lpirat3.mfbridge.core.transport.InboundFrame;
import io.github.pix3lpirat3.mfbridge.core.transport.NetConnection;
import io.github.pix3lpirat3.mfbridge.core.util.Encoding;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOp;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireException;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Takes inbound frames off the connections: round robin under a time budget, counted per
 * connection and policed per device.
 */
@ClientThread
final class Intake {
    /** Inbound rate of each device over all of its connections. */
    private final Map<String, DeviceRate> rates = new HashMap<>();
    private int roundRobin;

    private final Logger log;
    private final BridgeClock clock;
    private final Devices access;
    private final Streams streams;
    private final Recorder recorder;
    private final TrafficLog traffic;
    private final SessionTable table;
    private final Outbox outbox;
    private final WorldStores stores;
    private final Dispatcher dispatcher;

    Intake(Logger log, BridgeClock clock, Devices access, Streams streams, Recorder recorder, TrafficLog traffic,
            SessionTable table, Outbox outbox, WorldStores stores, Dispatcher dispatcher) {
        this.log = log;
        this.clock = clock;
        this.access = access;
        this.streams = streams;
        this.recorder = recorder;
        this.traffic = traffic;
        this.table = table;
        this.outbox = outbox;
        this.stores = stores;
        this.dispatcher = dispatcher;
    }

    /** One device's inbound frames and bytes in the current second, over all of its connections. */
    private static final class DeviceRate {
        final RatePolicer policer = new RatePolicer();
        long windowStartMs;
        long frames;
        long bytes;
    }

    /** Returns the listener to hand to {@link BridgeServer}; it only enqueues. */
    @AnyThread
    BridgeServer.Listener netListener() {
        return new BridgeServer.Listener() {
            @Override
            public void connected(NetConnection connection) {
                table.accepted.add(connection);
            }

            @Override
            public void disconnected(NetConnection connection) {
                // Closure is observed through NetConnection.isClosed once its queue is drained.
            }
        };
    }

    /** Starts tracking the connections accepted since the last tick. */
    void accept(long now) {
        NetConnection net;
        while ((net = table.accepted.poll()) != null) {
            recorder.connectionAccepted(table.addConnection(net, now));
        }
    }

    void drain(long budgetNanos, long now) {
        int n = table.connections.size();
        if (n == 0) {
            return;
        }
        long start = System.nanoTime();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int k = 0; k < n; k++) {
                Connection c = table.connections.get((roundRobin + k) % n);
                InboundFrame f = c.net.peek();
                if (f == null) {
                    continue;
                }
                if (c.state == Connection.State.CLOSING) {
                    while (c.net.poll() != null) {
                        // After our goodbye the peer's remaining frames are moot.
                        c.lastInboundMs = now;
                    }
                    continue;
                }
                if (stalledOnStore(c, f)) {
                    continue;
                }
                c.net.poll();
                handleFrame(c, f, now);
                progress = true;
                if (System.nanoTime() - start >= budgetNanos) {
                    roundRobin = (roundRobin + k + 1) % n;
                    return;
                }
            }
        }
        roundRobin = (roundRobin + 1) % n;
    }

    /**
     * A {@code shared.op} must see its world store loaded, or conflict checks would run against
     * missing data. The frame stays at the head of its queue until the load completes, which keeps
     * per-connection ordering.
     */
    private boolean stalledOnStore(Connection c, InboundFrame f) {
        Envelope env = f.envelope();
        if (env != null && env.type() == CoreMessages.STREAM_OFFER.id() && c.state == Connection.State.READY) {
            return !streams.ready();
        }
        if (env == null || env.type() != CoreMessages.SHARED_OP.id() || c.state != Connection.State.READY) {
            return false;
        }
        Session s = c.sessions.get(env.session());
        if (s == null) {
            return false;
        }
        SharedOp op;
        try {
            op = Encoding.decode(SharedOp.CODEC, f.payload());
        } catch (WireException e) {
            return false;
        }
        boolean stalled = false;
        for (String key : stores.storesFor(op.op(), s.world)) {
            // A store that may not be loaded leaves the op to fail in its handler.
            if (!stores.isLoaded(key) && stores.ensureLoading(key, false)) {
                stalled = true;
            }
        }
        return stalled;
    }

    private void handleFrame(Connection c, InboundFrame f, long now) {
        c.lastInboundMs = now;
        Envelope env = f.envelope();
        countInbound(c, env, f.payload(), now);
        if (env == null) {
            dispatcher.reject(c, null, new BridgeException("invalid_message", "malformed envelope: " + f.error()));
            return;
        }
        try {
            dispatcher.dispatch(c, env, f.payload(), now);
        } catch (BridgeException e) {
            dispatcher.reject(c, env, e);
        } catch (WireException e) {
            dispatcher.reject(c, env, new BridgeException(e.code(), e.getMessage() == null ? "malformed payload" : e.getMessage()));
        } catch (RuntimeException e) {
            String correlation = Long.toHexString(Handshake.RANDOM.nextLong());
            log.error("Internal bridge error {} handling type {}", correlation, env.type(), e);
            dispatcher.reject(c, env, new BridgeException("internal", "internal error " + correlation));
        }
    }

    /** Counters, the traffic log and the recorder see every inbound frame here. */
    private void countInbound(Connection c, @Nullable Envelope env, byte[] payload, long now) {
        int bytes = payload.length + (env == null ? 0 : env.encodedSize());
        c.framesIn++;
        c.bytesIn += bytes;
        if (now - c.rateWindowStartMs >= 1000) {
            boolean adjacent = now - c.rateWindowStartMs < 2000;
            c.lastSecondFrames = adjacent ? c.windowFrames : 0;
            c.lastSecondBytes = adjacent ? c.windowBytes : 0;
            c.windowFrames = 0;
            c.windowBytes = 0;
            c.rateWindowStartMs = now;
        }
        c.windowFrames++;
        c.windowBytes += bytes;
        countForDevice(c, bytes, now);
        if (env == null || Outbox.secret(env.type())) {
            return;
        }
        if (traffic.capturing() || c.recording != null) {
            long wall = clock.currentTimeMillis();
            if (traffic.capturing()) {
                traffic.add(c.net.id(), c.deviceName(), true, wall, env, payload, Outbox.typeOf(c, env.type()));
            }
            recorder.capture(c, true, wall, env, payload);
        }
    }

    /**
     * A device more than twice over its budget for five seconds gets {@code rate_limited}; one that
     * keeps going has its connections closed. The budget is per device: its connections and
     * sessions share it, so opening more of them buys nothing.
     */
    private void countForDevice(Connection c, int bytes, long now) {
        String device = c.deviceId;
        if (device == null || c.state != Connection.State.READY) {
            return;
        }
        DeviceRate r = rates.computeIfAbsent(device, k -> new DeviceRate());
        if (now - r.windowStartMs >= 1000) {
            police(device, r);
            r.frames = 0;
            r.bytes = 0;
            r.windowStartMs = now;
        }
        r.frames++;
        r.bytes += bytes;
    }

    private void police(String device, DeviceRate r) {
        Quotas q = access.quotas(device);
        RatePolicer.Verdict v = r.policer.second(r.frames, r.bytes, q.get(Quota.MESSAGES_PER_SECOND),
                q.get(Quota.BYTES_PER_SECOND));
        if (v == RatePolicer.Verdict.OK) {
            return;
        }
        for (Connection c : table.connectionsOf(device)) {
            if (v == RatePolicer.Verdict.WARN) {
                log.warn("Bridge connection {} ({}) is over twice its rate budget; warning it", c.net.id(), c.deviceName());
                outbox.sendError(c, 0, null, new BridgeException("rate_limited", "over twice the advertised rate budget for "
                        + RatePolicer.SUSTAIN_SECONDS + " s; slow down or the connection closes"), null, Envelope.NONE);
            } else {
                log.warn("Bridge connection {} ({}) stayed over its rate budget; closing it", c.net.id(), c.deviceName());
                outbox.goodbye(c, "rate_limited", "stayed over twice the advertised rate budget", true);
            }
        }
    }

    /** Forgets the rate windows of devices that have been silent for a minute. */
    void prune(long now) {
        rates.values().removeIf(r -> now - r.windowStartMs >= 60_000);
    }

    /** Keeps the round robin inside the list after connections were removed. */
    void connectionsChanged() {
        if (roundRobin >= Math.max(1, table.connections.size())) {
            roundRobin = 0;
        }
    }
}
