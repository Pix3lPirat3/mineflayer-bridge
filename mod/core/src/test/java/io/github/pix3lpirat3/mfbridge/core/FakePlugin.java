package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.pix3lpirat3.mfbridge.core.auth.Proofs;
import io.github.pix3lpirat3.mfbridge.core.util.Encoding;
import io.github.pix3lpirat3.mfbridge.protocol.core.AuthChallenge;
import io.github.pix3lpirat3.mfbridge.protocol.core.AuthMethod;
import io.github.pix3lpirat3.mfbridge.protocol.core.AuthResponse;
import io.github.pix3lpirat3.mfbridge.protocol.core.BotIdentity;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.Hello;
import io.github.pix3lpirat3.mfbridge.protocol.core.Lifetime;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjUpsert;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectFull;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectWrite;
import io.github.pix3lpirat3.mfbridge.protocol.core.Ping;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpened;
import io.github.pix3lpirat3.mfbridge.protocol.core.Welcome;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireReader;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/** A fake mineflayer-bridge plugin speaking the protocol over the JDK WebSocket client. */
final class FakePlugin implements AutoCloseable {
    record Frame(Envelope env, byte[] payload) {
        <T> T decode(MessageType<T> type) {
            assertEquals(type.id(), env.type(), "frame type");
            return Encoding.decode(type.codec(), payload);
        }

        boolean is(MessageType<?> type) {
            return env.type() == type.id();
        }
    }

    final Harness h;
    final WebSocket ws;
    final String deviceId;
    final CompletableFuture<Integer> closed = new CompletableFuture<>();
    private final List<Frame> received = new ArrayList<>();
    private int cursor;
    private final Map<Integer, Long> nextSeq = new HashMap<>();
    private final Map<Integer, Long> lastAppliedFromMod = new HashMap<>();
    private long nextReq = 1;
    @Nullable Welcome welcome;
    private @Nullable FrameListener listener;
    /** The connection id of the last challenge answered with {@link #handshakeWithNonce}. */
    long connectionId;

    private FakePlugin(Harness h, WebSocket ws, String deviceId) {
        this.h = h;
        this.ws = ws;
        this.deviceId = deviceId;
    }

    static FakePlugin connect(Harness h, String deviceId) {
        return connect(h, deviceId, HttpClient.newHttpClient(), "ws://127.0.0.1:" + h.port() + "/bridge/v1");
    }

    /** Connects with a given client and URL, for example {@code wss://} with a pinned trust manager. */
    static FakePlugin connect(Harness h, String deviceId, HttpClient client, String url) {
        FrameListener listener = new FrameListener();
        WebSocket ws = client
                .newWebSocketBuilder()
                .buildAsync(URI.create(url), listener)
                .orTimeout(5, TimeUnit.SECONDS)
                .join();
        FakePlugin p = new FakePlugin(h, ws, deviceId);
        listener.owner = p;
        p.listener = listener;
        return p;
    }

    /** Stops reading from the socket, as a stuck peer would. */
    void stopReading() {
        Objects.requireNonNull(listener).paused = true;
    }

    /** Completes the local handshake, sending the plugin's own nonce for the mod to prove itself on. */
    Welcome handshakeWithNonce(byte[] key, byte[] nonce) {
        AuthChallenge ch = hello();
        byte[] proof = Proofs.proof(key, ch.nonce(), ch.connectionId(), deviceId);
        connectionId = ch.connectionId();
        control(0, CoreMessages.AUTH_RESPONSE, new AuthResponse(AuthMethod.LOCAL, deviceId, proof, nonce));
        welcome = await(CoreMessages.WELCOME);
        return welcome;
    }

    /** Connects and completes the handshake with the local token. */
    static FakePlugin ready(Harness h, String deviceId) throws Exception {
        FakePlugin p = connect(h, deviceId);
        p.handshake(h.localToken(), List.of(), List.of());
        return p;
    }

    private static final class FrameListener implements WebSocket.Listener {
        @Nullable FakePlugin owner;
        volatile boolean paused;
        private final ByteArrayOutputStream partial = new ByteArrayOutputStream();

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            partial.writeBytes(chunk);
            if (last) {
                byte[] all = partial.toByteArray();
                partial.reset();
                ByteBuf buf = Unpooled.wrappedBuffer(all);
                Envelope env = Envelope.read(new WireReader(buf));
                byte[] payload = new byte[buf.readableBytes()];
                buf.readBytes(payload);
                FakePlugin o = owner;
                if (o != null) {
                    o.onFrame(new Frame(env, payload));
                }
            }
            if (!paused) {
                webSocket.request(1);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            FakePlugin o = owner;
            if (o != null) {
                o.closed.complete(statusCode);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            FakePlugin o = owner;
            if (o != null) {
                o.closed.complete(-1);
            }
        }
    }

    private synchronized void onFrame(Frame f) {
        received.add(f);
        if (f.env().seq() != Envelope.NONE) {
            lastAppliedFromMod.merge(f.env().session(), f.env().seq(), Math::max);
        }
    }

    synchronized List<Frame> all() {
        return List.copyOf(received);
    }

    synchronized long lastApplied(int session) {
        return lastAppliedFromMod.getOrDefault(session, 0L);
    }

    private synchronized @Nullable Frame take(Predicate<Frame> p) {
        for (int i = cursor; i < received.size(); i++) {
            Frame f = received.get(i);
            if (p.test(f)) {
                received.remove(i);
                received.add(cursor, f);
                cursor++;
                return f;
            }
        }
        return null;
    }

    /** Ticks until an unconsumed frame matching {@code p} arrives, then consumes it. */
    Frame await(String what, Predicate<Frame> p) {
        long deadline = System.currentTimeMillis() + Harness.TIMEOUT_MS;
        while (true) {
            h.tick();
            Frame f = take(p);
            if (f != null) {
                return f;
            }
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for " + what + "; received " + describe());
            }
            Harness.sleep();
        }
    }

    <T> T await(MessageType<T> type) {
        return await(type.name(), f -> f.is(type)).decode(type);
    }

    <T> T await(MessageType<T> type, int session) {
        return await(type.name() + " on session " + session, f -> f.is(type) && f.env().session() == session)
                .decode(type);
    }

    /** Returns whether an unconsumed frame matching {@code p} is present, consuming it. */
    boolean poll(Predicate<Frame> p) {
        return take(p) != null;
    }

    synchronized String describe() {
        StringBuilder sb = new StringBuilder("[");
        for (Frame f : received) {
            sb.append("0x").append(Integer.toHexString(f.env().type())).append('@').append(f.env().session()).append(' ');
        }
        return sb.append(']').toString();
    }

    <T> void send(int session, MessageType<T> type, T msg, int flags, long seq, long ack, long req) {
        ByteBuf buf = Unpooled.buffer();
        WireWriter w = new WireWriter(buf);
        new Envelope(flags, type.id(), session, seq, ack, req).write(w);
        type.codec().write(w, msg);
        sendRaw(buf);
    }

    void sendRaw(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        ws.sendBinary(ByteBuffer.wrap(bytes), true).orTimeout(5, TimeUnit.SECONDS).join();
    }

    <T> void control(int session, MessageType<T> type, T msg) {
        send(session, type, msg, 0, Envelope.NONE, Envelope.NONE, Envelope.NONE);
    }

    /** Sends a reliable message with the next seq for the session. Returns the seq used. */
    <T> long reliable(int session, MessageType<T> type, T msg) {
        long seq = nextSeq.merge(session, 1L, Long::sum);
        send(session, type, msg, 0, seq, Envelope.NONE, Envelope.NONE);
        return seq;
    }

    /** Sends a reliable request; returns the req id. */
    <T> long reliableRequest(int session, MessageType<T> type, T msg) {
        long seq = nextSeq.merge(session, 1L, Long::sum);
        long req = nextReq++;
        send(session, type, msg, 0, seq, Envelope.NONE, req);
        return req;
    }

    <T> long request(int session, MessageType<T> type, T msg) {
        long req = nextReq++;
        send(session, type, msg, 0, Envelope.NONE, Envelope.NONE, req);
        return req;
    }

    /** Allocates the next plugin seq for a session. */
    long nextSeq(int session) {
        return nextSeq.merge(session, 1L, Long::sum);
    }

    void setNextSeq(int session, long lastUsed) {
        nextSeq.put(session, lastUsed);
    }

    /** Sends {@code hello} and returns the challenge. */
    AuthChallenge hello() {
        control(0, CoreMessages.HELLO, new Hello(1, 0, "test-plugin", "test-device", List.of(), List.of()));
        return await(CoreMessages.AUTH_CHALLENGE);
    }

    /** Completes the handshake with the {@code device} method and a paired secret. */
    Welcome deviceHandshake(byte[] secret) {
        AuthChallenge ch = hello();
        byte[] proof = Proofs.proof(secret, ch.nonce(), ch.connectionId(), deviceId);
        control(0, CoreMessages.AUTH_RESPONSE, new AuthResponse(AuthMethod.DEVICE, deviceId, proof));
        welcome = await(CoreMessages.WELCOME);
        return welcome;
    }

    Welcome handshake(byte[] key, List<String> messages, List<String> kinds) {
        control(0, CoreMessages.HELLO, new Hello(1, 0, "test-plugin", "test-device", messages, kinds));
        AuthChallenge ch = await(CoreMessages.AUTH_CHALLENGE);
        byte[] proof = Proofs.proof(key, ch.nonce(), ch.connectionId(), deviceId);
        control(0, CoreMessages.AUTH_RESPONSE, new AuthResponse(AuthMethod.LOCAL, deviceId, proof));
        welcome = await(CoreMessages.WELCOME);
        return welcome;
    }

    SessionOpened openBot(String username, String server) {
        long req = request(0, CoreMessages.SESSION_OPEN, new SessionOpen(SessionKind.BOT, username,
                new BotIdentity(username, null, server), List.of()));
        Frame f = await("session.opened", x -> x.is(CoreMessages.SESSION_OPENED) && x.env().req() == req);
        return f.decode(CoreMessages.SESSION_OPENED);
    }

    static ObjectFull object(String key, KindType<?> kind, long rev, byte[] data) {
        return new ObjectFull(key, kind.id(), rev, null, Lifetime.SESSION, Scope.GLOBAL, null, null, data);
    }

    static ObjectFull worldObject(String key, KindType<?> kind, long rev, WorldKey world, byte[] data) {
        return new ObjectFull(key, kind.id(), rev, null, Lifetime.SESSION, Scope.WORLD, world, null, data);
    }

    long upsert(int session, ObjectFull... objects) {
        List<ObjectWrite> writes = new ArrayList<>();
        for (ObjectFull o : objects) {
            writes.add(new ObjectWrite.Full(o));
        }
        return reliable(session, CoreMessages.OBJ_UPSERT, new ObjUpsert(writes));
    }

    private long syncCounter = 1_000_000;

    /** Round-trips a ping, so every frame sent before it has been processed by the mod. */
    void sync() {
        long t = syncCounter++;
        control(0, CoreMessages.PING, new Ping(t));
        await("pong " + t, f -> f.is(CoreMessages.PONG)
                && f.decode(CoreMessages.PONG).time() == t);
    }

    void abort() {
        ws.abort();
    }

    @Override
    public void close() {
        ws.abort();
    }
}
