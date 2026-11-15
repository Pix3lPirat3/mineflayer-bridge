package io.github.pix3lpirat3.mfbridge.core.command;

import io.github.pix3lpirat3.mfbridge.protocol.core.Suggestion;
import java.util.List;

/**
 * A client-resolved completion source, registered by name with {@link
 * CommandControls#registerSource}: {@code entities}, {@code blocks}, {@code block_ids}, {@code
 * item_ids}, {@code sounds}, {@code particles}, {@code enchantments}, {@code players}, {@code bots},
 * {@code zones}, {@code points} or {@code waypoints}. Called on the client thread while the player
 * types, so it must be fast and must not block.
 */
@FunctionalInterface
public interface CompletionSource {
    /**
     * Suggests values for an argument.
     *
     * @param query the argument, what was typed and the completion spec ({@code near}, {@code
     *     radius}, {@code filter}, {@code limit})
     * @return suggestions that match the typed input, at most {@code query.spec().limit()}, in the
     *     order to show them. The source filters by the input itself, so it may match display
     *     labels as well as the inserted text.
     */
    List<Suggestion> suggest(CompletionQuery query);
}
