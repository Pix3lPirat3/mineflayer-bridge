package io.github.pix3lpirat3.mfbridge.core.history;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjEditResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpBody;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Undo and redo of the player's edits. Each recording method performs one
 * write and records it as one history entry holding the write and its inverse; an edit of a bot's
 * object is recorded once the bot accepts the proposal. Recording a new entry clears the redo
 * entries.
 *
 * <p>There is one history per world store (the player's current server, or the global store on the
 * title screen) holding at most {@link #DEFAULT_CAPACITY} entries; the oldest fall off. It is saved
 * with the store and loaded again when the player returns to that server.
 *
 * <p>Undo and redo of a shared object check its revision first: when someone else changed the
 * object since, they fail with {@code conflict} and the stale entry is dropped. Undo and redo of a
 * bot's object propose the inverse edit to the bot and complete with its answer.
 *
 * <p>Client thread only; futures complete on the client thread.
 */
@ClientThread
public interface HistoryControls {
    /** Entries kept per world store. */
    int DEFAULT_CAPACITY = 500;

    /**
     * Applies a shared op for the player and records it.
     *
     * @param label what the edit did, for a history list, for example "Move zone"
     * @throws io.github.pix3lpirat3.mfbridge.api.BridgeException as {@code BridgeCore.sharedOp}; nothing
     *     is recorded then
     */
    SharedOpResult sharedOp(String label, SharedOpBody op);

    /**
     * Writes a player object and records it.
     *
     * @param data kind data encoded with the kind's codec
     * @throws IllegalArgumentException as {@code BridgeCore.putPlayerObject}; nothing is recorded then
     */
    void putPlayerObject(String label, String key, KindType<?> kind, @Nullable String layer, Scope scope,
            @Nullable WorldKey world, byte[] data);

    /**
     * Removes a player object and records it.
     *
     * @return whether it existed; nothing is recorded when it did not
     */
    boolean removePlayerObject(String label, String key);

    /**
     * Proposes an edit of a bot-owned object and records it once the bot accepts.
     *
     * @param fields MessagePack map from field index to proposed value
     * @return the bot's answer, as {@code BridgeCore.proposeEdit}
     */
    CompletableFuture<ObjEditResult> proposeEdit(String label, String key, long baseRev, byte[] fields);

    /**
     * Undoes the newest applied entry.
     *
     * @return completes with the entry, now undone; fails with {@code BridgeException} {@code
     *     unsupported} when there is nothing to undo or an undo is still waiting for a bot, {@code
     *     conflict} when the object changed since, or the bot's error or rejection ({@code cancelled})
     */
    CompletableFuture<HistoryEntry> undo();

    /**
     * Redoes the newest undone entry.
     *
     * @return as {@link #undo()}
     */
    CompletableFuture<HistoryEntry> redo();

    /** Returns whether {@link #undo()} has an entry to undo. */
    boolean canUndo();

    /** Returns whether {@link #redo()} has an entry to redo. */
    boolean canRedo();

    /**
     * Returns the history for a history UI: applied entries oldest first, then undone entries, the next
     * one to redo first.
     *
     * @return an immutable copy
     */
    List<HistoryEntry> entries();

    /** Returns a counter that changes whenever the entries change, for cheap UI refresh checks. */
    long version();
}
