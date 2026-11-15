package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.BridgeClock;
import io.github.pix3lpirat3.mfbridge.core.SessionStats;
import io.github.pix3lpirat3.mfbridge.core.transport.NetConnection;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jspecify.annotations.Nullable;

/**
 * The connections and sessions the engine knows, and the lookups over them. It only holds state;
 * what opens, resumes and ends a session is in {@link SessionLifecycle}.
 */
@ClientThread
final class SessionTable {
    /** Connections the network threads accepted, waiting for the next tick. */
    final ConcurrentLinkedQueue<NetConnection> accepted = new ConcurrentLinkedQueue<>();
    /** Every connection, in accept order. */
    final ArrayList<Connection> connections = new ArrayList<>();
    final Map<NetConnection, Connection> byNet = new IdentityHashMap<>();
    /** Sessions by resume token. */
    final Object2ObjectOpenHashMap<String, Session> byToken = new Object2ObjectOpenHashMap<>();
    /** The session that writes for each owned prefix ({@code <owner>/}). */
    final Object2ObjectOpenHashMap<String, Session> writers = new Object2ObjectOpenHashMap<>();
    /** Sessions whose connection closed, kept for the resume window. */
    final ArrayList<Session> detached = new ArrayList<>();
    int nextEpoch = 1;
    int nextGen = 1;

    private final BridgeClock clock;

    SessionTable(BridgeClock clock) {
        this.clock = clock;
    }

    /** Adds a virtual connection that replays a recording; it is ticked like any other. */
    void adoptVirtual(NetConnection net) {
        addConnection(net, clock.millis()).replay = true;
    }

    /** Starts tracking an accepted connection. */
    Connection addConnection(NetConnection net, long now) {
        Connection c = new Connection(net, now);
        connections.add(c);
        byNet.put(net, c);
        return c;
    }

    /** Returns how many sessions a device holds. */
    int sessionsOf(String device) {
        int n = 0;
        for (Session s : liveSessions()) {
            if (s.deviceId.equals(device)) {
                n++;
            }
        }
        return n;
    }

    /** Returns the declared bytes of the open inbound streams of a device, over all of its sessions. */
    long streamBytesInFlight(String device) {
        long n = 0;
        for (Session s : liveSessions()) {
            if (s.deviceId.equals(device)) {
                n += s.streamBytesInFlight;
            }
        }
        return n;
    }

    /** Returns every session, with or without a connection. */
    List<Session> liveSessions() {
        List<Session> out = new ArrayList<>();
        for (Connection c : connections) {
            out.addAll(c.sessions.values());
        }
        out.addAll(detached);
        return out;
    }

    /** Returns diagnostics for every live or detached session. */
    List<SessionStats> sessionStats() {
        List<SessionStats> out = new ArrayList<>();
        for (Session s : liveSessions()) {
            out.add(new SessionStats(s.id, s.ownerPrefix, s.displayName, s.conn != null, s.superseded,
                    s.lastAppliedIn, s.nextOutSeq - 1, s.log.size(), s.log.bytes(), s.log.overflowed()));
        }
        return List.copyOf(out);
    }

    /** Returns the number of inbound frames waiting across all connections. */
    int queuedInboundFrames() {
        int n = 0;
        for (Connection c : connections) {
            n += c.net.queued();
        }
        for (NetConnection net : accepted) {
            n += net.queued();
        }
        return n;
    }

    /** Returns the sessions that currently have a ready connection. */
    List<Session> connectedSessions() {
        List<Session> out = new ArrayList<>();
        for (Connection c : connections) {
            if (c.state == Connection.State.READY) {
                out.addAll(c.sessions.values());
            }
        }
        return out;
    }

    /** Returns the live writer session of an owner (without trailing slash), or null. */
    @Nullable Session writer(String owner) {
        Session w = writers.get(owner + '/');
        return w == null || w.ended ? null : w;
    }

    /** Returns the connections a device authenticated, in any state. */
    List<Connection> connectionsOf(String deviceId) {
        List<Connection> out = new ArrayList<>();
        for (Connection c : connections) {
            if (deviceId.equals(c.deviceId) && c.state == Connection.State.READY && !c.net.isClosed()) {
                out.add(c);
            }
        }
        return out;
    }

    /** Returns the writer sessions sorted by owned prefix. */
    List<Session> writersInOrder() {
        List<Session> sorted = new ArrayList<>(writers.values());
        sorted.sort((a, b) -> a.ownerPrefix.compareTo(b.ownerPrefix));
        return sorted;
    }
}
