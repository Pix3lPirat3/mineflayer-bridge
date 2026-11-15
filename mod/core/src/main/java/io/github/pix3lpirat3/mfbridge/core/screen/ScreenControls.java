package io.github.pix3lpirat3.mfbridge.core.screen;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Declared screens as the UI sees them. The UI polls {@link
 * #requestedOpen} to open what a bot asked for and {@link #isOpen} to close what a bot closed, and
 * reports what the player opens and closes, so the owners hear {@code screen.open} and {@code
 * screen.close}. Several screens can be open at once: a child opened from a parent keeps the parent
 * open underneath.
 */
@ClientThread
public interface ScreenControls {

    /**
     * Returns the oldest screen a bot asked to open that the player may see, without removing it.
     *
     * @return its object key, or null
     */
    @Nullable String requestedOpen();

    /**
     * Returns whether the player asked for what this screen's owner opens: they ran a command,
     * action, button or menu entry of that owner within the last few seconds. Only then may the
     * requested screen open over another screen.
     *
     * @param screenKey object key of a {@code core:screen}
     */
    boolean askedFor(String screenKey);

    /**
     * Returns whether a screen is open and its owner can still be reached.
     *
     * @param screenKey object key of a {@code core:screen}
     * @return false once the owner closed it, left, detached or removed it
     */
    boolean isOpen(String screenKey);

    /**
     * Records that the player opened a screen. Tells the owner with {@code screen.open} unless the owner
     * asked for it.
     *
     * @param screenKey object key of a {@code core:screen}
     * @throws IllegalArgumentException when no attached session declares it
     */
    void opened(String screenKey);

    /**
     * Records that the player closed a screen and tells its owner with {@code screen.close}. Closing a
     * screen that is not open does nothing. Closing one the owner had opened counts as dismissing
     * it: for a while the owner may only open what the player asks for.
     *
     * @param screenKey object key of a {@code core:screen}
     */
    void closed(String screenKey);

    /**
     * Sends a click or a changed value to the screen's owner as {@code screen.event}.
     *
     * @param screenKey object key of a {@code core:screen}
     * @param item the item id
     * @param value the new value of a toggle ({@code Boolean}) or cycle ({@code Long} index), or null for a click
     * @return completes with the owner's {@code invocation.result}, or fails as {@code invokeAction} does
     */
    CompletableFuture<InvocationResult> event(String screenKey, String item, @Nullable Object value);

    /**
     * Returns the screen the primary menu key opens: the player's pick while an
     * attached session still declares it with {@code home: menu}.
     *
     * @return its object key, or null for Bridge home
     */
    @Nullable String primaryMenu();

    /**
     * Returns the player's saved pick, whether or not its owner is around.
     *
     * @return the object key, or null for Bridge home
     */
    @Nullable String primaryMenuChoice();

    /**
     * Saves the player's pick for the primary menu.
     *
     * @param screenKey object key of a {@code core:screen} with {@code home: menu}, or null for Bridge home
     */
    void setPrimaryMenu(@Nullable String screenKey);
}
