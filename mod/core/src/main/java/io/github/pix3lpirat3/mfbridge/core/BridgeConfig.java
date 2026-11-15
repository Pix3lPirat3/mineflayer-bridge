package io.github.pix3lpirat3.mfbridge.core;

import io.github.pix3lpirat3.mfbridge.api.BridgeExtension;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Immutable bridge configuration. Build with {@link #builder(Path)}, which fills in the defaults.
 *
 * @param bindAddress listen address; {@code 127.0.0.1} by default, remote access is opt-in
 * @param port listen port; 25590 by default, 0 picks an ephemeral port (tests)
 * @param allowPlainLoopback accept plain {@code ws://} from loopback peers; when off, loopback peers
 *     must use TLS like everyone else
 * @param advertiseHost host put into pairing URIs, or null to pick one from the network interfaces
 * @param configDir game config directory; bridge files live in {@code <configDir>/mineflayer_bridge}
 * @param maxFrameBytes largest accepted WebSocket frame in bytes; larger frames close with 1009
 * @param maxObjectsPerSession object quota per device, over all of its sessions
 * @param maxBlobBytes blob size cap advertised in {@code welcome}, in bytes
 * @param messagesPerSecond message budget of a device, over all of its connections
 * @param bytesPerSecond byte budget of a device, over all of its connections
 * @param streamBytesInFlight stream window budget of a device, in bytes; also the cap on the
 *     declared size of its open inbound streams
 * @param blobCacheBytes bound of the on-disk blob cache, in bytes (256 MiB by default)
 * @param resumeWindowMs how long a dropped session's objects are kept for resume, in ms
 * @param heartbeatMs idle time after which a {@code ping} is sent, in ms
 * @param deadPeerMs inbound silence after which a peer is considered dead, in ms
 * @param ackDelayMs idle time after which an empty {@code ack} is sent for unacknowledged input, in
 *     ms
 * @param outboundLogCapBytes cap of the per-session outbound replay log, in payload bytes
 * @param maxQueuedFrames inbound frames queued per connection before the socket stops reading
 * @param attachPolicy attach policy used until the player picks another
 * @param clock time source for protocol timers
 * @param extensions mod-side extensions registered at start, in order
 * @param askLocalApproval whether new local devices ask the player before they connect; off by
 *     default, so local devices are approved automatically
 * @param cancelCooldownsMs how long a bot's unasked surfaces are refused after the player dismissed
 *     one, in ms: the first entry after one dismissal, the next after two in a row, and so on
 * @param cancelResetMs time without a dismissal after which the count starts over, in ms
 * @param solicitedWindowMs how long after the player ran something of a bot its surfaces count as
 *     asked for, in ms
 * @param maxQueuedInputs picks, prompts, forms and world menus one session may have waiting
 * @param maxInputTimeoutMs longest timeout an input request may ask for, in ms; longer ones are cut
 * @param chatPromptWindowMs how long a chat prompt may wait for the player's next chat line, in ms
 * @param maxQueuedBytes inbound payload bytes queued per connection before the socket stops reading
 * @param preAuthFrames frames a connection may send before it is authenticated
 * @param preAuthBytes payload bytes a connection may send before it is authenticated
 * @param maxUnauthenticatedConnections connections that may be waiting to authenticate at once
 * @param maxSessionsPerDevice live sessions one device may hold
 * @param maxSharedObjects shared objects kept in total
 * @param maxSharedBytes data bytes of all shared objects together
 * @param maxOpenStores world stores kept open; the least recently used one is closed beyond it
 * @param maxOutboundBytes bytes queued for a peer that is not reading before it is disconnected
 * @param extensionBufferMs how long extensions keep an owner's buffers after its session ended, in ms
 */
public record BridgeConfig(
        String bindAddress,
        int port,
        boolean allowPlainLoopback,
        @Nullable String advertiseHost,
        Path configDir,
        int maxFrameBytes,
        int maxObjectsPerSession,
        int maxBlobBytes,
        int messagesPerSecond,
        int bytesPerSecond,
        int streamBytesInFlight,
        long blobCacheBytes,
        long resumeWindowMs,
        long heartbeatMs,
        long deadPeerMs,
        long ackDelayMs,
        int outboundLogCapBytes,
        int maxQueuedFrames,
        AttachPolicy attachPolicy,
        BridgeClock clock,
        List<BridgeExtension> extensions,
        boolean askLocalApproval,
        List<Long> cancelCooldownsMs,
        long cancelResetMs,
        long solicitedWindowMs,
        int maxQueuedInputs,
        long maxInputTimeoutMs,
        long chatPromptWindowMs,
        int maxQueuedBytes,
        int preAuthFrames,
        int preAuthBytes,
        int maxUnauthenticatedConnections,
        int maxSessionsPerDevice,
        int maxSharedObjects,
        long maxSharedBytes,
        int maxOpenStores,
        int maxOutboundBytes,
        long extensionBufferMs) {

    /** Copies the lists and validates ranges. */
    public BridgeConfig {
        extensions = List.copyOf(extensions);
        cancelCooldownsMs = List.copyOf(cancelCooldownsMs);
        for (long ms : cancelCooldownsMs) {
            if (ms < 0) {
                throw new IllegalArgumentException("timing out of range");
            }
        }
        if (cancelResetMs < 0 || solicitedWindowMs < 0 || maxInputTimeoutMs < 1 || chatPromptWindowMs < 1
                || extensionBufferMs < 0) {
            throw new IllegalArgumentException("timing out of range");
        }
        if (maxQueuedInputs < 1 || maxQueuedBytes < 1 || preAuthFrames < 2 || preAuthBytes < 1024
                || maxUnauthenticatedConnections < 1 || maxSessionsPerDevice < 1 || maxSharedObjects < 0
                || maxSharedBytes < 0 || maxOpenStores < 2 || maxOutboundBytes < 1) {
            throw new IllegalArgumentException("limits out of range");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (maxFrameBytes < 1024 || outboundLogCapBytes < 0 || maxQueuedFrames < 1 || maxObjectsPerSession < 1
                || maxBlobBytes < 0 || streamBytesInFlight < 0 || blobCacheBytes < 1) {
            throw new IllegalArgumentException("limits out of range");
        }
        if (resumeWindowMs < 0 || heartbeatMs < 1 || deadPeerMs < heartbeatMs || ackDelayMs < 1) {
            throw new IllegalArgumentException("timing out of range");
        }
    }

    /** Returns the {@code <configDir>/mineflayer_bridge} directory. */
    public Path bridgeDir() {
        return configDir.resolve("mineflayer_bridge");
    }

    /** Starts a builder with the defaults for the given config directory. */
    public static Builder builder(Path configDir) {
        return new Builder(configDir);
    }

    /** Mutable builder for {@link BridgeConfig}. Not thread-safe. */
    public static final class Builder {
        private final Path configDir;
        private String bindAddress = "127.0.0.1";
        private int port = 25590;
        private boolean allowPlainLoopback = true;
        private @Nullable String advertiseHost;
        private int maxFrameBytes = 1 << 20;
        private int maxObjectsPerSession = 20_000;
        private int maxBlobBytes = 32 << 20;
        private int messagesPerSecond = 2_000;
        private int bytesPerSecond = 8 << 20;
        private int streamBytesInFlight = 128 << 20;
        private long blobCacheBytes = 256L << 20;
        private long resumeWindowMs = 30_000;
        private long heartbeatMs = 5_000;
        private long deadPeerMs = 15_000;
        private long ackDelayMs = 250;
        private int outboundLogCapBytes = 4 << 20;
        private int maxQueuedFrames = 10_000;
        private AttachPolicy attachPolicy = AttachPolicy.AUTO;
        private BridgeClock clock = BridgeClock.SYSTEM;
        private final List<BridgeExtension> extensions = new ArrayList<>();
        private boolean askLocalApproval;
        private List<Long> cancelCooldownsMs = List.of(5_000L, 15_000L, 30_000L);
        private long cancelResetMs = 60_000;
        private long solicitedWindowMs = 5_000;
        private int maxQueuedInputs = 16;
        private long maxInputTimeoutMs = 3_600_000;
        private long chatPromptWindowMs = 60_000;
        private int maxQueuedBytes = 32 << 20;
        private int preAuthFrames = 8;
        private int preAuthBytes = 64 << 10;
        private int maxUnauthenticatedConnections = 16;
        private int maxSessionsPerDevice = 256;
        private int maxSharedObjects = 10_000;
        private long maxSharedBytes = 32L << 20;
        private int maxOpenStores = 8;
        private int maxOutboundBytes = 64 << 20;
        private long extensionBufferMs = 300_000;

        private Builder(Path configDir) {
            this.configDir = configDir;
        }

        /** Sets the listen address. */
        public Builder bindAddress(String value) {
            bindAddress = value;
            return this;
        }

        /** Sets the listen port; 0 picks an ephemeral port. */
        public Builder port(int value) {
            port = value;
            return this;
        }

        /** Sets whether plain {@code ws://} is accepted from loopback peers. */
        public Builder allowPlainLoopback(boolean value) {
            allowPlainLoopback = value;
            return this;
        }

        /** Sets the host advertised in pairing URIs; null or blank picks one automatically. */
        public Builder advertiseHost(@Nullable String value) {
            advertiseHost = value == null || value.isBlank() ? null : value.strip();
            return this;
        }

        /** Sets the largest accepted frame, in bytes. */
        public Builder maxFrameBytes(int value) {
            maxFrameBytes = value;
            return this;
        }

        /** Sets the object quota per device. */
        public Builder maxObjectsPerSession(int value) {
            maxObjectsPerSession = value;
            return this;
        }

        /** Sets the advertised blob size cap, in bytes. */
        public Builder maxBlobBytes(int value) {
            maxBlobBytes = value;
            return this;
        }

        /** Sets the advertised budgets: messages per second, bytes per second, stream bytes in flight. */
        public Builder budget(int messages, int bytes, int streamBytes) {
            messagesPerSecond = messages;
            bytesPerSecond = bytes;
            streamBytesInFlight = streamBytes;
            return this;
        }

        /** Sets the disk bound of the blob cache, in bytes. */
        public Builder blobCacheBytes(long value) {
            blobCacheBytes = value;
            return this;
        }

        /** Sets the resume window, in ms. */
        public Builder resumeWindowMs(long value) {
            resumeWindowMs = value;
            return this;
        }

        /** Sets the heartbeat idle time, in ms. */
        public Builder heartbeatMs(long value) {
            heartbeatMs = value;
            return this;
        }

        /** Sets the dead-peer silence, in ms. */
        public Builder deadPeerMs(long value) {
            deadPeerMs = value;
            return this;
        }

        /** Sets the idle delay before an empty ack, in ms. */
        public Builder ackDelayMs(long value) {
            ackDelayMs = value;
            return this;
        }

        /** Sets the per-session outbound log cap, in bytes. */
        public Builder outboundLogCapBytes(int value) {
            outboundLogCapBytes = value;
            return this;
        }

        /** Sets the inbound queue length per connection at which reading pauses. */
        public Builder maxQueuedFrames(int value) {
            maxQueuedFrames = value;
            return this;
        }

        /** Sets the initial attach policy. */
        public Builder attachPolicy(AttachPolicy value) {
            attachPolicy = value;
            return this;
        }

        /** Sets the clock for protocol timers. */
        public Builder clock(BridgeClock value) {
            clock = value;
            return this;
        }

        /** Sets whether new local devices ask the player before they connect. */
        public Builder askLocalApproval(boolean value) {
            askLocalApproval = value;
            return this;
        }

        /**
         * Sets the cooldowns after the player dismisses a bot's surface, and the quiet time after
         * which the count of dismissals starts over, in ms.
         */
        public Builder cancelCooldowns(List<Long> cooldownsMs, long resetMs) {
            cancelCooldownsMs = cooldownsMs;
            cancelResetMs = resetMs;
            return this;
        }

        /** Sets how long after the player ran something of a bot its surfaces count as asked for, in ms. */
        public Builder solicitedWindowMs(long value) {
            solicitedWindowMs = value;
            return this;
        }

        /** Sets the waiting input requests per session and the longest timeout one may ask for, in ms. */
        public Builder inputLimits(int queued, long timeoutMs) {
            maxQueuedInputs = queued;
            maxInputTimeoutMs = timeoutMs;
            return this;
        }

        /** Sets how long a chat prompt may wait for the player's next chat line, in ms. */
        public Builder chatPromptWindowMs(long value) {
            chatPromptWindowMs = value;
            return this;
        }

        /** Sets the inbound payload bytes queued per connection at which reading pauses. */
        public Builder maxQueuedBytes(int value) {
            maxQueuedBytes = value;
            return this;
        }

        /**
         * Sets what a connection may do before it is authenticated: frames and payload bytes it may
         * send, and how many such connections may be open at once.
         */
        public Builder preAuth(int frames, int bytes, int connections) {
            preAuthFrames = frames;
            preAuthBytes = bytes;
            maxUnauthenticatedConnections = connections;
            return this;
        }

        /** Sets the live sessions one device may hold. */
        public Builder maxSessionsPerDevice(int value) {
            maxSessionsPerDevice = value;
            return this;
        }

        /** Sets the total number and data bytes of shared objects. */
        public Builder sharedLimits(int objects, long bytes) {
            maxSharedObjects = objects;
            maxSharedBytes = bytes;
            return this;
        }

        /** Sets how many world stores stay open. */
        public Builder maxOpenStores(int value) {
            maxOpenStores = value;
            return this;
        }

        /** Sets the bytes queued for a peer that is not reading before it is disconnected. */
        public Builder maxOutboundBytes(int value) {
            maxOutboundBytes = value;
            return this;
        }

        /** Sets how long extensions keep an owner's buffers after its session ended, in ms. */
        public Builder extensionBufferMs(long value) {
            extensionBufferMs = value;
            return this;
        }

        /** Adds a mod-side extension. */
        public Builder extension(BridgeExtension value) {
            extensions.add(value);
            return this;
        }

        /** Builds the configuration. */
        public BridgeConfig build() {
            return new BridgeConfig(
                    bindAddress,
                    port,
                    allowPlainLoopback,
                    advertiseHost,
                    configDir,
                    maxFrameBytes,
                    maxObjectsPerSession,
                    maxBlobBytes,
                    messagesPerSecond,
                    bytesPerSecond,
                    streamBytesInFlight,
                    blobCacheBytes,
                    resumeWindowMs,
                    heartbeatMs,
                    deadPeerMs,
                    ackDelayMs,
                    outboundLogCapBytes,
                    maxQueuedFrames,
                    attachPolicy,
                    clock,
                    extensions,
                    askLocalApproval,
                    cancelCooldownsMs,
                    cancelResetMs,
                    solicitedWindowMs,
                    maxQueuedInputs,
                    maxInputTimeoutMs,
                    chatPromptWindowMs,
                    maxQueuedBytes,
                    preAuthFrames,
                    preAuthBytes,
                    maxUnauthenticatedConnections,
                    maxSessionsPerDevice,
                    maxSharedObjects,
                    maxSharedBytes,
                    maxOpenStores,
                    maxOutboundBytes,
                    extensionBufferMs);
        }
    }
}
