package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobCache;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobRef;
import io.github.pix3lpirat3.mfbridge.core.blob.StreamPayloads;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamAccept;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamChunk;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamEnd;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamHave;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamOffer;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamReject;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamWindow;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import io.github.pix3lpirat3.mfbridge.core.remote.Quota;
import io.github.pix3lpirat3.mfbridge.core.remote.Quotas;
import org.jspecify.annotations.Nullable;

/**
 * Inbound blob streams, node to mod. Per session:
 *
 * <ul>
 *   <li>{@code stream.offer{size}} is the decoded payload size and {@code hash} its SHA-256. The offer
 *       is answered with {@code stream.have} when the cache holds the hash, else with {@code
 *       stream.accept{window}}, or {@code stream.reject} with {@code hash_mismatch} (malformed hash),
 *       {@code unsupported_codec}, {@code too_large} (over the blob cap) or {@code quota_exceeded} (the
 *       session's open streams would exceed the in-flight quota). Re-offering an open stream id drops
 *       its partial data and starts over.
 *   <li>Windows are credits in encoded bytes: {@code accept.window} lets the sender send bytes {@code
 *       [0, window)}, and each {@code stream.window{window}} adds that many bytes. The mod grants another
 *       window once less than half of the current one is left.
 *   <li>Offsets count transferred bytes (compressed bytes for {@code zstd}, which senders use only
 *       when smaller, so transfers never exceed {@code size}). Chunks must arrive in order, else {@code
 *       bad_offset}; stay within the credit, else {@code window_exceeded}; and hold at most 256 KiB
 *       without exceeding {@code size} in total, else {@code too_large}.
 *   <li>{@code stream.end} decodes and hashes off the client thread: a corrupt zstd frame or a decoded
 *       length other than {@code size} rejects with {@code decode_failed}, a SHA-256 that differs with
 *       {@code hash_mismatch}. A verified payload enters the blob cache, which publishes a new render
 *       snapshot.
 *   <li>A {@code stream.reject} from the sender (for example {@code cancelled}, when the blob is no
 *       longer referenced) drops the partial data.
 * </ul>
 *
 * <p>Streams belong to the session, not the connection: chunks are reliable messages, so after a
 * successful resume the plugin's replay continues the stream where the mod's {@code lastApplied}
 * left it, and no stream state restarts. When a session ends (including a failed resume), its open
 * streams are dropped and the plugin offers them again on its next session. Chunks or ends for a
 * stream the mod does not know (already rejected, or never offered) are ignored, because the sender
 * may still have them in flight when the reject arrives.
 */
@ClientThread
final class Streams {
    /** Largest chunk payload, in bytes. */
    static final int MAX_CHUNK = 256 << 10;

    /** Credit granted at accept and at each refill, in bytes. */
    static final int WINDOW = 1 << 20;

    static final class Inbound {
        final int streamId;
        final BlobRef ref;
        final String codec;
        final long maxEncoded;
        byte[] buffer;
        int received;
        long granted;
        @Nullable CompletableFuture<Verified> verifying;

        Inbound(int streamId, BlobRef ref, String codec, long maxEncoded, int initialCapacity, long granted) {
            this.streamId = streamId;
            this.ref = ref;
            this.codec = codec;
            this.maxEncoded = maxEncoded;
            this.buffer = new byte[initialCapacity];
            this.granted = granted;
        }
    }

    /** Outcome of decoding and hashing a finished stream: the payload, or the reject reason. */
    record Verified(byte @Nullable [] payload, String reason) {}

    private final SessionHost host;
    private final SessionTable table;
    private final BlobCache cache;
    private final Executor worker;
    private final List<Session> verifying = new ArrayList<>();

    /** Blob size and in-flight limits come from each session's device quotas. */
    Streams(SessionHost host, SessionTable table, BlobCache cache, Executor worker) {
        this.host = host;
        this.table = table;
        this.cache = cache;
        this.worker = worker;
    }

    /** Offers must see the on-disk index, or a cached blob would be sent again. */
    boolean ready() {
        return cache.isIndexLoaded();
    }

    void offer(Session s, StreamOffer o) {
        int id = o.streamId();
        Inbound previous = s.streams.get(id);
        if (previous != null) {
            drop(s, previous);
        }
        BlobRef ref = BlobRef.tryOf(o.hash(), o.size());
        if (ref == null) {
            reject(s, id, "hash_mismatch");
            return;
        }
        if (cache.contains(ref.hex())) {
            cache.touch(ref.hex());
            host.sendReliable(s, CoreMessages.STREAM_HAVE, new StreamHave(id), 0, Envelope.NONE);
            return;
        }
        if (!StreamPayloads.supported(o.codec())) {
            reject(s, id, "unsupported_codec");
            return;
        }
        // The wire size is u64; anything above the cap, including values that wrapped negative, is too large.
        Quotas quotas = host.quotas(s);
        if (o.size() < 0 || o.size() > Integer.toUnsignedLong(quotas.get(Quota.BLOB_BYTES))) {
            reject(s, id, "too_large");
            return;
        }
        // In flight is counted per device, so more sessions do not buy more.
        if (table.streamBytesInFlight(s.deviceId) + o.size() > Integer.toUnsignedLong(quotas.get(Quota.BLOB_IN_FLIGHT))) {
            reject(s, id, "quota_exceeded");
            return;
        }
        long maxEncoded = StreamPayloads.maxEncodedSize(o.codec(), o.size());
        long window = Math.min(WINDOW, Math.max(1, maxEncoded));
        int capacity = (int) Math.min(maxEncoded, StreamPayloads.RAW.equals(o.codec()) ? o.size() : WINDOW);
        s.streams.put(id, new Inbound(id, ref, o.codec(), maxEncoded, capacity, window));
        s.streamBytesInFlight += o.size();
        host.sendReliable(s, CoreMessages.STREAM_ACCEPT, new StreamAccept(id, (int) window), 0, Envelope.NONE);
    }

    void chunk(Session s, StreamChunk c) {
        Inbound in = s.streams.get(c.streamId());
        if (in == null || in.verifying != null) {
            return;
        }
        byte[] data = c.data();
        long end = c.offset() + data.length;
        String problem = c.offset() != in.received ? "bad_offset"
                : data.length > MAX_CHUNK || end > in.maxEncoded ? "too_large"
                : end > in.granted ? "window_exceeded"
                : null;
        if (problem != null) {
            drop(s, in);
            reject(s, in.streamId, problem);
            return;
        }
        if (end > in.buffer.length) {
            in.buffer = Arrays.copyOf(in.buffer, (int) Math.min(in.maxEncoded, Math.max(end, in.buffer.length * 2L)));
        }
        System.arraycopy(data, 0, in.buffer, in.received, data.length);
        in.received = (int) end;
        long left = in.granted - in.received;
        if (left < WINDOW / 2 && in.granted < in.maxEncoded) {
            int more = (int) Math.min(WINDOW, in.maxEncoded - in.granted);
            in.granted += more;
            host.sendReliable(s, CoreMessages.STREAM_WINDOW, new StreamWindow(in.streamId, more), 0, Envelope.NONE);
        }
    }

    void end(Session s, StreamEnd e) {
        Inbound in = s.streams.get(e.streamId());
        if (in == null || in.verifying != null) {
            return;
        }
        byte[] buffer = in.buffer;
        int length = in.received;
        String codec = in.codec;
        int size = (int) in.ref.size();
        String hex = in.ref.hex();
        in.verifying = CompletableFuture.supplyAsync(() -> {
            byte[] payload = StreamPayloads.decode(codec, buffer, length, size);
            if (payload == null) {
                return new Verified(null, "decode_failed");
            }
            return StreamPayloads.sha256Hex(payload).equals(hex)
                    ? new Verified(payload, "")
                    : new Verified(null, "hash_mismatch");
        }, worker);
        if (!verifying.contains(s)) {
            verifying.add(s);
        }
    }

    /** The sender abandoned a stream. */
    void peerReject(Session s, StreamReject r) {
        Inbound in = s.streams.get(r.streamId());
        if (in != null) {
            drop(s, in);
        }
    }

    /** Messages for mod-to-node streams; the mod sends none yet. */
    static BridgeException noOutbound() {
        return new BridgeException("invalid_message", "this mod sends no streams");
    }

    /** Collects finished verifications: cache the verified payloads, reject the rest. */
    void tick() {
        for (int i = verifying.size() - 1; i >= 0; i--) {
            Session s = verifying.get(i);
            boolean pending = false;
            Iterator<Inbound> it = s.streams.values().iterator();
            while (it.hasNext()) {
                Inbound in = it.next();
                CompletableFuture<Verified> f = in.verifying;
                if (f == null) {
                    continue;
                }
                if (!f.isDone()) {
                    pending = true;
                    continue;
                }
                it.remove();
                s.streamBytesInFlight -= in.ref.size();
                Verified v = f.isCompletedExceptionally() ? new Verified(null, "decode_failed") : f.join();
                if (s.ended) {
                    continue;
                }
                byte[] payload = v.payload();
                if (payload == null) {
                    reject(s, in.streamId, v.reason());
                } else {
                    cache.put(in.ref.hex(), payload);
                }
            }
            if (!pending) {
                verifying.remove(i);
            }
        }
    }

    void sessionEnded(Session s) {
        s.streams.clear();
        s.streamBytesInFlight = 0;
        verifying.remove(s);
    }

    private static void drop(Session s, Inbound in) {
        s.streams.remove(in.streamId);
        s.streamBytesInFlight -= in.ref.size();
    }

    private void reject(Session s, int streamId, String reason) {
        host.sendReliable(s, CoreMessages.STREAM_REJECT, new StreamReject(streamId, reason), 0, Envelope.NONE);
    }
}
