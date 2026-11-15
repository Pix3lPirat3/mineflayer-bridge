package io.github.pix3lpirat3.mfbridge.core.container;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerAck;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotClick;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotEntry;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * The player's side of container views. At most one view is
 * open at a time. Bots ask for views to open with {@code container.open}; those requests queue in
 * arrival order until the client opens them, and lapse when the view or its owner goes away. Only
 * views of attached sessions can be requested or opened, and world-scoped views only while the
 * player is in their world.
 *
 * <p>The screen that shows a view is the client's own and never a vanilla container menu, so slot
 * gestures reach the bot as {@code container.input} and never as container packets. Client thread
 * only.
 */
@ClientThread
public interface ContainerControls {
    /** How long {@link #input} waits for {@code container.ack}, in ms. */
    long ACK_TIMEOUT_MS = 10_000;

    /**
     * Returns the oldest view a bot asked to open that the client has not opened yet.
     *
     * @return the view key, or null when no request is waiting
     */
    @Nullable String requestedOpen();

    /**
     * Returns the view the client reported open.
     *
     * @return the view key, or null when no view is open
     */
    @Nullable String open();

    /**
     * Reports that the client opened a view. A view the bot did not ask for is announced to its owner
     * with {@code container.open}, so the bot knows the player is looking at it; a requested view is
     * not echoed back. Any other open view is closed first as by {@link #closed}.
     *
     * @param viewKey full key of a declared {@code core:container-view}
     * @throws IllegalArgumentException when no attached session declares that view
     */
    void opened(String viewKey);

    /**
     * Reports that the player closed a view, or dismissed a request without opening it; the owner
     * gets {@code container.close}. Ignored for views that are neither open nor requested. Closing a
     * view the owner had opened counts as dismissing it: for a while the owner may only open what the
     * player asks for.
     *
     * @param viewKey full key of the view
     */
    void closed(String viewKey);

    /**
     * Returns whether the player asked for what this view's owner opens: they ran a command, action,
     * button or menu entry of that owner within the last few seconds. Only then may the requested
     * view open over another screen.
     *
     * @param viewKey full key of the view
     */
    boolean askedFor(String viewKey);

    /**
     * Sends a slot gesture on the open view as {@code container.input} with a fresh input id.
     *
     * @param viewKey the open view
     * @param slot slot index in the view's layout, or {@code -999} for a click outside the grid
     * @param click the gesture
     * @param hotbar the hotbar slot 0 to 8 for {@link SlotClick#NUMBER}, else null
     * @return completes on the client thread with the bot's {@code container.ack}, or fails with a
     *     {@code BridgeException}: {@code invalid_message} (the view is not open, or {@code hotbar}
     *     does not fit the gesture), {@code not_attached}, {@code disconnected} (no owner, or its
     *     session ended first) or {@code timeout} (no ack within {@link #ACK_TIMEOUT_MS})
     */
    default CompletableFuture<ContainerAck> input(String viewKey, int slot, SlotClick click, @Nullable Integer hotbar) {
        return input(viewKey, slot, click, hotbar, null, null);
    }

    /**
     * Sends a slot gesture as {@link #input(String, int, SlotClick, Integer)} does, with the move an
     * {@code ask} slot needs confirmed: the mod already applied it to the
     * view's storage and rolls it back when the ack says {@code ok = false}.
     *
     * @param moved the stack the player is moving, with the count that moves, or null
     * @param fromSlot where {@code moved} came from: a view slot, or -1 for the cursor; null with
     *     {@code moved} null
     * @return as for {@link #input(String, int, SlotClick, Integer)}
     */
    CompletableFuture<ContainerAck> input(String viewKey, int slot, SlotClick click, @Nullable Integer hotbar,
            @Nullable SlotItem moved, @Nullable Integer fromSlot);

    /**
     * Reports the contents of the view's storage slots after a change, as {@code container.storage}
     * (storage slots are virtual, so the bot sees each change). Dropped when
     * the view is not open or its owner is gone or detached.
     *
     * @param viewKey the open view
     * @param slots every storage slot of the view, empty ones as {@code minecraft:air} with count 0
     */
    void storageChanged(String viewKey, List<SlotEntry> slots);
}
