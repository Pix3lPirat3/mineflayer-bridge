package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Bot answers awaited by u32 id, such as invocation results and container acks. An
 * answer fails with {@code timeout} after the fixed timeout and with {@code disconnected} when its
 * session ends.
 *
 * @param <T> the answer type
 */
@ClientThread
final class PendingAnswers<T> {
    private record Entry<T>(Session session, String subject, CompletableFuture<T> future, long deadlineMs) {}

    private final Int2ObjectOpenHashMap<Entry<T>> pending = new Int2ObjectOpenHashMap<>();
    private final long timeoutMs;
    private final String timeoutMessage;
    private int next = 1;

    /** Creates the table; {@code timeoutMs} is how long each answer may take, in ms. */
    PendingAnswers(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        this.timeoutMessage = "the bot did not answer within " + timeoutMs / 1000 + " s";
    }

    /** Returns the next u32 id; 0 is never used and ids still pending are skipped after wrap-around. */
    int allocate() {
        int id;
        do {
            id = next++;
        } while (id == 0 || pending.containsKey(id));
        return id;
    }

    /**
     * Awaits the answer of {@code s} to {@code id}.
     *
     * @param subject what the answer must name, compared by {@link #complete}; empty when unchecked
     * @param now bridge clock ms
     */
    CompletableFuture<T> track(Session s, int id, String subject, long now) {
        CompletableFuture<T> f = new CompletableFuture<>();
        pending.put(id, new Entry<>(s, subject, f, now + timeoutMs));
        return f;
    }

    /** Completes the answer to {@code id}; answers from another session or naming another subject are ignored. */
    void complete(int id, Session s, String subject, T value) {
        Entry<T> e = pending.get(id);
        if (e == null || e.session() != s || !e.subject().equals(subject)) {
            return;
        }
        pending.remove(id);
        e.future().complete(value);
    }

    /** Fails the answers whose deadline has passed. */
    void expire(long now) {
        if (!pending.isEmpty()) {
            fail(e -> now >= e.deadlineMs(), "timeout", timeoutMessage);
        }
    }

    /** Fails the answers of a session that ended or was superseded. */
    void sessionEnded(Session s) {
        if (!pending.isEmpty()) {
            fail(e -> e.session() == s, "disconnected", s.displayName + " disconnected");
        }
    }

    private void fail(Predicate<Entry<T>> which, String code, String message) {
        List<Entry<T>> gone = new ArrayList<>();
        Iterator<Entry<T>> it = pending.values().iterator();
        while (it.hasNext()) {
            Entry<T> e = it.next();
            if (which.test(e)) {
                it.remove();
                gone.add(e);
            }
        }
        for (Entry<T> e : gone) {
            e.future().completeExceptionally(new BridgeException(code, message));
        }
    }
}
