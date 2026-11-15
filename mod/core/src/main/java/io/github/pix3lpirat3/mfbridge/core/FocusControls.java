package io.github.pix3lpirat3.mfbridge.core;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import org.jspecify.annotations.Nullable;

/**
 * The focused bot. At most one attached bot is focused. Focus stays
 * where it is while that bot stays attached; when it leaves, or while none is focused, the first
 * attached bot in owner order takes it, so a newly connected bot never steals it. Its sessions get {@code
 * interest.focused = true}, its HUD panels and boss bars render in full and {@code bot-selected}
 * input filters to it. Client thread only.
 */
@ClientThread
public interface FocusControls {
    /** Shortest time between two honoured {@code focus.request} messages of one bot, ms. */
    long REQUEST_INTERVAL_MS = 5_000L;

    /** Returns the focused bot's owner, {@code bot:<identity>}, or null when no bot is attached. */
    @Nullable String focused();

    /**
     * Focuses a bot.
     *
     * @param owner {@code bot:<identity>} of an attached bot; other values are ignored
     */
    void focus(String owner);

    /**
     * Moves focus to the next or previous attached bot in owner order, wrapping around.
     *
     * @param step 1 for the next bot, -1 for the previous one
     */
    void cycle(int step);

    /** Returns whether {@code focus.request} from bots may move focus; on by default. */
    boolean allowBotRequests();

    /** Sets whether {@code focus.request} from bots may move focus. */
    void setAllowBotRequests(boolean allow);

    /** Returns a counter that increases whenever the focused bot changes. */
    long version();
}
