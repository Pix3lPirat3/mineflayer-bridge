package io.github.pix3lpirat3.mfbridge.core;

/**
 * Diagnostic counters of one live or detached session, for the inspector and tests.
 *
 * @param sessionId current per-connection session id
 * @param ownerPrefix owned key prefix
 * @param displayName display name
 * @param connected whether the session is on a live connection (false while in the resume window)
 * @param superseded whether a newer session took over the identity
 * @param lastAppliedInbound highest plugin {@code seq} applied
 * @param lastOutboundSeq highest mod {@code seq} sent, 0 when none
 * @param outboundLogEntries unacknowledged reliable messages kept for replay
 * @param outboundLogBytes payload bytes of those messages
 * @param logOverflowed whether the log cap was exceeded since the peer last caught up
 */
public record SessionStats(
        int sessionId,
        String ownerPrefix,
        String displayName,
        boolean connected,
        boolean superseded,
        long lastAppliedInbound,
        long lastOutboundSeq,
        int outboundLogEntries,
        long outboundLogBytes,
        boolean logOverflowed) {}
