package io.github.pix3lpirat3.mfbridge.core;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Read access to bot presence, plus forgetting offline bots. Client thread only. */
@ClientThread
public interface PresenceView {
    /** Returns every known bot identity, sorted by identity. The list is an immutable copy. */
    List<Presence> all();

    /** Returns the presence of one identity, or null when unknown. */
    @Nullable Presence get(String identity);

    /**
     * Forgets an offline bot.
     *
     * @return false when the identity is unknown or still connected
     */
    boolean forget(String identity);
}
