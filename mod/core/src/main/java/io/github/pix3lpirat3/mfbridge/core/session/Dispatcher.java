package io.github.pix3lpirat3.mfbridge.core.session;

import java.util.Map;
import java.util.LinkedHashMap;
import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.event.EventBus;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.BridgeClock;
import io.github.pix3lpirat3.mfbridge.core.HostEnvironment;
import io.github.pix3lpirat3.mfbridge.core.Notifier;
import io.github.pix3lpirat3.mfbridge.core.effect.EffectRequested;
import io.github.pix3lpirat3.mfbridge.core.ext.ConnRegistry;
import io.github.pix3lpirat3.mfbridge.core.ext.ExtensionHost;
import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import io.github.pix3lpirat3.mfbridge.core.remote.Quota;
import io.github.pix3lpirat3.mfbridge.core.util.Encoding;
import io.github.pix3lpirat3.mfbridge.core.util.TextLimits;
import io.github.pix3lpirat3.mfbridge.core.world.PresenceRegistry;
import io.github.pix3lpirat3.mfbridge.protocol.core.Ack;
import io.github.pix3lpirat3.mfbridge.protocol.core.AuthResponse;
import io.github.pix3lpirat3.mfbridge.protocol.core.ChatPrint;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerAck;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerClose;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.EffectBody;
import io.github.pix3lpirat3.mfbridge.protocol.core.EffectPlay;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorInfo;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.FocusRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormClose;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.Goodbye;
import io.github.pix3lpirat3.mfbridge.protocol.core.Hello;
import io.github.pix3lpirat3.mfbridge.protocol.core.InvocationResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.Notify;
import io.github.pix3lpirat3.mfbridge.protocol.core.NotifyButton;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjBegin;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjCommit;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjRemove;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjSnapshotBegin;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjSnapshotEnd;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjUpsert;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickCancel;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.Ping;
import io.github.pix3lpirat3.mfbridge.protocol.core.Pong;
import io.github.pix3lpirat3.mfbridge.protocol.core.PresenceStatus;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptCancel;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueryBlocks;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueryEntities;
import io.github.pix3lpirat3.mfbridge.protocol.core.SampleRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenClose;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionClose;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionResume;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOp;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedSubscribe;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamAccept;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamChunk;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamEnd;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamHave;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamOffer;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamReject;
import io.github.pix3lpirat3.mfbridge.protocol.core.StreamWindow;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuCancel;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldReport;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Delivery;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Routes each inbound message of a ready connection to what handles it, and answers the ones that
 * fail with an error.
 */
@ClientThread
final class Dispatcher {
    /** Most notifications one session may show per second. */
    static final int NOTICES_PER_SECOND = 10;
    /** Detail key of a refused {@code screen.open} or {@code container.open}: the screen or view it was for. */
    static final String SURFACE_KEY = "key";

    /** Most buttons one notification may carry. */
    private static final int MAX_NOTIFY_BUTTONS = 4;
    /** The chat box holds 256 characters. */
    private static final int MAX_SUGGEST_CHARS = 256;

    private final HostEnvironment host;
    private final Logger log;
    private final BridgeClock clock;
    private final EventBus events;
    private final PresenceRegistry presence;
    private final InputQueue input;
    private final Interactions interactions;
    private final Containers containers;
    private final Screens screens;
    private final ChatRouter chat;
    private final Streams streams;
    private final Samples samples;
    private final Outbox outbox;
    private final Permits permits;
    private final PlayerView view;
    private final ObjectWrites writes;
    private final SharedObjects shared;
    private final SessionLifecycle lifecycle;
    private final Handshake handshake;
    private final ExtensionLinks links;
    private final ClientQueryService clientQueries;

    Dispatcher(HostEnvironment host, Logger log, BridgeClock clock, EventBus events, PresenceRegistry presence,
            InputQueue input, Interactions interactions, Containers containers, Screens screens, ChatRouter chat,
            Streams streams, Samples samples, Outbox outbox, Permits permits, PlayerView view, ObjectWrites writes,
            SharedObjects shared, SessionLifecycle lifecycle, Handshake handshake, ExtensionLinks links,
            ClientQueryService clientQueries) {
        this.host = host;
        this.log = log;
        this.clock = clock;
        this.events = events;
        this.presence = presence;
        this.input = input;
        this.interactions = interactions;
        this.containers = containers;
        this.screens = screens;
        this.chat = chat;
        this.streams = streams;
        this.samples = samples;
        this.outbox = outbox;
        this.permits = permits;
        this.view = view;
        this.writes = writes;
        this.shared = shared;
        this.lifecycle = lifecycle;
        this.handshake = handshake;
        this.links = links;
        this.clientQueries = clientQueries;
    }

    /**
     * Runs the opening of a screen or container view. These are not requests, so a refusal comes
     * back as a plain session error; its details name the surface, next to whatever the refusal
     * itself put there ({@code retryAfterMs} for a cooldown), so the bot can tell which open failed.
     */
    private static void opening(String key, Runnable open) {
        try {
            open.run();
        } catch (BridgeException e) {
            Map<String, Object> details = new LinkedHashMap<>();
            if (e.details() instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> entry : m.entrySet()) {
                    details.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            details.put(SURFACE_KEY, key);
            String message = e.getMessage();
            throw new BridgeException(e.code(), message == null ? e.code() : message, e.retryable(), details);
        }
    }

    /** Answers a failed message with {@code core:Error}; before welcome, with a goodbye. */
    void reject(Connection c, @Nullable Envelope env, BridgeException e) {
        if (c.state != Connection.State.READY) {
            outbox.goodbye(c, e.code(), Outbox.messageOf(e), false);
            return;
        }
        if (env != null && env.has(Envelope.RESPONSE)) {
            // Responses are never answered, or two peers could ping-pong errors forever.
            log.debug("Dropping bad response from {}: {}", c.net.remoteAddress(), e.getMessage());
            return;
        }
        int sessionId = 0;
        Session s = null;
        if (env != null && env.session() != 0) {
            s = c.sessions.get(env.session());
            if (s != null) {
                sessionId = env.session();
            }
        }
        long req = env != null && env.req() != Envelope.NONE ? env.req() : Envelope.NONE;
        outbox.sendError(c, sessionId, s, e, env == null ? null : env.type(), req);
    }

    void dispatch(Connection c, Envelope env, byte[] payload, long now) {
        if (c.state != Connection.State.READY) {
            handshake.dispatchHandshake(c, env, payload, now);
            return;
        }
        Session s = null;
        if (env.session() != 0) {
            s = c.sessions.get(env.session());
            if (s == null) {
                throw new BridgeException("invalid_message", "unknown session " + env.session());
            }
            if (s.identity != null) {
                presence.seen(s.identity, clock.currentTimeMillis());
            }
            if (env.ack() != Envelope.NONE) {
                s.log.trim(env.ack());
            }
        }
        int type = env.type();
        if (type >= ConnRegistry.FIRST_ID) {
            dispatchExtension(c, s, env, payload, now);
            return;
        }
        MessageType<?> mt = CoreMessages.byId(type);
        if (mt == null) {
            throw new BridgeException("unknown_type", "unknown message type 0x" + Integer.toHexString(type));
        }
        if (mt.delivery() == Delivery.RELIABLE && !acceptSeq(c, s, env, now)) {
            return;
        }
        if (env.has(Envelope.RESPONSE)) {
            handleResponse(s, env, payload);
            return;
        }
        if (!mt.from().allowsFromNode()) {
            throw new BridgeException("invalid_message", mt.name() + " may only be sent by the mod");
        }
        Object msg = Encoding.decode(mt.codec(), payload);
        switch (msg) {
            case Ping p -> outbox.sendControl(c, env.session(), s, CoreMessages.PONG, new Pong(p.time()), 0, Envelope.NONE);
            case Pong p -> handshake.pong(c, p);
            case Ack a -> {}
            case Goodbye g -> c.net.abort();
            case ErrorMessage e -> log.debug("Bridge peer {} reported {}", c.net.remoteAddress(), e.error().code());
            case SessionOpen o -> lifecycle.openSession(c, env, o, now);
            case SessionResume r -> lifecycle.resumeSession(c, env, r, now);
            case SessionClose sc -> lifecycle.endSession(need(s), sc.reason());
            case WorldReport w -> lifecycle.worldReport(need(s), w);
            case PresenceStatus ps -> {
                Session ss = need(s);
                if (ss.identity != null) {
                    presence.status(ss.identity, ps.status());
                }
            }
            case ObjSnapshotBegin b -> writes.snapshotBegin(need(s));
            case ObjSnapshotEnd e -> writes.snapshotEnd(need(s));
            case ObjUpsert u -> {
                if (!writes.buffered(need(s), u)) {
                    writes.upsert(c, need(s), u, now);
                }
            }
            case ObjRemove r -> {
                if (!writes.buffered(need(s), r)) {
                    writes.remove(need(s), r);
                }
            }
            case ObjBegin b -> writes.beginTx(need(s), b.tx(), now);
            case ObjCommit cm -> writes.commitTx(c, need(s), cm.tx(), now);
            case FocusRequest fr -> view.focus().request(need(s).owner, now);
            case SharedOp op -> shared.sharedOp(c, need(s), env, op, now);
            case SharedSubscribe sub -> shared.subscribe(need(s), sub);
            case PickRequest r -> {
                permits.requirePermission(need(s), Permission.INPUT_PICK);
                if (r.urgent()) {
                    permits.requirePermission(need(s), Permission.INPUT_PICK_URGENT);
                }
                input.pick(need(s), needReq(env), r, now);
            }
            case PromptRequest r -> {
                permits.requirePermission(need(s), Permission.INPUT_PICK);
                if (ChatRouter.CHAT_SURFACE.equals(r.surface())) {
                    // This prompt takes the player's next chat line.
                    permits.requirePermission(need(s), Permission.CHAT);
                }
                input.prompt(need(s), needReq(env), r, now);
            }
            case FormOpen r -> {
                permits.requirePermission(need(s), Permission.FORM);
                input.form(need(s), needReq(env), r, now);
            }
            case QueryEntities q -> outbox.respond(need(s), CoreMessages.QUERY_ENTITIES_RESULT, clientQueries.queryEntities(need(s), q), needReq(env));
            case QueryBlocks q -> outbox.respond(need(s), CoreMessages.QUERY_BLOCKS_RESULT, clientQueries.queryBlocks(need(s), q), needReq(env));
            case PickCancel x -> input.withdraw(need(s), Integer.toUnsignedLong(x.request()), CoreMessages.PICK_REQUEST.id());
            case PromptCancel x ->
                    input.withdraw(need(s), Integer.toUnsignedLong(x.request()), CoreMessages.PROMPT_REQUEST.id());
            case FormClose x -> input.withdraw(need(s), Integer.toUnsignedLong(x.request()), CoreMessages.FORM_OPEN.id());
            case WorldMenuRequest r -> {
                permits.requirePermission(need(s), Permission.RENDER_DISPLAY);
                input.worldMenu(need(s), needReq(env), r, now);
            }
            case WorldMenuCancel x ->
                    input.withdraw(need(s), Integer.toUnsignedLong(x.request()), CoreMessages.WORLD_MENU_REQUEST.id());
            case EffectPlay e -> effect(need(s), e.effect(), now);
            case InvocationResult r -> interactions.result(need(s), r);
            case ContainerOpen o -> {
                Session ss = need(s);
                opening(o.view(), () -> {
                    permits.requirePermission(ss, Permission.UI_CONTAINER);
                    containers.openRequest(ss, o);
                });
            }
            case ContainerClose x -> containers.closeRequest(need(s), x);
            case ScreenOpen o -> {
                Session ss = need(s);
                opening(o.screen(), () -> {
                    permits.requirePermission(ss, Permission.UI_CONTAINER);
                    screens.openRequest(ss, o);
                });
            }
            case ScreenClose x -> screens.closeRequest(need(s), x);
            case ContainerAck a -> containers.ack(need(s), a);
            case ChatPrint m -> chat.print(need(s), m);
            case StreamOffer o -> streams.offer(need(s), o);
            case StreamChunk ch -> streams.chunk(need(s), ch);
            case StreamEnd e -> streams.end(need(s), e);
            case StreamReject r -> streams.peerReject(need(s), r);
            case StreamAccept a -> throw Streams.noOutbound();
            case StreamHave hv -> throw Streams.noOutbound();
            case StreamWindow w -> throw Streams.noOutbound();
            case Notify n -> {
                Session ss = need(s);
                if (permits.allowedOrReport(ss, Permission.NOTIFY)) {
                    view.requireAttached(ss);
                    countNotice(ss, now);
                    host.notifier().notify(notification(ss, n));
                }
            }
            case SampleRequest r -> samples.request(need(s), r);
            case Hello h -> throw new BridgeException("invalid_message", "handshake already complete");
            case AuthResponse a -> {
                // A replay authenticated at hello; the recorded proof answers a nonce it never saw.
                if (!c.replay) {
                    throw new BridgeException("invalid_message", "handshake already complete");
                }
            }
            default -> throw new BridgeException("unsupported", mt.name() + " is not supported by this mod yet");
        }
    }

    /** Checks a notify message: buttons name the sender's own actions, and the sound is a plain id. */
    private static Notifier.Notification notification(Session s, Notify n) {
        List<Notifier.Button> buttons = new ArrayList<>();
        if (n.buttons() != null) {
            if (n.buttons().size() > MAX_NOTIFY_BUTTONS) {
                throw new BridgeException("invalid_message", "a notification takes at most " + MAX_NOTIFY_BUTTONS + " buttons");
            }
            for (NotifyButton b : n.buttons()) {
                if (b.label().isBlank() || b.label().length() > 32) {
                    throw new BridgeException("invalid_message", "a notification button needs a label of 1 to 32 characters");
                }
                String action = b.action();
                String suggest = b.suggest();
                if ((action == null) == (suggest == null)) {
                    throw new BridgeException("invalid_message", "a notification button has an action or a line to suggest, not both");
                }
                if (action != null && !action.startsWith(s.owner + "/")) {
                    throw new BridgeException("invalid_message", "a notification button runs one of the sender's own actions: " + action);
                }
                if (suggest != null && (suggest.isBlank() || suggest.length() > MAX_SUGGEST_CHARS)) {
                    throw new BridgeException("invalid_message", "a notification button suggests a line of 1 to " + MAX_SUGGEST_CHARS + " characters");
                }
                buttons.add(new Notifier.Button(b.label(), action, suggest));
            }
        }
        String sound = n.sound();
        if (sound != null && !sound.matches("[a-z0-9_.-]+(:[a-z0-9_./-]+)?")) {
            throw new BridgeException("invalid_message", "bad sound id " + sound);
        }
        return new Notifier.Notification(s.displayName, s.owner, TextLimits.clamp(n.text()), n.level(), n.icon(), buttons, sound);
    }

    /**
     * Counts a notification against the session's {@link #NOTICES_PER_SECOND}.
     *
     * @throws BridgeException {@code rate_limited}, retryable, when the second is full
     */
    private static void countNotice(Session s, long now) {
        if (now - s.noticeWindowStartMs >= 1000) {
            s.noticeWindowStartMs = now;
            s.noticesInWindow = 0;
        }
        if (s.noticesInWindow >= NOTICES_PER_SECOND) {
            throw new BridgeException("rate_limited", "more than " + NOTICES_PER_SECOND + " messages to the player in one second",
                    true, null);
        }
        s.noticesInWindow++;
    }

    static Session need(@Nullable Session s) {
        if (s == null) {
            throw new BridgeException("invalid_message", "message requires a session");
        }
        return s;
    }

    static long needReq(Envelope env) {
        if (env.req() == Envelope.NONE) {
            throw new BridgeException("invalid_message", "request without req id");
        }
        return env.req();
    }

    /** Apply in order, ignore duplicates, a gap closes the session. */
    private boolean acceptSeq(Connection c, @Nullable Session s, Envelope env, long now) {
        Session ss = need(s);
        long seq = env.seq();
        if (c.replay) {
            // Recordings may start mid-stream, and their preludes carry no seq at all.
            if (seq != Envelope.NONE) {
                ss.lastAppliedIn = Math.max(ss.lastAppliedIn, seq);
            }
            return true;
        }
        if (seq == Envelope.NONE) {
            throw new BridgeException("invalid_message", "reliable message without seq");
        }
        if (seq <= ss.lastAppliedIn) {
            return false;
        }
        if (seq != ss.lastAppliedIn + 1) {
            outbox.sendError(c, ss.id, ss, new BridgeException("invalid_message",
                    "sequence gap: expected " + (ss.lastAppliedIn + 1) + ", got " + seq), env.type(), Envelope.NONE);
            outbox.sendControl(c, ss.id, ss, CoreMessages.SESSION_CLOSE, new SessionClose("invalid_message"), 0, Envelope.NONE);
            lifecycle.endSession(ss, "invalid_message");
            return false;
        }
        ss.lastAppliedIn = seq;
        if (ss.ackDueSinceMs < 0) {
            ss.ackDueSinceMs = now;
        }
        return true;
    }

    private void handleResponse(@Nullable Session s, Envelope env, byte[] payload) {
        if (s == null || env.req() == Envelope.NONE) {
            return;
        }
        PendingRequest<?> p = s.pending.remove(env.req());
        if (p == null) {
            return;
        }
        if (env.has(Envelope.ERROR) || env.type() == CoreMessages.ERROR.id()) {
            ErrorInfo info = Encoding.decode(ErrorMessage.CODEC, payload).error();
            p.future().completeExceptionally(
                    new BridgeException(info.code(), info.message(), info.retryable(), info.details()));
        } else if (env.type() == p.response().id()) {
            try {
                p.complete(payload);
            } catch (WireException e) {
                p.future().completeExceptionally(new BridgeException("invalid_message", "malformed response"));
            }
        } else {
            p.future().completeExceptionally(new BridgeException("invalid_message", "unexpected response type"));
        }
    }

    private void dispatchExtension(Connection c, @Nullable Session s, Envelope env, byte[] payload, long now) {
        ConnRegistry reg = Objects.requireNonNull(c.registry);
        ExtensionHost.RegisteredMessage<?> m = reg.message(env.type());
        if (m == null) {
            throw new BridgeException("unknown_type", "type 0x" + Integer.toHexString(env.type()) + " was not negotiated");
        }
        if (m.type().delivery() == Delivery.RELIABLE && !acceptSeq(c, s, env, now)) {
            return;
        }
        if (!m.type().from().allowsFromNode()) {
            throw new BridgeException("invalid_message", m.type().name() + " may only be sent by the mod");
        }
        Session ss = need(s);
        Permission needed = m.permission();
        if (needed != null) {
            permits.requirePermission(ss, needed);
        }
        if (m.attached()) {
            view.requireAttached(ss);
        }
        invokeExtension(m, ss, payload);
    }

    private <T> void invokeExtension(ExtensionHost.RegisteredMessage<T> m, Session s, byte[] payload) {
        T value = Encoding.decode(m.type().codec(), payload);
        m.handler().handle(links.context(s), value);
    }

    /**
     * Posts a bot's client-only effect. Unreliable, so problems drop
     * the effect instead of answering with an error: a detached bot, a positional effect outside the
     * player's world, or more than the device's effects-per-second quota in the current second.
     * A missing permission is reported once per session.
     */
    private void effect(Session s, EffectBody body, long now) {
        if (s.superseded || !view.attachedNow(s)) {
            return;
        }
        boolean positional = switch (body) {
            case EffectBody.Sound x -> x.value().at() != null;
            case EffectBody.Particles x -> true;
            case EffectBody.Title x -> false;
            case EffectBody.Actionbar x -> false;
        };
        Permission needed = switch (body) {
            case EffectBody.Sound x -> Permission.EFFECTS_SOUND;
            case EffectBody.Particles x -> Permission.EFFECTS_PARTICLES;
            case EffectBody.Title x -> Permission.EFFECTS_TITLE;
            case EffectBody.Actionbar x -> Permission.EFFECTS_TITLE;
        };
        if (!permits.allowedOrReport(s, needed)) {
            return;
        }
        if (positional && s.kind == SessionKind.BOT && !view.worldMatched(s)) {
            return;
        }
        if (now - s.effectWindowStartMs >= 1000) {
            s.effectWindowStartMs = now;
            s.effectsInWindow = 0;
        }
        if (s.effectsInWindow >= permits.quotas(s).get(Quota.EFFECTS_PER_SECOND)) {
            return;
        }
        s.effectsInWindow++;
        if (events.hasListeners(EffectRequested.TYPE)) {
            events.post(EffectRequested.TYPE, new EffectRequested(s.displayName, body));
        }
    }
}
