package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.event.EventBus;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.input.FormInput;
import io.github.pix3lpirat3.mfbridge.core.input.InputChanged;
import io.github.pix3lpirat3.mfbridge.core.input.InputControls;
import io.github.pix3lpirat3.mfbridge.core.input.InputRequest;
import io.github.pix3lpirat3.mfbridge.core.input.PickInput;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.core.input.WorldMenuInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickResultMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuEntry;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The FIFO input queue behind {@link InputControls}: one active at a time, others
 * queue in arrival order, urgent picks go to the front without interrupting the active one; prompts
 * and forms queue the same way, and a world menu is an input request too.
 */
@ClientThread
final class InputQueue implements InputControls {
    private static final class Entry {
        final InputRequest view;
        final Session session;
        final long req;
        final int requestType;
        /** Surface the UI reported for a prompt, sent with a timeout; null until reported. */
        @Nullable String surface;
        /** Bridge clock ms at which the request became the active one, or -1 while it waits. */
        long activatedMs = -1;

        Entry(InputRequest view, Session session, long req, int requestType) {
            this.view = view;
            this.session = session;
            this.req = req;
            this.requestType = requestType;
        }

        boolean urgent() {
            return view instanceof PickInput p && p.request().urgent();
        }

        boolean chatPrompt() {
            return view instanceof PromptInput p && ChatRouter.CHAT_SURFACE.equals(p.request().surface());
        }

        boolean worldScoped() {
            return switch (view) {
                case PickInput p -> p.request().kind() != PickKind.ITEM;
                case PromptInput p -> "world".equals(p.request().surface());
                case FormInput f -> false;
                case WorldMenuInput w -> true;
            };
        }
    }

    private final SessionHost host;
    private final EventBus events;
    private final SurfaceGate gate;
    private final int maxPerSession;
    private final long maxTimeoutMs;
    private final long chatWindowMs;
    private final ArrayList<Entry> waiting = new ArrayList<>();
    private @Nullable Entry active;
    private long nextId = 1;
    private @Nullable InputRequest announcedActive;
    private int announcedQueued;

    /**
     * Creates the queue.
     *
     * @param maxPerSession requests one session may have pending; more are refused
     * @param maxTimeoutMs longest timeout a request may ask for; longer ones are cut to it
     * @param chatWindowMs how long an active chat prompt may wait for the player's next line
     */
    InputQueue(SessionHost host, EventBus events, SurfaceGate gate, int maxPerSession, long maxTimeoutMs, long chatWindowMs) {
        this.host = host;
        this.events = events;
        this.gate = gate;
        this.maxPerSession = maxPerSession;
        this.maxTimeoutMs = maxTimeoutMs;
        this.chatWindowMs = chatWindowMs;
    }

    private long deadline(long now, int timeoutMs) {
        return now + Math.min(Integer.toUnsignedLong(timeoutMs), maxTimeoutMs);
    }

    void pick(Session s, long req, PickRequest r, long now) {
        KindCaps.check(r);
        long id = nextId++;
        long deadline = deadline(now, r.timeoutMs());
        enqueue(new Entry(new PickInput(id, s.displayName, s.identity, s.id, r, now, deadline), s, req,
                CoreMessages.PICK_REQUEST.id()));
    }

    void prompt(Session s, long req, PromptRequest r, long now) {
        KindCaps.check(r);
        long id = nextId++;
        long deadline = deadline(now, r.timeoutMs());
        enqueue(new Entry(new PromptInput(id, s.displayName, s.identity, s.id, r, now, deadline), s, req,
                CoreMessages.PROMPT_REQUEST.id()));
    }

    void form(Session s, long req, FormOpen r, long now) {
        KindCaps.check(r);
        long id = nextId++;
        long deadline = deadline(now, r.timeoutMs());
        enqueue(new Entry(new FormInput(id, s.displayName, s.identity, s.id, r, now, deadline), s, req,
                CoreMessages.FORM_OPEN.id()));
    }

    void worldMenu(Session s, long req, WorldMenuRequest r, long now) {
        if (r.entries().isEmpty()) {
            throw new BridgeException("invalid_message", "a world menu needs at least one entry");
        }
        KindCaps.check(r);
        long id = nextId++;
        long deadline = deadline(now, r.timeoutMs());
        enqueue(new Entry(new WorldMenuInput(id, s.displayName, s.identity, s.id, r, now, deadline), s, req,
                CoreMessages.WORLD_MENU_REQUEST.id()));
    }

    /**
     * Admits a new request or throws the rejection, which the dispatcher answers as an error
     * response to the request.
     */
    private void enqueue(Entry e) {
        if (e.session.superseded) {
            throw new BridgeException("superseded", "a newer session took over this identity");
        }
        BridgeException problem = admission(e);
        if (problem != null) {
            throw problem;
        }
        gate.check(e.session.owner, e.view.createdAtMs());
        if (pendingOf(e.session) >= maxPerSession) {
            throw new BridgeException("quota_exceeded", "this session already has " + maxPerSession
                    + " input requests waiting for the player");
        }
        if (active == null) {
            activate(e, e.view.createdAtMs());
        } else if (e.urgent()) {
            int at = 0;
            while (at < waiting.size() && waiting.get(at).urgent()) {
                at++;
            }
            waiting.add(at, e);
        } else {
            waiting.add(e);
        }
        announce();
    }

    private void activate(Entry e, long now) {
        active = e;
        e.activatedMs = now;
    }

    private int pendingOf(Session s) {
        int n = active != null && active.session == s ? 1 : 0;
        for (Entry e : waiting) {
            if (e.session == s) {
                n++;
            }
        }
        return n;
    }

    private @Nullable BridgeException admission(Entry e) {
        if (!host.attachedNow(e.session)) {
            return new BridgeException("not_attached", "the player has not attached this bot");
        }
        if (e.worldScoped() && !host.worldMatched(e.session)) {
            return new BridgeException("world_mismatch", "the player is not in this bot's world");
        }
        return null;
    }

    /**
     * A bot withdrew a request ({@code pick.cancel}, {@code prompt.cancel}, {@code form.close},
     * {@code world-menu.cancel}); no response.
     */
    void withdraw(Session s, long req, int requestType) {
        Entry e = find(s, req, requestType);
        if (e != null) {
            remove(e);
            announce();
        }
    }

    private @Nullable Entry find(Session s, long req, int requestType) {
        Entry a = active;
        if (a != null && a.session == s && a.req == req && a.requestType == requestType) {
            return a;
        }
        for (Entry e : waiting) {
            if (e.session == s && e.req == req && e.requestType == requestType) {
                return e;
            }
        }
        return null;
    }

    /** Fails or defaults every request whose deadline passed; timeouts reject with {@code timeout}. */
    void expire(long now) {
        boolean changed = false;
        Entry a = active;
        if (a != null && now >= a.view.deadlineMs()) {
            timeOut(a);
            active = null;
            changed = true;
        } else if (a != null && a.chatPrompt() && now - a.activatedMs >= chatWindowMs) {
            // The player's chat is not held longer than this; an unanswered capture counts as dismissed.
            timeOut(a);
            active = null;
            changed = true;
            gate.dismissed(a.session.owner, now);
        }
        for (int i = waiting.size() - 1; i >= 0; i--) {
            Entry e = waiting.get(i);
            if (now >= e.view.deadlineMs()) {
                waiting.remove(i);
                timeOut(e);
                changed = true;
            }
        }
        if (changed) {
            announce();
        }
    }

    private void timeOut(Entry e) {
        if (e.view instanceof PromptInput p) {
            host.respond(e.session, CoreMessages.PROMPT_RESULT, new PromptResult(p.request().defaultValue(), true, e.surface != null ? e.surface : p.request().surface()),
                    e.req);
        } else {
            host.respondError(e.session, "timeout", "the player did not answer in time", e.requestType, e.req);
        }
    }

    /** Drops every request of a session that ended, without answering it. */
    void dropSession(Session s) {
        boolean changed = waiting.removeIf(e -> e.session == s);
        Entry a = active;
        if (a != null && a.session == s) {
            active = null;
            changed = true;
        }
        if (changed) {
            announce();
        }
    }

    @Override
    public @Nullable InputRequest active() {
        Entry a = active;
        return a == null ? null : a.view;
    }

    @Override
    public int queued() {
        return waiting.size();
    }

    @Override
    public List<InputRequest> pending() {
        List<InputRequest> out = new ArrayList<>(waiting.size() + 1);
        Entry a = active;
        if (a != null) {
            out.add(a.view);
        }
        for (Entry e : waiting) {
            out.add(e.view);
        }
        return List.copyOf(out);
    }

    @Override
    public void resolvePick(long id, PickResult result) {
        Entry e = byId(id);
        if (e == null) {
            return;
        }
        if (!(e.view instanceof PickInput p)) {
            throw new IllegalArgumentException("input " + id + " is not a pick");
        }
        if (kindOf(result) != p.request().kind()) {
            throw new IllegalArgumentException("pick " + id + " asked for a " + p.request().kind().wireName()
                    + ", not a " + kindOf(result).wireName());
        }
        remove(e);
        answered(e);
        host.respond(e.session, CoreMessages.PICK_RESULT, new PickResultMessage(result), e.req);
        announce();
    }

    /** The player answered: the owner's cooldown ends, and what it opens next counts as asked for. */
    private void answered(Entry e) {
        gate.completed(e.session.owner);
        gate.asked(e.session.owner, host.nowMs());
    }

    @Override
    public boolean askedFor(long id) {
        Entry e = byId(id);
        return e != null && gate.askedFor(e.session.owner, host.nowMs());
    }

    private static PickKind kindOf(PickResult r) {
        return switch (r) {
            case PickResult.Point p -> PickKind.POINT;
            case PickResult.Block b -> PickKind.BLOCK;
            case PickResult.Region g -> PickKind.REGION;
            case PickResult.Polygon g -> PickKind.POLYGON;
            case PickResult.Entity x -> PickKind.ENTITY;
            case PickResult.Path p -> PickKind.PATH;
            case PickResult.Direction d -> PickKind.DIRECTION;
            case PickResult.Item i -> PickKind.ITEM;
        };
    }

    @Override
    public void resolvePrompt(long id, @Nullable Object value, @Nullable String surface) {
        Entry e = byId(id);
        if (e == null) {
            return;
        }
        if (!(e.view instanceof PromptInput p)) {
            throw new IllegalArgumentException("input " + id + " is not a prompt");
        }
        // Not shown yet: it would have used the surface asked for.
        String used = surface != null ? surface : e.surface != null ? e.surface : p.request().surface();
        Object answer = value == null ? null : PromptSurfaces.handles(used) ? PromptSurfaces.value(p.request(), used, value)
                : promptValue(p.request(), value);
        remove(e);
        answered(e);
        host.respond(e.session, CoreMessages.PROMPT_RESULT, new PromptResult(answer, false, used), e.req);
        announce();
    }

    @Override
    public void surfaceShown(long id, String surface) {
        Entry e = byId(id);
        if (e != null && e.view instanceof PromptInput) {
            e.surface = surface;
        }
    }

    @Override
    public void resolveWorldMenu(long id, String entryId) {
        Entry e = byId(id);
        if (e == null) {
            return;
        }
        if (!(e.view instanceof WorldMenuInput w)) {
            throw new IllegalArgumentException("input " + id + " is not a world menu");
        }
        if (!hasEntry(w.request(), entryId)) {
            throw new IllegalArgumentException("world menu " + id + " has no entry '" + entryId + "'");
        }
        remove(e);
        answered(e);
        host.respond(e.session, CoreMessages.WORLD_MENU_RESULT, new WorldMenuResult(entryId), e.req);
        announce();
    }

    private static boolean hasEntry(WorldMenuRequest r, String entryId) {
        for (WorldMenuEntry entry : r.entries()) {
            if (entry.id().equals(entryId)) {
                return true;
            }
        }
        return false;
    }

    private static Object promptValue(PromptRequest r, Object value) {
        return switch (r.kind()) {
            case CONFIRM -> ValueRules.bool(value, "the answer");
            case CHOICE -> ValueRules.option(value, r.choices(), "the answer");
            case TEXT -> limitLength(ValueRules.string(value, "the answer"), r.maxLength());
            case NUMBER -> {
                double d = ValueRules.number(value, "the answer");
                if ((r.min() != null && d < r.min()) || (r.max() != null && d > r.max())) {
                    throw new IllegalArgumentException("the answer " + d + " is outside [" + r.min() + ", " + r.max() + "]");
                }
                yield value instanceof Double || value instanceof Float ? (Object) d : (Object) ValueRules.integral(value, "the answer");
            }
            case LINES, PAGES -> {
                if (!(value instanceof List<?> list)) {
                    throw new IllegalArgumentException("the answer must be a list of strings");
                }
                List<String> out = new ArrayList<>(list.size());
                for (Object item : list) {
                    out.add(limitLength(ValueRules.string(item, "each line"), r.maxLength()));
                }
                yield out;
            }
        };
    }

    private static String limitLength(String s, @Nullable Integer maxLength) {
        if (maxLength != null && s.length() > maxLength) {
            throw new IllegalArgumentException("the answer is longer than " + maxLength + " characters");
        }
        return s;
    }

    @Override
    public void resolveForm(long id, Map<String, Object> values) {
        Entry e = byId(id);
        if (e == null) {
            return;
        }
        if (!(e.view instanceof FormInput f)) {
            throw new IllegalArgumentException("input " + id + " is not a form");
        }
        Map<String, @Nullable Object> checked = ValueRules.formValues(f.request().fields(), values);
        remove(e);
        answered(e);
        host.respond(e.session, CoreMessages.FORM_RESULT, new FormResult(Collections.unmodifiableMap(checked)), e.req);
        announce();
    }

    @Override
    public void pressFormButton(long id, String button) {
        Entry e = byId(id);
        if (e == null) {
            return;
        }
        if (!(e.view instanceof FormInput f)) {
            throw new IllegalArgumentException("input " + id + " is not a form");
        }
        var buttons = f.request().buttons();
        if (buttons == null || buttons.stream().noneMatch(b -> b.id().equals(button))) {
            throw new IllegalArgumentException("the form has no button " + button);
        }
        remove(e);
        answered(e);
        host.respond(e.session, CoreMessages.FORM_RESULT, new FormResult(Map.of(), button), e.req);
        announce();
    }

    @Override
    public void cancel(long id) {
        Entry e = byId(id);
        if (e == null) {
            return;
        }
        remove(e);
        gate.dismissed(e.session.owner, host.nowMs());
        host.respondError(e.session, "cancelled", "the player cancelled", e.requestType, e.req);
        announce();
    }

    @Override
    public void unsupported(long id, String message) {
        Entry e = byId(id);
        if (e == null) {
            return;
        }
        remove(e);
        host.respondError(e.session, "unsupported", message, e.requestType, e.req);
        announce();
    }

    private @Nullable Entry byId(long id) {
        Entry a = active;
        if (a != null && a.view.id() == id) {
            return a;
        }
        for (Entry e : waiting) {
            if (e.view.id() == id) {
                return e;
            }
        }
        return null;
    }

    private void remove(Entry e) {
        if (e == active) {
            active = null;
        } else {
            waiting.remove(e);
        }
    }

    /**
     * Promotes the next admissible request and posts {@link InputChanged} when anything visible
     * changed. Waiting requests are re-checked on promotion because the player may have detached the
     * bot or left its world while they queued.
     */
    private void announce() {
        while (active == null && !waiting.isEmpty()) {
            Entry next = waiting.remove(0);
            BridgeException problem = admission(next);
            if (problem == null) {
                activate(next, host.nowMs());
            } else {
                host.respondError(next.session, problem.code(), String.valueOf(problem.getMessage()),
                        next.requestType, next.req);
            }
        }
        InputRequest now = active();
        int queued = waiting.size();
        if (now == announcedActive && queued == announcedQueued) {
            return;
        }
        announcedActive = now;
        announcedQueued = queued;
        if (events.hasListeners(InputChanged.TYPE)) {
            events.post(InputChanged.TYPE, new InputChanged(now, queued));
        }
    }
}
