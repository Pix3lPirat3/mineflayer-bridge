package io.github.pix3lpirat3.mfbridge.protocol.wire;

/** Delivery class. */
public enum Delivery {
    /** Connection and handshake traffic; never sequenced. */
    CONTROL,
    /** Sequenced per session and replayed on resume. */
    RELIABLE,
    /** Coalesced by type per session; never logged. */
    LATEST,
    /** Dropped under backpressure. */
    UNRELIABLE
}
