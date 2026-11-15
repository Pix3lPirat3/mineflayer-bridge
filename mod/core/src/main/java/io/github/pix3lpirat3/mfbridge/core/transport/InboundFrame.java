package io.github.pix3lpirat3.mfbridge.core.transport;

import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import org.jspecify.annotations.Nullable;

/**
 * One received frame, decoded up to the payload on the Netty thread.
 *
 * @param envelope the decoded header, or null when the header was malformed
 * @param payload the payload bytes after the header, owned by the receiver
 * @param error why the header could not be decoded, or null when {@code envelope} is present
 */
public record InboundFrame(@Nullable Envelope envelope, byte[] payload, @Nullable String error) {}
