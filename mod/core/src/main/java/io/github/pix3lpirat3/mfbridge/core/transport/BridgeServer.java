package io.github.pix3lpirat3.mfbridge.core.transport;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.api.thread.NetThread;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireException;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireReader;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The WebSocket listener. One Netty event loop thread accepts, upgrades
 * and decodes frame headers; no protocol logic runs here.
 *
 * <p>TLS is required off loopback. The first byte of every connection decides the
 * transport: a TLS handshake record ({@code 0x16}) gets an {@link SslHandler}; anything else is plain
 * HTTP, accepted only from loopback peers while plain loopback is allowed. A plain HTTP request never
 * starts with {@code 0x16}, so the two cannot be confused.
 *
 * <p>Sockets that have not authenticated yet are counted from the moment they are accepted; past
 * the limit new ones are closed at once, so unknown peers cannot pile up.
 */
public final class BridgeServer implements AutoCloseable {
    /** WebSocket path. */
    public static final String PATH = "/bridge/v1";

    private static final byte[] EMPTY = new byte[0];
    private static final AtomicLong CONNECTION_IDS = new AtomicLong();
    private static final int TLS_HANDSHAKE_RECORD = 0x16;
    /** A socket that has not completed the WebSocket upgrade by then is closed. */
    private static final long UPGRADE_TIMEOUT_MS = 10_000;
    private static final AttributeKey<Boolean> UPGRADED = AttributeKey.valueOf("mfbridge.upgraded");

    /** Receives connection lifecycle events on the Netty thread; must only enqueue. */
    public interface Listener {
        /** A WebSocket upgrade completed. */
        @NetThread
        void connected(NetConnection connection);

        /** The channel closed. Called at most once per connection, after {@link #connected}. */
        @NetThread
        void disconnected(NetConnection connection);
    }

    private final EventLoopGroup group;
    private final Channel serverChannel;
    private final int port;

    private BridgeServer(EventLoopGroup group, Channel serverChannel, int port) {
        this.group = group;
        this.serverChannel = serverChannel;
        this.port = port;
    }

    /**
     * Binds the listener. Fails fast if the port is taken; never picks another port.
     *
     * @param bindAddress interface to bind
     * @param port port, or 0 for an ephemeral port
     * @param limits frame, queue and unauthenticated-peer limits
     * @param allowPlainLoopback accept plain WebSocket from loopback peers
     * @param tls server context for TLS connections, or null to accept plain loopback only
     * @throws IOException when binding fails
     */
    @ClientThread
    public static BridgeServer start(
            String bindAddress,
            int port,
            TransportLimits limits,
            boolean allowPlainLoopback,
            @Nullable SslContext tls,
            Listener listener,
            Logger log)
            throws IOException {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(
                1, new DefaultThreadFactory("mfbridge-net", true), NioIoHandler.newFactory());
        WebSocketServerProtocolConfig wsConfig = WebSocketServerProtocolConfig.newBuilder()
                .websocketPath(PATH)
                // The gate has already rejected other paths; this only lets a query string through.
                .checkStartsWith(true)
                .maxFramePayloadLength(limits.maxFrameBytes())
                .allowExtensions(false)
                .handleCloseFrames(true)
                .dropPongFrames(true)
                .build();
        AtomicInteger unauthenticated = new AtomicInteger();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(1 << 20, 4 << 20))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        InetSocketAddress remote = ch.remoteAddress();
                        boolean loopback = remote != null && remote.getAddress() != null
                                && remote.getAddress().isLoopbackAddress();
                        boolean plainAllowed = loopback && allowPlainLoopback;
                        if (!plainAllowed && tls == null) {
                            ch.close();
                            return;
                        }
                        if (unauthenticated.incrementAndGet() > limits.maxUnauthenticated()) {
                            unauthenticated.decrementAndGet();
                            ch.close();
                            return;
                        }
                        // The slot is given back once: when the peer authenticates, or when it leaves first.
                        AtomicBoolean held = new AtomicBoolean(true);
                        Runnable release = () -> {
                            if (held.compareAndSet(true, false)) {
                                unauthenticated.decrementAndGet();
                            }
                        };
                        ch.closeFuture().addListener(f -> release.run());
                        String address = remote == null || remote.getAddress() == null
                                ? "unknown"
                                : remote.getAddress().getHostAddress() + ":" + remote.getPort();
                        Pipeline pipeline = new Pipeline(wsConfig, limits, release, listener, loopback, address, log);
                        ch.pipeline().addLast(new TransportSniffer(tls, plainAllowed, pipeline));
                        ch.eventLoop().schedule(() -> {
                            if (!Boolean.TRUE.equals(ch.attr(UPGRADED).get())) {
                                ch.close();
                            }
                        }, UPGRADE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    }
                });
        ChannelFuture bind = bootstrap.bind(bindAddress, port).awaitUninterruptibly();
        if (!bind.isSuccess()) {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(2, TimeUnit.SECONDS);
            Throwable cause = bind.cause();
            throw new IOException("Cannot listen on " + bindAddress + ":" + port + ": " + cause, cause);
        }
        int bound = ((InetSocketAddress) bind.channel().localAddress()).getPort();
        return new BridgeServer(group, bind.channel(), bound);
    }

    /** Returns the bound port. */
    public int port() {
        return port;
    }

    /** Stops listening and shuts the event loop down, waiting up to a few seconds. */
    @Override
    public void close() {
        serverChannel.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(3, TimeUnit.SECONDS);
    }

    /** What every connection gets after the transport is chosen. */
    private record Pipeline(
            WebSocketServerProtocolConfig wsConfig,
            TransportLimits limits,
            Runnable onAuthenticated,
            Listener listener,
            boolean loopback,
            String address,
            Logger log) {
        @NetThread
        void install(ChannelHandlerContext ctx, boolean secure) {
            ctx.pipeline()
                    .addLast(new HttpServerCodec())
                    .addLast(new HttpObjectAggregator(16 * 1024))
                    .addLast(new HttpGate())
                    .addLast(new WebSocketServerProtocolHandler(wsConfig))
                    .addLast(new WebSocketFrameAggregator(limits.maxFrameBytes()))
                    .addLast(new FrameHandler(listener, loopback, secure, address, limits, onAuthenticated, log));
        }
    }

    /**
     * Looks at the first byte and installs TLS or plain HTTP, then removes itself; the bytes it has
     * buffered are passed on to the new handlers.
     */
    @NetThread
    private static final class TransportSniffer extends ByteToMessageDecoder {
        private final @Nullable SslContext tls;
        private final boolean plainAllowed;
        private final Pipeline next;

        TransportSniffer(@Nullable SslContext tls, boolean plainAllowed, Pipeline next) {
            this.tls = tls;
            this.plainAllowed = plainAllowed;
            this.next = next;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (!in.isReadable()) {
                return;
            }
            boolean handshake = in.getUnsignedByte(in.readerIndex()) == TLS_HANDSHAKE_RECORD;
            SslContext context = tls;
            if (handshake && context != null) {
                ctx.pipeline().addLast(context.newHandler(ctx.alloc()));
                next.install(ctx, true);
            } else if (!handshake && plainAllowed) {
                next.install(ctx, false);
            } else {
                // Plain connections are refused off loopback, and on it when TLS is required.
                in.skipBytes(in.readableBytes());
                ctx.close();
                return;
            }
            ctx.pipeline().remove(this);
        }
    }

    /** Rejects upgrades with an Origin header (403) and any other path (404). */
    @NetThread
    private static final class HttpGate extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpRequest req) {
                HttpResponseStatus reject = null;
                if (req.headers().contains(HttpHeaderNames.ORIGIN)) {
                    reject = HttpResponseStatus.FORBIDDEN;
                } else if (!PATH.equals(new QueryStringDecoder(req.uri()).path())) {
                    reject = HttpResponseStatus.NOT_FOUND;
                }
                if (reject != null) {
                    ReferenceCountUtil.release(req);
                    FullHttpResponse res =
                            new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, reject, Unpooled.EMPTY_BUFFER);
                    res.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                    res.headers().set(HttpHeaderNames.CONNECTION, "close");
                    ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
                    return;
                }
            }
            ctx.fireChannelRead(msg);
        }
    }

    /** Decodes frame headers and queues frames for the client thread. */
    @NetThread
    private static final class FrameHandler extends ChannelInboundHandlerAdapter {
        private final Listener listener;
        private final boolean loopback;
        private final boolean secure;
        private final String address;
        private final TransportLimits limits;
        private final Runnable onAuthenticated;
        private final Logger log;
        private final WireReader reader = new WireReader(Unpooled.EMPTY_BUFFER);
        private @Nullable NetConnection connection;

        FrameHandler(Listener listener, boolean loopback, boolean secure, String address, TransportLimits limits,
                Runnable onAuthenticated, Logger log) {
            this.listener = listener;
            this.loopback = loopback;
            this.secure = secure;
            this.address = address;
            this.limits = limits;
            this.onAuthenticated = onAuthenticated;
            this.log = log;
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete && connection == null) {
                ctx.channel().attr(UPGRADED).set(Boolean.TRUE);
                connection = new NetConnection(CONNECTION_IDS.incrementAndGet(), ctx.channel(), loopback, secure, address,
                        limits, onAuthenticated);
                listener.connected(connection);
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                NetConnection conn = connection;
                if (msg instanceof BinaryWebSocketFrame frame && conn != null) {
                    conn.offer(decode(frame.content()));
                } else if (msg instanceof TextWebSocketFrame) {
                    // Text frames close the connection with 1003.
                    ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.INVALID_MESSAGE_TYPE))
                            .addListener(ChannelFutureListener.CLOSE);
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        private InboundFrame decode(ByteBuf content) {
            try {
                Envelope env = Envelope.read(reader.source(content));
                byte[] payload = content.isReadable() ? new byte[content.readableBytes()] : EMPTY;
                content.readBytes(payload);
                return new InboundFrame(env, payload, null);
            } catch (WireException e) {
                return new InboundFrame(null, EMPTY, e.getMessage());
            } finally {
                reader.source(Unpooled.EMPTY_BUFFER);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            NetConnection conn = connection;
            if (conn != null) {
                conn.markClosed();
                listener.disconnected(conn);
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.debug("Closing bridge connection {} after error: {}", address, cause.toString());
            ctx.close();
        }
    }
}
