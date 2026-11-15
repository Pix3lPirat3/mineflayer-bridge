package io.github.pix3lpirat3.mfbridge.core.input;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickResult;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The input request queue. Picks,
 * prompts, forms and world menus from bots queue in arrival order and at most one is active; an urgent pick goes to the front of the
 * queue without displacing the active request. {@link InputChanged} is posted when the active
 * request or the queue length changes.
 *
 * <p>Resolving or cancelling an id that is no longer pending (it timed out, the bot cancelled it or
 * its session ended) is ignored, because the UI can race with those events. Answers are sent as
 * responses to the original request; they reach a bot whose connection dropped once it resumes.
 * Client thread only.
 */
@ClientThread
public interface InputControls {
    /** Returns the active request, or null when none is pending. */
    @Nullable InputRequest active();

    /** Returns the number of requests waiting behind the active one. */
    int queued();

    /** Returns every pending request: the active one first, then the waiting ones in queue order. Immutable. */
    List<InputRequest> pending();

    /**
     * Answers a pick with {@code pick.result}.
     *
     * @throws IllegalArgumentException when the id is a prompt or form, or the result variant does
     *     not match the requested pick kind
     */
    void resolvePick(long id, PickResult result);

    /**
     * Answers a prompt with {@code prompt.result}. A confirm takes a {@code Boolean}, a choice one of
     * the offered strings, a text prompt a {@code String} and a number prompt a {@code Number}; null
     * answers with no value.
     *
     * @throws IllegalArgumentException when the id is a pick or form, or the value does not fit the
     *     prompt kind
     */
    default void resolvePrompt(long id, @Nullable Object value) {
        resolvePrompt(id, value, null);
    }

    /**
     * Answers a prompt with {@code prompt.result}, reporting the surface that answered it, so the
     * bot knows which surface was used after any fallback.
     * Values are checked as in {@link #resolvePrompt(long, Object)}.
     *
     * @param surface {@code hud}, {@code dialog}, {@code anvil}, {@code sign}, {@code book}, {@code
     *     chat}, {@code world}, {@code stonecutter}, {@code enchanting}, {@code merchant} or {@code
     *     crafting}; null when unknown, in which case the surface last reported with {@link
     *     #surfaceShown} is sent. Answers from the last four are checked by surface rather than by
     *     kind: an option id (enchanting also takes an option index), an offer id that is not
     *     disabled, or a list of nine item ids or nulls from the palette.
     * @throws IllegalArgumentException when the id is a pick, form or world menu, or the value does
     *     not fit the prompt kind
     */
    void resolvePrompt(long id, @Nullable Object value, @Nullable String surface);

    /**
     * Records the surface a prompt is being shown on, so a timeout can report it in {@code
     * prompt.result}. Ignored for ids that are not pending prompts.
     *
     * @param surface the surface name, as for {@link #resolvePrompt(long, Object, String)}
     */
    void surfaceShown(long id, String surface);

    /**
     * Answers a world menu with {@code world-menu.result}.
     *
     * @param entryId id of one of the request's entries
     * @throws IllegalArgumentException when the id is not a world menu, or no entry has that id
     */
    void resolveWorldMenu(long id, String entryId);

    /**
     * Submits a form with {@code form.result}. Missing fields get their declared default when they
     * have one.
     *
     * @param values field id to value: {@code String} for text, item, zone and select fields, {@code
     *     Number} for number and slider fields, {@code Boolean} for toggles, a list of strings for
     *     multi-selects, and a block position ({@code Vec3i}, a list of three integers or a map with
     *     integer {@code x}, {@code y}, {@code z}) for block-pos fields, sent as a map
     * @throws IllegalArgumentException when the id is a pick or prompt, or a value breaks its field's
     *     constraints (unknown field, wrong type, out of range, not an option, required but missing)
     */
    void resolveForm(long id, Map<String, Object> values);

    /**
     * Answers a form with one of its extra buttons instead of submitting it: {@code form.result}
     * carries the button and no values.
     *
     * @param id the input queue id; ignored when no longer pending
     * @param button the id of one of the form's {@code buttons}
     * @throws IllegalArgumentException when the id is not a form or the form has no such button
     */
    void pressFormButton(long id, String button);

    /**
     * The player dismissed the request (Esc, the home key, a close button): the requester gets error
     * {@code cancelled}, and for a while its sessions may only open what the player asks for;
     * anything else is refused with {@code cooldown}.
     */
    void cancel(long id);

    /**
     * Returns whether the player asked for this request: they ran a command, action, button or menu
     * entry of its owner, or answered one of its requests, within the last few seconds. Only such a
     * request may open over another screen.
     *
     * @param id the input queue id; false when no longer pending
     */
    boolean askedFor(long id);

    /**
     * Rejects a request the client cannot show, such as a crafting or merchant prompt whose surface is
     * unavailable: the requester gets error {@code unsupported}.
     *
     * @param id the input queue id; ignored when no longer pending
     * @param message why, for the bot
     */
    void unsupported(long id, String message);
}
