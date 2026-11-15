package io.github.pix3lpirat3.mfbridge.api.event;

/** Listener order: {@link #HIGHEST} runs first. Listeners of equal priority run in subscription order. */
public enum EventPriority {
    /** Runs first, for example to cancel an event before anyone reacts. */
    HIGHEST,
    /** Runs before normal listeners. */
    HIGH,
    /** Default priority. */
    NORMAL,
    /** Runs after normal listeners. */
    LOW,
    /** Runs last, for example to observe the final outcome. */
    LOWEST
}
