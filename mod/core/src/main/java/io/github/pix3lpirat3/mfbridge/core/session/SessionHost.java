package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.core.decl.DeclarationSnapshot;
import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import io.github.pix3lpirat3.mfbridge.core.remote.Quotas;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectTraits;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * What the engine gives the services that sit on top of it (input, chat, screens, containers and
 * the rest): the clock, sending to a session, who is attached and visible, and permissions. A
 * service depends on this and on nothing else of the engine. Client thread only, like the engine.
 */
interface SessionHost {
    /** Returns the bridge clock in ms; every protocol timer is measured in it. */
    long nowMs();

    /** Returns wall-clock time in epoch milliseconds, from the bridge clock. */
    long wallMs();

    /** Sends a sequenced message; it is logged and replayed after a resume. */
    <T> void sendReliable(Session s, MessageType<T> type, T msg, int flags, long req);

    /**
     * Queues a latest-wins message; a newer one with the same type and subject replaces it until the
     * next flush. Dropped while the session has no connection.
     */
    <T> void sendLatest(Session s, MessageType<T> type, T msg, @Nullable String subject);

    /** Sends a reliable success response to a request the session made. */
    <T> void respond(Session s, MessageType<T> type, T msg, long req);

    /**
     * Sends an error response to a request the session made. It is sequenced and logged, so it is
     * replayed after a resume when the session has no connection right now.
     */
    void respondError(Session s, String code, String message, int relatesTo, long req);

    /** Like {@link #respondError(Session, String, String, int, long)}, with the error's details. */
    void respondError(Session s, BridgeException error, int relatesTo, long req);

    /**
     * Sends a reliable request to a session and waits for its response, failing with {@code timeout}
     * after the request type's {@code timeoutMs}.
     */
    <Q, R> CompletableFuture<R> request(Session w, MessageType<Q> type, Q msg, MessageType<R> response);

    /** Returns the live writer session of an owner (without trailing slash), or null. */
    @Nullable Session writer(String owner);

    /** Returns the live, attached bot sessions (not controllers), sorted by owned prefix. */
    List<Session> attachedBots();

    /** Returns the live controller sessions, sorted by owner. Controllers are always attached. */
    List<Session> liveControllers();

    /** Returns the live bot sessions, attached or not, sorted by owned prefix. */
    List<Session> liveBots();

    /** Returns whether the player currently has this session attached. */
    boolean attachedNow(Session s);

    /** Records the player's own choice to attach or detach a bot, which holds in every world. */
    void chooseAttached(Session s, boolean attached);

    /** Returns whether a bot session reported the world the player is in. */
    boolean worldMatched(Session s);

    /** Returns whether a world-scoped object in world {@code w} is in the player's world. */
    boolean worldVisible(@Nullable WorldKey w);

    /**
     * Requires that the player sees {@code o} of session {@code s}: the session is attached and a
     * world-scoped object is in the player's world.
     *
     * @throws BridgeException {@code not_attached} or {@code world_mismatch}
     */
    void requireVisible(Session s, StoredObject o);

    /** {@code visibleWhen.focusedOnly} objects show only while their bot is focused. */
    boolean focusAllows(String owner, @Nullable ObjectTraits traits);

    /** Returns whether a session holds a permission (its device, with the override for its bot). */
    boolean permitted(Session s, Permission p);

    /**
     * Rejects a request with {@code permission_denied} and queues the one-time prompt.
     *
     * @throws BridgeException {@code permission_denied} when the permission is missing
     */
    void requirePermission(Session s, Permission p);

    /**
     * For a latest-wins message that is not a request: a missing permission queues the one-time
     * prompt and reports {@code permission_denied} once per session and permission.
     */
    void reportMissing(Session s, Permission p);

    /** Returns whether a device authenticated with the local token. */
    boolean localDevice(String deviceId);

    /** Returns the effective quotas of a session's device. */
    Quotas quotas(Session s);

    /** Returns the latest published declaration snapshot. */
    DeclarationSnapshot declarations();
}
