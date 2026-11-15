package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import org.jspecify.annotations.Nullable;

/**
 * One queued outbound message: header fields plus the encoded payload. The {@code ack} field is
 * filled in at flush time from the session's current state, so replays never carry stale acks.
 *
 * @param flags RESPONSE and ERROR bits; RELIABLE, HAS_ACK and HAS_REQ are derived
 * @param type wire type id
 * @param sessionId wire session id on the connection the frame is written to
 * @param session session whose ack to attach, or null for connection frames
 * @param seq sequence number, or {@link Envelope#NONE}
 * @param req request id, or {@link Envelope#NONE}
 * @param payload encoded payload
 */
record OutFrame(int flags, int type, int sessionId, @Nullable Session session, long seq, long req, byte[] payload) {
    OutFrame withSessionId(int id) {
        return new OutFrame(flags, type, id, session, seq, req, payload);
    }
}
