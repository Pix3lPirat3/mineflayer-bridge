package io.github.pix3lpirat3.mfbridge.common.ui.screen;

import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import org.jspecify.annotations.Nullable;

/**
 * Where a prompt screen sends its answer: the bot prompt queue, or a local editor that reuses the
 * prompt surfaces for Shift+click editing. Client thread only.
 */
public interface PromptAnswers {
    /**
     * Returns the prompt being answered; a screen closes once this no longer is its prompt.
     *
     * @return the active prompt, or null
     */
    @Nullable PromptInput active();

    /**
     * Answers the active prompt.
     *
     * @param value a {@code Boolean}, a choice, a {@code String} or a {@code Number}, matching the kind
     */
    void answer(Object value);

    /**
     * Answers the active prompt, reporting the surface that took the answer.
     *
     * @param value the answer
     * @param surface the surface's wire name, for example {@code anvil}
     */
    default void answer(final Object value, final String surface) {
        answer(value);
    }

    /**
     * Returns the time left before the prompt times out.
     *
     * @return milliseconds, or {@link Long#MAX_VALUE} for prompts without a deadline
     */
    long remainingMs();
}
