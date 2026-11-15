package io.github.pix3lpirat3.mfbridge.core.world;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.FocusControls;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the focused bot. The candidates are the attached bots; {@link
 * #refresh} runs every tick and moves focus to the first candidate when the focused bot is no longer
 * one. Client thread only.
 */
@ClientThread
public final class FocusState implements FocusControls {
    private final Supplier<List<String>> candidates;
    private final Runnable changed;
    private final Object2LongOpenHashMap<String> lastRequestMs = new Object2LongOpenHashMap<>();
    private @Nullable String focused;
    private boolean allowRequests = true;
    private long version;

    /**
     * Creates the state.
     *
     * @param candidates returns the owners of attached bots, sorted
     * @param changed runs after the focused bot changed
     */
    public FocusState(Supplier<List<String>> candidates, Runnable changed) {
        this.candidates = candidates;
        this.changed = changed;
        lastRequestMs.defaultReturnValue(Long.MIN_VALUE / 2);
    }

    @Override
    public @Nullable String focused() {
        return focused;
    }

    @Override
    public void focus(String owner) {
        if (candidates.get().contains(owner)) {
            set(owner);
        }
    }

    @Override
    public void cycle(int step) {
        List<String> c = candidates.get();
        if (c.isEmpty()) {
            return;
        }
        int at = focused == null ? -1 : c.indexOf(focused);
        int next = at < 0 ? (step >= 0 ? 0 : c.size() - 1) : Math.floorMod(at + Integer.signum(step == 0 ? 1 : step), c.size());
        set(c.get(next));
    }

    @Override
    public boolean allowBotRequests() {
        return allowRequests;
    }

    @Override
    public void setAllowBotRequests(boolean allow) {
        allowRequests = allow;
    }

    @Override
    public long version() {
        return version;
    }

    /**
     * Handles a bot's {@code focus.request}: honoured while the player allows it, at most once per
     * {@link FocusControls#REQUEST_INTERVAL_MS} per bot.
     *
     * @param owner the requesting bot's owner
     * @param nowMs bridge clock ms
     * @return whether focus moved to the bot or already was on it
     */
    public boolean request(String owner, long nowMs) {
        if (!allowRequests || nowMs - lastRequestMs.getLong(owner) < REQUEST_INTERVAL_MS) {
            return false;
        }
        if (!candidates.get().contains(owner)) {
            return false;
        }
        lastRequestMs.put(owner, nowMs);
        set(owner);
        return true;
    }

    /** Moves focus to the first attached bot when the focused one is no longer attached. */
    public void refresh() {
        List<String> c = candidates.get();
        String f = focused;
        if (f != null && c.contains(f)) {
            return;
        }
        set(c.isEmpty() ? null : c.getFirst());
    }

    private void set(@Nullable String owner) {
        if (Objects.equals(owner, focused)) {
            return;
        }
        focused = owner;
        version++;
        changed.run();
    }
}
