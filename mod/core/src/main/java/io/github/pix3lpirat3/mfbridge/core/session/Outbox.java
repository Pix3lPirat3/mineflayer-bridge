package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.protocol.core.Ack;
import io.github.pix3lpirat3.mfbridge.protocol.core.Ping;
import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.BridgeClock;
import io.github.pix3lpirat3.mfbridge.core.BridgeConfig;
import io.github.pix3lpirat3.mfbridge.core.diag.TrafficLog;
import io.github.pix3lpirat3.mfbridge.core.ext.ConnRegistry;
import io.github.pix3lpirat3.mfbridge.core.ext.ExtensionHost;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorInfo;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.Goodbye;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Everything that leaves the mod: encoding, the three delivery classes (control, reliable and
 * latest-wins), errors, and the one flush per connection and tick.
 */
@ClientThread
final class Outbox {
    private final ByteBuf scratch = Unpooled.buffer(512);
    private final WireWriter scratchWriter = new WireWriter(scratch);
    private final WireWriter frameWriter = new WireWriter(Unpooled.EMPTY_BUFFER);

    private final BridgeConfig config;
    private final Logger log;
    private final BridgeClock clock;
    private final SessionTable table;
    private final TrafficLog traffic;
    private final Recorder recorder;

    Outbox(BridgeConfig config, Logger log, BridgeClock clock, SessionTable table, TrafficLog traffic,
            Recorder recorder) {
        this.config = config;
        this.log = log;
        this.clock = clock;
        this.table = table;
        this.traffic = traffic;
        this.recorder = recorder;
    }

    private void countOutbound(Connection c, Envelope env, byte[] payload, int bytes) {
        c.framesOut++;
        c.bytesOut += bytes;
                if ((traffic.capturing() || c.recording != null) && !secret(env.type())) {
            long wall = clock.currentTimeMillis();
            if (traffic.capturing()) {
                traffic.add(c.net.id(), c.deviceName(), false, wall, env, payload, typeOf(c, env.type()));
            }
            recorder.capture(c, false, wall, env, payload);
        }
    }

    /**
     * Frames that carry credentials (the auth proof and the issued device
     * secret) are never captured, so recordings and the traffic log are safe to share.
     */
    static boolean secret(int type) {
        return type == CoreMessages.AUTH_RESPONSE.id() || type == CoreMessages.PAIR_RESULT.id();
    }

    static @Nullable MessageType<?> typeOf(Connection c, int type) {
        if (type < ConnRegistry.FIRST_ID) {
            return CoreMessages.byId(type);
        }
        ConnRegistry reg = c.registry;
        ExtensionHost.RegisteredMessage<?> m = reg == null ? null : reg.message(type);
        return m == null ? null : m.type();
    }

    void sendError(
            Connection c, int sessionId, @Nullable Session s, BridgeException e, @Nullable Integer relatesTo, long req) {
        if (s != null) {
            sendSessionError(s, e, relatesTo, req);
        } else {
            sendControl(c, sessionId, null, CoreMessages.ERROR, errorMessage(e, relatesTo), errorFlags(req), req);
        }
    }

    void sendSessionError(Session s, BridgeException e, int relatesTo) {
        sendSessionError(s, e, relatesTo, Envelope.NONE);
    }

    /** Errors inside a session are sequenced so they survive a resume. */
    void sendSessionError(Session s, BridgeException e, @Nullable Integer relatesTo, long req) {
        sendReliable(s, CoreMessages.ERROR, errorMessage(e, relatesTo), errorFlags(req), req);
    }

    private static ErrorMessage errorMessage(BridgeException e, @Nullable Integer relatesTo) {
        return new ErrorMessage(new ErrorInfo(e.code(), messageOf(e), e.retryable(), e.details()), relatesTo);
    }

    private static int errorFlags(long req) {
        return Envelope.ERROR | (req != Envelope.NONE ? Envelope.RESPONSE : 0);
    }

    static String messageOf(BridgeException e) {
        String message = e.getMessage();
        return message == null ? e.code() : message;
    }

    <T> void sendExtension(Session s, int id, MessageType<T> type, T message) {
        byte[] payload = encode(type, message);
        Connection c = s.conn;
        switch (type.delivery()) {
            case RELIABLE -> sendReliableRaw(s, id, payload, 0, Envelope.NONE);
            case LATEST -> {
                if (c != null) {
                    c.latest.put(new Connection.LatestKey(s.id, id, null), unsequenced(s, id, payload));
                }
            }
            case CONTROL, UNRELIABLE -> {
                if (c != null) {
                    queue(c, unsequenced(s, id, payload));
                }
            }
        }
    }

    /**
     * Closes dead peers, pings idle ones and sends the empty acks that are due.
     */
    void keepAlive(long now) {
        for (Connection c : table.connections) {
            if (c.state == Connection.State.CLOSING || c.net.isClosed()) {
                continue;
            }
            if (c.state != Connection.State.AWAIT_APPROVAL
                    && !c.replay
                    && now - c.lastInboundMs >= config.deadPeerMs()
                    && c.net.queued() == 0) {
                goodbye(c, "timeout", "no traffic for " + config.deadPeerMs() + " ms", true);
                continue;
            }
            if (c.state == Connection.State.READY && now - c.lastOutboundMs >= config.heartbeatMs() && c.out.isEmpty()) {
                sendControl(c, 0, null, CoreMessages.PING, new Ping(clock.currentTimeMillis()), 0, Envelope.NONE);
            }
            for (Session s : c.sessions.values()) {
                // An empty ack after 250 ms of having nothing to send.
                if (s.ackDueSinceMs >= 0 && now - s.ackDueSinceMs >= config.ackDelayMs()) {
                    sendControl(c, s.id, s, CoreMessages.ACK, new Ack(), 0, Envelope.NONE);
                    s.ackDueSinceMs = -1;
                }
            }
        }
    }

    /** Sends {@code goodbye} and closes the connection after the next flush. */
    void goodbye(Connection c, String reason, String message, boolean retry) {
        if (c.state == Connection.State.CLOSING) {
            return;
        }
        sendControl(c, 0, null, CoreMessages.GOODBYE, new Goodbye(reason, message, retry), 0, Envelope.NONE);
        c.state = Connection.State.CLOSING;
        c.closeAfterFlush = true;
    }

    private <T> byte[] encode(MessageType<T> type, T msg) {
        scratch.clear();
        type.codec().write(scratchWriter, msg);
        byte[] out = new byte[scratch.readableBytes()];
        scratch.getBytes(scratch.readerIndex(), out);
        return out;
    }

    <T> void sendControl(
            Connection c, int sessionId, @Nullable Session s, MessageType<T> type, T msg, int flags, long req) {
        queue(c, new OutFrame(flags, type.id(), sessionId, s, Envelope.NONE, req, encode(type, msg)));
    }

    /** Queues a frame for the next flush and counts its bytes against the outbound cap. */
    static void queue(Connection c, OutFrame f) {
        c.out.add(f);
        c.outBytes += f.payload().length;
    }

    <T> void sendReliable(Session s, MessageType<T> type, T msg, int flags, long req) {
        sendReliableRaw(s, type.id(), encode(type, msg), flags, req);
    }

    private void sendReliableRaw(Session s, int typeId, byte[] payload, int flags, long req) {
        OutFrame f = new OutFrame(flags, typeId, s.id, s, s.nextOutSeq++, req, payload);
        s.log.append(f);
        Connection c = s.conn;
        if (c != null) {
            queue(c, f);
        }
    }

    <T> void sendLatest(Session s, MessageType<T> type, T msg) {
        sendLatest(s, type, msg, null);
    }

    /**
     * Queues a latest-wins message; a newer one with the same type and subject replaces it until the
     * next flush. Dropped while the session has no connection (latest messages are not logged).
     */
    <T> void sendLatest(Session s, MessageType<T> type, T msg, @Nullable String subject) {
        Connection c = s.conn;
        if (c != null) {
            c.latest.put(new Connection.LatestKey(s.id, type.id(), subject), unsequenced(s, type.id(), encode(type, msg)));
        }
    }

    private static OutFrame unsequenced(Session s, int typeId, byte[] payload) {
        return new OutFrame(0, typeId, s.id, s, Envelope.NONE, Envelope.NONE, payload);
    }

    void flush(long now) {
        for (Connection c : table.connections) {
            if (c.net.isClosed()) {
                c.out.clear();
                c.outBytes = 0;
                c.latest.clear();
                continue;
            }
            int n = c.out.size() + c.latest.size();
            if (n == 0 && !c.closeAfterFlush) {
                continue;
            }
            // While unwritable, latest-wins frames coalesce and reliable ones queue, up to a cap.
            if (!c.net.isWritable() && !c.closeAfterFlush) {
                if (c.outBytes > config.maxOutboundBytes()) {
                    log.warn("Bridge connection {} ({}) stopped reading with {} bytes queued; closing it", c.net.id(),
                            c.deviceName(), c.outBytes);
                    c.out.clear();
                    c.outBytes = 0;
                    c.latest.clear();
                    c.net.abort();
                }
                continue;
            }
            ByteBuf[] frames = new ByteBuf[n];
            int i = 0;
            for (OutFrame f : c.out) {
                frames[i++] = encodeFrame(c, f);
            }
            for (OutFrame f : c.latest.values()) {
                frames[i++] = encodeFrame(c, f);
            }
            c.out.clear();
            c.outBytes = 0;
            c.latest.clear();
            boolean close = c.closeAfterFlush;
            c.closeAfterFlush = false;
            c.net.write(frames, n, close);
            if (n > 0) {
                c.lastOutboundMs = now;
            }
        }
    }

    private ByteBuf encodeFrame(Connection c, OutFrame f) {
        long ack = Envelope.NONE;
        Session s = f.session();
        if (s != null && s.conn == c && s.lastAppliedIn > 0) {
            ack = s.lastAppliedIn;
            s.ackDueSinceMs = -1;
        }
        ByteBuf buf = c.net.alloc().buffer(16 + f.payload().length);
        Envelope env = new Envelope(f.flags(), f.type(), f.sessionId(), f.seq(), ack, f.req());
        env.write(frameWriter.target(buf));
        frameWriter.target(Unpooled.EMPTY_BUFFER);
        buf.writeBytes(f.payload());
        countOutbound(c, env, f.payload(), buf.readableBytes());
        return buf;
    }

    /**
     * Sends a reliable request to a session and waits for its response, failing with {@code timeout}
     * after the request type's {@code timeoutMs}. A response that arrives later
     * finds no pending request and is dropped.
     */
    <Q, R> CompletableFuture<R> request(Session w, MessageType<Q> type, Q msg, MessageType<R> response) {
        Connection c = w.conn;
        long req = c != null ? c.nextReq++ : ++w.detachedReq;
        CompletableFuture<R> future = new CompletableFuture<>();
        w.pending.put(req, new PendingRequest<>(req, response, future, clock.millis() + type.timeoutMs()));
        sendReliable(w, type, msg, 0, req);
        return future;
    }

    /** Sends a reliable success response to a request the session made. */
    <T> void respond(Session s, MessageType<T> type, T msg, long req) {
        sendReliable(s, type, msg, Envelope.RESPONSE, req);
    }

    /**
     * Sends an error response to a request the session made. It is sequenced and logged, so it is
     * replayed after a resume when the session has no connection right now.
     */
    void respondError(Session s, String code, String message, int relatesTo, long req) {
        sendSessionError(s, new BridgeException(code, message), relatesTo, req);
    }
}
