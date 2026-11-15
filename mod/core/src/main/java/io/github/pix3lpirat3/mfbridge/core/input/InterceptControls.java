package io.github.pix3lpirat3.mfbridge.core.input;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.ApprovalDecision;
import io.github.pix3lpirat3.mfbridge.core.decl.InterceptDecl;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.TargetSpec;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Interaction intercepts, under the {@code input.intercept} permission. Bots
 * declare {@code core:intercept} rules; before vanilla sends any packet for a real interaction the
 * client asks {@link #matching} which rules apply, then calls {@link #dispatch} to tell the bots and
 * learn whether vanilla must stop.
 *
 * <p>Rules run in priority order, {@code highest} first, then {@code high}, {@code normal}, {@code
 * low} and {@code lowest}, with {@code monitor} always last; ties go by key. The
 * first {@code cancel} rule whose bot holds the cancel permission cancels the interaction, and its
 * bot answers with {@code invocation.result}; every other matching rule observes. Rules after the
 * cancelling one hear {@code cancelled = true}. Without the permission a cancel rule only observes.
 *
 * <p>The cancel permission is granted per bot identity. It is on by default for bots of local
 * devices (same machine) and off for bots of remote devices, which are asked once through the
 * batched permission prompt ({@code DeviceControls#permissionBatch}). An explicit choice is saved in
 * the player preferences and wins over the default. Client thread only.
 */
@ClientThread
public interface InterceptControls {
    /** Input name of attack (left click). */
    String PRIMARY = "primary";
    /** Input name of use (right click). */
    String SECONDARY = "secondary";

    /**
     * Returns the rules of attached bots that apply to an interaction, in run order. Empty while
     * paused.
     *
     * @param input {@link #PRIMARY} or {@link #SECONDARY}
     * @param modifiers modifier keys held: bit 0 shift, bit 1 control, bit 2 alt; a rule with a
     *     modifier needs that key held, a rule without one matches regardless
     * @param target whether the block or entity of the interaction matches a rule's target spec
     * @return the matching rules, sorted. Immutable.
     */
    List<InterceptDecl> matching(String input, int modifiers, Predicate<TargetSpec> target);

    /**
     * Sends {@code intercept.event} to the owner of every rule, in order, and decides whether the
     * interaction is cancelled. Rules whose owner is gone or detached are skipped.
     *
     * @param rules rules from {@link #matching}
     * @param targets the target as each rule's declared target type sees it, such as {@code entity} or
     *     {@code bot} for the same player entity; rules whose type resolves to null are skipped
     * @return what happened
     */
    InterceptOutcome dispatch(List<InterceptDecl> rules, Function<String, @Nullable Target> targets);

    /**
     * Dispatches with one target for every rule.
     *
     * @param rules rules from {@link #matching}
     * @param target the block or entity the player interacted with
     * @return what happened
     */
    default InterceptOutcome dispatch(final List<InterceptDecl> rules, final Target target) {
        return dispatch(rules, type -> target);
    }

    /**
     * Returns whether every intercept is paused by the player.
     *
     * @return true while paused
     */
    boolean paused();

    /**
     * Pauses or resumes every intercept; paused rules neither hear nor cancel interactions. Not saved.
     *
     * @param paused the new state
     */
    void setPaused(boolean paused);

    /**
     * Returns the saved choice for a bot's cancel permission.
     *
     * @param identity the bot identity
     * @return true or false, or null when the player has not decided
     */
    @Nullable Boolean cancelPermission(String identity);

    /**
     * Saves or clears the choice for a bot's cancel permission.
     *
     * @param identity the bot identity
     * @param allowed the choice, or null to forget it, so the bot is asked again
     */
    void setCancelPermission(String identity, @Nullable Boolean allowed);

    /**
     * Returns whether the cancel rules of a bot may cancel right now: a saved choice, else on when
     * the bot's live session belongs to a local device, else an answer to the prompt in this run.
     *
     * @param identity the bot identity, or null for a controller, which never may
     * @return true when cancel rules of that bot take effect
     */
    boolean mayCancel(@Nullable String identity);

    /**
     * Records the answer to the permission prompt: {@link ApprovalDecision#ALLOW} saves a grant,
     * {@link ApprovalDecision#ALLOW_ONCE} grants until the game restarts, and {@link
     * ApprovalDecision#DENY} refuses until the game restarts without asking again.
     *
     * @param identity the bot identity
     * @param decision the answer
     */
    void answerPermission(String identity, ApprovalDecision decision);
}
