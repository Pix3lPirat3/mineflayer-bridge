package io.github.pix3lpirat3.mfbridge.core;

/** The player's answer to a device approval request. */
public enum ApprovalDecision {
    /** Allow and remember the device in {@code devices.json}. */
    ALLOW,
    /** Allow this connection only; the next connection asks again. */
    ALLOW_ONCE,
    /** Refuse; the plugin gets {@code goodbye{unauthorized, retry: false}}. */
    DENY
}
