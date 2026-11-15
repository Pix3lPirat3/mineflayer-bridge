package io.github.pix3lpirat3.mfbridge.api.event;

/** Event types posted by the bridge core on the client thread. */
public final class BridgeEvents {
    /**
     * A session's lifecycle changed.
     *
     * @param ownerPrefix the session's owned key prefix, for example {@code bot:<identity>/}
     * @param displayName the session's display name
     * @param controller true for controller sessions, false for bot sessions
     * @param reason why the session ended, or empty for {@link #SESSION_OPENED} and {@link
     *     #SESSION_RESUMED}
     */
    public record SessionEvent(String ownerPrefix, String displayName, boolean controller, String reason) {}

    /** A new session was opened (not a resume). */
    public static final EventType<SessionEvent> SESSION_OPENED =
            EventType.create("core:session.opened", SessionEvent.class);

    /** A detached session was resumed on a new connection. */
    public static final EventType<SessionEvent> SESSION_RESUMED =
            EventType.create("core:session.resumed", SessionEvent.class);

    /** A session ended for good: closed, superseded objects dropped, or its resume window expired. */
    public static final EventType<SessionEvent> SESSION_ENDED =
            EventType.create("core:session.ended", SessionEvent.class);

    private BridgeEvents() {}
}
