package io.github.pix3lpirat3.mfbridge.core.transport;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.api.thread.NetThread;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * One upgraded WebSocket connection as seen from both threads. The Netty thread offers frames; the
 * client thread polls them and writes batches back. The inbound queue is a lock-free MPSC queue;
 * when it grows past its cap, in frames or in payload bytes, the socket stops reading until the
 * client thread has drained half of it. Until the peer is authenticated it may only send a few
 * small frames; more closes the connection.
 *
 * <p>A virtual connection ({@link #virtual}) has no socket: frames are delivered with {@link
 * #deliver} and writes go to a sink. Replays of recordings run on one.
 */
@AnyThread
public final class NetConnection {
    private static final AtomicLong VIRTUAL_IDS = new AtomicLong();

    private final long id;
    private final @Nullable Channel channel;
    private final @Nullable Consumer<ByteBuf> sink;
    private final boolean loopback;
    private final boolean secure;
    private final String remoteAddress;
    private final TransportLimits limits;
    private final Runnable onAuthenticated;
    private final ConcurrentLinkedQueue<InboundFrame> inbound = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicLong queuedBytes = new AtomicLong();
    private volatile boolean closed;
    private volatile boolean authenticated;
    /** Whether reading is paused because the queue is full. */
    private volatile boolean paused;
    // Frames and bytes received before authentication; Netty thread only.
    private int preAuthFrames;
    private long preAuthBytes;

    /**
     * Wraps an upgraded channel.
     *
     * @param onAuthenticated run once, on any thread, when the peer is authenticated
     */
    NetConnection(long id, Channel channel, boolean loopback, boolean secure, String remoteAddress, TransportLimits limits,
            Runnable onAuthenticated) {
        this(id, channel, null, loopback, secure, remoteAddress, limits, onAuthenticated);
    }

    private NetConnection(long id, @Nullable Channel channel, @Nullable Consumer<ByteBuf> sink, boolean loopback,
            boolean secure, String remoteAddress, TransportLimits limits, Runnable onAuthenticated) {
        this.id = id;
        this.channel = channel;
        this.sink = sink;
        this.loopback = loopback;
        this.secure = secure;
        this.remoteAddress = remoteAddress;
        this.limits = limits;
        this.onAuthenticated = onAuthenticated;
    }

    /**
     * Creates a connection without a socket. It counts as a loopback, non-TLS peer; it never applies
     * backpressure and is closed only by {@link #abort()} or {@link #markVirtualClosed()}.
     *
     * @param remoteAddress shown in diagnostics, for example {@code replay:<file>}
     * @param sink receives every frame written to the connection and must release it
     */
    public static NetConnection virtual(String remoteAddress, Consumer<ByteBuf> sink) {
        NetConnection c = new NetConnection(-VIRTUAL_IDS.incrementAndGet(), null, sink, true, false, remoteAddress,
                TransportLimits.UNLIMITED, () -> {});
        c.authenticated = true;
        return c;
    }

    /**
     * Marks the peer authenticated: the limits on what an unknown peer may send no longer apply.
     * Calling it again does nothing.
     */
    @ClientThread
    public void authenticated() {
        if (!authenticated) {
            authenticated = true;
            onAuthenticated.run();
        }
    }

    /** Returns whether this is a virtual connection. */
    public boolean isVirtual() {
        return channel == null;
    }

    /**
     * Queues a frame on a virtual connection as if it had been received.
     *
     * @throws IllegalStateException on a socket connection
     */
    @ClientThread
    public void deliver(InboundFrame frame) {
        if (channel != null) {
            throw new IllegalStateException("deliver() is for virtual connections");
        }
        inbound.add(frame);
        queuedBytes.addAndGet(frame.payload().length);
        queued.incrementAndGet();
    }

    /** Marks a virtual connection closed, as a socket close would. */
    public void markVirtualClosed() {
        if (channel == null) {
            closed = true;
        }
    }

    /** Returns a process-unique id for diagnostics; negative for virtual connections. */
    public long id() {
        return id;
    }

    /** Returns whether the peer address is a loopback address. */
    public boolean loopback() {
        return loopback;
    }

    /** Returns whether the connection runs over TLS. */
    public boolean secure() {
        return secure;
    }

    /** Returns the peer address as {@code host:port}. */
    public String remoteAddress() {
        return remoteAddress;
    }

    @NetThread
    void offer(InboundFrame frame) {
        Channel ch = channel;
        if (!authenticated) {
            preAuthBytes += frame.payload().length;
            if (++preAuthFrames > limits.preAuthFrames() || preAuthBytes > limits.preAuthBytes()) {
                // An unknown peer gets a handshake's worth of frames and no more.
                if (ch != null) {
                    ch.close();
                }
                return;
            }
        }
        inbound.add(frame);
        long bytes = queuedBytes.addAndGet(frame.payload().length);
        if ((queued.incrementAndGet() >= limits.maxQueuedFrames() || bytes >= limits.maxQueuedBytes()) && ch != null) {
            // Set the flag first, then look again: a poll that emptied the queue in between saw
            // either the flag, and asks for a resume, or is seen here.
            paused = true;
            ch.config().setAutoRead(false);
            resume();
        }
    }

    private boolean drained() {
        return queued.get() <= limits.maxQueuedFrames() / 2 && queuedBytes.get() <= limits.maxQueuedBytes() / 2;
    }

    /** Reads again once the queue is half drained. Reading is only switched on the Netty thread. */
    @NetThread
    private void resume() {
        Channel ch = channel;
        if (paused && drained() && ch != null) {
            paused = false;
            ch.config().setAutoRead(true);
        }
    }

    /** Returns the next frame without removing it, or null. */
    @ClientThread
    public @Nullable InboundFrame peek() {
        return inbound.peek();
    }

    /** Removes and returns the next frame, or null. Resumes reading once the queue is half drained. */
    @ClientThread
    public @Nullable InboundFrame poll() {
        InboundFrame f = inbound.poll();
        if (f == null) {
            return null;
        }
        queuedBytes.addAndGet(-f.payload().length);
        queued.decrementAndGet();
        Channel ch = channel;
        if (paused && ch != null && drained()) {
            ch.eventLoop().execute(this::resume);
        }
        return f;
    }

    /** Returns the payload bytes of the queued inbound frames. */
    public long queuedBytes() {
        return queuedBytes.get();
    }

    /** Returns the number of queued inbound frames. */
    public int queued() {
        return queued.get();
    }

    @NetThread
    void markClosed() {
        closed = true;
    }

    /** Returns whether the channel has closed. Frames may still be queued. */
    public boolean isClosed() {
        return closed;
    }

    /** Returns whether the socket is below its write-buffer high watermark. */
    public boolean isWritable() {
        Channel ch = channel;
        return ch == null || ch.isWritable();
    }

    /** Returns the allocator for outbound buffers. */
    public ByteBufAllocator alloc() {
        Channel ch = channel;
        return ch == null ? UnpooledByteBufAllocator.DEFAULT : ch.alloc();
    }

    /**
     * Writes encoded frames in order and flushes once, on the event loop. Ownership of the buffers
     * passes to Netty.
     *
     * @param frames encoded envelopes, one per WebSocket frame
     * @param count number of leading entries of {@code frames} to write
     * @param closeAfter send a normal close frame and close the channel after the frames
     */
    @ClientThread
    public void write(ByteBuf[] frames, int count, boolean closeAfter) {
        Channel channel = this.channel;
        if (channel == null) {
            Consumer<ByteBuf> s = sink;
            for (int i = 0; i < count; i++) {
                if (s != null) {
                    s.accept(frames[i]);
                } else {
                    frames[i].release();
                }
            }
            if (closeAfter) {
                closed = true;
            }
            return;
        }
        channel.eventLoop().execute(() -> {
            for (int i = 0; i < count; i++) {
                channel.write(new BinaryWebSocketFrame(frames[i]), channel.voidPromise());
            }
            if (closeAfter) {
                channel.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE))
                        .addListener(ChannelFutureListener.CLOSE);
            } else {
                channel.flush();
            }
        });
    }

    /** Closes the channel without a close handshake. */
    public void abort() {
        Channel ch = channel;
        if (ch == null) {
            closed = true;
        } else {
            ch.close();
        }
    }
}
