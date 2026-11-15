package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides whether an owner may put a surface (form, prompt, pick, world menu, screen, container
 * view) in front of the player right now. After the player dismisses one, the owner's unasked
 * surfaces are refused for a while, longer with each dismissal in a row. A surface is asked for
 * when the player ran something of that owner a moment ago; those always pass.
 */
@ClientThread
final class SurfaceGate {
    /** Error code of a refused surface. */
    static final String COOLDOWN = "cooldown";
    /** Detail key holding the ms left until the owner may try again. */
    static final String RETRY_AFTER = "retryAfterMs";

    private static final class State {
        /** Dismissals in a row. */
        int cancels;
        long lastCancelMs;
        /** Bridge clock ms until which unasked surfaces are refused. */
        long untilMs;
        /** Bridge clock ms of the player's last use of this owner; far in the past for never. */
        long askedMs = Long.MIN_VALUE / 2;
    }

    private final List<Long> cooldownsMs;
    private final long resetMs;
    private final long askedWindowMs;
    private final Map<String, State> owners = new HashMap<>();

    SurfaceGate(List<Long> cooldownsMs, long resetMs, long askedWindowMs) {
        this.cooldownsMs = cooldownsMs;
        this.resetMs = resetMs;
        this.askedWindowMs = askedWindowMs;
    }

    private State state(String owner) {
        return owners.computeIfAbsent(owner, k -> new State());
    }

    /** The player ran a command, action, button or menu entry of {@code owner}. */
    void asked(String owner, long now) {
        state(owner).askedMs = now;
    }

    /** Returns whether the player ran something of {@code owner} within the window. */
    boolean askedFor(String owner, long now) {
        State st = owners.get(owner);
        return st != null && now - st.askedMs <= askedWindowMs;
    }

    /** The player dismissed a surface of {@code owner}. */
    void dismissed(String owner, long now) {
        if (cooldownsMs.isEmpty()) {
            return;
        }
        State st = state(owner);
        if (st.cancels > 0 && now - st.lastCancelMs >= resetMs) {
            st.cancels = 0;
        }
        st.cancels++;
        st.lastCancelMs = now;
        st.untilMs = now + cooldownsMs.get(Math.min(st.cancels, cooldownsMs.size()) - 1);
    }

    /** The player answered a surface of {@code owner}. */
    void completed(String owner) {
        State st = owners.get(owner);
        if (st != null) {
            st.cancels = 0;
            st.untilMs = 0;
        }
    }

    /**
     * Refuses an unasked surface while the owner is cooling down.
     *
     * @throws BridgeException {@code cooldown}, retryable, with {@code retryAfterMs} in its details
     */
    void check(String owner, long now) {
        State st = owners.get(owner);
        if (st == null || st.cancels == 0 || now >= st.untilMs || now - st.askedMs <= askedWindowMs) {
            return;
        }
        long left = st.untilMs - now;
        throw new BridgeException(COOLDOWN, "the player dismissed this a moment ago; try again in " + left + " ms",
                true, Map.of(RETRY_AFTER, left));
    }

    /** Forgets owners whose cooldown, dismissal count and window have all lapsed. */
    void prune(long now) {
        owners.values().removeIf(st -> now >= st.untilMs && (st.cancels == 0 || now - st.lastCancelMs >= resetMs)
                && now - st.askedMs > askedWindowMs);
    }
}
