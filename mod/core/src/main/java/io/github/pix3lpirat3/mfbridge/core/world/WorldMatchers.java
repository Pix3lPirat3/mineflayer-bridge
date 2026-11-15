package io.github.pix3lpirat3.mfbridge.core.world;

import io.github.pix3lpirat3.mfbridge.api.WorldMatcher;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Matcher chain: registered matchers by descending priority, then {@link
 * DefaultWorldMatcher}. The first definite answer wins.
 */
@ClientThread
public final class WorldMatchers {
    private record Entry(WorldMatcher matcher, int priority, int order) {}

    private final List<Entry> entries = new ArrayList<>();
    private WorldMatcher[] chain = {DefaultWorldMatcher.INSTANCE};

    /** Adds a matcher; equal priorities keep registration order. */
    public void add(WorldMatcher matcher, int priority) {
        entries.add(new Entry(matcher, priority, entries.size()));
        entries.sort(Comparator.comparingInt((Entry e) -> -e.priority()).thenComparingInt(Entry::order));
        WorldMatcher[] next = new WorldMatcher[entries.size() + 1];
        for (int i = 0; i < entries.size(); i++) {
            next[i] = entries.get(i).matcher();
        }
        next[entries.size()] = DefaultWorldMatcher.INSTANCE;
        chain = next;
    }

    /** Returns whether {@code other} is the same world as {@code player}. */
    public boolean matches(WorldKey player, WorldKey other) {
        for (WorldMatcher m : chain) {
            WorldMatcher.Result r = m.match(player, other);
            if (r == WorldMatcher.Result.MATCH) {
                return true;
            }
            if (r == WorldMatcher.Result.MISMATCH) {
                return false;
            }
        }
        return false;
    }
}
