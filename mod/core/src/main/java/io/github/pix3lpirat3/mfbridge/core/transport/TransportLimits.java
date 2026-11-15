package io.github.pix3lpirat3.mfbridge.core.transport;

/**
 * What the listener lets a peer send and hold.
 *
 * @param maxFrameBytes largest accepted frame in bytes; larger frames close with 1009
 * @param maxQueuedFrames inbound frames queued per connection before reading pauses
 * @param maxQueuedBytes inbound payload bytes queued per connection before reading pauses
 * @param preAuthFrames frames a connection may send before it is authenticated
 * @param preAuthBytes payload bytes a connection may send before it is authenticated
 * @param maxUnauthenticated connections that may be waiting to authenticate at once; more are
 *     closed as they arrive
 */
public record TransportLimits(int maxFrameBytes, int maxQueuedFrames, int maxQueuedBytes, int preAuthFrames,
        int preAuthBytes, int maxUnauthenticated) {
    /** No limits, for connections without a socket. */
    static final TransportLimits UNLIMITED = new TransportLimits(Integer.MAX_VALUE, Integer.MAX_VALUE,
            Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
}
