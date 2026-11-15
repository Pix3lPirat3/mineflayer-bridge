package io.github.pix3lpirat3.mfbridge.core;

/** Which bots are attached when the player has made no explicit choice. */
public enum AttachPolicy {
    /** Attach bots whose world matches the player's. The default. */
    AUTO,
    /** Attach every connected bot. */
    ALWAYS,
    /** Attach only bots the player attached explicitly. */
    MANUAL
}
