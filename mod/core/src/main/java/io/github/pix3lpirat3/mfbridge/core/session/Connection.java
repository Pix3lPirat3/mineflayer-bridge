package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.core.ApprovalDecision;
import io.github.pix3lpirat3.mfbridge.core.ext.ConnRegistry;
import io.github.pix3lpirat3.mfbridge.core.transport.NetConnection;
import io.github.pix3lpirat3.mfbridge.protocol.core.AuthMethod;
import io.github.pix3lpirat3.mfbridge.protocol.core.Hello;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/** Client-thread protocol state of one connection. */
final class Connection {
    enum State {
        AWAIT_HELLO,
        AWAIT_AUTH,
        AWAIT_APPROVAL,
        READY,
        /** A goodbye was queued; inbound frames are discarded until the channel closes. */
        CLOSING
    }

    final NetConnection net;
    State state = State.AWAIT_HELLO;
    long connectionId;
    byte[] nonce = new byte[0];
    List<AuthMethod> offered = List.of();
    @Nullable Hello hello;
    @Nullable String deviceId;
    @Nullable CompletableFuture<ApprovalDecision> approval;
    @Nullable ConnRegistry registry;

    final Int2ObjectLinkedOpenHashMap<Session> sessions = new Int2ObjectLinkedOpenHashMap<>();
    int nextSessionId = 1;
    long nextReq = 1;

    /** The secret the peer proved it knows, kept until {@code welcome} answers with the mod's proof. */
    byte @Nullable [] proofKey;
    /** The peer's own nonce from {@code auth.response}, or null when it sent none. */
    byte @Nullable [] peerNonce;

    final ArrayList<OutFrame> out = new ArrayList<>();
    /** Payload bytes waiting in {@link #out}. */
    long outBytes;
    /**
     * Coalescing key of a latest-wins frame.
     *
     * @param subject what the message is about when one session gets several of the same type (the
     *     settings key of {@code settings.values}), else null
     */
    record LatestKey(int session, int type, @Nullable String subject) {}

    // Latest-wins frames coalesce per key while unwritable.
    final Object2ObjectLinkedOpenHashMap<LatestKey, OutFrame> latest = new Object2ObjectLinkedOpenHashMap<>();

    long lastInboundMs;
    long lastOutboundMs;
    boolean closeAfterFlush;

    /**
     * A replay of a recording: authentication is taken as done, sequence
     * gaps are allowed and dead-peer timeouts do not apply.
     */
    boolean replay;
    /** Frame and byte counters, both directions. */
    long framesIn;
    long framesOut;
    long bytesIn;
    long bytesOut;
    /** Inbound frames and bytes in the current one-second window, and in the last full one. */
    long rateWindowStartMs;
    long windowFrames;
    long windowBytes;
    long lastSecondFrames;
    long lastSecondBytes;
    /** Last heartbeat round trip in milliseconds, -1 before the first pong. */
    long rttMs = -1;
    /** The open recording of this connection, or null. */
    Recorder.@Nullable Track recording;

    Connection(NetConnection net, long nowMs) {
        this.net = net;
        this.lastInboundMs = nowMs;
        this.lastOutboundMs = nowMs;
    }

    String deviceName() {
        Hello h = hello;
        return h == null ? "unknown" : h.deviceName();
    }
}
